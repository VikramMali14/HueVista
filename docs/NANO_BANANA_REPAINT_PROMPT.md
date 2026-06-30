# Nano Banana Pro — House Exterior Repaint Prompt

This is the finalized prompt + colour mapping for the **clean repaint / declutter**
pass that turns a raw house photo into a real-estate-style base render, from which
the recolor masks are extracted.

## Why flat RGB hues instead of the real greige colours

Mask extraction reads the regions back **out of the generated image by colour**.
The real target colours (`#e2e2d9` wall and `#b6b7b0` accent) are almost identical
greige — they bleed together under thresholding, JPEG noise and the model's own
colour drift. So the generation step paints each surface type a **maximally distinct
flat hue**, and each hue is mapped to its real colour downstream.

Each region keeps its **original light/shadow gradient** (recolour, do not flatten).
Hue stays constant across light and shade, so masks still extract cleanly by hue while
the 3-D form is preserved for the final recolor.

## Colour mapping (generated hue → real colour)

| Region | Generated colour | Maps to |
| --- | --- | --- |
| Main walls (plaster / painted concrete) | Red `#FF0000` | `#e2e2d9` (soft light greige) |
| Accent / side / return walls | Green `#00FF00` | `#b6b7b0` (greige highlight) |
| Trim / border (window & door frames, fascia, parapet edges, ledges, bands) | Blue `#0000FF` | `#585858` (mid grey) |
| Metal/iron railings + wooden/iron doors ("keep") | Yellow `#FFFF00` | dark brown (not user-recolorable) |

> Railings + doors are **"keep"** elements: painted a distinct hue only so their mask
> boundary is detectable; they are preserved as dark brown, not exposed for user recolor.
> Yellow is used (instead of brown) because it is maximally distinct from the red walls,
> giving the cleanest mask separation.

## Generation settings

- **Model:** Nano Banana Pro (Gemini 3 Pro Image, `gemini-3-pro-image-preview`)
- **Resolution:** 2K — same price as 1K ($0.134/image official) and sharper edges =
  cleaner masks. Only go 4K ($0.24/image) if a specific case needs it.

## Prompt

```
Look at this photograph of a house. Edit it so the house looks freshly painted and
free of clutter — like a real-estate listing photo taken right after a clean repaint.
Keep every architectural element pristine and preserve the EXACT perspective, layout,
dimensions, materials, lighting and shadows. Only the COLOUR of painted surfaces changes.

REMOVE (clutter): electrical/telephone wires, power lines, cables crossing the building;
garbage, debris, construction material on the ground; parked cars, motorcycles, scooters,
bicycles in front of the house; tree branches, leaves, bushes covering wall surfaces;
hanging laundry and temporary banners (not permanent signage); scaffolding, ladders,
wooden props and bracing; people and animals.

REPAINT — use these EXACT flat colours, one even hue per surface type, but KEEP each
surface's original highlights, shadows and soft gradients (recolour, do not flatten into
a solid sticker):
- Every painted wall (plaster, painted concrete): pure red #FF0000.
- Accent / side / return walls (the secondary wall planes): pure green #00FF00.
- Door frames, window frames, fascia, parapet edges, ledges, bands and trim: pure blue #0000FF.
- Metal/iron railings and all wooden or iron doors: yellow #FFFF00.
- No peeling, water stains, dust streaks, faded patches or graffiti — one clean uniform
  hue per surface.

FINISH unfinished walls: where a wall is bare cement, raw blockwork/brick, or patchy
half-applied plaster, complete the plaster across the WHOLE wall into one smooth even
paintable surface following the wall's existing plane, perspective and outline exactly —
then paint that whole wall the same red #FF0000. Do NOT move or invent corners, windows,
doors or edges. Do NOT plaster over intentional exposed-brick feature walls, natural
stone, or decorative tile — those stay exactly as they are.

KEEP UNCHANGED (shape & position): doors, windows, grilles, balconies, railings, columns,
parapets, mouldings, ledges keep their exact shape and position — only paint colour
changes. Keep roof, eaves, chimneys, wall-mounted AC units and drainpipes visible (they
are part of the house, not clutter). Keep lighting, shadows, time of day, weather, sky,
camera angle, perspective, framing and image dimensions. Do NOT widen, narrow or reshape
the building. Finished/decorative stone, brick, tile, marble and wood stay in their
original colour.

OUTPUT: the same photograph, clutter removed, unfinished walls completed into smooth
plaster, painted surfaces recoloured to the flat hues above (walls red, accent walls
green, trim blue, railings+doors yellow), each keeping its original light and shade.
Pixel-faithful to the original in shape, proportion and material.
```

## Notes

- If the railing/door mask ever bleeds into another region, the yellow placeholder can be
  swapped for any other unused primary (e.g. magenta `#FF00FF`) — just update the mapping
  table; it still resolves to brown.
- Extract masks by **hue**, not exact RGB, since the model will not output the literal hex
  and applies its own lighting variation.
