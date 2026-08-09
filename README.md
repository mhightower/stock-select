# stock-select

Spring Boot service that pulls stock quotes and option chains from the
[EODHD API](https://eodhd.com/) and screens them for options-selling trade
candidates, starting with the [Jade Lizard](https://eodhd.com/) strategy
(short call + short put vertical spread).

## Setup

1. Get an EODHD API token (a US options data subscription is required for
   the `/api/mp/unicornbay/options/eod` endpoint).
2. Export it before running:

   ```bash
   export EODHD_API_KEY=your-eodhd-api-token
   ```

## Running

```bash
mvn spring-boot:run
```

## Usage

```bash
curl "http://localhost:8080/api/screen/jade-lizard/AAPL"
```

Returns a JSON list of `TradeCandidate`s (empty if no expiration/strike
combination satisfies the strategy's construction rules).

## Adding a new strategy

Implement `TradeStrategy` (see `com.stockselect.strategy.jadelizard.JadeLizardStrategy`
for reference), register it as a `@Component`, and it becomes selectable via
`/api/screen/{strategy-name}/{symbol}` automatically.
