# HueVista

**AI-Powered Paint Shade Visualizer — B2B Platform for the Indian Paint Retail Market**

> "See your walls in your chosen color — before you paint a single stroke."

---

## Overview

HueVista is a B2B paint visualization platform for the Indian paint retail market. Retailers and their walk-in customers upload a room photograph and preview real catalogue shades (Asian Paints at launch, Berger and Nerolac to follow) applied photorealistically — before a single stroke is painted.

Distribution follows the existing trade hierarchy:

```
Manufacturer → Distributor → Retailer → Painter → End Customer
```

Retailers subscribe to use HueVista as a sales tool with walk-ins, shortening the color-selection conversation and closing sales faster.

---

## Architecture

```
+--------------------------------------------------+
|           FRONTEND (Next.js + React)             |
|  Auth | Atelier | Catalogue | Portal             |
|  WebGL Color Engine | Dashboard                  |
+---------------------+----------------------------+
                      | REST API
+---------------------v----------------------------+
|           BACKEND (Spring Boot 4, Java 17)       |
|  auth/       — JWT, Google OAuth2          ✓ Built|
|  image/      — Upload, classify, S3        ✓ Built|
|  paint/      — Brand, Shade, AP seeder     ✓ Built|
|  project/    — Project, Region, segment    ✓ Built|
|  ai/         — Claude Sonnet recommend     ✓ Built|
|  billing/    — Razorpay, quota, webhooks   ✓ Built|
|  account/    — Org, distributor, codes     ✓ Built|
|  admin/      — Admin endpoints             ✓ Built|
|  common/     — CORS, errors, OpenAPI       ✓ Built|
+---------------------+----------------------------+
                      |
+---------------------v----------------------------+
|              AI / ML LAYER                       |
|  Image cleaning (opt-in): Nano Banana Pro        |
|  Auto mask generation:    Nano Banana (color)    |
|  Click refinement:        SAM 2 (point prompt)   |
|  Image classification:    Claude Haiku Vision    |
|  Color recommendations:   Claude Sonnet          |
+---------------------+----------------------------+
                      |
+---------------------v----------------------------+
|           STORAGE & CDN                          |
|  AWS S3 (ap-south-1) → CloudFront                |
|  PostgreSQL · Redis (cache)                      |
+--------------------------------------------------+
```

---

## Module map

| Module | Status | Key files |
|---|---|---|
| `auth/` | ✓ Built | JWT, refresh tokens, Google OAuth2, role-based auth |
| `image/` | ✓ Built | Upload, Claude Haiku classification, S3 + local storage |
| `paint/` | ✓ Built | Brand + Shade entities, filterable catalogue, AP seeder + Claude enrichment |
| `project/` | ✓ Built | Full lifecycle: create → segment (async) → save regions → share |
| `ai/` | ✓ Built | Claude Sonnet recommendations, CIELAB ΔE matcher |
| `billing/` | ✓ Built | Razorpay subscriptions, HMAC webhooks, AI quota enforcement |
| `account/` | ✓ Built | Distributor → retailer org hierarchy, access codes (3/7/14 day) |
| `admin/` | ✓ Built | Admin endpoints for ops tasks |
| `common/` | ✓ Built | Global exception handler, CORS, OpenAPI/Swagger, Redis cache |

---

## API endpoints

| Method | Endpoint | Description |
|---|---|---|
| POST | `/api/auth/register` | Register new user |
| POST | `/api/auth/login` | Login, returns JWT |
| POST | `/api/auth/refresh` | Refresh access token |
| POST | `/api/images/upload` | Upload + classify image (Claude Haiku) |
| GET | `/api/shades` | List shades with filters (brand, family, temperature, tonality, search) |
| GET | `/api/shades/{brand}` | List shades by brand |
| GET | `/api/shades/{brand}/families` | Distinct family names |
| GET | `/api/shades/{brand}/{code}` | Single shade detail |
| POST | `/api/admin/paint/seed/asian-paints` | Seed AP catalogue (idempotent) |
| POST | `/api/projects` | Create project |
| GET | `/api/projects` | List user's projects |
| GET | `/api/projects/{id}` | Project detail with regions |
| PUT | `/api/projects/{id}/regions` | Auto-save region colors |
| POST | `/api/projects/{id}/segment` | Async auto-segmentation (Nano Banana) |
| POST | `/api/projects/{id}/segment/point` | Click-to-segment (SAM 2) |
| GET | `/api/projects/{id}/status` | Poll segmentation status |
| POST | `/api/projects/{id}/share` | Generate share link |
| GET | `/api/shared/{token}` | Public share view |
| POST | `/api/projects/{projectId}/recommendations` | Claude color recommendations |
| POST | `/api/billing/subscriptions` | Create Razorpay subscription |
| GET | `/api/billing/subscriptions/current` | Current subscription |
| POST | `/api/billing/webhooks/razorpay` | Razorpay webhook receiver (HMAC verified) |
| POST | `/api/organizations` | Create distributor/retailer org |
| POST | `/api/organizations/{orgId}/access-codes` | Generate customer access code |
| POST | `/api/access-codes/redeem` | Redeem an access code |

Full OpenAPI spec at `http://localhost:8080/swagger-ui.html` when running.

---

## Getting started

### Prerequisites
- Java 17+
- Maven 3.9+ (or use the wrapper)
- PostgreSQL 14+
- Redis (optional — disable cache via `spring.cache.type=none` if not running)

### 1. Configure environment

Required env vars (no in-source defaults — app fails to start without them):

```bash
DB_PASSWORD=...
JWT_SECRET=$(openssl rand -base64 32)
GOOGLE_CLIENT_ID=...
GOOGLE_CLIENT_SECRET=...
ANTHROPIC_API_KEY=...
REPLICATE_API_TOKEN=...
RAZORPAY_KEY_ID=...
RAZORPAY_KEY_SECRET=...
RAZORPAY_WEBHOOK_SECRET=...
RAZORPAY_PLAN_STARTER=plan_...        # create in Razorpay dashboard first
RAZORPAY_PLAN_PROFESSIONAL=plan_...
RAZORPAY_PLAN_BUSINESS=plan_...
```

Optional (have safe defaults):

```bash
REPLICATE_NANO_BANANA_ENABLED=true    # default false (auto-mask disabled)
REPLICATE_IMAGE_CLEANER_ENABLED=true  # default false (opt-in extra Replicate call)
S3_BUCKET_NAME=huevista-uploads       # default: local filesystem
APP_BASE_URL=https://api.huevista.com # default: http://localhost:8080
CORS_ALLOWED_ORIGINS=https://huevista.com
```

### 2. Run

```bash
./mvnw spring-boot:run
```

The schema is applied automatically at startup by Flyway (`src/main/resources/db/migration`); Hibernate runs with `ddl-auto=validate` and never mutates it. The `dev` profile (H2) skips Flyway and lets Hibernate generate the schema instead — see "Operational notes" below.

### 3. Seed the Asian Paints catalogue

A sample seed file lives at [`src/main/resources/seed/asian-paints-sample.json`](src/main/resources/seed/asian-paints-sample.json) — 50 representative shades for local development. To seed:

```bash
# Log in as an admin user first to get a token, then:
curl -X POST http://localhost:8080/api/admin/paint/seed/asian-paints \
  -H "Authorization: Bearer $ADMIN_TOKEN" \
  -H "Content-Type: application/json" \
  --data @src/main/resources/seed/asian-paints-sample.json
```

The endpoint is **idempotent** — re-running only inserts shades not already present. Each shade is enriched with Claude (style tags, mood descriptors, finish recommendations, AI description) — expect ~1–2 minutes for 50 shades.

For production, replace the sample with the full Asian Paints API export (~2,400 shades). The expected JSON shape is documented at the top of the seed file.

### 4. Berger / Dulux / Nerolac / Nippon catalogues (auto-loaded)

The Berger, Dulux, Nerolac and Nippon catalogues ship bundled under [`src/main/resources/seed/brands/`](src/main/resources/seed/brands/) and are **loaded automatically on startup** (`BrandCatalogSeeder`) — ~8,000 shades total, no admin call required. These files use a simpler `{name, code, hex, category}` shape; `category` maps to `shadeFamily`, and hex/RGB/LRV are computed locally.

Unlike the Asian Paints path, this import is **raw — no Claude enrichment** (style tags, mood, etc. are left empty). It is idempotent and de-duplicated by `(brand, code)` — duplicate codes within or across files are skipped (the two Nerolac files overlap heavily and merge into one `nerolac` brand), and rows with malformed hex are dropped with a warning.

To skip this bulk load (e.g. in a constrained environment), set `app.catalog.auto-seed=false`. Tests disable it so their fixtures aren't drowned out.

---

## Subscription tiers

| Tier | Price (ex-GST) | AI Generations | Notes |
|---|---|---|---|
| Starter | ₹499/mo | 20/mo | Core visualization, WhatsApp share |
| Professional | ₹999/mo | 60/mo | + AI recommendations, per-region recolour |
| Business | ₹1,999/mo | 150/mo | + White-label, painter portal beta |
| Enterprise | Custom | Unlimited | + API access, dedicated onboarding |

Color application (browser-side WebGL) is unlimited at zero marginal cost. Segmentation (which includes the image clean) and recommendations count against the monthly quota — reserved/charged atomically in `BillingService.reserveAiUsage()` / `incrementAiUsage()`, with failed runs refunded. Upload classification is not quota-charged but is per-IP rate-limited (`app.rate-limit.image-upload.*`) to bound its Claude cost.

---

## Operational notes

### Database migrations
The schema is owned by **Flyway** (`src/main/resources/db/migration`), applied automatically at startup; Hibernate runs with `ddl-auto=validate` and never mutates the schema.

- `V1__baseline.sql` is the full schema as of Flyway adoption (generated from the JPA entities against PostgreSQL 16). Never edit it.
- Databases that predate Flyway are adopted automatically: `baseline-on-migrate=true` stamps a non-empty schema as already at V1 and applies only V2+.
- Every schema change from here on is a new versioned file: `V3__add_xxx.sql`, `V4__…`, etc. Change the entity **and** write the matching migration — `validate` at boot will catch any drift between them.
- The `dev` profile (H2) and the test suite disable Flyway (migrations are PostgreSQL SQL) and keep Hibernate DDL generation.

### Health checks
`GET /actuator/health` is public and reports overall status only (`{"status":"UP"}`); liveness/readiness variants live at `/actuator/health/liveness` and `/actuator/health/readiness`. The Docker image and `docker-compose.yml` use it as their healthcheck. No other actuator endpoint is exposed.

### CI / CD
GitHub Actions workflow at [`.github/workflows/ci.yml`](.github/workflows/ci.yml) — runs `./mvnw verify` on every PR with H2 (in-memory) for tests. Production deploy is not wired up.

### Tests
- Run: `./mvnw test`
- Coverage: auth, billing webhooks, project flow, paint catalogue, image upload, AI recommendations, accounts/access codes
- Tests use H2 in-memory DB and mock all external services (Razorpay, Replicate, Claude). See `src/test/resources/application-test.properties`.

### Costs (per-upload)
| Step | Provider | Approx cost |
|---|---|---|
| Classification | Claude Haiku Vision | ~₹0.30 |
| Auto-mask | Replicate Nano Banana | ~₹3.40 |
| Image cleaning (opt-in) | Replicate Nano Banana Pro | ~₹8.50 |
| Click refinement | Replicate SAM 2 | ~₹1.70 |
| Recommendation | Claude Sonnet Vision | ~₹2.50 |
| Color application | Browser WebGL | ₹0 |

A Starter retailer's 20 monthly renders translates to roughly ₹100 in cloud spend — well within margin at ₹499/mo.

---

## Going to production — checklist

Everything in [.env.example](.env.example) explains itself; these are the items that
bite if skipped:

1. **Email + SMS delivery** — set `MAIL_ENABLED=true` (+ SMTP creds) and
   `SMS_ENABLED=true`, `SMS_PROVIDER=twilio` (+ Twilio creds). These flags also
   drive the retailer verification gate: a channel is only required-verified
   before project creation when it can actually deliver a code, so leaving them
   off silently weakens onboarding verification.
2. **Admin bootstrap** — set `ADMIN_EMAIL` / `ADMIN_PASSWORD` (shops are
   admin-provisioned; shop-lead notifications go to this address). Change the
   password after first sign-in.
3. **Razorpay** — create the three plans in the dashboard, set the plan IDs, and
   point a webhook at `POST {APP_BASE_URL}/api/billing/webhooks/razorpay` with
   `RAZORPAY_WEBHOOK_SECRET`. Renewals depend on the webhook.
4. **Network posture** — if the backend port is ever reachable except through
   your own frontend/proxy, set `RATE_LIMIT_TRUST_FORWARDED=false` (otherwise
   spoofed `X-Forwarded-For` bypasses every per-IP limit).
5. **Timezone** — the schema stores zone-naive timestamps; the Docker image pins
   `TZ=Asia/Kolkata`. Keep the JVM on IST on any other host too, or every
   share/subscription/access-code expiry shifts by the host's offset.
6. **Monitoring** — set `ACTUATOR_EXPOSURE=health,prometheus` and
   `METRICS_PUBLIC=true` (private networks only) and scrape
   `/actuator/prometheus` (JVM, HTTP latencies/status codes, Hikari pool,
   cache). Alert on 5xx rate, p95 latency, pool exhaustion and heap. Log files
   rotate at `logs/huevista.log` (10×10 MB) — ship them somewhere durable.
7. **Backups** — schedule `pg_dump` (or your provider's automated snapshots)
   for PostgreSQL and enable S3 bucket versioning; masks and cleaned images are
   reproducible at cost, originals are not.
8. **Swagger stays off** — `SWAGGER_ENABLED=false` in production; the spec and
   UI are public when on.

---

## Phased roadmap

### ✓ Phase 1 — MVP (complete)
- [x] Image upload + indoor/outdoor classification
- [x] Nano Banana auto-segmentation + SAM 2 manual refinement
- [x] Asian Paints catalogue (seedable)
- [x] Project save/load with auto-save
- [x] PNG export + share link
- [x] Razorpay subscriptions with quota enforcement

### Phase 2 — Multi-tier & white-label
- [x] Distributor → Retailer org hierarchy
- [x] Customer access code system (3/7/14-day validity)
- [x] AI color recommendations
- [ ] Wildcard subdomain routing (`{shop}.huevista.com`) — needs infra
- [ ] All four pricing tiers active simultaneously (currently single ₹999 plan in pilot)

### Phase 3 — E-commerce & painter integration
- [ ] Painter accounts under retailers
- [ ] Online order placement
- [ ] Retailer storefront

### Phase 4 — Premium visual features
- [ ] Depth Anything v2 for depth/parallax
- [ ] Generative AI re-rendering (SDXL via Replicate)
- [ ] Automated commission payouts (Razorpay X)

### Phase 5 — True 3D (demand-conditional)
- [ ] Multi-image capture → Gaussian Splatting / NeRF

---

## Why 2D recolour, not generative AI

Generative AI re-rendering was rejected as the primary engine:
- **Cost:** ₹3–8 per render destroys margins at sub-Premium tiers.
- **Latency:** 10–30 s waits between color changes is unusable at the counter.
- **Hallucination:** AI subtly changes furniture, lighting, layout — customers say "that's not my house."

The 2D luminance-preserving WebGL approach is photorealistic by definition — it IS the original photo with only the wall's hue replaced. Generative AI remains a Premium-tier feature for styled final renders.

---

*PRD Version: 2.1 | Status: Phase 1 complete · Phase 2 in progress | Engineered in Belgavi*
