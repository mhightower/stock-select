# stock-select

[![CI](https://github.com/mhightower/stock-select/actions/workflows/ci.yml/badge.svg)](https://github.com/mhightower/stock-select/actions/workflows/ci.yml)

Spring Boot service that pulls stock quotes from [EODHD](https://eodhd.com/)
and option chains from [MarketData.app](https://www.marketdata.app/), then
screens them for options-selling trade candidates. Three strategies today:
**Jade Lizard** (short call + short put vertical spread), **Bull Put
Spread** (short put vertical spread only), and **Bear Call Spread**
(short call vertical spread only).

## Setup

1. Get an EODHD API token (used for the stock quote only).
2. Get a MarketData.app API token (used for the options chain — their free
   "Free Forever" plan works, options data is just 24h delayed on it).
3. Export both before running:

   ```bash
   export EODHD_API_KEY=your-eodhd-api-token
   export MARKETDATA_API_KEY=your-marketdata-app-api-token
   ```

## Running

```bash
./mvnw spring-boot:run
```

## Usage

```bash
curl "http://localhost:8080/api/screen/jade-lizard/AAPL"
curl "http://localhost:8080/api/screen/bull-put-spread/AAPL"
curl "http://localhost:8080/api/screen/bear-call-spread/AAPL"
```

Use the bare ticker (`AAPL`, not `AAPL.US`) — MarketData.app rejects
exchange-suffixed symbols outright.

Returns `{"candidates": [...], "warnings": [...]}`. `candidates` is empty
if no expiration/strike combination satisfies the strategy's construction
rules, *or* if the only otherwise-qualifying contracts fail the liquidity
gate (too little open interest, or a bid-ask spread too wide to realistically
fill — see `strategy.*.min-open-interest`/`max-bid-ask-spread-ratio` in
`application.yml`). `warnings` is non-empty if EODHD's quote was
unavailable — the underlying price then falls back to MarketData.app's own
(24h-delayed) price instead of failing the request.

Each candidate also includes `tosOrderText` — order-entry text in
TOS order-bar syntax, ready to paste into TOS's order bar instead
of re-entering each leg by hand. This is built from documented TOS
conventions and hasn't been verified against a live TOS session, so
test-paste one before trusting it for a real order.

## Health check

```bash
curl "http://localhost:8080/health"
```

Reports whether EODHD and MarketData.app are currently reachable, based on
the outcome of the app's own recent real requests to them — it doesn't
make dedicated calls of its own, since both vendors' free tiers are
tightly quota-limited (EODHD: 20/day, MarketData.app: 100/day) and a
health check polled every few seconds would burn through that on its own.
A vendor shows `UNKNOWN` until the app has actually talked to it at least
once.

## Testing

```bash
./mvnw test      # unit tests — isolated, no API keys needed
./mvnw verify    # unit + integration tests — needs EODHD_API_KEY and MARKETDATA_API_KEY set
```

Integration tests (`*ClientIT`) call the real EODHD/MarketData.app APIs and
skip automatically if their key isn't set, so `./mvnw verify` is always safe
to run.

## Docker / Podman

```bash
podman build -t stock-select .
podman run --env-file .env -p 8080:8080 stock-select
```

Or with compose (`podman-compose up` / `docker compose up` — both read the
same `docker-compose.yml`):

```bash
podman-compose up
```

Either way this reuses the same `.env` from the Setup section above — no
separate copy of the API keys to maintain. The image is a multi-stage build
(`eclipse-temurin:26-jdk-jammy` to build the jar with the Maven wrapper,
`eclipse-temurin:26-jre-jammy` to run it) so `podman build` works from a
clean checkout with no local Java install needed.

## Adding a new strategy

Implement `TradeStrategy` (see `com.stockselect.strategy.bullputspread.BullPutSpreadStrategy`
for the simplest reference), register it as a `@Component`, and it becomes
selectable via `/api/screen/{strategy-name}/{symbol}` automatically.

## License

Apache License 2.0 — see [LICENSE](LICENSE). Copyright © 2026 Marcus Hightower.
