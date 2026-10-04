English · [繁體中文](../zh-TW/user-guide.md) · [简体中文](../zh-CN/user-guide.md)

# User guide

For players on a server running ChunkLand. Nothing here needs administrator
access — though as shipped, most commands do (see
[Permissions](reference/permissions.md)).

## Claiming land

Get the wand, click two corners, then name the claim:

```
/land wand
/land claim Home
```

The two clicks set a rectangle. Both corners are chunk-aligned, so a rectangle
covers whole chunks. The boundary is drawn with particles while you move, and
the colours tell you whether the area overlaps land you cannot claim.

`/land claim` does not take effect immediately. It returns a confirmation that
you can click; the claim happens when you confirm. If the selection expired, or
the land changed since you looked at it, you are told to run it again.

To see what you have:

```
/land inspect
```

This prints the owner, chunk count, subland count, the quotas that applied to
you and where those quotas came from, the land id, and the revision numbers.

## Changing the shape

| Command | What it does | Money |
| --- | --- | --- |
| `/land expand` | Adds the chunks in your current selection to the land you are standing in | charged |
| `/land shrink` | Removes selected chunks from the land | refunded |
| `/land unclaim` | Releases selected chunks, keeping the land | refunded |
| `/land delete` | Removes the whole land | refunded in full |
| `/land rename <new_name>` | Renames the land | free |

`expand` and `shrink` need you standing on the land and a selection. `delete`
asks for a confirmation with a revision number, which is also what makes it
safe against someone else's changes.

Shrinking with a selection that covers the entire land is refused — that case is
a delete, and you should use `/land delete`. `unclaim` is refused the same way
rather than leaving you an empty land.

## Letting other people in

```
/land trust <player>       # trusted member of this land
/land untrust <player>     # remove them
/land ban <player>         # refuse entry to this land
/land unban <player>
```

A trusted member passes the actions the land allows members to pass. A ban is
about entry and is decided at the land binding layer, which is why it holds even
when the global entry default says allow.

Player references are UUIDs underneath. A raw UUID always works. A name is
resolved against online players first, then against the server's offline-player
lookup, which runs off the region thread and fails closed if it cannot answer.
In practice: names that have played on this server resolve, names that never
have do not. If a name does not work, use the player's UUID.

## Sublands

A subland is a region inside a land with its own permissions:

```
/land subland select          # pick the region with the wand
/land subland create Shop
/land subland update Shop
/land subland extend          # deepen an existing subland
/land subland delete Shop
```

`extend` changes how deep the protection reaches. After an extend, run the
create or update confirmation once more — the plugin asks for it, because the
live index refreshes on the next confirmation.

## The management screen

```
/land manage
```

Java Edition players get a GUI: a permission page where toggling a default asks
for confirmation first, plus member and ban lists you can page through, add to
and remove from. Bedrock players get the same operations as forms. Form text
follows each player's own language.

One limitation: adding someone from a roster page only offers online players. For
offline players use `/land trust` or `/land unban` in chat.

## When an action is refused

You see a particle wall along the boundary for a refused entry, an outline on
the block or entity for a refused action, and the reason on your action bar.

To find out why, stand inside the land and ask:

```
/land explain BLOCK_BREAK
```

The output gives the outcome, which layer decided it, whether you own the land,
whether admin bypass or the steward role applies, and the covering subland if
there is one.

Being banned does not remove you instantly. If you are already inside, you are
pushed out on your next movement — to the nearest verified exit about three
blocks from the boundary, or to the world spawn if there is no exit.

## Things that do not work the way you might expect

- **Strangers can walk in.** Entry is allowed by default. `/land ban` overrides
  it per player; for everyone, the operator has to set
  `subject-defaults.global.ENTRY: DENY`.
- **You cannot start a fire inside your land.** Fire spread and burning are
  blocked, but setting a fire source is not checked at all.
- **You cannot turn rules on.** PVP, explosions, pistons, fluid flow and the
  rest have no player interface.
- **A thin subland can be crossed at speed.** Entry is judged from where a
  movement started and ended. Elytra, ice boats or a long fall will pass over a
  subland that is thinner than one tick of movement.
- **`/tp` into your land works.** An operator teleporting you in is not blocked
  by entry rules.

[Limitations](limitations.md) has the full list.

## Where to look next

[Command reference](reference/commands.md) ·
[Permission reference](reference/permissions.md) ·
[Limitations](limitations.md) ·
[Server guide](server-guide.md)