English · [繁體中文](../../zh-TW/reference/configuration.md) · [简体中文](../../zh-CN/reference/configuration.md)

# Configuration reference

`plugins/ChunkLand/config.yml`. Two rules first:

- **Unknown top-level keys fail the load.** Until a section is implemented, its
  top-level key is rejected rather than ignored, so a typo or a premature paste
  stops startup with an error naming the key. Recovery steps are in the
  [server guide](../server-guide.md#when-the-config-file-is-broken).
- **A successful load copies the content to `config-last-known-good.yml`.**
  That file is only refreshed on a successful load — never by `/land reload`.

## Reloading without a restart

`/land reload` (node `chunkland.command.land.reload`, console included)
re-reads the file and applies it live:

- **Hot — applies on the next read:** `limits` quotas, `messages` settings,
  `selection` sessions and budgets, `subject-defaults` and `rule-defaults`,
  `feedback` tuning, the decision-cache budget, and `claim-enabled` of
  already-listed worlds.
- **Needs a restart — refuses the whole reload, never a partial apply:** added
  or removed worlds, any world's `vertical-mode`, the whole `economy` section,
  and the whole `audit` section. The refusal names the reason; restart the
  server for those. Bukkit's `/reload` stays unsupported.

A reload that fails to read or validate keeps the previous settings and says
why. A reload whose content matches the live settings answers "no change"
without publishing anything. A reload that commits but whose follow-up
notification fails still has the new settings active and says so distinctly.

## messages

| Key | Default | Meaning |
| --- | --- | --- |
| `messages.default-locale` | `en_US` | Locale used when a player has no usable locale of their own. Bundled files are `en_US` and `zh_TW` |
| `messages.cooldown-seconds` | `2` | Quiet time between repeated refusal messages to one player |

## audit

| Key | Default | Range | Meaning |
| --- | --- | --- | --- |
| `audit.retention-days` | `180` | `0` or more | `0` keeps history forever and runs no purge. Otherwise audit rows strictly older than the cutoff are deleted in batches on the persistence thread |

Purge only deletes audit rows. Land, chunk and ledger rows are never touched,
and history of a deleted land stays readable until its own cutoff passes.

## economy

| Key | Default | Meaning |
| --- | --- | --- |
| `economy.enabled` | `false` | The purchase switch. While `false`, claiming and expanding cost nothing and need no economy plugin |
| `economy.currency.code` | `EMC` | The money every price is stored and charged in |
| `economy.currency.scale` | `2` | Minor units per major unit: `2` means 100 minor = 1 major |
| `economy.pricing.tiers` | see below | Price per chunk, tiered by the owner's total chunk count |

Default tiers:

| Range | Price per chunk |
| --- | --- |
| first 20 chunks | `1.00` |
| 20 to 50 | `2.00` |
| 50 to 100 | `4.00` |
| beyond 100 (`unbounded`) | `8.00` |

The owner's total counts all Player Lands and never counts Server Land. Each
tier's `until` is exclusive on the previous bound: `(previousUntil, until]`.

Editing the table:

- It must stay a contiguous partition ending in `unbounded`. A gap, an overlap,
  a negative or non-finite value, or an overflow fails the load and keeps the
  previous snapshot, so player claims fail closed instead of being repriced.
- Refunds always come from the durable per-chunk cost basis, never from these
  tiers. Editing tiers never changes what a past payment is refunded.
- Do not change `currency.scale` while the ledger holds live rows; stored minor
  units would reconvert to different major amounts.

`economy.enabled` decides whether any of this is charged:

- `false` (the shipped default): claims and expands are free and need no economy
  plugin. `limits` is the only thing bounding a player. Chunks claimed this way
  cost nothing and refund nothing.
- `true`: each chunk is charged from the tiers. This requires a Vault-ecosystem
  economy plugin; without one the plugin still starts, and a player claim or
  expand is refused as unavailable.

The currency and tiers are validated whatever the switch says. Chunks bought
while the switch was `true` keep their recorded price and still refund it on
shrink or delete after it is turned off, so keep the economy plugin installed if
you switch a live server off. Without one, a land that still owes a refund is
refused before anything is removed, rather than removed and left for manual
reconciliation. A file with no `economy` section at all behaves like
`enabled: false`.

## limits

| Key | Default | Meaning |
| --- | --- | --- |
| `limits.max-lands-per-player` | `5` | Lands one player may own |
| `limits.max-total-chunks-per-player` | `10` | Total chunks across a player's lands |
| `limits.max-chunks-per-land` | `10` | Chunks in one land |
| `limits.max-sublands-per-land` | `16` | Sublands in one land |
| `limits.max-selection-side-length` | `32` | Longest side of a selection rectangle |
| `limits.max-selection-chunks` | `1024` | Chunks one selection may cover |
| `limits.max-decision-cache-entries` | `4096` | Memory-only protection decision cache budget. `0` disables reuse, so every decision recomputes, still fail-closed |

`/land inspect` reports the limit that applied and which layer it came from, so
when a player hits a cap you can tell whether it was this file, LuckPerms meta
metadata, or the plugin's own default.

## selection

| Key | Default | Range | Meaning |
| --- | --- | --- | --- |
| `selection.session-timeout-seconds` | `600` | — | How long a wand selection stays alive |
| `selection.visualization-max-segments` | `256` | 1–4096 | Boundary edges kept per frame; sorted, prefix kept |
| `selection.visualization-max-particles-per-tick` | `512` | 1–1024 | Particles sent per render tick; the window rotates |
| `selection.visualization-render-distance-blocks` | `64` | 8–128 | Segments culled beyond this viewer distance |
| `selection.visualization-refresh-interval-ticks` | `10` | 1–200 | Ticks between renders |

Visualization is player-scoped and never queries terrain.

## feedback

Everything here is shown to the denied player only and changes nothing about
what is allowed.

| Key | Default | Range | Meaning |
| --- | --- | --- | --- |
| `feedback.entry-wall-enabled` | `true` | — | Draw the red border when entry is refused |
| `feedback.entry-wall-radius-blocks` | `6` | 2–16 | How far around the player the border is traced |
| `feedback.entry-wall-particle-size-percent` | `160` | 25–400 | Dust size; `100` is vanilla |
| `feedback.entry-wall-points-per-block` | `2` | 1–4 | Particles per block along the border |
| `feedback.entry-wall-cooldown-millis` | `2000` | 100–60000 | Quiet time between two walls for one player |
| `feedback.action-mark-enabled` | `true` | — | Outline the block or entity an action was refused on |
| `feedback.action-mark-particle-size-percent` | `110` | 25–400 | Outline dust size |
| `feedback.action-mark-cooldown-millis` | `400` | 100–60000 | Quiet time between two outlines |
| `feedback.push-out-distance-blocks` | `3` | 1–8 | Blocks between the border and the landing spot |
| `feedback.push-out-cooldown-millis` | `500` | 100–60000 | Quiet time between two push-outs for one player |

Keep `push-out-cooldown-millis` at or below the time a player needs to walk the
distance back — about 500 ms for 3 blocks. A longer cooldown leaves the player
held on cancelled moves and the screen stutters.

Landing spots only ever use already-loaded chunks that match the current region;
the plugin never loads a chunk to find somewhere to put someone. If no safe spot
exists, it falls back to the position before the entry, and failing that it just
cancels the move.

## worlds

| Key | Default | Meaning |
| --- | --- | --- |
| `worlds.<name>.claim-enabled` | `true` | Whether claims may be made in that world |
| `worlds.<name>.vertical-mode` | `PER_CHUNK_DEPTH` | `PER_CHUNK_DEPTH` or `FULL_HEIGHT` |

Worlds not listed follow `claim-enabled: true` and `PER_CHUNK_DEPTH`. The shipped
file lists `world` as claim-enabled and `world_nether` and `world_the_end` as
disabled.

`vertical-mode` changes how effective depth is read. Stored depths are never
migrated or rewritten when you switch, so switching the mode does not convert
existing land.

World names are case-sensitive. Unknown worlds warn and are ignored.

## subject-defaults

| Key | Default | Meaning |
| --- | --- | --- |
| `subject-defaults.global.<ACTION>` | `ALLOW` for `ENTRY` | World/global default per subject action |
| `subject-defaults.worlds.<world>.<ACTION>` | inherits | Per-world override |

`subject-defaults` answers "may this actor act?" for a `ProtectionActionType`.
It is separate from `rule-defaults`, which answers "can this mechanic happen
here?" for a `LandRuleType`. The two namespaces are independent typed maps and
never fall back to each other.

A missing section, a missing entry, or an explicit `INHERIT` all mean "fall
through to the next resolver layer", ending at implicit `DENY`. Action keys are
case-insensitive; world names are case-sensitive. Land bindings, land defaults
and subland layers are not configured here — they are managed in game with
`/land trust`, `/land default`, `/land binding`, `/land subland` and
`/land manage`.

The shipped default is:

```yaml
subject-defaults:
  global:
    ENTRY: ALLOW
```

That means a stranger can walk into a loaded claim. Entry bans still deny,
because a ban is decided at the land binding layer. Set `ENTRY: DENY` to close
claims to everyone who is not trusted.

## rule-defaults

`rule-defaults` has the same shape: `rule-defaults.global.<RULE>` and
`rule-defaults.worlds.<world>.<RULE>`, values `ALLOW` / `DENY` / `INHERIT`. It
feeds the world and global layers of the rule chain — land, world, global,
built-in — so a world entry overrides the global one and both override the
built-in defaults below. A missing entry or an explicit `INHERIT` falls through
to the next layer. The per-land layer above them is written in game with
`/land rule`, which is also where the owner sees the explicit, effective and
source values side by side.

Built-in rule defaults, used when no layer sets a value:

| Value | Rules |
| --- | --- |
| `ALLOW` | `FLUID_FLOW`, `PISTON`, `HOPPER_TRANSFER`, `HOSTILE_MOB_SPAWN`, `PASSIVE_MOB_SPAWN` |
| `DENY` | `PVP`, `EXPLOSION_TERRAIN`, `EXPLOSION_ENTITY`, `FIRE_SPREAD`, `FIRE_BURN`, `MOB_GRIEFING` |

These rules only cover what stays inside one land. A piston, a fluid or a
hopper reaching across a land boundary — in either direction — is always
refused, and no entry here opens it. Unlike the subject chain, the rule chain
has no owner guarantee: the owner is bound by the same rules as everyone else.

## Language files

`plugins/ChunkLand/lang/` holds `en_US.yml` and `zh_TW.yml`. They are written
only when missing, so your edits survive upgrades. When a new version adds keys,
existing files keep your values for keys that were already there and pick up the
bundled defaults for the new ones. Delete a file to have it regenerated.

## Related pages

[Server guide](../server-guide.md) ·
[Command reference](commands.md) ·
[API reference](api.md)