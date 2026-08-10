# stock-select

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
mvn spring-boot:run
```

## Usage

```bash
curl "http://localhost:8080/api/screen/jade-lizard/AAPL"
```

Use the bare ticker (`AAPL`, not `AAPL.US`) — MarketData.app rejects
exchange-suffixed symbols outright.

Returns a JSON list of `TradeCandidate`s (empty if no expiration/strike
combination satisfies the strategy's construction rules).

## Adding a new strategy

Implement `TradeStrategy` (see `com.stockselect.strategy.jadelizard.JadeLizardStrategy`
for reference), register it as a `@Component`, and it becomes selectable via
`/api/screen/{strategy-name}/{symbol}` automatically.
