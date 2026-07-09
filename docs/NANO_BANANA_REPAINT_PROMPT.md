# Nano Banana — repaint + segmentation colour scheme

How the house-exterior (and interior) auto-paint pipeline assigns colours, and
where each colour is defined in code. Two separate Nano Banana / Gemini calls do
two different jobs:

| Step | Class | Job |
| --- | --- | --- |
| 1. Clean + repaint | `ImageCleanerService` | Removes clutter and **repaints the actual photo** into the reference palette → this is the canvas shown to the user and fed to step 2. |
| 2. Colour-block edit | `ReplicateNanoBananaSegmenter` | **Edits the cleaned photo**, flooding each surface with a flat category colour (red/green/blue/black) in place. `MaskProcessor.splitColorCodedMask` then splits the result by colour into per-category recolourable regions. |

Step 2 is deliberately framed as an **edit of the real photo**, not an abstract
"generate a segmentation mask" task. Painting flat colour *onto* the existing
surfaces tracks their true edges far better than drawing a mask from scratch
(Nano Banana warns "pixel alignment isn't guaranteed" for generation), so the
derived masks line up with the canvas. The fill is forced **flat and
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
| Main walls | Red `#FF0000` | `#ffffff` white | `#ffffff` white | Yes |
| Accent / highlight wall | Green `#00FF00` | `#ffffff` white | `#ffffff` white | Yes |
| Trim / border (window & door **frames**, fascia, parapet edges, ledges, banding) | Blue `#0000FF` | `#ffffff` white | `#ffffff` white | Yes |
| **Doors + metal/iron railings** | **Black (excluded)** | **`#5c4033` dark brown** | **`#5c4033` dark brown** | **No — kept** |
| Everything else (sky, ground, stone, brick, tile, glass, fixtures…) | Black | original | original | No |

## Doors & railings are "kept", not recoloured

Per the requirement *"I don't want a recolour mask for doors/windows/railings —
keep those"*, doors and metal/iron railings are **not** a recolourable region:

- `ImageCleanerService` paints the door leaves/panels and all metal/iron railings
  a fixed dark brown `#5c4033` (constant `DOOR_RAILING`). Window/door **frames**
  stay trim grey; only the door panels and the railings go brown.
- `ReplicateNanoBananaSegmenter` marks doors and railings **BLACK** in the mask,
  exactly like stone or brick — so `splitColorCodedMask` never creates a region
  for them and the user can't recolour them. They simply keep the brown from the
  clean step.

This keeps the segmentation at four flat colours (red/green/blue/black) and adds
no new `RegionCategory` — consistent with the "main / accent / trim only"
category set.

## Where the colours live (keep in sync)

- `ImageCleanerService` — `EXT_WALL`, `EXT_BORDER`, `INT_WALL`, `INT_BORDER`
  (must match `defaultHexFor` + frontend `DEFAULT_HEX_FOR_KIND`), and
  `DOOR_RAILING` (cleaner-only; no region uses it).
- `SegmentationService.defaultHexFor` — the per-category real colours applied to
  each recolourable region.
- Frontend `DEFAULT_HEX_FOR_KIND` — the same wall/accent/trim hexes.

## Generation settings

- **Model:** Nano Banana Pro (`google/nano-banana-pro`, Gemini 3 Pro Image) for
  the clean/repaint; the segmenter defaults to `google/nano-banana-2`.
- **Resolution:** request **2K** — same price as 1K, sharper edges = cleaner
  masks. 4K costs ~1.8× more. Set via `replicate.image-cleaner.resolution`.
