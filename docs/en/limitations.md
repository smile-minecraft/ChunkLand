English · [繁體中文](../zh-TW/limitations.md) · [简体中文](../zh-CN/limitations.md)

# Known limitations

What you will actually see, what it affects, and what to do instead. Every entry
below was confirmed against the behaviour under test; entries that are not yet
confirmed on a live server say so.

This list is not claimed to be exhaustive. Behaviour not listed here is not
promised by this document.

## 1. Starting a fire is not protected

Fire spread and burning are blocked by default, but there is no decision point
for *igniting* — lava can light a fire. Spread and burn are stopped; a fire that
is already lit has to be put out by hand.

**Workaround:** extinguish fires you find and remove lava sources you do not need
inside a claim. Reduce who can get in and be trusted, so fewer people can light
one.

## 2. The land rules cannot be changed

All eleven land rules — PVP, terrain explosion, entity explosion, fire spread,
fire burn, mob griefing, fluid flow, pistons, hopper transfer, hostile mob
spawning, passive mob spawning — have no player or administrator interface.
They run on built-in defaults. Inside a land, fluid flow, pistons, hopper
transfer and both kinds of mob spawning are allowed and follow vanilla; PVP,
terrain and entity explosions, fire spread and burn, and mob griefing are
denied. The `rule-defaults` section in `config.yml` is a commented example and
is not wired to anything.

A piston, fluid or hopper that reaches across a land boundary is always refused,
whichever way it points: from outside into a land, from a land out, or between
two different lands, including two neighbouring lands with the same owner. This
is not one of the eleven rules and nothing opens it.

**Workaround:** keep a machine inside one land, and design around the denied
mechanics. There is no player-side route today.

## 3. The `/land manage` screen: what it cannot do

The Java Edition management screen works. The permission page toggles defaults
after a confirmation, the member and ban lists can be viewed, paged, added to and
removed from, and the back button returns to the previous page.

The one remaining limit: adding from a roster page only offers **online**
players. To add someone offline, use `/land trust` for members or `/land unban`
to clear a ban.

Bedrock players get the same operations as forms. A step that has no form yet
tells the player to use the chat command instead.

## 4. What "entry denied" actually does

- Entry defaults to `ALLOW` (`subject-defaults.global.ENTRY: ALLOW`), so an
  unbanned stranger can enter a loaded claim. This is the shipped default, not a
  per-server setting somebody got wrong.
- A ban still holds: bans are decided at the land binding layer with deny
  priority, which outranks the world and global defaults.
- When a move is refused, the player is pushed to about 3 blocks back on the
  outside of the boundary, shortening if there is no room. Landing spots only use
  already-loaded chunks from the current region; the plugin never loads a chunk
  to find one. With no safe spot it returns the player to where they were, and
  failing that it just cancels the move.
- A banned player who is already inside is not teleported out immediately. They
  are pushed out on their next movement, to the nearest verified exit about 3
  blocks from the boundary, or to the world spawn if there is no exit.
- Push-out cooldown defaults to 500 ms and is set by
  `feedback.push-out-cooldown-millis` (100–60000 ms).
- Entry is judged from the start and end points of a movement only. A very thin
  subland crossed at speed is section 8.

**Workaround:** after banning someone who is still inside, ask them to leave or
handle it yourself. You can also set the ENTRY default back to DENY, which
closes the claim to strangers as well.

## 5. Selection and subland: two current differences

**Selection is rectangle-only.** Per-chunk add and remove with left and right
click is not implemented. Both buttons currently do the same thing: fix the
first corner, then move the second.

**A subland depth extension needs a second confirmation.** After an extend, the
live index refresh happens on a slightly later tick, so the plugin asks you to
run the create or update confirmation once more. Run it again and it goes
through — you do not need to redo the selection.

## 6. Refusal messages use the server's default locale

Refusal messages on the action bar render in the server's configured default
locale rather than per player. A player whose client is set to another locale
sees the default-language text. The important information is in the main text, so
it is still readable. Per-player localisation is a later improvement.

## 7. Atomic authorisation covers database state only

Writing a land default — from the `/land manage` confirmation page or from
`/land default` — compares the authorisation generation you were shown against
the current database value inside the same transaction. A mismatch rejects the
whole write: no `land_defaults` change, no audit row, no event.

Only database-backed authorisation writes advance the generation: trust and
untrust, bindings, groups and members, permission profiles, and land defaults
themselves. A rename is not an authorisation change and does not advance it.

Config defaults, admin bypass and the server-land steward are in-memory sources.
They do not advance the generation and take no part in the in-transaction
comparison; revoking one is only visible at the permission gate *before* a write
is submitted. So if you revoke an in-memory source after the gate has passed but
before the transaction lands, that in-flight write still succeeds. How long the
wait is depends on the write queue and transaction latency — writes share one
persistence thread and queue when it is busy — and there is no fixed upper
bound. Revocations that come from the database, such as an untrust or an unbind,
are always caught.

**Workaround:** after revoking an in-memory authorisation, let the current
operation finish before verifying `land_defaults`, or check the live value
yourself.

## 8. A thin subland can be crossed at speed

Entry is judged from a movement's start and end points, not the path between
them. If a subland is thin in the direction of travel and the player covers more
than that thickness in one game tick, with start and end both outside the
subland, the crossing is not judged and nobody is pushed back. Elytra (with
firework boost), ice boats, a trident, a long fall, or plugin-driven movement can
all do it.

**What this does not affect:** damage, placing, opening containers, interacting
and combat after the crossing are still judged per action at the position the
player is actually at, so crossing does not grant rights inside the subland.
Land-level bans are whole-chunk, far thicker than any movement distance, and
cannot be crossed. Walking, sprinting and jumping move under one block per tick
and cannot cross even the thinnest subland.

**Workaround:** make a subland that is meant to be a wall thick in the direction
of travel — 4 blocks or more vertically to stop a fall, 8 or more horizontally
to cover elytra and vehicles. Those numbers are reasoned, not measured; treat
them as a starting point. Otherwise express "no passage" with a land-level ban
or the global ENTRY default.

> Verification note: this section follows from the code and static analysis
> (the movement check reads only the start and end points). It has not been
> reproduced at speed on a live server.

## 9. `/tp` is not intercepted

When an operator or the console teleports a player directly into a claim with
`/tp`, entry judgement does not block it. The player lands at the target, can
move, and can walk out; the system did not push them out during testing.

So "nobody can get in" is not a statement that survives an operator with
`/tp`. Ordinary walking is still blocked.

**Workaround:** there is no setting or command that brings `/tp` under entry
control. To deal with a specific player, use `/land ban` — a banned player is
pushed out on their next movement — and keep administrative permission
controlled.

> Verified on Folia 26.2, with walking blocked and `/tp` not blocked in the same
> session. This comes from how the server performs teleportation, not from a
> setting. Only `/tp` was checked; other teleportation paths were out of scope.

## Not yet covered

Behaviour not listed in this document is not committed to by it. In particular,
no claim is made here about Bedrock form parity beyond what section 3 states, or
about versions of Folia other than 26.2.