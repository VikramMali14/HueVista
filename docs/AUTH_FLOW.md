# HueVista — Authentication Service: Complete Deep-Dive Flow

> This document explains **exactly** how every auth request travels through the system —
> from the moment it hits the server to the moment a response is sent back.
> Read top-to-bottom. Each section maps to real code in `src/main/java/com/gridstore/huevista/auth/`.

---

## Table of Contents

1. [Architecture Overview](#1-architecture-overview)
2. [The Spring Security Filter Chain](#2-the-spring-security-filter-chain)
3. [Flow A — Local Register (`POST /api/auth/register`)](#3-flow-a--local-register)
4. [Flow B — Local Login (`POST /api/auth/login`)](#4-flow-b--local-login)
5. [Flow C — Protected Endpoint (Bearer JWT)](#5-flow-c--protected-endpoint-bearer-jwt)
6. [Flow D — Google OAuth2 Login](#6-flow-d--google-oauth2-login)
7. [Flow E — Refresh Token](#7-flow-e--refresh-token)
8. [Flow F — Logout](#8-flow-f--logout)
9. [JWT Internals](#9-jwt-internals)
10. [Refresh Token Internals](#10-refresh-token-internals)
11. [Database Tables](#11-database-tables)
12. [Error Handling Reference](#12-error-handling-reference)
13. [Security Decisions Explained](#13-security-decisions-explained)

---

## 1. Architecture Overview

```
Client (Browser / Mobile / Postman)
         │
         │  HTTP Request
         ▼
┌─────────────────────────────────────────────────────────────────────┐
│                    Servlet Container (Tomcat)                        │
│                                                                      │
│  ┌──────────────────────────────────────────────────────────────┐   │
│  │              Spring Security Filter Chain                     │   │
│  │                                                               │   │
│  │  1. SecurityContextHolderFilter                               │   │
│  │  2. CsrfFilter  (DISABLED — stateless API)                    │   │
│  │  3. JwtAuthFilter  ◄── our custom filter                      │   │
│  │  4. UsernamePasswordAuthenticationFilter                      │   │
│  │  5. OAuth2AuthorizationRequestRedirectFilter  (OAuth2 only)   │   │
│  │  6. OAuth2LoginAuthenticationFilter           (OAuth2 only)   │   │
│  │  7. ExceptionTranslationFilter                                │   │
│  │  8. AuthorizationFilter                                       │   │
│  └──────────────────────────────────────────────────────────────┘   │
│                         │                                            │
│              DispatcherServlet                                        │
│                         │                                            │
│              AuthController / other controllers                      │
└─────────────────────────────────────────────────────────────────────┘
         │
         │  Calls into
         ▼
┌────────────────────────────────────────────────┐
│  Auth Service Layer                             │
│  ├── AuthService        (business logic)        │
│  ├── JwtService         (token generation)      │
│  └── CustomOAuth2UserService (Google upsert)    │
└────────────────────────────────────────────────┘
         │
         ▼
┌────────────────────────────────────────────────┐
│  Persistence Layer                              │
│  ├── UserRepository      → users table          │
│  └── RefreshTokenRepository → refresh_tokens   │
└────────────────────────────────────────────────┘
         │
         ▼
    PostgreSQL Database
```

---

## 2. The Spring Security Filter Chain

Every single HTTP request to the application — whether it's a login, a protected API call, or an OAuth2 redirect — travels through the **same ordered list of filters** before it ever reaches a controller. Understanding this chain is the key to understanding how auth works.

### What is a Filter Chain?

Java Servlet specification defines `Filter` objects. Each filter wraps the next one. Spring Security registers a single `FilterChainProxy` as a Servlet filter, and inside it maintains a list of security filters. Each filter either:
- Lets the request pass through (`filterChain.doFilter(request, response)`)
- Short-circuits and writes a response directly (e.g., sends 401)

### Our Full Filter Order

```
Incoming Request
       │
       ▼
① DisableEncodeUrlFilter
   └── Prevents session IDs leaking into URLs. No-op for us.

② SecurityContextHolderFilter
   └── Sets up the empty SecurityContext thread-local before the chain starts.
       Clears it after the response is sent. Critical — all auth state lives here.

③ HeaderWriterFilter
   └── Writes security headers (X-Content-Type-Options, etc.). Cosmetic for API.

④ CorsFilter
   └── Handles CORS preflight. Relevant when frontend is on a different origin.

⑤ CsrfFilter  ← DISABLED in SecurityConfig
   └── We disable this because our API is stateless (no cookies, no sessions).
       JWT-based APIs don't need CSRF protection.

⑥ LogoutFilter
   └── Handles /logout if configured. We implement logout in our own controller.

⑦ ══════════════════════════════════════════════════════════
   JwtAuthFilter  ← OUR CUSTOM FILTER (addFilterBefore position)
   ══════════════════════════════════════════════════════════
   └── Reads "Authorization: Bearer <token>" header.
       Validates signature + expiry.
       Writes UsernamePasswordAuthenticationToken into SecurityContext.
       See Section 5 for full detail.

⑧ UsernamePasswordAuthenticationFilter
   └── Handles form-login POST. Not used by us (we use our own /api/auth/login).
       Passes through for all our requests.

⑨ OAuth2AuthorizationRequestRedirectFilter  (active only for oauth2Login)
   └── Intercepts GET /oauth2/authorization/google
       Builds the Google authorization URL with state + nonce.
       Redirects the browser to Google's consent page.

⑩ OAuth2LoginAuthenticationFilter  (active only for oauth2Login)
   └── Intercepts GET /login/oauth2/code/google (Google's redirect back to us).
       Exchanges the authorization code for access + ID tokens.
       Calls CustomOAuth2UserService.loadUser() to upsert our DB user.
       On success → calls OAuth2AuthenticationSuccessHandler.
       On failure → calls OAuth2AuthenticationFailureHandler.

⑪ ExceptionTranslationFilter
   └── Catches AccessDeniedException and AuthenticationException from filters below.
       Sends 401 / 403 responses.

⑫ AuthorizationFilter
   └── Final gate. Checks that the SecurityContext has a valid Authentication
       for any request that isn't .permitAll(). Throws AccessDeniedException if not.

       ▼
DispatcherServlet → Controller method runs
```

### Key Rule

**JwtAuthFilter runs for every request except `/api/auth/**` and `/oauth2/**`** (via `shouldNotFilter()`). This is intentional: those endpoints are either creating the token (register/login) or handling the OAuth2 code exchange. They don't carry a token yet.

---

## 3. Flow A — Local Register

**Endpoint:** `POST /api/auth/register`
**Body:** `{ "name": "...", "email": "...", "password": "..." }`
**No Authorization header required.**

```
Client → POST /api/auth/register
           │
           ▼
  ┌─ Filter Chain ──────────────────────────────────────────────┐
  │  JwtAuthFilter.shouldNotFilter() → true                     │
  │  (path starts with /api/auth/) → SKIPPED entirely           │
  │                                                             │
  │  AuthorizationFilter sees /api/auth/** → permitAll() → PASS │
  └─────────────────────────────────────────────────────────────┘
           │
           ▼
  AuthController.register(RegisterRequest request)
           │
           │  @Valid triggers Bean Validation BEFORE the method body runs:
           │    • @NotBlank on name, email, password
           │    • @Email format check on email
           │    • @Size(min=8) on password
           │  → 400 Bad Request if any fail
           │
           ▼
  AuthService.register(request)
           │
           ├── userRepository.existsByEmail(email)
           │     → 400 if email already taken
           │
           ├── passwordEncoder.encode(rawPassword)
           │     BCryptPasswordEncoder generates:
           │       $2a$10$<22-char-salt><31-char-hash>
           │     The raw password is NEVER stored.
           │
           ├── userRepository.save(user)
           │     INSERT INTO users (id, email, password, name, provider, ...)
           │     id is auto-generated UUID by Hibernate (GenerationType.UUID)
           │
           └── buildAuthResponse(user)  ← shared with login + OAuth2
                    │
                    ├── jwtService.generateToken(userId, email)
                    │       Creates JWT:
                    │         header:  { "alg": "HS256" }
                    │         payload: { "sub": "<userId>", "email": "...",
                    │                    "iat": <now>, "exp": <now + 15min> }
                    │         signed with HMAC-SHA256 using the secret key
                    │
                    ├── UUID.randomUUID() → rawRefreshToken string
                    │
                    ├── refreshTokenRepository.save(RefreshToken)
                    │       INSERT INTO refresh_tokens (token, user_id, expiry_date)
                    │       expiry = now + 7 days
                    │
                    └── returns AuthResponse {
                              accessToken:  "eyJ...",
                              refreshToken: "550e8400-...",
                              tokenType:    "Bearer",
                              expiresIn:    900,
                              user: { id, name, email, picture, provider }
                            }
           │
           ▼
  HTTP 201 Created
  Body: AuthResponse JSON
```

---

## 4. Flow B — Local Login

**Endpoint:** `POST /api/auth/login`
**Body:** `{ "email": "...", "password": "..." }`

```
Client → POST /api/auth/login
           │
           ▼
  ┌─ Filter Chain ──────────────────────────────────────────────┐
  │  JwtAuthFilter → SKIPPED (/api/auth/ path)                  │
  │  AuthorizationFilter → permitAll() → PASS                   │
  └─────────────────────────────────────────────────────────────┘
           │
           ▼
  AuthController.login(LoginRequest)
           │
           │  @Valid → 400 if blank / bad email format
           │
           ▼
  AuthService.login(request)
           │
           ├── authenticationManager.authenticate(
           │       new UsernamePasswordAuthenticationToken(email, rawPassword)
           │   )
           │
           │   ↓ AuthenticationManager delegates to:
           │
           │   ProviderManager (Spring's default AuthenticationManager)
           │       │
           │       ▼
           │   DaoAuthenticationProvider  (configured in SecurityConfig)
           │       │
           │       ├── calls userDetailsService.loadUserByUsername(email)
           │       │       → UserRepository.findByEmail(email)
           │       │       → returns UserDetails with:
           │       │             username  = userId (UUID)
           │       │             password  = BCrypt hash from DB
           │       │             authorities = []
           │       │       → throws UsernameNotFoundException if not found
           │       │         (DaoAuthProvider turns this into BadCredentialsException
           │       │          so we don't leak whether email exists)
           │       │
           │       └── passwordEncoder.matches(rawPassword, storedBcryptHash)
           │               → true  → authentication succeeds
           │               → false → throws BadCredentialsException → 401
           │
           ├── (if authenticate() returned without throwing)
           │   userRepository.findByEmail(email) → get full User entity
           │
           └── buildAuthResponse(user)  → same as register flow above
           │
           ▼
  HTTP 200 OK
  Body: AuthResponse JSON
```

### Why does `loadUserByUsername` receive email but return userId as the username?

`DaoAuthenticationProvider` calls `loadUserByUsername` with whatever was passed as the "username" — we pass email. But the `UserDetails` object we return sets `username = userId`. This is intentional: the `Authentication` object stored in the SecurityContext has `userId` as its principal name, which is what `@AuthenticationPrincipal UserDetails` gives controllers. We use userId (not email) as the stable identifier everywhere downstream.

---

## 5. Flow C — Protected Endpoint (Bearer JWT)

Any endpoint not under `/api/auth/**` or `/oauth2/**` requires a valid JWT.

**Example:** `GET /api/projects` (future endpoint)
**Header:** `Authorization: Bearer eyJ...`

```
Client → GET /api/projects
  Header: Authorization: Bearer eyJhbGciOiJIUzI1NiJ9...
           │
           ▼
  ┌─ Filter Chain ──────────────────────────────────────────────────────┐
  │                                                                      │
  │  ① JwtAuthFilter.shouldNotFilter() → false (not /api/auth/ path)    │
  │     JwtAuthFilter.doFilterInternal() runs:                          │
  │                                                                      │
  │     a) extractBearerToken(request)                                   │
  │           reads "Authorization" header                               │
  │           strips "Bearer " prefix                                    │
  │           returns raw token string                                   │
  │                                                                      │
  │     b) jwtService.isTokenValid(token)                                │
  │           Jwts.parser()                                              │
  │             .verifyWith(signingKey)   ← HMAC-SHA256 verification     │
  │             .build()                                                 │
  │             .parseSignedClaims(token)                                │
  │           If signature invalid → JwtException → returns false        │
  │           If exp claim < now   → JwtException → returns false        │
  │           If all good          → returns true                        │
  │                                                                      │
  │     c) jwtService.extractUserId(token)  → UUID string               │
  │        jwtService.extractEmail(token)   → email string              │
  │                                                                      │
  │     d) SecurityContextHolder.getContext().getAuthentication() == null│
  │           (ensures we don't overwrite an existing auth)              │
  │                                                                      │
  │     e) userRepository.existsById(userId)                             │
  │           DB check: confirms user hasn't been deleted since token     │
  │           was issued. If false → skip, context stays empty.          │
  │                                                                      │
  │     f) Build UserDetails:                                            │
  │           User.builder()                                             │
  │             .username(userId)                                        │
  │             .password("")                                            │
  │             .authorities([])                                         │
  │             .build()                                                 │
  │                                                                      │
  │     g) Create authentication token:                                  │
  │           new UsernamePasswordAuthenticationToken(                   │
  │               userDetails, null, userDetails.getAuthorities())       │
  │           .setDetails(WebAuthenticationDetailsSource                 │
  │               .buildDetails(request))  ← attaches IP, session info  │
  │                                                                      │
  │     h) SecurityContextHolder.getContext()                            │
  │           .setAuthentication(authToken)                              │
  │           ← THE KEY LINE: marks this request as authenticated        │
  │                                                                      │
  │     i) filterChain.doFilter(request, response)  ← pass through      │
  │                                                                      │
  │  ② AuthorizationFilter                                               │
  │       getAuthentication() → not null → PASS                         │
  │                                                                      │
  └──────────────────────────────────────────────────────────────────────┘
           │
           ▼
  Controller method runs
  @AuthenticationPrincipal UserDetails ud → ud.getUsername() = userId
           │
           ▼
  HTTP 200 OK with response data
```

### What happens if the JWT is missing or invalid?

```
JwtAuthFilter:
  token == null → skips steps b–h → SecurityContext stays empty

AuthorizationFilter:
  getAuthentication() == null → throws AuthenticationException

ExceptionTranslationFilter:
  catches it → sends HTTP 401 Unauthorized
  Body: { "error": "Unauthorized" }
```

---

## 6. Flow D — Google OAuth2 Login

OAuth2 is a two-step redirect dance. Here is every step in detail.

### Step 1 — Frontend initiates login

```
User clicks "Login with Google" on frontend
  → Frontend opens: GET http://localhost:8080/oauth2/authorization/google
```

```
Server receives GET /oauth2/authorization/google
           │
           ▼
  ┌─ Filter Chain ────────────────────────────────────────────────────┐
  │  JwtAuthFilter → SKIPPED (/oauth2/ path)                         │
  │                                                                    │
  │  OAuth2AuthorizationRequestRedirectFilter intercepts the path:    │
  │    1. Generates random state parameter (CSRF protection for OAuth) │
  │    2. Generates nonce (replay attack protection)                   │
  │    3. Stores state in session / cookie                             │
  │    4. Builds Google Authorization URL:                             │
  │         https://accounts.google.com/o/oauth2/v2/auth?             │
  │           client_id=<your-client-id>                               │
  │           &redirect_uri=http://localhost:8080/login/oauth2/code/google
  │           &response_type=code                                      │
  │           &scope=email profile                                     │
  │           &state=<random-state>                                    │
  │    5. Sends HTTP 302 redirect to that URL                          │
  └────────────────────────────────────────────────────────────────────┘
```

### Step 2 — Google consent page

```
Browser → redirected to Google
User sees "HueVista wants access to your email and profile"
User clicks Allow
Google → redirects browser back to:
  GET http://localhost:8080/login/oauth2/code/google
      ?code=4/0AXeAR...      ← authorization code (single-use, 60s TTL)
      &state=<same-state>    ← must match what we stored
```

### Step 3 — Code exchange and user upsert

```
Server receives GET /login/oauth2/code/google?code=...&state=...
           │
           ▼
  ┌─ Filter Chain ──────────────────────────────────────────────────────────┐
  │  OAuth2LoginAuthenticationFilter intercepts /login/oauth2/code/google:  │
  │                                                                          │
  │  a) Verifies state parameter matches what was stored → prevents CSRF     │
  │                                                                          │
  │  b) Calls Google Token Endpoint (server-to-server, not visible to user): │
  │       POST https://oauth2.googleapis.com/token                           │
  │         grant_type=authorization_code                                    │
  │         code=<code from URL>                                             │
  │         redirect_uri=http://localhost:8080/login/oauth2/code/google      │
  │         client_id=<our client id>                                        │
  │         client_secret=<our secret>                                       │
  │       Response: { access_token, id_token, expires_in, ... }              │
  │                                                                          │
  │  c) Calls Google UserInfo Endpoint using the access_token:               │
  │       GET https://www.googleapis.com/oauth2/v3/userinfo                  │
  │         Authorization: Bearer <google-access-token>                      │
  │       Response: { sub, email, name, picture, email_verified }            │
  │                                                                          │
  │  d) Calls CustomOAuth2UserService.loadUser(userRequest):                 │
  │       → receives the attributes map from Google                          │
  │       → extracts email, name, picture, sub (Google's user ID)            │
  │       → userRepository.findByEmail(email):                               │
  │             EXISTS  → updateExistingUser(name, picture) → save()         │
  │             MISSING → createOAuth2User(...) → save()                     │
  │       → returns DefaultOAuth2User with our internal user id              │
  │                                                                          │
  │  e) Authentication succeeds → calls:                                     │
  │     OAuth2AuthenticationSuccessHandler.onAuthenticationSuccess()         │
  │       → retrieves our User entity by email                               │
  │       → authService.buildAuthResponse(user)                              │
  │           → generates JWT (access token)                                 │
  │           → generates UUID refresh token → saves to DB                   │
  │           → returns AuthResponse                                          │
  │       → writes AuthResponse JSON to HTTP response body                   │
  │                                                                          │
  │     OR on failure:                                                       │
  │     OAuth2AuthenticationFailureHandler.onAuthenticationFailure()         │
  │       → writes { error, message } JSON with 401 status                   │
  └──────────────────────────────────────────────────────────────────────────┘
           │
           ▼
  Browser receives HTTP 200 + AuthResponse JSON
  Frontend stores access token + refresh token
```

---

## 7. Flow E — Refresh Token

When the 15-minute access token expires, the frontend silently gets a new one.

**Endpoint:** `POST /api/auth/refresh`
**Body:** `{ "refreshToken": "550e8400-..." }`

```
Client → POST /api/auth/refresh
           │
           ▼
  ┌─ Filter Chain ──────────────────────────────────────────────┐
  │  JwtAuthFilter → SKIPPED (/api/auth/ path)                  │
  │  AuthorizationFilter → permitAll() → PASS                   │
  └─────────────────────────────────────────────────────────────┘
           │
           ▼
  AuthController.refresh(RefreshTokenRequest)
           │
           ▼
  AuthService.refreshToken(rawToken)
           │
           ├── refreshTokenRepository.findByToken(rawToken)
           │     → NOT FOUND → 400 (token was already rotated or never existed)
           │
           ├── stored.getExpiryDate().isBefore(Instant.now())
           │     → EXPIRED:
           │         refreshTokenRepository.delete(stored)  ← clean up
           │         throw → 400 "Refresh token expired — please log in again"
           │
           ├── VALID:
           │     refreshTokenRepository.delete(stored)     ← ROTATION: old token gone
           │     buildAuthResponse(stored.getUser())       ← brand new pair issued
           │       → new access token (JWT)
           │       → new refresh token (UUID, 7 more days)
           │       → both saved to DB
           │
           └── returns AuthResponse with fresh tokens
           │
           ▼
  HTTP 200 OK
  Body: AuthResponse JSON (new access + refresh tokens)
```

### Why rotate the refresh token?

Refresh token rotation means each refresh token can only be used **once**. After use it is deleted and a new one is issued. If an attacker steals an old refresh token and tries to use it after the legitimate user has already refreshed, it will be gone from the DB → 400. This limits the damage window of a stolen refresh token.

---

## 8. Flow F — Logout

**Endpoint:** `POST /api/auth/logout`
**Header:** `Authorization: Bearer <accessToken>`

```
Client → POST /api/auth/logout
  Header: Authorization: Bearer eyJ...
           │
           ▼
  ┌─ Filter Chain ──────────────────────────────────────────────────────┐
  │  JwtAuthFilter runs (this IS a protected endpoint):                  │
  │    → validates JWT → extracts userId                                 │
  │    → sets Authentication in SecurityContext                          │
  │                                                                      │
  │  AuthorizationFilter → authenticated → PASS                         │
  └──────────────────────────────────────────────────────────────────────┘
           │
           ▼
  AuthController.logout(@AuthenticationPrincipal UserDetails ud)
    ud.getUsername() = userId (from JWT subject)
           │
           ▼
  AuthService.logout(userId)
           │
           ├── userRepository.findById(userId)  → get User entity
           │
           └── refreshTokenRepository.deleteByUser(user)
                 DELETE FROM refresh_tokens WHERE user_id = ?
                 ← ALL refresh tokens for this user are wiped
           │
           ▼
  HTTP 200 OK
  Body: { "message": "Logged out successfully" }

  NOTE: The access token itself is NOT invalidated (JWTs are stateless).
        It will remain technically valid until its 15-minute expiry.
        This is acceptable because:
          • 15 min is short
          • All refresh tokens are gone so no new access tokens can be issued
          • For stricter logout: implement a token blacklist (Redis set of jti claims)
```

---

## 9. JWT Internals

### Structure

```
eyJhbGciOiJIUzI1NiJ9   ← Base64url(header JSON)
.
eyJzdWIiOiI1NTBlODQwMC1lMjliLTQxZDQtYTcxNi00NDY2NTU0NDBjZTQiLCJlbWFpbCI6InVzZXJAZXhhbXBsZS5jb20iLCJpYXQiOjE3NDY4NjAwMDAsImV4cCI6MTc0Njg2MDkwMH0
.
HMAC_SHA256_SIGNATURE   ← keyed hash of header + "." + payload
```

**Header decoded:**
```json
{ "alg": "HS256" }
```

**Payload decoded:**
```json
{
  "sub":   "550e8400-e29b-41d4-a716-446655440000",  ← userId (UUID)
  "email": "user@example.com",
  "iat":   1746860000,
  "exp":   1746860900
}
```

### Signing key

The secret in `application.properties` must be a **Base64-encoded string of at least 32 bytes** (256 bits) to satisfy HMAC-SHA256. Generate it with:

```bash
openssl rand -base64 32
```

The key is decoded at runtime in `JwtService.getSigningKey()`:
```java
byte[] keyBytes = Decoders.BASE64.decode(jwtSecret);
return Keys.hmacShaKeyFor(keyBytes);
```

### Validation steps (inside `JwtService.isTokenValid`)

```
Jwts.parser()
  .verifyWith(signingKey)     ← 1. Recompute HMAC, compare to signature
  .build()
  .parseSignedClaims(token)   ← 2. Check exp claim > current time
                              ← 3. Check iat claim <= current time
```

Any failure throws a `JwtException` subclass:
| Exception | Cause |
|---|---|
| `SignatureException` | Signature doesn't match (tampered or wrong key) |
| `ExpiredJwtException` | `exp` is in the past |
| `MalformedJwtException` | Not a valid 3-part JWT |
| `UnsupportedJwtException` | Algorithm not as expected |

All are caught and return `false` from `isTokenValid()`.

---

## 10. Refresh Token Internals

Refresh tokens are **opaque** — just a random UUID stored in the DB. They don't carry information; they are a DB lookup key.

```
refresh_tokens table:
┌──────────────────────────────────────────────────────────────────┐
│ id (UUID PK) │ token (UUID, unique) │ user_id │ expiry_date       │
│──────────────│─────────────────────│─────────│───────────────────│
│ abc-123      │ 550e8400-...        │ def-456 │ 2026-05-17T10:00Z │
└──────────────────────────────────────────────────────────────────┘
```

**Lifecycle:**
```
Login / Register / OAuth2
      │
      ▼ INSERT new refresh token row
      
Client stores token (localStorage / httpOnly cookie)
      │
      │  (15 min later, access token expires)
      ▼
POST /api/auth/refresh  { refreshToken: "550e8400-..." }
      │
      ▼ SELECT by token value → check expiry
      ▼ DELETE old row
      ▼ INSERT new row with new UUID and fresh 7-day expiry
      │
      ▼ Return new access + refresh tokens to client

Logout:
      ▼ DELETE all rows WHERE user_id = ?
```

---

## 11. Database Tables

### `users`

| Column | Type | Notes |
|---|---|---|
| `id` | VARCHAR(36) PK | UUID generated by Hibernate |
| `email` | VARCHAR UNIQUE NOT NULL | Login identifier |
| `password` | VARCHAR NULL | BCrypt hash; NULL for OAuth2 users |
| `name` | VARCHAR NOT NULL | Display name |
| `picture` | VARCHAR NULL | Google profile photo URL |
| `provider` | VARCHAR NOT NULL | `LOCAL` or `GOOGLE` |
| `provider_id` | VARCHAR NULL | Google's `sub` claim |
| `email_verified` | BOOLEAN | Always true for Google users |
| `created_at` | TIMESTAMP | Set on insert |
| `updated_at` | TIMESTAMP | Updated on every save |

### `refresh_tokens`

| Column | Type | Notes |
|---|---|---|
| `id` | VARCHAR(36) PK | UUID |
| `token` | VARCHAR UNIQUE NOT NULL | Random UUID (the actual token value) |
| `user_id` | VARCHAR(36) FK → users.id | |
| `expiry_date` | TIMESTAMP | 7 days from creation |

---

## 12. Error Handling Reference

| Scenario | HTTP Status | Response |
|---|---|---|
| Validation fails (blank field, bad email) | 400 | Spring's default validation error JSON |
| Email already registered | 400 | `{ "message": "Email already in use: ..." }` |
| Wrong password | 401 | Spring Security's default 401 (BadCredentials) |
| Missing / invalid JWT | 401 | Spring Security's 401 |
| Expired JWT | 401 | Spring Security's 401 |
| Unknown userId in JWT (deleted user) | 401 | Spring Security's 401 (no auth set) |
| Expired refresh token | 400 | `{ "message": "Refresh token expired..." }` |
| Refresh token not found | 400 | `{ "message": "Refresh token not found" }` |
| OAuth2 failure | 401 | `{ "error": "oauth2_authentication_failed", "message": "..." }` |

---

## 13. Security Decisions Explained

### Why separate access token (15 min) and refresh token (7 days)?

- **Access token is short-lived** so a stolen JWT can only be exploited for 15 minutes.
- **Refresh token is long-lived** so users don't have to log in every 15 minutes.
- **Refresh token is in the DB** so it can be revoked instantly (logout, suspicious activity).
- **Access token cannot be revoked** without a blacklist — the 15-min TTL is the mitigation.

### Why userId in JWT subject, not email?

- Email can change. UUID never changes. A stable identifier in the sub claim avoids breakage if a user updates their email.

### Why BCrypt?

- BCrypt is an adaptive hashing algorithm — its cost factor can be increased as hardware gets faster.
- It includes a built-in salt so identical passwords produce different hashes.
- Spring's `BCryptPasswordEncoder` defaults to cost factor 10 (~100ms per hash), which is fast enough for UX and slow enough to resist brute force.

### Why no CSRF?

- CSRF attacks exploit browser cookie auto-submission. Our API uses `Authorization: Bearer` headers, which browsers do NOT automatically attach. Therefore CSRF is not a threat, and we disable the filter.

### Why disable session creation?

- `SessionCreationPolicy.STATELESS` tells Spring Security to never create or use an HTTP session. Our auth is 100% token-based. No session = no session fixation attacks, no memory waste on the server.

### Why rotate refresh tokens?

- If a refresh token is stolen and used, the attacker gets a new token and the legitimate user's old one is gone. The next time the legitimate user tries to refresh, it will fail (token not in DB). This is detectable. With non-rotating tokens, a stolen refresh token is valid indefinitely until logout.

---

*Document maintained alongside the codebase. Update when auth behavior changes.*
