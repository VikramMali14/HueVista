# HueVista · Postman collection

Three files in this folder:

| File | What it is |
|---|---|
| `HueVista.postman_collection.json` | The collection — every backend endpoint, grouped into 11 folders, with sample bodies and auto-capture test scripts. |
| `HueVista.postman_environment.json` | Local environment (`baseUrl=http://localhost:8080`). |
| `HueVista.staging.postman_environment.json` | Empty staging environment scaffold — fill in `baseUrl` and credentials. |

## Importing

1. Open Postman → top-left **Import** button.
2. Drag all three JSON files in at once, or use **File** → **Upload Files**.
3. In the top-right environment switcher, pick **HueVista · Local** (or Staging).

## How the auto-capture works

The collection uses collection-level **Bearer token** auth pointing at `{{accessToken}}`. The login and register requests have test scripts that automatically write back:

- `accessToken`, `refreshToken`
- `userId`
- and (for the relevant requests) `imageId`, `projectId`, `regionId`, `shareToken`, `retailerOrgId`, `distributorOrgId`, `accessCode`, `painterInvitationCode`, `jobId`, `subscriptionId`

So you can step through a flow without copy-pasting IDs.

## Recommended run order (smoke test)

```
0 · Auth                                                          
  Register                              # captures accessToken + userId
  Get me                                # confirms auth works

1 · Images                              
  Upload image (multipart)              # captures imageId — attach any JPEG/PNG

3 · Projects                            
  Create project                        # captures projectId
  Get project                           # captures regionId (after segment)
  Start auto segmentation               # async; poll status next
  Poll status                           # repeat until SEGMENTED
  Update region colors (auto-save)
  Generate share link                   # captures shareToken

4 · Shared Project (public)             
  Get shared project by token

5 · AI Recommendations                  
  Generate color recommendations        # requires active subscription

6 · Billing                             
  List plans
  Create subscription                   # requires RAZORPAY_PLAN_* env vars on backend
  Current subscription

7 · Organizations & Customers           
  Create retailer org                   # captures retailerOrgId
  Generate customer access code         # captures accessCode

  # Sign in as a different user, then:
  Redeem access code (as customer)

8 · Painters (Phase 2)                  
  Generate painter invitation           # captures painterInvitationCode
  # Sign in as the painter, then:
  Redeem painter invitation
  My painter profile
  Update my painter profile

9 · Paint Jobs (Phase 2)                
  # Set {{painterUserId}} manually after the painter redeems
  # As retailer owner:
  Create job                            # captures jobId
  # As painter:
  Accept job
  Start job
  Complete job

10 · Admin                              
  # Requires adminToken — see "Admin access" below
  Platform stats
  List users
```

## Admin access

The admin folder uses a separate `{{adminToken}}` variable. The backend grants `ROLE_ADMIN` based on `User.role = ADMIN`, which isn't a self-service flag — you have to set it directly in the database:

```sql
UPDATE users SET role = 'ADMIN' WHERE email = 'you@example.com';
```

Then log in as that user and copy the resulting `accessToken` into the `adminToken` environment variable.

## Multipart upload

The "Upload image" request uses `multipart/form-data`. Click on the **Body** tab → **form-data** row → in the `file` row, change the value type from "text" to "file" via the dropdown if not already, then click "Select Files".

## Webhooks

The `POST /api/billing/webhooks/razorpay` endpoint is NOT in the collection — it's signed with `RAZORPAY_WEBHOOK_SECRET` and called by Razorpay's servers, not by clients. If you need to test it, use the Razorpay dashboard's webhook test feature or generate a signed payload with the same HMAC-SHA256 scheme.

## OAuth2

Google login (`/oauth2/authorization/google`) goes through a browser redirect flow which Postman can't drive end-to-end. Use a real browser for that path; the resulting JWT works in this collection just like one from `/api/auth/login`.

## Updating the collection

Endpoints in `src/main/java/com/gridstore/huevista/**/controller` are the source of truth. When you add or change a route, update the matching item in the collection JSON and bump the description / readme. Run `./mvnw test` first — the integration tests touch most of these paths and will surface signature mismatches before clients hit them.
