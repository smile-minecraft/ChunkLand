[English](../../en/reference/commands.md) · [繁體中文](../../zh-TW/reference/commands.md) · [参考索引](README.md)

← [文档索引](../README.md) · [项目说明](../../../README.zh-CN.md) · [命令参考](commands.md) · [权限参考](permissions.md) · [配置参考](configuration.md) · [API 参考](api.md)

# 命令参考

`/land` 一共 25 个子命令，顺序与插件内部的派发顺序一致。`/chunkland` 是给开发与除错用的诊断指令，与玩家功能无关。

直接输入 `/land` 不带参数等同 `/land help`；`?` 也是 `help` 的别名。

**权限节点那一栏在[权限参考](permissions.md)有完整说明，这里只标节点名称。**

**所有权限节点的默认值都是 `op`。** 刚装好的服务器上，这些命令对管理员以外的人一律无效，插件不会自己开放给玩家。

## 圈地与范围

| 子命令 | 用法 | 权限节点 | 玩家专属 |
| --- | --- | --- | --- |
| `help` | `/land help [子命令]` | `chunkland.command.land.help` | 否 |
| `wand` | `/land wand` | `chunkland.command.land.wand` | 是 |
| `claim` | `/land claim <land_name>` | `chunkland.command.land.claim` | 是 |
| `confirm` | `/land confirm <generation> <revision> <land_name>` | `chunkland.command.land.confirm` | 是 |
| `expand` | `/land expand` | `chunkland.command.land.expand` | 是 |
| `shrink` | `/land shrink` | `chunkland.command.land.shrink` | 是 |
| `unclaim` | `/land unclaim` | `chunkland.command.land.shrink` | 是 |
| `rename` | `/land rename <new_name>` | `chunkland.command.land.rename` | 是 |
| `delete` | `/land delete confirm <revision>` | `chunkland.command.land.delete` | 是 |
| `subland` | `/land subland (select\|create\|update\|delete\|extend) [generation revision] [name]` | `chunkland.command.land.subland` | 是 |

`claim` 单独跑不会成立领地：它回一个确认，真正落地的是 `confirm`。`shrink` 与 `unclaim` 共用一个权限节点，`unclaim` 没有自己的节点。`delete` 第一次执行只取得带 `<revision>` 的确认指令。

「玩家专属」指该命令从主控台执行会被拒绝。管理类子命令还要再过一道领地层的管理闸门，所以你必须站在自己管得到的领地里面。

## 权限与成员

| 子命令 | 用法 | 权限节点 | 玩家专属 |
| --- | --- | --- | --- |
| `trust` | `/land trust <player>` | `chunkland.command.land.trust` | 是 |
| `untrust` | `/land untrust <player>` | `chunkland.command.land.untrust` | 是 |
| `ban` | `/land ban <player>` | `chunkland.command.land.ban` | 是 |
| `unban` | `/land unban <player>` | `chunkland.command.land.unban` | 是 |
| `default` | `/land default <action> [ALLOW\|DENY\|INHERIT]` | `chunkland.command.land.default` | 是 |
| `binding` | `/land binding (bind\|unbind) player <player> <profile> \| group <group> <profile> [--in-subland]` | `chunkland.command.land.binding` | 是 |
| `group` | `/land group (create\|list\|add\|remove\|delete) [参数]` | `chunkland.command.land.group` | 是 |
| `profile` | `/land profile (create\|list\|set\|delete) [参数]` | `chunkland.command.land.profile` | 是 |
| `manage` | `/land manage` | `chunkland.command.land.manage` | 是 |

`default` 的 `<action>` 只接受那 17 个可直接设默认的动作，规则动作不行；第三个参数省略时显示当前设置。`group` 的 `create`、`delete` 各收一个名称，`add`、`remove` 先群组再玩家。`profile` 的 `set` 收 `<action>` 与 `<state>`，`state` 只能是 `ALLOW`、`DENY`、`INHERIT`，它的 `<action>` 接受全部 22 个玩家权限动作。`binding` 加 `--in-subland` 表示绑在子领地里。

动作标识字的完整清单在[权限参考](permissions.md#动作标识字)。

## 查询

| 子命令 | 用法 | 权限节点 | 玩家专属 |
| --- | --- | --- | --- |
| `explain` | `/land explain <action>` | `chunkland.command.land.explain` | 是 |
| `inspect` | `/land inspect [player]` | `chunkland.command.land.inspect` | 是 |
| `log` | `/land log [u:<player-uuid>] [t:<duration>] [a:<action>] [land:<land-uuid>] [world:<world-uuid>] [limit:<n>] [page:<n>]` | `chunkland.command.land.log` | 否 |
| `history` | `/land history` | `chunkland.command.land.history` | 是 |

`explain`、`inspect`、`manage` 都要你站在自己管得到的领地里面。`explain` 的 `<action>` 不分大小写比对 `ProtectionActionType` 的完整名称，Tab 补全会按前缀给出合法名字；`log` 的 `t:` 接受 `7d` 这样的时长。

`history` 需要服务器上装了 CoreProtect，没有对应 provider 时会回覆功能暂时无法使用。

## 管理员

| 子命令 | 用法 | 权限节点 | 玩家专属 |
| --- | --- | --- | --- |
| `bypass` | `/land bypass [on\|off]` | `chunkland.admin.bypass` | 是 |
| `admin` | `/land admin (ledger\|orphan) ...` | 见下表 | 否 |

两个分支用不同节点，互不授权：

| 分支 | 权限节点 | 用法 |
| --- | --- | --- |
| `admin ledger` | `chunkland.admin.ledger` | `/land admin ledger list [state]`<br>`/land admin ledger show [operation]`<br>`/land admin ledger resolve <operation> [RESOLVED\|REFUNDED\|IGNORED]` |
| `admin orphan` | `chunkland.admin.orphan` | `/land admin orphan list`<br>`/land admin orphan purge [world-uuid]`<br>`/land admin orphan purge [world-uuid] confirm <nonce>` |

`orphan purge` 永久删除那个世界底下的所有领地，**不会退款也无法复原**。指令先给你一个有实效的确认码，超时要重新产生。

## 诊断指令

| 用法 | 权限节点 | 用途 |
| --- | --- | --- |
| `/chunkland m0message [locale]` | `chunkland.debug.m0message` | 测试消息管线在指定语系下的输出 |
| `/chunkland m0test [scheduler\|gui\|form\|cancelall]` | `chunkland.debug.m0test` | 调度器、界面、表单与取消流程的烟雾测试 |
| `/chunkland viz <start\|stop\|status>` | `chunkland.debug.visualization` | 选区边界可视化的开关与状态查询 |

`viz` 只画粒子，不查地形数据，负载由 `selection` 区段那几个键控制，见[配置参考](configuration.md#selection)。

## 输入到一半时的补全

| 子命令 | 补全什么 |
| --- | --- |
| `help` | 全部子命令名称 |
| `explain` | 全部 `ProtectionActionType` 名称 |
| `default` | 可设默认值的动作标识字；第三个参数补 `ALLOW`、`DENY`、`INHERIT` |
| `trust`、`untrust`、`ban`、`unban`、`inspect` | **在线**玩家名称 |
| `subland` | `select`、`create`、`update`、`delete`、`extend` |
| `group` | `create`、`list`、`add`、`remove`、`delete` |
| `profile` | `create`、`list`、`set`、`delete` |
| `binding` | `bind`、`unbind`；接著补 `player` 或 `group` |
| `bypass` | `on`、`off` |
| `delete` | 确认分支的关键字 |
| `admin` | `ledger`、`orphan` |

名单类命令的补全只给在线玩家，这是设计如此。

## 玩家名称怎么解析

`<player>` 参数接受 UUID 文本、在线玩家名称，或离线玩家名称。顺序：

1. UUID 文本，直接解析，必定成功。
2. 在线玩家，名称完全匹配，区分大小写。
3. 离线玩家查询，在异步执行器上跑，只对服务器已经认识的玩家作答（曾登录过或当前在线）。它可能卡在 profile 查询上，所以离开区域线程执行，超时就 fail closed。

其他一律 fail closed，回覆找不到该玩家。从未加入过这台服务器的名字解析不出来，请改用 UUID。没有部分匹配。

## 被拒绝时你会看到什么

聊天栏会直接写出原因。

| 你看到的情况 | 意思 |
| --- | --- |
| 不允许使用这个命令，后面附节点名称 | 权限节点没授权，请服主处理 |
| 请站在你可以管理的领地内再试一次 | 管理闸门没过，你不在自己管得到的领地里 |
| 功能暂时无法使用 | 对应子系统没接好或暂时不可用 |
| 找不到这位玩家；离线玩家请输入完整的玩家名称或 UUID | 名字从未登录过或拼错了，改用 UUID |
| 子命令 X 尚未实现 | 这个子命令在当前构建里还没有接上处理器 |
| 那次确认已过期，请看最新详情再确认一次 | 确认有实效，重新走一次流程 |

## 相关页面

[权限参考](permissions.md) ·
[配置参考](configuration.md) ·
[玩家指南](../user-guide.md) ·
[已知限制](../limitations.md)