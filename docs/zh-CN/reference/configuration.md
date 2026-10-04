[English](../../en/reference/configuration.md) · [繁體中文](../../zh-TW/reference/configuration.md) · [参考索引](README.md)

← [文档索引](../README.md) · [项目说明](../../../README.zh-CN.md) · [命令参考](commands.md) · [权限参考](permissions.md) · [配置参考](configuration.md) · [API 参考](api.md)

# 配置参考

配置文件在 `plugins/ChunkLand/config.yml`，由插件首次启动时生成，之后每次启动都读取它。

**改完一定要重启服务器才生效。** ChunkLand 没有 reload 指令，Bukkit 的 `/reload` 也不支持。加载成功时会把内容复制到 `config-last-known-good.yml`。

配置文件损坏或遗失时的行为与修复步骤，写在[服务器管理指南](../server-guide.md#配置文件坏了怎么办)。

## 写错会怎样

**未知的顶层键会在加载时被拒绝**，不会被安静忽略。打错字或从别处贴错设置，宁可让服务器以明显的方式失败，也不要默默吃掉一行。

每个区段都是各自型别化的，不是一个大杂块。少了某个区段，那个区段的所有键就用内置默认值；有写错的值就整份加载失败，沿用上一次可用的设定快照。

下面每一节列出该区段支持的键。

## messages

| 键 | 默认值 | 说明 |
| --- | --- | --- |
| `default-locale` | `en_US` | 玩家没有可用语系时使用的语言 |
| `cooldown-seconds` | `2` | 同一条消息对同一位玩家的重复间隔秒数 |

语言文件本身在 `plugins/ChunkLand/lang/`，第一次启动会产生 `en_US.yml` 与 `zh_TW.yml`。

## audit

| 键 | 默认值 | 说明 |
| --- | --- | --- |
| `retention-days` | `180` | 审计记录保留天数。`0` 表示永久保留，不会执行任何清理 |

清理只删除**严格早于**截止时间的审计记录，而且在持久化线程上分批进行。领地、区块与账本的记录永远不会被动到；已删除领地的历史在它自己的截止时间到期前都还读得到。

## economy

圈地计价的完整设置。这个区段一旦存在就必须完整且精确：未知键、缺少 currency 或 pricing、空白代码、超范围的 scale、非正数或非整数的界、重叠的区间、顶层不是 `unbounded`、负数或溢出的价格，全部都会在加载时失败并保留前一次快照——玩家圈地会因此被挡下，而不是被悄悄改价。

| 键 | 默认值 | 说明 |
| --- | --- | --- |
| `enabled` | `false` | 购买开关。`false` 时圈地与扩张不收费，也不需要经济插件 |
| `currency.code` | `EMC` | 所有价格存储与扣款的货币代码 |
| `currency.scale` | `2` | 小数位数指数。`2` 表示 100 个最小单位等于 1 个主要单位 |

`pricing.tiers` 用**拥有者的总区块数**分级计价，也就是「前一级的 `until`（不含）到这一级的 `until`（含）」之间的每一个区块，各是多少主要单位。计价只看玩家领地的总区块数，不含服务器领地。

默认的分级：

```yaml
economy:
  enabled: false
  currency:
    code: EMC
    scale: 2
  pricing:
    tiers:
      - until: 20
        price-per-chunk: 1.00
      - until: 50
        price-per-chunk: 2.00
      - until: 100
        price-per-chunk: 4.00
      - until: unbounded
        price-per-chunk: 8.00
```

分级必须是一段没有缝隙、没有重叠、且最后一档是 `unbounded` 的连续分割。

**退款不看这张表。** 退款一律从持久化的每区块成本基础计算，所以之后调整分级不会改变过去任何一笔付款的退款金额。

**不要在账本还有有效记录时更改 `currency.scale`。** 存下来的是最小单位，改了 scale 会让同一笔余额换算成不同的主要金额。

**`enabled` 决定要不要收费。** 默认是 `false`：圈地与扩张免费，不需要安装经济插件，玩家能圈多少只受 `limits` 限制；这样圈到的区块成本为零，缩减或删除时也没有退款。改成 `true` 之后才按上面的分级收费，此时必须安装 Vault 系经济插件，否则玩家圈地会被挡下。

不论开关是哪一边，货币与分级都会照常校验。开启期间买下的区块会保留当时记录的价格，之后即使把开关关掉，缩减或删除仍会按那笔金额退款，所以在已上线的服务器关闭购买时，请保留经济插件。没有经济插件时，仍欠着退款金额的领地在动任何资料之前就会先被挡下，不会先删掉土地再留下来等人工处理。

整个 `economy` 区段不存在的话，行为等同 `enabled: false`。

## limits

| 键 | 默认值 | 说明 |
| --- | --- | --- |
| `max-lands-per-player` | `5` | 每位玩家的领地数上限 |
| `max-total-chunks-per-player` | `10` | 每位玩家的领地总区块数上限 |
| `max-chunks-per-land` | `10` | 单一领地的区块数上限 |
| `max-sublands-per-land` | `16` | 单一领地的子领地数上限 |
| `max-selection-side-length` | `32` | 选取范围单边的区块数上限 |
| `max-selection-chunks` | `1024` | 单次选取范围的区块总数上限 |
| `max-decision-cache-entries` | `4096` | 保护判定的快取预算（只在内存中，每次启用世代重新计算）。`0` 关闭重用，每次判断都重算 |

快取在世代不变时会重用同一个判定结果，任何世代移动都自然失效。

## selection

| 键 | 默认值 | 有效范围 | 说明 |
| --- | --- | --- | --- |
| `session-timeout-seconds` | `600` | — | 选取工作阶段多久没动作就结束 |
| `visualization-max-segments` | `256` | 1–4096 | 每影格保留的边界线段数量。超过时保留排序后的前段 |
| `visualization-max-particles-per-tick` | `512` | 1–1024 | 每个算绘影格送出的粒子数，视窗会轮替 |
| `visualization-render-distance-blocks` | `64` | 8–128 | 超过这个观看距离的线段会被略过 |
| `visualization-refresh-interval-ticks` | `10` | 1–200 | 两次算绘之间间隔的刻数 |

这四个可视化键是玩家范围的，只送粒子，不查地形资料。

## feedback

拒绝反馈的全部设置。这里的每一项**只影响被拒绝的玩家看到什么**，不改变任何判定结果。

进入被拒时画的红色边界：

| 键 | 默认值 | 有效范围 | 说明 |
| --- | --- | --- | --- |
| `entry-wall-enabled` | `true` | — | 是否要画这个边界 |
| `entry-wall-radius-blocks` | `6` | 2–16 | 边界画在玩家周围多远的位置 |
| `entry-wall-particle-size-percent` | `160` | 25–400 | 粒子大小，`100` 是原版大小 |
| `entry-wall-points-per-block` | `2` | 1–4 | 每格方块沿边界放几个粒子 |
| `entry-wall-cooldown-millis` | `2000` | 100–60000 | 对同一位玩家两次画边界之间的安静时间 |

被拒绝破坏、放置或使用时，标在方块（生物则是画环）上的外框：

| 键 | 默认值 | 有效范围 | 说明 |
| --- | --- | --- | --- |
| `action-mark-enabled` | `true` | — | 是否要画这个标记 |
| `action-mark-particle-size-percent` | `110` | 25–400 | 粒子大小 |
| `action-mark-cooldown-millis` | `400` | 100–60000 | 对同一位玩家两次标记之间的安静时间 |

被拒绝的玩家会被推回去多远：

| 键 | 默认值 | 有效范围 | 说明 |
| --- | --- | --- | --- |
| `push-out-distance-blocks` | `3` | 1–8 | 边界与落点之间的格数 |
| `push-out-cooldown-millis` | `500` | 100–60000 | 对同一位玩家两次推回之间的安静时间 |

冷却时间不要设得比玩家走回来的时间长。走 3 格大约 500 毫秒，冷却设太高会让玩家一直被卡在取消的移动上，画面看起来会一顿一顿的。

落点只会用已经加载、且与当前区域一致的区块，插件不会为了找个地方把区块加载起来。找不到安全落点时退回进入前的位置，再不行就只取消这次移动。

## worlds

没列在这里的世界沿用默认：`claim-enabled: true`、`vertical-mode: PER_CHUNK_DEPTH`。

| 键 | 默认值 | 说明 |
| --- | --- | --- |
| `<世界名称>.claim-enabled` | `true` | 关闭后该世界不能新圈地，**既有领地仍然受保护** |
| `<世界名称>.vertical-mode` | `PER_CHUNK_DEPTH` | 有效保护深度的读取方式 |

默认文件已经把三个常见世界写进来：

```yaml
worlds:
  world:
    claim-enabled: true
    vertical-mode: PER_CHUNK_DEPTH
  world_nether:
    claim-enabled: false
  world_the_end:
    claim-enabled: false
```

世界名称**大小写敏感**。

### vertical-mode 的两个值

| 值 | 行为 |
| --- | --- |
| `PER_CHUNK_DEPTH` | 每个区块各自从储存的最低保护高度解析有效深度 |
| `FULL_HEIGHT` | 有效深度解析成世界的最低高度，同时仍然保存原本的储存值，以便之后切回去 |

切换模式**只影响有效值的读取**，永远不会迁移或改写已经存好的资料。

## subject-defaults

回答「这个玩家能不能做这个动作」，是 Direct Trust 的基础。

| 位置 | 说明 |
| --- | --- |
| `subject-defaults.global.<动作>` | 全域默认值 |
| `subject-defaults.worlds.<世界名称>.<动作>` | 单一世界的默认值 |

默认值只有 `ENTRY: ALLOW`：

```yaml
subject-defaults:
  global:
    ENTRY: ALLOW
```

这个默认代表**没有被封禁的陌生玩家可以走进已经加载的领地**。`/land ban` 仍然挡得住，因为封禁在领地绑定层以拒绝优先，效力高于世界与全域默认值。想把领地关给陌生人，改成 `ENTRY: DENY`。

缺少区段、缺少该项，或明确写 `INHERIT`，三者的意思都是「往下一层解析」，一路问到最后就是隐含拒绝。

## rule-defaults

回答「这个机制在这里能不能发生」，是每个 `LAND_RULE` 的默认值。

**这个区段目前没有接上任何实际功能。** 默认文件里的 `rule-defaults` 区块整段被注解起来，写进去不会改变任何行为。十一项领地规则目前一律使用内置默认：水流、活塞、漏斗传输与生物生成在领地内放行，PVP、爆炸、火焰、生物破坏挡下，活塞、液体、漏斗跨越领地边界一律挡下；没有玩家或管理员界面可以调整。完整说明在[已知限制](../limitations.md)。

```
#rule-defaults:
#  global:
#    PVP: DENY
#  worlds:
#    world:
#      PVP: ALLOW
```

`subject-defaults` 与 `rule-defaults` 是两个各自型别化的命名空间，**永远不会互相回退**。领地绑定、领地默认值与子领地这三层不在这里设置（还没有可持久化的来源），一律维持 `INHERIT`。

## 语言文件

`plugins/ChunkLand/lang/` 底下有 `en_US.yml` 与 `zh_TW.yml`。它们只在缺档时写入，所以你改过的内容会留到升级之后。新版本加入新键时，已有的键保留你的值，新键则采用内置默认值。删掉文件就会重新生成。

## 相关页面

[服务器管理指南](../server-guide.md) ·
[命令参考](commands.md) ·
[权限参考](permissions.md) ·
[API 参考](api.md)