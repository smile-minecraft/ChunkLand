[English](../en/README.md) · 繁體中文 · [简体中文](../zh-CN/README.md)

← [回到專案說明](../../README.zh-TW.md)

# ChunkLand 說明文件

歡迎閱讀 ChunkLand 官方文檔！ChunkLand 是專為 **Folia 26.2** 多執行緒伺服器量身打造的區塊領地保護外掛。無論是方塊破壞、容器開關、紅石交互還是 PVP 戰鬥，系統皆在動作發生的當下即時判定，兼顧領地安全與多執行緒伺服器的高效能。

目前正式版是 **0.1.0**，可從 [GitHub Release](https://github.com/smile-minecraft/ChunkLand/releases/tag/v0.1.0) 下載插件 jar。若要在伺服器上安裝，請見[伺服器管理指南](server-guide.md)。

## 依你的需求開始閱讀

| 你的目標 | 推薦指南 |
| --- | --- |
| 學習如何圈地、管理權限、邀請隊友一同建築 | [玩家指南](user-guide.md) |
| 安裝部署外掛、調優伺服器參數、設定容錯備援 | [伺服器管理指南](server-guide.md) |
| 在自製外掛中取得領地快照或監聽領地事件 | [開發者指南](developer-guide.md) |
| 查詢全部指令語法、設定鍵、權限節點與 API | [參考資料索引](reference/) |
| 了解當前版本的行為邊界與已知運行特性 | [已知限制](limitations.md) |

## 核心參考手冊

完整規格與語法整理於[參考資料目錄](reference/README.md)：

| 參考手冊 | 主要內容 |
| --- | --- |
| [指令參考](reference/commands.md) | `/land` 旗下 25 個子指令語法與 `/chunkland` 診斷工具 |
| [權限參考](reference/permissions.md) | 29 個權限節點清單、管理閘門檢驗邏輯與玩家建議配置 |
| [設定參考](reference/configuration.md) | `config.yml` 每個設定區段的預設值、驗證邊界與調優說明 |
| [API 參考](reference/api.md) | 公開唯讀查詢 API、事件匯流排規格與架構邊界 |

## 執行環境規格

| 項目 | 支援版本 | 說明 |
| --- | --- | --- |
| 伺服器核心 | Folia 26.2 | 必備。僅支援 Folia 多執行緒架構 |
| Java 執行環境 | Java 25 | 必備 |
| 基礎前置外掛 | AceLib 1.3.0 | 必備。缺少則外掛無法啟動 |
| 選配相依 | Vault 系經濟外掛、LuckPerms、CoreProtect | 選配：經濟外掛支援圈地計價、LuckPerms 提供額度中繼資料、CoreProtect 支援 `/land history` |

特別注意：**ChunkLand 僅支援 Folia 26.2**。外掛描述檔與編譯設定中標記的 `26.1.2` 僅為相依標記，不代表向下相容 Paper 或舊版核心。

## 開源授權

專案採用 MIT 授權條款，完整條款請見 [LICENSE](../../LICENSE)。