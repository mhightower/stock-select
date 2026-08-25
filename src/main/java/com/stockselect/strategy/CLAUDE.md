# strategy/ — trade strategies

The extension point. `OptionContract` (the app's vendor-neutral option
model, populated by whichever client fetched it) and `Quote` live
conceptually here. `TradeStrategy` is the interface every strategy
implements (`name()` + `evaluate(StrategyContext)`). `ScreeningService`
autowires `List<TradeStrategy>` and indexes them by `name()`, so **a new
strategy only needs to exist as a `@Component`** — no registry to update.

`TradeCandidate` is the shared output shape; strategies that don't use a
given leg (e.g. no long put) leave that field null rather than the shape
being subclassed per-strategy. It's a record with a positional
constructor, not a builder — field additions ripple to every construction
site (both strategies, `ScreeningServiceTest`, `ScreeningControllerTest`),
not just the strategy adding the field, as happened when Bear Call Spread
needed `longCallStrike` added alongside the existing `shortCallStrike`.

All prices are per-share, not per-contract (multiply by 100 for actual
dollars on a standard equity contract) — the options-market convention,
and how both vendors already quote bid/ask/mid. `currency` is hardcoded
`"USD"` rather than read from either vendor's response, since neither
actually includes a currency field; correct as long as this app stays
scoped to US-listed equities/options (MarketData.app rejects non-US
symbols outright anyway).

`TosOrderFormatter` builds `TradeCandidate.tosOrderText` — ready-to-paste
order-entry text in TOS order-bar syntax (`VERTICAL`/`CUSTOM` keywords,
`D MMM YY` dates, per-leg `-1`/`+1` quantity signs), one static method per
spread shape (`putVertical`, `callVertical`, `customThreeLeg`) rather than
inferring the shape from which `TradeCandidate` fields are null, since
each strategy already knows its own shape. Built from documented TOS
order-bar conventions, **not verified against a live TOS session** —
test-paste one before trusting it for a real order.

Both strategies apply a liquidity gate (`isLiquid`, duplicated in each
class — each strategy is meant to be self-contained, see below) before any
delta-based selection: a contract needs at least `minOpenInterest` open
interest and a bid-ask spread no wider than `maxBidAskSpreadRatio` of its
mid, or it's excluded entirely — otherwise a contract with a perfect delta
match but no real market (e.g. a 900% wide spread) could get suggested as
tradeable when it isn't.

## `jadelizard/` — Jade Lizard (short call + short put vertical)

`JadeLizardStrategy` picks an expiration inside the configured DTE window
closest to the target DTE, picks the short call/put whose delta is
closest to the configured target delta, picks the long put as the next
strike down from the short put, and only returns a candidate when credit
received is at least `minCreditToWidthRatio × putSpreadWidth` (the
standard Jade Lizard construction rule, configured in `application.yml`
under `strategy.jade-lizard`). `JadeLizardProperties` is a
`@ConfigurationProperties` record — add new tunables there rather than
hardcoding thresholds in the strategy class.

## `bullputspread/` — Bull Put Spread (short put vertical, no call leg)

The simpler reference to read first when adding a new strategy.
`BullPutSpreadStrategy` is a strict subset of `JadeLizardStrategy` with
the call leg removed entirely: pick an expiration, pick the short put by
target delta, pick the long put as the next strike down, require
`minCreditToWidthRatio × width` (default 0.33 — "collect at least a third
of the width," the standard vertical-spread guideline, deliberately
looser than Jade Lizard's 1.0 since there's no naked call premium
cushioning it). `TradeCandidate`'s call-leg and `upsideBreakEven` fields
are left null (a vertical spread has only a downside breakeven).

## `bearcallspread/` — Bear Call Spread (short call vertical, no put leg)

The Bull Put Spread's mirror image: `BearCallSpreadStrategy` picks an
expiration, picks the short call by target delta, picks the long call as
the next strike *up* (the put-side strategies pick the next strike
*down* — this is the one place the mirroring isn't literally identical
code, since a credit spread's protective leg always sits further OTM,
which is a higher strike on the call side and a lower strike on the put
side), and requires `minCreditToWidthRatio × width` (default 0.33, same
guideline as Bull Put Spread).

## Adding a new strategy

1. Add a subpackage under `strategy/` (mirror `bullputspread/` — simpler
   starting point than `jadelizard/`).
2. Implement `TradeStrategy`, return a unique lowercase-hyphenated `name()`.
3. Annotate it `@Component` — `ScreeningService` picks it up automatically
   and it becomes reachable at `/api/screen/{name}/{symbol}`.
4. If it needs tunable thresholds, add a `@ConfigurationProperties` record
   for it (see `JadeLizardProperties`) and a corresponding block in
   `application.yml`.
