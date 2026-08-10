# stock-select

[![CI](https://github.com/mhightower/stock-select/actions/workflows/ci.yml/badge.svg)](https://github.com/mhightower/stock-select/actions/workflows/ci.yml)

Spring Boot service that pulls stock quotes from [EODHD](https://eodhd.com/)
and option chains from [MarketData.app](https://www.marketdata.app/), then
screens them for options-selling trade candidates, starting with the Jade
Lizard strategy (short call + short put vertical spread).

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
```

Use the bare ticker (`AAPL`, not `AAPL.US`) — MarketData.app rejects
exchange-suffixed symbols outright.

Returns `{"candidates": [...], "warnings": [...]}`. `candidates` is empty
if no expiration/strike combination satisfies the strategy's construction
rules. `warnings` is non-empty if EODHD's quote was unavailable — the
underlying price then falls back to MarketData.app's own (24h-delayed)
price instead of failing the request.

## Testing

```bash
./mvnw test      # unit tests — isolated, no API keys needed
./mvnw verify    # unit + integration tests — needs EODHD_API_KEY and MARKETDATA_API_KEY set
```

Integration tests (`*ClientIT`) call the real EODHD/MarketData.app APIs and
skip automatically if their key isn't set, so `./mvnw verify` is always safe
to run.

## Adding a new strategy

Implement `TradeStrategy` (see `com.stockselect.strategy.jadelizard.JadeLizardStrategy`
for reference), register it as a `@Component`, and it becomes selectable via
`/api/screen/{strategy-name}/{symbol}` automatically.

## License

Apache License 2.0 — see [LICENSE](LICENSE). Copyright © 2026 Marcus Hightower.
