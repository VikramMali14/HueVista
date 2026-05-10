AI-Powered Paint Shade Visualizer — Product Requirements Document (PRD)
Project Name: PaintVision AI
Version: 1.0
Prepared For: Development & AI Integration Team
Document Type: PRD + AI Prompt Engineering Guide


1. PROJECT OVERVIEW
1.1 What Is This Product?
PaintVision AI is a web application that allows users to upload a photo of their wall/room and virtually apply real paint shades from a brand catalog (e.g., Asian Paints, Berger, Nerolac) using AI. The AI model analyzes the image, identifies paintable surfaces, and intelligently applies selected shades while preserving shadows, textures, and lighting.
1.2 Core Value Proposition
"See your walls in your chosen color — before you paint a single stroke."


2. USER FLOW (End-to-End)
[Landing Page]

      ↓

[Sign Up / Login]  ←→  [OAuth / Email Auth]

      ↓

[Dashboard]

      ↓

[Upload Image]  →  (JPG/PNG of room or wall)

      ↓

[Select Paint Brand]  →  Asian Paints / Berger / Custom

      ↓

[Browse Shade Deck]  →  Filter by: Color Family, Finish, LRV

      ↓

[Select 1–3 Shades]  →  Primary wall / Accent wall / Trim

      ↓

[Click "Visualize"]

      ↓

[Image sent to AI Model via API]

      ↓

[AI Returns Colorized Image]

      ↓

[Preview & Compare]  →  Before / After Slider

      ↓

[Download / Share / Save to Project]


3. FUNCTIONAL REQUIREMENTS
3.1 Authentication Module
Feature
Description
Sign Up
Email + Password or Google OAuth
Login
JWT-based session management
Forgot Password
Email OTP reset
User Profile
Name, saved projects, history
Guest Mode
1 free try without login, watermarked output



3.2 Image Upload Module
Feature
Spec
Accepted Formats
JPG, JPEG, PNG, WEBP
Max File Size
10 MB
Min Resolution
800 × 600 px
Max Resolution
4000 × 3000 px
Auto-resize
Resize >4MP before sending to AI
Preview
Show thumbnail before submission
Validation
Reject non-room/wall images (optional AI check)


UI Elements Required:

Drag-and-drop upload zone
"Browse Files" fallback button
Image preview card with dimensions
Warning if image is too dark or blurry


3.3 Shade Deck Module
3.3.1 Data Structure for Each Shade
{

  "shade_id": "AP-7729",

  "brand": "Asian Paints",

  "collection": "Royale",

  "shade_name": "Mellow Coral",

  "color_family": "Orange",

  "hex_code": "#E8937A",

  "rgb": { "r": 232, "g": 147, "b": 122 },

  "lrv": 42,

  "finish": ["Matt", "Sheen", "Gloss"],

  "recommended_rooms": ["Living Room", "Bedroom"],

  "is_popular": true,

  "image_swatch_url": "/swatches/AP-7729.png"

}
3.3.2 Shade Deck UI Features
Grid view of color swatches (40–200 shades per collection)
Filter by: Color Family, Finish Type, LRV (Light Reflectance Value), Collection
Search by shade name or code
"Compare" button to place 2 shades side-by-side
Shade detail popup: name, code, finish, recommended rooms, similar shades
Maximum selection: 3 shades per visualization (Primary, Secondary, Trim)


3.4 AI Colorization Module
3.4.1 What Gets Sent to the AI
{

  "image_base64": "<base64_encoded_image>",

  "selected_shades": [

    {

      "zone": "primary_wall",

      "shade_name": "Mellow Coral",

      "hex_code": "#E8937A",

      "rgb": { "r": 232, "g": 147, "b": 122 },

      "finish": "Matt"

    },

    {

      "zone": "accent_wall",

      "shade_name": "Deep Ocean",

      "hex_code": "#1B4F72",

      "rgb": { "r": 27, "g": 79, "b": 114 },

      "finish": "Sheen"

    }

  ],

  "brand": "Asian Paints",

  "settings": {

    "preserve_shadows": true,

    "preserve_texture": true,

    "blend_mode": "realistic"

  }

}
3.4.2 What the AI Returns
{

  "status": "success",

  "colorized_image_url": "https://cdn.paintvision.ai/results/job_xyz.png",

  "colorized_image_base64": "<base64>",

  "zones_detected": ["primary_wall", "ceiling", "floor", "furniture"],

  "zones_colored": ["primary_wall"],

  "confidence_score": 0.91,

  "processing_time_ms": 3200

}


4. AI PROMPT ENGINEERING GUIDE
This section defines all prompts to be used when calling the AI/Vision model. These prompts must be sent as system or user messages alongside the image.


4.1 MASTER SYSTEM PROMPT (Send Once per Session)
You are an expert AI interior design assistant specializing in wall paint visualization.

Your role is to analyze room/wall images and apply paint shades realistically,

exactly as a professional painter would. You must:

1. Accurately identify walls, ceilings, trims, and other paintable surfaces.

2. Apply the exact color specified by its HEX code and finish type to the correct zone.

3. Preserve all original shadows, light gradients, wall textures, fixtures, furniture,

   doors, windows, and non-wall elements untouched.

4. Blend colors naturally with room lighting — do not apply flat, uniform color blocks.

5. Respect surface finish:

   - Matt: low reflectance, soft matte appearance

   - Sheen/Eggshell: slight sheen, soft glow on lit surfaces

   - Gloss: high shine, reflections visible

6. Return only the modified image. Do not add text, labels, watermarks, or UI elements.

7. The output image must be the same resolution and aspect ratio as the input.


4.2 USER PROMPT — Single Wall Colorization
Here is a photo of a room/wall.

Task: Apply the paint shade described below ONLY to the PRIMARY/MAIN WALL visible

in this image. Do not change any other surfaces.

Paint Shade Details:

- Brand: {brand_name}

- Shade Name: {shade_name}

- Shade Code: {shade_code}

- HEX Color: {hex_code}

- RGB: R={r}, G={g}, B={b}

- Finish Type: {finish_type}

Instructions:

- Identify the largest wall surface in the image (primary wall).

- Apply the HEX color {hex_code} to it with a {finish_type} texture appearance.

- Keep shadows, texture, corners, edges and any imperfections to maintain realism.

- Do NOT paint: ceiling, floor, furniture, doors, windows, or decorative items.

- Ensure color blending at edges where the wall meets other surfaces (ceiling, floor, corners).

Output: A single realistic, photorealistic image with the paint applied.


4.3 USER PROMPT — Multi-Zone Colorization (Primary + Accent + Trim)
Here is a photo of a room. Apply different paint shades to different zones as specified below.

Zone 1 — PRIMARY WALL (largest wall):

- Shade: {shade_name_1} | HEX: {hex_1} | Finish: {finish_1}

Zone 2 — ACCENT WALL (feature wall, usually opposite or perpendicular to main wall):

- Shade: {shade_name_2} | HEX: {hex_2} | Finish: {finish_2}

Zone 3 — TRIM & MOLDINGS (door frames, window frames, skirting boards):

- Shade: {shade_name_3} | HEX: {hex_3} | Finish: {finish_3}

Rules:

- Apply each shade only to its designated zone.

- Maintain realistic lighting and shadow transitions between zones.

- Do NOT alter furniture, flooring, ceiling (unless specified), or decorative objects.

- Output must look like a real painted room — not a digital overlay or flat fill.

- Ensure natural edge blending where two zones meet.


4.4 USER PROMPT — Shade Suggestion (AI Auto-Recommends)
I have uploaded a photo of my room. Based on the existing room's:

- Furniture colors

- Flooring tone

- Natural lighting

- Overall style (modern / traditional / minimalist / etc.)

Please recommend 3 paint shades from the following Asian Paints shade palette:

[Insert shade list as JSON array with hex codes and names]

For each recommendation:

1. Shade Name & Code

2. Why it complements this room

3. Which wall it should go on (primary / accent / trim)

4. Suggested finish (Matt / Sheen / Gloss)

Format the response as a JSON object.


4.5 USER PROMPT — Wall Detection Only (Pre-Processing Step)
Analyze this room image and identify all paintable surface zones.

Return a JSON object with:

{

  "zones_detected": [

    {

      "zone_id": "zone_1",

      "label": "Primary Wall",

      "location": "back center",

      "approximate_percentage_of_image": 38,

      "current_color_hex": "#F5F0EB",

      "is_paintable": true

    },

    ...

  ],

  "room_type_guess": "Living Room",

  "lighting_condition": "natural daylight / artificial / mixed",

  "image_quality": "good / too_dark / too_blurry / insufficient_wall_area"

}

Do not modify or return the image. Only return the JSON.


4.6 IMAGE VALIDATION PROMPT (Run Before Colorization)
Examine this image and determine if it is suitable for paint visualization.

Check for:

1. Is this an indoor room or wall? (Yes/No)

2. Is there at least one clearly visible wall surface? (Yes/No)

3. Is the image sharp enough for realistic colorization? (Yes/No)

4. Is the image lighting sufficient (not too dark)? (Yes/No)

5. Is the wall area at least 25% of the total image? (Yes/No)

Return ONLY a JSON:

{

  "is_suitable": true/false,

  "reasons": ["reason if not suitable"],

  "suggestion": "what the user should do to get a better image"

}


5. SHADE DECK — ASIAN PAINTS SAMPLE DATA
Below is a starter dataset. Expand this with the full official catalog.

[

  { "shade_id": "AP-0001", "shade_name": "Polar White", "hex_code": "#F8F8F0", "rgb": {"r":248,"g":248,"b":240}, "color_family": "White", "lrv": 91, "finish": ["Matt","Gloss"] },

  { "shade_id": "AP-1021", "shade_name": "Sunbeam Yellow", "hex_code": "#F5D76E", "rgb": {"r":245,"g":215,"b":110}, "color_family": "Yellow", "lrv": 68, "finish": ["Matt","Sheen"] },

  { "shade_id": "AP-2045", "shade_name": "Dusty Rose", "hex_code": "#D4A0A0", "rgb": {"r":212,"g":160,"b":160}, "color_family": "Pink", "lrv": 44, "finish": ["Matt"] },

  { "shade_id": "AP-3012", "shade_name": "Sage Mist", "hex_code": "#A8C5A0", "rgb": {"r":168,"g":197,"b":160}, "color_family": "Green", "lrv": 52, "finish": ["Matt","Sheen"] },

  { "shade_id": "AP-4055", "shade_name": "Ocean Depth", "hex_code": "#2E6DA4", "rgb": {"r":46,"g":109,"b":164}, "color_family": "Blue", "lrv": 18, "finish": ["Matt","Sheen"] },

  { "shade_id": "AP-5033", "shade_name": "Sandstone Beige", "hex_code": "#D4B896", "rgb": {"r":212,"g":184,"b":150}, "color_family": "Neutral", "lrv": 53, "finish": ["Matt","Sheen","Gloss"] },

  { "shade_id": "AP-6022", "shade_name": "Terracotta Dreams", "hex_code": "#C4683A", "rgb": {"r":196,"g":104,"b":58}, "color_family": "Orange", "lrv": 22, "finish": ["Matt"] },

  { "shade_id": "AP-7041", "shade_name": "Lavender Bliss", "hex_code": "#B8A0C8", "rgb": {"r":184,"g":160,"b":200}, "color_family": "Purple", "lrv": 40, "finish": ["Matt","Sheen"] }

]


6. TECHNICAL ARCHITECTURE
┌──────────────────────────────────────────────────────────┐

│                      FRONTEND (React/Next.js)            │

│  ┌─────────────┐  ┌──────────────┐  ┌────────────────┐  │

│  │  Auth Pages  │  │ Upload + Deck│  │  Result Viewer │  │

│  └─────────────┘  └──────────────┘  └────────────────┘  │

└─────────────────────────┬────────────────────────────────┘

                          │ REST API / GraphQL

┌─────────────────────────▼────────────────────────────────┐

│                    BACKEND (Node.js / Python FastAPI)     │

│  ┌──────────────┐  ┌──────────────┐  ┌───────────────┐   │

│  │  Auth Service│  │ Image Service│  │  Shade DB API │   │

│  └──────────────┘  └──────┬───────┘  └───────────────┘   │

└─────────────────────────  │  ────────────────────────────┘

                            │

┌───────────────────────────▼──────────────────────────────┐

│                   AI MODEL LAYER                          │

│  Option A: Claude API (Vision) + Image Generation        │

│  Option B: OpenAI GPT-4o Vision + DALL·E / Stable Diff  │

│  Option C: Custom fine-tuned model on room data          │

└──────────────────────────────────────────────────────────┘

                            │

┌───────────────────────────▼──────────────────────────────┐

│             STORAGE & CDN                                 │

│  AWS S3 / Cloudflare R2  →  CloudFront / Cloudflare CDN  │

└──────────────────────────────────────────────────────────┘


7. API ENDPOINTS (Backend)
Method
Endpoint
Description
POST
/api/auth/register
Register new user
POST
/api/auth/login
Login, returns JWT
GET
/api/shades
Get all shades (with filters)
GET
/api/shades/:brand
Get shades by brand
POST
/api/upload
Upload image, returns image_id
POST
/api/colorize
Send image_id + shades to AI
GET
/api/results/:job_id
Poll for AI result
GET
/api/projects
Get user's saved projects
POST
/api/projects
Save a colorization result



8. UI SCREENS REQUIRED
Landing Page — Hero section with before/after demo, CTA to sign up
Login / Sign Up Page — Clean auth forms
Dashboard — Recent projects, quick-start button
Upload Screen — Drag-drop zone, tips for best photos
Shade Deck Screen — Full filterable palette grid
Shade Detail Modal — Swatch, name, code, similar shades
Processing Screen — Loading animation while AI works
Result Screen — Before/after slider, download, share, save
Saved Projects — Gallery of past visualizations


9. NON-FUNCTIONAL REQUIREMENTS
Requirement
Target
AI Processing Time
< 10 seconds per image
Image Upload Speed
< 5 seconds on 10 Mbps
System Uptime
99.5%
Concurrent Users
Support 500 simultaneous
Mobile Responsive
Yes (iOS + Android browsers)
Security
HTTPS, JWT, input sanitization
GDPR Compliance
User data deletion on request



10. PHASE-WISE DELIVERY PLAN
Phase 1 — MVP (6–8 Weeks)
Auth (email + Google)
Image upload + preview
Shade deck (Asian Paints, static JSON)
Single wall AI colorization
Basic result viewer + download
Phase 2 — Enhanced (4–6 Weeks)
Multi-zone colorization
AI shade recommendation
Before/after slider
Save to project / history
Phase 3 — Scale (4–6 Weeks)
Multiple paint brands
Mobile app (React Native)
Share to social
Pro subscription plan
API access for paint retailers


11. MONETIZATION OPTIONS
Model
Description
Freemium
3 free visualizations/month, then ₹299/month
B2B License
White-label for Asian Paints stores / dealers
Pay Per Use
₹29 per visualization
Pro Plan
Unlimited + HD downloads + priority AI



12. NOTES FOR DEVELOPERS
Always compress uploaded images before sending to AI (use sharp / PIL)
Cache AI results by (image_hash + shade_ids) to avoid duplicate API calls
Store shade deck in a PostgreSQL database for fast filtering
Use WebSocket or polling for AI job status (not blocking HTTP)
Watermark free-tier results in the bottom-right corner
Log all AI calls with tokens used and latency for cost monitoring



Document Version: 1.0 | Created: May 2026 | Status: Ready for Development

