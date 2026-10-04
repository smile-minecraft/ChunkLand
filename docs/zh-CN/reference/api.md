[English](../../en/reference/api.md) · [繁體中文](../../zh-TW/reference/api.md) · [参考索引](README.md)

← [文档索引](../README.md) · [项目说明](../../../README.zh-CN.md) · [命令参考](commands.md) · [权限参考](permissions.md) · [配置参考](configuration.md) · [API 参考](api.md)

# API 参考

这份文件写给要**在自己的插件里调用 ChunkLand** 的开发者。如果你要的是在服务器上圈地、设权限，请看[玩家指南](../user-guide.md)。

## 先讲清楚现在能拿到什么

项目还没有发布任何版本，**没有公开构件、没有 Maven 或 JitPack 坐标，也没有注册到 Bukkit 的 `ServicesManager`**。你只能挂自己从源码构建出来的 jar。

这代表两件事：拿不到能靠相依管理解析的构件，也没有办法用服务注册找到实例。

从源码构建：

```bash
./scripts/build-acelib.sh          # 取得编译用的 AceLib 1.3.0
./gradlew build --no-daemon --console=plain
```

产物：

| 产物 | 路径 |
| --- | --- |
| 自带相依的插件 jar | `chunkland-plugin/build/libs/chunkland-plugin-0.1.0.jar` |
| API 模块 jar | `chunkland-api/build/libs/chunkland-api-0.1.0.jar` |

插件 jar 是自带相依的：API 模块、SQLite、SnakeYAML 都包在里面，服务器的 `plugins/` 不需要再放其他 jar。paper-api 与 AceLib 属于服务器提供，不会被打包进去。

在自己的 Gradle 项目里挂本机档案：

```kotlin
dependencies {
    compileOnly(files("chunkland-plugin/build/libs/chunkland-plugin-0.1.0.jar"))
}
```

## 取得实例

```java
ChunkLandPlugin plugin = (ChunkLandPlugin) Bukkit.getPluginManager().getPlugin("ChunkLand");
if (plugin == null || !plugin.isEnabled()) return;
```

`plugin.yml` 宣告的插件名称是 `ChunkLand`。请在 ChunkLand 完成启用之后才取用 API。

两个取用点，都在 `com.smile.chunkland.ChunkLandPlugin` 上：

| 方法 | 返回 | 生命周期 |
| --- | --- | --- |
| `plugin.getReadApi()` | `ChunkLandApi` | **不会是 null**。启用前与停用后读到的都是空 |
| `plugin.publicEventBus()` | `ChunkLandEventBus` | **只有首次启用前才是 `null`**；停用后仍会回传上一次启用的旧 bus，它不报错，但从此不再收到任何事件 |

`getReadApi()` 每次调用都回传一个轻量持有者，内部指向同一份即时快照，所以之后的设定变动不需要重建就能看到。这个持有者会记住它所属的启用世代：停用时该世代会被停用，所以你事先缓存起来的持有者在停用之后仍然只回空；下一次启用会开新的世代，不会让旧的持有者复活。

`publicEventBus()` 每次启用都会新建一条 bus，**务必在每次启用后重新获取并注册 listener**，不要把 handle 带过停用（旧 bus 不会抛异常，只会安静地不再触发）。null 检查只需要覆盖首次启用前。

## 读取 API

`ChunkLandApi` 一共六个方法。

```java
Optional<LandSnapshot> getLandSnapshot(LandId landId);
Optional<SubLandSnapshot> getSubLandSnapshot(SubLandId subLandId);
Optional<OwnerRef> getOwner(LandId landId);
boolean can(UUID actor, LandId landId, ProtectionActionType action);
Optional<PermissionState> getRule(LandId landId, LandRuleType rule);
Optional<Integer> getProtectionDepth(LandId landId);
```

| 方法 | 是否接上正式数据 | 说明 |
| --- | --- | --- |
| `getLandSnapshot(LandId)` | 是 | 领地不存在时回空 |
| `getSubLandSnapshot(SubLandId)` | 是 | 在快照的子领地索引里查找 |
| `getOwner(LandId)` | 是 | 由领地快照推出 |
| `getProtectionDepth(LandId)` | 是 | 回有效保护的最下方块 Y。读取不会改写已储存的深度 |
| `can(UUID, LandId, ProtectionActionType)` | **否** | 固定回 `false` |
| `getRule(LandId, LandRuleType)` | **否** | 固定回 `Optional.empty()` |

`LandId` 与 `SubLandId` 都是包着 `UUID` 的 record，直接 `new LandId(uuid)` 即可。`OwnerRef` 是 sealed 介面，`OwnerRef.player(uuid)` 与 `OwnerRef.server()` 是它的两个工厂，稳定键分别形如 `PLAYER:<uuid>` 与 `SERVER`。

要拿领地编号，来源是 `/land inspect` 的输出。

### 不要把 `can()` 当成保护裁决用

这是目前最重要的一件事。`getReadApi()` 建立读取 API 时，注入的权限脉络来源固定回 `null`，于是 `can()` 每次都走 fail-closed 分支回 `false`；`getRule()` 的规则查找来源同理，固定回空的 `Optional`。

这两个方法**不是**领地保护引擎的查询介面。保护引擎在内部走自己的判定路径，两条路径不共用。拿 `can()` 去做 UI 显示、指令检查或任何「能不能做」的裁决，结果会是恒定的「不行」，而且看不出原因。

**现在需要权限裁决的话，请让站在领地里的玩家跑 `/land explain <action>`**，那一条读的是真正的执行路径。

`ChunkLandApi` 介面本身也注明了 `can`、`getRule`、`getProtectionDepth` 的签章仍是暂定的，最终形状会跟着权限解析器与读取 API 的实作一起定案。

### 调用约定

| 约定 | 内容 |
| --- | --- |
| 线程 | 任何线程都可以调用 |
| I/O | 不做 I/O，永远不会加载区块 |
| 快照 | 每次调用读同一份不可变快照，不会看到套用一半的变更 |
| 返回类型 | 不返回可变的领域集合，全部是不可变快照或值物件 |

未知 id 回空而不是抛例外。参数为 null 会抛 `NullPointerException`。

## 事件总线

`ChunkLandEventBus` 是**行程内同步**发送，不是 Bukkit 事件。

```java
<E> void register(Class<E> eventType, ChunkLandEventListener<? super E> listener);
<E> void unregister(Class<E> eventType, ChunkLandEventListener<? super E> listener);
<E> void publish(E event);   // 由 ChunkLand 调用，不是你
```

| 方法 | 说明 |
| --- | --- |
| `register(Class<E>, listener)` | 观察之后所有该类型的事件 |
| `unregister(Class<E>, listener)` | 停止观察 |
| `publish(E)` | 在调用线程上同步送给所有符合的监听者 |

`publish` 会等所有监听者都跑完才返回。发送端在持久化副作用之前发 Pre 事件，在持久化提交加上即时快照发布之后发 Post 事件。

### 监听者的线程限制

监听者**不得**阻塞、不得做 I/O、不得等待，也不得在对应的 Folia 线程之外存取世界。监听器要实作 `ChunkLandEventListener`。

### 失败怎么处理

| 监听者抛出例外 | 结果 |
| --- | --- |
| Pre 事件 | 整个变更 fail closed：发送端把该次 Pre 标记为已取消，变更不产生任何副作用 |
| Post 事件 | 被捕捉、记录并隔离；已经提交的变更维持成立 |

### 事件清单

五个 Pre 事件可以取消，其余都是通知用途。

| 事件 | 可取消 | 实际发送 |
| --- | --- | --- |
| `LandCreatePreEvent` / `LandCreatePostEvent` | 只有 `Pre` | 是 |
| `LandDeletePreEvent` / `LandDeletePostEvent` | 只有 `Pre` | 是 |
| `LandChunkAddPreEvent` / `LandChunkAddPostEvent` | 只有 `Pre` | 是 |
| `LandChunkRemovePreEvent` / `LandChunkRemovePostEvent` | 只有 `Pre` | 是 |
| `SubLandPreEvent` / `SubLandPostEvent` | 只有 `Pre` | 是 |
| `LandEnterEvent` | 否 | 是 |
| `LandLeaveEvent` | 否 | 是 |
| `SubLandEnterEvent` | 否 | 是 |
| `SubLandLeaveEvent` | 否 | 是 |
| `PermissionChangedEvent` | 否 | 是 |
| `RuleChangedEvent` | 否 | **从不发送** |

`RuleChangedEvent` 虽然型别宣告了、两条通道也都接线完成，但**实际上从不发送**：领地规则目前没有任何可写入的介面，没有可以触发它的持久化写入点。为它注册监听器不会收到任何事件。

事件类型在 `com.smile.chunkland.api.event`，可取消的事件实作 `ChunkLandCancellable`。`NoopChunkLandEventBus.instance()` 是惰性实作，写自己的测试时可以用。

### Bukkit 视图

每个公开事件在 `com.smile.chunkland.event.bukkit` 下都有一个视图类别，命名方式是把后缀换掉：事件叫 `LandCreatePostEvent`，对应的视图叫 `LandCreatePostBukkitEvent`；`PermissionChangedEvent` 对应 `PermissionChangedBukkitEvent`。它们都继承 `ChunkLandBukkitEvent`。

优先用 API 总线。Bukkit 视图是给拿不到编译期相依的插件用的。

| API 事件 | Bukkit 视图 | 实际发送 |
| --- | --- | --- |
| `LandCreatePreEvent` / `LandCreatePostEvent` | `LandCreatePreBukkitEvent` / `LandCreatePostBukkitEvent` | 是 |
| `LandDeletePreEvent` / `LandDeletePostEvent` | `LandDeletePreBukkitEvent` / `LandDeletePostBukkitEvent` | 是 |
| `LandChunkAddPreEvent` / `LandChunkAddPostEvent` | `LandChunkAddPreBukkitEvent` / `LandChunkAddPostBukkitEvent` | 是 |
| `LandChunkRemovePreEvent` / `LandChunkRemovePostEvent` | `LandChunkRemovePreBukkitEvent` / `LandChunkRemovePostBukkitEvent` | 是 |
| `SubLandPreEvent` / `SubLandPostEvent` | `SubLandPreBukkitEvent` / `SubLandPostBukkitEvent` | 是 |
| `LandEnterEvent` | `LandEnterBukkitEvent` | 是 |
| `LandLeaveEvent` | `LandLeaveBukkitEvent` | 是 |
| `SubLandEnterEvent` | `SubLandEnterBukkitEvent` | 是 |
| `SubLandLeaveEvent` | `SubLandLeaveBukkitEvent` | 是 |
| `PermissionChangedEvent` | `PermissionChangedBukkitEvent` | 是 |
| `RuleChangedEvent` | `RuleChangedBukkitEvent` | **从不发送，见上** |

## 领域类型

| 套件 | 内容 |
| --- | --- |
| `api.land` | `LandId`、`SubLandId`、`LandSnapshot`、`SubLandSnapshot`、`OwnerRef`、`LandName`、`LandNameKey`、`ChunkKey`、`Cuboid`、`SubLandTopologyValidator`、`LandNameUniqueness` |
| `api.permission` | `ProtectionActionType`、`Permission`、`PermissionState`、`PermissionDecision`、`PermissionResolver`、`PermissionContext`、`PermissionSubject`、`PermissionBinding`、`DecisionSource`、`SubjectPermissionTier` |
| `api.rule` | `LandRuleType`、`LandRule` |
| `api.geometry` | `ChunkGeometry`、`BoundaryExtractor`、`BoundarySegment`、`Direction` |
| `api.money` | `Money`、`Currency`、`PricingTable`、`PricingTier`、`CostBasisCalculator`、`CostBasisAllocation`、`ChunkCoordinate` |
| `api.limit` | `LimitType`、`LimitResult`、`LimitSource`、`ExternalLimitProvider` |
| `api.mutation` | `ChunkLandMutations`、`MutationRequest`、`MutationKind`、`MutationResult`、`MutationOutcome` |
| `api.history` | `HistoryQuery`、`HistoryResult`、`HistoryEntry`、`WorldHistoryProvider` |

`ProtectionActionType` 有 40 个常量，每个都声明自己从哪个 `DecisionSource` 解析。`SUBJECT_PERMISSION` 覆盖 22 个——`BLOCK_BREAK`、`BLOCK_PLACE`、`CONTAINER_OPEN`、`WORKSTATION_USE`、`DOOR_USE`、`BUTTON_USE`、`LEVER_USE`、`REDSTONE_USE`、`BUCKET_USE`、`ENTRY`、`VEHICLE_USE`、`ENTITY_INTERACT`、`ENTITY_DAMAGE`、`ITEM_FRAME`、`ARMOR_STAND`、`HANGING_ENTITY`、`FARMLAND_TRAMPLE`，加上管理组 `MANAGE_MEMBER`、`MANAGE_PERMISSION`、`MANAGE_SUBLAND`、`EXPAND_LAND` 与 `DELETE_LAND`，这一组有主人保障，主人永远放行。`LAND_RULE` 覆盖 18 个：`PLAYER_DAMAGE_PLAYER`、`PISTON_MOVE`、`FLUID_FLOW`、`HOPPER_TRANSFER`、`FIRE_SPREAD`、`FIRE_BURN`、`EXPLOSION_TERRAIN`、`EXPLOSION_ENTITY`、`MOB_GRIEFING`、`HOSTILE_MOB_SPAWN`、`PASSIVE_MOB_SPAWN`，加上跨边界那组 `BLOCK_MOVE_IN`、`BLOCK_MOVE_OUT`、`FLUID_ENTER`、`FLUID_EXIT`、`ITEM_TRANSFER_IN`、`ITEM_TRANSFER_OUT`、`DISPENSER_CROSS_BOUNDARY`。

`LandRuleType` 有 11 个值：`PVP`、`EXPLOSION_TERRAIN`、`EXPLOSION_ENTITY`、`FIRE_SPREAD`、`FIRE_BURN`、`MOB_GRIEFING`、`FLUID_FLOW`、`PISTON`、`HOPPER_TRANSFER`、`HOSTILE_MOB_SPAWN`、`PASSIVE_MOB_SPAWN`。它们回答的是「这个机制能不能在这里发生」，不是「这个玩家能不能动作」。可设领地默认的动作清单在[权限参考](permissions.md#动作标识字)。

## 模组边界

`chunkland-api` 是纯领域模组，**不得宣告任何外部的正式相依**。SQLite、Bukkit/Paper、AceLib 这些实作层的相依全部属于 `chunkland-plugin`。

这个限制不是靠审查维持的，而是由构建脚本机械检查：宣告了禁止的相依会让 `check`（因而也让 `build`）直接失败，而且这个检查同时挂在 `jar` 上。检查只看正式产物的相依设定，测试专用相依不算违规。

## 这里没有的

- **没有支援的写入取用点。** `com.smile.chunkland.api.mutation.ChunkLandMutations` 这个类型确实存在，只有一个方法 `submit(MutationRequest)`，外挂模组里也有实作（`runtime.mutation.MutationServiceAdapter`）。缺的是取用点：`ChunkLandPlugin` 只公开 `getReadApi()` 与 `publicEventBus()`，没有 `mutations()` 方法，`ServicesManager` 里也没有注册。所以契约在你的 classpath 上，却没有支援的入口可以呼叫它，没有支援的程序化圈地路径。
- 没有服务注册。`getRegistration(ChunkLandApi.class)` 之类的调用按设计回 `null`。
- 没有已发布的构件可以相依。

## 相关页面

[开发者指南](../developer-guide.md) ·
[配置参考](configuration.md) ·
[权限参考](permissions.md) ·
[已知限制](../limitations.md)