[English](../en/server-guide.md) · [繁體中文](../zh-TW/server-guide.md) · [文档索引](README.md)

# ChunkLand 服务器管理指南

写给要安装、配置和维护 ChunkLand 的服主。玩家怎么用看[玩家指南](user-guide.md)，每个配置项的完整说明看[配置参考](reference/configuration.md)。

## 装起来

1. 把 AceLib 1.3.0 放进服务器的 `plugins/` 目录。ChunkLand 在 `plugin.yml` 里声明了 `depend: [AceLib]`，缺了它服务器不会加载 ChunkLand。
2. 从 [v0.1.0 Release](https://github.com/smile-minecraft/ChunkLand/releases/tag/v0.1.0) 下载 `chunkland-plugin-0.1.0.jar`，复制到 `plugins/`。这个 jar 自带依赖，API 模块、SQLite、SnakeYAML 都在里面，`plugins/` 里不用再放别的 jar；同一个 Release 里的 `SHA256SUMS` 列出各附件的预期摘要值。

   要改从源码构建的话：

   ```bash
   ./scripts/build-acelib.sh          # 取得编译用的 AceLib 1.3.0
   ./gradlew build --no-daemon --console=plain
   ```

   产物是 `chunkland-plugin/build/libs/chunkland-plugin-0.1.0.jar`。
3. 重启服务器。首次启动会创建 `plugins/ChunkLand/`。
4. 按需修改 `config.yml`，再重启一次。

构建步骤和脚本细节在[开发者指南](developer-guide.md#编译前先取-acelib)里。

**没有 reload 命令。** Bukkit 的 `/reload` 也不支持。任何配置改动都要重启服务器才生效。

## 第一次启动会生成什么

| 路径 | 内容 |
| --- | --- |
| `plugins/ChunkLand/config.yml` | 配置 |
| `plugins/ChunkLand/lang/en_US.yml` | 英文语言文件 |
| `plugins/ChunkLand/lang/zh_TW.yml` | 繁体中文语言文件 |
| `plugins/ChunkLand/chunkland.db` | SQLite 数据库 |

插件目前只带英文和繁体中文两个语言文件。简中客户端看到的提示文字仍是服务器默认语言，拒绝提示的 ActionBar 也一律用服务器默认语言渲染，不按玩家逐个切换语言——这只是显示差异，不影响保护判定。

可选依赖：`softdepend: [Vault, LuckPerms, CoreProtect]`。

| 插件 | 用途 |
| --- | --- |
| Vault 生态的经济插件 | 圈地计费与退款。只有把 `economy.enabled` 改成 `true` 时才需要；默认 `false`，圈地免费 |
| LuckPerms | 额度元数据（`/land inspect` 显示的上限来源） |
| CoreProtect | `/land history` 附近的方块改动记录 |

没有它们插件照常启动，只是计费、额度覆盖和 `history` 用不了。

## 开服前值得先改的两个默认值

**进入默认放行。** `subject-defaults.global.ENTRY: ALLOW` 意味着从没被你信任过的玩家可以走进已加载的领地。`/land ban` 仍然拦得住。要把领地对外关上：

```yaml
subject-defaults:
  global:
    ENTRY: DENY
```

**十一项领地规则没有入口可调。** PVP、爆炸、火焰蔓延、生物破坏、水流、活塞、漏斗传输、生物生成全部走内置默认：水流、活塞、漏斗传输与敌对／被动生物生成在领地内照原版运作；PVP、爆炸、火焰蔓延与烧毁、生物破坏一律拒绝。活塞、液体、漏斗只要跨越领地边界（不论进出）一律拒绝。`config.yml` 里被注释掉的 `rule-defaults` 只是示例，还没接上实际功能，写了也不会生效。

## 普通玩家用不了命令，直到你授权

全部 29 个权限节点的默认值都是 `op`，开箱状态下只有管理员能圈地、信任成员、打开管理界面。授权方式就是常规的权限插件或 `permissions.yml`，插件自己不提供任何玩家端开关，也没有自动开放。

最省事的做法是把日常需要的几个节点发给玩家组：

```
chunkland.command.land.wand
chunkland.command.land.claim
chunkland.command.land.trust
chunkland.command.land.untrust
chunkland.command.land.ban
chunkland.command.land.unban
chunkland.command.land.inspect
chunkland.command.land.explain
chunkland.command.land.manage
```

完整清单和每个节点管什么，见[权限参考](reference/permissions.md)。

## 配置文件坏了怎么办

启动时插件会先决定配置从哪里来。配置文件正常就照常加载，并更新备份。以下两种异常走后备来源。

**配置文件存在但读不了、解析不了或校验不过（损坏）：** 改用上次确认可用的配置（备份）。没有可用备份时，改用一套刻意保守的配置。这条路径**不会**退回插件内置的默认配置，保护也不会被悄悄放宽。

**配置文件不见了：** 分两种。全新安装（目录里没有任何先前使用痕迹）会从内置默认生成一份 `config.yml` 并以它启动，只做一次。以前用过但文件不见的，**不会**生成默认文件，改用备份；没有备份就用保守配置。

保守配置可能比原来的更严：所有世界停用新圈地，经济相关操作停用（定价格式不可用），保护范围从严，判定一律以拒绝为底。这是刻意的取舍，宁可暂时从严，也不让文件损坏意外放宽保护。

控制台会看到什么：

- 全新安装：`INFO`，包含生成文件的绝对路径，并提示编辑后需要重启。
- 配置损坏，或以前用过但文件不见：`ERROR`，包含配置文件绝对路径、实际采用的后备来源；损坏时还会给出失败的配置键路径（例如 `worlds.world.vertical-mode`），并明确标示没有采用内置默认配置。
- 全新安装时默认文件写不出去：`WARNING`，插件仍以内存中的内置默认启动。

修的步骤：停服，改好 `plugins/ChunkLand/config.yml`，再启动。你原来的 `config.yml` 不会被覆盖、删除或改名，插件只读它。备份在 `plugins/ChunkLand/config-last-known-good.yml`，存放最后一次成功加载并通过校验的配置原文，只有加载成功才更新。

> **验证层级说明**：这一节描述的行为是在代码与自动化测试层确认的，还没有在实机服务器上复验过——真正生成配置文件、控制台消息样式、以及改完修好重启生效的完整流程都没跑过。所以这一节目前**还不是**实机确认的条目。

## 数据库与备份

所有领地数据都在 `plugins/ChunkLand/chunkland.db` 这一个 SQLite 文件里。备份服务器的时候把整个 `plugins/ChunkLand/` 文件夹一起带走。

**服务器还在跑的时候不要直接复制这个文件。** SQLite 会锁住数据文件，同时读写可能把数据弄坏。要备份就先把服务器停下来。

停服之后再复制 `chunkland.db`。如果目录里还有 `chunkland.db-wal` 或 `chunkland.db-shm`，那两个是 SQLite 的写入日志与共享内存档，运行时存在就一起复制过去，缺了会少掉尚未并回主档的内容。正常停服会自己清理它们，留下来就说明当时有未结案的写入，一起带走最保险。

`config.yml` 和你改过的语言文件也值得备份，跟数据库一样值得留一份。`config.yml` 丢掉并不会自动重新生成默认配置，插件会改用备份或保守配置，实际回退行为见本页「配置文件坏了怎么办」。语言文件下次启动会补上缺的部分，你手改的文案则要自己留一份。

其他插件的数据库有同样的问题：`LuckPerms/luckperms-h2-v2.mv.db`、`CoreProtect/database.duckdb` 也是运行时不能直接开的。要查线上数据，先停服务器，或改用该插件自己的指令。

### 还原方式

还原同样要先停服务器。把当初备份的整个 `plugins/ChunkLand/` 文件夹照原样放回去：数据库（`chunkland.db`，以及当时一起复制的 `chunkland.db-wal`／`chunkland.db-shm`）、`config.yml`、`config-last-known-good.yml` 和语言文件，**全部放回去之后才启动服务器**。

最容易漏的是这两样。只把 `chunkland.db` 放回去、却少了当时一起备份的 `-wal`／`-shm`，可能丢掉还没并回主档的数据；只把 `config.yml` 放回去、却少了 `config-last-known-good.yml`，就失去配置读不动时的后备——而那正是你最需要它的时候。

绝对不要在运行中的服务器上直接覆盖还原：覆盖活的 SQLite 文件跟运行时复制一样有损坏风险，正确顺序是先停机、换回整个文件夹、再启动。

## 计费与退款

```
economy:
  enabled: false
  currency:
    code: EMC
    scale: 2
  pricing:
    tiers: ...
```

`enabled` 是购买开关，默认 `false`：圈地与扩张免费，不需要经济插件，玩家能圈多少只受 `limits` 限制（默认每人合计 10 个区块）。改成 `true` 才按下面的档位收费。

`currency.code` 是所有价格存储和扣款使用的货币名；`scale` 是最小单位对主单位的指数，2 表示 100 最小单位等于 1 主单位。

`pricing.tiers` 按地主的全局区块总数分档计价（只算玩家领地，不含服务器领地）：落在 `(previousUntil, until]` 这一档的区块，每块收 `price-per-chunk` 个主单位。档位必须是连续分区，最后一档必须是 `unbounded`；有空档、重叠、负数、非有限值或溢出的条目，配置加载失败并保留上一份快照，玩家圈地按拒绝处理，不会被悄悄改价。

退款永远来自每块区块持久保存的成本基准，不来自这套档位表，所以改档位不会影响过去已付款项的退款金额。

**账本里还有有效记录时不要改 `scale`。** 已存储的最小单位会被换算成不同的主单位金额。

开启购买（`enabled: true`）时需要 Vault 生态的经济插件；开着购买却没有经济插件，玩家圈地会被挡下。购买关闭时不需要经济插件。免费圈到的区块没有退款；开启期间买下的区块在关闭购买之后仍按当时记录的价格退款，所以在已上线的服务器关闭购买时请保留经济插件。

## 审计日志保留多久

`audit.retention-days` 默认 180 天，界定审计历史保留多久。设成 `0` 表示永久保留、不做清理。清理只删除严格早于截止点的审计行，在持久化线程上分批执行；领地、区块和账本行不会被碰到，已删除领地的历史在自己的截止点到期前仍可读。

## 遇到问题先取这个

玩家报告保护动作被拒时，最有用的一份材料是他在领地内跑 `/land explain <action>` 的输出：它给出结果、判定层与来源，以及所有权、admin bypass、管理人三个角色各自有没有生效。完整的已知问题清单在[已知限制](limitations.md)。

## 相关页面

- [文档索引](README.md)
- [配置参考](reference/configuration.md) · [命令参考](reference/commands.md) · [权限参考](reference/permissions.md)
- [已知限制](limitations.md)