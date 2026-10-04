# 文件怎麼分工

ChunkLand 的公開文件依讀者正在做的事拆分。新增內容前，先把資訊放到負責的頁面，其他頁只留一句摘要與連結。

| 內容 | 更新位置 |
| --- | --- |
| 專案介紹、需求基線、安裝、第一個指令、兩件開服前要注意的預設值 | 根目錄 `README.md`／`README.zh-TW.md`／`README.zh-CN.md` |
| 圈地、成員與封鎖、子領地、管理介面、被拒絕時的行為 | `docs/<lang>/user-guide.md` |
| 安裝部署、設定檔、後備來源、備份、審計與孤兒資料 | `docs/<lang>/server-guide.md` |
| 從原始碼建置、依賴本機 jar、讀取 API、事件匯流排、Folia 執行緒 | `docs/<lang>/developer-guide.md` |
| `/land` 與 `/chunkland` 完整語法、玩家名稱解析順序 | `docs/<lang>/reference/commands.md` |
| `config.yml` 每個鍵的預設值、範圍與注意事項 | `docs/<lang>/reference/configuration.md` |
| 29 個權限節點與各自動到什麼 | `docs/<lang>/reference/permissions.md` |
| `ChunkLandApi` 六個方法、事件匯流排、領域型別、未接線的部分 | `docs/<lang>/reference/api.md` |
| 已知限制 | 根目錄 `LIMITATIONS.md`（繁中交付正本）、`docs/en/limitations.md`、`docs/zh-CN/limitations.md` |
| 版本歷史 | `CHANGELOG.md` |
| 建置、測試、程式不變條件、文件規則 | `CONTRIBUTING.md` |

## 三語結構

`docs/` 下有三個語系資料夾，檔名完全對應：`en`、`zh-TW`、`zh-CN`。每個資料夾都是 `README.md`、`user-guide.md`、`server-guide.md`、`developer-guide.md`、`limitations.md`，加上 `reference/` 底下的 `commands.md`、`configuration.md`、`permissions.md`、`api.md`。

- 每頁 H1 下方第一行是導航列，指向另外兩種語言的同一頁：`English · [繁體中文](../zh-TW/xxx.md) · [简体中文](../zh-CN/xxx.md)`。
- 英文版先寫，翻譯跟著改；三種語言內容不一致視為缺陷。
- 兩種語言分別起草，不要逐句對譯。術語一致的對象是「讀者在同一份文件裡讀到的詞」，不是另一種語言的句型。

## 寫法

- 標題直接寫讀者要做的事，例如「設定檔損壞時怎麼修」或「訂閱事件」。
- 先給可執行的步驟，再補原因與限制。
- 指令、設定鍵、程式碼要能複製；placeholder 使用容易辨認的值，例如 `<land_name>`、`<world-uuid>`。
- 保留 Folia、Java、AceLib、UUID、路徑與設定鍵等識別名稱，其餘用自然的該語言說法。
- 參考頁以表格為主，散文留給需要順序或理由的地方。
- 每頁結尾放「相關頁面」，列 1–3 個直接相關連結。

## 事實來源

規範性說法要能回到來源。以下依序可信度遞減：

1. `chunkland-plugin/src/main/resources/config.yml`、`plugin.yml`、`lang/*.yml` — 設定鍵、權限節點、指令語法的直接來源。指令語法以 `lang/<locale>.yml` 裡的 `usage` 字串為準，不要自行改寫。
2. 程式碼本身 — 尤其 `chunkland-plugin/src/main/java/com/smile/chunkland/command/` 底下的 handler 與 `LandPermissions.java` 的節點對應、`runtime/api/ChunkLandReadApi.java` 的實際接線。
3. `chunkland-api` 的公開介面與列舉 — 方法簽名、動作清單、規則清單。
4. `LIMITATIONS.md` — 已確認的行為與限制。

以下不得出現在公開文件：內部追蹤編號、里程碑或任務編號、企劃書章節引用、代理流程、驗收紀錄、一次性測試數量、本機絕對路徑、`/reload` 之類不存在的能力、尚未發布的下載位置或外部套件座標。

素材沒有提供的細節不要補。要嘛回到來源核對，要嘛標示為尚未驗證。

## 版本語境

`plugin.yml` 的 `api-version: '26.1.2'` 與編譯用的 paper-api 26.1.2 是技術標記，不是支援宣稱。受支援的執行環境只有 Folia 26.2。公開文件提到版本時要分清楚這兩者。

尚未發布任何版本，因此「取得方式」一律寫成從 checkout 建置，不要寫下載連結、外部座標或 Release asset。

## 已知的文件陷阱

- `chunkland-api` 的 `can()` 固定回 `false`、`getRule()` 固定回空，原因是 `getReadApi()` 注入的是 stub。文件必須明寫，不能讓讀者以為可以用。
- `plugin.publicEventBus()` 只在首次啟用前是 `null`；停用不會把它清成 `null`，舊 bus 仍會被回傳，但從此不再收到任何事件（不報錯、只是沒反應）。`getReadApi()` 則永不為 null、停用後讀到空，快取的舊持有者也不會在下次啟用時復活。兩者生命週期不同，文件要分開寫。
- `shrink` 與 `unclaim` 共用 `chunkland.command.land.shrink` 節點，容易漏掉。
- 權限節點共 29 個，全部 `default: op`。任何「玩家可以用」的說法都要搭配授權提醒。
- `rule-defaults` 區段尚未接線，寫成可調會誤導。