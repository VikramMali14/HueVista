# HueVista — Image Upload Service: Complete Deep-Dive Flow

> This document explains **exactly** how every image request travels through the system —
> from the HTTP hit to the database save and file-serving response.
> Read top-to-bottom. Each section maps to real code in
> `src/main/java/com/gridstore/huevista/image/` and `common/`.

---

## Table of Contents

1. [Architecture Overview](#1-architecture-overview)
2. [Package Layout](#2-package-layout)
3. [API Endpoints Reference](#3-api-endpoints-reference)
4. [Flow A — Image Upload (`POST /api/images/upload`)](#4-flow-a--image-upload)
   - [Step 1: Request enters the Security Filter Chain](#step-1-request-enters-the-security-filter-chain)
   - [Step 2: JWT Authentication](#step-2-jwt-authentication)
   - [Step 3: ImageController receives the file](#step-3-imagecontroller-receives-the-file)
   - [Step 4: File Validation](#step-4-file-validation)
   - [Step 5: Claude Vision AI Classification](#step-5-claude-vision-ai-classification)
   - [Step 6: Claude API internals](#step-6-claude-api-internals)
   - [Step 7: Storage](#step-7-storage)
   - [Step 8: Database persistence](#step-8-database-persistence)
   - [Step 9: Response](#step-9-response)
5. [Flow B — Get Single Image (`GET /api/images/{imageId}`)](#5-flow-b--get-single-image)
6. [Flow C — List All Images (`GET /api/images`)](#6-flow-c--list-all-images)
7. [Flow D — Serve Raw Image File (`GET /api/images/files/**`)](#7-flow-d--serve-raw-image-file)
8. [Internal Component Deep-Dive](#8-internal-component-deep-dive)
   - [ClaudeVisionService](#claudevisionservice)
   - [LocalStorageService](#localstorageservice)
   - [ImageService orchestration](#imageservice-orchestration)
9. [Database Table](#9-database-table)
10. [Error Handling Reference](#10-error-handling-reference)
11. [CORS Configuration](#11-cors-configuration)
12. [Security Decisions Explained](#12-security-decisions-explained)
13. [Swapping Local Storage for S3](#13-swapping-local-storage-for-s3)
14. [Environment Variables](#14-environment-variables)

---

## 1. Architecture Overview

### Tech Stack

| Layer | Technology |
|---|---|
| Framework | Spring Boot 4.0.6 (Spring Framework 7.0.7) |
| Security | Spring Security 6.x (JWT via `JwtAuthFilter`) |
| AI Classification | Anthropic Claude Vision API (`claude-haiku-4-5-20251001`) |
| HTTP Client | Spring `RestTemplate` |
| File Storage | Local filesystem (interface-backed — swap to S3 anytime) |
| ORM | Spring Data JPA + Hibernate 7.x |
| Database | PostgreSQL — `uploaded_images` table |
| Error Handling | `@RestControllerAdvice` GlobalExceptionHandler |

### High-Level Request Path

```
Client (Browser / Mobile / Postman)
         │
         │  HTTP Request (multipart/form-data OR GET)
         ▼
┌──────────────────────────────────────────────────────────────────────┐
│                     Servlet Container (Tomcat)                        │
│                                                                       │
│  ┌───────────────────────────────────────────────────────────────┐   │
│  │              Spring Security Filter Chain                      │   │
│  │                                                                │   │
│  │  1. SecurityContextHolderFilter                                │   │
│  │  2. CorsFilter          ◄── checks Origin header               │   │
│  │  3. CsrfFilter          (DISABLED)                             │   │
│  │  4. JwtAuthFilter       ◄── validates Bearer token             │   │
│  │  5. AuthorizationFilter ◄── enforces .anyRequest().authenticated│   │
│  └───────────────────────────────────────────────────────────────┘   │
│                          │                                            │
│               DispatcherServlet                                        │
│                          │                                            │
│               ImageController  (@RequestMapping /api/images)          │
└──────────────────────────────────────────────────────────────────────┘
         │
         │  Calls into
         ▼
┌──────────────────────────────────────────────────────┐
│  Service Layer                                        │
│  ├── ImageService          (orchestration)            │
│  ├── ClaudeVisionService   (AI classification)        │
│  └── LocalStorageService   (file I/O)                 │
└──────────────────────────────────────────────────────┘
         │
         ├──────────────────────────────────────────────►  Anthropic API
         │                                                 (Claude Haiku Vision)
         ▼
┌──────────────────────────────────────────────────────┐
│  Persistence Layer                                    │
│  ├── ImageRepository      → uploaded_images table     │
│  └── UserRepository       → users table (FK lookup)   │
└──────────────────────────────────────────────────────┘
         │
         ▼
    PostgreSQL Database
```

---

## 2. Package Layout

```
src/main/java/com/gridstore/huevista/
│
├── image/
│   ├── controller/
│   │   └── ImageController.java      ← REST endpoints (upload, get, list, serve)
│   ├── dto/
│   │   └── ImageResponse.java        ← response shape for all image endpoints
│   ├── model/
│   │   ├── UploadedImage.java        ← @Entity: uploaded_images table
│   │   └── ImageType.java            ← enum: INDOOR | OUTDOOR
│   ├── repository/
│   │   └── ImageRepository.java      ← JPA queries
│   └── service/
│       ├── ImageService.java         ← orchestrates the full upload pipeline
│       ├── ClaudeVisionService.java  ← calls Claude Vision API, returns ImageType
│       ├── StorageService.java       ← interface (abstracts local vs S3)
│       └── LocalStorageService.java  ← stores files on local filesystem
│
└── common/
    ├── config/
    │   ├── CorsConfig.java           ← CorsConfigurationSource bean
    │   └── AppConfig.java            ← RestTemplate bean
    └── exception/
        ├── GlobalExceptionHandler.java      ← @RestControllerAdvice
        ├── ImageValidationException.java    ← file or AI validation failure
        ├── StorageException.java            ← filesystem I/O failure
        └── ResourceNotFoundException.java   ← image not found (404)
```

---

## 3. API Endpoints Reference

All endpoints require a valid JWT `Authorization: Bearer <token>` header.
Tokens are issued by the Auth Service (`POST /api/auth/login` or `/register`).

| Method | Endpoint | Auth | Content-Type | Description |
|---|---|---|---|---|
| `POST` | `/api/images/upload` | Required | `multipart/form-data` | Upload + classify + store an image |
| `GET` | `/api/images/{imageId}` | Required | — | Fetch metadata for one image |
| `GET` | `/api/images` | Required | — | List all images for the logged-in user |
| `GET` | `/api/images/files/**` | Required | — | Serve the raw image file (binary) |

### Request / Response shapes

**POST /api/images/upload**
```
Request (multipart/form-data):
  file: <binary image data>    ← form field name must be "file"

Response 201 Created:
{
  "imageId":         "550e8400-e29b-41d4-a716-446655440000",
  "imageUrl":        "/api/images/files/{userId}/{uuid}.jpg",
  "originalFilename": "living-room.jpg",
  "imageType":       "INDOOR",       // or "OUTDOOR"
  "fileSize":        2457600,         // bytes
  "uploadedAt":      "2026-05-10T14:32:00"
}

Response 422 Unprocessable Entity (invalid image):
{
  "status":    422,
  "error":     "Unprocessable Entity",
  "message":   "Please upload a photo of an indoor room or outdoor house/building exterior...",
  "timestamp": "2026-05-10T14:32:01"
}
```

**GET /api/images/{imageId}**
```
Response 200 OK:  same ImageResponse body as above
Response 404:     { "status": 404, "error": "Not Found", "message": "Image not found: <id>" }
```

**GET /api/images**
```
Response 200 OK:
[
  { ...ImageResponse... },
  { ...ImageResponse... }
]
// sorted newest first
```

**GET /api/images/files/{userId}/{filename}**
```
Response 200 OK:  raw binary image bytes
Content-Type:     image/jpeg | image/png | image/webp
Response 403:     empty body — cross-user access attempt blocked
```

---

## 4. Flow A — Image Upload

This is the most important and most complex flow. Here is every step in detail.

```
POST /api/images/upload
Content-Type: multipart/form-data
Authorization: Bearer eyJhbGci...

[binary image bytes in "file" field]
```

---

### Step 1: Request enters the Security Filter Chain

Every HTTP request — including the upload — passes through Spring Security's filter chain before reaching any controller. Relevant filters for this flow:

```
Incoming POST /api/images/upload
         │
         ▼
① CorsFilter
   └── Checks the Origin header against CorsConfig.corsConfigurationSource()
       Allowed origins: value of CORS_ALLOWED_ORIGINS env var (default: http://localhost:3000)
       If origin is not allowed → 403 immediately, never reaches controller.
       For same-origin or allowed origin → passes through.

         │
         ▼
② JwtAuthFilter  (OncePerRequestFilter)
   └── See Step 2 below.

         │
         ▼
③ AuthorizationFilter
   └── SecurityConfig has .anyRequest().authenticated()
       Checks if SecurityContext now holds an Authentication object.
       If JwtAuthFilter set it → passes through.
       If not → 401 Unauthorized, never reaches controller.

         │
         ▼
④ DispatcherServlet → routes to ImageController
```

---

### Step 2: JWT Authentication

`JwtAuthFilter` (`auth/filter/JwtAuthFilter.java`) runs on every request except `/api/auth/**`.

```
JwtAuthFilter.doFilterInternal()
         │
         ├── Extract "Authorization" header
         │   └── If missing or doesn't start with "Bearer " → skip (no auth set)
         │
         ├── Extract token: header.substring(7)
         │
         ├── JwtService.extractUserId(token)
         │   └── Parses JWT, reads "sub" claim = userId (UUID string)
         │
         ├── Check SecurityContext is empty (don't re-authenticate)
         │
         ├── UserRepository.findById(userId)
         │   └── Confirms user still exists in DB
         │   └── If not found → skip (no auth set → AuthorizationFilter will 401)
         │
         ├── JwtService.isTokenValid(token, userId)
         │   └── Verifies HMAC-SHA256 signature with JWT_SECRET
         │   └── Verifies token not expired (15-min TTL)
         │
         └── If valid:
             UsernamePasswordAuthenticationToken auth = new UsernamePasswordAuthenticationToken(
                 UserDetails(username=userId), null, emptyList()
             )
             SecurityContextHolder.getContext().setAuthentication(auth)
             → request proceeds as authenticated
```

After this filter, `@AuthenticationPrincipal UserDetails userDetails` in the controller will have `userDetails.getUsername()` = the userId (UUID).

---

### Step 3: ImageController receives the file

**File:** `image/controller/ImageController.java`

```java
@PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
public ResponseEntity<ImageResponse> upload(
        @RequestParam("file") MultipartFile file,
        @AuthenticationPrincipal UserDetails userDetails) {

    ImageResponse response = imageService.upload(file, userDetails.getUsername());
    //                                                  ↑ userId from JWT
    return ResponseEntity.status(HttpStatus.CREATED).body(response);
}
```

- `@RequestParam("file")` — Spring binds the multipart file field named "file"
- `@AuthenticationPrincipal` — Spring injects the `UserDetails` set by `JwtAuthFilter`
- `userDetails.getUsername()` — returns the **userId** (UUID), not the email
- Delegates entirely to `ImageService.upload()` — the controller has no business logic
- Returns `201 Created` on success

---

### Step 4: File Validation

**File:** `image/service/ImageService.java` → `validateFile()`

This runs **first**, before any I/O or API call, so invalid files are rejected instantly at zero cost.

```
ImageService.validateFile(file)
         │
         ├── Is file null or empty?
         │   └── YES → throw ImageValidationException("No file provided.")
         │             → GlobalExceptionHandler → 422 Unprocessable Entity
         │
         ├── Is contentType in { image/jpeg, image/png, image/webp }?
         │   └── NO  → throw ImageValidationException("Only JPEG, PNG, and WebP images are accepted.")
         │             → GlobalExceptionHandler → 422
         │
         └── Is fileSize > 10,485,760 bytes (10 MB)?
             └── YES → throw ImageValidationException("File size must not exceed 10MB.")
                       → GlobalExceptionHandler → 422
```

> **Note:** Spring Boot also enforces `spring.servlet.multipart.max-file-size=10MB` at the
> Tomcat level. If the multipart parser itself rejects the size, `MaxUploadSizeExceededException`
> is thrown and caught by `GlobalExceptionHandler` → 400 Bad Request.

---

### Step 5: Claude Vision AI Classification

**File:** `image/service/ClaudeVisionService.java`

This is the core of Phase 2. After a file passes basic validation, it is sent to Claude Haiku for classification **before** being written to disk. Invalid images (selfies, food, landscapes, etc.) are rejected here and never reach storage.

```
ImageService.upload()
         │
         ├── claudeVisionService.classify(file)
         │
         ▼
ClaudeVisionService.classify(MultipartFile file)
         │
         ├── Read all bytes from file: file.getBytes()
         │
         ├── Base64-encode the bytes
         │
         ├── Build Claude API request body (JSON):
         │   {
         │     "model": "claude-haiku-4-5-20251001",
         │     "max_tokens": 10,
         │     "messages": [{
         │       "role": "user",
         │       "content": [
         │         {
         │           "type": "image",
         │           "source": {
         │             "type": "base64",
         │             "media_type": "image/jpeg",  ← from file.getContentType()
         │             "data": "<base64 string>"
         │           }
         │         },
         │         {
         │           "type": "text",
         │           "text": "Classify this image for a paint color visualization app.
         │                    Is it an indoor room (living room, bedroom, kitchen, etc.)
         │                    or an outdoor house/building exterior?
         │                    Answer with exactly one word: INDOOR, OUTDOOR, or INVALID."
         │         }
         │       ]
         │     }]
         │   }
         │
         ├── Set HTTP headers:
         │   x-api-key: <ANTHROPIC_API_KEY>
         │   anthropic-version: 2023-06-01
         │   Content-Type: application/json
         │
         ├── POST https://api.anthropic.com/v1/messages  (via RestTemplate)
         │
         ├── Parse response:
         │   response.body["content"][0]["text"].trim().toUpperCase()
         │
         └── Map to ImageType:
             "INDOOR"  → return ImageType.INDOOR
             "OUTDOOR" → return ImageType.OUTDOOR
             anything else (INVALID, etc.) → return null
```

Back in `ImageService`:
```
imageType = claudeVisionService.classify(file)

if (imageType == null) {
    throw new ImageValidationException(
        "Please upload a photo of an indoor room or outdoor house/building exterior..."
    )
    → GlobalExceptionHandler → 422 Unprocessable Entity
}
// Otherwise: imageType is INDOOR or OUTDOOR — proceed to storage
```

---

### Step 6: Claude API internals

Understanding exactly what happens during the API call:

```
RestTemplate.exchange(
    "https://api.anthropic.com/v1/messages",
    HttpMethod.POST,
    HttpEntity<Map>(requestBody, headers),
    Map.class   ← response parsed into raw Map
)
```

**Claude's response structure:**
```json
{
  "id": "msg_01XFDUDYJgAACzvnptvVoYEL",
  "type": "message",
  "role": "assistant",
  "content": [
    {
      "type": "text",
      "text": "INDOOR"
    }
  ],
  "model": "claude-haiku-4-5-20251001",
  "stop_reason": "end_turn",
  "usage": {
    "input_tokens": 1042,
    "output_tokens": 1
  }
}
```

The code reads: `response.body["content"][0]["text"]` → `"INDOOR"`

**Why `max_tokens: 10`?**
Claude only needs to output one word (INDOOR/OUTDOOR/INVALID). Capping at 10 tokens:
- Makes the response near-instant (no long generation)
- Keeps cost extremely low (~$0.0003 per image at Haiku pricing)

**Why Haiku and not Sonnet/Opus?**
- Image classification is a simple binary task — Haiku is more than capable
- Haiku is ~10x cheaper than Sonnet for this use case
- Latency is lower, which matters for an upload endpoint

**If the API call fails:**
```
catch (Exception e) {
    log.error("Claude Vision API call failed: {}", e.getMessage())
    throw new RuntimeException("Image classification service is temporarily unavailable...")
    → GlobalExceptionHandler catches generic Exception → 500 Internal Server Error
}
```

---

### Step 7: Storage

**File:** `image/service/LocalStorageService.java`

Only reached if Claude returned INDOOR or OUTDOOR.

```
LocalStorageService.store(file, userId)
         │
         ├── Generate storageKey:
         │   "{userId}/{UUID}.{ext}"
         │   e.g. "abc-123-def/9f8e7d6c-5b4a-3210.jpg"
         │
         ├── Resolve target path:
         │   Path.of(storagePath, storageKey)
         │   storagePath = UPLOAD_PATH env var (default: /tmp/huevista/uploads)
         │   full path e.g.: /tmp/huevista/uploads/abc-123/9f8e7d6c.jpg
         │
         ├── Create parent directories if they don't exist:
         │   Files.createDirectories(target.getParent())
         │
         ├── Write file bytes to disk:
         │   Files.copy(file.getInputStream(), target, REPLACE_EXISTING)
         │
         └── Return storageKey (stored in DB, used to reconstruct URL later)
```

**URL generation:**
```
storageService.getPublicUrl(storageKey)
→ "/api/images/files/" + storageKey
→ "/api/images/files/abc-123-def/9f8e7d6c-5b4a-3210.jpg"
```

This is the URL returned in `ImageResponse.imageUrl`. The client uses it to later fetch the raw image via `GET /api/images/files/**`.

---

### Step 8: Database persistence

**File:** `image/service/ImageService.java`

```
userRepository.findById(userId)
    → loads the User entity (needed for the FK relationship)
    → if not found → ResourceNotFoundException → 404 (shouldn't happen since JWT validated)

imageRepository.save(
    UploadedImage.builder()
        .user(user)
        .originalFilename(file.getOriginalFilename())   // "living-room.jpg"
        .storageKey(storageKey)                          // "abc-123/9f8e7d6c.jpg"
        .contentType(file.getContentType())              // "image/jpeg"
        .fileSize(file.getSize())                        // 2457600 (bytes)
        .imageType(imageType)                            // INDOOR
        .build()
)
→ Hibernate generates INSERT INTO uploaded_images (...)
→ @GeneratedValue(UUID) assigns id automatically
→ @CreationTimestamp assigns uploadedAt = now()
→ Returns saved entity with id populated
```

---

### Step 9: Response

```
ImageService.toResponse(saved)
→ ImageResponse {
    imageId:          "550e8400-e29b-41d4-a716-446655440000"
    imageUrl:         "/api/images/files/abc-123-def/9f8e7d6c-5b4a-3210.jpg"
    originalFilename: "living-room.jpg"
    imageType:        INDOOR
    fileSize:         2457600
    uploadedAt:       2026-05-10T14:32:00
  }

Controller wraps in ResponseEntity.status(201).body(response)
Jackson serializes to JSON
Client receives 201 Created
```

### Full Upload Flow — One-line summary per step

```
POST /api/images/upload
         │
         ▼
[1] CorsFilter         → check origin header
[2] JwtAuthFilter      → validate Bearer token, set SecurityContext userId
[3] AuthorizationFilter→ confirm authenticated
[4] ImageController    → bind MultipartFile, extract userId from principal
[5] ImageService       → validateFile() → type/size checks
[6] ClaudeVisionService→ base64 encode → POST to Anthropic API → get INDOOR/OUTDOOR/null
[7] ImageService       → null? → 422. Valid? → continue
[8] LocalStorageService→ write bytes to /tmp/huevista/uploads/{userId}/{uuid}.ext
[9] ImageRepository    → INSERT INTO uploaded_images (...)
[10] ImageController   → return 201 Created + ImageResponse JSON
```

---

## 5. Flow B — Get Single Image

```
GET /api/images/{imageId}
Authorization: Bearer eyJhbGci...
```

```
[1] JwtAuthFilter      → validate token → userId in SecurityContext
[2] AuthorizationFilter→ authenticated check
[3] ImageController.getImage(imageId, userDetails)
         │
         ▼
[4] ImageService.getImage(imageId, userId)
         │
         ├── imageRepository.findByIdAndUserId(imageId, userId)
         │   SQL: SELECT * FROM uploaded_images
         │        WHERE id = ? AND user_id = ?
         │
         ├── Not found (wrong id OR belongs to different user)?
         │   → ResourceNotFoundException → GlobalExceptionHandler → 404
         │
         └── Found → toResponse(image) → ImageResponse
[5] 200 OK + ImageResponse JSON
```

**Security note:** `findByIdAndUserId` means a user can never see another user's image even if they guess the UUID. The `user_id` column filter enforces ownership at the DB query level.

---

## 6. Flow C — List All Images

```
GET /api/images
Authorization: Bearer eyJhbGci...
```

```
[1] JwtAuthFilter      → validate token → userId
[2] AuthorizationFilter→ authenticated check
[3] ImageController.listImages(userDetails)
         │
         ▼
[4] ImageService.listImages(userId)
         │
         └── imageRepository.findByUserIdOrderByUploadedAtDesc(userId)
             SQL: SELECT * FROM uploaded_images
                  WHERE user_id = ?
                  ORDER BY uploaded_at DESC
             → List<UploadedImage> (newest first)
             → stream().map(toResponse).collect(toList())

[5] 200 OK + JSON array of ImageResponse
    (empty array [] if user has no uploads)
```

---

## 7. Flow D — Serve Raw Image File

```
GET /api/images/files/{userId}/{filename}
Authorization: Bearer eyJhbGci...
```

```
[1] JwtAuthFilter      → validate token → userId in SecurityContext
[2] AuthorizationFilter→ authenticated check
[3] ImageController.serveFile(request, userDetails)
         │
         ├── Extract storageKey from URI:
         │   request.getRequestURI() = "/api/images/files/abc-123/9f8e7d6c.jpg"
         │   strip prefix "/api/images/files/"
         │   storageKey = "abc-123/9f8e7d6c.jpg"
         │
         ├── SECURITY CHECK:
         │   storageKey.startsWith(userId + "/")
         │   "abc-123/..." starts with "abc-123/" ?
         │   → NO (different user) → 403 Forbidden (empty body)
         │   → YES (owner) → continue
         │
         ├── LocalStorageService.load(storageKey)
         │   Files.readAllBytes(Path.of(storagePath, storageKey))
         │   → byte[]
         │
         ├── Detect Content-Type from extension:
         │   .png  → "image/png"
         │   .webp → "image/webp"
         │   else  → "image/jpeg"
         │
         └── ResponseEntity.ok()
               .contentType(MediaType.parseMediaType(contentType))
               .body(data)

[4] 200 OK + raw image bytes
    Content-Type: image/jpeg
```

**Why not store content-type in DB and look it up?**
Avoiding a DB round-trip for file serving keeps it fast. The extension in the storageKey is sufficient since we only accept JPEG/PNG/WebP on upload.

---

## 8. Internal Component Deep-Dive

### ClaudeVisionService

**File:** `image/service/ClaudeVisionService.java`

This is a Spring `@Service` that makes a single synchronous HTTP POST to the Anthropic Messages API.

**Dependencies:**
- `RestTemplate` — injected from `AppConfig.restTemplate()` bean
- `@Value("${app.claude.api-key}")` — reads `ANTHROPIC_API_KEY` env var
- `@Value("${app.claude.model:claude-haiku-4-5-20251001}")` — configurable model

**Request construction:**
```java
Map<String, Object> imageBlock = Map.of(
    "type", "image",
    "source", Map.of(
        "type", "base64",
        "media_type", mediaType,     // e.g. "image/jpeg"
        "data", base64Data           // base64-encoded image bytes
    )
);
Map<String, Object> textBlock = Map.of("type", "text", "text", PROMPT);

Map<String, Object> requestBody = Map.of(
    "model", model,
    "max_tokens", 10,
    "messages", List.of(
        Map.of("role", "user", "content", List.of(imageBlock, textBlock))
    )
);
```

**Return contract:**
| Claude says | Method returns | Meaning |
|---|---|---|
| `"INDOOR"` | `ImageType.INDOOR` | Valid — indoor room |
| `"OUTDOOR"` | `ImageType.OUTDOOR` | Valid — house exterior |
| `"INVALID"` or anything else | `null` | Invalid image — upload rejected |

---

### LocalStorageService

**File:** `image/service/LocalStorageService.java`

Implements the `StorageService` interface. Stores files on the local filesystem.

**Storage path structure:**
```
{UPLOAD_PATH}/
  └── {userId}/
       ├── 9f8e7d6c-5b4a-3210-fedc-ba9876543210.jpg
       ├── 1a2b3c4d-5e6f-7890-abcd-ef1234567890.png
       └── ...
```

Each user gets their own subdirectory named by their UUID. Each file gets a fresh UUID as its filename (prevents guessability).

**Why the `StorageService` interface?**
The interface (`StorageService.java`) defines 4 methods:
- `store(file, userId)` — write and return storageKey
- `load(storageKey)` — read and return bytes
- `delete(storageKey)` — remove file
- `getPublicUrl(storageKey)` — return the URL for the image

`LocalStorageService` implements all four. When you're ready to switch to AWS S3 or Cloudflare R2, you create a new `S3StorageService implements StorageService` and swap the `@Service` annotation — zero changes to `ImageService` or `ImageController`.

---

### ImageService orchestration

**File:** `image/service/ImageService.java`

This is the central coordinator. It enforces the strict order:

```
validate → classify → store → persist → respond
```

No step is skipped. If any step throws, the global handler catches it and the client gets a structured error. Storage is only written to if AI classification succeeds. The DB is only written to if storage succeeds. This keeps the system consistent — you never get an orphaned file in storage with no DB record.

**The ALLOWED_TYPES set:**
```java
private static final Set<String> ALLOWED_TYPES = Set.of("image/jpeg", "image/png", "image/webp");
```
Uses `Set.of()` (O(1) lookup) instead of a list for fast membership checks.

---

## 9. Database Table

Hibernate auto-creates this table on startup (`spring.jpa.hibernate.ddl-auto=update`).

```sql
CREATE TABLE uploaded_images (
    id               VARCHAR(36) PRIMARY KEY,   -- UUID generated by Hibernate
    user_id          VARCHAR(36) NOT NULL,       -- FK → users.id
    original_filename VARCHAR(255) NOT NULL,
    storage_key      VARCHAR(500) NOT NULL,       -- path: {userId}/{uuid}.ext
    content_type     VARCHAR(50) NOT NULL,        -- e.g. "image/jpeg"
    file_size        BIGINT NOT NULL,             -- bytes
    image_type       VARCHAR(10) NOT NULL,        -- "INDOOR" or "OUTDOOR"
    uploaded_at      TIMESTAMP,                  -- set by @CreationTimestamp

    CONSTRAINT fk_uploaded_images_user
        FOREIGN KEY (user_id) REFERENCES users(id)
);
```

### Entity → Table mapping

| Java field | Column | Notes |
|---|---|---|
| `id` | `id` | UUID, `@GeneratedValue(UUID)` |
| `user` | `user_id` | `@ManyToOne` → FK to `users` |
| `originalFilename` | `original_filename` | As uploaded by client |
| `storageKey` | `storage_key` | `{userId}/{uuid}.ext` |
| `contentType` | `content_type` | MIME type string |
| `fileSize` | `file_size` | In bytes |
| `imageType` | `image_type` | Enum stored as String |
| `uploadedAt` | `uploaded_at` | Auto-set on insert |

### JPA queries in ImageRepository

```java
// Used by: GET /api/images (list all)
List<UploadedImage> findByUserIdOrderByUploadedAtDesc(String userId);
// SQL: SELECT * FROM uploaded_images WHERE user_id = ? ORDER BY uploaded_at DESC

// Used by: GET /api/images/{imageId}
Optional<UploadedImage> findByIdAndUserId(String id, String userId);
// SQL: SELECT * FROM uploaded_images WHERE id = ? AND user_id = ?
```

---

## 10. Error Handling Reference

All errors are handled by `GlobalExceptionHandler` (`common/exception/GlobalExceptionHandler.java`) which is annotated `@RestControllerAdvice`. It intercepts any exception thrown from a controller or service and returns a consistent JSON structure.

### Error response format
```json
{
  "status":    422,
  "error":     "Unprocessable Entity",
  "message":   "Human-readable description of what went wrong",
  "timestamp": "2026-05-10T14:32:01.123"
}
```

### Exception → HTTP Status mapping

| Exception | HTTP Status | When thrown |
|---|---|---|
| `ImageValidationException` | 422 Unprocessable Entity | File type/size invalid, or Claude returned INVALID |
| `ResourceNotFoundException` | 404 Not Found | Image not found or belongs to another user |
| `StorageException` | 500 Internal Server Error | Filesystem I/O failure |
| `MaxUploadSizeExceededException` | 400 Bad Request | Multipart size exceeds Tomcat limit (10 MB) |
| `MethodArgumentNotValidException` | 400 Bad Request | `@Valid` bean validation failure |
| Any other `Exception` | 500 Internal Server Error | Unexpected error (Claude API down, DB down, etc.) |

### Why 422 instead of 400 for invalid images?

`400 Bad Request` means the request itself was malformed — wrong format, missing fields.
`422 Unprocessable Entity` means the request was well-formed but the **content** cannot be processed. A valid JPEG of a selfie is technically a correct file, but it cannot be used by the application. 422 is semantically accurate.

---

## 11. CORS Configuration

**File:** `common/config/CorsConfig.java`

The `CorsConfigurationSource` bean is auto-picked up by Spring Security when `SecurityConfig` wires:
```java
.cors(cors -> cors.configurationSource(corsConfigurationSource))
```

**Configuration:**
```
Allowed Origins:  value of CORS_ALLOWED_ORIGINS env var
                  default: http://localhost:3000
                  multiple: set to "https://app.huevista.com,http://localhost:3000"
Allowed Methods:  GET, POST, PUT, DELETE, OPTIONS
Allowed Headers:  * (all headers, including Authorization)
Allow Credentials: true (needed for cookies if added later)
Max Age:          3600 seconds (browser caches preflight for 1 hour)
```

**CORS preflight flow:**
```
Browser sends OPTIONS /api/images/upload
    ↓
CorsFilter intercepts (before JwtAuthFilter)
    ↓
Checks Origin against allowed list
    ↓
If allowed → returns 200 with CORS headers
             Access-Control-Allow-Origin: http://localhost:3000
             Access-Control-Allow-Methods: GET, POST, ...
             Access-Control-Allow-Headers: *
    ↓
Browser sends actual POST /api/images/upload
```

Note: `CorsFilter` runs **before** `JwtAuthFilter`, so OPTIONS preflight requests are handled without a JWT token — which is correct, since the browser sends the preflight before the actual request.

---

## 12. Security Decisions Explained

### Why is the storageKey `{userId}/{uuid}.ext` and not just `{uuid}.ext`?

Two reasons:
1. **Organisation** — each user's files are grouped in their own subdirectory
2. **Security** — the file-serving endpoint (`GET /api/images/files/**`) uses the userId prefix as an ownership check: `storageKey.startsWith(userId + "/")`. This prevents a user from accessing another user's file by guessing a UUID, even if JwtAuthFilter only verifies the Bearer token and not file ownership.

### Why store the storageKey in the DB instead of re-constructing from imageId?

The storageKey embeds a separate UUID (the filename), not the imageId. This means:
- The storage location is opaque to the client — they can't reverse-engineer where files are stored
- If an image is moved or re-stored (e.g. migrated to S3), only the storageKey column needs updating, not any external references

### Why is AI classification done before storage?

Cost and cleanliness:
- **Cost**: Every file written to storage costs money (eventually, on S3). Rejecting invalid images before writing saves storage cost.
- **Cleanliness**: The storage bucket and DB only ever contain validated house photos. No garbage to clean up later.
- **UX**: The user gets immediate feedback ("this isn't a house photo") without any partial state being created.

### Why does `GET /api/images/{imageId}` use `findByIdAndUserId` instead of just `findById`?

If we used `findById(imageId)` and only checked ownership afterwards, there's an IDOR vulnerability (Insecure Direct Object Reference): a user could discover that an image with a certain ID exists (404 vs 403). By filtering on both `id` AND `user_id` in a single query, the result is indistinguishable — the user simply gets 404 whether the image doesn't exist or belongs to someone else.

---

## 13. Swapping Local Storage for S3

When you're ready to move from local filesystem to AWS S3 or Cloudflare R2:

1. Add the AWS SDK dependency to `pom.xml`:
   ```xml
   <dependency>
       <groupId>software.amazon.awssdk</groupId>
       <artifactId>s3</artifactId>
       <version>2.x.x</version>
   </dependency>
   ```

2. Create `S3StorageService implements StorageService`:
   ```java
   @Service
   @Primary  // ← makes Spring prefer this over LocalStorageService
   public class S3StorageService implements StorageService {
       // implement store(), load(), delete(), getPublicUrl()
       // getPublicUrl() returns the CDN/S3 URL instead of /api/images/files/...
   }
   ```

3. Remove `@Service` from `LocalStorageService` (or keep for local dev with `@Profile("local")`).

4. Add env vars: `AWS_ACCESS_KEY_ID`, `AWS_SECRET_ACCESS_KEY`, `S3_BUCKET_NAME`, `S3_REGION`.

No changes required to `ImageService`, `ImageController`, or any other class — that is the entire value of the `StorageService` interface.

---

## 14. Environment Variables

All configuration is externalised. Never hardcode secrets.

| Variable | Required | Default | Description |
|---|---|---|---|
| `DB_PASSWORD` | Yes | — | PostgreSQL password |
| `JWT_SECRET` | Yes | — | Base64-encoded 256-bit key (`openssl rand -base64 32`) |
| `GOOGLE_CLIENT_ID` | Yes | — | Google OAuth2 client ID |
| `GOOGLE_CLIENT_SECRET` | Yes | — | Google OAuth2 client secret |
| `ANTHROPIC_API_KEY` | Yes | — | Anthropic API key (from console.anthropic.com) |
| `UPLOAD_PATH` | No | `/tmp/huevista/uploads` | Local filesystem storage root |
| `CORS_ALLOWED_ORIGINS` | No | `http://localhost:3000` | Comma-separated frontend origins |

### Generating secrets

```bash
# JWT secret
openssl rand -base64 32

# Test Claude API key is working
curl https://api.anthropic.com/v1/messages \
  -H "x-api-key: $ANTHROPIC_API_KEY" \
  -H "anthropic-version: 2023-06-01" \
  -H "content-type: application/json" \
  -d '{"model":"claude-haiku-4-5-20251001","max_tokens":10,"messages":[{"role":"user","content":"Hello"}]}'
```

### Minimal `.env` to run the application

```bash
DB_PASSWORD=your_postgres_password
JWT_SECRET=your_base64_secret_here
GOOGLE_CLIENT_ID=your_google_client_id
GOOGLE_CLIENT_SECRET=your_google_client_secret
ANTHROPIC_API_KEY=sk-ant-api03-...
```
