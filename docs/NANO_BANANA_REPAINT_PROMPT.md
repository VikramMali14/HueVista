# Nano Banana — repaint + segmentation colour scheme

How the house-exterior (and interior) auto-paint pipeline assigns colours, and
where each colour is defined in code. Two separate Nano Banana / Gemini calls do
two different jobs:

| Step | Class | Job |
| --- | --- | --- |
| 1. Clean + repaint | `ImageCleanerService` | Removes clutter and **repaints the actual photo** into the reference palette → this is the canvas shown to the user and fed to step 2. |
| 2. Segmentation mask | `ReplicateNanoBananaSegmenter` | Produces a **flat colour-coded mask** (red/green/blue/black) that `MaskProcessor.splitColorCodedMask` splits into per-category recolourable regions. |

The mask uses **distinct flat RGB hues** (not the real surface colours) precisely
so the regions separate cleanly — the real wall/accent/trim colours can be
near-identical (on exteriors they are all white), so the mask never uses them; it
uses pure red/green/blue and maps back to the real colours via
`SegmentationService.defaultHexFor`.

## Colour assignments

| Surface | Mask hue (step 2) | Real colour (exterior) | Real colour (interior) | Recolourable by user? |
| --- | --- | --- | --- | --- |
| Main walls | Red `#FF0000` | `#ffffff` white | `#baad9c` sage | Yes |
| Accent / highlight wall | Green `#00FF00` | `#ffffff` white | `#a77e60` | Yes |
| Trim / border (window & door **frames**, fascia, parapet edges, ledges, banding) | Blue `#0000FF` | `#ffffff` white | `#432211` deep brown | Yes |
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
