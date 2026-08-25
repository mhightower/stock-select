# marketdata/ — MarketData.app option chain client

`MarketDataClient.getOptionsChain(symbol)` requests a `from`/`to` date
range and maps the response into `OptionContract`.

**Quota is metered per contract returned, not per HTTP call** — confirmed
live (2026-08-21): a pre-batch check showed 9,725/10,000 remaining, and
just 10 sequential `/api/screen/*` calls (one options-chain fetch each,
covering the app's original 1-75 DTE window) against liquid mega-caps
(AAPL, MSFT, NVDA, AMZN, GOOGL, META, TSLA, AMD, JPM, DIS) drained it to 0
— roughly 970 units/call, consistent with each chain response containing
hundreds of contracts. In practice this means **~10 chain screens/day** on
the free tier, not the 100/day the docs list or the 10,000/day the
`x-api-ratelimit-*` headers otherwise suggest — check
`x-api-ratelimit-remaining` (or the `x-api-ratelimit-reset` epoch
timestamp for when it refills) before running more than a couple of
symbols through `/api/screen/*` in one session.

**Mitigation:** the fetch window was narrowed from 1-75 to **25-65 DTE**
(matching the 30-60 DTE band every current strategy actually uses, plus a
small buffer) — cuts the contracts fetched per call without dropping
anything any strategy actually uses. This doesn't eliminate the
quota-per-contract behavior (a vendor characteristic), just reduces its
impact.

**Wire format is parallel arrays** (`OptionsChainResponse`: every field is
a `List`, index `i` across all lists describes one contract) rather than
an array of objects — very different from EODHD's shape, which is the
whole reason the mapping lives in its own client rather than being folded
into a generic "options DTO."

**Timezone:** expiration dates arrive as Unix-epoch seconds and must be
converted using `America/New_York` (not UTC, or the calendar date shifts)
since that's the zone the exchange's 4pm/4:15pm close times are anchored
to.

**Buffer size:** the `marketDataWebClient` bean raises `WebClient`'s
default 256KB in-memory buffer to 10MB in `WebClientConfig` — a real chain
response blows past the default and throws `DataBufferLimitException` at
request time, not at startup.
