English · [繁體中文](../../zh-TW/reference/commands.md) · [简体中文](../../zh-CN/reference/commands.md)

# Command reference

Two commands: `/land` for land management, `/chunkland` for diagnostics.

**All permission nodes default to `op`.** Nothing here works for a regular
player until an operator grants the node. "Player" in the last column means the
command is refused from the console.

## `/land`

### Land actions

| Subcommand | Usage | Permission | Player |
| --- | --- | --- | --- |
| `help` | `/land help` | `chunkland.command.land.help` | yes |
| `help` | `/land help <subcommand>` | `chunkland.command.land.help` | yes |
| `wand` | `/land wand` | `chunkland.command.land.wand` | yes |
| `claim` | `/land claim <land_name>` | `chunkland.command.land.claim` | yes |
| `confirm` | `/land confirm <generation> <revision> <land_name>` | `chunkland.command.land.confirm` | yes |
| `expand` | `/land expand` | `chunkland.command.land.expand` | yes |
| `shrink` | `/land shrink` | `chunkland.command.land.shrink` | yes |
| `unclaim` | `/land unclaim` | `chunkland.command.land.shrink` | yes |
| `rename` | `/land rename <new_name>` | `chunkland.command.land.rename` | yes |
| `delete` | `/land delete confirm <revision>` | `chunkland.command.land.delete` | yes |
| `subland` | `/land subland (select\|create\|update\|delete\|extend) [generation revision] [name]` | `chunkland.command.land.subland` | yes |

`shrink` and `unclaim` share one permission node. `claim` alone does nothing:
it returns a confirmation, and `confirm` is what commits it.

### Permissions on a land

| Subcommand | Usage | Permission | Player |
| --- | --- | --- | --- |
| `trust` | `/land trust <player>` | `chunkland.command.land.trust` | yes |
| `untrust` | `/land untrust <player>` | `chunkland.command.land.untrust` | yes |
| `ban` | `/land ban <player>` | `chunkland.command.land.ban` | yes |
| `unban` | `/land unban <player>` | `chunkland.command.land.unban` | yes |
| `default` | `/land default <action> [ALLOW\|DENY\|INHERIT]` | `chunkland.command.land.default` | yes |
| `binding` | `/land binding (bind\|unbind) player <player> <profile>` | `chunkland.command.land.binding` | yes |
| `binding` | `/land binding (bind\|unbind) group <group> <profile>` | `chunkland.command.land.binding` | yes |
| `binding` | add `--in-subland` to bind inside a subland | `chunkland.command.land.binding` | yes |

`<action>` in `default` is a `ProtectionActionType` name; the value is optional
and omitting it shows the current setting. `default` accepts the 17 everyday
member actions — see [Permissions](permissions.md#action-identifiers).

### Groups and profiles

| Subcommand | Usage | Permission | Player |
| --- | --- | --- | --- |
| `group` | `/land group create <group>` | `chunkland.command.land.group` | yes |
| `group` | `/land group list` | `chunkland.command.land.group` | yes |
| `group` | `/land group add <group> <player>` | `chunkland.command.land.group` | yes |
| `group` | `/land group remove <group> <player>` | `chunkland.command.land.group` | yes |
| `group` | `/land group delete <group>` | `chunkland.command.land.group` | yes |
| `profile` | `/land profile create <profile>` | `chunkland.command.land.profile` | yes |
| `profile` | `/land profile list` | `chunkland.command.land.profile` | yes |
| `profile` | `/land profile set <profile> <action> <state>` | `chunkland.command.land.profile` | yes |
| `profile` | `/land profile delete <profile>` | `chunkland.command.land.profile` | yes |

`create` and `delete` take a single name token; `add`/`remove` take the group
first, then the player. `<player>` accepts a UUID or a name the server can
resolve — see [Player references](#player-references).

### Queries

| Subcommand | Usage | Permission | Player |
| --- | --- | --- | --- |
| `explain` | `/land explain <action>` | `chunkland.command.land.explain` | yes |
| `inspect` | `/land inspect [player]` | `chunkland.command.land.inspect` | no |
| `log` | `/land log [u:<player-uuid>] [t:<duration>] [a:<action>] [land:<land-uuid>] [world:<world-uuid>] [limit:<n>] [page:<n>]` | `chunkland.command.land.log` | no |
| `history` | `/land history` | `chunkland.command.land.history` | no |
| `manage` | `/land manage` | `chunkland.command.land.manage` | yes |

`explain`, `inspect` and `manage` require you to be standing inside a land you
manage. `<action>` is matched case-insensitively against the exact
`ProtectionActionType` names — tab completion offers the valid ones by prefix.
`explain` accepts all 40, so it will explain land-rule actions that no command
can configure; see [Permissions](permissions.md#action-identifiers).
`t:` accepts a duration such as `7d`.

### Administration

| Subcommand | Usage | Permission | Player |
| --- | --- | --- | --- |
| `bypass` | `/land bypass [on\|off]` | `chunkland.admin.bypass` | yes |
| `admin` | `/land admin ledger list [state]` | `chunkland.admin.ledger` | no |
| `admin` | `/land admin ledger show [operation]` | `chunkland.admin.ledger` | no |
| `admin` | `/land admin ledger resolve <operation> [RESOLVED\|REFUNDED\|IGNORED]` | `chunkland.admin.ledger` | no |
| `admin` | `/land admin orphan list` | `chunkland.admin.orphan` | no |
| `admin` | `/land admin orphan purge <world-uuid>` | `chunkland.admin.orphan` | no |
| `admin` | `/land admin orphan purge <world-uuid> confirm <nonce>` | `chunkland.admin.orphan` | no |

Purge is destructive and does not refund. `/land admin orphan ...` routes to the
orphan branch and its own permission node; every other `/land admin ...` verb
routes to the ledger branch.

## `/chunkland`

Diagnostics. Operator-only; players have no reason to touch these.

| Subcommand | Usage | Permission |
| --- | --- | --- |
| `m0message` | `/chunkland m0message [locale]` | `chunkland.debug.m0message` |
| `m0test` | `/chunkland m0test [scheduler\|gui\|form\|cancelall]` | `chunkland.debug.m0test` |
| `viz` | `/chunkland viz <start\|stop\|status>` | `chunkland.debug.visualization` |

## Player references

`<player>` arguments accept a raw UUID, an online player name, or an offline name.
Resolution order:

1. Raw UUID text — parsed directly, always succeeds.
2. Online player, exact case-sensitive name match.
3. Offline-player lookup, run on an async executor. It only answers for players
   the server already knows (has played before, or is online). It may block on a
   profile request, so it runs off the region thread and fails closed on
   timeout.

Anything else fails closed and reports an unknown target. Names that never
joined this server do not resolve — use the UUID. There is no partial-name
matching.

## Related pages

[Permission reference](permissions.md) ·
[User guide](../user-guide.md) ·
[Configuration reference](configuration.md)