[English](../../en/reference/configuration.md) · 繁體中文 · [简体中文](../../zh-CN/reference/configuration.md)

← [參考索引](README.md) · [文件索引](../README.md) · [指令參考](commands.md) · [權限參考](permissions.md) · [設定參考](configuration.md) · [API 參考](api.md)

# 設定參考

主設定檔路徑為 `plugins/ChunkLand/config.yml`。外掛初次開服時會自動生成該檔，之後每次啟動時讀取載入。

**變更參數請務必重啟伺服器**：ChunkLand 未提供熱重載指令，Folia 環境下亦請避免使用 Bukkit 的 `/reload`。若設定檔有語法損毀，請參考[伺服器管理指南](../server-guide.md#設定檔損壞或遺失時的自動修復)的修復說明。

## 強型別驗證與容錯機制

**未知的頂層設定鍵在載入時會直接被拒絕**，絕不會靜默忽略。這項設計是為了避免服主手滑打錯字或貼錯配置而產生非預期的行為。

設定檔中每個區段皆具備嚴格的強型別約束。若漏掉某個區段，系統會自動套用該區段的內建預設；若存在型別不符或語法錯誤的值，整份檔案將拒絕載入，並自動啟用上一份確認可用的安全備份。

下面每一節列出該區段支援的鍵。

## messages

| 鍵 | 預設值 | 說明 |
| --- | --- | --- |
| `default-locale` | `en_US` | 未指定語系時使用的語言 |
| `cooldown-seconds` | `2` | 同一則訊息對同一位玩家的重複間隔秒數 |

語言檔本身在 `plugins/ChunkLand/lang/`，第一次啟動會產生 `en_US.yml` 與 `zh_TW.yml`。

## audit

| 鍵 | 預設值 | 說明 |
| --- | --- | --- |
| `retention-days` | `180` | 審計紀錄保留天數。`0` 表示永久保留，不會執行任何清除 |

清除只刪除**嚴格早於**截止時間的審計紀錄，而且是在持久化執行緒上分批進行。領地、區塊與帳本的紀錄永遠不會被動到；已刪除領地的歷史在它自己的截止時間到期前都還讀得到。

負數或非整數會讓載入失敗，不會默默縮短或清掉歷史。

## economy

圈地計價的完整設定。這個區段一旦存在就必須完整且精確：未知鍵、缺少 currency 或 pricing、空白代碼、超範圍的 scale、非正數或非整數的界、重疊的區間、頂層不是 `unbounded`、負數或溢出的價格，全部都會在載入時失敗並保留前一次快照——玩家圈地會因此擋下，而不是被悄悄改價。

| 鍵 | 預設值 | 說明 |
| --- | --- | --- |
| `enabled` | `false` | 購買開關。`false` 時圈地與擴張不收費，也不需要經濟外掛 |
| `currency.code` | `EMC` | 所有價格儲存與扣款的貨幣代碼 |
| `currency.scale` | `2` | 小數位數指數。`2` 表示 100 個最小單位等於 1 個主要單位 |

`pricing.tiers` 用**擁有者的總區塊數**分級計價，也就是「前一級的 `until`（不含）到這一級的 `until`（含）」之間的每一個區塊，各是多少主要單位。計價只看玩家領地的總區塊數，不含伺服器領地。

預設的分級：

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

分級必須是一段沒有縫隙、沒有重疊、且最後一級是 `unbounded` 的連續分割。

**退款不看這張表。** 退款一律從持久化的每區塊成本基礎計算，所以之後調整分級不會改變過去任何一筆付款的退款金額。

**不要在帳本還有有效紀錄時更改 `currency.scale`。** 存下來的是最小單位，改了 scale 會讓同一筆餘額換算成不同的主要金額。

**`enabled` 決定要不要收費。** 預設是 `false`：圈地與擴張免費，不需要安裝經濟外掛，玩家能圈多少只受 `limits` 限制；這樣圈到的區塊成本為零，縮減或刪除時也沒有退款。改成 `true` 之後才依上面的分級收費，此時必須安裝 Vault 系經濟外掛，否則玩家圈地會被擋下。

不論開關是哪一邊，貨幣與分級都會照常驗證。開啟期間買下的區塊會保留當時記錄的價格，之後即使把開關關掉，縮減或刪除仍會照那筆金額退款，所以在已上線的伺服器關閉購買時，請保留經濟外掛。沒有經濟外掛時，仍欠著退款金額的領地會在動任何資料之前就先被擋下，不會先刪掉土地再留下來等人工處理。

整個 `economy` 區段不存在的話，行為等同 `enabled: false`。

## limits

| 鍵 | 預設值 | 說明 |
| --- | --- | --- |
| `max-lands-per-player` | `5` | 每位玩家的領地數上限 |
| `max-total-chunks-per-player` | `10` | 每位玩家的領地總區塊數上限 |
| `max-chunks-per-land` | `10` | 單一領地的區塊數上限 |
| `max-sublands-per-land` | `16` | 單一領地的子領地數上限 |
| `max-selection-side-length` | `32` | 選取範圍單邊的區塊數上限 |
| `max-selection-chunks` | `1024` | 單次選取範圍的區塊總數上限 |
| `max-decision-cache-entries` | `4096` | 保護判定的快取預算（只在記憶體中，每次啟用世代重新計算）。`0` 關閉重用，每次判斷都重算 |

快取在世代不變時會重用同一個判定結果，任何世代移動都自然失效。負數或非整數會讓載入失敗。

## selection

| 鍵 | 預設值 | 有效範圍 | 說明 |
| --- | --- | --- | --- |
| `session-timeout-seconds` | `600` | — | 選取工作階段多久沒動作就結束 |
| `visualization-max-segments` | `256` | 1–4096 | 每影格保留的邊界線段數量。超過時保留排序後的前段 |
| `visualization-max-particles-per-tick` | `512` | 1–1024 | 每個算繪影格送出的粒子數，視窗會輪替 |
| `visualization-render-distance-blocks` | `64` | 8–128 | 超過這個觀看距離的線段會被略過 |
| `visualization-refresh-interval-ticks` | `10` | 1–200 | 兩次算繪之間間隔的刻數 |

這四個視覺化鍵是玩家範圍的，只送粒子，不查地形資料。

## feedback

拒絕回饋的全部設定。這裡的每一項**只影響被拒絕的玩家看到什麼**，不會改變任何判定結果。

進入被拒時畫的紅色邊界：

| 鍵 | 預設值 | 有效範圍 | 說明 |
| --- | --- | --- | --- |
| `entry-wall-enabled` | `true` | — | 是否要畫這個邊界 |
| `entry-wall-radius-blocks` | `6` | 2–16 | 邊界畫在玩家周圍多遠的位置 |
| `entry-wall-particle-size-percent` | `160` | 25–400 | 粒子大小，`100` 是原版大小 |
| `entry-wall-points-per-block` | `2` | 1–4 | 每格方塊沿邊界放幾個粒子 |
| `entry-wall-cooldown-millis` | `2000` | 100–60000 | 對同一位玩家兩次畫邊界之間的安靜時間 |

被拒絕破壞、放置或使用時標在方塊（生物則是畫環）上的外框：

| 鍵 | 預設值 | 說明 |
| --- | --- | --- |
| `action-mark-enabled` | `true` | 是否要畫這個標記 |
| `action-mark-particle-size-percent` | `110` | 粒子大小 |
| `action-mark-cooldown-millis` | `400` | 對同一位玩家兩次標記之間的安靜時間 |

被拒絕的玩家會被推回去多遠：

| 鍵 | 預設值 | 有效範圍 | 說明 |
| --- | --- | --- | --- |
| `push-out-distance-blocks` | `3` | 1–8 | 邊界與落點之間的格數 |
| `push-out-cooldown-millis` | `500` | 100–60000 | 對同一位玩家兩次推回之間的安靜時間 |

冷卻時間不要設得比玩家走回來的時間長。走 3 格大約 500 毫秒，冷卻設太高會讓玩家一直被卡在取消的移動上，畫面看起來會一頓一頓的。

## worlds

沒列在這裡的世界沿用預設：`claim-enabled: true`、`vertical-mode: PER_CHUNK_DEPTH`。

| 鍵 | 預設值 | 說明 |
| --- | --- | --- |
| `<世界名稱>.claim-enabled` | `true` | 關閉後該世界不能新圈地，**既有領地仍然受保護** |
| `<世界名稱>.vertical-mode` | `PER_CHUNK_DEPTH` | 有效保護深度的讀取方式 |

預設檔已經把三個常見世界寫進來：

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

世界名稱**大小寫敏感**。動作與規則的鍵不分大小寫，但用不同大小寫重複定義同一個鍵會被拒絕，而不是被後寫的覆蓋。

### vertical-mode 兩個值

| 值 | 行為 |
| --- | --- |
| `PER_CHUNK_DEPTH` | 每個區塊各自從儲存的最低保護高度解析有效深度 |
| `FULL_HEIGHT` | 有效深度解析成世界的最低高度，同時仍然保存原本的儲存值，以便之後切回去 |

切換模式**只影響有效值的讀取**，永遠不會遷移或改寫已經存好的資料。想把 FULL_HEIGHT 切回 PER_CHUNK_DEPTH 時，先前存下的深度還在。

## subject-defaults

回答「這個玩家能不能做這個動作」，是 Direct Trust 的基礎。

| 位置 | 說明 |
| --- | --- |
| `subject-defaults.global.<動作>` | 全域預設 |
| `subject-defaults.worlds.<世界名稱>.<動作>` | 單一世界的預設 |

預設值只有 `ENTRY: ALLOW`：

```yaml
subject-defaults:
  global:
    ENTRY: ALLOW
```

這個預設代表**沒有被封鎖的陌生玩家可以走進已經載入的領地**。`/land ban` 仍然擋得住，因為封鎖在領地綁定層以拒絕優先，效力高於世界與全域預設。想把領地關給陌生人，見[伺服器管理指南](../server-guide.md#陌生人進入預設放行還是關閉)。

缺少區段、缺少該項，或是明確寫 `INHERIT`，三者的意思都是「往下一層解析」，一路問到最後就是隱含拒絕。

## rule-defaults

回答「這個機制在這裡能不能發生」，是每個 `LAND_RULE` 的預設。

**這個區段目前沒有接上任何實際功能。** 預設檔裡的 `rule-defaults` 區塊整段被註解起來，寫進去不會改變任何行為。十一項領地規則目前一律使用內建預設：水流、活塞、漏斗傳輸與生物生成在領地內放行，PVP、爆炸、火焰、生物破壞擋下，活塞、液體、漏斗跨越領地邊界一律擋下；沒有玩家或管理員介面可以調整。完整說明在[已知限制](../limitations.md)。

```
#rule-defaults:
#  global:
#    PVP: DENY
#  worlds:
#    world:
#      PVP: ALLOW
```

`subject-defaults` 與 `rule-defaults` 是兩個各自型別化的命名空間，**永遠不會互相回退**。領地綁定、領地預設與子領地這三層不在這裡設定（還沒有可持久化的來源），一律維持 `INHERIT`。