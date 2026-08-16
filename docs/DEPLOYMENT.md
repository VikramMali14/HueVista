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
- This list also drives the **S3 bucket's** CORS rule (see below), so it must name
  every origin that displays images, not only those that call the API.

### The S3 bucket needs its own CORS rule

The frontend draws every image it recolours onto a canvas, which means loading it
`crossOrigin="anonymous"`, which makes it a CORS request. Presigning the URL does
not help: only the **bucket's** CORS configuration can make S3 send
`Access-Control-Allow-Origin`, and without it the browser blocks the image and the
room renders blank.

On startup (S3 enabled, `S3_CONFIGURE_CORS` unset or `true`) the app installs a
read-only rule for `CORS_ALLOWED_ORIGINS` if the bucket doesn't already allow them.
It needs **`s3:PutBucketCors`** on the bucket. Without that permission startup still
succeeds and the log carries the `aws s3api put-bucket-cors …` command to run by
hand — check for it after the first deploy to a new bucket. Until the rule exists,
images are served through the frontend's `/api/media` fallback, which works but puts
image bytes through the frontend server. That fallback needs `S3_BUCKET_NAME` set on
the **frontend** (same value as here) to arm. See `docs/IMAGE_UPLOAD_FLOW.md` §12.

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

## 5. Database schema: Flyway owns it

- The schema is managed by **Flyway** migrations in
  `src/main/resources/db/migration`, applied automatically at startup.
  `spring.jpa.hibernate.ddl-auto` defaults to `validate` — Hibernate only checks
  that the schema matches the entities and never mutates it.
- **Existing databases** (created by the old `ddl-auto=update`) are adopted
  automatically: `spring.flyway.baseline-on-migrate=true` stamps a non-empty
  schema as already at V1 (the baseline) and applies only V2+. Fresh, empty
  databases get V1 and everything after it. No manual steps either way.
- Heads-up for the first deploy of this version: **V2 clears the
  `refresh_tokens` table** (old rows hold raw token values; the app now stores
  only SHA-256 hashes). Every signed-in user is logged out once and simply signs
  in again.
- Every future schema change is a new versioned file (`V3__...`, `V4__...`);
  never edit an applied migration, and don't set `SPRING_JPA_DDL_AUTO=update`
  anywhere Flyway runs.
- The dev profile (H2) and the test suite disable Flyway and keep Hibernate DDL
  generation — migrations are written for PostgreSQL.

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

## 8. SMTP: the relay must be able to authenticate the sender domain

Mail is the one subsystem that starts up perfectly and then fails on first use.
The app only builds a `JavaMailSender` when `MAIL_HOST` is set, and only sends when
`MAIL_ENABLED=true`; otherwise codes are written to the log as `[DEV EMAIL]`. The
startup config diagnostic now prints a `MAIL / SMTP` block and warns about each of
the traps below — check it after any mail change.

Senders default to `no-reply@huevista.org` and `payments@huevista.org`, so the
relay must be authorised to send as **huevista.org**.

| Symptom in the log | Cause | Fix |
| --- | --- | --- |
| `550-5.7.0 Mail relay denied [<ip>]. Invalid credentials for relay` + `SMTP relay isn't supported for unmanaged work accounts` | `MAIL_HOST=smtp-relay.gmail.com` with a personal/unmanaged Google account, or the host's egress IP is not registered | Use SES or `smtp.gmail.com` (below). The Workspace relay needs a **managed** Workspace account *and* the egress IP registered under Admin console → Apps → Google Workspace → Gmail → Routing → SMTP relay service |
| `535 Username and Password not accepted` | Gmail account password used instead of an app password | Enable 2-Step Verification, then generate a 16-char app password |
| Mail "sent" but the From is rewritten | The SMTP account cannot authenticate the From domain | Put every sender on one verified domain, or set `MAIL_FROM` to the authenticated address |
| No SMTP error at all, codes appear as `[DEV EMAIL]` in the log | `MAIL_ENABLED=false`, or `MAIL_HOST` empty | Set both |

**Recommended for this stack** — Amazon SES in the region you already run in:
verify `huevista.org`, enable DKIM, request production access (to leave the
sandbox), then generate **SES SMTP credentials** — these are not your AWS access
keys:

```
MAIL_ENABLED=true
MAIL_HOST=email-smtp.ap-south-1.amazonaws.com
MAIL_PORT=587
MAIL_USERNAME=<SES SMTP username>
MAIL_PASSWORD=<SES SMTP password>
```

Note that SMTP is deliberately excluded from the health probe
(`MAIL_HEALTH_CHECK=false` by default), so a broken relay will **not** show up as
an unhealthy container — it surfaces only as a `503` from the affected endpoint
and an `Email delivery failed` line in the log.

## Quick pre-deploy checklist

- [ ] `JWT_SECRET` set to a freshly generated 32+ byte base64 value (compose fails fast without it)
- [ ] `CORS_ALLOWED_ORIGINS` set to the real frontend origin(s), no wildcard
- [ ] With S3 on: startup log shows the bucket CORS rule installed (or already covered) — if it warns instead, run the `aws s3api put-bucket-cors` command it prints
- [ ] `SWAGGER_ENABLED=false`
- [ ] `LOG_LEVEL_APP=INFO` (DEBUG logs emails and storage keys)
- [ ] Leave `SPRING_JPA_DDL_AUTO` unset (defaults to `validate`; Flyway applies the schema)
- [ ] Point the load balancer / container healthcheck at `GET /actuator/health`
- [ ] `RATE_LIMIT_TRUST_FORWARDED=true` only if behind a proxy; `false` if directly exposed
- [ ] All third-party secrets (Razorpay, Replicate, Anthropic, SMTP, …) supplied via the environment — never committed
- [ ] If `MAIL_ENABLED=true`: startup diagnostic shows `Delivering : yes — real SMTP` with no `MAIL / SMTP` warnings, and a test password reset actually arrives
