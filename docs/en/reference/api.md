English · [繁體中文](../../zh-TW/reference/api.md) · [简体中文](../../zh-CN/reference/api.md)

# API reference

For plugins that read ChunkLand state or subscribe to its events. See the
[developer guide](../developer-guide.md) for how to get the jar onto your compile
classpath — there is no Maven coordinate and no `ServicesManager` registration.

## Entry points

Both are public methods on `com.smile.chunkland.ChunkLandPlugin`.

| Accessor | Returns | Nullability |
| --- | --- | --- |
| `getReadApi()` | `ChunkLandApi` | Never null. Reads as an empty registry before the first enable and after disable |
| `publicEventBus()` | `ChunkLandEventBus` | **`null` only before the first enable**. A disable leaves the previous bus in place, so a stale handle is non-null but permanently silent |

```java
ChunkLandPlugin plugin = (ChunkLandPlugin) Bukkit.getPluginManager().getPlugin("ChunkLand");
if (plugin == null || !plugin.isEnabled()) return;

ChunkLandApi api = plugin.getReadApi();           // cache it; it tracks later changes
ChunkLandEventBus bus = plugin.publicEventBus();  // acquire per enable; null only before the first enable
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
```

| Method | Wired to production data | Notes |
| --- | --- | --- |
| `getLandSnapshot` | Yes | Empty for an unknown land |
| `getSubLandSnapshot` | Yes | Searches the subland indexes of the snapshot |
| `getOwner` | Yes | Derived from the land snapshot |
| `getProtectionDepth` | Yes | `PER_CHUNK_DEPTH` lands report their stored minimum depth; `FULL_HEIGHT` lands report the world minimum. Reads never rewrite stored depths |
| `can` | **No** | Always `false` |
| `getRule` | **No** | Always `Optional.empty()` |

### Why `can` and `getRule` always fail

`getReadApi()` builds the read API with a permission-context provider that
returns `null` and a rule lookup that returns `Optional.empty()`. `can()` treats
a `null` context as "no permission" and returns `false`; `getRule()` has nothing
to report and returns empty. Both are the fail-closed answer working as designed,
not a defect you can work around from outside.

If you need a permission verdict today, do not read it from these methods. For
diagnosis, ask a player standing in the land to run `/land explain <action>`,
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
| `RuleChangedEvent` | No | **Never fired** |

`RuleChangedEvent` is declared and wired on both channels, but nothing
constructs it: there is no durable per-land rule writer to fire it from,
because land rules have no adjustable interface yet. A listener registered for
it will simply never run. Cancellable events implement `ChunkLandCancellable`.

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
| `RuleChangedEvent` | `RuleChangedBukkitEvent` | **Never fired — see above** |

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
| `api.mutation` | `ChunkLandMutations`, `MutationRequest`, `MutationKind`, `MutationResult`, `MutationOutcome` |
| `api.history` | `HistoryQuery`, `HistoryResult`, `HistoryEntry`, `WorldHistoryProvider` |

`ProtectionActionType` carries 40 constants. Each declares the `DecisionSource`
it resolves from. `SUBJECT_PERMISSION` covers 22 — the actor actions
`BLOCK_BREAK`, `BLOCK_PLACE`, `CONTAINER_OPEN`, `WORKSTATION_USE`, `DOOR_USE`,
`BUTTON_USE`, `LEVER_USE`, `REDSTONE_USE`, `BUCKET_USE`, `ENTRY`,
`VEHICLE_USE`, `ENTITY_INTERACT`, `ENTITY_DAMAGE`, `ITEM_FRAME`,
`ARMOR_STAND`, `HANGING_ENTITY`, `FARMLAND_TRAMPLE`, plus the management set
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

## Not available

- **No supported way to reach the write facade.** The type
  `com.smile.chunkland.api.mutation.ChunkLandMutations` does exist, with one
  method `submit(MutationRequest)`, and the plugin ships an implementation of
  it (`runtime.mutation.MutationServiceAdapter`). What is missing is the
  accessor: `ChunkLandPlugin` exposes only `getReadApi()` and `publicEventBus()`,
  there is no `mutations()` method, and nothing registers the facade in
  `ServicesManager`. So the contract is in your classpath and there is no
  supported entry point to call it through. There is no supported programmatic
  claim.
- No service registration. `getRegistration(ChunkLandApi.class)` and friends
  return `null` by design.
- No published artifact to depend on.

## Related pages

[Developer guide](../developer-guide.md) ·
[Configuration reference](configuration.md) ·
[Limitations](../limitations.md)