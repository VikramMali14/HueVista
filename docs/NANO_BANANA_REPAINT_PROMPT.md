# Repaint + segmentation colour scheme

How the house-exterior (and interior) auto-paint pipeline assigns colours, and
where each colour is defined in code. Two separate image-editing model calls do
two different jobs:

| Step | Class | Model | Job |
| --- | --- | --- | --- |
| 1. Clean + repaint | `ImageCleanerService` | Nano Banana Pro (Gemini) | Removes clutter and **repaints the actual photo** into the reference palette → this is the canvas shown to the user and fed to step 2. |
| 2. Colour-block edit | `ReplicateMaskSegmenter` | Nano Banana Pro | **Edits the cleaned photo**, flooding each surface with a flat category colour (red/green/blue/black) in place. `MaskProcessor.splitColorCodedMask` then splits the result by colour into per-category recolourable regions. |

Step 2 is deliberately framed as an **edit of the real photo**, not an abstract
"generate a segmentation mask" task. Painting flat colour *onto* the existing
surfaces tracks their true edges far better than drawing a mask from scratch
(image-editing models don't guarantee pixel alignment when generating), so the
derived masks line up with the canvas. The mask model is configurable
(`REPLICATE_NANO_BANANA_MODEL` — env var name kept for compatibility) and
requests `aspect_ratio: match_input_image`, so the colour-block image comes
back at the photo's own aspect ratio. The fill is forced **flat and
fully-saturated with the photo's own shadows ignored**, so colour-thresholding
in `splitColorCodedMask` stays clean instead of losing shaded pixels.

The blocks use **distinct flat RGB hues** (not the real surface colours) precisely
so the regions separate cleanly — the real wall/accent/trim colours are
identical (all white), so the split can't rely on them; it uses pure
red/green/blue and maps back to the real colours via
`SegmentationService.defaultHexFor`.

## Colour assignments

| Surface | Mask hue (step 2) | Real colour (exterior) | Real colour (interior) | Recolourable by user? |
| --- | --- | --- | --- | --- |
| Main walls | Red `#FF0000` | `#ffffff` white | white (named in words) | Yes |
| Accent / highlight wall | Green `#00FF00` | `#ffffff` white | white (named in words) | Yes |
| Trim / border (window & door **frames**, fascia, parapet edges, ledges, banding) | Blue `#0000FF` | `#ffffff` white | white (named in words) | Yes |
| **Doors + metal/iron railings** | **Black (excluded)** | **`#5c4033` dark brown** | **dark wood brown (named in words)** | **No — kept** |
| Everything else (sky, ground, stone, brick, tile, glass, fixtures…) | Black | original | original | No |

**Interior wall tile is an exception to that last row.** The interior clean prompt
resurfaces and paints a tiled *wall* (a stairwell dado, a corridor band) white
along with the rest of the wall, so by the time the segmenter sees the cleaned
image there is no tile face left to mark BLACK and the wall is recolourable like
any other. Floor tile is untouched, and bathrooms/kitchens keep their tile via
their `houseTypeClause`. Note the dependency: the prompt says *resurface*, not
just *paint*, precisely because `CLADDING IS A MATERIAL, NOT PAINT` in the mask
prompt would otherwise exclude a wall still showing its grout grid.

## Doors & railings are "kept", not recoloured

Per the requirement *"I don't want a recolour mask for doors/windows/railings —
keep those"*, doors and metal/iron railings are **not** a recolourable region:

- `ImageCleanerService` paints the door leaves/panels a fixed dark brown
  (`DOOR_LEAF`, `#5c4033`) and all metal/iron railings a charcoal grey
  (`RAILING`, `#43464a`) — the exterior prompt by hex, the interior one in
  words. Window/door **frames** stay trim colour; only the door panels and the
  railings differ.
- `ReplicateNanoBananaSegmenter` marks doors and railings **BLACK** in the mask,
  exactly like stone or brick — so `splitColorCodedMask` never creates a region
  for them and the user can't recolour them. They simply keep the brown from the
  clean step.

This keeps the segmentation at four flat colours (red/green/blue/black) and adds
no new `RegionCategory` — consistent with the "main / accent / trim only"
category set.

## Where the colours live (keep in sync)

- `ImageCleanerService` — `EXT_WALL`, `EXT_BORDER` (must match `defaultHexFor` +
  frontend `DEFAULT_HEX_FOR_KIND`), and `DOOR_RAILING` (cleaner-only; no region
  uses it).
- `ImageCleanerService.CLEAN_PROMPT_INTERIOR` — the **interior** clean prompt
  asks for its colours in plain words rather than hex: "a clean, bright, pure
  brilliant white" for walls, ceiling and trim, "a dark wood brown" for door
  leaves, "a charcoal grey" for railings. There is no `INT_WALL`/`INT_BORDER`
  constant any more. The words mean the same white as the hexes above, and if
  one moves the other must move with it — the interior prompt is the one place
  the palette is written in English, so nothing greps it out of the code.
  (Why: the interior prompt is by far the longest of the three and a six-digit
  code inside it was one more thing to decode. Nothing downstream parses either
  form — the cleaned photo is an illumination map, and the real per-region
  colours come from `defaultHexFor` and the user's picks.)
- `SegmentationService.defaultHexFor` — the per-category real colours applied to
  each recolourable region.
- Frontend `DEFAULT_HEX_FOR_KIND` — the same wall/accent/trim hexes.

## Generation settings

- **Model:** Nano Banana Pro (`google/nano-banana-pro`, Gemini 3 Pro Image) for
  the clean/repaint; the segmenter defaults to `google/nano-banana-2`.
- **Resolution:** request **2K** — same price as 1K, sharper edges = cleaner
  masks. 4K costs ~1.8× more. Set via `replicate.image-cleaner.resolution`.
