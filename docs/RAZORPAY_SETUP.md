# Razorpay setup — test mode first, then activation

Everything the payment code in this repo needs from a Razorpay account, in the
order you should do it. Part 1–4 gets you a working **test** environment. Part 5
is the go-live / website-review checklist.

Nothing here needs code changes: every value is an environment variable already
wired into [`application.properties`](../src/main/resources/application.properties).

---

## 0. What the code already expects

Six money flows are implemented. Five use one-time **Orders**, one uses
**Subscriptions**:

| Flow | Razorpay product | Backend endpoint | Where in the UI |
|---|---|---|---|
| Monthly plan (Starter / Professional / Business) | Subscriptions | `POST /api/billing/subscriptions` → `.../verify` | `/pricing`, `/subscription` |
| Wallet top-up | Orders | `POST /api/billing/wallet/topup/order` → `.../verify` | `/subscription` |
| One extra image (₹50) | Orders | `POST /api/billing/image-credits/order` → `.../verify` | in-app quota prompt |
| Buy a project | Orders | `POST /api/projects/credits/order` → `.../verify` | project create |
| Reopen an expired project | Orders | same controller, reopen variant | project list |
| In-store kiosk (walk-in customer pays ₹99) | Orders | `POST /api/store/{slug}/order` → `.../verify` | `/store/{slug}` (public) |

All of them verify the Checkout signature server-side before granting anything,
and the webhook receiver at `POST /api/billing/webhooks/razorpay` handles the
subscription lifecycle and refunds.

**Environment variables — that's the whole list:**

```
RAZORPAY_KEY_ID=
RAZORPAY_KEY_SECRET=
RAZORPAY_WEBHOOK_SECRET=
RAZORPAY_PLAN_STARTER=
RAZORPAY_PLAN_PROFESSIONAL=
RAZORPAY_PLAN_BUSINESS=
```

---

## 1. Create the account and get test keys

1. Sign up at <https://dashboard.razorpay.com>. Pick the correct business type
   during signup — **Individual / Proprietorship** if you are not a registered
   company. This is what your KYC documents must match later.
2. Flip the dashboard to **Test Mode** (toggle at the top of the sidebar).
   Test mode works immediately; you do **not** need KYC for it.
3. **Settings → API Keys → Generate Test Key.** You get:
   - Key ID — looks like `rzp_test_xxxxxxxxxxxx`
   - Key Secret — **shown exactly once**, copy it now

Put them in your backend `.env`:

```
RAZORPAY_KEY_ID=rzp_test_xxxxxxxxxxxx
RAZORPAY_KEY_SECRET=<the secret>
```

> The Key ID is sent to the browser on purpose (Checkout needs it). The **secret
> never leaves the server** — it signs and verifies payments. Never put it in
> the Next.js repo or any `NEXT_PUBLIC_*` variable.

---

## 2. Enable Subscriptions and create the three plans

Orders work out of the box. **Subscriptions is a separate product you must
request** — until it is enabled, `POST /api/billing/subscriptions` will fail
with a gateway error.

1. Dashboard → **Subscriptions** in the left sidebar → request/enable access.
   (Test mode is usually enabled quickly; live mode needs activation first.)
2. Still in **Test Mode**, go to **Subscriptions → Plans → Create Plan** and
   create three plans:

| Plan | Billing cycle | Amount | Notes |
|---|---|---|---|
| Starter | Monthly, every 1 month | **₹999.00** (99900 paise) | |
| Professional | Monthly, every 1 month | **₹2,499.00** (249900 paise) | |
| Business | Monthly, every 1 month | **₹4,999.00** (499900 paise) | |

3. Copy each plan ID (`plan_xxxxxxxxxxxx`) into `.env`:

```
RAZORPAY_PLAN_STARTER=plan_xxxxxxxxxxxx
RAZORPAY_PLAN_PROFESSIONAL=plan_xxxxxxxxxxxx
RAZORPAY_PLAN_BUSINESS=plan_xxxxxxxxxxxx
```

### ⚠ The amounts must match `Plan.java` exactly

The app **does not verify** that the dashboard plan amount equals the price it
shows the customer. If they differ, the pricing page advertises one number and
Razorpay charges another — a chargeback and a review problem.

The source of truth is
[`Plan.java`](../src/main/java/com/gridstore/huevista/billing/model/Plan.java):
`STARTER(99900)`, `PROFESSIONAL(249900)`, `BUSINESS(499900)`, with
`GST_PERCENT = 0`. **GST is currently zero**, so the dashboard amount is the
base price with nothing added. If you register for GST later, bump
`GST_PERCENT` to 18 **and** recreate the plans at the new gross amounts —
Razorpay plan amounts cannot be edited after creation.

### ENTERPRISE has no plan ID — that's intentional

`ENTERPRISE` is priced at `-1` (custom). It is listed on the pricing page but is
not sold through Checkout. No dashboard plan needed.

---

## 3. Set up the webhook

The webhook is what keeps subscriptions correct over time: renewals, failed
payments, cancellations and refunds. Checkout verification only covers the
*first* payment.

**Endpoint:** `https://<your-backend-host>/api/billing/webhooks/razorpay`

For local testing your machine needs a public HTTPS URL. Use a tunnel:

```bash
# either one works
ngrok http 8080
cloudflared tunnel --url http://localhost:8080
```

Then in the dashboard (still Test Mode): **Settings → Webhooks → Add New Webhook**

- **Webhook URL:** `https://<tunnel-id>.ngrok-free.app/api/billing/webhooks/razorpay`
- **Secret:** invent a long random string (`openssl rand -base64 32`) and paste
  the *same* value into `.env` as `RAZORPAY_WEBHOOK_SECRET`.
- **Active events** — tick exactly these nine, which are the ones
  [`RazorpayWebhookService`](../src/main/java/com/gridstore/huevista/billing/service/RazorpayWebhookService.java)
  handles:

  ```
  subscription.activated
  subscription.charged
  subscription.cancelled
  subscription.completed
  subscription.halted
  payment.captured
  payment.failed
  refund.created
  refund.processed
  ```

> **The webhook secret is not optional.** With `RAZORPAY_WEBHOOK_SECRET` blank
> the service rejects every webhook with 401 (fail-closed by design — the
> endpoint is public, so an unsigned event could otherwise forge a paid plan).
> Subscriptions would still activate via Checkout verify, but renewals,
> cancellations and refunds would silently stop working.

Each tunnel restart gives a new URL — update the webhook, or keep a reserved
ngrok domain.

---

## 4. Test each flow

Restart the backend after filling `.env`, and confirm the log **does not** say
`Razorpay credentials not configured`.

### Test instruments (test mode only)

| Method | Value | Result |
|---|---|---|
| Card | `4111 1111 1111 1111`, any future expiry, any CVV | success (choose Success on the OTP screen) |
| Card | same card, choose **Failure** on the OTP screen | failed payment |
| UPI | `success@razorpay` | success |
| UPI | `failure@razorpay` | failure |
| Netbanking | any bank → simulator page | your choice |

Full list: <https://razorpay.com/docs/payments/payments/test-card-details/>

**Use a card for subscriptions.** UPI Autopay is a separately-enabled mandate
product; cards are the reliable recurring-payment path in test mode.

### The runs to make

1. **Subscribe** — `/pricing` → Starter → pay with the test card. Expect: modal
   closes, plan shows ACTIVE immediately (Checkout verify path), and
   `subscription.activated` lands in the log a few seconds later.
2. **Dismiss Checkout without paying** — plan must stay unchanged, no error toast.
3. **Cancel** — `/subscription` → cancel. Access must continue until period end.
4. **Upgrade** — Starter → Professional. The old subscription must be superseded,
   not doubled.
5. **Wallet top-up** — a small amount, then check the balance and statement row.
6. **Overage** — spend the image quota, buy one extra image at ₹50, confirm the
   quota went up by exactly one. Then **replay the same verify call** (resend it
   in Postman) and confirm it does *not* grant a second image.
7. **Buy a project / reopen a project** — check the validity window.
8. **Kiosk** — open `/store/{slug}` in an incognito window (it is public, no
   login), pay ₹99 with UPI `success@razorpay`, confirm an access code is issued
   and the shop earned 39 points (kiosk panel, and a `KIOSK_BONUS` row in the
   wallet statement).
9. **Spend the points** — with no active plan, buy a project from the balance and
   confirm it debits ₹50/₹99 and issues a project credit. With a plan, buy an
   extra image from the balance instead.
10. **Refund** — refund the kiosk payment from the dashboard and confirm the
    points are clawed back (`refund.processed` →
    `WalletService.reverseKioskPayment`). Spend the points *first* on one run to
    confirm the balance is allowed to go negative rather than silently keeping
    the reward for a refunded sale.
11. **Failed payment** — pay a subscription with the Failure option and confirm
    the plan does not activate.

Watch **Dashboard → Webhooks → the webhook → Logs** for delivery status; a
non-2xx there means the app rejected or errored on the event (401 = secret
mismatch).

The Postman collection in [`docs/postman/`](postman/) has these endpoints ready.

---

## 5. Going live: activation and website review

Razorpay reviews your **website** before enabling live payments. Do this part
only after the test flows all pass.

### 5a. KYC (Account → Activation Form)

Have ready: PAN, bank account + cancelled cheque / statement, address proof,
GSTIN **only if registered** (you can activate without one as an individual),
and your business category — pick **SaaS / Software** for HueVista.

### 5b. Website requirements — what reviewers look for

| Requirement | Status in this project |
|---|---|
| Live site on your own domain, HTTPS, no placeholder content | your deployment |
| Clear description of what is sold | landing + `/pricing` ✅ |
| Prices shown in ₹, tax position stated | `/pricing` reads live from `/api/billing/plans` ✅ |
| Terms & Conditions | `/legal/terms` ✅ |
| Privacy Policy | `/legal/privacy` ✅ |
| Refund / Cancellation Policy | `/legal/refunds` ✅ |
| **Contact Us page** — email **and** phone **and** business address | ❌ **missing** — the footer only has a `mailto:` link |
| **Shipping / Delivery Policy** | ❌ **missing** — for a digital service, state that access is delivered electronically and instantly to the account, with no physical shipment |
| Legal entity name shown on the site | verify it matches your Razorpay application |

**Fix the two ❌ rows before submitting.** A missing Contact Us page with a
working phone number is the single most common rejection reason.

### 5c. Fix the email domain mismatch

The site and the backend currently disagree about your domain:

- Frontend footer + legal pages: `hello@huevista.com`
- Backend `app.mail.billing-from`: `payments@huevista.org`
- Backend `app.store.redemption-email`: `redemeamount@huevista.org`

Pick one domain, make every address live on it, and make sure it is the same
domain you submit to Razorpay. Receipts arriving from a domain that isn't the
one on the application is a review flag.

### 5d. The kiosk is a plain B2C sale — keep it that way

Worth knowing what you are declaring, because this was deliberately changed to
be answerable in one line.

A walk-in at `/store/{slug}` pays a **flat ₹99 that is entirely HueVista's**, for
a HueVista visualisation. The shop does not set that price and takes no share of
it. What the shop earns is **39 reward points** credited to its billing wallet —
₹39 of spending power on extra images, AI auto-masks and projects, with no way to
withdraw it as cash.

So on your application the kiosk is simply "customers buy a room visualisation
from us at a fixed price", and there is no third-party settlement to explain.

**Do not add a cash-out path for points.** Paying a shop's balance to a bank
account or UPI id would turn every kiosk sale back into a payment collected on
that shop's behalf — which needs **Razorpay Route** (linked accounts + transfers)
rather than a plain merchant account, and is the kind of thing that surfaces as a
settlement hold rather than a rejection letter. The earlier revenue-share model
did exactly this; it was removed for this reason.

### 5e. Redo every test-mode object in live mode

Test and live are completely separate. **Nothing carries over.**

1. Generate **live** API keys (`rzp_live_…`).
2. Get Subscriptions enabled on the **live** account.
3. **Create the three plans again** in live mode — the IDs are different.
4. **Create the webhook again** in live mode, pointing at your production URL,
   with a new secret and the same nine events.
5. Update production env vars with all six live values and restart.
6. Enable the payment methods you want under **Settings → Payment Methods**
   (cards, UPI, netbanking).
7. Do one real ₹1–₹5 transaction end to end, then refund it from the dashboard.

### 5f. Before you announce

- `RAZORPAY_KEY_SECRET` and `RAZORPAY_WEBHOOK_SECRET` live in your secrets store,
  never in git. Rotate anything that was ever pasted into a chat or a log.
- `APP_BASE_URL` and `CORS_ALLOWED_ORIGINS` point at the production frontend.
- `MAIL_ENABLED=true` with real SMTP — payment receipts are part of the review
  expectation, and the retailer verification gate depends on it.
- Settlement schedule and bank account confirmed under **Settings → Settlements**.
