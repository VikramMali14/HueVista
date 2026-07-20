# HueVista Mobile — Implementation Plan (Handoff Document)

> **Who this is for:** any AI agent or developer picking up the mobile app build.
> Read this file top to bottom before writing any code. It contains everything
> you need: context, locked decisions, design reference, API map, architecture,
> and phase-by-phase task checklists.
>
> **How to use it:** work through the phases in order. As you complete a task,
> flip its `- [ ]` to `- [x]` and commit the edit together with the work.
> Append one line to the **Progress log** (bottom of this file) per work
> session. This file is the single source of truth for what is done and what
> is pending — keep it honest.

---

## 1. Context — what HueVista is

HueVista is an AI-powered paint shade visualizer for the Indian paint retail
market. Users photograph a room and preview real catalogue shades (Asian
Paints, Berger, Nerolac, Dulux, Nippon — ~8,000 shades) applied
photorealistically before painting.

Distribution follows the paint trade hierarchy:

```
Distributor → Retailer (paying subscriber) → Painter → End Customer
```

**User roles** (backend enum `UserRole`): `ADMIN`, `DISTRIBUTOR`, `RETAILER`,
`PAINTER`, `CUSTOMER`. Organizations (`OrgType`) are `DISTRIBUTOR` or
`RETAILER`; painters link to retailer orgs; customers enter via time-limited
access codes issued by retailers.

**Existing repos:**

| Repo | Stack | Role |
|---|---|---|
| `VikramMali14/HueVista` | Spring Boot 4, Java 17, PostgreSQL, Flyway | Backend REST API — serves web AND mobile. **Do not modify** except for the explicitly listed additions in §9. |
| `VikramMali14/HueVistaFrontEnd` | Next.js 15, React 19, TypeScript | Website. Source for design tokens, API client patterns, Zod schemas — **copy from it, never modify it** as part of mobile work. |
| `VikramMali14/HueVistaMobile` | React Native + Expo (TypeScript) | **The app this plan builds. Does not exist yet — creating it is Phase 0.** |

The backend README (`HueVista/README.md`) documents the platform, subscription
tiers, and core endpoints. Swagger is available at
`http://localhost:8080/swagger-ui.html` when the backend runs locally.

---

## 2. Locked decisions — do not relitigate

These were agreed with the owner. Change them only if the owner says so.

1. **Separate repository** `HueVistaMobile` — not a folder in either existing
   repo, not a monorepo.
2. **React Native + Expo, TypeScript, Expo Router.** Not Flutter, not native
   Swift/Kotlin, not a WebView wrapper.
3. **One app for all roles.** After login the app reads the account's role and
   renders that role's tab navigator. No separate per-role apps.
4. **Admin stays on the web.** No admin screens in the mobile app.
5. **Backend is consumed as-is.** Same JWT auth, same endpoints the website
   uses. The only backend additions allowed are listed in §9.
6. **Visual identity: "Midnight Spectrum"** carried over from the website
   (tokens in §4). The app is dark-themed by design — one theme, no
   light mode at launch.
7. **Recolor engine runs on-device** (GPU, luminance-preserving — same
   technique as the website's WebGL engine) so color changes stay instant and
   free. AI segmentation stays server-side (it is quota-billed).

---

## 3. Design reference

The full visual design — 12 phone-screen mockups, app map, navigation tables,
rationale — lives next to this file: **`docs/mobile-app/design.html`**
(open it in any browser). Summary:

### Entry flow (all users)

```
Open app ─► Welcome screen
              ├─► Sign in (email / Google)          — existing accounts
              ├─► Create account                    — new customers
              ├─► "My paint shop gave me a code"    — access-code redeem (customer)
              └─► Painter invite link (deep link)   — painter onboarding
                        │
                        ▼
        Server returns role ─► app mounts that role's tab navigator
```

### Tabs per role

| Role | Bottom tabs | Notes |
|---|---|---|
| CUSTOMER | Home · Shades · **Visualize (raised center)** · Projects · Account | Core loop: photo → try shades → save → share → order |
| RETAILER | Counter · Codes · Orders · Painters · Account | Counter dashboard: AI quota meter, walk-ins, quick actions |
| PAINTER | Jobs · Visualize · Earnings · Account | Jobs carry approved shades + litres + site address |
| DISTRIBUTOR | Network · Codes · Reports · Account | Retailer health, renewals due, invites |

### Screens designed (see design.html for exact layouts)

1. **Welcome** — brand moment, "Sign in" / "My paint shop gave me a code"
2. **Shop code redeem** — code field `HV-XXXXXX`, shows linked shop card
3. **Sign in** — email+password, Google, forgot password
4. **Customer home** — big "Visualize a room" CTA, recent projects, AI picks
5. **Camera capture** — full-screen camera, wall-detection overlay, gallery pick
6. **Visualizer editor (hero screen)** — before/after compare slider, region
   chips (Main wall / Left wall / Ceiling / + tap to add), horizontal shade
   tray, "AI suggest" + "Share" actions, auto-save indicator
7. **Shade library** — search, brand chips, mood/family chips, swatch grid,
   shade detail card with "Try on wall"
8. **Share sheet** — WhatsApp, copy link, "Send to shop for a quote", save image
9. **Retailer counter dashboard** — quota meter (e.g. 43/60), stat tiles
   (walk-ins / active codes / pending orders / week's order value), "New
   walk-in visualization" button, today's activity feed
10. **Access codes** — list with status pills (ACTIVE / EXPIRING / EXPIRED),
    bottom sheet: 3/7/14-day selector → "Create & send on WhatsApp"
11. **Painter jobs list + job detail** — status pills (NEW / IN PROGRESS /
    DONE·PAID), approved shades with codes and litres, site address +
    navigate, "Add site photo", "Mark complete"
12. **Distributor network** — territory stats, retailer rows with health dot,
    renewal follow-ups, "Invite a new retailer"
13. **Support chat** (all roles) — existing AI-assisted support module

---

## 4. Design tokens — Midnight Spectrum

Copy these exactly (source: `HueVistaFrontEnd/src/app/globals.css`).

```ts
export const colors = {
  bg:          '#0a090f',   // app background
  bgDeep:      '#050409',
  surface:     '#14131c',   // cards  (web uses #121119; mobile mockups use #14131c — pick one, stay consistent)
  surface2:    '#1b1a26',   // sheets, elevated surfaces
  fg:          '#eae8e3',   // primary text
  fgSoft:      '#a7a4bb',   // secondary text
  fgMute:      '#6d6a84',   // tertiary / disabled
  accent:      '#7c5cff',   // electric purple — primary actions
  accentSoft:  '#a080ff',
  accentDeep:  '#5a3fcc',
  rule:        'rgba(234,232,227,0.09)',  // hairline borders
  success:     '#7fae84',   // sage
  danger:      '#d0654c',   // terracotta
  warning:     '#d9b45c',
};
```

**Type:** Space Grotesk (headings / display, weights 500–700, via
`expo-font` + Google Fonts package), system default for body, JetBrains Mono
(or platform mono) for shade codes, access codes, prices.

**Shape language:** cards radius 13, buttons radius 11, pills fully rounded,
status pills are UPPERCASE mono 8–10pt. Status colors: NEW=accent,
IN PROGRESS/EXPIRING=warning, DONE/ACTIVE=success, EXPIRED/OVERDUE=danger.

---

## 5. Backend API map

Base URL from env (`EXPO_PUBLIC_API_ORIGIN`, default `http://localhost:8080`).
All endpoints below exist today. **Treat the backend controllers as the source
of truth** — verify request/response shapes against the Java controllers or
Swagger before wiring each screen; do not guess fields.

| Feature | Endpoints (controller package in `HueVista`) |
|---|---|
| Auth | `POST /api/auth/register`, `POST /api/auth/login`, `POST /api/auth/refresh`, Google OAuth (`auth/`) — JWT access + refresh token |
| Verification | `/api/auth/verify/*` (email/SMS codes — gate before project creation when enabled) |
| Image upload | `POST /api/images/upload` (multipart; server classifies via Claude) (`image/`) |
| Shade catalogue | `GET /api/shades`, `/api/shades/{brand}`, `/{brand}/families`, `/{brand}/{code}` (`paint/`) |
| Projects | `POST/GET /api/projects`, `GET /api/projects/{id}`, `PUT .../regions`, `POST .../segment` (async — poll `GET .../status`), `POST .../segment/point` (SAM2 click refine), region mask endpoints, `POST .../send-to-shop`, `POST .../share` → `GET /api/shared/{token}` (`project/`) |
| AI recommendations | `POST /api/projects/{projectId}/recommendations` (`ai/`) |
| Billing | `POST /api/billing/subscriptions`, `GET /api/billing/subscriptions/current`, project-credit endpoints (`billing/`) — Razorpay |
| Orgs & access codes | `POST /api/organizations`, `POST /api/organizations/{orgId}/access-codes`, `POST /api/access-codes/redeem` (`account/`) |
| Painters | `POST /api/organizations/{retailerOrgId}/painter-invitations`, `POST /api/painter-invitations/redeem`, `GET /api/painters/me`, `PUT /api/painters/me`, `GET /api/painters/me/retailers`, by-retailer listing (`painter/`) |
| Paint jobs | `/api/jobs` — `GET /mine/painter`, `GET /mine/customer`, `GET /{jobId}`, `POST /{jobId}/accept·decline·start·complete·cancel` (`painter/`) |
| Store | `/api/store/{slug}` public storefront, `POST /{slug}/order`, `POST /{slug}/verify`, store links, wallet + redemptions per org (`store/`) |
| Guest mode | `/api/guest/*` — limited browse without account (`guest/`) |
| Support | `/api/support/*` — AI-assisted chat threads (`support/`) |

**Auth handling on mobile:** access token in memory; refresh token in
`expo-secure-store` (Android Keystore / iOS Keychain) — the mobile equivalent
of the website's HttpOnly cookie. Auto-refresh on 401, single-flight. Optional
biometric unlock later (Phase 4).

---

## 6. App architecture (HueVistaMobile)

```
HueVistaMobile/
├── app/                          # Expo Router file-based routes
│   ├── (auth)/                   #   welcome, sign-in, register, redeem-code, painter-invite
│   ├── (customer)/               #   tabs: home, shades, visualize, projects, account
│   ├── (retailer)/               #   tabs: counter, codes, orders, painters, account
│   ├── (painter)/                #   tabs: jobs, visualize, earnings, account
│   ├── (distributor)/            #   tabs: network, codes, reports, account
│   └── _layout.tsx               #   root: fonts, theme, auth gate, role router
├── src/
│   ├── api/                      # typed client per backend module (auth, projects, shades, jobs…)
│   ├── auth/                     # token store (secure-store), session context, refresh logic
│   ├── engine/                   # recolor engine (Skia/GL) — mask + luminance-preserving tint
│   ├── components/               # ui kit: Button, Card, Pill, Meter, SwatchGrid, ShadeTray…
│   ├── theme/                    # tokens from §4
│   └── offline/                  # catalogue cache, project draft queue
├── assets/                       # icon, splash, fonts
├── app.json / eas.json           # Expo + build config
└── .github/workflows/ci.yml     # typecheck + lint + test on PR
```

**Key libraries:** `expo` (SDK — latest stable), `expo-router`,
`@shopify/react-native-skia` (recolor engine), `expo-camera`,
`expo-image-picker`, `expo-secure-store`, `expo-notifications` (Phase 2),
`react-native-razorpay` (Phase 2), `zod` (validation — reuse website schemas
where possible), `@tanstack/react-query` (server state, retries, cache).

**Recolor engine (the one genuinely new piece):** the website recolors in
WebGL preserving per-pixel luminance so texture/shadows survive. On mobile,
implement the same math as a Skia shader (or GL): for each pixel inside the
region mask, replace hue/chroma with the target shade while keeping the
original luminance. Masks come from the backend segmentation endpoints as
images. **Build and prove this first in Phase 1 — it is the only technical
risk.** A throwaway spike screen that loads a bundled test photo + mask and
recolors at 60fps is the gate for the rest of Phase 1.

---

## 7. Phase checklists

> Rules: finish phases in order; within a phase, tasks are roughly ordered.
> Every phase ends with a working, committed, pushed app. Never leave the
> repo in a state that doesn't build.

### Phase 0 — Repository & foundations

- [ ] Create GitHub repo `VikramMali14/HueVistaMobile` (private)
- [ ] Scaffold Expo app (TypeScript template, Expo Router), commit clean baseline
- [ ] Add theme module with §4 tokens; load Space Grotesk via expo-font
- [ ] Build base UI kit: Button, Card, Pill, Input, SheetModal, StatTile, Meter
- [ ] Typed API client with base URL from env + error normalization
- [ ] Auth store: secure-store refresh token, in-memory access token, 401 auto-refresh
- [ ] CI workflow: typecheck + eslint + unit tests on every push
- [ ] README.md: how to run (`npx expo start`), how to point at a backend, repo map

### Phase 1 — Customer core (the product)

- [ ] **Recolor engine spike** (see §6) — bundled photo + mask recolored live at interactive framerate on a real Android device. Gate for everything below.
- [ ] Welcome screen (brand moment, three entry paths)
- [ ] Sign in / register / forgot password against existing endpoints
- [ ] Google sign-in (expo-auth-session against existing Google OAuth flow)
- [ ] Access-code redeem screen (`POST /api/access-codes/redeem`) incl. linked-shop card
- [ ] Role router: on session start, mount tab navigator for the account's role (customer first; other roles show a "coming in phase 2/3" placeholder screen)
- [ ] Customer home: CTA, recent projects, AI picks strip
- [ ] Camera capture + gallery pick → `POST /api/images/upload` → create project
- [ ] Segmentation flow: trigger `POST .../segment`, poll status, handle failure + tap-to-refine (`segment/point`)
- [ ] Visualizer editor: region chips, shade tray, before/after compare, auto-save regions (`PUT .../regions`)
- [ ] Shade library: search + brand/family filters against `/api/shades`, offline cache
- [ ] "Try on wall" from any shade → visualizer with shade preselected
- [ ] AI suggest: `POST .../recommendations` surfaced in editor
- [ ] Projects list + detail (resume editing)
- [ ] Share: render result image, native share sheet (WhatsApp first), share link (`POST .../share`), save to gallery
- [ ] "Send to shop for a quote" (`POST .../send-to-shop`)
- [ ] Guest browse mode (shade library only) via `/api/guest/*`

### Phase 2 — Retailer counter mode (the subscriber)

- [ ] Retailer tab navigator + counter dashboard (quota via billing endpoints, stat tiles, activity feed)
- [ ] "New walk-in visualization" fast path (photo → shades → send, minimal taps)
- [ ] Access codes: list with status, create sheet (3/7/14 days), WhatsApp hand-off
- [ ] Orders list (store module) with status updates
- [ ] Painters tab: invite painter (link generation + share), painter list
- [ ] Subscription screen: current plan, renew/upgrade via Razorpay checkout SDK
- [ ] Push notifications: backend device-token registration (§9), then — shared-look opened, new order, renewal reminder
- [ ] Retailer onboarding polish: empty states that teach the walk-in flow

### Phase 3 — Painter & distributor (the network)

- [ ] Painter invite deep link → redeem (`POST /api/painter-invitations/redeem`) → painter role
- [ ] Painter jobs list (`GET /api/jobs/mine/painter`) with status pills
- [ ] Job detail: approved shades + litres, address (open in maps), accept/decline/start/complete actions, site photo upload
- [ ] Painter earnings screen (wallet endpoints)
- [ ] Painter gets the visualizer (same customer engine, painter context)
- [ ] Distributor navigator: network dashboard (org hierarchy endpoints), retailer health, renewals due
- [ ] Distributor: invite retailer flow; codes + reports tabs
- [ ] Support chat screen (all roles) against `/api/support/*`
- [ ] Notifications inbox screen (mirror of website inbox)

### Phase 4 — Launch (the stores)

- [ ] App icon + splash (Midnight Spectrum brand)
- [ ] EAS build profiles: dev / preview / production for both platforms
- [ ] Crash reporting (sentry-expo) + basic analytics events (visualize started, share sent, code redeemed)
- [ ] Over-the-air updates channel (EAS Update) for JS-only fixes
- [ ] Store listings: screenshots from the design mockup set, descriptions (English + Kannada/Hindi)
- [ ] Google Play submission (owner provides dev account) → closed testing → production
- [ ] Apple App Store submission (owner provides dev account) → TestFlight → review
- [ ] Post-launch: monitor crashes, fix, iterate

---

## 8. Working agreements for the executing agent

1. **Update this file as you go** — checkboxes + progress log. That is how the
   owner (who is not a mobile developer) sees status.
2. **Small commits, clear messages**, conventional prefix (`feat:`, `fix:`,
   `chore:`). Push at every stable point.
3. **Verify API shapes against the backend code/Swagger** before wiring a
   screen; the table in §5 is a map, not a contract.
4. **Never modify `HueVista` or `HueVistaFrontEnd`** except the §9 additions
   (backend) — and do those on a branch with tests, matching that repo's
   conventions (Flyway migration for any schema change).
5. **Test on a real Android device early** (owner's phone via Expo Go /
   dev build); iOS via Expo Go until Phase 4.
6. **Keep the app runnable** — `npx expo start` must always work from a fresh
   clone + `npm install`.
7. **Ask the owner** before: spending money (accounts, services), changing a
   §2 locked decision, or any backend change beyond §9.

## 9. Allowed backend additions (only these)

| When | Addition | Notes |
|---|---|---|
| Phase 2 | `POST /api/devices` (+ entity/migration) to register Expo push tokens per user; notification fan-out on: share-link opened, order created, job assigned, renewal approaching | Follow existing module conventions (`notification/` exists); Flyway migration; tests |
| Phase 2 (optional) | Server-driven WhatsApp code send for access codes | Support module already has WhatsApp webhook plumbing to build on |
| Phase 1 (optional, nice-to-have) | Phone-sized image variant in upload response | Only if mobile bandwidth proves painful; otherwise skip |

---

## 10. Progress log

> Append one line per work session: date · phase · what happened · blockers.

| Date | Phase | Summary |
|---|---|---|
| 2026-07-20 | — | Plan + visual design created and committed (this document). App repo not yet created. Next action: Phase 0. |
