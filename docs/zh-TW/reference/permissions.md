[English](../../en/reference/permissions.md) · 繁體中文 · [简体中文](../../zh-CN/reference/permissions.md)

← [參考索引](README.md) · [文件索引](../README.md) · [指令參考](commands.md) · [權限參考](permissions.md) · [設定參考](configuration.md) · [API 參考](api.md)

# 權限參考

外掛一共宣告 29 個權限節點，**全部預設鎖定為管理員（`default: op`）**。這是為了保障開箱安全，避免伺服器剛裝好就被隨意圈地。服主可利用 LuckPerms 等權限外掛將節點指派給玩家或群組。

想快速完成玩家基礎授權，可直接跳至[一般玩家至少需要這幾個](#一般玩家至少需要這幾個)。

## 雙重權限防護：指令節點與領地管理閘門

通過指令節點只是第一道門檻。涉及領地管理的子指令還會再經過一道領地層的**管理閘門**檢驗（確認「操作者是否站在自己擁有的領地內、是否具備該項管理動作權限」），唯有兩道檢驗皆通過才會真正執行。

閘門在解析鏈之前優先檢驗：
- **管理員豁免（Bypass）**開啟時直接通過，不需具備地主身分。
- **伺服器領地協管（Steward）**在伺服器領地上等同主人身分；此身分僅在伺服器領地有效，在一般玩家領地上自動忽略。
- 無豁免亦無協管身分時，才進入常規解析鏈，玩家領地由地主特權保障。

伺服器領地在進入常規解析鏈前即會被隔離，綁定在玩家命名空間的自訂授權絕無法擅改伺服器領地。

## 指令節點

| 節點 | 對應指令 | 說明 |
| --- | --- | --- |
| `chunkland.command.land.help` | `/land help`、`/land`（無參數） | 指令總覽與個別子指令說明 |
| `chunkland.command.land.wand` | `/land wand` | 領取選區魔杖 |
| `chunkland.command.land.claim` | `/land claim` | 建立領地 |
| `chunkland.command.land.confirm` | `/land confirm` | 確認待處理的圈地 |
| `chunkland.command.land.expand` | `/land expand` | 擴張領地 |
| `chunkland.command.land.shrink` | `/land shrink`、`/land unclaim` | 縮減領地。`unclaim` 沒有獨立節點，共用這個 |
| `chunkland.command.land.subland` | `/land subland` | 子領地選取、建立、更新、刪除、延伸 |
| `chunkland.command.land.rename` | `/land rename` | 重新命名領地 |
| `chunkland.command.land.delete` | `/land delete` | 刪除領地 |
| `chunkland.command.land.trust` | `/land trust` | 信任成員 |
| `chunkland.command.land.untrust` | `/land untrust` | 取消信任 |
| `chunkland.command.land.default` | `/land default` | 變更領地預設權限 |
| `chunkland.command.land.binding` | `/land binding` | 建立或移除通用權限綁定 |
| `chunkland.command.land.group` | `/land group` | 管理玩家群組 |
| `chunkland.command.land.profile` | `/land profile` | 管理權限組合 |
| `chunkland.command.land.ban` | `/land ban` | 封鎖玩家 |
| `chunkland.command.land.unban` | `/land unban` | 解除封鎖 |
| `chunkland.command.land.manage` | `/land manage` | 開啟管理介面 |
| `chunkland.command.land.explain` | `/land explain` | 查詢單一動作的判定說明 |
| `chunkland.command.land.inspect` | `/land inspect` | 查詢領地資訊 |
| `chunkland.command.land.log` | `/land log` | 查詢審計紀錄 |
| `chunkland.command.land.history` | `/land history` | 查詢附近方塊歷史，需要 CoreProtect |

沒有 `/land` 的頂層節點，也沒有 `chunkland.command.land.unclaim`。

## 指令節點之後的管理閘門

下面這些子指令通過指令節點之後，還會被對應到一個管理動作，由領地層的權限決定。動作清單就是管理閘門認得的那五個。

| 管理動作 | 覆蓋的子指令 |
| --- | --- |
| `MANAGE_MEMBER`（管理成員） | `trust`、`untrust`、`ban`、`unban` |
| `MANAGE_PERMISSION`（管理權限） | `default`、`binding`、`explain`、`inspect` |
| `MANAGE_SUBLAND`（管理子領地） | `subland` |
| `EXPAND_LAND`（擴張領地） | `expand`、`shrink`、`unclaim` |
| `DELETE_LAND`（刪除領地） | `delete` |

其餘子指令（`help`、`wand`、`claim`、`confirm`、`rename`、`group`、`profile`、`log`、`history`、`manage`、`bypass`、`admin`）不帶管理動作，不會進這道閘門；它們各自靠指令節點加上領地資料的擁有者檢查把關。

`explain` 與 `inspect` 的拒絕是刻意做成不回填內容的：無論是節點不足、閘門拒絕、目標解析不到還是解析器出錯，回覆都是同一句固定的拒絕文字，不會透露這塊領地存不存在。

## 管理員節點

這四個節點互相獨立。持有其中一個不會拿到另外三個的判定結果。

| 節點 | 授予什麼 | 不授予什麼 |
| --- | --- | --- |
| `chunkland.admin.bypass` | 允許你執行 `/land bypass on\|off` 嘗試切換豁免 | 單靠它不會授權任何管理操作。豁免只在明確切換之後才對閘門生效，而且每次切換都會留下審計紀錄 |
| `chunkland.admin.serverland` | 伺服器領地協管身分，在伺服器領地上等同主人 | 不會給你豁免、帳本或孤兒世界權限；在玩家領地上完全無效 |
| `chunkland.admin.ledger` | `/land admin ledger` 的帳本查詢與處理 | 不會授權不可逆的孤兒世界清除 |
| `chunkland.admin.orphan` | `/land admin orphan`，包含不可逆的 `purge` | 不會給你帳本或豁免的判定結果 |

豁免記憶體是**每次啟用世代**建立的：伺服器一啟動每個人都預設關閉，停用時清空，所以沒有任何開關狀態能撐過一次重啟。單純持有節點永遠不會翻轉它。

## 診斷節點

| 節點 | 對應指令 |
| --- | --- |
| `chunkland.debug.m0message` | `/chunkland m0message` |
| `chunkland.debug.m0test` | `/chunkland m0test` |
| `chunkland.debug.visualization` | `/chunkland viz` |

`/chunkland` 本身沒有頂層節點，三個子指令各自檢查自己的節點。

## 一般玩家至少需要這幾個

服主最常見的起手式是開一個「能圈地」的權限組合，下面這幾個節點是最低需求。實際要開到什麼程度取決於你想讓玩家自己決定多少事。

| 想要的程度 | 需要開的節點 |
| --- | --- |
| 只能圈地 | `chunkland.command.land.wand`、`chunkland.command.land.claim`、`chunkland.command.land.confirm` |
| 加上改名 | 再加 `chunkland.command.land.rename` |
| 加上擴張與縮減 | 再加 `chunkland.command.land.expand`、`chunkland.command.land.shrink` |
| 加上自己管成員與封鎖 | 再加 `chunkland.command.land.trust`、`chunkland.command.land.untrust`、`chunkland.command.land.ban`、`chunkland.command.land.unban` |
| 加上自己調權限 | 再加 `chunkland.command.land.default`、`chunkland.command.land.binding`、`chunkland.command.land.group`、`chunkland.command.land.profile`、`chunkland.command.land.manage` |
| 加上管理子領地 | 再加 `chunkland.command.land.subland` |
| 讓玩家自己查 | 再加 `chunkland.command.land.help`、`chunkland.command.land.explain`、`chunkland.command.land.inspect`、`chunkland.command.land.log` |

`chunkland.command.land.delete` 建議不要開給一般玩家。刪除會永久移除領地並全額退費，只有你能決定要不要給這個權力。

## 動作識別字

識別字就是 `ProtectionActionType` 裡的列舉名稱，不分大小寫，頭尾空白會被去掉。整份列舉有 40 個，但**三個指令各自接受的範圍不同**：

| 指令 | 接受的動作 |
| --- | --- |
| `/land default <action>` | 下面「可設領地預設」那 17 個日常成員動作 |
| `/land profile set <profile> <action> <state>` | 全部 22 個 `SUBJECT_PERMISSION` 動作，也就是 17 個日常動作加上 5 個管理動作 |
| `/land explain <action>` | 全部 40 個，連規則動作與跨邊界動作都能解釋 |

`explain` 的接受範圍最寬，所以它會回報某些沒有任何介面可以設定的動作的判定結果。這是診斷用的讀取路徑，不代表那些動作可調。

**可設領地預設的日常成員動作**（`/land default` 接受的就是這 17 個）

| 識別字 | 動作 |
| --- | --- |
| `BLOCK_BREAK` | 破壞方塊 |
| `BLOCK_PLACE` | 放置方塊 |
| `CONTAINER_OPEN` | 開啟容器 |
| `WORKSTATION_USE` | 使用工作方塊 |
| `DOOR_USE` | 開關門 |
| `BUTTON_USE` | 按按鈕 |
| `LEVER_USE` | 扳動拉桿 |
| `REDSTONE_USE` | 觸發紅石裝置 |
| `BUCKET_USE` | 使用桶子 |
| `ENTRY` | 進入領地 |
| `VEHICLE_USE` | 使用載具 |
| `ENTITY_INTERACT` | 與生物互動 |
| `ENTITY_DAMAGE` | 攻擊生物 |
| `ITEM_FRAME` | 動用物品展示框 |
| `ARMOR_STAND` | 動用盔甲座 |
| `HANGING_ENTITY` | 動用畫與掛飾 |
| `FARMLAND_TRAMPLE` | 踩踏耕地 |

**管理動作**（`/land default` 不接受，`/land profile set` 接受）

| 識別字 | 對應的閘門 |
| --- | --- |
| `MANAGE_MEMBER` | 管理成員 |
| `MANAGE_PERMISSION` | 管理權限 |
| `MANAGE_SUBLAND` | 管理子領地 |
| `EXPAND_LAND` | 擴張領地 |
| `DELETE_LAND` | 刪除領地 |

這五個是上面那道管理閘門在內部使用的動作。它們不能寫進領地預設——預設只給日常成員動作留位子——但可以在權限組合裡設定，讓你把某個組合整包綁給成員時連管理能力一起帶上。

**領地規則動作**（`/land default` 與 `/land profile set` 都不接受，只有 `explain` 能解釋）

| 識別字 | 規則 |
| --- | --- |
| `PLAYER_DAMAGE_PLAYER` | PVP |
| `EXPLOSION_TERRAIN` | 爆炸破壞地形 |
| `EXPLOSION_ENTITY` | 爆炸傷害生物 |
| `FIRE_SPREAD` | 火焰蔓延 |
| `FIRE_BURN` | 火焰燒毀方塊 |
| `MOB_GRIEFING` | 生物破壞方塊 |
| `FLUID_FLOW` | 液體流動 |
| `PISTON_MOVE` | 活塞推動方塊 |
| `HOPPER_TRANSFER` | 漏斗傳輸物品 |
| `HOSTILE_MOB_SPAWN` | 敵對生物生成 |
| `PASSIVE_MOB_SPAWN` | 友善生物生成 |

這一類動作答的是「這個機制能不能在這裡發生」，沒有玩家的面向。判定時會同時套用玩家權限鏈與規則鏈，兩者都允許才會放行。

底下還有一組只用在跨越邊界時的規則動作：`BLOCK_MOVE_IN`、`BLOCK_MOVE_OUT`、`FLUID_ENTER`、`FLUID_EXIT`、`ITEM_TRANSFER_IN`、`ITEM_TRANSFER_OUT`、`DISPENSER_CROSS_BOUNDARY`。同樣沒有可調整的介面，只有 `explain` 讀得到判定結果。這七個加上上面 11 個就是全部 18 個規則動作，17 加 22 加它們剛好是 40。

## 判定鏈的層級

`/land explain` 會告訴你這一刻是哪一層決定了結果。實際順序如下，每一層出現明確值就停下來，不再往下問。

**玩家權限鏈**

| 順序 | 層級 | 備註 |
| --- | --- | --- |
| 1 | 管理員豁免 | 所有來源都先查這一項 |
| 2 | 領地主人保障 | 你是主人就直接允許 |
| 3 | 子領地授權彙總 | |
| 4 | 子領地預設 | |
| 5 | 領地授權彙總 | |
| 6 | 領地預設 | `/land default` 設的就是這一層 |
| 7 | 世界預設 | `config.yml` 的 `subject-defaults.worlds.<世界>` |
| 8 | 全域預設 | `config.yml` 的 `subject-defaults.global` |
| 9 | 隱含拒絕 | 全部都沒設定時一律拒絕 |

**領地規則鏈**

| 順序 | 層級 | 備註 |
| --- | --- | --- |
| 1 | 子領地規則 | |
| 2 | 領地規則 | |
| 3 | 世界預設 | |
| 4 | 全域預設 | |
| 5 | 隱含拒絕 | |

規則鏈刻意不含主人保障，所以環境規則對領地主人同樣有效。

## 這裡沒有涵蓋的

權限節點只管「能不能執行指令」與「能不能改這塊領地」。實際能不能在領地裡破壞方塊、開箱子，是領地層的權限決定的，兩套東西互相獨立：開了 `chunkland.command.land.ban` 不代表你能在任何地方封鎖人，還是得先站在自己管得到的領地裡。