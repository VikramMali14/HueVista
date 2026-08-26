# HueVista - Authentication Service: Complete Deep-Dive Flow

> This document explains exactly how every auth request travels through the system -
> from the moment it hits the server to the moment a response is sent back.
> Read top-to-bottom. Each section maps to real code in `src/main/java/com/gridstore/huevista/auth/`.

---

## Table of Contents

1. [Architecture Overview](#1-architecture-overview)
2. [The Spring Security Filter Chain](#2-the-spring-security-filter-chain)
3. [Flow A - Local Register (POST /api/auth/register)](#3-flow-a--local-register)
4. [Flow B - Local Login (POST /api/auth/login)](#4-flow-b--local-login)
5. [Flow C - Protected Endpoint (Bearer JWT)](#5-flow-c--protected-endpoint-bearer-jwt)
6. [Flow D - Google OAuth2 Login](#6-flow-d--google-oauth2-login)
7. [Flow G - Mobile Number Sign-In (Firebase Phone Auth)](#7-flow-g--mobile-number-sign-in-firebase-phone-auth)
8. [Flow E - Refresh Token](#8-flow-e--refresh-token)
9. [Flow F - Logout](#9-flow-f--logout)
10. [JWT Internals](#10-jwt-internals)
11. [Refresh Token Internals](#11-refresh-token-internals)
12. [Database Tables](#12-database-tables)
13. [Error Handling Reference](#13-error-handling-reference)
14. [Security Decisions Explained](#14-security-decisions-explained)
15. [Spring Boot 4.x Compatibility Notes](#15-spring-boot-4x-compatibility-notes)

---

## 1. Architecture Overview

### Tech Stack

| Layer | Technology |
|---|---|
| Framework | Spring Boot 4.0.6 (Spring Framework 7.0.7) |
| Security | Spring Security 7.0.5 |
| JWT | JJWT 0.12.6 (HMAC-SHA256) |
| OAuth2 | spring-boot-starter-oauth2-client (Google) |
| JSON | Jackson 3.x (tools.jackson.databind) - Spring Boot 4.x default |
| ORM | Spring Data JPA + Hibernate 7.x |
| Database | PostgreSQL (production), H2 in-memory (tests) |
| Passwords | BCryptPasswordEncoder (cost 10) |
| Error Handling | GlobalExceptionHandler (@RestControllerAdvice) |

### Package Layout

```
auth/
+-- config/
|   +-- SecurityConfig.java       <- filter chain, providers, AuthenticationManager
|   +-- PasswordConfig.java       <- PasswordEncoder bean (separate to avoid circular dep)
+-- controller/
|   +-- AuthController.java       <- REST endpoints
+-- dto/
|   +-- RegisterRequest.java
|   +-- LoginRequest.java
|   +-- AuthResponse.java
|   +-- RefreshTokenRequest.java
+-- filter/
|   +-- JwtAuthFilter.java        <- OncePerRequestFilter, reads Bearer token
+-- handler/
|   +-- OAuth2AuthenticationSuccessHandler.java
|   +-- OAuth2AuthenticationFailureHandler.java
+-- model/
|   +-- User.java                 <- @Entity: users table
|   +-- RefreshToken.java         <- @Entity: refresh_tokens table
|   +-- AuthProvider.java         <- enum: LOCAL | GOOGLE
+-- repository/
|   +-- UserRepository.java
|   +-- RefreshTokenRepository.java
+-- service/
    +-- AuthService.java          <- register, login, refresh, logout
    +-- JwtService.java           <- token generation and validation
    +-- CustomOAuth2UserService.java <- Google user upsert
```

### Request Path Diagram

```
Client (Browser / Mobile / Postman)
         |
         |  HTTP Request
         v
+---------------------------------------------------------------------+
|                    Servlet Container (Tomcat)                        |
|                                                                      |
|  +------------------------------------------------------------------+|
|  |              Spring Security Filter Chain                        ||
|  |                                                                  ||
|  |  1. SecurityContextHolderFilter                                  ||
|  |  2. CorsFilter          <- checks Origin header                  ||
|  |  3. CsrfFilter  (DISABLED - stateless API)                      ||
|  |  4. JwtAuthFilter  <- our custom filter                         ||
|  |  5. UsernamePasswordAuthenticationFilter                         ||
|  |  6. OAuth2AuthorizationRequestRedirectFilter  (OAuth2 only)     ||
|  |  7. OAuth2LoginAuthenticationFilter           (OAuth2 only)     ||
|  |  8. ExceptionTranslationFilter                                   ||
|  |  9. AuthorizationFilter                                          ||
|  +------------------------------------------------------------------+|
|                         |                                            |
|              DispatcherServlet                                        |
|                         |                                            |
|              AuthController / other controllers                      |
+---------------------------------------------------------------------+
         |
         |  Calls into
         v
+------------------------------------------------+
|  Auth Service Layer                             |
|  +-- AuthService        (business logic)        |
|  +-- JwtService         (token generation)      |
|  +-- CustomOAuth2UserService (Google upsert)    |
+------------------------------------------------+
         |
         v
+------------------------------------------------+
|  Persistence Layer                              |
|  +-- UserRepository      -> users table         |
|  +-- RefreshTokenRepository -> refresh_tokens   |
+------------------------------------------------+
         |
         v
    PostgreSQL Database
```

---

## 2. The Spring Security Filter Chain

Every single HTTP request to the application travels through the same ordered list of filters
before it ever reaches a controller. Understanding this chain is the key to understanding how auth works.

### What is a Filter Chain?

Java Servlet specification defines Filter objects. Each filter wraps the next one. Spring Security
registers a single FilterChainProxy as a Servlet filter, and inside it maintains a list of security
filters. Each filter either:
- Lets the request pass through (filterChain.doFilter(request, response))
- Short-circuits and writes a response directly (e.g., sends 401)

### Our Full Filter Order

```
Incoming Request
       |
       v
(1) DisableEncodeUrlFilter
    Prevents session IDs leaking into URLs. No-op for us.

(2) SecurityContextHolderFilter
    Sets up the empty SecurityContext thread-local before the chain starts.
    Clears it after the response is sent.

(3) HeaderWriterFilter
    Writes security headers (X-Content-Type-Options, etc.).

(4) CorsFilter
    Handles CORS preflight. Reads CorsConfig.corsConfigurationSource().
    Runs BEFORE JwtAuthFilter so OPTIONS preflights pass without a token.

(5) CsrfFilter  <- DISABLED in SecurityConfig
    We disable this because our API is stateless (no cookies, no sessions).
    JWT-based APIs don't need CSRF protection.

(6) LogoutFilter
    Handles /logout if configured. We implement logout in our own controller.

(7) =====================================================
    JwtAuthFilter  <- OUR CUSTOM FILTER
    =====================================================
    Reads "Authorization: Bearer <token>" header.
    Validates signature + expiry.
    Writes UsernamePasswordAuthenticationToken into SecurityContext.
    See Section 5 for full detail.

(8) UsernamePasswordAuthenticationFilter
    Handles form-login POST. Not used by us (we use our own /api/auth/login).
    Passes through for all our requests.

(9) OAuth2AuthorizationRequestRedirectFilter  (active only for oauth2Login)
    Intercepts GET /oauth2/authorization/google.
    Builds the Google authorization URL with state + nonce.
    Redirects the browser to Google's consent page.

(10) OAuth2LoginAuthenticationFilter  (active only for oauth2Login)
     Intercepts GET /login/oauth2/code/google (Google's redirect back to us).
     Exchanges the authorization code for access + ID tokens.
     Calls CustomOAuth2UserService.loadUser() to upsert our DB user.
     On success -> calls OAuth2AuthenticationSuccessHandler.
     On failure -> calls OAuth2AuthenticationFailureHandler.

(11) ExceptionTranslationFilter
     Catches AccessDeniedException and AuthenticationException from filters below.
     Sends 401 / 403 responses.

(12) AuthorizationFilter
     Final gate. Checks that the SecurityContext has a valid Authentication
     for any request that isn't .permitAll(). Throws AccessDeniedException if not.

     v
DispatcherServlet -> Controller method runs
```

### Key Rule

**JwtAuthFilter runs for every request except `/api/auth/**` and `/oauth2/**`** (via `shouldNotFilter()`).
Those endpoints are either creating the token (register/login) or handling the OAuth2 code exchange.
They don't carry a token yet.

---

## 3. Flow A - Local Register

**Endpoint:** `POST /api/auth/register`
**Body:** `{ "name": "...", "email": "...", "password": "..." }`
**No Authorization header required.**

```
Client -> POST /api/auth/register
           |
           v
  +- Filter Chain -----------------------------------------+
  |  JwtAuthFilter.shouldNotFilter() -> true               |
  |  (path starts with /api/auth/) -> SKIPPED entirely     |
  |                                                        |
  |  AuthorizationFilter sees /api/auth/** -> permitAll()  |
  +--------------------------------------------------------+
           |
           v
  AuthController.register(RegisterRequest request)
           |
           |  @Valid triggers Bean Validation BEFORE the method body runs:
           |    - @NotBlank on name, email, password
           |    - @Email format check on email
           |    - @Size(min=8) on password
           |  -> 400 Bad Request if any fail
           |
           v
  AuthService.register(request)
           |
           +-- userRepository.existsByEmail(email)
           |     -> 400 if email already taken
           |
           +-- passwordEncoder.encode(rawPassword)
           |     BCryptPasswordEncoder generates:
           |       $2a$10$<22-char-salt><31-char-hash>
           |     The raw password is NEVER stored.
           |
           +-- userRepository.save(user)
           |     INSERT INTO users (id, email, password, name, provider, ...)
           |     id is auto-generated UUID by Hibernate (GenerationType.UUID)
           |
           +-- buildAuthResponse(user)  <- shared with login + OAuth2
                    |
                    +-- jwtService.generateToken(userId, email)
                    |       Creates JWT:
                    |         header:  { "alg": "HS256" }
                    |         payload: { "sub": "<userId>", "email": "...",
                    |                    "iat": <now>, "exp": <now + 15min> }
                    |         signed with HMAC-SHA256 using the secret key
                    |
                    +-- UUID.randomUUID() -> rawRefreshToken string
                    |
                    +-- refreshTokenRepository.save(RefreshToken)
                    |       INSERT INTO refresh_tokens (token, user_id, expiry_date)
                    |       expiry = now + 7 days
                    |
                    +-- returns AuthResponse {
                              accessToken:  "eyJ...",
                              refreshToken: "550e8400-...",
                              tokenType:    "Bearer",
                              expiresIn:    900,
                              user: { id, name, email, picture, provider }
                            }
           |
           v
  HTTP 201 Created
  Body: AuthResponse JSON
```

---

## 4. Flow B - Local Login

**Endpoint:** `POST /api/auth/login`
**Body:** `{ "email": "...", "password": "..." }`

```
Client -> POST /api/auth/login
           |
           v
  +- Filter Chain -----------------------------------------+
  |  JwtAuthFilter -> SKIPPED (/api/auth/ path)            |
  |  AuthorizationFilter -> permitAll() -> PASS            |
  +--------------------------------------------------------+
           |
           v
  AuthController.login(LoginRequest)
           |
           |  @Valid -> 400 if blank / bad email format
           |
           v
  AuthService.login(request)
           |
           +-- authenticationManager.authenticate(
           |       new UsernamePasswordAuthenticationToken(email, rawPassword)
           |   )
           |
           |   AuthenticationManager delegates to:
           |
           |   ProviderManager (Spring's default AuthenticationManager)
           |       |
           |       v
           |   DaoAuthenticationProvider  (configured in SecurityConfig)
           |       |
           |       +-- calls userDetailsService.loadUserByUsername(email)
           |       |       -> UserRepository.findByEmail(email)
           |       |       -> returns UserDetails with:
           |       |             username  = userId (UUID)
           |       |             password  = BCrypt hash from DB
           |       |             authorities = []
           |       |       -> throws UsernameNotFoundException if not found
           |       |         (DaoAuthProvider turns this into BadCredentialsException
           |       |          so we don't leak whether email exists)
           |       |
           |       +-- passwordEncoder.matches(rawPassword, storedBcryptHash)
           |               -> true  -> authentication succeeds
           |               -> false -> throws BadCredentialsException -> 401
           |
           +-- (if authenticate() returned without throwing)
           |   userRepository.findByEmail(email) -> get full User entity
           |
           +-- buildAuthResponse(user)  -> same as register flow above
           |
           v
  HTTP 200 OK
  Body: AuthResponse JSON
```

### Why does loadUserByUsername receive email but return userId as the username?

`DaoAuthenticationProvider` calls `loadUserByUsername` with whatever was passed as the "username" -
we pass email. But the `UserDetails` object we return sets `username = userId`. This is intentional:
the `Authentication` object stored in the SecurityContext has `userId` as its principal name,
which is what `@AuthenticationPrincipal UserDetails` gives controllers. We use userId (not email)
as the stable identifier everywhere downstream.

---

## 5. Flow C - Protected Endpoint (Bearer JWT)

Any endpoint not under `/api/auth/**` or `/oauth2/**` requires a valid JWT.

**Example:** `GET /api/images` or `GET /api/auth/me`
**Header:** `Authorization: Bearer eyJ...`

```
Client -> GET /api/images
  Header: Authorization: Bearer eyJhbGciOiJIUzI1NiJ9...
           |
           v
  +- Filter Chain ---------------------------------------------------+
  |                                                                   |
  |  (1) JwtAuthFilter.shouldNotFilter() -> false                    |
  |      JwtAuthFilter.doFilterInternal() runs:                      |
  |                                                                   |
  |      a) extractBearerToken(request)                               |
  |            reads "Authorization" header                           |
  |            strips "Bearer " prefix                                |
  |            returns raw token string                               |
  |                                                                   |
  |      b) jwtService.extractUserId(token) -> UUID string           |
  |                                                                   |
  |      c) SecurityContextHolder.getContext().getAuthentication()    |
  |            == null (ensures we don't overwrite an existing auth)  |
  |                                                                   |
  |      d) userRepository.findById(userId)                          |
  |            DB check: confirms user hasn't been deleted since      |
  |            token was issued. If not found -> skip.               |
  |                                                                   |
  |      e) jwtService.isTokenValid(token, userId)                   |
  |            Verifies HMAC-SHA256 signature with JWT_SECRET         |
  |            Verifies token not expired (15-min TTL)                |
  |            If invalid -> skip (context stays empty)              |
  |                                                                   |
  |      f) Build UserDetails:                                        |
  |            User.builder()                                         |
  |              .username(userId)                                    |
  |              .password("")                                        |
  |              .authorities([])                                     |
  |              .build()                                             |
  |                                                                   |
  |      g) Create authentication token:                              |
  |            new UsernamePasswordAuthenticationToken(               |
  |                userDetails, null, userDetails.getAuthorities())   |
  |            .setDetails(WebAuthenticationDetailsSource             |
  |                .buildDetails(request))                            |
  |                                                                   |
  |      h) SecurityContextHolder.getContext()                        |
  |            .setAuthentication(authToken)                          |
  |            <- marks this request as authenticated                 |
  |                                                                   |
  |      i) filterChain.doFilter(request, response)  <- pass through |
  |                                                                   |
  |  (2) AuthorizationFilter                                          |
  |       getAuthentication() -> not null -> PASS                    |
  |                                                                   |
  +-------------------------------------------------------------------+
           |
           v
  Controller method runs
  @AuthenticationPrincipal UserDetails ud -> ud.getUsername() = userId
           |
           v
  HTTP 200 OK with response data
```

### What happens if the JWT is missing or invalid?

```
JwtAuthFilter:
  token == null -> skips steps b-h -> SecurityContext stays empty

AuthorizationFilter:
  getAuthentication() == null -> throws AuthenticationException

ExceptionTranslationFilter:
  catches it -> sends HTTP 401 Unauthorized
```

---

## 6. Flow D - Google OAuth2 Login

OAuth2 is a two-step redirect dance. Here is every step in detail.

### Step 1 - Frontend initiates login

```
User clicks "Login with Google" on frontend
  -> Frontend opens: GET http://localhost:8080/oauth2/authorization/google
```

```
Server receives GET /oauth2/authorization/google
           |
           v
  +- Filter Chain -----------------------------------------------------+
  |  JwtAuthFilter -> SKIPPED (/oauth2/ path)                         |
  |                                                                    |
  |  OAuth2AuthorizationRequestRedirectFilter intercepts the path:    |
  |    1. Generates random state parameter (CSRF protection for OAuth) |
  |    2. Generates nonce (replay attack protection)                   |
  |    3. Stores state in session / cookie                             |
  |    4. Builds Google Authorization URL:                             |
  |         https://accounts.google.com/o/oauth2/v2/auth?             |
  |           client_id=<your-client-id>                              |
  |           &redirect_uri=http://localhost:8080/login/oauth2/code/google
  |           &response_type=code                                      |
  |           &scope=email profile                                     |
  |           &state=<random-state>                                    |
  |    5. Sends HTTP 302 redirect to that URL                          |
  +--------------------------------------------------------------------+
```

### Step 2 - Google consent page

```
Browser -> redirected to Google
User sees "HueVista wants access to your email and profile"
User clicks Allow
Google -> redirects browser back to:
  GET http://localhost:8080/login/oauth2/code/google
      ?code=4/0AXeAR...      <- authorization code (single-use, 60s TTL)
      &state=<same-state>    <- must match what we stored
```

### Step 3 - Code exchange and user upsert

```
Server receives GET /login/oauth2/code/google?code=...&state=...
           |
           v
  +- Filter Chain ---------------------------------------------------+
  |  OAuth2LoginAuthenticationFilter intercepts this path:            |
  |                                                                   |
  |  a) Verifies state parameter matches -> prevents CSRF             |
  |                                                                   |
  |  b) Calls Google Token Endpoint (server-to-server):              |
  |       POST https://oauth2.googleapis.com/token                    |
  |         grant_type=authorization_code                             |
  |         code=<code from URL>                                      |
  |         redirect_uri=http://localhost:8080/login/oauth2/code/google
  |         client_id / client_secret                                 |
  |       Response: { access_token, id_token, expires_in, ... }       |
  |                                                                   |
  |  c) Calls Google UserInfo Endpoint:                               |
  |       GET https://www.googleapis.com/oauth2/v3/userinfo           |
  |         Authorization: Bearer <google-access-token>               |
  |       Response: { sub, email, name, picture, email_verified }     |
  |                                                                   |
  |  d) Calls CustomOAuth2UserService.loadUser(userRequest):          |
  |       -> extracts email, name, picture, sub                       |
  |       -> userRepository.findByEmail(email):                       |
  |             EXISTS  -> updateExistingUser(name, picture) -> save()|
  |             MISSING -> createOAuth2User(...) -> save()            |
  |       -> returns DefaultOAuth2User with our internal user id      |
  |                                                                   |
  |  e) Authentication succeeds -> calls:                             |
  |     OAuth2AuthenticationSuccessHandler.onAuthenticationSuccess()  |
  |       -> retrieves our User entity by email                       |
  |       -> authService.buildAuthResponse(user)                      |
  |           -> generates JWT (access token)                         |
  |           -> generates UUID refresh token -> saves to DB          |
  |           -> returns AuthResponse                                  |
  |       -> writes AuthResponse JSON to HTTP response body           |
  |                                                                   |
  |     OR on failure:                                                |
  |     OAuth2AuthenticationFailureHandler.onAuthenticationFailure()  |
  |       -> writes { error, message } JSON with 401 status           |
  +-------------------------------------------------------------------+
           |
           v
  Browser receives HTTP 200 + AuthResponse JSON
  Frontend stores access token + refresh token
```

---

## 7. Flow G - Mobile Number Sign-In (Firebase Phone Auth)

`POST /api/auth/phone/firebase`

### Why this exists

Texting a one-time code to an Indian mobile requires a **DLT** registration — a sender
id and message templates registered with TRAI through a telecom operator. HueVista does
not hold one, so `SmsSender` has no delivery provider and writes every code to the
server log instead (see its class comment). Every SMS flow in the app is therefore dark.

Firebase Phone Auth sends the code over **Google's own registered routes**. No DLT, no
SMS gateway account, and nothing to implement — the browser does the whole code exchange
with Firebase directly, and the backend only checks the result.

### The shape of it

The critical thing to understand: **our backend never sees a code, and never sends one.**
What it receives is a *Firebase ID token* — a short-lived JWT, signed by Google,
asserting "this browser proved control of +919876543210". Verifying that assertion is
the entire security boundary of this flow.

```
Browser                          Firebase (Google)              HueVista backend
   |                                    |                              |
   |-- 1. signInWithPhoneNumber ------->|                              |
   |     (+919876543210, reCAPTCHA)     |                              |
   |                                    |--- SMS to the handset        |
   |<-- 2. confirmationResult ----------|                              |
   |                                    |                              |
   |-- 3. confirm("482913") ----------->|                              |
   |<-- 4. Firebase user + ID token ----|                              |
   |                                                                   |
   |-- 5. POST /api/auth/phone/firebase { idToken, name? } ----------->|
   |                                                                   |
   |                             6. FirebaseTokenVerifier.verify       |
   |                             7. resolve or open the account        |
   |                             8. buildAuthResponse (same as every   |
   |                                other sign-in path)                |
   |<-- 9. { accessToken, refreshToken, user } -------------------------|
```

Steps 1–4 are `HueVistaFrontEnd/src/lib/firebase.ts`. Steps 6–8 are
`auth/service/PhoneAuthService` and `auth/service/FirebaseTokenVerifier`.

### Step 6 - Verifying the token

`FirebaseTokenVerifier` does this offline, with **no credential of any kind** — only the
public project id. The Firebase Admin SDK does the same job and drags in gRPC, Google
Cloud Storage and a service-account private key to do it; JJWT is already on the
classpath, so there is no new dependency and, more to the point, no secret to leak,
rotate, or forget to configure.

| Check | Why it is there |
|---|---|
| `alg` is RS256 | Refused in the key locator, before any signature work — a token claiming `none` or `HS256` must never reach the parser with a key it could be verified against |
| RS256 signature against the `kid`'s certificate | Certificates come from Google's public x509 endpoint, cached for as long as its `Cache-Control: max-age` says, refetched once on an unknown `kid` (Google rotates them) |
| `exp` future, `iat` past | 60s clock skew allowance, enforced by JJWT |
| **`aud` == our project id** | **The one that matters most.** Anyone can create a Firebase project in a minute and sign in to it as any number they control — including one read off a customer's account. Such a token is signed by these very same Google keys, so the signature alone proves nothing |
| `iss` == `https://securetoken.google.com/<project id>` | Same reason, from the other side |
| `sub` non-empty | The Firebase uid |
| `auth_time` in the past | A later one means a clock we should not believe |
| `firebase.sign_in_provider` == `phone` | Checked by `PhoneAuthService`, not the verifier. The same project can mint anonymous and email-link tokens, which prove nothing about a number — and an anonymous Firebase sign-in is free and instant, so without this it would be an account for the asking |

With `app.firebase.project-id` unset the endpoint answers **503** rather than being
lenient: with no project id there is nothing to check `aud` against.

### Step 7 - Which account the caller lands on

```
normalize the phone_number claim (PhoneNumbers.normalize — the SAME
normalizer the verification flow stores with, so the two cannot drift)
           |
           v
  live accounts with this number VERIFIED, oldest first
           |
   +-------+--------+
   |                |
 none            one or more
   |                |
   |                +-> role == ADMIN ? -> 403, use email + password
   |                |
   |                +-> clear any password lockout, sign in
   |
   +-> open a PHONE / CUSTOMER account:
         email        = Emails.syntheticForPhone(...)  (a row key, not an inbox)
         password     = null
         phoneVerified = TRUE
```

Three decisions worth spelling out:

- **Only `phoneVerified = true` matches.** Anyone can type any number into the signup
  form and nothing has proved it. If an unverified number matched, typing a stranger's
  number at signup would be all it took to be handed their account later.
- **A number verified on an email account signs into that account.** This is the case
  that matters: a customer who bought a room last month must find it waiting, not a
  second account holding half their work.
- **ADMIN is refused.** An admin password login sends a second factor by email
  (`loginWithOtp`). One SMS must not be able to skip it — a swapped SIM would otherwise
  be the whole admin console.

`VerificationService.sendPhoneCode` now refuses a number another live account has
already verified, which is the invariant this resolution rests on. The migration's index
is deliberately **not** unique: enforcing it in code fails one verification attempt with
an explanation the customer can act on, where a unique index would fail the deploy on
any existing duplicate.

### Configuration

| Where | Variable | Secret? |
|---|---|---|
| Backend | `FIREBASE_PROJECT_ID` | No — it is checked against Google's *public* certificates |
| Frontend | `NEXT_PUBLIC_FIREBASE_API_KEY` | No — a Firebase web API key identifies a project, it does not authorise anything |
| Frontend | `NEXT_PUBLIC_FIREBASE_AUTH_DOMAIN` | No (also feeds the CSP — needed at build **and** run time) |
| Frontend | `NEXT_PUBLIC_FIREBASE_PROJECT_ID` | No — must match the backend's |
| Frontend | `NEXT_PUBLIC_FIREBASE_APP_ID` | No, and optional (Analytics only) |

What actually guards the project is the **authorised-domains list** in the Firebase
console plus the backend's `aud` check. Leave the values blank and the feature is off:
the mobile option is not offered and the endpoint answers 503.

### What it costs, and the one setting that bounds it

Phone sign-in is **not** on Firebase's free tier. Since September 2024 the project must
be on the **Blaze** (pay-as-you-go) plan with a Cloud Billing account before Firebase
will send a verification SMS at all; only the first 10 a day go unbilled. India runs
about **USD 0.07 per SMS** — roughly ₹6 for every code requested, including the ones
sent to a mistyped number that get requested again a moment later.

That is far more than a DLT registration with a domestic aggregator costs per message.
This flow is the way to launch *without* a DLT registration, not the way to stay
without one; revisit it once the volume is real.

**Set the SMS region policy to allow India only** (Firebase console → Authentication →
Settings → SMS region policy) before this is public. It is the one setting that bounds
the bill. Other regions cost up to USD 0.46 per message, and a public endpoint that
will text any number on earth is a standing invitation to SMS pumping — an attacker
requests codes to premium-rate numbers they collect revenue on, and the charges are
yours. Nothing in this codebase can prevent that; the region policy can.

Per-IP rate limiting on `POST /api/auth/phone/firebase` does **not** help here, because
the SMS is sent by Firebase before that endpoint is ever reached. The controls that
bound SMS spend all live in the Firebase console: the region policy, Firebase's own
per-number and per-IP quotas, and the reCAPTCHA the client must pass.

---

## 8. Flow E - Refresh Token

When the 15-minute access token expires, the frontend silently gets a new one.

**Endpoint:** `POST /api/auth/refresh`
**Body:** `{ "refreshToken": "550e8400-..." }`

```
Client -> POST /api/auth/refresh
           |
           v
  +- Filter Chain -----------------------------------------+
  |  JwtAuthFilter -> SKIPPED (/api/auth/ path)            |
  |  AuthorizationFilter -> permitAll() -> PASS            |
  +--------------------------------------------------------+
           |
           v
  AuthController.refresh(RefreshTokenRequest)
           |
           v
  AuthService.refreshToken(rawToken)
           |
           +-- refreshTokenRepository.findByToken(rawToken)
           |     -> NOT FOUND -> 400 (already rotated or never existed)
           |
           +-- stored.getExpiryDate().isBefore(Instant.now())
           |     -> EXPIRED:
           |         refreshTokenRepository.delete(stored)  <- clean up
           |         throw -> 400 "Refresh token expired - please log in again"
           |
           +-- VALID:
           |     refreshTokenRepository.delete(stored)     <- ROTATION: old token gone
           |     buildAuthResponse(stored.getUser())       <- brand new pair issued
           |       -> new access token (JWT)
           |       -> new refresh token (UUID, 7 more days)
           |       -> both saved to DB
           |
           +-- returns AuthResponse with fresh tokens
           |
           v
  HTTP 200 OK
  Body: AuthResponse JSON (new access + refresh tokens)
```

### Why rotate the refresh token?

Refresh token rotation means each refresh token can only be used **once**. After use it is deleted
and a new one is issued. If an attacker steals an old refresh token and tries to use it after the
legitimate user has already refreshed, it will be gone from the DB -> 400.

---

## 9. Flow F - Logout

**Endpoint:** `POST /api/auth/logout`
**Header:** `Authorization: Bearer <accessToken>`

```
Client -> POST /api/auth/logout
  Header: Authorization: Bearer eyJ...
           |
           v
  +- Filter Chain -----------------------------------------------+
  |  JwtAuthFilter runs (this IS a protected endpoint):           |
  |    -> validates JWT -> extracts userId                        |
  |    -> sets Authentication in SecurityContext                  |
  |                                                               |
  |  AuthorizationFilter -> authenticated -> PASS                |
  +--------------------------------------------------------------+
           |
           v
  AuthController.logout(@AuthenticationPrincipal UserDetails ud)
    ud.getUsername() = userId (from JWT subject)
           |
           v
  AuthService.logout(userId)
           |
           +-- userRepository.findById(userId)  -> get User entity
           |
           +-- refreshTokenRepository.deleteByUser(user)
                 DELETE FROM refresh_tokens WHERE user_id = ?
                 <- ALL refresh tokens for this user are wiped
           |
           v
  HTTP 200 OK
  Body: { "message": "Logged out successfully" }

NOTE: The access token itself is NOT invalidated (JWTs are stateless).
      It remains technically valid until its 15-minute expiry.
      This is acceptable because:
        - 15 min is short
        - All refresh tokens are gone so no new access tokens can be issued
        - For stricter logout: implement a token blacklist (Redis set of jti claims)
```

---

## 10. JWT Internals

### Structure

```
eyJhbGciOiJIUzI1NiJ9                          <- Base64url(header JSON)
.
eyJzdWIiOiI1NTBlODQwMC1lMjliLTQxZDQtYTcxNi00NDY2NTU0NDBjZTQiLCJlbWFpbCI6InVzZXJAZXhhbXBsZS5jb20iLCJpYXQiOjE3NDY4NjAwMDAsImV4cCI6MTc0Njg2MDkwMH0
.
HMAC_SHA256_SIGNATURE                          <- keyed hash of header + "." + payload
```

**Header decoded:**
```json
{ "alg": "HS256" }
```

**Payload decoded:**
```json
{
  "sub":   "550e8400-e29b-41d4-a716-446655440000",
  "email": "user@example.com",
  "iat":   1746860000,
  "exp":   1746860900
}
```

### Signing key

The secret in `application.properties` must be a **Base64-encoded string of at least 32 bytes**
(256 bits) to satisfy HMAC-SHA256. Generate it with:

```bash
openssl rand -base64 32
```

The key is decoded at runtime in `JwtService.getSigningKey()`:
```java
byte[] keyBytes = Decoders.BASE64.decode(jwtSecret);
return Keys.hmacShaKeyFor(keyBytes);
```

### Validation steps (inside JwtService.isTokenValid)

```
Jwts.parser()
  .verifyWith(signingKey)     <- 1. Recompute HMAC, compare to signature
  .build()
  .parseSignedClaims(token)   <- 2. Check exp claim > current time
                              <- 3. Check iat claim <= current time
```

| Exception | Cause |
|---|---|
| `SignatureException` | Signature doesn't match (tampered or wrong key) |
| `ExpiredJwtException` | exp is in the past |
| `MalformedJwtException` | Not a valid 3-part JWT |
| `UnsupportedJwtException` | Algorithm not as expected |

All are caught and return `false` from `isTokenValid()`.

---

## 11. Refresh Token Internals

Refresh tokens are **opaque** - just a random UUID stored in the DB. They don't carry information;
they are a DB lookup key.

```
refresh_tokens table:
+--------------------------------------------------------------+
| id (UUID PK) | token (UUID, unique) | user_id | expiry_date  |
|--------------|---------------------|---------|--------------|
| abc-123      | 550e8400-...        | def-456 | 2026-05-17   |
+--------------------------------------------------------------+
```

**Lifecycle:**
```
Login / Register / OAuth2
      |
      v  INSERT new refresh token row

Client stores token (localStorage / httpOnly cookie)
      |
      |  (15 min later, access token expires)
      v
POST /api/auth/refresh  { refreshToken: "550e8400-..." }
      |
      v  SELECT by token value -> check expiry
      v  DELETE old row
      v  INSERT new row with new UUID and fresh 7-day expiry
      |
      v  Return new access + refresh tokens to client

Logout:
      v  DELETE all rows WHERE user_id = ?
```

---

## 12. Database Tables

### users

| Column | Type | Notes |
|---|---|---|
| `id` | VARCHAR(36) PK | UUID generated by Hibernate |
| `email` | VARCHAR UNIQUE NOT NULL | Login identifier |
| `password` | VARCHAR NULL | BCrypt hash; NULL for OAuth2 users |
| `name` | VARCHAR NOT NULL | Display name |
| `picture` | VARCHAR NULL | Google profile photo URL |
| `provider` | VARCHAR NOT NULL | LOCAL or GOOGLE |
| `provider_id` | VARCHAR NULL | Google's sub claim |
| `email_verified` | BOOLEAN | Always true for Google users |
| `created_at` | TIMESTAMP | Set on insert |
| `updated_at` | TIMESTAMP | Updated on every save |

### refresh_tokens

| Column | Type | Notes |
|---|---|---|
| `id` | VARCHAR(36) PK | UUID |
| `token` | VARCHAR UNIQUE NOT NULL | Random UUID (the actual token value) |
| `user_id` | VARCHAR(36) FK -> users.id | |
| `expiry_date` | TIMESTAMP | 7 days from creation |

---

## 13. Error Handling Reference

All errors pass through `GlobalExceptionHandler` in `common/exception/GlobalExceptionHandler.java`.

**Standard error response shape:**
```json
{
  "status":    400,
  "error":     "Bad Request",
  "message":   "Human-readable description",
  "timestamp": "2026-05-12T10:30:00.000"
}
```

| Scenario | HTTP Status | Notes |
|---|---|---|
| Validation fails (blank field, bad email) | 400 | MethodArgumentNotValidException |
| Email already registered | 400 | Thrown in AuthService.register() |
| Wrong password | 401 | Spring Security BadCredentials |
| Missing / invalid JWT | 401 | Spring Security 401 |
| Expired JWT | 401 | Spring Security 401 |
| Unknown userId in JWT (deleted user) | 401 | No auth set in SecurityContext |
| Expired refresh token | 400 | Thrown in AuthService.refreshToken() |
| Refresh token not found | 400 | Thrown in AuthService.refreshToken() |
| OAuth2 failure | 401 | OAuth2AuthenticationFailureHandler |

---

## 14. Security Decisions Explained

### Why separate access token (15 min) and refresh token (7 days)?

- Access token is short-lived so a stolen JWT can only be exploited for 15 minutes.
- Refresh token is long-lived so users don't have to log in every 15 minutes.
- Refresh token is in the DB so it can be revoked instantly (logout, suspicious activity).
- Access token cannot be revoked without a blacklist - the 15-min TTL is the mitigation.

### Why userId in JWT subject, not email?

Email can change. UUID never changes. A stable identifier in the sub claim avoids breakage if a
user updates their email.

### Why BCrypt?

BCrypt is an adaptive hashing algorithm - its cost factor can be increased as hardware gets faster.
It includes a built-in salt so identical passwords produce different hashes.
Spring's BCryptPasswordEncoder defaults to cost factor 10 (~100ms per hash).

### Why no CSRF?

CSRF attacks exploit browser cookie auto-submission. Our API uses `Authorization: Bearer` headers,
which browsers do NOT automatically attach. Therefore CSRF is not a threat.

### Why disable session creation?

`SessionCreationPolicy.STATELESS` tells Spring Security to never create or use an HTTP session.
Our auth is 100% token-based. No session = no session fixation attacks, no memory waste.

### Why rotate refresh tokens?

If a refresh token is stolen and used, the attacker gets a new token and the legitimate user's old
one is gone. The next time the legitimate user tries to refresh, it will fail (token not in DB).
With non-rotating tokens, a stolen refresh token is valid indefinitely until logout.

---

## 15. Spring Boot 4.x Compatibility Notes

### Jackson 3.x - package namespace change

Spring Boot 4.x ships with **Jackson 3.x**. Jackson 3.x moved its package namespace:

| Spring Boot | Jackson | ObjectMapper import |
|---|---|---|
| 2.x / 3.x | 2.x | `com.fasterxml.jackson.databind.ObjectMapper` |
| 4.x | 3.x | `tools.jackson.databind.ObjectMapper` |

Our OAuth2 handlers use `tools.jackson.databind.ObjectMapper`.
Note: `com.fasterxml.jackson` 2.x is still on the runtime classpath (pulled in by `jjwt-jackson`)
but is NOT used for Spring MVC serialization.

### PasswordConfig - breaking the circular dependency

Spring Boot 4.x prohibits circular bean dependencies. The original design had `PasswordEncoder`
as a `@Bean` inside `SecurityConfig`, which caused a startup cycle:

```
SecurityConfig
  <- injects: OAuth2AuthenticationSuccessHandler
                  <- injects: AuthService
                                  <- injects: PasswordEncoder <- @Bean in SecurityConfig (!)
```

**Fix:** `PasswordEncoder` was extracted to a standalone `PasswordConfig` class:

```
PasswordConfig           <- created first, no dependencies
  +-- PasswordEncoder @Bean

AuthService              <- needs PasswordEncoder (from PasswordConfig, not SecurityConfig)
  +-- @Lazy AuthenticationManager  <- proxy; resolved only on first login call

OAuth2AuthenticationSuccessHandler
  +-- needs AuthService  <- now safe

SecurityConfig           <- created last
  +-- needs OAuth2AuthenticationSuccessHandler  <- fine
  +-- needs PasswordEncoder (from PasswordConfig)  <- fine
```

### @Lazy on AuthenticationManager in AuthService

`AuthService` injects `AuthenticationManager` with `@Lazy`. This injects a proxy that is only
resolved when `authenticationManager.authenticate(...)` is first called (on the first login
request), after all beans are fully initialized.

### DaoAuthenticationProvider - Spring Security 7.x constructor change

Spring Security 7.x (used by Spring Boot 4.x) requires `UserDetailsService` as a constructor
argument:

```java
// Old way (removed):
DaoAuthenticationProvider provider = new DaoAuthenticationProvider();
provider.setUserDetailsService(userDetailsService());

// New way (required):
DaoAuthenticationProvider provider = new DaoAuthenticationProvider(userDetailsService());
```

### Lombok annotation processing - Maven compiler plugin

Maven Compiler Plugin 3.14.x no longer auto-discovers annotation processors from the compile
classpath. Lombok requires explicit registration in `pom.xml`:

```xml
<plugin>
    <groupId>org.apache.maven.plugins</groupId>
    <artifactId>maven-compiler-plugin</artifactId>
    <configuration>
        <annotationProcessorPaths>
            <path>
                <groupId>org.projectlombok</groupId>
                <artifactId>lombok</artifactId>
            </path>
        </annotationProcessorPaths>
    </configuration>
</plugin>
```

### Test configuration

The `HueVistaApplicationTests.contextLoads` test loads the full Spring ApplicationContext.
It overrides all environment-dependent properties via `@TestPropertySource` so the test
runs without a live PostgreSQL, real AWS credentials, or real API keys:

```java
@SpringBootTest
@TestPropertySource(properties = {
    "spring.datasource.url=jdbc:h2:mem:testdb;DB_CLOSE_DELAY=-1",
    "spring.datasource.driver-class-name=org.h2.Driver",
    "spring.datasource.username=sa",
    "spring.datasource.password=",
    "spring.jpa.database-platform=org.hibernate.dialect.H2Dialect",
    "spring.security.oauth2.client.registration.google.client-id=test-client-id",
    "spring.security.oauth2.client.registration.google.client-secret=test-client-secret",
    "app.jwt.secret=dGVzdC1zZWNyZXQta2V5LWZvci11bml0LXRlc3RzLW9ubHk=",
    "app.claude.api-key=test-claude-api-key",
    "app.upload.storage-path=/tmp/huevista/test-uploads",
    "app.cors.allowed-origins=http://localhost:3000",
    "app.s3.region=ap-south-1",   // overrides IntelliJ env var if incorrectly set
})
```

The `app.s3.region` override is needed because `S3Config` is `@ConditionalOnProperty(name = "app.s3.bucket-name")`.
If the `S3_BUCKET_NAME` environment variable leaks from IntelliJ into the test run (via Spring's
relaxed binding), S3Config activates and reads `app.s3.region`. Providing the correct region code
here prevents an invalid URI from being constructed during context load.

---

*Document maintained alongside the codebase. Update when auth behavior changes.*
