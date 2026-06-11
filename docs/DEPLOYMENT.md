# Production deployment checklist

A concise hardening checklist for taking the HueVista backend to production.
All settings below are environment variables read via `${VAR}` placeholders in
[`application.properties`](../src/main/resources/application.properties); copy
[`.env.example`](../.env.example) to `.env` and fill them in (the `.env` file is
gitignored — never commit it).

## 1. JWT_SECRET — required, no default

- The app **fails to start** if `JWT_SECRET` is unset (`app.jwt.secret=${JWT_SECRET}`
  has no fallback). This is deliberate: the service must never run on a publicly
  known signing key.
- `docker-compose.yml` enforces the same thing at the compose layer:
  `JWT_SECRET: ${JWT_SECRET:?...}` makes `docker compose up` **fail fast** with an
  error message instead of starting the container.
- Generate a strong secret (base64, 32+ random bytes):

  ```bash
  # Linux / macOS
  openssl rand -base64 48
  ```

  ```powershell
  # Windows PowerShell
  $b = New-Object byte[] 48
  [System.Security.Cryptography.RandomNumberGenerator]::Create().GetBytes($b)
  [Convert]::ToBase64String($b)
  ```

- **Rotation:** rotating the secret invalidates every outstanding access and
  refresh token — all users (and guest sessions) are signed out and must log in
  again. Rotate during a low-traffic window, and rotate immediately if the secret
  is ever exposed (committed, logged, or shared).

## 2. CORS_ALLOWED_ORIGINS

- Defaults to `http://localhost:3000` (dev frontend). In production set it to the
  exact frontend origin(s), comma-separated, e.g.
  `CORS_ALLOWED_ORIGINS=https://app.your-domain.example`.
- Never use `*` — the API uses credentialed requests (cookies / Authorization
  headers).

## 3. SWAGGER_ENABLED=false

- Swagger UI (`/swagger-ui.html`) and the OpenAPI spec (`/v3/api-docs`) are
  **public endpoints** when enabled — they enumerate the entire API surface.
- Both default to `false`; keep `SWAGGER_ENABLED=false` in production and enable
  it only in dev/staging environments.

## 4. LOG_LEVEL_APP=INFO

- `logging.level.com.gridstore.huevista` defaults to `INFO` via `LOG_LEVEL_APP`.
- Do **not** run production at `DEBUG`: debug logging emits PII and
  infrastructure detail, e.g. user emails on authentication
  (`JwtAuthFilter` — now masked, but other debug lines may carry identifiers)
  and S3 storage bucket/object keys (`S3StorageService`).
- If you need temporary verbosity in prod, prefer scoping it to a single package
  rather than raising the whole app to DEBUG.

## 5. Database schema: switch to ddl-auto=validate

- `spring.jpa.hibernate.ddl-auto` defaults to `update` (overridable via
  `SPRING_JPA_DDL_AUTO`). `update` is what makes **first-run deploys** work out of
  the box — Hibernate creates the schema from the JPA entities — so the default is
  intentionally left as-is.
- For **steady-state production**, set `SPRING_JPA_DDL_AUTO=validate` once the
  schema exists. `update` in prod can silently alter tables on deploy and cannot
  handle destructive changes (renames, drops) safely.
- The recommended end-state is `validate` + Flyway migrations — see the
  "Database migrations" section of the [README](../README.md) for the step-by-step
  Flyway adoption guide.

## 6. RATE_LIMIT_TRUST_FORWARDED — only behind a proxy

- `RATE_LIMIT_TRUST_FORWARDED` defaults to `true`, meaning the per-IP rate limiter
  reads the client IP from `X-Forwarded-For`. That is correct **only when the
  backend sits behind a trusted reverse proxy / load balancer** that overwrites
  the header.
- If the backend port is ever reachable directly from the internet, set
  `RATE_LIMIT_TRUST_FORWARDED=false` — otherwise any client can spoof
  `X-Forwarded-For` and bypass the per-IP limits.

## 7. API change: list endpoints are now paginated

- `GET /api/projects`, `GET /api/images` and the paint-job list endpoints accept
  optional `?page=` (0-based, default `0`) and `?size=` (default `200`, max `200`)
  parameters. The defaults match the previous hard cap of 200 rows, so existing
  clients are unaffected.
- `GET /api/admin/users`, `/api/admin/organizations` and `/api/admin/subscriptions`
  — previously **unbounded** — now return at most `size` rows (default `200`,
  max `500`), ordered newest-first (`createdAt` DESC, then `id`). Any admin script
  or reporting tool that consumed these endpoints expecting the complete dataset
  must now iterate pages (`?page=0`, `?page=1`, … until an empty response).
- All of these endpoints still return bare JSON arrays — there is no envelope or
  metadata wrapper.

## Quick pre-deploy checklist

- [ ] `JWT_SECRET` set to a freshly generated 32+ byte base64 value (compose fails fast without it)
- [ ] `CORS_ALLOWED_ORIGINS` set to the real frontend origin(s), no wildcard
- [ ] `SWAGGER_ENABLED=false`
- [ ] `LOG_LEVEL_APP=INFO` (DEBUG logs emails and storage keys)
- [ ] `SPRING_JPA_DDL_AUTO=validate` once the schema is established (default `update` is for first-run only)
- [ ] `RATE_LIMIT_TRUST_FORWARDED=true` only if behind a proxy; `false` if directly exposed
- [ ] All third-party secrets (Razorpay, Replicate, Anthropic, SMTP, …) supplied via the environment — never committed
