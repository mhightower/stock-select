# screening/ — ScreeningService

The only place that blocks on the reactive `EodhdClient`/`MarketDataClient`
calls (`.block()`); everything below it is synchronous. If this ever needs
to be non-blocking end-to-end, that's the seam. It's also where the symbol
gets normalized (uppercased, `.US` suffix stripped) before either client
sees it.

EODHD's quote is treated as optional, not required: it's only ever used
for `underlyingPrice` (a near-real-time number, nicer than MarketData's
own 24h-delayed `underlyingPrice` embedded in every chain contract, but
not something the strategy actually needs to function), so an
`UpstreamApiException` from `EodhdClient.getQuote()` is caught in
`resolveUnderlyingPrice()`, falls back to the first contract's
`underlyingPrice`, and is surfaced as a string in
`ScreeningResult.warnings()` instead of failing the whole request —
unlike a `MarketDataClient` failure, which is NOT caught and still
propagates to `ApiExceptionHandler` as before, since there's no candidate
to build at all without the chain.

`ScreeningResult(candidates, warnings)` is what the endpoint actually
returns (`{"candidates": [...], "warnings": [...]}`), not a bare
`List<TradeCandidate>`.
