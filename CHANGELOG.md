# Changelog

All notable changes to ChunkLand are recorded here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/) and the project uses
[semantic versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Added

- `ChunkLandApi.isReady()` reports whether the land index has finished loading
  at startup. Until it is `true`, an empty location answer means unknown, not
  wilderness.
- `ChunkLandApi.getLandAt(worldId, chunkX, chunkZ)` returns the land owning a
  chunk from the in-memory index, without loading the chunk.
- `ChunkLandApi.decideAtBlock(actor, worldId, x, y, z, action)` returns the
  full protection decision ChunkLand's own listeners enforce at that block.
  Wilderness answers `ALLOW`; an unconfirmed index, a disabled plugin or a
  lookup failure answers `DENY`.
- All three are default methods on the interface; an implementation that does
  not override them answers `false`, empty and `DENY`.

### Changed

- `getReadApi().can(actor, landId, action)` now returns real land-level
  decisions through the same permission context the protection engine uses,
  instead of always `false`. It still returns `false` while `isReady()` is
  `false` or the land is unknown. `getRule()` is unchanged and still always
  returns empty.

## [0.1.0] - 2026-10-04

First public shape of the plugin: chunk-based land claims, per-action protection
decisions, and a read-only API for other plugins.

Published as a GitHub Release: the plugin jar
(`chunkland-plugin-0.1.0.jar`, self-contained with the API module, SQLite and
SnakeYAML) plus `SHA256SUMS`, and the API artifact on JitPack
(`https://jitpack.io`) under the root coordinate
`com.github.smile-minecraft:ChunkLand:v0.1.0`. Building from a checkout remains
a developer alternative.

The JitPack artifact is the API module alone — it does not contain
`com.smile.chunkland.ChunkLandPlugin`, the class that declares both entry
points. A consumer that names that class compiles against the plugin jar from the
same release as well (it embeds the API module, so it can stand in for the
JitPack coordinate), and `Bukkit` comes from the consumer's own paper-api. Every
one of those stays `compileOnly`.

#### Land and selection

- Chunk-based claims. `/land wand` gives a selection wand, two corner clicks
  fix a rectangle, and `/land claim <land_name>` turns it into a land.
- Selection boundaries render as particles, budgeted by
  `selection.visualization-*` so a large rectangle does not flood the client.
- Land can grow (`/land expand`), shrink (`/land shrink`), release chunks
  (`/land unclaim`), be renamed, and be deleted. Shrink and delete refund what
  the owner paid.
- Sublands: `/land subland` creates, updates, extends, and deletes nested
  regions that carry their own permissions and their own entry/leave notices.
- Claims, deletes, shrinks and subland writes are two-phase: the plugin
  proposes a revision, and the player confirms it.

#### Protection

- Every action is decided at the moment it happens, not when a chunk loads.
  The decided set covers block break and place, containers, workstations,
  doors, buttons, levers, redstone, buckets, vehicles, entity interaction,
  entity damage, item frames, armour stands, hanging entities, farmland
  trampling, PVP and entry.
- Inside a land, fluid flow, pistons, hopper transfer and mob spawning follow
  vanilla by default; PVP, explosions, fire spread and burn, and mob griefing
  are denied.
- A land boundary is a wall for ownerless mechanics. A piston (including its
  head and a block it moves), a fluid or a hopper that reaches across a land
  edge is refused in either direction, whatever the land's rules say, so a
  machine outside a land cannot push into it, flood it or empty its chests.
  Dispenser transfers that cross a land edge are decided too.
- A stranger's only default right is entry. Every other action is denied until
  the owner trusts them or changes the land's defaults.
- Projectiles are judged at the moment of impact, not by where the shooter
  stood.
- Denials are visible: a particle wall along the refused boundary for entry,
  an outline on the block or entity for refused actions, and a reason on the
  action bar.

#### Permissions

- Direct trust (`/land trust`, `/land untrust`), entry bans (`/land ban`,
  `/land unban`), per-land defaults (`/land default`), reusable profiles
  (`/land profile`), and profile bindings for players and groups
  (`/land binding`, `/land group`).
- `/land explain <action>` prints the verdict for the permission you are
  standing in, together with the layer that decided it.
- `/land manage` opens a management GUI. Bedrock players get the same
  operations as forms.
- Entry bans deny even when the global entry default allows it.

#### Economy

- Purchases ship switched off (`economy.enabled: false`): claiming and
  expanding are free, need no economy plugin, and are bounded by `limits` —
  by default 10 chunks per player and 10 chunks per land, across at most 5
  lands.
- With `economy.enabled: true`, claim pricing goes through a Vault-ecosystem
  economy plugin. Price per chunk is tiered by the owner's total chunk count,
  and refunds are taken from the durable per-chunk cost basis, so editing tiers
  never changes what a past payment is refunded.
- A claim that fails part-way is compensated, and the compensation is written
  to a ledger an operator can reconcile with `/land admin ledger`.

#### Operator surface

- `config.yml` covers quotas, pricing, per-world claim switches, selection and
  particle budgets, denial feedback, message locale, and audit retention. Each
  section is validated; an unknown top-level key fails the load instead of
  being ignored.
- A last-known-good copy of the config is kept and used when the live file is
  unreadable or invalid.
- `/land inspect`, `/land log`, and `/land history` report state, audit
  entries, and nearby block history (the last needs CoreProtect).
- `/land bypass`, `/land admin ledger`, and `/land admin orphan` are the
  administration tools. Orphan purge is for lands whose world no longer exists
  and does not refund.

#### API

- `chunkland-api` is a pure domain module: no Bukkit, no SQL, no AceLib on its
  production classpath. A build guard fails the build if that changes.
- `ChunkLandPlugin.getReadApi()` returns a `ChunkLandApi` that reads from one
  immutable snapshot per call, does no I/O, and never loads a chunk.
- `ChunkLandPlugin.publicEventBus()` returns a synchronous in-process
  `ChunkLandEventBus` for pre/post land and subland events.

#### Known gaps at this version

Read [LIMITATIONS.md](LIMITATIONS.md) before deploying. The short version:
starting a fire is not protected, the eleven land rules have no interface to
change them, entry allows strangers by default, `/tp` is not intercepted, and
there is no reload command.

[Unreleased]: https://github.com/smile-minecraft/ChunkLand/commits/main
[0.1.0]: https://github.com/smile-minecraft/ChunkLand/releases/tag/v0.1.0