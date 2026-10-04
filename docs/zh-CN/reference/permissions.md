[English](../../en/reference/permissions.md) · [繁體中文](../../zh-TW/reference/permissions.md) · [参考索引](README.md)

← [文档索引](../README.md) · [项目说明](../../../README.zh-CN.md) · [命令参考](commands.md) · [权限参考](permissions.md) · [配置参考](configuration.md) · [API 参考](api.md)

# 权限参考

插件一共宣告 29 个权限节点，**全部默认 `op`**。刚装好的服务器上，除了管理员没有人能用任何 `/land` 子指令，这是刻意的保守设定。服主必须用权限插件（例如 LuckPerms）把节点指派给玩家组，权限才真的开放。插件没有面向玩家的开关，也不会自动把权限放给玩家。

想快速上手，先看[普通玩家至少要开哪几个](#普通玩家至少要开哪几个)。

## 为什么有两道闸门

通过权限节点只是第一关。管理类子命令还会再经过一道领地层的**管理闸门**，它问的是「你在这块领地里有没有权做这件事」。两道都通过才会真的执行。

闸门在解析链之前先判断：

- 管理员豁免开启时直接通过，不需要是主人。
- 服务器领地协管在服务器领地上等同主人。这个身分只在服务器领地有效，在玩家领地与不存在的领地上都被忽略，而且单靠它不授权任何其他操作。
- 没有豁免也没有协管身分时，才走一般解析链，玩家领地由主人保障撑住。

服务器领地在进解析链之前就被拒绝，所以绑在玩家命名空间的授权永远无法变更服务器领地。

## 命令节点

| 节点 | 对应命令 |
| --- | --- |
| `chunkland.command.land.help` | `/land help`、`/land`（无参数） |
| `chunkland.command.land.wand` | `/land wand` |
| `chunkland.command.land.claim` | `/land claim` |
| `chunkland.command.land.confirm` | `/land confirm` |
| `chunkland.command.land.expand` | `/land expand` |
| `chunkland.command.land.shrink` | `/land shrink`、`/land unclaim` |
| `chunkland.command.land.subland` | `/land subland` |
| `chunkland.command.land.rename` | `/land rename` |
| `chunkland.command.land.delete` | `/land delete` |
| `chunkland.command.land.trust` | `/land trust` |
| `chunkland.command.land.untrust` | `/land untrust` |
| `chunkland.command.land.default` | `/land default` |
| `chunkland.command.land.binding` | `/land binding` |
| `chunkland.command.land.group` | `/land group` |
| `chunkland.command.land.profile` | `/land profile` |
| `chunkland.command.land.ban` | `/land ban` |
| `chunkland.command.land.unban` | `/land unban` |
| `chunkland.command.land.manage` | `/land manage` |
| `chunkland.command.land.explain` | `/land explain` |
| `chunkland.command.land.inspect` | `/land inspect` |
| `chunkland.command.land.log` | `/land log` |
| `chunkland.command.land.history` | `/land history` |

这 22 个是 `/land` 的节点，`unclaim` 没有独立节点，共用 `shrink` 那个。`/land` 本身没有顶层节点，`chunkland.command.land.unclaim` 不存在。

## 命令节点之后的管理闸门

下面这些子命令通过命令节点之后，还会被对应到一个管理动作，由领地层权限决定。动作清单就是管理闸门认得的那些。

| 管理动作 | 涵盖的子命令 |
| --- | --- |
| `MANAGE_MEMBER`（管理成员） | `trust`、`untrust`、`ban`、`unban` |
| `MANAGE_PERMISSION`（管理权限） | `default`、`binding`、`explain`、`inspect` |
| `MANAGE_SUBLAND`（管理子领地） | `subland` |
| `EXPAND_LAND`（扩张领地） | `expand`、`shrink`、`unclaim` |
| `DELETE_LAND`（删除领地） | `delete` |

其余子命令（`help`、`wand`、`claim`、`confirm`、`rename`、`group`、`profile`、`log`、`history`、`manage`、`bypass`、`admin`）不带管理动作，不进这道闸门；它们各自靠命令节点加上领地资料的拥有者检查把关。

## 管理员节点

这四个节点互相独立。持有其中一个不会拿到另外三个的判定结果。

| 节点 | 授予什么 | 不授予什么 |
| --- | --- | --- |
| `chunkland.admin.bypass` | 允许你执行 `/land bypass on\|off` 尝试切换豁免 | 单靠它不授权任何管理操作。豁免只在明确切换之后才对闸门生效，而且每次切换都留下审计记录 |
| `chunkland.admin.serverland` | 服务器领地协管身分，在服务器领地上等同主人 | 不会给你豁免、账本或孤儿世界权限；在玩家领地上完全无效 |
| `chunkland.admin.ledger` | `/land admin ledger` 的账本查询与处理 | 不会授权不可逆的孤儿世界清除 |
| `chunkland.admin.orphan` | `/land admin orphan`，包含不可逆的 `purge` | 不会给你账本或豁免的判定结果 |

豁免内存是**每次启用世代**建立的：服务器一启动每个人都默认关闭，停用时清空，所以没有任何开关状态能撑过一次重启。单纯持有节点永远不会翻转它。

## 诊断节点

| 节点 | 对应命令 |
| --- | --- |
| `chunkland.debug.m0message` | `/chunkland m0message` |
| `chunkland.debug.m0test` | `/chunkland m0test` |
| `chunkland.debug.visualization` | `/chunkland viz` |

`/chunkland` 没有顶层节点，三个子命令各自检查自己的节点。

## 普通玩家至少要开哪几个

服主最常见的起手式是开一个「能圈地」的权限组合。下面几行是逐级加深的清单，实际要开到什么程度取决于你想让玩家自己决定多少事。

| 想要的程度 | 需要开的节点 |
| --- | --- |
| 只能圈地 | `chunkland.command.land.wand`、`chunkland.command.land.claim`、`chunkland.command.land.confirm` |
| 加上改名 | 再加 `chunkland.command.land.rename` |
| 加上扩张与缩减 | 再加 `chunkland.command.land.expand`、`chunkland.command.land.shrink` |
| 加上自己管成员与封禁 | 再加 `chunkland.command.land.trust`、`chunkland.command.land.untrust`、`chunkland.command.land.ban`、`chunkland.command.land.unban` |
| 加上自己调权限 | 再加 `chunkland.command.land.default`、`chunkland.command.land.binding`、`chunkland.command.land.group`、`chunkland.command.land.profile`、`chunkland.command.land.manage` |
| 加上管理子领地 | 再加 `chunkland.command.land.subland` |
| 让玩家自己查 | 再加 `chunkland.command.land.help`、`chunkland.command.land.explain`、`chunkland.command.land.inspect`、`chunkland.command.land.log` |

`chunkland.command.land.delete` 建议不要开给普通玩家。删除会永久移除领地并全额退款，只有你能决定要不要给这个权力。

## 动作标识字

标识字就是 `ProtectionActionType` 里的枚举名称，不分大小写，头尾空白会被去掉。整份枚举有 40 个，但**三个指令各自接受的范围不同**：

| 命令 | 接受的动作 |
| --- | --- |
| `/land default <action>` | 下面「可以直接设领地默认的动作」那 17 个日常成员动作 |
| `/land profile set <profile> <action> <state>` | 全部 22 个 `SUBJECT_PERMISSION` 动作，也就是 17 个日常动作加上 5 个管理动作 |
| `/land explain <action>` | 全部 40 个，连规则动作与跨边界动作都能解释 |

`explain` 的接受范围最宽，所以它会回报某些没有任何界面可以设定的动作的判定结果。这是诊断用的读取路径，不代表那些动作可调。

**可以直接设领地默认的动作**（`/land default` 接受的就是这 17 个）

| 标识字 | 动作 |
| --- | --- |
| `BLOCK_BREAK` | 破坏方块 |
| `BLOCK_PLACE` | 放置方块 |
| `CONTAINER_OPEN` | 打开容器 |
| `WORKSTATION_USE` | 使用工作方块 |
| `DOOR_USE` | 开关门 |
| `BUTTON_USE` | 按按钮 |
| `LEVER_USE` | 扳动拉杆 |
| `REDSTONE_USE` | 触发红石装置 |
| `BUCKET_USE` | 使用桶 |
| `ENTRY` | 进入领地 |
| `VEHICLE_USE` | 使用载具 |
| `ENTITY_INTERACT` | 与生物互动 |
| `ENTITY_DAMAGE` | 攻击生物 |
| `ITEM_FRAME` | 动用品展示框 |
| `ARMOR_STAND` | 动用盔甲架 |
| `HANGING_ENTITY` | 动用画与挂饰 |
| `FARMLAND_TRAMPLE` | 踩踏耕地 |

**管理动作**（`/land default` 不接受，`/land profile set` 接受）

| 标识字 | 对应的闸门 |
| --- | --- |
| `MANAGE_MEMBER` | 管理成员 |
| `MANAGE_PERMISSION` | 管理权限 |
| `MANAGE_SUBLAND` | 管理子领地 |
| `EXPAND_LAND` | 扩张领地 |
| `DELETE_LAND` | 删除领地 |

这五个是上面那道管理闸门在内部使用的动作。它们不能写进领地默认——默认只给日常成员动作留位置——但可以在权限组合里设定，让你把某个组合整包绑给成员时连管理能力一起带上。

**规则动作**（`/land default` 与 `/land profile set` 都不接受，只有 `explain` 能解释）

| 标识字 | 规则 |
| --- | --- |
| `PLAYER_DAMAGE_PLAYER` | PVP |
| `EXPLOSION_TERRAIN` | 爆炸破坏地形 |
| `EXPLOSION_ENTITY` | 爆炸伤害生物 |
| `FIRE_SPREAD` | 火焰蔓延 |
| `FIRE_BURN` | 火焰烧毁方块 |
| `MOB_GRIEFING` | 生物破坏方块 |
| `FLUID_FLOW` | 液体流动 |
| `PISTON_MOVE` | 活塞推动方块 |
| `HOPPER_TRANSFER` | 漏斗传输物品 |
| `HOSTILE_MOB_SPAWN` | 敌对生物生成 |
| `PASSIVE_MOB_SPAWN` | 友好生物生成 |

这一类答的是「这个机制能不能在这里发生」，没有玩家的面向。底下还有一组只在跨越边界时用到的规则动作：`BLOCK_MOVE_IN`、`BLOCK_MOVE_OUT`、`FLUID_ENTER`、`FLUID_EXIT`、`ITEM_TRANSFER_IN`、`ITEM_TRANSFER_OUT`、`DISPENSER_CROSS_BOUNDARY`。同样没有可调整的界面，只有 `explain` 读得到判定结果。这七个加上上面 11 个就是全部 18 个规则动作，17 加 22 加它们刚好是 40。

完整的枚举清单在 [API 参考](api.md#领域类型)。

## 判定链的层级

`/land explain` 会告诉你这一刻是哪一层决定了结果。实际顺序如下，每一层出现明确值就停下来，不再往下问。

**玩家权限链**

| 顺序 | 层级 |
| --- | --- |
| 1 | 管理员豁免（所有来源都先查这一项） |
| 2 | 领地主人保障（你是主人就直接允许） |
| 3 | 子领地授权汇总 |
| 4 | 子领地默认值 |
| 5 | 领地授权汇总 |
| 6 | 领地默认值（`/land default` 设的就是这一层） |
| 7 | 世界默认值（`config.yml` 的 `subject-defaults.worlds.<世界>`） |
| 8 | 全域默认值（`config.yml` 的 `subject-defaults.global`） |
| 9 | 隐含拒绝（全部都没设定时一律拒绝） |

**领地规则链**

| 顺序 | 层级 |
| --- | --- |
| 1 | 子领地规则 |
| 2 | 领地规则 |
| 3 | 世界默认值 |
| 4 | 全域默认值 |
| 5 | 隐含拒绝 |

规则链刻意不含主人保障，所以环境规则对领地主人同样有效。

## 怎么把节点发下去

任何权限插件都可以，节点就是普通的 Bukkit 权限。LuckPerms：

```
/lp user <player> permission set chunkland.command.land.claim true
```

在权限插件的 YAML 或某个插件自己的 `plugin.yml` 里：

```yaml
permissions:
  chunkland.command.land.claim: true
```

## 这里没有涵盖的

权限节点只管「能不能执行命令」与「能不能改这块领地」。实际能不能在领地里破坏方块、开箱子，是领地层的权限决定的，两套东西互相独立：开了 `chunkland.command.land.ban` 不代表你能在任何地方封禁人，还是得先站在自己管得到的领地里。

## 相关页面

[命令参考](commands.md) ·
[配置参考](configuration.md) ·
[服务器管理指南](../server-guide.md) ·
[玩家指南](../user-guide.md)