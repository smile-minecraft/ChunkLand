[English](../en/developer-guide.md) · [繁體中文](../zh-TW/developer-guide.md) · [文档索引](README.md)

# ChunkLand 开发者指南

写给要在自己的插件里调用 ChunkLand，以及要在这份代码上动手的人。服主侧的安装配置在[服务器管理指南](server-guide.md)。

## 仓库结构

| 模块 | 负责 | 规矩 |
| --- | --- | --- |
| `chunkland-api` | 领域类型、`ChunkLandApi`、事件总线接口、几何、定价与金额类型 | 纯领域。生产 classpath 上不许有 Bukkit、SQL、AceLib |
| `chunkland-plugin` | 服务端插件：保护、持久化、命令、界面与表单、经济、配置 | 唯一接触 Bukkit 的模块 |

`chunkland-api` 上挂了一道构建检查，接进 `check` 和 `jar`：`chunkland-api` 声明了任何外部生产依赖就直接让构建失败。撞到这个错误，说明依赖放错模块了。

## 编译前先取 AceLib

AceLib 是编译期依赖，从本地缓存目录解析，所以 Gradle 配置项目之前它必须已经在那里：

```bash
./scripts/build-acelib.sh
```

脚本下载固定的 AceLib 1.3.0 GitHub Release 附件，然后核对固定的 SHA-256、`plugin.yml` 里的版本号，以及 `AceLibVersion.class` 是否存在。任何一项不对——摘要不符、版本不对、拿回来的不是 JAR、缺类——都以非零退出码中止。产物落在 `$XDG_CACHE_HOME/chunkland-acelib`（一般是 `~/.cache/chunkland-acelib`），要换位置设 `ACE_OUTPUT_DIR`。

AceLib 不走浮动版本：`SNAPSHOT`、`latest`、`mavenLocal()` 都没有用到。

仓库里另外三个脚本：`scripts/acelib-common.sh` 是上面那个脚本共用的来源与校验信息，`scripts/test-build-acelib.sh` 测脚本本身，`scripts/check-performance-gate.sh` 与 `scripts/verify-api-dependency-guard.sh` 是构建门禁。

## 构建与测试

```bash
./gradlew build --no-daemon --console=plain
```

`build` 会跑 `check`，所以测试套件一起跑。常用子集：

```bash
./gradlew :chunkland-api:test --no-daemon --console=plain
./gradlew :chunkland-plugin:test --no-daemon --console=plain
```

JavaDoc 带 doclint 生成：

```bash
./gradlew javadoc --no-daemon --console=plain
```

工具链是 Java 25，编码 UTF-8，测试跑 JUnit Platform。版本都写在 `gradle/libs.versions.toml` 里。

## 产物

- `chunkland-plugin/build/libs/chunkland-plugin-0.1.0.jar` — 交给服务器的那个。`chunkland-api`、SQLite、SnakeYAML 打在里面；编译期依赖（paper-api、AceLib、VaultAPI、LuckPerms API）不在里面，因为服务器提供了。
- `chunkland-api/build/libs/chunkland-api-0.1.0.jar` — 单独给外部调用方用。

归档构建关掉了文件时间戳、定了条目顺序，所以同一份源码在两台机器上构建出的 jar 逐字节相同。要守住这个性质，跑两次 `clean build` 比对 SHA-256 就行；加了写出非确定性内容的任务就会破。

## 依赖 ChunkLand：目前只能挂本地 jar

没有发布构件，没有 Maven 坐标，也没有注册到 `ServicesManager`，所以 Gradle 里只能指本地文件：

```kotlin
dependencies {
    compileOnly(files("chunkland-plugin/build/libs/chunkland-plugin-0.1.0.jar"))
}
```

```java
ChunkLandPlugin plugin = (ChunkLandPlugin) Bukkit.getPluginManager().getPlugin("ChunkLand");
if (plugin == null || !plugin.isEnabled()) return; // 先做好防护：首次启用前 bus 会是 null

ChunkLandApi api = plugin.getReadApi();          // 不会是 null；启用前与停用后读到的都是空
ChunkLandEventBus bus = plugin.publicEventBus(); // 只有首次启用前才会是 null；之后每次启用都要重新获取
```

读 API 有六个查询方法，事件总线有 `register`、`unregister`、`publish` 三个方法。两者的生命周期、方法语义、当前接上线到什么程度，都写在[API 参考](reference/api.md)里——尤其是 `can()` 与 `getRule()` 目前不接正式数据，不要拿它们当生产裁决依据。

## 动手前值得知道的两条不变量

**保护判定 fail-closed。** 注册表启动时为空，只有在启动重建发布出完整快照之后，占用查询才可读。在那之前，空注册表的含义是「未知」而不是「无主之地」，所以读者一律拒绝，而不是把看不见的持久领地当成空地放行。判定缓存的命中范围是单个策略世代，世代一变就自然落空。

**授权写入在事务内比对。** 领地默认值写入会在同一笔事务里，把它被展示时看到的授权世代与数据库当前值比对，不一致就整笔拒绝。内存里的授权来源——配置默认值、admin bypass、server-land 管理人——不参与这比对，只有提交写入前那道闸门看得到它们的撤销。

## Folia 线程

玩家、实体、方块和背包相关的调度都走 AceLib 的安全调度器，并校验线程上下文。区域线程这件事测试证明不了，真正的服务器才算数。

共享的 Folia 测试服在这个仓库之外。它的数据目录被服务器进程锁着，要直接读 SQLite 数据库、LuckPerms 数据或 CoreProtect 数据库之前先停服，否则走插件自己的指令。

## 注释和文档

- Java 25，四空格缩进，UTF-8。
- 公开类型和方法写 JavaDoc，`@param`、`@return`、`@throws` 填齐。
- Paper 与 Folia 行为不同的地方，JavaDoc 要写明调用方必须待在哪个线程或区域操作上。
- 注释解释约束为什么存在——那条不变量、那个正在避免的失败模式。不复述下一行在做什么，也不在注释里放追踪编号、任务标识或计划引用。

发布出去的文档规矩写在 `CONTRIBUTING.md`：三种语言同名对应，改一份就要同时改三份。

## 相关页面

- [文档索引](README.md)
- [API 参考](reference/api.md)
- [配置参考](reference/configuration.md) · [命令参考](reference/commands.md)
- [已知限制](limitations.md)