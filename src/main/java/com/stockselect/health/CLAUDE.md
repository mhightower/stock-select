# health/ — vendor health check

`GET /health` (moved off Actuator's default `/actuator/health` via
`management.endpoints.web.base-path: /` in `application.yml`; the
built-in `diskSpace` indicator is also turned off there via
`management.health.diskspace.enabled: false` — irrelevant noise for a
stateless API with no local storage).

`VendorHealthTracker` is passive — it never makes its own vendor calls.
Instead `EodhdClient`/`MarketDataClient` report the outcome of every real
call they already make (`.doOnSuccess(...)` / `.doOnError(UpstreamApiException.class, ...)`
right where they map errors) into the tracker, and `HealthConfig`'s two
`HealthIndicator` beans just read the last-recorded outcome.

**This is deliberate, not a shortcut**: EODHD's free tier is 20
requests/day and MarketData.app's is 100/day (see `/api/user` and the
vendor docs), so a health check that pinged either vendor on its own
schedule — which is how liveness/readiness probes are normally hit, every
few seconds — would exhaust those quotas in minutes and break the app's
actual function. The tradeoff: a vendor shows `UNKNOWN` in `/health` until
the first real request touches it.

EODHD's indicator uses a custom `DEGRADED` status (not `DOWN`) on
failure — a status Spring Boot's default `StatusAggregator` doesn't rank
above `UP`, so it never drags the aggregate `/health` status down, unlike
MarketData.app's indicator which uses the standard `DOWN`. This mirrors
`ScreeningService`'s own EODHD-is-optional/MarketData-is-required
distinction (see `screening/CLAUDE.md`) — MarketData.app doesn't appear to
validate its bearer token at all in practice (confirmed live: a garbage
token still returns `200` with real data), so its `DOWN` path is only
reachable via a real outage or rate limit, not a bad key.
