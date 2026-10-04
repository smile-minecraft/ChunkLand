English · [繁體中文](../../zh-TW/reference/permissions.md) · [简体中文](../../zh-CN/reference/permissions.md)

# Permission reference

29 nodes. **Every one of them defaults to `op`**, so a fresh install gives these
commands to operators and to nobody else. A player needs the node granted
explicitly before `/land` will do anything for them.

Nodes are enforced on the subcommand, not on the land — holding
`chunkland.command.land.trust` lets a player run `/land trust` anywhere; who
they may actually trust is decided separately by the land's own permission
layers.

## Land commands

| Node | Gates |
| --- | --- |
| `chunkland.command.land.help` | `/land help` |
| `chunkland.command.land.wand` | Receiving the selection wand |
| `chunkland.command.land.claim` | `/land claim` — proposing a claim |
| `chunkland.command.land.confirm` | `/land confirm` — committing it |
| `chunkland.command.land.trust` | `/land trust` |
| `chunkland.command.land.untrust` | `/land untrust` |
| `chunkland.command.land.ban` | `/land ban` |
| `chunkland.command.land.unban` | `/land unban` |
| `chunkland.command.land.default` | `/land default` — per-land defaults |
| `chunkland.command.land.binding` | `/land binding` |
| `chunkland.command.land.group` | `/land group` |
| `chunkland.command.land.profile` | `/land profile` |
| `chunkland.command.land.subland` | `/land subland` |
| `chunkland.command.land.expand` | `/land expand` |
| `chunkland.command.land.shrink` | `/land shrink` **and** `/land unclaim` |
| `chunkland.command.land.rename` | `/land rename` |
| `chunkland.command.land.delete` | `/land delete` |
| `chunkland.command.land.explain` | `/land explain` |
| `chunkland.command.land.inspect` | `/land inspect` |
| `chunkland.command.land.log` | `/land log` — the audit log |
| `chunkland.command.land.history` | `/land history` — CoreProtect block history |
| `chunkland.command.land.manage` | `/land manage` — the management GUI and forms |

`shrink` and `unclaim` intentionally share a node: they are the same operation
with a different outcome, so a server that allows shrinking has implicitly
allowed releasing chunks.

A sensible player grant is the set an ordinary player needs for the land they
own — wand, claim, confirm, expand, shrink, rename, delete, subland, trust,
untrust, ban, unban, default, inspect, explain, manage. Leave `log`, `history`
and `binding` to staff unless you have a reason.

## Administration

| Node | Gates | Notes |
| --- | --- | --- |
| `chunkland.admin.bypass` | `/land bypass` | Bypass is deliberately separate from the other admin nodes: holding it never grants ledger, orphan or steward powers, and holding those never grants bypass |
| `chunkland.admin.ledger` | `/land admin ledger ...` | Claim and refund reconciliation |
| `chunkland.admin.orphan` | `/land admin orphan ...` | Listing and purging lands whose world is gone |
| `chunkland.admin.serverland` | Server Land stewardship | Not bound to a `/land` subcommand; it names the steward role over the server's own land |

Do not grant any of these to ordinary players. `orphan purge` is irreversible
and does not refund.

## Diagnostics

| Node | Gates |
| --- | --- |
| `chunkland.debug.m0message` | `/chunkland m0message` |
| `chunkland.debug.m0test` | `/chunkland m0test` |
| `chunkland.debug.visualization` | `/chunkland viz` |

## Action identifiers

An identifier is a `ProtectionActionType` constant name, matched
case-insensitively with surrounding whitespace stripped. The enum has 40
constants, but the three commands accept different ranges:

| Command | Accepts |
| --- | --- |
| `/land default <action>` | The 17 everyday member actions in the table below |
| `/land profile set <profile> <action> <state>` | All 22 `SUBJECT_PERMISSION` actions — the 17 everyday ones plus the 5 management ones |
| `/land explain <action>` | All 40, including the land-rule and cross-boundary actions |

`explain` has the widest range, so it will report a verdict for actions that no
interface lets you configure. That is the read path for diagnosis; it does not
make those actions adjustable.

### Everyday member actions

`/land default` accepts exactly these 17. The gate above
(`MANAGE_MEMBER` and friends) is not among them: a land default never carries
management powers.

`BLOCK_BREAK`, `BLOCK_PLACE`, `CONTAINER_OPEN`, `WORKSTATION_USE`, `DOOR_USE`,
`BUTTON_USE`, `LEVER_USE`, `REDSTONE_USE`, `BUCKET_USE`, `ENTRY`,
`VEHICLE_USE`, `ENTITY_INTERACT`, `ENTITY_DAMAGE`, `ITEM_FRAME`,
`ARMOR_STAND`, `HANGING_ENTITY`, `FARMLAND_TRAMPLE`

### Management actions

`MANAGE_MEMBER`, `MANAGE_PERMISSION`, `MANAGE_SUBLAND`, `EXPAND_LAND`,
`DELETE_LAND`. `/land default` rejects these; `/land profile set` accepts them,
so you can put management powers in a profile and bind the whole profile.

### Land rule actions

`PLAYER_DAMAGE_PLAYER`, `PISTON_MOVE`, `FLUID_FLOW`, `HOPPER_TRANSFER`,
`FIRE_SPREAD`, `FIRE_BURN`, `EXPLOSION_TERRAIN`, `EXPLOSION_ENTITY`,
`MOB_GRIEFING`, `HOSTILE_MOB_SPAWN`, `PASSIVE_MOB_SPAWN`, plus the
cross-boundary set `BLOCK_MOVE_IN`, `BLOCK_MOVE_OUT`, `FLUID_ENTER`,
`FLUID_EXIT`, `ITEM_TRANSFER_IN`, `ITEM_TRANSFER_OUT`,
`DISPENSER_CROSS_BOUNDARY`. Neither `/land default` nor `/land profile set`
accepts these — see [Limitations](../limitations.md). Only `explain` reads
them.

## Granting them

Any permission plugin works — the nodes are ordinary Bukkit permissions. With
LuckPerms:

```
/lp user <player> permission set chunkland.command.land.claim true
```

In a permissions plugin's YAML or in a plugin's own `plugin.yml`:

```yaml
permissions:
  chunkland.command.land.claim: true
```

## Related pages

[Command reference](commands.md) ·
[Server guide](../server-guide.md) ·
[User guide](../user-guide.md)