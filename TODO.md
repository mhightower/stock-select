# Architecture TODO

Prioritized list from an architecture review (2026-08-18), organized by impact.
Unchecked items are proposals, not commitments — see each item's reasoning
before starting it.

## Critical

- [ ] **Add a caching layer for MarketData.app option chains.** Every screen
      request re-fetches from both vendors even though MarketData's chain data
      is already 24h delayed on the free tier — caching it for 15-60 minutes
      costs nothing in real freshness and would eliminate most of the quota
      exhaustion hit repeatedly during development.
- [ ] **Add connect/response timeouts to both `WebClient` beans**
      (`eodhdWebClient`, `marketDataWebClient` in `WebClientConfig`) — currently
      unbounded, so a hung vendor connection blocks the request thread
      indefinitely instead of failing into the existing clean-error path.
- [ ] **Add rate limiting on `/api/screen/{strategy}/{symbol}`.** Nothing
      currently stops a client (or a bug, or a bot) from burning the entire
      daily vendor quota in a handful of requests.
- [x] **Add a catch-all `@ExceptionHandler(Exception.class)`** in
      `ApiExceptionHandler` so unexpected exceptions also return the app's
      clean JSON error shape instead of falling through to Spring's default.

## Important

- [ ] Add API key authentication to the screening endpoint.
- [x] **Add liquidity filtering (open interest / bid-ask spread width) to
      strategy leg selection** — done, see `JadeLizardStrategy`/
      `BullPutSpreadStrategy`'s `isLiquid` check and
      `strategy.*.min-open-interest` / `strategy.*.max-bid-ask-spread-ratio`
      in `application.yml`.
- [ ] Add an "as of" timestamp to `ScreeningResult` so consumers know how
      stale the data is — matters more once caching lands.
- [x] Add OpenAPI/Swagger docs via `springdoc-openapi`.
- [x] Add a `Dockerfile`.
- [ ] Add input validation on the `symbol` path variable — fail fast with a
      400 instead of round-tripping to vendors for garbage input.

## Lower priority

- [ ] Move `ScreeningService` off `.block()` to be fully reactive end-to-end
      (only matters under real concurrent load; already noted as a known
      seam in `CLAUDE.md`).
- [ ] Externalize `VendorHealthTracker` state if ever scaled to multiple
      instances (currently single-instance, in-memory).
- [ ] Add structured logging / metrics export (Micrometer → Prometheus) and
      request correlation IDs.
- [ ] Add persistence/audit history of past screens.
- [x] Add an explicit "not investment advice" disclaimer — done in
      `README.md` rather than API responses (user's call when this was
      picked up).
