English · [繁體中文](../zh-TW/server-guide.md) · [简体中文](../zh-CN/server-guide.md)

# Server guide

For whoever runs the server. Covers installation, configuration, backups and
recovery.

## Before you install

ChunkLand runs on **Folia 26.2** with **Java 25**. Paper is not supported. It
requires **AceLib 1.3.0** on the server (`depend: [AceLib]`, so the server
refuses to enable ChunkLand without it) and optionally a Vault-ecosystem
economy plugin, LuckPerms, or CoreProtect.

There is no published release. Build the jar from a checkout:

```bash
./scripts/build-acelib.sh
./gradlew build --no-daemon --console=plain
```

`build-acelib.sh` downloads AceLib 1.3.0 from its fixed GitHub Release asset
into a cache directory outside the working tree and verifies it against a pinned
SHA-256; Gradle then resolves AceLib from there as a `flatDir` dependency. It
never embeds AceLib in the ChunkLand jar.

The output is `chunkland-plugin/build/libs/chunkland-plugin-0.1.0.jar`. It is
self-contained: `chunkland-api`, SQLite and SnakeYAML are inside it. Copy that
one file into `plugins/`.

## First start

Restart the server. ChunkLand creates `plugins/ChunkLand/` containing:

| Path | What it is |
| --- | --- |
| `config.yml` | Your settings. Generated once, on a fresh install |
| `config-last-known-good.yml` | Copy of the last config that loaded and validated |
| `lang/en_US.yml`, `lang/zh_TW.yml` | Message text |
| `chunkland.db` | SQLite database — all land, chunk, permission and ledger data |

Language files are written only when missing. Once they exist the plugin never
overwrites your edits, so an upgraded server keeps an older copy and keys added
since then come from the jar. Deleting a language file makes the plugin write a
fresh one.

## Configuration changes need a restart

There is no reload command. Edit `config.yml`, restart. The internal reload API
that the config file's header comment mentions is not exposed as a command, and
Bukkit's `/reload` is not supported by this plugin or by Folia.

Two settings interact with restarts:

- Every accepted top-level section is validated on load. An unrecognised
  top-level key fails the load loudly instead of being ignored, so a typo
  stops startup rather than silently doing nothing.
- A successful load writes the content to `config-last-known-good.yml`. That
  file is only updated on success, so it always holds the last config known to
  work.

Full key list with defaults and accepted ranges:
[configuration reference](reference/configuration.md).

## Granting permissions to players

**Every permission node defaults to `op`.** Out of the box a regular player
cannot claim land, trust a member, or open the management screen — they get
"you are not allowed to use this command". This is the single most common
support question, so grant the nodes explicitly:

```yaml
# example: let ordinary players manage the land they own
permissions:
  chunkland.command.land.wand: true
  chunkland.command.land.claim: true
  chunkland.command.land.confirm: true
  chunkland.command.land.inspect: true
  chunkland.command.land.explain: true
  chunkland.command.land.trust: true
  chunkland.command.land.untrust: true
  chunkland.command.land.ban: true
  chunkland.command.land.unban: true
  chunkland.command.land.default: true
  chunkland.command.land.subland: true
  chunkland.command.land.expand: true
  chunkland.command.land.shrink: true
  chunkland.command.land.manage: true
```

All 25 nodes and what they gate are in the
[permission reference](reference/permissions.md). The `chunkland.admin.*` and
`chunkland.debug.*` nodes are for operators; do not hand them to players.

## Locking claims down

Entry is allowed by default, so anyone can walk into a loaded claim:

```yaml
subject-defaults:
  global:
    ENTRY: DENY
```

Per-player entry bans override this default in the other direction — a banned
player is refused even when the default allows. With the default set to DENY,
trusted members and the owner still pass, because trust is a separate layer.

The eleven land rules cannot be changed from any interface. `rule-defaults` in
`config.yml` is a commented example that is not wired to anything yet.

## Claim pricing

Purchases ship switched off: `economy.enabled` is `false`, so claiming and
expanding are free, no economy plugin is needed, and `limits` is what bounds a
player (10 chunks in total by default). Set `economy.enabled: true` to charge
for chunks. Charging needs a Vault-ecosystem economy plugin; with the switch on
and no such plugin, the plugin starts normally and player claims are refused as
unavailable.

Chunks claimed while purchases are off cost nothing and refund nothing. Chunks
bought while they were on keep their recorded price and still refund it on
shrink or delete, so leave the economy plugin installed if you turn purchases
off on a live server.

`economy.pricing.tiers` prices each chunk by the owner's **total** chunk count
across all player lands, in the currency named by `economy.currency.code`
(`EMC` by default) with the scale given by `economy.currency.scale`. The shipped
tiers charge 1.00 for the first 20 chunks, 2.00 up to 50, 4.00 up to 100 and
8.00 beyond.

Two rules matter when you edit this:

- The tier table must be a contiguous partition ending in `unbounded`. A gap, an
  overlap, a negative or a non-finite value fails the load and keeps the previous
  snapshot, so player claims fail closed instead of being silently repriced.
- Refunds come from the durable per-chunk cost basis, never from the tiers.
  Editing tiers never changes what a past payment is refunded.
- Do not change `currency.scale` while the ledger holds rows. Stored minor units
  would reconvert to different major amounts.

A claim that fails part-way is compensated, and the compensation lands in the
ledger. Reconcile with `/land admin ledger`.

## Backups

Stop the server, copy `plugins/ChunkLand/`, restart. The database is
a single SQLite file and it is the only thing you cannot regenerate.

**Do not copy that file while the server is running.** SQLite locks the data
file, and copying it under a live write can corrupt it.

Copy `chunkland.db` only after the server has stopped. If
`chunkland.db-wal` or `chunkland.db-shm` are still in the folder, copy those
too — they are SQLite's write-ahead log and shared-memory files, and leaving
them behind drops whatever had not yet been merged into the main file. A clean
shutdown removes them, so their presence means there was an unfinished write.

`config.yml` and your edited language files are worth backing up too, but losing
them is recoverable — see below.

### Restoring

Restore with the server stopped as well. Put the whole `plugins/ChunkLand/`
folder back exactly as you backed it up — the database (`chunkland.db` together
with any `chunkland.db-wal` / `chunkland.db-shm` that was captured alongside it),
`config.yml`, `config-last-known-good.yml` and the language files — and only then
start the server again.

Two parts are easy to leave out. Putting back `chunkland.db` without the `-wal`
and `-shm` files that were captured with it can drop writes that had not been
merged into the main file yet. Putting back `config.yml` without
`config-last-known-good.yml` costs you the fallback that covers a config which no
longer parses, which is exactly when you will be reading this page.

Never restore over a running server. Copying onto a live SQLite file carries the
same corruption risk as copying it out, so stop first, replace the folder, then
start.

Other plugins have the same problem: `LuckPerms/luckperms-h2-v2.mv.db` and
`CoreProtect/database.duckdb` also cannot be opened while the server is running.
Stop first, or use the owning plugin's own command.

## When the config file is broken

ChunkLand decides where its configuration comes from before it loads it.

**The file exists but cannot be read, parsed, or validated.** ChunkLand falls
back to the last-known-good copy. If there is no usable copy, it falls back to a
deliberately conservative configuration. This path never substitutes the
built-in defaults, and protection is never silently widened.

**The file is missing.** Two cases:

- *Fresh install* — no trace of previous use in the folder. The plugin generates
  `plugins/ChunkLand/config.yml` from the built-in defaults and starts on it.
  This happens once.
- *Previously used, file now gone* — no default file is generated; the
  last-known-good copy is used, or the conservative configuration if none
  exists.

The conservative configuration can be **stricter** than what you had: new claims
are disabled in every world, economy operations are disabled so pricing is
unavailable, and protection coverage is tightened with deny as the default. That
is deliberate — a corrupt or missing file must not quietly relax protection.

What the console shows:

| Situation | Level | Content |
| --- | --- | --- |
| Fresh install | `INFO` | Absolute path of the generated file, and that a restart is needed after editing |
| Corrupt file, or missing after previous use | `ERROR` | Path of the config, which fallback was used, the failing key path (for example `worlds.world.vertical-mode`), and that built-in defaults were not used |
| Default file cannot be written | `WARNING` | The plugin still starts on the in-memory built-in defaults |

To recover:

1. Stop the server.
2. Fix `plugins/ChunkLand/config.yml`, or put the missing settings back.
3. Start the server.

Your original `config.yml` is never overwritten, deleted, or renamed on failure —
the plugin only reads it.

> Verification note: this section describes behaviour confirmed at the code and
> automated-test level. It has not been re-verified on a live server — actually
> generating the file, the console wording, and the full
> repair-restart-apply cycle have not been run.

## Checking what happened

| Command | Reports |
| --- | --- |
| `/land inspect` | Land you are standing in: owner, chunk and subland counts, quotas and where they came from, ids, revisions |
| `/land log` | Audit entries, filterable by player uuid, time, action, land uuid, world uuid |
| `/land history` | Block history near you. Requires CoreProtect; reports unavailable without it |
| `/land admin ledger list` / `show` / `resolve` | Claim and refund transactions, and reconciliation of a pending one |
| `/land admin orphan list` | Lands whose world no longer exists |
| `/land admin orphan purge <world-uuid> confirm <nonce>` | Deletes those lands. Irreversible, and does not refund |

Audit retention is `audit.retention-days` (default 180). `0` keeps everything
and runs no purge. Purging only deletes audit rows older than the cutoff;
land, chunk and ledger rows are never touched.

## Related pages

[User guide](user-guide.md) ·
[Configuration reference](reference/configuration.md) ·
[Permission reference](reference/permissions.md) ·
[Limitations](limitations.md)