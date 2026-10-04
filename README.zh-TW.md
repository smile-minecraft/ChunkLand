[English](README.md) · 繁體中文 · [简体中文](README.zh-CN.md)

![ChunkLand 示意圖：在 Folia 伺服器上以區塊為單位的領地，每塊領地都有不同顏色的邊界](docs/assets/banner.png)

# ChunkLand

ChunkLand 是專為 **Folia 26.2** 多執行緒伺服器設計的區塊領地保護外掛。玩家只要拿起魔杖圈出範圍，無論是破壞、放置、開箱、紅石互動還是 PVP 戰鬥，每一次動作都會在發生的當下即時判定，兼顧領地安全與多執行緒效能。

專案目前處於 **0.1.0** 開發階段，尚未發布預編譯好的 Release 檔案；想在伺服器上試用，請直接從原始碼建置 jar 檔（步驟見下方[安裝說明](#安裝)）。

## 保護涵蓋範圍

ChunkLand 的核心邏輯很純粹：**在特定的位置，某位玩家（或機制）究竟能不能執行該動作？** 目前判定的範圍涵蓋：

- **玩家日常操作**：方塊破壞與放置、容器存取（箱子、熔爐、釀造台）、紅石機關（門、按鈕、拉桿）、桶子與載具、生物互動與傷害、裝飾物件（展示框、盔甲架、懸掛物）、踩踏農田、PVP 與進入領地。
- **跨界機制防護**：活塞推拉、水流蔓延、漏斗物品傳輸，以及跨邊界推送物品的發射器。
- **即時投射物判定**：箭矢與三叉戟等投射物一律在「命中目標當下」判定，不以射擊者當初站在哪裡為準。

![ChunkLand 邊界示意圖：兩個相鄰區塊之間的領地邊界，兩側分屬不同領地](docs/assets/land-boundaries.png)

### 功能一覽

![圈地三步驟：拿魔杖點兩個角落、選取範圍自動對齊整個區塊、輸入領地名稱並在聊天欄確認](docs/assets/claiming.png)

![領地不必是矩形：先圈一塊矩形，再選取更多區塊並擴張，領地就能變成任何相連的形狀；區塊必須以邊相連，領地中間不能有洞](docs/assets/land-shapes.png)

![誰能做什麼：陌生玩家只能進入，受信任的玩家可以進入、使用與建造，地主另外可以管理，被封鎖的玩家什麼都不能做](docs/assets/permissions.png)

![領地內的機制：水與岩漿、活塞、漏斗、生物生成照原版運作；PVP、爆炸、火焰蔓延、生物破壞被擋下](docs/assets/land-rules.png)

![一塊領地裡有商店與農場兩個子領地，各自有獨立的權限與進出提示](docs/assets/sublands.png)

*注：以上圖片皆為概念示意圖，不是伺服器實機截圖。邊界圖展示相鄰兩塊領地與邊界會擋下什麼；其餘五張依序說明圈地步驟、領地可以長成任意形狀、誰能做什麼、領地內哪些機制照常運作，以及子領地，內容皆以隨附的預設值為準。*

## 環境需求

| 項目 | 版本 | 說明 |
| --- | --- | --- |
| 伺服器核心 | Folia 26.2 | 必備，不支援標準 Paper 或其他分支 |
| Java 執行環境 | Java 25 | 必備 |
| 基礎前置外掛 | AceLib 1.3.0 | 必備，缺少則無法啟動外掛 |
| 選配相依 | Vault 系經濟外掛、LuckPerms、CoreProtect | 選配：經濟外掛支援圈地計價、LuckPerms 提供額度中繼資料、CoreProtect 支援 `/land history` |

特別注意：**ChunkLand 僅支援 Folia 26.2**。外掛描述檔與編譯設定中標記的 `26.1.2` 僅為相依建置所需，不代表相容舊版本或 Paper。

## 安裝

1. 將 AceLib 1.3.0 放入伺服器的 `plugins/` 目錄。ChunkLand 宣告了 `depend: [AceLib]`，缺少前置時伺服器會拒絕載入。
2. 建置外掛 jar 檔並複製至 `plugins/`：

   ```bash
   ./scripts/build-acelib.sh          # 下載並驗證編譯所需的 AceLib 1.3.0
   ./gradlew build --no-daemon --console=plain
   ```

   編譯產物位於 `chunkland-plugin/build/libs/chunkland-plugin-0.1.0.jar`。此 jar 檔已內建相依（API 模組、SQLite、SnakeYAML），不需要額外在 `plugins/` 補放其他執行庫。
3. 啟動伺服器。首次載入會自動生成 `plugins/ChunkLand/` 目錄，內含預設 `config.yml`、語系檔（`lang/en_US.yml`、`lang/zh_TW.yml`）以及 SQLite 資料庫 `chunkland.db`。
4. 依伺服器需求微調 `config.yml` 後重啟伺服器。**Folia 環境下不提供 reload 指令**，任何設定變更皆需重啟伺服器生效。

更詳細的服主維運指南（包含設定檔損毀時的自動還原機制）請參閱[伺服器管理指南](docs/zh-TW/server-guide.md)。

## 第一次圈地

只要簡單三步即可完成保護：

```
/land wand          # 領取選區魔杖（預設為木棍）
                    # 左鍵點擊第一角，右鍵點擊對角形成選區
/land claim Home    # 設定領地名稱；聊天欄會顯示確認資訊，直接點擊 [確認] 即可
```

圈地完成後，可用 `/land inspect` 查看詳細資訊，或透過 `/land explain BLOCK_BREAK` 即時排查某個動作在該領地的判定結果。

選區魔杖、多數領地管理指令與圖形介面都需要玩家親自站在目標領地內操作。完整語法請參考[指令參考](docs/zh-TW/reference/commands.md)。

## 開服必看：剛裝好時玩家預設沒有指令權限

為了避免伺服器剛裝好就被隨意圈地，所有權限節點預設皆鎖定給管理員（`default: op`）。也就是說，**一般玩家剛進服時是無法圈地、信任朋友或打開管理介面的**。

服主需透過 LuckPerms 等權限外掛將節點指派給玩家或群組，大家才能開始圈地。常用的 29 個節點請參考[權限參考](docs/zh-TW/reference/permissions.md)；其中 `chunkland.admin.*` 與 `chunkland.debug.*` 請務必保留給管理團隊。

## 正式營運前建議留意的兩個預設值

1. **陌生玩家預設可以走進領地**：設定檔中 `subject-defaults.global.ENTRY: ALLOW` 代表未受信任的路人也能進入已載入的領地（不過 `/land ban` 依然能個別阻擋）。若希望打造完全封閉的私人領地，請改為：
   ```yaml
   subject-defaults:
     global:
       ENTRY: DENY
   ```
2. **十一項領地規則目前由系統預設託管**：包含 PVP、爆炸、火焰蔓延、生物破壞、水流、活塞與漏斗跨界等機制，目前尚未開放指令或介面調整，一律採用內建預設：水流、活塞、漏斗傳輸與敵對／被動生物生成在領地內照原版運作；PVP、爆炸、火焰蔓延與燒毀、生物破壞一律攔阻。活塞、液體、漏斗只要跨越領地邊界（不論進出）一律攔阻。`config.yml` 中的 `rule-defaults` 註解區塊暫未接線。

其餘已知的運行特性（例如點火行為未受保護、`/tp` 傳送不受攔阻、被封鎖者在下次移動時推回等），請查閱 [LIMITATIONS.md](LIMITATIONS.md)。

## 說明文件一覽

完整文件提供繁體中文、簡體中文與英文三種版本，統一收錄於 `docs/`：

| 你的角色 | 推薦從這裡開始 |
| --- | --- |
| 想要圈地保護基地的玩家 | [玩家指南](docs/zh-TW/user-guide.md) |
| 負責開服維護與調優的服主 | [伺服器管理指南](docs/zh-TW/server-guide.md) |
| 想要串接 API 的外掛開發者 | [開發者指南](docs/zh-TW/developer-guide.md) |
| 想查指令、設定參數或權限 | [參考資料索引](docs/zh-TW/reference/) |
| 想先掌握已知問題與邊界條件 | [已知限制](docs/zh-TW/limitations.md) |

其他資源：[CHANGELOG.md](CHANGELOG.md) 記錄各版本變更細節，[llms.txt](llms.txt) 為大型語言模型索引，[CONTRIBUTING.md](CONTRIBUTING.md) 供有意參與專案開發的夥伴參考。

## 外掛開發者整合

目前尚未發布公開 Maven/Gradle 座標，亦未向 Bukkit `ServicesManager` 註冊。若要在自己的外掛中取用，請直接引文本機編譯產出的 jar 檔：

```kotlin
dependencies {
    compileOnly(files("chunkland-plugin/build/libs/chunkland-plugin-0.1.0.jar"))
}
```

在程式碼中取得實例：

```java
ChunkLandPlugin plugin = (ChunkLandPlugin) Bukkit.getPluginManager().getPlugin("ChunkLand");
if (plugin == null || !plugin.isEnabled()) return; // 先做好防禦：首次啟用前 bus 會是 null

ChunkLandApi api = plugin.getReadApi();          // 永不為 null；外掛未啟用或停用時讀取結果為空
ChunkLandEventBus bus = plugin.publicEventBus(); // 只有首次啟用前才會是 null；之後每次啟用都要重新取得
```

目前 `ChunkLandApi` 提供六個查詢方法；其中 `can()` 與 `getRule()` 尚在對接中（暫時回傳 stub），請先不要用於關鍵保護判定。完整公開介面約定請參考[API 參考](docs/zh-TW/reference/api.md)。

## 開源授權

專案採用 MIT 授權條款，完整內容請見 [LICENSE](LICENSE)。