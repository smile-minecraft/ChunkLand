# docs/assets

Two images are published from here, and other documents refer to them by path:
the repository [README.md](../../README.md),
[README.zh-TW.md](../../README.zh-TW.md) and
[README.zh-CN.md](../../README.zh-CN.md) use both, the
[zh-TW user guide](../zh-TW/user-guide.md) and the
[zh-CN README](../zh-CN/README.md) use the diagram. Renaming either file means
editing those references too.

| File | Size | Rendered from |
| --- | --- | --- |
| `banner.png` | 1280 × 360 | `src/banner-v3.mcpx` (160 × 45, scaled 8×) |
| `land-boundaries.png` | 1120 × 700 | `src/land-boundaries-v3.mcpx` (224 × 140, scaled 5×) |

## What the images show

`banner.png` is the header illustration: the ChunkLand wordmark next to a chunk
grid with two claims outlined on it.

`land-boundaries.png` is the diagram that explains a boundary. Two neighbouring
lands sit across the grid, each with a smaller claim inside it, plus a marker
for an action that was refused and one that was allowed. The colours mean the
same thing in both images:

| Colour | Meaning |
| --- | --- |
| gold outline | a land — one player's claim |
| teal outline | a sub-land, a claim carved inside the land around it |
| red marker | an action the claim refused |
| green marker | an action the claim allowed |

Each cell of the grid is one 16 × 16 chunk, the unit a claim is measured in.

Both are concept diagrams. Neither is a screenshot of a running server, and
neither was captured from one — they are drawn, which is also why the diagram
carries a "concept diagram, not a screenshot" line of its own.

## The pictures don't decide anything

A diagram can't stand in for the real decision. Whether a break, a container, a
piston push or a projectile ends up allowed depends on the claim, the actor and
the per-event rules ChunkLand applies, and the diagrams leave all of that out.
Where the two disagree, the plugin decides. [LIMITATIONS.md](../../LIMITATIONS.md)
lists the gaps this build does not close.

## Regenerating an image

A `.mcpx` file is plain text: the canvas size, a 13-colour palette, one layer,
and one character per pixel indexing the palette. The `mc-asset` MCP server
edits and renders them — `apply_asset_operations` applies a batch of pixel
operations and rolls the whole batch back if one fails, `build_asset` renders
the canvas to a PNG at its native size, and `transform_asset` with
`resizeMode: nearest` scales that render up. The published PNGs are
nearest-neighbour upscales, so the pixel grid stays crisp.

To change an image, edit its `.mcpx`, render it, scale it to the size in the
table above, and write the result over the published path. Have the tool write
to a new path and copy it over, rather than pointing it at the published file,
so the version you are replacing stays on disk. Then open the PNG and read it:
the files in `drafts/` came out of that same toolchain with letters missing
from their labels, and the tool output said nothing was wrong.

Give text its own clear space. The banner's tagline sits three blank pixel rows
under the land diagram; one row was close enough that the two read as touching.

## Sources and history

`src/` keeps the editable source of each published image with its 1× render:
`banner-v3.mcpx` with `banner-v3.png`, and `land-boundaries-v3.mcpx` with
`land-boundaries-v3.png`.

`drafts/` is history: the two broken drafts that used to sit at the published
paths, superseded sources behind them, earlier renders, the banner that shipped
before v3, and copies of rendered images. It carries its own `.gitignore`, so
the files stay on disk and stay out of the repository — the published PNGs and
`src/` are the only image files git tracks. Read it if you want to see how the
images got here; nothing else should touch it.

## Licence

Both images are original work for ChunkLand, released under the repository's MIT
licence along with everything else in it.