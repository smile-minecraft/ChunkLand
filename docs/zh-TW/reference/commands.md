[English](../../en/reference/commands.md) · 繁體中文 · [简体中文](../../zh-CN/reference/commands.md)

← [參考索引](README.md) · [文件索引](../README.md) · [指令參考](commands.md) · [權限參考](permissions.md) · [設定參考](configuration.md) · [API 參考](api.md)

# 指令參考

這裡整理了 `/land` 旗下的 25 個子指令與除錯專用的 `/chunkland` 指令，詳細權限節點可對照[權限參考](permissions.md)。

執行指令時請留意兩大前置條件：
1. **權限節點檢查**：若未被指派對應節點，會收到「你沒有使用這個指令的權限」提示。
2. **領地管理閘門**：管理類指令需由操作者親自**站在自己擁有的領地內**執行。多數領地管理指令與圖形介面僅供玩家使用，主控台輸入時會收到專屬提示。

直接輸入 `/land`（不帶參數）等同於 `/land help`。

## 圈地與範圍

| 子指令 | 權限節點 | 用法 | 用途 |
| --- | --- | --- | --- |
| `help` | `chunkland.command.land.help` | `/land help [子指令]` | 依用途分組列出全部子指令；帶子指令名稱時顯示該子指令的說明、權限節點與用法。也接受 `?` |
| `wand` | `chunkland.command.land.wand` | `/land wand` | 發放選區魔杖 |
| `claim` | `chunkland.command.land.claim` | `/land claim <land_name>` | 把目前的魔杖選取範圍建立為領地，會先列出區塊數、花費、衝突數與最低保護高度並等待確認 |
| `confirm` | `chunkland.command.land.confirm` | `/land confirm <generation> <revision> <land_name>` | 確認待處理的圈地。確認訊息上的按鈕會自動填好這三個參數 |
| `expand` | `chunkland.command.land.expand` | `/land expand` | 把選取範圍併入腳下的領地 |
| `shrink` | `chunkland.command.land.shrink` | `/land shrink` | 從腳下的領地移除選取範圍並退款 |
| `unclaim` | `chunkland.command.land.shrink` | `/land unclaim` | 與 `shrink` 共用同一個流程與同一個權限節點 |
| `rename` | `chunkland.command.land.rename` | `/land rename <new_name>` | 更改腳下領地的顯示名稱 |
| `delete` | `chunkland.command.land.delete` | `/land delete confirm <revision>` | 永久刪除領地並全額退費。第一次執行 `/land delete` 只會取得確認指令 |
| `subland` | `chunkland.command.land.subland` | `/land subland (select\|create\|update\|delete\|extend) [generation revision] [名稱]` | 選取、建立、更新、刪除或延伸子領地 |

## 權限與成員

| 子指令 | 權限節點 | 用法 | 用途 |
| --- | --- | --- | --- |
| `trust` | `chunkland.command.land.trust` | `/land trust <player>` | 授予該玩家在此領地的成員權限 |
| `untrust` | `chunkland.command.land.untrust` | `/land untrust <player>` | 撤銷該玩家的成員權限 |
| `ban` | `chunkland.command.land.ban` | `/land ban <player>` | 拒絕該玩家進入與使用此領地 |
| `unban` | `chunkland.command.land.unban` | `/land unban <player>` | 恢復該玩家對此領地的存取權 |
| `default` | `chunkland.command.land.default` | `/land default <action> [ALLOW\|DENY\|INHERIT]` | 設定整個領地對單一玩家權限動作的預設結果。只接受玩家權限動作，領地規則動作不行 |
| `binding` | `chunkland.command.land.binding` | `/land binding (bind\|unbind) player <player> <profile> \| group <group> <profile> [--in-subland]` | 建立、更新或移除通用權限綁定 |
| `group` | `chunkland.command.land.group` | `/land group (create\|list\|add\|remove\|delete) [參數]` | 維護綁定所使用的玩家群組 |
| `profile` | `chunkland.command.land.profile` | `/land profile (create\|list\|set\|delete) [參數]` | 維護可重複使用的權限組合。`set` 的狀態只能是 `ALLOW`、`DENY`、`INHERIT` |
| `manage` | `chunkland.command.land.manage` | `/land manage` | 開啟兩層管理介面：權限頁、成員名單頁、封鎖名單頁 |

## 查詢

| 子指令 | 權限節點 | 用法 | 用途 |
| --- | --- | --- | --- |
| `explain` | `chunkland.command.land.explain` | `/land explain <action>` | 顯示單一玩家權限動作在此處為何允許或拒絕，包含決定來源層 |
| `inspect` | `chunkland.command.land.inspect` | `/land inspect [玩家]` | 顯示領地的擁有者、區塊數、子領地數、上下限與其來源、領地編號與版本號 |
| `log` | `chunkland.command.land.log` | `/land log [u:玩家UUID] [t:7d] [a:動作] [land:領地UUID] [world:世界UUID] [limit:筆數] [page:頁數]` | 依篩選條件與分頁查詢審計紀錄 |
| `history` | `chunkland.command.land.history` | `/land history` | 顯示你附近最近的方塊變更，需要伺服器上裝了 CoreProtect；沒有時會回覆功能暫時無法使用 |

`<action>` 用的是動作識別字，不分大小寫：`BLOCK_PLACE`、`BLOCK_BREAK`、`CONTAINER_OPEN`、`ENTRY`、`ENTITY_DAMAGE` 這樣的形式。`default` 接受 17 個、`profile set` 接受 22 個、`explain` 接受全部 40 個，各指令接受哪些寫在[權限參考](permissions.md#動作識別字)。

## 管理員

| 子指令 | 權限節點 | 用法 | 用途 |
| --- | --- | --- | --- |
| `bypass` | `chunkland.admin.bypass` | `/land bypass [on\|off]` | 切換你自己的管理員豁免。這個節點只允許你「嘗試」切換，切換本身會留下審計紀錄 |
| `admin` | `chunkland.admin.ledger`（`orphan` 分支另有節點） | `/land admin (ledger\|orphan) ...` | 帳本處理與孤兒世界管理 |

`/land admin` 的兩個分支用不同權限節點，`ledger` 與 `orphan` 互不授權：

| 分支 | 權限節點 | 用法 |
| --- | --- | --- |
| `admin ledger` | `chunkland.admin.ledger` | `/land admin ledger list [狀態]`<br>`/land admin ledger show [操作編號]`<br>`/land admin ledger resolve [操作編號] [RESOLVED\|REFUNDED\|IGNORED]` |
| `admin orphan` | `chunkland.admin.orphan` | `/land admin orphan list`<br>`/land admin orphan purge [世界UUID]`<br>`/land admin orphan purge [世界UUID] confirm [確認碼]` |

`orphan purge` 會永久刪除那個世界底下的所有領地，**不會退款也無法復原**。指令會先給你一個有時效的確認碼，超時就要重新產生。

## 自動完成

輸入到一半時，Tab 補完會依子指令提供對應的候選：

| 子指令 | 補完什麼 |
| --- | --- |
| `help` | 全部子指令名稱 |
| `explain` | 全部 40 個動作識別字 |
| `default` | 可設領地預設的那 17 個；第三個參數補 `ALLOW`、`DENY`、`INHERIT` |
| `trust`、`untrust`、`ban`、`unban`、`inspect` | **線上**玩家名稱 |
| `subland` | `select`、`create`、`update`、`delete`、`extend` |
| `group` | `create`、`list`、`add`、`remove`、`delete` |
| `profile` | `create`、`list`、`set`、`delete` |
| `binding` | `bind`、`unbind` |
| `bypass` | `on`、`off` |
| `delete` | 確認分支的關鍵字 |
| `admin` | `ledger`、`orphan` |

名單類指令的補完只給線上玩家，這是設計如此。

## 診斷指令

`/chunkland` 是給開發與除錯用的診斷指令，與玩家功能無關。

| 用法 | 權限節點 | 用途 |
| --- | --- | --- |
| `/chunkland m0message [locale]` | `chunkland.debug.m0message` | 測試訊息管線在指定語系下的輸出 |
| `/chunkland m0test [scheduler\|gui\|form\|cancelall]` | `chunkland.debug.m0test` | 排程器、介面、表單與取消流程的煙霧測試 |
| `/chunkland viz <start\|stop\|status>` | `chunkland.debug.visualization` | 選取邊界視覺化的開關與狀態查詢 |

`viz` 只畫粒子，不查地形資料，對效能的影響由 `selection` 區段的那幾個鍵控制。

## 執行之後

指令被拒絕時，聊天欄會直接寫出原因。常見的幾種：

| 你看到的情況 | 意思 |
| --- | --- |
| 「你沒有使用這個指令的權限」並附上節點名稱 | 指令節點沒授權，請服主處理 |
| 「請站在你可以管理的領地內再試一次」 | 管理閘門沒過，你不在自己管得到的領地裡 |
| 「這個功能暫時無法使用，請稍後再試」 | 對應的子系統還沒接好或暫時不可用 |
| 「找不到這位玩家；離線玩家請輸入完整的玩家名稱或 UUID」 | 這個名字從未登入過，或拼寫不對。用 UUID 試試 |
| 「子指令 X 尚未實作」 | 這個子指令在目前的建置裡還沒有接上處理器 |
| 「資料在這段期間已經更新，請重新操作一次」 | 你送出確認之前資料被改過了，重新走一次流程 |