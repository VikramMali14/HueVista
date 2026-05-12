# HueVista - Image Upload Service: Complete Deep-Dive Flow

> This document explains exactly how every image request travels through the system -
> from the HTTP hit to the database save and file-serving response.
> Read top-to-bottom. Each section maps to real code in
> `src/main/java/com/gridstore/huevista/image/` and `common/`.

---

## Table of Contents

1. [Architecture Overview](#1-architecture-overview)
2. [Package Layout](#2-package-layout)
3. [API Endpoints Reference](#3-api-endpoints-reference)
4. [Flow A - Image Upload (POST /api/images/upload)](#4-flow-a--image-upload)
5. [Flow B - Get Single Image (GET /api/images/{imageId})](#5-flow-b--get-single-image)
6. [Flow C - List All Images (GET /api/images)](#6-flow-c--list-all-images)
7. [Flow D - Serve Raw Image File (GET /api/images/files/**)](#7-flow-d--serve-raw-image-file)
8. [Internal Component Deep-Dive](#8-internal-component-deep-dive)
9. [Storage Layer: Local vs S3](#9-storage-layer-local-vs-s3)
10. [Database Table](#10-database-table)
11. [Error Handling Reference](#11-error-handling-reference)
12. [CORS Configuration](#12-cors-configuration)
13. [Security Decisions Explained](#13-security-decisions-explained)
14. [Cost Optimisation: Claude + S3](#14-cost-optimisation-claude--s3)
15. [Environment Variables](#15-environment-variables)

---

## 1. Architecture Overview

### Tech Stack

| Layer | Technology |
|---|---|
| Framework | Spring Boot 4.0.6 (Spring Framework 7.0.7) |
| Security | Spring Security 7.0.5 (JWT via JwtAuthFilter) |
| AI Classification | Anthropic Claude Vision API (claude-haiku-4-5-20251001) |
| Image Pre-processing | Thumbnailator 0.4.20 (resize before Claude - 10x cost saving) |
| HTTP Client | Spring RestTemplate |
| File Storage | Local filesystem (default) or AWS S3 (activated by env var) |
| AWS SDK | AWS SDK for Java v2 (2.31.0) with BOM |
| ORM | Spring Data JPA + Hibernate 7.x |
| Database | PostgreSQL - uploaded_images table |
| Error Handling | GlobalExceptionHandler (@RestControllerAdvice) |

### High-Level Request Path

```
Client (Browser / Mobile / Postman)
         |
         |  HTTP Request (multipart/form-data OR GET)
         v
+-------------------------------------------------------------------------+
|                     Servlet Container (Tomcat)                           |
|                                                                          |
|  +-------------------------------------------------------------------+  |
|  |              Spring Security Filter Chain                          |  |
|  |                                                                    |  |
|  |  1. SecurityContextHolderFilter                                    |  |
|  |  2. CorsFilter          <- checks Origin header                   |  |
|  |  3. CsrfFilter          (DISABLED)                                |  |
|  |  4. JwtAuthFilter       <- validates Bearer token                 |  |
|  |  5. AuthorizationFilter <- enforces .anyRequest().authenticated() |  |
|  +-------------------------------------------------------------------+  |
|                          |                                               |
|               DispatcherServlet                                           |
|                          |                                               |
|               ImageController  (@RequestMapping /api/images)             |
+-------------------------------------------------------------------------+
         |
         |  Calls into
         v
+--------------------------------------------------+
|  Service Layer                                    |
|  +-- ImageService          (orchestration)        |
|  +-- ClaudeVisionService   (AI classification)    |
|  +-- StorageService        (interface)            |
|       +-- LocalStorageService  (default)          |
|       +-- S3StorageService     (when S3 active)   |
+--------------------------------------------------+
         |
         +--------------------------------------------->  Anthropic API
         |                                               (Claude Haiku Vision)
         +--------------------------------------------->  AWS S3
         |                                               (when S3_BUCKET_NAME set)
         v
+--------------------------------------------------+
|  Persistence Layer                                |
|  +-- ImageRepository      -> uploaded_images      |
|  +-- UserRepository       -> users (FK lookup)    |
+--------------------------------------------------+
         |
         v
    PostgreSQL Database
```

---

## 2. Package Layout

```
src/main/java/com/gridstore/huevista/
|
+-- image/
|   +-- config/
|   |   +-- S3Config.java            <- S3Client + S3Presigner beans (only if S3 active)
|   |   +-- StorageConfig.java       <- LocalStorageService @Bean (fallback when no S3)
|   +-- controller/
|   |   +-- ImageController.java     <- REST endpoints (upload, get, list, serve)
|   +-- dto/
|   |   +-- ImageResponse.java       <- response shape for all image endpoints
|   +-- model/
|   |   +-- UploadedImage.java       <- @Entity: uploaded_images table
|   |   +-- ImageType.java           <- enum: INDOOR | OUTDOOR
|   +-- repository/
|   |   +-- ImageRepository.java     <- JPA queries
|   +-- service/
|       +-- ImageService.java        <- orchestrates the full upload pipeline
|       +-- ClaudeVisionService.java <- resizes + calls Claude Vision API
|       +-- StorageService.java      <- interface (abstracts local vs S3)
|       +-- LocalStorageService.java <- stores files on local filesystem
|       +-- S3StorageService.java    <- stores files in AWS S3 (when active)
|
+-- common/
    +-- config/
    |   +-- CorsConfig.java          <- CorsConfigurationSource bean
    |   +-- AppConfig.java           <- RestTemplate bean
    +-- exception/
        +-- GlobalExceptionHandler.java      <- @RestControllerAdvice
        +-- ImageValidationException.java    <- file or AI validation failure
        +-- StorageException.java            <- filesystem / S3 I/O failure
        +-- ResourceNotFoundException.java   <- image not found (404)
```

---

## 3. API Endpoints Reference

All endpoints require a valid JWT `Authorization: Bearer <token>` header.

| Method | Endpoint | Content-Type | Description |
|---|---|---|---|
| `POST` | `/api/images/upload` | `multipart/form-data` | Upload, AI-classify, and store an image |
| `GET` | `/api/images/{imageId}` | - | Fetch metadata for one image |
| `GET` | `/api/images` | - | List all images for the logged-in user (newest first) |
| `GET` | `/api/images/files/**` | - | Serve raw image bytes (local storage only) |

### Request / Response shapes

**POST /api/images/upload**
```
Request (multipart/form-data):
  file: <binary image data>    <- form field name must be "file"

Response 201 Created:
{
  "imageId":          "550e8400-e29b-41d4-a716-446655440000",
  "imageUrl":         "/api/images/files/{userId}/{uuid}.jpg",
  "originalFilename": "living-room.jpg",
  "imageType":        "INDOOR",
  "fileSize":         2457600,
  "uploadedAt":       "2026-05-12T03:07:00"
}

Response 422 Unprocessable Entity (not a house/room image):
{
  "status":    422,
  "error":     "Unprocessable Entity",
  "message":   "Please upload a photo of an indoor room or outdoor house/building exterior...",
  "timestamp": "2026-05-12T03:07:01"
}
```

**GET /api/images/{imageId}**
```
Response 200 OK:  same ImageResponse body
Response 404:     { "status": 404, "error": "Not Found", "message": "Image not found: <id>" }
```

**GET /api/images**
```
Response 200 OK:
[
  { ...ImageResponse... },
  { ...ImageResponse... }
]
```

**GET /api/images/files/{userId}/{filename}**
```
Response 200 OK:  raw binary image bytes
Content-Type:     image/jpeg | image/png | image/webp
Response 403:     empty body - cross-user access attempt blocked
```

---

## 4. Flow A - Image Upload

This is the most important and complex flow. Here is every step.

```
POST /api/images/upload
Content-Type: multipart/form-data
Authorization: Bearer eyJhbGci...
[binary image bytes in "file" field]
```

### Step 1: Security Filter Chain

```
Incoming POST /api/images/upload
         |
         v
(1) CorsFilter
    Checks Origin header against app.cors.allowed-origins.
    Default: http://localhost:3000
    If origin not allowed -> 403 immediately.

         |
         v
(2) JwtAuthFilter
    Reads Authorization: Bearer <token> header.
    Validates HMAC-SHA256 signature and expiry.
    Sets userId in SecurityContext.
    See Section 4 Step 2 for detail.

         |
         v
(3) AuthorizationFilter
    Confirms SecurityContext holds an Authentication object.
    If JwtAuthFilter set it -> passes through.
    If not -> 401 Unauthorized.

         |
         v
(4) DispatcherServlet -> routes to ImageController
```

### Step 2: JWT Authentication

```
JwtAuthFilter.doFilterInternal()
         |
         +-- Extract "Authorization" header
         |   If missing or not "Bearer ..." -> skip (no auth set)
         |
         +-- jwtService.extractUserId(token)
         |   Parses JWT, reads "sub" claim = userId (UUID)
         |
         +-- Check SecurityContext is empty (don't re-authenticate)
         |
         +-- userRepository.findById(userId)
         |   Confirms user still exists in DB
         |   If not found -> skip (AuthorizationFilter will 401)
         |
         +-- jwtService.isTokenValid(token, userId)
         |   Verifies HMAC-SHA256 signature
         |   Verifies not expired (15-min TTL)
         |
         +-- If valid:
             UsernamePasswordAuthenticationToken auth = new UPAT(
                 UserDetails(username=userId), null, emptyList()
             )
             SecurityContextHolder.getContext().setAuthentication(auth)
             -> request proceeds as authenticated
```

### Step 3: ImageController receives the file

**File:** `image/controller/ImageController.java`

```java
@PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
public ResponseEntity<ImageResponse> upload(
        @RequestParam("file") MultipartFile file,
        @AuthenticationPrincipal UserDetails userDetails) {
    ImageResponse response = imageService.upload(file, userDetails.getUsername());
    return ResponseEntity.status(HttpStatus.CREATED).body(response);
}
```

- `@RequestParam("file")` - Spring binds the multipart field named "file"
- `@AuthenticationPrincipal` - Spring injects the UserDetails set by JwtAuthFilter
- `userDetails.getUsername()` - returns the **userId** (UUID), not the email
- The controller has zero business logic - it delegates entirely to ImageService

### Step 4: File Validation

**File:** `image/service/ImageService.java` -> `validateFile()`

Runs first, before any API call or file I/O. Invalid files are rejected instantly at zero cost.

```
ImageService.validateFile(file)
         |
         +-- Is file null or empty?
         |   YES -> throw ImageValidationException("No file provided.")
         |         -> GlobalExceptionHandler -> 422
         |
         +-- Is contentType in { image/jpeg, image/png, image/webp }?
         |   NO  -> throw ImageValidationException("Only JPEG, PNG, and WebP images are accepted.")
         |         -> GlobalExceptionHandler -> 422
         |
         +-- Is fileSize > 10,485,760 bytes (10 MB)?
             YES -> throw ImageValidationException("File size must not exceed 10MB.")
                   -> GlobalExceptionHandler -> 422
```

Spring Boot also enforces `spring.servlet.multipart.max-file-size=10MB` at the Tomcat level.
If Tomcat rejects the file before it reaches the controller, `MaxUploadSizeExceededException`
is thrown and caught by `GlobalExceptionHandler` -> 400 Bad Request.

### Step 5: Image Pre-processing (Thumbnailator resize)

**File:** `image/service/ClaudeVisionService.java` -> `resizeForClassification()`

Before sending to Claude, the image is **resized to a maximum of 1024x1024 JPEG at 85% quality**.
This is done with Thumbnailator (net.coobird:thumbnailator:0.4.20).

```
ClaudeVisionService.resizeForClassification(file)
         |
         +-- Thumbnails.of(file.getInputStream())
         |       .size(1024, 1024)        <- max dimension, aspect ratio preserved
         |       .keepAspectRatio(true)
         |       .outputFormat("jpeg")    <- always convert to JPEG
         |       .outputQuality(0.85)     <- 85% quality
         |       .toOutputStream(out)
         |
         +-- Returns byte[] of the resized JPEG
```

**Why resize?**
- A typical phone photo is 4000x3000px and 5-10MB.
  Sending it raw to Claude would use ~2000-4000 input tokens just for the image.
- After resizing to 1024x1024, the same image uses ~200-400 input tokens.
- **This cuts Claude API cost by ~10x** with zero impact on classification accuracy
  (a room or house exterior is just as identifiable at 1024px as at 4000px).
- The original file (full resolution) is still stored - only the copy sent to Claude is resized.

### Step 6: Claude Vision AI Classification

**File:** `image/service/ClaudeVisionService.java` -> `classify()`

After resizing, the image is sent to Claude Haiku for classification **before** being written
to storage. Invalid images (selfies, food, landscapes, cars, etc.) are rejected here and
never reach storage.

```
ClaudeVisionService.classify(MultipartFile file)
         |
         +-- resizeForClassification(file)
         |   -> byte[] resizedBytes (1024px JPEG)
         |
         +-- Base64.getEncoder().encodeToString(resizedBytes)
         |   -> base64Data string
         |
         +-- Build Claude API request body:
         |   {
         |     "model": "claude-haiku-4-5-20251001",
         |     "max_tokens": 10,
         |     "messages": [{
         |       "role": "user",
         |       "content": [
         |         {
         |           "type": "image",
         |           "source": {
         |             "type": "base64",
         |             "media_type": "image/jpeg",
         |             "data": "<base64 string>"
         |           }
         |         },
         |         {
         |           "type": "text",
         |           "text": "Classify this image for a paint color visualization app.
         |                    Is it an indoor room or outdoor house/building exterior?
         |                    Answer with exactly one word: INDOOR, OUTDOOR, or INVALID."
         |         }
         |       ]
         |     }]
         |   }
         |
         +-- HTTP headers:
         |   x-api-key: ${ANTHROPIC_API_KEY}
         |   anthropic-version: 2023-06-01
         |   Content-Type: application/json
         |
         +-- RestTemplate POST https://api.anthropic.com/v1/messages
         |
         +-- Parse response:
         |   body["content"][0]["text"].trim().toUpperCase()
         |
         +-- Return:
             "INDOOR"  -> ImageType.INDOOR
             "OUTDOOR" -> ImageType.OUTDOOR
             anything else (INVALID, etc.) -> null
```

**Claude's response structure:**
```json
{
  "id": "msg_01XFDUDYJgAACzvnptvVoYEL",
  "type": "message",
  "role": "assistant",
  "content": [{ "type": "text", "text": "INDOOR" }],
  "model": "claude-haiku-4-5-20251001",
  "stop_reason": "end_turn",
  "usage": { "input_tokens": 180, "output_tokens": 1 }
}
```

Back in `ImageService`:
```
imageType = claudeVisionService.classify(file)

if (imageType == null) {
    throw ImageValidationException(
        "Please upload a photo of an indoor room or outdoor house/building exterior..."
    )
    -> GlobalExceptionHandler -> 422 Unprocessable Entity
}
// INDOOR or OUTDOOR -> proceed to storage
```

**Why Haiku and not Sonnet/Opus?**
Image classification is a simple task. Haiku is ~10x cheaper than Sonnet and returns
the answer in a fraction of a second. Sonnet/Opus offer no accuracy advantage here.

**Why max_tokens: 10?**
Claude only needs to output one word. Capping at 10 tokens ensures instant responses
and minimises the tiny per-output-token cost.

### Step 7: Storage

**File:** `image/service/LocalStorageService.java` (or S3StorageService if S3 is active)

Only reached if Claude returned INDOOR or OUTDOOR.

**LocalStorageService (default):**
```
LocalStorageService.store(file, userId)
         |
         +-- Generate storageKey:
         |   "{userId}/{UUID}.{ext}"
         |   e.g. "abc-123-def/9f8e7d6c-5b4a-3210.jpg"
         |
         +-- Resolve full path:
         |   Path.of(storagePath, storageKey)
         |   storagePath = UPLOAD_PATH env var (default: /tmp/huevista/uploads)
         |
         +-- Files.createDirectories(target.getParent())
         |
         +-- Files.copy(file.getInputStream(), target, REPLACE_EXISTING)
         |
         +-- Return storageKey
```

**S3StorageService (when app.s3.bucket-name is set):**
```
S3StorageService.store(file, userId)
         |
         +-- Generate key: "{userId}/{UUID}.{ext}"
         |
         +-- s3Client.putObject(
         |       PutObjectRequest.builder()
         |           .bucket(bucketName)
         |           .key(key)
         |           .contentType(file.getContentType())
         |           .serverSideEncryption(ServerSideEncryption.AES256) <- encrypt at rest
         |           .build(),
         |       RequestBody.fromBytes(file.getBytes())
         |   )
         |
         +-- Return key (stored in DB as storageKey)
```

**URL generation:**
```
// Local storage:
storageService.getPublicUrl(storageKey)
-> "/api/images/files/" + storageKey
-> "/api/images/files/abc-123-def/9f8e7d6c.jpg"
   (served by GET /api/images/files/**)

// S3 storage:
storageService.getPublicUrl(storageKey)
-> S3Presigner generates a pre-signed URL valid for 60 minutes
-> "https://bucket.s3.ap-south-1.amazonaws.com/abc-123/uuid.jpg?X-Amz-Signature=..."
   (client fetches directly from S3 - zero bandwidth through our server)
```

### Step 8: Database persistence

**File:** `image/service/ImageService.java`

```
userRepository.findById(userId)
    -> loads the User entity for the FK relationship

imageRepository.save(
    UploadedImage.builder()
        .user(user)
        .originalFilename(file.getOriginalFilename())
        .storageKey(storageKey)
        .contentType(file.getContentType())
        .fileSize(file.getSize())
        .imageType(imageType)           // INDOOR or OUTDOOR
        .build()
)
-> INSERT INTO uploaded_images (...)
-> @GeneratedValue(UUID) assigns id
-> @CreationTimestamp assigns uploadedAt = now()
```

### Step 9: Response

```
ImageService.toResponse(saved)
-> ImageResponse {
    imageId:          "550e8400-...",
    imageUrl:         "/api/images/files/abc.../9f8e.../jpg"  (or S3 presigned URL)
    originalFilename: "living-room.jpg"
    imageType:        INDOOR
    fileSize:         2457600
    uploadedAt:       2026-05-12T03:07:00
  }

Controller -> ResponseEntity.status(201).body(response)
Jackson serializes to JSON
Client receives 201 Created
```

### Complete Upload - One Line Per Step

```
POST /api/images/upload
         |
[1] CorsFilter         -> check Origin header
[2] JwtAuthFilter      -> validate Bearer token, set SecurityContext userId
[3] AuthorizationFilter-> confirm authenticated
[4] ImageController    -> bind MultipartFile, extract userId from principal
[5] ImageService       -> validateFile() -> type + size checks
[6] ClaudeVisionService-> resize to 1024px JPEG (Thumbnailator)
[7] ClaudeVisionService-> base64 encode -> POST to Anthropic API -> INDOOR/OUTDOOR/null
[8] ImageService       -> null? -> 422. Valid? -> continue
[9] StorageService     -> write to /tmp/huevista/uploads/{userId}/{uuid}.ext
                         (or PUT to S3 with AES256 encryption)
[10] ImageRepository   -> INSERT INTO uploaded_images (...)
[11] ImageController   -> return 201 Created + ImageResponse JSON
```

---

## 5. Flow B - Get Single Image

```
GET /api/images/{imageId}
Authorization: Bearer eyJhbGci...
```

```
[1] JwtAuthFilter      -> validate token -> userId in SecurityContext
[2] AuthorizationFilter-> authenticated check
[3] ImageController.getImage(imageId, userDetails)
         |
         v
[4] ImageService.getImage(imageId, userId)
         |
         +-- imageRepository.findByIdAndUserId(imageId, userId)
         |   SQL: SELECT * FROM uploaded_images
         |        WHERE id = ? AND user_id = ?
         |
         +-- Not found (wrong id OR belongs to different user)?
         |   -> ResourceNotFoundException -> 404
         |
         +-- Found -> toResponse(image) -> ImageResponse
[5] 200 OK + ImageResponse JSON
```

**Security note:** `findByIdAndUserId` means a user can never fetch another user's image even
if they guess the UUID. Ownership is enforced at the DB query level.

---

## 6. Flow C - List All Images

```
GET /api/images
Authorization: Bearer eyJhbGci...
```

```
[1] JwtAuthFilter      -> validate token -> userId
[2] AuthorizationFilter-> authenticated check
[3] ImageController.listImages(userDetails)
         |
         v
[4] ImageService.listImages(userId)
         |
         +-- imageRepository.findByUserIdOrderByUploadedAtDesc(userId)
             SQL: SELECT * FROM uploaded_images
                  WHERE user_id = ?
                  ORDER BY uploaded_at DESC
             -> stream().map(toResponse).collect(toList())

[5] 200 OK + JSON array (empty [] if no uploads)
```

---

## 7. Flow D - Serve Raw Image File

This endpoint is only used when **local storage** is active. When S3 is active, `imageUrl`
in the response is already a pre-signed S3 URL - the client fetches that directly from S3
without hitting our server at all.

```
GET /api/images/files/{userId}/{filename}
Authorization: Bearer eyJhbGci...
```

```
[1] JwtAuthFilter      -> validate token -> userId in SecurityContext
[2] AuthorizationFilter-> authenticated check
[3] ImageController.serveFile(request, userDetails)
         |
         +-- Extract storageKey from URI:
         |   request.getRequestURI() = "/api/images/files/abc-123/9f8e7d6c.jpg"
         |   strip prefix "/api/images/files/"
         |   storageKey = "abc-123/9f8e7d6c.jpg"
         |
         +-- SECURITY CHECK:
         |   storageKey.startsWith(userId + "/")
         |   "abc-123/..." starts with "abc-123/" ?
         |   -> NO (different user) -> 403 Forbidden (empty body)
         |   -> YES (owner) -> continue
         |
         +-- LocalStorageService.load(storageKey)
         |   Files.readAllBytes(Path.of(storagePath, storageKey))
         |   -> byte[]
         |
         +-- Detect Content-Type from extension:
         |   .png  -> "image/png"
         |   .webp -> "image/webp"
         |   else  -> "image/jpeg"
         |
         +-- ResponseEntity.ok()
               .contentType(MediaType.parseMediaType(contentType))
               .body(data)

[4] 200 OK + raw image bytes
```

---

## 8. Internal Component Deep-Dive

### ClaudeVisionService

**File:** `image/service/ClaudeVisionService.java`

A Spring `@Service` that pre-processes the image and makes a single synchronous HTTP POST
to the Anthropic Messages API.

**Dependencies:**
- `RestTemplate` - from `AppConfig.restTemplate()` bean
- `@Value("${app.claude.api-key}")` - reads ANTHROPIC_API_KEY env var
- `@Value("${app.claude.model:claude-haiku-4-5-20251001}")` - configurable model

**Two-phase operation:**
1. `resizeForClassification(file)` - Thumbnailator resize to max 1024x1024 JPEG
2. Base64-encode + POST to Anthropic API

**Return contract:**
| Claude says | Returns | Meaning |
|---|---|---|
| `"INDOOR"` | `ImageType.INDOOR` | Valid - indoor room |
| `"OUTDOOR"` | `ImageType.OUTDOOR` | Valid - house exterior |
| `"INVALID"` or anything else | `null` | Invalid - upload rejected |

---

### StorageService Interface

**File:** `image/service/StorageService.java`

```java
public interface StorageService {
    String store(MultipartFile file, String userId) throws IOException;
    byte[] load(String storageKey) throws IOException;
    void delete(String storageKey);
    String getPublicUrl(String storageKey);
}
```

`ImageService` and `ImageController` depend only on this interface - never on the concrete
implementations. Switching from local to S3 requires zero changes in the service/controller layer.

---

### LocalStorageService

**File:** `image/service/LocalStorageService.java`

A plain Java class (no `@Service` annotation). It is registered as a Spring bean by `StorageConfig`.

**Storage path structure:**
```
{UPLOAD_PATH}/
  +-- {userId}/
       +-- 9f8e7d6c-5b4a-3210-fedc-ba9876543210.jpg
       +-- 1a2b3c4d-5e6f-7890-abcd-ef1234567890.png
```

Each user gets their own subdirectory named by their UUID. Each file gets a fresh UUID as its
filename (prevents guessability even if someone knows the storage path).

---

### S3StorageService

**File:** `image/service/S3StorageService.java`

Active only when `app.s3.bucket-name` is set in properties. Uses AWS SDK v2.

**Key behaviour:**
- `store()` - calls `s3Client.putObject()` with AES256 server-side encryption
- `load()` - calls `s3Client.getObjectAsBytes()` (used only internally; clients get presigned URLs)
- `delete()` - calls `s3Client.deleteObject()`
- `getPublicUrl()` - uses `S3Presigner` to generate a pre-signed GET URL valid for
  `app.s3.presigned-url-expiry-minutes` (default 60 minutes)

**Pre-signed URL flow:**
```
S3StorageService.getPublicUrl(storageKey)
         |
         +-- S3Presigner.presignGetObject(r -> r
         |       .signatureDuration(Duration.ofMinutes(60))
         |       .getObjectRequest(g -> g.bucket(bucketName).key(storageKey))
         |   )
         |
         +-- Returns URL like:
             https://my-bucket.s3.ap-south-1.amazonaws.com/{userId}/{uuid}.jpg
             ?X-Amz-Algorithm=AWS4-HMAC-SHA256
             &X-Amz-Credential=...
             &X-Amz-Date=...
             &X-Amz-Expires=3600
             &X-Amz-Signature=...
```

The client fetches the image directly from S3 using this URL. No image bytes flow through
our application server - this is zero-bandwidth file serving.

---

### StorageConfig

**File:** `image/config/StorageConfig.java`

A `@Configuration` class that registers `LocalStorageService` as a Spring bean using
`@ConditionalOnMissingBean`. This is the correct way to provide a fallback bean.

```java
@Configuration
public class StorageConfig {
    @Bean
    @ConditionalOnMissingBean(StorageService.class)
    public StorageService localStorageService(
            @Value("${app.upload.storage-path:/tmp/huevista/uploads}") String storagePath) {
        return new LocalStorageService(storagePath);
    }
}
```

**Why not @Service + @ConditionalOnMissingBean on LocalStorageService directly?**

`@ConditionalOnMissingBean` is designed to be used on `@Bean` factory methods inside
`@Configuration` classes. When placed on a `@Service` (component-scanned class), the ordering
of condition evaluation is undefined and unreliable - it can result in neither bean being
registered. Moving the condition to a `@Configuration` `@Bean` method guarantees correct
evaluation order.

---

### S3Config

**File:** `image/config/S3Config.java`

Active only when `app.s3.bucket-name` is set. Creates `S3Client` and `S3Presigner` beans.

```java
@Configuration
@ConditionalOnProperty(name = "app.s3.bucket-name")
public class S3Config {
    @Bean public S3Client s3Client() { ... }
    @Bean public S3Presigner s3Presigner() { ... }

    private AwsCredentialsProvider credentialsProvider() {
        if (!accessKey.isBlank() && !secretKey.isBlank())
            return StaticCredentialsProvider.create(
                AwsBasicCredentials.create(accessKey, secretKey));
        return DefaultCredentialsProvider.create(); // IAM role in production
    }
}
```

**Credentials priority:**
1. Explicit `app.s3.access-key` + `app.s3.secret-key` (dev/testing with IAM user keys)
2. `DefaultCredentialsProvider` chain: IAM role -> env vars -> ~/.aws/credentials (production)

---

### ImageService

**File:** `image/service/ImageService.java`

Central coordinator. Enforces the strict order: **validate -> classify -> store -> persist -> respond**.

No step is skippable. If any step throws, the global handler catches it.
- Storage is only written if AI classification succeeds.
- DB is only written if storage succeeds.
- You never get an orphaned file in storage with no DB record.

---

## 9. Storage Layer: Local vs S3

The storage implementation is chosen purely by configuration - no code changes needed.

### How the switch works

| `app.s3.bucket-name` | Active beans | Storage |
|---|---|---|
| Not set (default) | `StorageConfig` registers `LocalStorageService` | Local filesystem |
| Set to bucket name | `S3Config` registers `S3Client` + `S3Presigner`; `S3StorageService` activates; `LocalStorageService` NOT registered (ConditionalOnMissingBean) | AWS S3 |

### Activation diagram

```
application.properties / env vars
         |
         +-- app.s3.bucket-name NOT SET
         |       |
         |       v
         |   S3Config @ConditionalOnProperty -> SKIPPED
         |   S3StorageService @ConditionalOnProperty -> SKIPPED
         |   StorageConfig @ConditionalOnMissingBean -> REGISTERS LocalStorageService
         |
         +-- app.s3.bucket-name = "my-bucket"
                 |
                 v
             S3Config @ConditionalOnProperty -> ACTIVE (S3Client + S3Presigner beans created)
             S3StorageService @ConditionalOnProperty -> ACTIVE
             StorageConfig @ConditionalOnMissingBean -> SKIPPED (S3StorageService already exists)
```

### Switching to S3 in practice

1. Create an S3 bucket in AWS console with:
   - Block all public access: ON
   - Server-side encryption (SSE-S3 AES256): ON
   - Versioning: optional

2. Create an IAM user (for dev) or IAM role (for production) with this bucket policy:
   ```json
   {
     "Effect": "Allow",
     "Action": ["s3:PutObject", "s3:GetObject", "s3:DeleteObject"],
     "Resource": "arn:aws:s3:::YOUR-BUCKET-NAME/*"
   }
   ```

3. Uncomment and set these in application.properties (or set as env vars):
   ```properties
   app.s3.bucket-name=${S3_BUCKET_NAME}
   app.s3.region=${S3_REGION:ap-south-1}
   app.s3.access-key=${AWS_ACCESS_KEY_ID:}
   app.s3.secret-key=${AWS_SECRET_ACCESS_KEY:}
   app.s3.presigned-url-expiry-minutes=60
   ```

4. Set env vars in IntelliJ / deployment:
   ```
   S3_BUCKET_NAME=my-huevista-images
   S3_REGION=ap-south-1
   AWS_ACCESS_KEY_ID=AKIAxxx       (leave blank if using IAM role)
   AWS_SECRET_ACCESS_KEY=xxx       (leave blank if using IAM role)
   ```

No changes to any Java code required.

---

## 10. Database Table

Hibernate auto-creates this table on startup (`spring.jpa.hibernate.ddl-auto=update`).

```sql
CREATE TABLE uploaded_images (
    id                VARCHAR(36)  PRIMARY KEY,
    user_id           VARCHAR(36)  NOT NULL,
    original_filename VARCHAR(255) NOT NULL,
    storage_key       VARCHAR(500) NOT NULL,
    content_type      VARCHAR(50)  NOT NULL,
    file_size         BIGINT       NOT NULL,
    image_type        VARCHAR(10)  NOT NULL,
    uploaded_at       TIMESTAMP,
    CONSTRAINT fk_uploaded_images_user
        FOREIGN KEY (user_id) REFERENCES users(id)
);
```

### Entity -> Table mapping

| Java field | Column | Notes |
|---|---|---|
| `id` | `id` | UUID, @GeneratedValue(UUID) |
| `user` | `user_id` | @ManyToOne FK to users |
| `originalFilename` | `original_filename` | As uploaded by client |
| `storageKey` | `storage_key` | `{userId}/{uuid}.ext` |
| `contentType` | `content_type` | MIME type string |
| `fileSize` | `file_size` | Bytes |
| `imageType` | `image_type` | Enum stored as String: INDOOR or OUTDOOR |
| `uploadedAt` | `uploaded_at` | Auto-set on insert by @CreationTimestamp |

### JPA queries in ImageRepository

```java
// GET /api/images - list all for user, newest first
List<UploadedImage> findByUserIdOrderByUploadedAtDesc(String userId);

// GET /api/images/{id} - get one, enforcing ownership
Optional<UploadedImage> findByIdAndUserId(String id, String userId);
```

---

## 11. Error Handling Reference

All errors pass through `GlobalExceptionHandler` in `common/exception/GlobalExceptionHandler.java`.

### Error response format
```json
{
  "status":    422,
  "error":     "Unprocessable Entity",
  "message":   "Please upload a photo of an indoor room or outdoor house/building exterior.",
  "timestamp": "2026-05-12T03:07:01.123"
}
```

### Exception -> HTTP Status mapping

| Exception | Status | When thrown |
|---|---|---|
| `ImageValidationException` | 422 | File type/size invalid, or Claude returned INVALID |
| `ResourceNotFoundException` | 404 | Image not found or belongs to another user |
| `StorageException` | 500 | Filesystem or S3 I/O failure |
| `MaxUploadSizeExceededException` | 400 | Multipart size exceeds Tomcat limit (10 MB) |
| `MethodArgumentNotValidException` | 400 | @Valid bean validation failure |
| Any other `Exception` | 500 | Unexpected error (Claude API down, DB down, etc.) |

### Why 422 instead of 400 for invalid images?

`400 Bad Request` means the request itself was malformed.
`422 Unprocessable Entity` means the request was well-formed but the content cannot be processed.
A valid JPEG of a selfie is a correct file - the request is fine. But the application cannot
use it. 422 is semantically accurate.

---

## 12. CORS Configuration

**File:** `common/config/CorsConfig.java`

```
Allowed Origins:   value of CORS_ALLOWED_ORIGINS env var
                   default: http://localhost:3000
                   multiple: "https://app.huevista.com,http://localhost:3000"
Allowed Methods:   GET, POST, PUT, DELETE, OPTIONS
Allowed Headers:   * (all, including Authorization)
Allow Credentials: true
Max Age:           3600 seconds (browser caches preflight for 1 hour)
```

CorsFilter runs **before** JwtAuthFilter. This means OPTIONS preflight requests are answered
without a JWT token - which is correct. The browser sends the preflight before the real request.

```
Browser sends OPTIONS /api/images/upload
    |
    v
CorsFilter -> checks Origin -> returns 200 with CORS headers
    |
    v
Browser sends actual POST /api/images/upload  (with JWT + file)
```

---

## 13. Security Decisions Explained

### Why is the storageKey {userId}/{uuid}.ext and not just {uuid}.ext?

Two reasons:
1. **Organisation** - each user's files are in their own subdirectory
2. **Security** - the file-serving endpoint uses the userId prefix as an ownership check:
   `storageKey.startsWith(userId + "/")`. This prevents cross-user file access even if a UUID
   is guessed.

### Why store storageKey in the DB instead of reconstructing from imageId?

The storageKey embeds a separate UUID (the filename), not the imageId. This means:
- The storage location is opaque - clients cannot reverse-engineer where files are stored
- Files can be migrated (e.g. local to S3) by updating only the storageKey column

### Why is AI classification done before storage?

- **Cost**: Every write to S3 costs money. Rejecting invalid images before writing saves money.
- **Cleanliness**: Storage and DB only ever contain validated house photos. No garbage to clean up.
- **UX**: The user gets immediate feedback without any partial state being created.

### Why does GET /api/images/{imageId} use findByIdAndUserId instead of just findById?

If we used `findById(imageId)` and checked ownership afterwards, there's an IDOR vulnerability:
a user could detect that an image with a certain ID exists (404 vs 403 leak). By filtering on
both `id AND user_id` in a single query, the result is indistinguishable - 404 in both cases.

### Why store the original (un-resized) file?

The resize is only for Claude classification (cost saving). The original full-resolution file
is what gets stored - the user uploaded a high-quality image and should be able to download it
at full quality for paint visualisation work.

---

## 14. Cost Optimisation: Claude + S3

### Claude Vision cost reduction

| Without Thumbnailator | With Thumbnailator |
|---|---|
| ~2000-4000 input tokens per image | ~180-400 input tokens per image |
| $0.003-0.006 per image | $0.0003-0.0006 per image |

Haiku pricing (as of 2025): $0.80 per million input tokens.
A 1024px JPEG at 85% quality uses approximately 180-400 input tokens.
At 1000 uploads/day: savings ~$2-5/day vs sending raw images.

### S3 cost reduction via presigned URLs

When S3 is active, clients fetch images directly from S3 using pre-signed URLs. This means:
- **Zero egress bandwidth** through our application server for image downloads
- **No compute cost** for serving image bytes
- S3 direct-to-client transfer is also faster for the user (no server hop)

The `GET /api/images/files/**` endpoint is only relevant for local storage.
With S3, `imageUrl` in the response IS the pre-signed S3 URL.

---

## 15. Environment Variables

All configuration is externalised. Never hardcode secrets.

### Required (app will not start without these)

| Variable | Description |
|---|---|
| `DB_PASSWORD` | PostgreSQL password |
| `JWT_SECRET` | Base64-encoded 256-bit key. Generate: `openssl rand -base64 32` |
| `GOOGLE_CLIENT_ID` | Google OAuth2 client ID |
| `GOOGLE_CLIENT_SECRET` | Google OAuth2 client secret |
| `ANTHROPIC_API_KEY` | Anthropic API key from console.anthropic.com |

### Optional (have safe defaults)

| Variable | Default | Description |
|---|---|---|
| `UPLOAD_PATH` | `/tmp/huevista/uploads` | Local filesystem storage root |
| `CORS_ALLOWED_ORIGINS` | `http://localhost:3000` | Comma-separated frontend origins |

### S3 variables (all optional - only needed when activating S3)

| Variable | Default | Description |
|---|---|---|
| `S3_BUCKET_NAME` | (not set) | **Setting this activates S3.** Leave unset for local storage. |
| `S3_REGION` | `ap-south-1` | AWS region code (e.g. ap-south-1, us-east-1) |
| `AWS_ACCESS_KEY_ID` | (blank = IAM role) | IAM user access key (dev). Leave blank in prod to use IAM role. |
| `AWS_SECRET_ACCESS_KEY` | (blank = IAM role) | IAM user secret key (dev). Leave blank in prod. |

**Important:** `S3_REGION` must be the AWS region code (e.g. `ap-south-1`), NOT the full
display name (e.g. "Asia Pacific (Mumbai)"). The full display name produces an invalid URI.

### Minimal environment to run locally

```bash
DB_PASSWORD=your_postgres_password
JWT_SECRET=your_base64_secret         # openssl rand -base64 32
GOOGLE_CLIENT_ID=your_google_client_id
GOOGLE_CLIENT_SECRET=your_google_client_secret
ANTHROPIC_API_KEY=sk-ant-api03-...
```

### Verify Claude API key is working

```bash
curl https://api.anthropic.com/v1/messages \
  -H "x-api-key: $ANTHROPIC_API_KEY" \
  -H "anthropic-version: 2023-06-01" \
  -H "content-type: application/json" \
  -d '{"model":"claude-haiku-4-5-20251001","max_tokens":10,"messages":[{"role":"user","content":"Hello"}]}'
```

---

*Document maintained alongside the codebase. Update when image upload behaviour changes.*
