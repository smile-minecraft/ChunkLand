[English](../en/developer-guide.md) · 繁體中文 · [简体中文](../zh-CN/developer-guide.md)

← [文件索引](README.md) · [回到專案說明](../../README.zh-TW.md)

# 開發者指南

這份指南為有意參與 ChunkLand 核心開發或撰寫測試的開發者而寫，深入介紹專案的架構分層、相依邊界防護與 Folia 多執行緒設計原則。如果你僅需要在**自製外掛中呼叫** ChunkLand，請直接查閱[API 參考](reference/api.md)。

## 倉庫模組劃分

| 模組 | 職責與內容 | 允許的正式相依 |
| --- | --- | --- |
| `chunkland-api` | 純領域模型與公開介面：權限動作、判定鏈、事件定義、領地快照、幾何計算與計價型別 | **嚴禁宣告任何外部正式相依** |
| `chunkland-plugin` | 伺服器外掛本體實作：指令系統、保護監聽器、圖形介面、持久化儲存、訊息管線與選配整合 | SQLite、SnakeYAML；編譯期可見 paper-api、AceLib、Vault、LuckPerms |

專案 Gradle group 為 `com.smile.chunkland`，版本鎖定 `0.1.0`，編譯工具鏈採 Java 25。外部相依版本統一在 `gradle/libs.versions.toml` 管理，不散落於個別子專案。

### 為什麼堅持雙模組分離

`chunkland-api` 的核心哲學是「不依賴 Minecraft 引擎也能完整表達領地領域模型」。權限判定鏈、事件定義、幾何計算與快照型別全數封裝在此，完全隔離了 Bukkit、SQL 或 AceLib。

這種純領域設計帶來了極高的可測試性：所有權限解析邏輯（如 `PermissionResolver`）都能在完全不啟動 Mock Bukkit 伺服器的情況下，直接由毫秒級的 JUnit 5 單元測試完整驗證。

### 機械化守護 API 相依邊界

程式碼審查難免有疏漏，因此我們透過 Gradle 任務實作了機械化邊界防護。若 `chunkland-api` 被宣告了任何外部正式相依，`check` 與 `jar` 任務會直接報錯中斷建置，並精確列出違規的相依項目。

此項檢查掛載在 `jar` 任務上，即使 API 模組是經由外掛模組間接觸發建置，邊界守衛依然會嚴格攔截。檢查僅針對正式產出，`testImplementation` 這類測試相依不受此限。

你也可以隨時在命令列直接執行檢驗腳本：

```bash
./scripts/verify-api-dependency-guard.sh
```

**擴充公開介面的準則**：純值物件與業務介面應放入 `chunkland-api`；一旦發現邏輯需要碰觸 Bukkit、SQL 資料庫或 AceLib，代表該職責屬於 `chunkland-plugin`。應將抽象介面定義於 API 模組，再由外掛模組負責具體實作。

## 建置與測試流程

```bash
./scripts/build-acelib.sh          # 下載並校驗編譯所需的 AceLib 1.3.0
./gradlew build --no-daemon --console=plain
```

| 指令 | 用途說明 |
| --- | --- |
| `./gradlew build` | 完整建置：編譯、執行測試套件、驗證架構邊界並打包 jar |
| `./gradlew test` | 僅執行單元測試 |
| `./gradlew :chunkland-api:check` | 單獨驗證 API 模組的相依邊界 |
| `./scripts/check-performance-gate.sh` | 執行效能門檻驗證腳本 |

測試框架採用 JUnit 5。`chunkland-plugin` 的測試會直接驅動 Bukkit 生命週期接縫與 AceLib 公開介面，這兩項在測試 classpath 上皆立即可用，但絕不會被打包進正式 jar 檔。

### 可重現建置（Reproducible Builds）

建置腳本移除了封存的時間戳並固定檔案順序，確保同一份原始碼在任何環境編譯皆能產出位元完全一致的 jar 檔（可直接透過 SHA-256 校驗）。若日後擴充 Gradle 任務，請避免引入非確定性的時間或檔案列舉操作。

## 自帶相依的外掛 Jar

`chunkland-plugin` 的 `jar` 任務打包了完整的 `runtimeClasspath`：包含 API 模組、sqlite-jdbc、snakeyaml 及其傳遞相依。伺服器端僅需放置此 jar 即可運作。

`paper-api` 與 AceLib 設定為伺服器環境提供（`compileOnly`），不會被打入成品。`jar` 任務嚴格相依於 `runtimeClasspath` 設定物件，確保在乾淨建置或 CI 環境中，API 模組的建置任務一定優先完成，杜絕遺漏或打包過期舊檔案的風險。

## 權限動作的三重註冊

新增一項領地保護動作時，必須在以下三個位置同步定義，缺一不可：

| 位置 | 負責內容 |
| --- | --- |
| `chunkland-api` 的 `ProtectionActionType` | 動作列舉定義，並標明屬於 `SUBJECT_PERMISSION`、`LAND_RULE` 或 `COMBINED` |
| `lang/en_US.yml` 與 `lang/zh_TW.yml` | `permission.action.<名字>` 的雙語顯示文案 |
| 執行期路徑 | 判定進入點、以及將 Bukkit 事件轉譯為該動作的監聽器 |

動作類型決定了判定鏈走向：
- `SUBJECT_PERMISSION`：完整走訪玩家權限鏈，並套用地主特權保障。
- `LAND_RULE`：走訪環境規則鏈，**刻意不套用地主特權**（確保環境機制如防爆對地主同樣生效）。

指令輸入的動作名稱不區分大小寫，解析時會先修剪頭尾空白並轉為大寫列舉比對（例如 `/land explain block_break` 與 `BLOCK_BREAK` 效果相同）。不在列舉中的自訂名稱一律解析為空，觸發安全拒絕。

## 指令子系統設計架構

`/land` 採用確定性派發：子指令清單與順序固定，未定義指令直接回覆「未知子指令」。管理類指令會在派發層先檢查基礎指令節點，通過後再將管理動作交給領地層的管理閘門。

**管理閘門的參數由外部解析器準備，但閘門檢驗本身在派發層強制執行**，從架構上杜絕解析器越權授權的漏洞。解析器若回傳 null、空值或拋出例外，一律 fail closed 安全拒絕。

### 誠實面對未接線路徑

外掛對於尚未對接完成的功能採取清晰誠實的回覆策略：

| 情形 | 系統回覆方式 |
| --- | --- |
| 該子指令在此建置中尚未實作處理器 | 回傳 `command.land.not_yet`，附帶子指令名稱 |
| 領域邏輯已完成，但本建置尚未掛接 mutation 執行器 | 回傳對應子指令的 `failed` 訊息，附帶精確的 reason code（如 `expand.unavailable`） |

清晰區分「尚未開發」與「此路徑目前不可用」，避免讓使用者產生功能即將就緒的誤解。所有提示文字統一透過 `ReplySink` 與 `lang/*.yml` 派發，禁止在程式中寫死文字字面量。

### 權限節點的同步維護

新增子指令時，以下三處必須保持同步：
1. `plugin.yml` 的 `permissions` 聲明
2. `LandPermissions` 的常數與對照表
3. `/land` 的子指令註冊表

ChunkLand 不設計隱式的全域萬用字元頂層節點，每一個註冊指令都有專屬明確的節點把關。

### 查詢指令的防資訊探測設計

為了防止未授權訪客透過指令刺探領地資訊，`explain` 與 `inspect` 在派發層的所有拒絕分支皆回傳相同的靜態拒絕訊息。無論是權限不足、閘門擋下、目標領地不存在或解析錯誤，絕不透露目標領地是否存在，保障地主隱私。

## GUI 與多平台表單文案注入

GUI 邏輯本體不直接查詢語系檔，所有介面文案皆由呼叫端注入的字串提供者供應，使視窗與表單元件能在純粹的無狀態環境下獨立執行單元測試。

針對基岩版（Bedrock）玩家，Modal Form 與 Java 版的箱子介面分開處理。若特定步驟尚未提供基岩版表單，系統會友善提示玩家切換為聊天指令，避免顯示異常破碎的介面。

## 軟相依整合原則

Vault、LuckPerms 與 CoreProtect 均為軟相依整合。這三項服務的探索皆在**每次啟用世代（Enable Generation）初始化時執行一次**，並將參考快取供後續呼叫；任何一項缺席或載入異常一律平順降級為空，絕不向上拋出例外導致開服崩潰。

各相依缺席時的分工明確：
- **Vault 經濟**：購買開啟（`economy.enabled: true`）時計價安全關閉（fail closed），圈地與擴張安全攔阻；購買關閉（預設）時圈地與擴張免費，不經過 Vault。
- **LuckPerms**：額度計算退回設定檔靜態預設值。
- **CoreProtect**：`/land history` 提示服務暫不可用，**核心保護判定完全不受任何影響**。

## Folia 多執行緒運作原則

在 Folia 架構下開發，**絕不可假設存在單一 Bukkit 主執行緒**。所有涉及世界、區塊與實體的邏輯，呼叫端必須自行確保運行於正確的 Region 執行緒上下文；而純領域邏輯則維持無 Bukkit 狀態獨立運算。

對外的事件匯流排採**行程內同步發送**：
- 監聽器**嚴禁執行阻塞性 I/O、長時間等待或休眠**，亦不得跨越非當前的 Folia 執行緒存取世界。
- Pre 事件監聽器若拋出例外，將觸發 fail closed 終止整個變更交易。
- Post 事件監聽器拋出例外會被安全捕獲並記錄日誌，已提交的資料庫交易不受干擾。

## 待完成接線清單

為維持透明度，以下列出目前刻意保留接線槽位的元件：

| 元件位置 | 當前狀態說明 |
| --- | --- |
| `chunkland-api` 的 `ChunkLandApi.can()` | 權限脈絡來源恆定回傳 `null`，目前固定回傳 `false`（fail-closed stub） |
| `ChunkLandApi.getRule()` | 規則查詢來源恆定回傳空 `Optional` |
| `config.yml` 的 `rule-defaults` 區段 | 僅保留註解範例，尚未連結內部規則引擎 |
| `ChunkLandPlugin` 設定重載機制 | 保留內部重載 API，尚未對接玩家或管理指令 |

前兩項 API 目前為 stub，切勿用於正式的保護裁決或使用者介面顯示，詳細架構考量請見[API 參考](reference/api.md#不要把-can-當成保護裁決用)。