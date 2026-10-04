[English](README.md) · [繁體中文](README.zh-TW.md) · 简体中文

![ChunkLand 示意图：Folia 服务器上以区块为单位的领地，每块领地都有不同颜色的边界](docs/assets/banner.png)

# ChunkLand

ChunkLand 是专为 **Folia 26.2** 多线程服务器设计的区块领地保护插件。玩家只要拿起魔杖圈出范围，无论是破坏、放置、开箱、红石互动还是 PVP 战斗，每一次动作都会在发生的当下即时判定，兼顾领地安全与多线程服务器的高性能。

项目目前处于 **0.1.0** 开发阶段，尚未发布预编译好的 Release 文件；若要在服务器上试用，请直接从源码构建 jar 包（步骤见下方[安装步骤](#安装步骤)）。

## 保护涵盖范围

ChunkLand 的核心逻辑非常纯粹：**在特定的位置，某位玩家（或机制）究竟能不能执行该动作？** 目前判定的范围涵盖：

- **玩家日常操作**：方块破坏与放置、容器存取（箱子、熔炉、酿造台）、红石机关（门、按钮、拉杆）、桶与载具、生物互动与伤害、装饰悬挂物（展示框、盔甲架）、踩踏农田、PVP 与进入领地。
- **跨界机制防护**：活塞推拉、水流蔓延、漏斗物品传输，以及跨边界推送物品的发射器。
- **即时投射物判定**：箭矢与三叉戟等投射物一律在“命中目标当下”判定，不以射击者当初站在哪里为准。

![ChunkLand 边界示意图：相邻两个区块之间的领地边界，两侧分属不同领地](docs/assets/land-boundaries.png)

### 功能一览

![圈地三步骤：拿魔杖点两个角落、选区自动对齐整个区块、输入领地名称并在聊天栏确认](docs/assets/claiming.png)

![领地不必是矩形：先圈一块矩形，再选取更多区块并扩张，领地就能变成任何相连的形状；区块必须以边相连，领地中间不能有洞](docs/assets/land-shapes.png)

![谁能做什么：陌生玩家只能进入，受信任的玩家可以进入、使用与建造，地主另外可以管理，被封禁的玩家什么都不能做](docs/assets/permissions.png)

![领地内的机制：水与岩浆、活塞、漏斗、生物生成照原版运作；PVP、爆炸、火焰蔓延、生物破坏被挡下](docs/assets/land-rules.png)

![一块领地里有商店与农场两个子领地，各自有独立的权限与进出提示](docs/assets/sublands.png)

*注：以上图片皆为概念示意图，不是服务器实机截图。边界图展示相邻两块领地与边界会挡下什么；其余五张依次说明圈地步骤、领地可以长成任意形状、谁能做什么、领地内哪些机制照常运作，以及子领地，内容皆以随附的默认值为准。*

## 运行环境需求

| 项目 | 版本 | 说明 |
| --- | --- | --- |
| 服务器核心 | Folia 26.2 | 必备，不支持标准 Paper 或其他分支 |
| Java 运行环境 | Java 25 | 必备 |
| 基础前置插件 | AceLib 1.3.0 | 必备，缺少则无法启动插件 |
| 可选依赖 | Vault 系列经济插件、LuckPerms、CoreProtect | 可选：经济插件支持圈地计费、LuckPerms 提供额度元数据、CoreProtect 支持 `/land history` |

特别注意：**ChunkLand 仅支持 Folia 26.2**。插件描述文件与编译配置中标记的 `26.1.2` 仅为依赖构建所需，不代表兼容旧版本或 Paper。

## 安装步骤

1. 将 AceLib 1.3.0 放入服务器的 `plugins/` 目录。ChunkLand 声明了 `depend: [AceLib]`，缺少前置时服务器会拒绝加载。
2. 构建插件 jar 并复制至 `plugins/`：

   ```bash
   ./scripts/build-acelib.sh          # 下载并校验编译所需的 AceLib 1.3.0
   ./gradlew build --no-daemon --console=plain
   ```

   编译产物位于 `chunkland-plugin/build/libs/chunkland-plugin-0.1.0.jar`。此 jar 已内置核心依赖（API 模块、SQLite 驱动、SnakeYAML），不需要额外在 `plugins/` 补充运行库。
3. 启动服务器。初次加载会自动生成 `plugins/ChunkLand/` 目录，内含默认 `config.yml`、语言文件（`lang/en_US.yml`、`lang/zh_TW.yml`）以及 SQLite 数据库 `chunkland.db`。
4. 根据服务器需求微调 `config.yml` 后重启服务器。**Folia 环境下不提供 reload 命令**，任何配置变更皆需重启服务器生效。

更详细的服主运维指南（包含配置文件损坏时的自动恢复机制）请参阅[服务器管理指南](docs/zh-CN/server-guide.md)。

## 第一次圈地

只要简单三步即可完成保护：

```
/land wand          # 领取选区魔杖（外观为木棍）
                    # 左键点击第一角，右键点击对角形成选区
/land claim Home    # 设置领地名称；聊天栏会显示确认信息，直接点击 [确认] 即可
```

圈地完成后，可用 `/land inspect` 查看详细信息，或通过 `/land explain BLOCK_BREAK` 即时排查某个动作在该领地的判定结果。

选区魔杖、多数领地管理命令与图形界面都需要玩家亲自站在目标领地内操作。完整语法请参考[命令参考](docs/zh-CN/reference/commands.md)。

## 开服必看：刚装好时玩家默认没有命令权限

为了避免服务器刚装好就被随意圈地，所有权限节点默认皆锁定给管理员（`default: op`）。也就是说，**普通玩家刚进服时是无法圈地、信任朋友或打开管理界面的**。

服主需通过 LuckPerms 等权限插件将节点分配给玩家或权限组，大家才能开始圈地。常用的 29 个节点请参考[权限参考](docs/zh-CN/reference/permissions.md)；其中 `chunkland.admin.*` 与 `chunkland.debug.*` 请务必保留给管理团队。

## 正式运营前建议留意的两个默认值

1. **陌生玩家默认可以走进领地**：配置文件中 `subject-defaults.global.ENTRY: ALLOW` 代表未受信任的路人也能进入已加载的领地（不过 `/land ban` 依然能个别拦截）。若希望打造完全封闭的私人领地，请改为：
   ```yaml
   subject-defaults:
     global:
       ENTRY: DENY
   ```
2. **十一项领地规则目前由系统默认托管**：包含 PVP、爆炸、火焰蔓延、生物破坏、水流、活塞与漏斗跨界等机制，目前尚未开放命令或界面调整，一律采用内置默认：水流、活塞、漏斗传输与敌对／被动生物生成在领地内照原版运作；PVP、爆炸、火焰蔓延与烧毁、生物破坏一律拦截。活塞、液体、漏斗只要跨越领地边界（不论进出）一律拦截。`config.yml` 中的 `rule-defaults` 注释区块暂未接线。

其余已知的运行特性（例如点火行为未受保护、`/tp` 传送不受拦截、被封锁者在下次移动时推回等），请查阅 [LIMITATIONS.md](LIMITATIONS.md)。

## 说明文档一览

完整文档提供简体中文、繁体中文与英文三种版本，统一收录于 `docs/`：

| 你的角色 | 推荐从这里开始 |
| --- | --- |
| 想要圈地保护基地的玩家 | [玩家指南](docs/zh-CN/user-guide.md) |
| 负责开服维护与调优的服主 | [服务器管理指南](docs/zh-CN/server-guide.md) |
| 想要对接 API 的插件开发者 | [开发者指南](docs/zh-CN/developer-guide.md) |
| 想查命令、配置参数或权限 | [参考资料索引](docs/zh-CN/reference/) |
| 想先掌握已知问题与边界条件 | [已知限制](docs/zh-CN/limitations.md) |

其他资源：[CHANGELOG.md](CHANGELOG.md) 记录各版本变更细节，[llms.txt](llms.txt) 为大语言模型索引，[CONTRIBUTING.md](CONTRIBUTING.md) 供有意参与项目开发的伙伴参考。

## 外部插件开发者集成

目前尚未发布公开 Maven/Gradle 坐标，亦未向 Bukkit `ServicesManager` 注册。若要在自己的插件中调用，请直接引入本地编译生成的 jar 包：

```kotlin
dependencies {
    compileOnly(files("chunkland-plugin/build/libs/chunkland-plugin-0.1.0.jar"))
}
```

在代码中获取实例：

```java
ChunkLandPlugin plugin = (ChunkLandPlugin) Bukkit.getPluginManager().getPlugin("ChunkLand");
if (plugin == null || !plugin.isEnabled()) return; // 先做好防护：首次启用前 bus 会是 null

ChunkLandApi api = plugin.getReadApi();          // 永不为 null；插件未启用或停用时读取结果为空
ChunkLandEventBus bus = plugin.publicEventBus(); // 只有首次启用前才会是 null；之后每次启用都要重新获取
```

目前 `ChunkLandApi` 提供六个查询方法；其中 `can()` 与 `getRule()` 尚在对接中（暂时返回 stub），请先不要用于关键保护判定。完整公开接口约定请参考[API 参考](docs/zh-CN/reference/api.md)。

## 开源授权

项目采用 MIT 授权条款，完整内容请见 [LICENSE](LICENSE)。