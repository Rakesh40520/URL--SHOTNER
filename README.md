# Dispatch — URL Shortener (Spring Boot)

A production-shaped URL shortener: Base62 short codes derived from the DB id
(no random-string collision retries), click analytics with paginated
history, optional link expiry with a scheduled cleanup job, custom aliases
with race-safe uniqueness, per-IP rate limiting, thorough request
validation, a global exception handler, OpenAPI docs, and a small themed
frontend that talks to the API.

## Tech stack

- Java 17, Spring Boot 3.3
- Spring Web, Spring Data JPA, Bean Validation
- H2 (file-based, zero setup) by default, PostgreSQL profile for production
  (single instance, or `postgres-ha` for a primary + read-replica setup)
- Redis (optional, `app.redis.enabled=true`) for distributed rate limiting
  and a shared redirect cache
- Kafka (optional, `app.kafka.enabled=true`) for async click tracking
- Optional 2-shard Postgres setup (`postgres-sharded` profile) - see
  "Sharding + multi-region" below
- Actuator + Micrometer (Prometheus format) for metrics/health, always on;
  resilience4j circuit breaker around the Safe Browsing call
- springdoc-openapi (Swagger UI)
- JUnit 5 + Mockito
- Docker / docker-compose
- Vanilla HTML/CSS/JS frontend (no build step)

## Project structure

```
url-shortener/
 ├── src/main/java/com/example/urlshortener/
 │   ├── controller/     UrlController        REST endpoints
 │   ├── service/        UrlShortenerService  business logic
 │   ├── repository/     UrlMappingRepository, UrlClickEventRepository (Spring Data JPA)
 │   ├── entity/         UrlMapping, UrlClickEvent
 │   ├── dto/             request/response DTOs with validation
 │   ├── exception/       custom exceptions + @RestControllerAdvice handler
 │   ├── ratelimit/        RateLimiter interface + InMemoryRateLimiter/RedisRateLimiter
 │   ├── util/            Base62Encoder, ClientIpUtils
 │   ├── cache/            CachedShortUrl (redirect cache-aside payload)
 │   ├── kafka/            ClickEventPublisher/Consumer (async click tracking)
 │   ├── sharding/         ShardResolver/ShardContext/ShardRoutingDataSource
 │   └── config/          CORS config; config/datasource/ = primary+replica (postgres-ha);
 │                         CacheConfig = in-memory/Redis redirect cache
 ├── src/main/resources/
 │   ├── application.yml  H2 (default) + postgres/postgres-ha/postgres-sharded profiles
 │   └── static/          index.html, css/style.css, js/app.js  (frontend)
 ├── src/test/java/...    unit tests (Base62Encoder, RateLimiter, service, routing datasource)
 ├── Dockerfile
 ├── docker-compose.yml         app + single Postgres (+ optional redis/kafka services)
 ├── docker-compose.replica.yml  Postgres primary + read replica (postgres-ha profile)
 ├── docker-compose.shards.yml   two independent Postgres shards (postgres-sharded profile)
 └── pom.xml
```

## Run it

Requires JDK 17+ and Maven (or use the Maven Wrapper if you generate one
with `mvn -N wrapper:wrapper`).

```bash
mvn spring-boot:run
```

Then open **http://localhost:8080** — the frontend is served directly by
Spring Boot from `src/main/resources/static`.

- Swagger UI: http://localhost:8080/swagger-ui.html
- H2 console: http://localhost:8080/h2-console
  (JDBC URL: `jdbc:h2:file:./data/urlshortenerdb`, user `sa`, empty password)

### Run with Docker

```bash
docker compose up --build
```

This starts the app on port 8080 backed by a real Postgres container
(`SPRING_PROFILES_ACTIVE=postgres`).

### Run tests

```bash
mvn test
```

## API

| Method | Path                                | Description                              |
|--------|-------------------------------------|-------------------------------------------|
| POST   | `/api/v1/urls`                      | Create a short URL (rate-limited, Safe Browsing-checked) |
| GET    | `/{shortCode}`                      | 302 redirect to the long URL              |
| GET    | `/api/v1/urls/{shortCode}/stats`    | Click count / expiry / status metadata    |
| GET    | `/api/v1/urls/{shortCode}/clicks`   | Paginated click history                   |
| POST   | `/api/v1/urls/{shortCode}/report`   | Report a link as abusive (rate-limited, no account needed) |
| POST   | `/api/v1/urls/{shortCode}/disable`  | Owner/management-key: stop a link from resolving |
| POST   | `/api/v1/urls/{shortCode}/enable`   | Owner/management-key: re-enable a disabled/flagged link |

**Create a short URL**

```bash
curl -X POST http://localhost:8080/api/v1/urls \
  -H "Content-Type: application/json" \
  -d '{"longUrl": "https://example.com/some/very/long/path", "expiresInDays": 30}'
```

```json
{
  "shortCode": "1eK7",
  "shortUrl": "http://localhost:8080/1eK7",
  "longUrl": "https://example.com/some/very/long/path",
  "createdAt": "2026-08-16T10:15:30",
  "expiresAt": "2026-09-15T10:15:30"
}
```

Optional fields: `customAlias` (3–20 chars, letters/digits/-/_) and
`expiresInDays`.

**Get paginated click history**

```bash
curl "http://localhost:8080/api/v1/urls/1eK7/clicks?page=0&size=20"
```

Returns a Spring Data `Page<ClickResponse>` — `content` (the click records
for this page), plus `totalElements`, `totalPages`, `number`, `size`, etc.
Never returns a link's entire click history in one response, no matter how
many clicks it has.

## Validation

`POST /api/v1/urls` rejects the request with `400 Bad Request` (and a
field-by-field message) unless:

| Field         | Rule                                                             |
|---------------|-------------------------------------------------------------------|
| `longUrl`     | Required, not blank — rejects `{"longUrl": ""}`                  |
| `longUrl`     | Must start with `http://` or `https://` with a non-empty host    |
| `longUrl`     | At most 2048 characters                                          |
| `longUrl`     | Can't point back at this same service (blocks redirect loops)    |
| `customAlias` | If present: 3–20 chars, letters/digits/hyphen/underscore only    |
| `expiresInDays` | If present: must be a positive number                          |

## Error responses

Every error — validation failure, missing link, expired link, alias
conflict, malformed JSON, rate limit, unexpected exception — comes back as
JSON in the same shape, never a raw stack trace:

```json
{
  "timestamp": "2026-08-17T09:12:03.501",
  "status": 404,
  "error": "Not Found",
  "message": "No URL found for short code: xyz789"
}
```

| Status | When                                                          |
|--------|---------------------------------------------------------------|
| 400    | Invalid/missing URL, bad custom alias, malformed JSON body    |
| 404    | Short code doesn't exist                                      |
| 409    | Custom alias already in use (checked *and* DB-enforced)       |
| 410    | Short code exists but has expired                             |
| 429    | Too many requests from this IP (see Rate limiting, below)     |
| 500    | Unexpected server error                                       |

## Redirect status code

`GET /{shortCode}` returns **302 Found**, not 301/307/308. That's a
deliberate choice, not an oversight — see the doc comment on
`UrlController.redirect()` for the full reasoning, in short: 301 (and 308)
tell browsers to cache the redirect permanently and skip the server on
future visits, which would silently break click tracking and mean an
expired or edited link could never change behavior for a browser that
already cached it. 302 (like 307) is not cached that way, so every click
still reaches this server.

## Preventing custom-code collisions

```
Request custom code
        │
        ▼
Does it already exist? ──Yes──▶ 409 Conflict
        │
        No
        ▼
   Create URL
```

This is enforced **twice**: an application-level `existsByShortCode` check
(fast path, clean error message) *and* a `UNIQUE` constraint on the
`shortCode` column at the database level, which is the real source of
truth. If two requests for the same alias race each other and both pass
the first check, the second insert fails the DB constraint, and the
service catches that (`DataIntegrityViolationException`) and turns it into
the same `409` response instead of a raw `500`.

## Rate limiting

`POST /api/v1/urls` is throttled per client IP — by default, **20 requests
per 60-second window** (configurable via `app.rate-limit.max-requests` and
`app.rate-limit.window-seconds` in `application.yml`, or the matching
`APP_RATE_LIMIT_MAX_REQUESTS` / `APP_RATE_LIMIT_WINDOW_SECONDS` environment
variables). Exceeding it returns `429 Too Many Requests`:

```json
{
  "status": 429,
  "error": "Too Many Requests",
  "message": "Too many requests - please slow down and try again shortly."
}
```

Implementation is a simple in-memory fixed-window counter (see
`ratelimit/RateLimiter.java`) — correct for a single instance, which fits
this project. It is **not distributed**: running multiple instances behind
a load balancer would give each instance its own counter. At real scale
you'd back this with a shared store instead (Bucket4j + Redis, or a plain
counter with TTL in Redis) so every instance agrees on the count.

## Abuse protection

Beyond rate limiting, three things work together to keep this from being a
useful tool for phishing/malware/spam distribution:

**1. Safe Browsing checks at creation time.** If `app.safe-browsing.enabled`
is `true` (off by default — get a free key at
https://developers.google.com/safe-browsing/v4/get-started and set
`SAFE_BROWSING_API_KEY`), every `POST /api/v1/urls` looks the destination up
against Google's Safe Browsing v4 API before creating the link. An actual
match (malware, social engineering/phishing, unwanted software, or a
potentially harmful app) is always rejected with `400 Bad Request`,
regardless of config. If the API call itself fails or times out,
`app.safe-browsing.fail-open` (default `true`) decides whether creation
proceeds anyway (an outage in a third-party API shouldn't take link
creation down with it) or is blocked until Safe Browsing is reachable
again.

**2. A scheduled re-check for links that turn bad later.** A destination
can become malicious well after its short link was created and shared -
`UrlShortenerService.recheckActiveLinksForThreats` re-checks every
currently-`ACTIVE` link's destination against Safe Browsing on a timer
(`app.safe-browsing.recheck-interval-ms`, default every 6 hours), in
batches sized to the API's own per-request cap. Anything that now matches
moves to `FLAGGED`, which blocks the redirect (`403 Forbidden`) the same
way a manual disable does.

**3. Public reporting, for links Safe Browsing doesn't catch** (spam,
scams, and other abuse that isn't a malware/phishing signature).
`POST /api/v1/urls/{shortCode}/report` takes no account and no management
key — anyone who was sent a bad link can flag it — but is tightly
rate-limited per IP (`app.report-rate-limit`, default 5/minute) so it can't
itself be used to mass-flag someone else's legitimate link. Crossing
`app.report.auto-disable-threshold` (default 3) reports auto-flags the
link; a false positive is always recoverable, since the owner (or
management-key holder) can re-enable it via
`POST /api/v1/urls/{shortCode}/enable`.

A link's owner can also disable/re-enable their own link at any time via
`POST /api/v1/urls/{shortCode}/disable` and `.../enable` - same
ownership-or-management-key check as `/stats` and `/clicks`. "My
Dispatches" shows `Flagged`/`Disabled` links distinctly from `Active`/
`Expired` ones, with the reason as a tooltip.

## Scaling reads: primary + read-replica Postgres

The `postgres-ha` profile splits traffic across a primary (writes) and a
read replica (reads), instead of one Postgres instance handling everything:

```
docker compose -f docker-compose.replica.yml up -d
java -jar url-shortener.jar --spring.profiles.active=postgres-ha
```

**How the routing decision gets made.** Every service method is annotated
`@Transactional` (writes) or `@Transactional(readOnly = true)` (reads) -
using Spring's own annotation, not `jakarta.transaction.Transactional`,
because only Spring's version carries a `readOnly` flag at all.
`ReplicationRoutingDataSource` reads that flag straight off
`TransactionSynchronizationManager` and picks the primary or the replica
accordingly - see `config/datasource/`. There's no second place this is
configured and no guessing at the SQL itself; the routing is a direct
consequence of the `readOnly` annotation already on each method, so it
can't quietly drift out of sync with what a method actually does.

**Why this needed fixing first.** The four read methods (`getStats`,
`getClicks`, `getMyUrls`, `getMyUrlsSummary`) already had `readOnly = true`
on them before this - but with the `jakarta.transaction.Transactional`
import, which has no `readOnly` attribute at all. That's not just
inert; `jakarta.transaction.Transactional(readOnly = true)` doesn't
compile. Wiring up replica routing forced switching every service to
Spring's `@Transactional`, which fixed a real (if latent) build break as a
side effect.

**The trade-off this introduces.** Replication is asynchronous, so a
replica can be momentarily behind the primary. Every write path (`shorten`,
`resolve`'s click-count update, `report`, `disable`/`enable`, `deleteUrl`)
stays on the primary; nothing in this app ever needs a read to see its own
immediately-preceding write, so read-after-write consistency isn't a
concern here in practice - but it's exactly the kind of thing worth being
able to explain if asked, since it's the whole reason real systems don't
get to add a read replica for free.

**Every other profile is untouched.** `DataSourceConfig` only activates
under `postgres-ha` (`@Profile("postgres-ha")`) - the default H2 profile
and the single-instance `postgres` profile still use Spring Boot's normal
auto-configured DataSource exactly as before.

## Async click tracking (Kafka)

Off by default (`app.kafka.enabled=false`) - the redirect path writes the
click synchronously, same as always. Flip it on and `resolve()` instead
publishes a small `ClickEventMessage` to the `url-clicks` topic and returns
immediately; `kafka/ClickEventConsumer` does the actual `UPDATE` + click-log
insert on its own thread, so a redirect response no longer waits on any DB
write at all - the only thing on the hot path is the (already-async, by
design) Kafka send.

```
docker compose up -d kafka
# then run the app with APP_KAFKA_ENABLED=true / app.kafka.enabled=true
```

Messages are keyed by `shortCode`, so every click for one link stays in
partition order. The producer side (`ClickEventPublisher`/`KafkaTemplate`)
is always registered - like the Redis beans elsewhere, it's lazy and inert
until actually sent to - but the consumer (`@KafkaListener`) is
`@ConditionalOnProperty`-gated on the same flag, since a listener container
actively connects to a broker on startup rather than waiting to be used.

## Distributed rate limiting & redirect caching (Redis)

Two things switch over together behind one flag, `app.redis.enabled`
(default `false`):

```
docker compose up -d redis   # or bring up the whole stack, redis included
# then run the app with APP_REDIS_ENABLED=true / app.redis.enabled=true
```

**Rate limiting.** `InMemoryRateLimiter` (the default) tracks counts
per-instance - fine for one instance, silently wrong for more than one,
since each instance enforces its own separate budget (the effective limit
becomes `maxRequests * instanceCount`). `RedisRateLimiter` fixes that by
keeping the counter in Redis instead, incremented via a small Lua script
(`INCR` + `EXPIRE` on first hit) so the increment-and-set-TTL sequence is
atomic - without that, two concurrent requests could both `INCR` before
either sets the expiry, or a crash between the two could leave a key that
never expires. `RateLimitFilter` doesn't change at all between the two
modes; it only ever depends on the `RateLimiter` interface, and exactly one
of `RateLimitConfig`/`RedisRateLimitConfig` is active at a time
(`@ConditionalOnProperty` on `app.redis.enabled`).

**Redirect caching.** `UrlShortenerService.resolve()` checks a cache-aside
`shortUrls` cache before touching Postgres at all - a hit skips the SELECT
entirely and goes straight to recording the click via a single atomic bulk
`UPDATE` (`UrlMappingRepository.incrementClickCount`), not a read-modify-write
on a loaded entity. Only what's needed for a redirect is cached
(`CachedShortUrl`: `longUrl` + `expiresAt`), never the full `UrlMapping` -
partly for size, partly because its lazy `User` association can't safely
round-trip through Redis's JSON serializer. Only a verified `ACTIVE`,
not-yet-expired link is ever cached; disabling, reporting past the
auto-flag threshold, deleting, or the scheduled Safe Browsing re-check
flagging a link all evict it immediately (`evictFromCache`), so a blocked
link can't keep serving redirects out of a stale cache entry. A
`short-url-ttl-seconds` TTL (default 10 minutes, Redis mode only) is a
backstop on top of that explicit eviction, not the primary mechanism.

Same on/off pattern as everything else here: `app.redis.enabled=false`
uses `ConcurrentMapCacheManager` (in-memory, per-instance, zero setup);
`=true` switches to a real `RedisCacheManager` shared by every instance, so
an eviction on one instance is immediately visible to all of them - which
matters specifically because disable/report/delete need every instance to
stop serving a blocked link right away, not just the one that handled the
disable request.

## Sharding + multi-region

**Sharding** (`postgres-sharded` profile) splits `url_mapping`/`url_click_event`
across two independent Postgres instances instead of one:

```
docker compose -f docker-compose.shards.yml up -d
java -jar url-shortener.jar --spring.profiles.active=postgres-sharded
```

Every shard-aware method resolves `Math.floorMod(shortCode.hashCode(), 2)`
(`ShardResolver`) and sets that on a thread-local (`ShardContext`) before
touching the repository; `ShardRoutingDataSource` reads it off that
thread-local to pick the physical DataSource - same
resolve-before-connect/`LazyConnectionDataSourceProxy` pattern as Phase 1's
primary/replica routing, just keyed by shard index instead of
read-vs-write. Because short codes are random (`RandomCodeGenerator`, not a
Base62-encoded auto-increment id), hashing the code spreads links evenly
with no hot shard - which is specifically *not* true of the classic
"shard by sequential id" approach, and is a big part of why this app moved
off that scheme.

**What this does NOT solve, on purpose - two known gaps, left visible
rather than silently wrong:**
- `getMyUrls`/`getMyUrlsSummary` are keyed by `userId`, not `shortCode` -
  there's no single shard to resolve a user's links to, since they're
  scattered across every shard by design. Both currently just read shard 0
  and silently miss a user's links elsewhere. A real fix needs either
  scatter-gather across every shard or a separate `user_id -> shard`
  index table.
- `purgeExpiredLinks` and `recheckActiveLinksForThreats` (the two
  scheduled jobs) have the same problem - no single `shortCode` driving
  either job, so both only ever scan shard 0. A real deployment needs
  these to loop over every configured shard.

**Resharding** is the other classic cost worth being able to name: going
from 2 shards to 3 reshuffles `hash(code) % N` for almost every existing
key, meaning most rows would need to physically move. Consistent hashing
(a hash ring) is the standard fix - not implemented here, since plain
modulo is the right *starting* point to explain before reaching for that,
not a gap to quietly paper over.

**Multi-region** doesn't get a profile or any new code, deliberately - and
that's itself worth being able to explain. The redirect response already
sends `Cache-Control: no-cache` on purpose (see `UrlController.redirect`'s
own doc comment): every click has to actually reach this server, because
that's what makes click analytics, expiry, and abuse blocking (a
disabled/flagged link must stop resolving immediately, everywhere) work at
all. Putting a CDN or edge KV in front of redirects - the standard
multi-region move for a latency-sensitive, read-heavy path - would mean
some fraction of clicks silently never reach the origin, which directly
breaks all three. A real multi-region setup would need to resolve that
tension first (e.g. a short, explicit edge TTL traded off against staleness
tolerance, or pushing disable/expiry checks to the edge itself) rather than
just adding cache headers - so nothing here does that, rather than adding
something that looks like progress but quietly reintroduces a staleness
bug into abuse protection.

## Resilience and observability

Actuator + Micrometer are always on (no profile/flag - safe to leave
enabled everywhere):

- `/actuator/health` - liveness/readiness
- `/actuator/metrics` - every metric name, including the two hand-written
  ones below; `WebMvcMetricsAutoConfiguration` also instruments every
  controller method automatically, so `/actuator/metrics/http.server.requests`
  gets you redirect p50/p99 latency with zero extra code
- `/actuator/prometheus` - the same metrics in a format Prometheus can
  scrape directly
- `/actuator/circuitbreakers` - current state (CLOSED/OPEN/HALF_OPEN) of
  the Safe Browsing breaker below

**Hand-written metrics**, both tagged (not split across separate counter
names) so a single Prometheus query gives a ratio:
- `url_shortener.redirect.cache{result=hit|miss}` - incremented in
  `UrlShortenerService.resolve()`
- `url_shortener.ratelimit.rejected{limiter=shorten|auth|report}` -
  incremented in `RateLimitFilter` whenever a request gets a 429

**Circuit breaker.** `SafeBrowsingClient.check`/`checkBatch` are wrapped in
a resilience4j `@CircuitBreaker` (`resilience4j.circuitbreaker.instances.safeBrowsing`
in application.yml). Without it, a degraded/unreachable Safe Browsing API
means every single link creation eats a full connect+read timeout while
it's down - with it, once the failure rate trips the breaker, calls fail
immediately into a fallback method (same `unavailable()`/empty-map result
`app.safe-browsing.fail-open` already knew how to handle) without even
attempting the network call, until a half-open trial after the configured
wait duration. This is also why the old try/catch inside `check`/`checkBatch`
had to come out - a caught-and-swallowed exception never reaches the
breaker to be counted as a failure in the first place; the fallback method
is what replaces it now.

## Using it across a LAN (e.g. testing with a friend's laptop)

The short URL returned by `POST /api/v1/urls` is built from the **actual
request** (scheme + host + port), not a hardcoded config value. So:

- If you open `http://localhost:8080`, short links say `localhost` — only
  works on your machine.
- If you open `http://<your-LAN-IP>:8080` (find it with `ipconfig` on
  Windows or `ifconfig`/`ip addr` on Mac/Linux, e.g. `192.168.1.5`), short
  links will say `http://192.168.1.5:8080/...` and work for anyone on the
  same network — including you.

So: **share the LAN-IP URL with your friend, not `localhost`.** Also make
sure your firewall allows inbound connections on port 8080 (on Windows,
allow the app through "Windows Defender Firewall" when prompted, or add a
rule for port 8080).

## Deploying it (GitHub + hosting)

**1. Push to GitHub**

```bash
cd url-shortener
git init
git add .
git commit -m "Initial commit: URL shortener"
git branch -M main
git remote add origin https://github.com/<your-username>/url-shortener.git
git push -u origin main
```

(`.gitignore` already excludes `target/` and the local `data/` H2 files, so
you won't accidentally commit build output or your local database.)

**2. Pick a host**

Any platform that can run a Docker container or a `.jar` works — e.g.
Render, Railway, Fly.io, or a plain VPS. The `Dockerfile` in this repo is
ready to use as-is; most platforms will detect and build it automatically
once you connect the GitHub repo.

**3. Two things to configure on the host**

- **Use a real database, not the default H2 file.** Most hosts have an
  ephemeral filesystem — anything written to disk (including the H2 file)
  is wiped on every redeploy or restart, so your short links would
  disappear. Instead:
  - Provision a Postgres database on your host (most have a free/cheap
    add-on for this).
  - Set these environment variables on the host (Spring Boot picks up env
    vars automatically, no code or config changes needed):
    ```
    SPRING_PROFILES_ACTIVE=postgres
    SPRING_DATASOURCE_URL=jdbc:postgresql://<host>:<port>/<db>
    SPRING_DATASOURCE_USERNAME=<user>
    SPRING_DATASOURCE_PASSWORD=<password>
    ```
- **Port binding.** `application.yml` already reads `server.port` from a
  `PORT` environment variable if present (`${PORT:8080}`), which is what
  Render/Railway/Heroku-style platforms expect — they assign a port at
  runtime and your app has to listen on it.

**4. Why the short links will still be correct when hosted**

Two things work together for this:
- The controller builds each short link from the *actual* incoming
  request's host, so it automatically matches your real domain
  (`https://your-app.onrender.com`) instead of `localhost`.
- `server.forward-headers-strategy: framework` tells Spring Boot to trust
  the `X-Forwarded-Proto`/`X-Forwarded-Host` headers that reverse proxies
  (which is what these hosting platforms put in front of your app) set —
  without this, the app would build links using the *internal* address the
  proxy talks to your container on, not your public URL.

## Design notes (useful for interviews)

- **Why Base62 of the DB id instead of a random string?** Guarantees
  uniqueness with a single insert — no "generate, check, retry" loop under
  concurrent writes. The id is offset by a constant before encoding so
  codes are always ≥ 3 characters, even for the first rows in the table.
- **Why validate with a route regex too?** `/{shortCode:[a-zA-Z0-9_-]{3,20}}`
  keeps the redirect endpoint from swallowing requests for static assets,
  Swagger UI, or the H2 console, which live at other paths.
- **Expiry** is enforced on read (`isExpired()` on the entity), returns
  `410 Gone` rather than `404` so callers can tell "existed but expired"
  apart from "never existed", and a `@Scheduled` job purges expired rows
  hourly so the table doesn't grow unbounded.
- **Click history is a separate table from the click counter.** `UrlMapping.clickCount`
  stays as a fast running total (no join needed for stats); each individual
  click also gets a row in `url_click_event`, queried through `Pageable` so
  a link with millions of clicks never gets pulled into memory at once.
- **Custom-alias uniqueness is enforced twice** — an app-level check for a
  clean error message on the common path, and a DB unique constraint as the
  actual source of truth, with the resulting `DataIntegrityViolationException`
  caught and translated to the same `409` a normal duplicate gets. This
  closes the race-condition gap a check-then-insert pattern has on its own.
- **Rate limiting is in-memory and single-instance.** Documented as a known
  scaling limit rather than glossed over — see the Rate limiting section
  above for what a distributed version would need.

## Ideas for extending it further

- Richer per-click analytics (referrer, user agent, rough geolocation)
- Persist report reasons/history for real moderation instead of just a
  count (see `ReportRequest`/`report()` - the API already accepts a reason,
  it just isn't stored anywhere yet)
- A moderator/admin role for reviewing `FLAGGED` links, rather than relying
  entirely on the owner (or the reporting threshold) to resolve them
- Refresh tokens + real server-side logout (JWTs currently just expire
  after `jwt.expiration-ms`; there's no revocation path)
