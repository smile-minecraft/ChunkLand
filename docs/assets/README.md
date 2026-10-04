# docs/assets

Eight images are published from here. Other documents refer to all but the logo
by path: the repository [README.md](../../README.md),
[README.zh-TW.md](../../README.zh-TW.md) and
[README.zh-CN.md](../../README.zh-CN.md) use the banner, the boundary diagram
and the five explainers; the user guides in [en](../en/user-guide.md),
[zh-TW](../zh-TW/user-guide.md) and [zh-CN](../zh-CN/user-guide.md) use the five
explainers; the [zh-TW user guide](../zh-TW/user-guide.md) and the
[zh-CN README](../zh-CN/README.md) also use the boundary diagram. Renaming a
file means editing those references too. Nothing links to the logo yet; it is
here for a plugin listing, a wiki or a social card.

| File | Size | Rendered from |
| --- | --- | --- |
| `logo.png` | 512 × 512, transparent | `src/logo-v4.mcpx` (64 × 64, scaled 8×) |
| `banner.png` | 1280 × 360 | `src/banner-v4.mcpx` (320 × 90, scaled 4×) |
| `land-boundaries.png` | 1120 × 816 | `src/land-boundaries-v4.mcpx` (280 × 204, scaled 4×) |
| `claiming.png` | 1120 × 704 | `src/claiming-v1.mcpx` (280 × 176, scaled 4×) |
| `land-shapes.png` | 1120 × 1040 | `src/land-shapes-v1.mcpx` (280 × 260, scaled 4×) |
| `permissions.png` | 1120 × 744 | `src/permissions-v1.mcpx` (280 × 186, scaled 4×) |
| `land-rules.png` | 1120 × 648 | `src/land-rules-v1.mcpx` (280 × 162, scaled 4×) |
| `sublands.png` | 1120 × 744 | `src/sublands-v1.mcpx` (280 × 186, scaled 4×) |

## What the images show

`logo.png` is the mark: one grass block seen from above its corner, with a gold
claim line running round its top face and a teal sub-land in the middle.

`banner.png` is the header illustration: the ChunkLand wordmark next to a slab
of twenty chunks with two claims outlined on it.

`land-boundaries.png` is the diagram that explains a boundary. Two neighbouring
lands sit on the chunk grid, each with a smaller claim inside it, and four
numbered markers show what the boundary does under the shipped defaults. A
green marker is something the land lets happen, a red one something it stops,
and the lines under the map say who is acting in each case:

| Marker | What it shows |
| --- | --- |
| 1, green | anyone may walk into a land — entry is the one thing a stranger gets |
| 2, green | the owner and the players they trust may build |
| 3, red | a stranger inside the land cannot break, place or open anything |
| 4, red | a piston, water or a hopper cannot cross the edge of the land |

The five explainers each answer one question a new player has, and each states
the shipped defaults — a server that changes its config will differ:

| File | Question | What it shows |
| --- | --- | --- |
| `claiming.png` | How do I get land? | Three panels: `/land wand` and two corner clicks, the selection snapping to whole chunks, then `/land claim` and the confirmation. The footer gives the default quota, free and ten chunks |
| `land-shapes.png` | Does a land have to be a rectangle? | No. Three panels grow a rectangle into an L with `/land expand`; three more show what the shape rules accept and refuse: any shape whose chunks touch by an edge, never by a corner alone, and never with a hole |
| `permissions.png` | Who may do what? | A table of stranger, trusted, owner and banned against enter, use, build and manage. A stranger only enters; trust adds use and build; only the owner manages; a ban refuses entry |
| `land-rules.png` | What still works inside? | Fluids, pistons, hoppers and mob spawning run as in vanilla; PVP, explosions, fire spread and mob griefing are blocked; machines stop at the border |
| `sublands.png` | What is a sub-land? | One land with a shop and a farm marked inside it, each a region with its own permissions and its own enter and leave notices |

If a default changes in `config.yml` or in the code, these five go stale before
anything else does: check them against [LIMITATIONS.md](../../LIMITATIONS.md)
and the [configuration reference](../en/reference/configuration.md).

The colours mean the same thing in every image:

| Colour | Meaning |
| --- | --- |
| gold outline | a land — one player's claim |
| teal outline | a sub-land, a claim carved inside the land around it |
| red marker | "denied": the land stopped this action |
| green marker | "allowed": the land let this action happen |
| bright grass | chunks inside a land |
| dark grass | unclaimed chunks |

Each cell of the grid is one 16 × 16 chunk, the unit a claim is measured in.
The lands in the diagram are eight and nine chunks, inside the ten-chunk quota a
player gets by default.

All of them are drawn. None is a screenshot of a running server, and none was
captured from one.

## The pictures don't decide anything

A diagram can't stand in for the real decision. Whether a break, a container, a
piston push or a projectile ends up allowed depends on the claim, the actor and
the per-event rules ChunkLand applies, and the diagrams leave all of that out.
Where the two disagree, the plugin decides. [LIMITATIONS.md](../../LIMITATIONS.md)
lists the gaps this build does not close.

## Regenerating an image

A `.mcpx` file is plain text: the canvas size, a palette of up to a few dozen
colours keyed by one character each (`.` is reserved for transparent), one
layer, and one character per pixel indexing the palette. The `mc-asset` tool
renders them, from its command line or through its MCP server:

```bash
mc-asset build docs/assets/src/banner-v4.mcpx --output /tmp/banner-v4.png
mc-asset transform /tmp/banner-v4.png --resize 1280x360 --resize-mode nearest \
    --output /tmp/banner.png
```

`build` renders the canvas to a PNG at its native size and `transform` with
`--resize-mode nearest` scales that render up (the MCP server calls the same
steps `build_asset` and `transform_asset`, and adds `apply_asset_operations`
for batches of pixel edits that roll back together if one fails). The published
PNGs are nearest-neighbour upscales, so the pixel grid stays crisp.

To change an image, edit its `.mcpx`, render it, scale it to the size in the
table above, and write the result over the published path. Have the tool write
to a new path and copy it over, rather than pointing it at the published file,
so the version you are replacing stays on disk. Then open the PNG and read it:
the files in `drafts/` came out of that same toolchain with letters missing
from their labels, and the tool output said nothing was wrong.

Give text its own clear space. In an earlier banner the tagline sat one blank
pixel row under the artwork, close enough that the two read as touching; the
current one keeps the wordmark and the slab in separate halves.

## Sources and history

`src/` keeps the editable source of each published image with its 1× render:
`logo-v4`, `banner-v4`, `land-boundaries-v4`, `claiming-v1`, `land-shapes-v1`,
`permissions-v1`, `land-rules-v1` and `sublands-v1`, each as a `.mcpx` with a
`.png` beside it.

`drafts/` is history: the two broken drafts that used to sit at the published
paths, superseded sources behind them (the flat v3 diagrams included), earlier
renders, the banners that shipped before v4, and copies of rendered images. It
carries its own `.gitignore`, so the files stay on disk and stay out of the
repository — the published PNGs and `src/` are the only image files git tracks. Read it if you want to see how the
images got here; nothing else should touch it.

## Licence

All of the images are original work for ChunkLand, released under the repository's MIT
licence along with everything else in it.