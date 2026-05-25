# HueVista

**AI-Powered Paint Shade Visualizer — B2B Platform for the Indian Paint Retail Market**

> "See your walls in your chosen color — before you paint a single stroke."

---

## Overview

HueVista is a B2B paint visualization platform built for the Indian paint retail market. Retailers, distributors, and their end customers can upload photographs of interior rooms or building exteriors and preview real paint shades from major brand catalogs (Asian Paints, Berger, Nerolac) applied photorealistically to walls and surfaces — before a single stroke is painted.

Unlike consumer-facing paint apps, HueVista distributes through the existing paint industry hierarchy:

```
Manufacturer → Distributor → Retailer → Painter → End Customer
```

Retailers pay a subscription to use HueVista as a sales tool with walk-in customers, shortening the color-selection conversation and closing sales faster.

---

## The Problem

- Customers stare at small printed swatches and mentally imagine how a 2 cm card will look on a 12-foot wall — most get it wrong.
- Repaints due to color disappointment cost customers time and money and create complaints for retailers.
- Retailers spend 30+ minutes per customer walking through brochures, often losing the sale to indecision.
- Existing manufacturer apps are generic, slow, and not tied to a specific retailer's inventory or commercial relationship.

---

## Core Features

### Image Upload & Classification
- Drag-drop or browse upload — JPEG, PNG, WebP up to 10 MB.
- Pre-classification via **Claude Haiku Vision**: rejects invalid uploads (selfies, food, landscapes) before storage.
- Original full-resolution image stored in **AWS S3**; a 1024 px copy used for AI calls (~10× token cost reduction). ✓ Built

### Surface Detection
- Two-step automatic segmentation via Replicate:
  1. **Image cleaner** *(opt-in)*: Nano Banana Pro removes overhead wires, bushes, parked cars, debris, hanging laundry and refreshes painted surfaces in their current color. The cleaned image becomes the paint preview canvas AND the input for mask generation. Disable to use the original photo.
  2. **Nano Banana** (Gemini Image): a single Replicate call returns one color-coded mask — **white** for the main paintable wall, **green** for trim & frames, **blue** for an accent wall (when distinct), **black** for everything else. The server splits this into per-category binary masks (`MAIN_WALL`, `ACCENT_WALL`, `TRIM`).
- **Click-based refinement** *(Phase 1)*: user clicks any point on the photo, SAM 2 segments the surface at that click, and the resulting region is saved as `MANUAL`. The safety net for whatever the auto path misses.

### Color Application Engine
- **Browser-side HTML Canvas + WebGL** — zero backend round-trip per color change.
- Luminance-preserving recolor: keeps original brightness, shadows, and texture; replaces only hue and saturation.
- Per-region color assignment — different walls can have different colors simultaneously.
- Real-time preview on swatch hover, 60 fps on mid-range mobile browsers. *(Phase 1)*

### Paint Shade Catalog
- Multi-brand: **Asian Paints** (launch), Berger, Nerolac (Phase 2).
- Each shade: code, name, hex, RGB, color family, LRV, available finishes, recommended room types.
- Filter by color family, finish, LRV range, style (Indian / Italian / American).
- Search by shade code, name, or hex value.
- "Find similar" using CIELAB color distance (Delta E). *(Phase 1)*

### AI Color Recommendations
- Claude analyzes the room/exterior style and proposes 3 hex combinations matched to the visual style.
- Each hex snapped to the nearest real shade via Delta E nearest-neighbor.
- One-click apply: primary wall + accent + trim colors applied simultaneously. *(Phase 2)*

### Projects & Sharing
- Auto-save every 2 seconds — no manual save button.
- Browser-side PNG export via `canvas.toBlob()` — zero backend cost.
- **WhatsApp sharing** (optimized for Indian market).
- Backend high-resolution render for Premium tier. *(Phase 1–2)*

### White-Label Customer Portal
- Custom subdomain: `{shopname}.huevista.com`.
- Retailer issues temporary access codes (3 / 7 / 14-day validity).
- Customers visualize colors without seeing shade codes.
- "Send to Retailer" packages the project; retailer receives full shade codes + pixel-area paint quantity estimates. *(Phase 2)*

---

## Business Model

### Distribution Hierarchy
**HueVista (Super Admin) → Distributors → Retailers → Customers / Painters**

### Retailer Subscription Tiers

| Tier | Price (ex-GST) | AI Generations | Key Features |
|---|---|---|---|
| Starter | ₹499/mo | 20/mo | Core visualization, WhatsApp share |
| Professional | ₹999/mo | 60/mo | + AI recommendations, projects |
| Business | ₹1,999/mo | 150/mo | + White-label portal, priority support |
| Enterprise | Custom | Unlimited | + API access, dedicated onboarding |

> **Important distinction:** Color application (2D canvas recolor) is unlimited at zero marginal cost to HueVista. AI generation (full generative re-render) is the capped resource — capped per tier.

### Distributor Commission
- One-time onboarding fee for a regional license.
- **3% recurring commission** on every retailer subscription brought on.
- Commission can be applied as an upfront discount to the retailer's subscription to close sales faster.
- MVP: manual UPI/bank transfer payouts. Phase 2: automated via Razorpay X.

### White-Label Add-on
- ₹1,499 one-time activation fee per retailer.

---

## Technical Architecture

```
+--------------------------------------------------+
|           FRONTEND (Next.js + React)             |
|  Auth | Upload + Catalog | WebGL Color Engine    |
|  Customer Portal | Retailer Dashboard            |
+---------------------+----------------------------+
                      | REST API
+---------------------v----------------------------+
|           BACKEND (Spring Boot — Java)           |
|  auth/       — JWT, Google OAuth2          (Built)|
|  image/      — Upload, classify, S3        (Built)|
|  common/     — CORS, error handling        (Built)|
|  paint/      — Brand, Shade, ColorMatch  (Phase 1)|
|  project/    — Project, Region, Render   (Phase 1)|
|  ai/         — Nano Banana, SAM 2, Claude (Phase 1)|
|  account/    — Org, Distributor, Retailer(Phase 2)|
|  billing/    — Subscription, Razorpay    (Phase 1)|
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
|  AWS S3 (ap-south-1)  →  CloudFront CDN          |
+--------------------------------------------------+
```

### Async AI Calls
- Nano Banana mask generation: 5–10 s. With image cleaner enabled: ~15–20 s end-to-end (clean + mask).
- MVP: Spring `@Async` thread pool (core 4, max 16, queue 100). Frontend polls project status every 1 s.
- Scale: Redis-backed queue + SSE for status streaming.

### Stack
| Layer | Technology |
|---|---|
| Backend | Spring Boot 4, Java |
| Database | PostgreSQL |
| Storage | AWS S3 (ap-south-1) |
| Auth | JWT + Google OAuth2 |
| Frontend | Next.js + React |
| Mobile | Capacitor (post-Phase 1) |

---

## API Endpoints

| Method | Endpoint | Description | Status |
|---|---|---|---|
| POST | `/api/auth/register` | Register new user | ✓ Built |
| POST | `/api/auth/login` | Login, returns JWT | ✓ Built |
| POST | `/api/auth/refresh` | Refresh access token | ✓ Built |
| POST | `/api/images/upload` | Upload + classify image | ✓ Built |
| GET | `/api/shades` | Get shades (with filters) | Phase 1 |
| GET | `/api/shades/:brand` | Get shades by brand | Phase 1 |
| POST | `/api/projects` | Create/save project | Phase 1 |
| GET | `/api/projects` | Get user's projects | Phase 1 |
| POST | `/api/projects/:id/segment` | Run Nano Banana segmentation | Phase 1 |
| GET | `/api/projects/:id/status` | Poll AI job status | Phase 1 |
| POST | `/api/projects/:id/share` | Generate share link | Phase 1 |

---

## Phased Roadmap

### Phase 1 — MVP (Weeks 1–6)
**Goal:** One paying retailer using core paint visualization in production.
- [x] Image upload + indoor/outdoor classification
- [x] Nano Banana auto segmentation + optional image cleaner, SAM 2 click-refinement
- [ ] WebGL-based color application engine
- [ ] Asian Paints catalog (~200 shades) seeded
- [ ] Project save/load with auto-save
- [ ] WhatsApp share with PNG export
- [ ] Single subscription plan (₹999/mo) via Razorpay

### Phase 2 — Multi-Tier Accounts & White-Label (Weeks 7–14)
- [ ] Distributor → Retailer organization hierarchy
- [ ] Wildcard subdomain routing (`{shop}.huevista.com`)
- [ ] Customer access code system (3/7/14-day validity)
- [ ] White-label customer portal (no shade codes shown)
- [ ] AI color recommendations (Claude integration)
- [ ] All four pricing tiers; commission payouts manual

### Phase 3 — E-commerce & Painter Integration (Weeks 15–22)
- [ ] Painter accounts under retailers
- [ ] Online order placement with in-app payment
- [ ] Retailer storefront with product pricing and availability

### Phase 4 — Premium Visual Features (Weeks 23–28)
- [ ] Depth Anything v2 for depth maps and parallax effect
- [ ] Generative AI re-rendering via SDXL (Replicate)
- [ ] 1–8 style variants per project for Premium plans
- [ ] Automated commission payouts via Razorpay X

### Phase 5 — True 3D (Conditional on Demand)
- [ ] Multi-image capture → Gaussian Splatting / NeRF
- [ ] Walkable 3D space with color application in any view

---

## MVP Success Criteria

- At least 1 retailer using HueVista in daily customer conversations.
- Retailer has used it with at least 10 walk-in customers.
- Retailer paying ₹999/month (manually billed via UPI or bank transfer).
- Time from photo upload to color preview: **< 10 seconds**.
- Color application after segmentation: **< 100 ms per swatch change**.

---

## Getting Started

### Prerequisites
- Java 21+
- PostgreSQL
- Maven 3.9+
- AWS account with S3 bucket (or use local storage for dev)

### Configuration

Copy and edit `src/main/resources/application.properties`:

```properties
spring.datasource.url=jdbc:postgresql://localhost:5432/huevista
spring.datasource.username=your_db_user
spring.datasource.password=your_db_password

app.jwt.secret=your_jwt_secret
app.jwt.expiration-ms=900000
app.jwt.refresh-expiration-ms=604800000

spring.security.oauth2.client.registration.google.client-id=your_google_client_id
spring.security.oauth2.client.registration.google.client-secret=your_google_client_secret

# Storage: 's3' or 'local'
storage.type=local
storage.local.upload-dir=uploads/

aws.s3.bucket-name=your_bucket
aws.s3.region=ap-south-1
aws.access-key-id=your_key
aws.secret-access-key=your_secret

anthropic.api.key=your_claude_api_key
```

### Run

```bash
./mvnw spring-boot:run
```

---

## Out of Scope (MVP)

The following are real future features intentionally excluded from the MVP:
- Multi-tier organization hierarchy (distributors, painters) — manual onboarding for MVP
- Custom subdomain white-label portal
- Customer access code system
- Multiple subscription tiers — single ₹999 plan only at launch
- Automated distributor commission payouts
- Native Android app — mobile web is sufficient for MVP
- E-commerce / online order placement
- Generative AI image re-rendering
- Multiple brand catalogs — Asian Paints only at launch

---

## Why 2D Color Application (Not Generative AI) for Core Recoloring

Generative AI re-rendering was considered and rejected as the primary engine because:
- **Cost:** ₹3–8 per render destroys margins at sub-Premium tiers.
- **Latency:** 10–30 s waits between color changes is unusable.
- **Hallucination:** AI subtly changes furniture, lighting, and layout — customers say "that's not my house."

The 2D luminance-preserving approach is photorealistic by definition — it IS the original photo with only the wall's hue replaced. Generative AI remains a **Premium-tier feature** for styled final renders, not the core engine.

---

## Why B2B First, Not Direct-to-Consumer

- Consumers paint once every 5–7 years — no repeat usage.
- Paint retailers close color conversations every day — high-frequency, high-value usage.
- B2B pricing (₹999/month) is trivial for a retailer who closes ₹50,000+ in monthly sales.
- Distribution through the existing paint industry hierarchy costs far less than consumer marketing.

---

*PRD Version: 2.0 | Last Updated: May 2026 | Status: Phase 1 In Progress*
