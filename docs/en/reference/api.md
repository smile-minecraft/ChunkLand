English · [繁體中文](../../zh-TW/reference/api.md) · [简体中文](../../zh-CN/reference/api.md)

# API reference

For plugins that read ChunkLand state or subscribe to its events. See the
[developer guide](../developer-guide.md) for how to get the API onto your compile
classpath — the published artifact is on JitPack, and there is no
`ServicesManager` registration.

## Entry points

Both are public methods on `com.smile.chunkland.ChunkLandPlugin`. That class is
in the plugin jar, not in the JitPack API artifact — the samples below also need
`compileOnly(files("libs/chunkland-plugin-0.1.0.jar"))` alongside the API
dependency, see the [developer guide](../developer-guide.md#depending-on-it).

| Accessor | Returns | Nullability |
| --- | --- | --- |
| `getReadApi()` | `ChunkLandApi` | Never null. Reads as an empty registry before the first enable and after disable |
| `publicEventBus()` | `ChunkLandEventBus` | **`null` only before the first enable**. A disable leaves the previous bus in place, so a stale handle is non-null but permanently silent |
| `getMutations()` | `ChunkLandMutations` | **`null` while the mutation flows are not assembled**. After a disable the previous handle keeps rejecting instead of running |

```java
ChunkLandPlugin plugin = (ChunkLandPlugin) Bukkit.getPluginManager().getPlugin("ChunkLand");
if (plugin == null || !plugin.isEnabled()) return;

ChunkLandApi api = plugin.getReadApi();           // cache it; it tracks later changes
ChunkLandEventBus bus = plugin.publicEventBus();  // acquire per enable; null only before the first enable
ChunkLandMutations mutations = plugin.getMutations(); // null-check; null while flows are not assembled
```

The read API holder is deliberately long-lived: each call reads the current
volatile snapshot, so a holder you cached at enable time still sees land created
afterwards. Cleanup deactivates that enable generation, which is what makes a
cached handle keep returning empty after a disable rather than reviving on the
next enable.

The event bus is the opposite: each enable builds a new bus, and the field is
only cleared by the plugin being unloaded, not by a disable. So a handle you kept
across a disable is still non-null, but nothing publishes to it any more and it
never throws — your listeners just go quiet. Register listeners on a handle you
acquired in the current enable, and null-check only if you can be called before
the first enable.

## ChunkLandApi

```java
Optional<LandSnapshot> getLandSnapshot(LandId landId);
Optional<SubLandSnapshot> getSubLandSnapshot(SubLandId subLandId);
Optional<OwnerRef> getOwner(LandId landId);
boolean can(UUID actor, LandId landId, ProtectionActionType action);
Optional<PermissionState> getRule(LandId landId, LandRuleType rule);
Optional<Integer> getProtectionDepth(LandId landId);
boolean isReady();
Optional<LandId> getLandAt(UUID worldId, int chunkX, int chunkZ);
PermissionDecision decideAtBlock(UUID actor, UUID worldId,
                                 int blockX, int blockY, int blockZ,
                                 ProtectionActionType action);
```

| Method | Wired to production data | Notes |
| --- | --- | --- |
| `getLandSnapshot` | Yes | Empty for an unknown land |
| `getSubLandSnapshot` | Yes | Searches the subland indexes of the snapshot |
| `getOwner` | Yes | Derived from the land snapshot |
| `getProtectionDepth` | Yes | `PER_CHUNK_DEPTH` lands report their stored minimum depth; `FULL_HEIGHT` lands report the world minimum. Reads never rewrite stored depths |
| `can` | Yes | Land-level decision through the same permission context the protection engine uses. No block position, so no subland applies. `false` while `isReady()` is `false` or the land is unknown |
| `getRule` | **No** | Always `Optional.empty()` |
| `isReady` | Yes | `true` only while the current enable is active and the land index has finished loading at startup. Until then an empty `getLandAt` answer means "unknown", not wilderness |
| `getLandAt` | Yes | Land owning a chunk, from the in-memory index. Empty means wilderness only while `isReady()` is `true` |
| `decideAtBlock` | Yes | The full protection decision ChunkLand's own listeners enforce at that block. See below |

`isReady`, `getLandAt` and `decideAtBlock` are default methods. If you implement
`ChunkLandApi` yourself (for example as a test double), the defaults answer
`false`, empty and `DENY`.

### Making a protection decision at a block

`decideAtBlock` returns the same `PermissionDecision` ChunkLand's own listeners
act on: admin bypass, the owner guarantee, the subland covering the block,
member and group bindings, defaults and land rules all apply. The decision is
taken against the same snapshot the read API observed.

| Situation | Outcome |
| --- | --- |
| Block is in wilderness (index ready) | `ALLOW` — vanilla applies |
| Index not confirmed yet (`isReady()` is `false`) | `DENY` |
| Plugin disabled (cached holder) | `DENY` |
| Lookup fails internally | `DENY` — it does not throw |
| `actor`, `worldId` or `action` is `null` | Throws `NullPointerException` |

For actions whose `DecisionSource` is `LAND_RULE`, the actor only matters for
admin bypass.

```java
ChunkLandApi api = plugin.getReadApi();
if (!api.isReady()) {
    return; // land index still loading: treat as unknown, not wilderness
}
boolean allowed = api.decideAtBlock(
        player.getUniqueId(), world.getUID(), x, y, z,
        ProtectionActionType.BLOCK_PLACE).outcome() == PermissionState.ALLOW;
```

`decideAtBlock` already answers `DENY` while the index is not ready, so the
`isReady()` check is there for your own handling of that window — for example,
to wait rather than show a "denied" message.

Use `can` when you have a land but no block position: it gives the land-level
answer and ignores sublands. Use `decideAtBlock` whenever a position is known.

### Why `getRule` always returns empty

`getReadApi()` builds the read API with a rule lookup that returns
`Optional.empty()`, so `getRule()` has nothing to report — even though land
rules are adjustable in game since `/land rule` (see
[Limitations](../limitations.md)). That is the fail-closed answer working as
designed, not a defect you can work around from outside. The rule still takes
effect in `decideAtBlock` for `LAND_RULE` actions.

For diagnosis, ask a player standing in the land to run `/land explain <action>`,
which reads the real enforcement path.

### Thread and I/O contract

Every method:

- is callable from any thread;
- reads exactly one volatile snapshot per call, so you never observe a
  half-applied mutation;
- performs no I/O, never loads a chunk, never blocks;
- returns only immutable snapshots and value objects.

Unknown ids return empty rather than throwing. A null argument throws
`NullPointerException`.

## Events

`ChunkLandEventBus`:

```java
<E> void register(Class<E> eventType, ChunkLandEventListener<? super E> listener);
<E> void unregister(Class<E> eventType, ChunkLandEventListener<? super E> listener);
<E> void publish(E event);   // called by ChunkLand, not by you
```

Dispatch is synchronous on the publishing thread; `publish` returns only after
every listener has run.

| Event kind | When | If a listener throws |
| --- | --- | --- |
| Pre (`*PreEvent`) | Before any economy or durable side effect | The event is marked cancelled and the mutation produces zero side effects |
| Post (`*PostEvent`) | After the durable commit and the runtime snapshot publish | Caught, logged, isolated. The committed mutation stands |

Listener contract: return immediately. No blocking, no I/O, no waiting, and no
Bukkit or Paper world access outside the matching Folia thread. Post listeners
run on the async continuation after the commit, so touching world state from one
is wrong even when it appears to work — hop to the right scheduler through
AceLib's `SafeExecutor`.

Event types live in `com.smile.chunkland.api.event`. There are 16 of them:

| Event | Cancellable | Fired today |
| --- | --- | --- |
| `LandCreatePreEvent` | Yes | Yes |
| `LandCreatePostEvent` | No | Yes |
| `LandDeletePreEvent` | Yes | Yes |
| `LandDeletePostEvent` | No | Yes |
| `LandChunkAddPreEvent` | Yes | Yes |
| `LandChunkAddPostEvent` | No | Yes |
| `LandChunkRemovePreEvent` | Yes | Yes |
| `LandChunkRemovePostEvent` | No | Yes |
| `SubLandPreEvent` | Yes | Yes |
| `SubLandPostEvent` | No | Yes |
| `LandEnterEvent` | No | Yes |
| `LandLeaveEvent` | No | Yes |
| `SubLandEnterEvent` | No | Yes |
| `SubLandLeaveEvent` | No | Yes |
| `PermissionChangedEvent` | No | Yes |
| `RuleChangedEvent` | No | **Yes — fired by durable per-land rule writes** |

A listener registered for `RuleChangedEvent` runs when a `/land rule` write (or
the equivalent typed `RULE_CHANGE`) commits. Cancellable events implement
`ChunkLandCancellable`.

`NoopChunkLandEventBus.instance()` is the inert implementation — useful in your
own tests.

### Bukkit views

Each public event also has a Bukkit view class under
`com.smile.chunkland.event.bukkit`. The class name is the event name with the
suffix changed: `LandCreatePostEvent` has the view `LandCreatePostBukkitEvent`,
`PermissionChangedEvent` has `PermissionChangedBukkitEvent`. All of them extend
`ChunkLandBukkitEvent`.

Prefer the API bus. The Bukkit views exist for plugins that cannot take a
compile-time dependency on `chunkland-api`.

| API event | Bukkit view | Fired |
| --- | --- | --- |
| `LandCreatePreEvent` / `LandCreatePostEvent` | `LandCreatePreBukkitEvent` / `LandCreatePostBukkitEvent` | Yes |
| `LandDeletePreEvent` / `LandDeletePostEvent` | `LandDeletePreBukkitEvent` / `LandDeletePostBukkitEvent` | Yes |
| `LandChunkAddPreEvent` / `LandChunkAddPostEvent` | `LandChunkAddPreBukkitEvent` / `LandChunkAddPostBukkitEvent` | Yes |
| `LandChunkRemovePreEvent` / `LandChunkRemovePostEvent` | `LandChunkRemovePreBukkitEvent` / `LandChunkRemovePostBukkitEvent` | Yes |
| `SubLandPreEvent` / `SubLandPostEvent` | `SubLandPreBukkitEvent` / `SubLandPostBukkitEvent` | Yes |
| `LandEnterEvent` | `LandEnterBukkitEvent` | Yes |
| `LandLeaveEvent` | `LandLeaveBukkitEvent` | Yes |
| `SubLandEnterEvent` | `SubLandEnterBukkitEvent` | Yes |
| `SubLandLeaveEvent` | `SubLandLeaveBukkitEvent` | Yes |
| `PermissionChangedEvent` | `PermissionChangedBukkitEvent` | Yes |
| `RuleChangedEvent` | `RuleChangedBukkitEvent` | **Yes — see above** |

## Domain types

| Package | What it holds |
| --- | --- |
| `api.land` | `LandId`, `SubLandId`, `LandSnapshot`, `SubLandSnapshot`, `OwnerRef`, `LandName`, `LandNameKey`, `ChunkKey`, `Cuboid`, `SubLandTopologyValidator` |
| `api.land` | `LandNameUniqueness` — the naming rule the commands enforce |
| `api.permission` | `ProtectionActionType`, `Permission`, `PermissionState`, `PermissionDecision`, `PermissionResolver`, `PermissionContext`, `PermissionSubject`, `PermissionBinding`, `DecisionSource`, `SubjectPermissionTier` |
| `api.rule` | `LandRuleType`, `LandRule` |
| `api.geometry` | `ChunkGeometry`, `BoundaryExtractor`, `BoundarySegment`, `Direction` |
| `api.money` | `Money`, `Currency`, `PricingTable`, `PricingTier`, `CostBasisCalculator`, `CostBasisAllocation`, `ChunkCoordinate` |
| `api.limit` | `LimitType`, `LimitResult`, `LimitSource`, `ExternalLimitProvider` |
| `api.mutation` | `ChunkLandMutations`, `MutationRequest`, `MutationKind`, `MutationResult`, `MutationOutcome`, `TypedMutationRequest`, `TypedMutationResult`, `ExpectedVersion` |
| `api.history` | `HistoryQuery`, `HistoryResult`, `HistoryEntry`, `WorldHistoryProvider` |

`ProtectionActionType` carries 41 constants. Each declares the `DecisionSource`
it resolves from. `SUBJECT_PERMISSION` covers 23 — the actor actions
`BLOCK_BREAK`, `BLOCK_PLACE`, `CONTAINER_OPEN`, `WORKSTATION_USE`, `DOOR_USE`,
`BUTTON_USE`, `LEVER_USE`, `REDSTONE_USE`, `BUCKET_USE`, `ENTRY`,
`VEHICLE_USE`, `ENTITY_INTERACT`, `ENTITY_DAMAGE`, `ITEM_FRAME`,
`ARMOR_STAND`, `HANGING_ENTITY`, `FARMLAND_TRAMPLE`, `FIRE_IGNITE`, plus the
management set
`MANAGE_MEMBER`, `MANAGE_PERMISSION`, `MANAGE_SUBLAND`, `EXPAND_LAND` and
`DELETE_LAND`, where the owner guarantee applies and the owner always passes.
`LAND_RULE` covers 18: `PLAYER_DAMAGE_PLAYER`, `PISTON_MOVE`, `FLUID_FLOW`,
`HOPPER_TRANSFER`, `FIRE_SPREAD`, `FIRE_BURN`, `EXPLOSION_TERRAIN`,
`EXPLOSION_ENTITY`, `MOB_GRIEFING`, `HOSTILE_MOB_SPAWN`, `PASSIVE_MOB_SPAWN` and
the cross-boundary set `BLOCK_MOVE_IN`, `BLOCK_MOVE_OUT`, `FLUID_ENTER`,
`FLUID_EXIT`, `ITEM_TRANSFER_IN`, `ITEM_TRANSFER_OUT`,
`DISPENSER_CROSS_BOUNDARY`.

`LandRuleType` has 11 values: `PVP`, `EXPLOSION_TERRAIN`, `EXPLOSION_ENTITY`,
`FIRE_SPREAD`, `FIRE_BURN`, `MOB_GRIEFING`, `FLUID_FLOW`, `PISTON`,
`HOPPER_TRANSFER`, `HOSTILE_MOB_SPAWN`, `PASSIVE_MOB_SPAWN`. They answer "can
this mechanic happen here?", not "may this player act?".

## Module boundary

`chunkland-api` is pure domain. A build guard fails the build if it declares any
external production dependency — no Bukkit, no SQL, no AceLib, nothing. SQLite
and the persistence layer live in `chunkland-plugin`.

## Writing through `getMutations()`

`plugin.getMutations()` returns the production typed mutation entry: the same
sagas the chat handlers use — same validation, quotas, reservations, pricing,
ledger, economy, atomic commits, public events and recovery. There is no second
write path. The legacy `submit(MutationRequest)` envelope stays unsupported;
only typed requests execute.

Every typed request carries the same five envelope fields, all enforced, never
accepted-but-dropped:

| Field | Meaning |
| --- | --- |
| `actorUuid` | The operator performing the mutation |
| `targetOwner` | The owner of the targeted land: the future owner for creates, the observed owner otherwise |
| `landId` | The targeted land (`null` only for creates, which have nothing to address yet) |
| `expectedVersion` | The optimistic-concurrency expectation (`null` only for creates) |
| `operationId` | The idempotency key of this operation |

The contract around them:

- **Authorisation.** A player land requires the actor to be the durable owner;
  a Server land requires the steward grant. On top of that the shared
  management gate applies, exactly as the matching chat handler checks it. That
  makes this entry stricter than chat for delegated managers: someone holding
  only a binding passes the chat gate but is not the owner, so the typed entry
  rejects them.
- **Versions.** The expectation is compared against the live snapshot before
  any side effect. A stale expectation rejects with
  `mutation.stale-expected-version` and writes nothing — no ledger row, no
  domain write, no event.
- **Idempotency.** The operation id travels end to end into the ledger. A
  resubmitted id returns the recorded result — success stays success, failure
  stays failure — without charging, refunding or publishing twice. The same id
  with different content rejects with `mutation.operation-id-mismatch` and runs
  nothing: after a land changes hands, resubmitting with the old owner reads as
  a content mismatch, so confirm the current owner first. A different id with
  the same content is an independent operation and runs the full flow again.
- **Cancellation.** The returned stage is a caller-side handle only: cancelling
  it means this caller stops waiting. Cancellation is not propagated to the
  pipeline, so it never reverts effects that already landed. An operation that
  already took effect stays in effect: reconcile by operation id instead of
  retrying blindly.
- **Lifecycle.** After the handle is closed, submissions complete with
  `FAILED / coordinator.rejected` and create no new side effects. The facade
  never touches Bukkit state, so an offline actor changes nothing except the
  steward and bypass lookups, which resolve to `false` when they cannot prove
  the grant.

One typed kind is refused on purpose: subland depth extension answers
`subland.confirm_depth`, because the live index refreshes on the next
confirmation — run the create or update confirmation again instead of
resubmitting.

## Not available

- No service registration. `getRegistration(ChunkLandApi.class)` and friends
  return `null` by design.

## Related pages

[Developer guide](../developer-guide.md) ·
[Configuration reference](configuration.md) ·
[Limitations](../limitations.md)