English · [繁體中文](../zh-TW/developer-guide.md) · [简体中文](../zh-CN/developer-guide.md)

# Developer guide

For plugins that want to read ChunkLand state or react to its events. There is
no published artifact to depend on, so this guide starts with how to get the
jar onto your compile classpath at all.

## Building ChunkLand locally

```bash
./scripts/build-acelib.sh          # AceLib 1.3.0 into the cache dir, SHA-256 verified
./gradlew build --no-daemon --console=plain
```

Produces two jars:

| Artifact | Contents |
| --- | --- |
| `chunkland-api/build/libs/chunkland-api-0.1.0.jar` | Domain types, `ChunkLandApi`, the event bus interface. No Bukkit, no SQL, no AceLib |
| `chunkland-plugin/build/libs/chunkland-plugin-0.1.0.jar` | The server plugin. Embeds the api jar, SQLite and SnakeYAML |

`chunkland-api` carries a build guard that fails the build if any external
production dependency is declared on it. If you add one to satisfy your own
build, the guard fails first — that is the rule working, not a bug.

## Depending on it

There is no Maven publication, no JitPack coordinate, and no Gradle plugin
portal entry. The plugin also does **not** register itself in Bukkit's
`ServicesManager`, so `getRegistration(...)` will never find it.

Point at the local jars instead:

```kotlin
dependencies {
    compileOnly(files("chunkland-plugin/build/libs/chunkland-plugin-0.1.0.jar"))
}
```

The plugin jar already embeds `chunkland-api`, so this one entry is enough for
compilation. If you would rather depend on the API module alone, add
`chunkland-api/build/libs/chunkland-api-0.1.0.jar` as well — you will still need
the plugin jar at compile time because `getReadApi()` and `publicEventBus()` are
declared on `ChunkLandPlugin`.

Declare the dependency so ChunkLand loads first:

```yaml
depend: [ChunkLand]
```

Keep it `compileOnly`. The server provides these classes; bundling them creates
two copies of the same types and the classloader picks one of them at random.

## Getting the two entry points

```java
Plugin found = Bukkit.getPluginManager().getPlugin("ChunkLand");
if (!(found instanceof ChunkLandPlugin plugin) || !plugin.isEnabled()) {
    getLogger().warning("ChunkLand not available; land integration inactive.");
    return;
}
```

| Accessor | Nullability | Lifetime |
| --- | --- | --- |
| `plugin.getReadApi()` | Never null. Returns a holder that reads as an empty registry before the first enable and after disable | Safe to cache. Each call returns a lightweight holder over the live snapshot, so you can keep the first one and still observe later changes |
| `plugin.publicEventBus()` | **`null` only before the first enable**. A disable leaves the previous bus in place, so a stale handle is non-null but permanently silent | Acquire it per enable. Re-acquire after every enable and never keep a handle across a disable |

Neither is thread-restricted on the calling side, but see the Folia section
below before you do real work in a listener.

## Reading state

`ChunkLandApi` has six query methods:

| Method | Returns today |
| --- | --- |
| `getLandSnapshot(LandId)` | Live. Empty if no such land |
| `getSubLandSnapshot(SubLandId)` | Live. Empty if no such subland |
| `getOwner(LandId)` | Live. Derived from the land snapshot |
| `getProtectionDepth(LandId)` | Live. `PER_CHUNK_DEPTH` lands report their stored minimum depth; `FULL_HEIGHT` lands report the world minimum |
| `can(UUID, LandId, ProtectionActionType)` | **Always `false`.** Not backed by production data |
| `getRule(LandId, LandRuleType)` | **Always empty.** Not backed by production data |

The last two are the trap. `ChunkLandPlugin.getReadApi()` builds the read API
with a permission-context provider that returns `null` and a rule lookup that
returns `Optional.empty()`. `can()` maps a `null` context to `false` by design,
so it denies everything; `getRule()` has nothing to report. Both are honest
fail-closed answers, not bugs you can work around — if you need a permission
verdict today, ask a player with `/land explain <action>` and parse nothing, or
wait for the wiring to land. Do not build a feature that depends on either
method returning something useful.

The four live methods share one guarantee: each call reads exactly one volatile
snapshot, does no I/O, never loads a chunk, and returns immutable values. There
is no way to observe a half-applied mutation through them.

```java
api.getLandSnapshot(landId).ifPresent(land -> {
    log.info("{} owner={} chunks={}", land.id(), land.ownerRef(), land.chunks().size());
});
```

## Listening to events

`publicEventBus()` gives a synchronous in-process `ChunkLandEventBus`:

```java
ChunkLandEventBus bus = plugin.publicEventBus(); // acquired per enable; non-null once enabled
bus.register(LandCreatePostEvent.class, event -> {
    notifyChannel("claimed " + event.chunkCount() + " chunks as " + event.landId());
});
```

Keep that handle for the current enable only. Every enable builds a new bus, and
a handle carried across a disable is not nulled — it simply stops firing, which
is much harder to notice than an exception.

`LandCreatePostEvent` carries only what the commit produced: `landId()`,
`actorUuid()`, `worldId()`, `chunkCount()` and `priceMinorUnits()`. It has no
`snapshot()` and no land name — read the snapshot through
`api.getLandSnapshot(event.landId())` if you need one, and remember that call
returns empty once the land is gone.

```java
<E> void register(Class<E> eventType, ChunkLandEventListener<? super E> listener);
<E> void unregister(Class<E> eventType, ChunkLandEventListener<? super E> listener);
<E> void publish(E event);   // you do not call this
```

Dispatch is synchronous on the publishing thread. `publish` returns only after
every listener has run.

**Pre events** run before any economy or durable side effect. If one of your
Pre listeners throws, the dispatcher marks the event cancelled and the mutation
produces zero side effects — a throwing Pre listener fails the operation closed.

**Post events** run after the durable commit plus the runtime snapshot publish.
If one throws, the dispatcher catches, logs and isolates it. The committed
mutation still stands; your listener cannot roll it back.

### Thread rules inside a listener

Listeners must return immediately. No blocking, no I/O, no waiting, and no
Bukkit or Paper world access outside the matching Folia thread. Post listeners
in particular run on the async continuation after the commit, so touching world
state from one is wrong even when it looks like it would work — hop to the right
scheduler through AceLib's `SafeExecutor` instead.

If you need the Bukkit event views rather than the API channel, each public
event also has a view class under `com.smile.chunkland.event.bukkit`, named
after the event with the suffix changed — `LandCreatePostEvent` becomes
`LandCreatePostBukkitEvent`. Prefer the API bus; the Bukkit views exist for
plugins that cannot take a compile-time dependency.

One caveat: `RuleChangedEvent` is declared on both channels but is never
actually fired, because land rules have no writable interface yet. Do not build
a listener around it.

## What is not available yet

- **No supported way to reach the write facade.**
  `com.smile.chunkland.api.mutation.ChunkLandMutations` is in your classpath
  and declares `submit(MutationRequest)`, and the plugin contains an
  implementation of it — but `ChunkLandPlugin` exposes no accessor for it
  (only `getReadApi()` and `publicEventBus()`), so there is no supported entry
  point. Use the commands; there is no supported programmatic claim.
- **No offline name guarantee for you.** The command layer resolves player
  references as: raw UUID first, then an exact case-sensitive online name, then
  an offline-player lookup that runs on an async executor and fails closed on
  timeout. That last lookup only answers for players the server already knows.
  `ChunkLandApi` takes UUIDs throughout, so resolve names yourself and expect
  empty for names that never joined.
- **No reload notification.** `ConfigService.reload()` exists and bumps the
  global and per-world policy epochs, but no command triggers it. Restart is the
  only path today.

## Related pages

[API reference](reference/api.md) ·
[User guide](user-guide.md) ·
[Server guide](server-guide.md) ·
[Limitations](limitations.md)