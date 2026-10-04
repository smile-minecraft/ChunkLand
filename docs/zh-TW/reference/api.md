[English](../../en/reference/api.md) · 繁體中文 · [简体中文](../../zh-CN/reference/api.md)

← [參考索引](README.md) · [文件索引](../README.md) · [指令參考](commands.md) · [權限參考](permissions.md) · [設定參考](configuration.md) · [API 參考](api.md)

# API 參考

這份文件提供外掛開發者在自有外掛中整合 ChunkLand 的完整 API 規格。包含實例獲取、唯讀快照查詢、事件監聽與目前的接線狀態。若需要的是遊戲內指令操作，請參考[玩家指南](../user-guide.md)。

## 現階段整合說明

API 已發布在 JitPack，座標是**根座標** `com.github.smile-minecraft:ChunkLand`，不是模組座標。該構件就是 API jar 本身：純領域介面與快照型別、`ChunkLandApi` 與事件匯流排介面，不含 Bukkit、SQL 或 AceLib。

ChunkLand 未向 Bukkit `ServicesManager` 註冊，`getRegistration(ChunkLandApi.class)` 一律回傳 `null`，實例只能從外掛實例取得。

```kotlin
repositories {
    maven { url = uri("https://jitpack.io") }
}

dependencies {
    compileOnly("com.github.smile-minecraft:ChunkLand:v0.1.0") // ChunkLandApi、事件匯流排介面、領域型別
    compileOnly(files("libs/chunkland-plugin-0.1.0.jar"))      // ChunkLandPlugin
}
```

在你自己的 `plugin.yml` 宣告 `depend: [ChunkLand]` 讓 ChunkLand 先載入，兩條相依都維持 `compileOnly`——伺服器已經提供這些類型，打包進去只會讓同一組型別出現兩份。

### 為什麼要掛兩個

[取得實例](#取得-api-實例)之後的兩個取用點都宣告在 `com.smile.chunkland.ChunkLandPlugin` 上，那個類別在**外掛 jar** 裡，不在 JitPack 構件裡。所以只要你的程式碼寫出 `ChunkLandPlugin` 這個型別——不論是 import、cast 還是區域變數宣告——就必須再掛一份外掛 jar，否則編譯期會找不到這個類別。從 [v0.1.0 Release](https://github.com/smile-minecraft/ChunkLand/releases/tag/v0.1.0) 下載 `chunkland-plugin-0.1.0.jar` 放進 `libs/` 即可；`SHA256SUMS` 有它的預期摘要值。

`Bukkit` 不在這兩個 jar 的任何一個裡，它來自你自己的 paper-api 編譯期相依。

外掛 jar 已內建 API 模組，所以已經在用它的人可以拿掉 JitPack 那條。反過來，只把 `ChunkLandApi` 與領域值傳來傳去、沒有用到 `ChunkLandPlugin` 的程式碼，單靠 JitPack 座標就編譯得過。

若要改用本機 jar 編譯，先從原始碼建置：

```bash
./scripts/build-acelib.sh          # 下載並校驗編譯所需的 AceLib 1.3.0
./gradlew build --no-daemon --console=plain
```

建置產物清單：

| 產物 | 輸出路徑 | 說明 |
| --- | --- | --- |
| 自帶相依外掛 jar | `chunkland-plugin/build/libs/chunkland-plugin-0.1.0.jar` | 內建 API 模組、SQLite、SnakeYAML，伺服器部署用 |
| API 模組 jar | `chunkland-api/build/libs/chunkland-api-0.1.0.jar` | 純領域介面與快照型別，供第三方外掛編譯期依賴 |
| API 原始碼 jar | `chunkland-api/build/libs/chunkland-api-0.1.0-sources.jar` | 包含 JavaDoc 與原始碼 |

指向 `chunkland-plugin/build/libs/chunkland-plugin-0.1.0.jar` 即可，它已內建 API 模組：`compileOnly(files("chunkland-plugin/build/libs/chunkland-plugin-0.1.0.jar"))`。

## 取得 API 實例

```java
ChunkLandPlugin plugin = (ChunkLandPlugin) Bukkit.getPluginManager().getPlugin("ChunkLand");
```

ChunkLand 在 `plugin.yml` 宣告的外掛名稱為 `ChunkLand`。請確保在 ChunkLand 完整啟用後再取得 API。

兩大取用點：

| 方法 | 回傳型別 | 生命週期與特性 |
| --- | --- | --- |
| `plugin.getReadApi()` | `ChunkLandApi` | **永不為 null**。外掛啟用前與停用後呼叫皆安全回傳空值 |
| `plugin.publicEventBus()` | `ChunkLandEventBus` | **只有首次啟用前才為 `null`**；停用後仍會回傳上一次啟用的舊 bus，它不報錯，但從此不再收到任何事件 |

`getReadApi()` 每次呼叫回傳一個輕量持有者（Holder），內部指向即時快照，配置變更無須手動重建即可取得最新資料。持有者會綁定當前的啟用世代：當外掛停用時世代失效，快取的持有者安全回傳空值，避免因殘留物件讀取到過期舊資料；再次啟用時會開闢全新世代。

`publicEventBus()` 每次啟用都會新建一條 bus，**請在每次啟用後重新取得並註冊 listener**，不要把 handle 帶過停用（舊 bus 不會拋例外，只會安靜地不再觸發）。null 防禦只需要覆蓋首次啟用前。

## 唯讀查詢 API

`ChunkLandApi` 提供六個查詢方法：

| 方法 | 回傳值 | 當前狀態 |
| --- | --- | --- |
| `getLandSnapshot(LandId)` | `Optional<LandSnapshot>` | 正式可用。領地不存在時回傳空值 |
| `getSubLandSnapshot(SubLandId)` | `Optional<SubLandSnapshot>` | 正式可用。子領地不存在時回傳空值 |
| `getOwner(LandId)` | `Optional<OwnerRef>` | 正式可用。由領地快照推導 |
| `getProtectionDepth(LandId)` | `Optional<Integer>` | 正式可用。回傳領地有效保護的最低 Y 座標 |
| `can(UUID, LandId, ProtectionActionType)` | `boolean` | **施工中：目前固定回傳 `false`（fail closed）** |
| `getRule(LandId, LandRuleType)` | `Optional<PermissionState>` | **施工中：目前固定回傳空 `Optional`** |

### 不要把 `can()` 當成保護裁決用

這是目前最關鍵的整合注意事項：`can()` 的內部資料來源目前固定回傳 `null`，因此每次呼叫都會走 fail-closed 安全分支回傳 `false`；`getRule()` 同理恆定回傳空 `Optional`。

這兩個方法目前處於 stub 階段，並非伺服器保護引擎的實際判定路徑。請勿將 `can()` 用於客製化 UI 顯示或保護裁決，否則會恆定得到拒絕結果。完整判定邏輯將隨後續版本對接。

### 呼叫約定與線程安全

| 約定面向 | 實作保證 |
| --- | --- |
| 執行緒安全 | 任何執行緒皆可安全呼叫 |
| I/O 承諾 | 全記憶體快照查詢，不執行任何阻塞性 I/O，絕不觸發區塊載入 |
| 回傳型別 | 不回傳可變的內部集合，一律回傳不可變快照（Record）或值物件 |

`LandId` 與 `SubLandId` 皆為包裝 `UUID` 的 Record，可直接透過 `new LandId(uuid)` 建立。`OwnerRef` 為 sealed 介面，提供 `OwnerRef.player(uuid)` 與 `OwnerRef.server()` 兩組工廠方法。

要查詢領地編號，可由 `/land inspect` 的輸出結果中獲取。

## 事件匯流排

`ChunkLandEventBus` 採**行程內同步分發**，非 Bukkit 原生事件管線：

| 方法 | 說明 |
| --- | --- |
| `register(Class<E>, ChunkLandEventListener<? super E>)` | 註冊監聽指定型別的領地事件 |
| `unregister(Class<E>, ChunkLandEventListener<? super E>)` | 取消監聽 |
| `publish(E)` | 在當前執行緒上同步派發給所有已註冊的監聽者 |

`publish` 會等待所有監聽者執行完畢後返回。發送端在持久化操作前發送 Pre 事件、在交易提交並發布快照後發送 Post 事件。

### 監聽器執行緒規範

監聽器**嚴禁執行阻塞性 I/O、長時間等待或休眠**，亦不得在對應的 Folia Region 執行緒之外存取世界。

### 例外處理行為

| 事件階段 | 監聽器拋出例外時的處置 |
| --- | --- |
| Pre 事件 | 整個變更觸發 Fail-Closed：發送端將該次操作標記為已取消，變更不產生任何資料庫副作用 |
| Post 事件 | 被安全捕獲、記錄日誌並隔離，已提交的資料庫交易維持成立 |

### 事件清單一覽

| 事件類別 | 是否可取消 | 實際派發狀態 |
| --- | --- | --- |
| `LandCreatePreEvent` / `LandCreatePostEvent` | 僅 Pre 可取消 | 正常發送 |
| `LandDeletePreEvent` / `LandDeletePostEvent` | 僅 Pre 可取消 | 正常發送 |
| `LandChunkAddPreEvent` / `LandChunkAddPostEvent` | 僅 Pre 可取消 | 正常發送 |
| `LandChunkRemovePreEvent` / `LandChunkRemovePostEvent` | 僅 Pre 可取消 | 正常發送 |
| `SubLandPreEvent` / `SubLandPostEvent` | 僅 Pre 可取消 | 正常發送 |
| `LandEnterEvent` | 否 | 正常發送 |
| `LandLeaveEvent` | 否 | 正常發送 |
| `SubLandEnterEvent` | 否 | 正常發送 |
| `SubLandLeaveEvent` | 否 | 正常發送 |
| `PermissionChangedEvent` | 否 | 正常發送 |
| `RuleChangedEvent` | 否 | **暫不發送**（目前尚無寫入路徑） |

### Bukkit 兼容事件視圖

針對未在編譯期引入 API jar 的外掛，ChunkLand 在 `com.smile.chunkland.event.bukkit` 套件下提供了 Bukkit 事件包裝視圖（如 `LandCreatePostBukkitEvent`）。所有視圖類別皆繼承自 `ChunkLandBukkitEvent`。

推薦優先使用原生的 `ChunkLandEventBus`，享有更純粹的領域事件定義。

## 領域模型型別

| 套件路徑 | 核心型別 |
| --- | --- |
| `api.land` | `LandId`、`SubLandId`、`LandSnapshot`、`SubLandSnapshot`、`OwnerRef`、`LandName`、`LandNameKey`、`ChunkKey`、`Cuboid`、`SubLandTopologyValidator`、`LandNameUniqueness` |
| `api.permission` | `ProtectionActionType`、`Permission`、`PermissionState`、`PermissionDecision`、`PermissionResolver`、`PermissionContext`、`PermissionSubject`、`PermissionBinding`、`DecisionSource`、`SubjectPermissionTier` |
| `api.rule` | `LandRuleType`、`LandRule` |
| `api.geometry` | `ChunkGeometry`、`BoundaryExtractor`、`BoundarySegment`、`Direction` |
| `api.money` | `Money`、`Currency`、`PricingTable`、`PricingTier`、`CostBasisCalculator`、`CostBasisAllocation`、`ChunkCoordinate` |
| `api.limit` | `LimitType`、`LimitResult`、`LimitSource`、`ExternalLimitProvider` |
| `api.mutation` | `ChunkLandMutations`、`MutationRequest`、`MutationKind`、`MutationResult`、`MutationOutcome` |
| `api.history` | `HistoryQuery`、`HistoryResult`、`HistoryEntry`、`WorldHistoryProvider` |

`ProtectionActionType` 包含 40 個列舉值，其中 22 個為 `SUBJECT_PERMISSION`（17 個日常成員動作 + 5 個管理動作），其餘 18 個為 `LAND_RULE`。

## 外部寫入邊界提示

目前版本尚未公開程序化圈地或變更的外部入口：
- `ChunkLandMutations` 型別雖然存在於 API 中，但外掛本體目前僅公開唯讀的 `getReadApi()` 與 `publicEventBus()`，未向外暴露 `mutations()` 入口。
- 請勿透過非公開內部類別強行呼叫寫入操作，所有領地變更請引導玩家經由遊戲內指令或 GUI 操作完成。