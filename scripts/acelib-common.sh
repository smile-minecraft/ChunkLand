#!/usr/bin/env bash
# acelib-common.sh — AceLib 固定來源 / 版本 / 完整性常數與驗證函式。
#
# 僅定義，不執行任何副作用；供 build-acelib.sh 與 test-build-acelib.sh source。
# 來源與版本皆寫死，不使用 mavenLocal() / SNAPSHOT / latest / 浮動下載。
#
# 鎖定依據（可查證）：
#   - 倉庫：smile-minecraft/AceLib（GitHub）
#   - 標籤：v1.2.0（annotated tag → 固定 commit，見下方 provenance sidecar）
#   - 產物來源：GitHub Release v1.2.0 固定附件 AceLib-1.2.0.jar（不再由 source build 產生）
#   - 下載 URL：固定 HTTPS release asset URL（允許 GitHub redirect，不允許浮動 URL）
#   - 產物：AceLib-1.2.0.jar（plugin JAR，供 Folia plugins/ 使用）
#   - checksum：Release 提供的 SHA-256（固定 literal ACE_EXPECTED_SHA256，與本機驗證一致）
#
# 來源取得方式已由「clone + gradlew clean jar」改為「下載固定 release asset」；
# ACE_REPO_URL / ACE_TAG / ACE_COMMIT 保留為 provenance sidecar（記錄上游來源），
# 不再用於實際取得 JAR。

ACE_RELEASE_URL="https://github.com/smile-minecraft/AceLib/releases/download/v1.2.0/AceLib-1.2.0.jar"
ACE_ASSET_NAME="AceLib-1.2.0.jar"

ACE_REPO_URL_DEFAULT="https://github.com/smile-minecraft/AceLib.git"
ACE_TAG="v1.2.0"
ACE_COMMIT="55b27651f0156047e622354e2542e47f1f6bfffd"
ACE_VERSION="1.2.0"
ACE_EXPECTED_SHA256="da9f196b47c2b28c6db443d102236b27c1a1bbdf7dd3e7c22470170420935278"

# 固定來源與完整性常數：不可由環境變數覆寫（防止空字串跳過 checksum 或浮動 URL）。
ACE_RELEASE_URL_FIXED="$ACE_RELEASE_URL"
ACE_ASSET_NAME_FIXED="$ACE_ASSET_NAME"
ACE_EXPECTED_SHA256_FIXED="$ACE_EXPECTED_SHA256"

# Provenance sidecar（僅記錄上游來源，不再用於實際取得 JAR）
ACE_REPO_URL="$ACE_REPO_URL_DEFAULT"

OUT_NAME="AceLib-${ACE_VERSION}.jar"

# verify_acelib_jar JAR_PATH [EXPECTED_SHA256]
#   回傳 0 通過；非零並寫 stderr 說明失敗原因（缺 jar / 錯版本 / 缺 class / checksum 不符）。
verify_acelib_jar() {
  local jar="$1" expected_sha="${2:-}"
  if [[ ! -s "$jar" ]]; then
    echo "verify: jar 不存在或為空: $jar" >&2
    return 1
  fi
  local ver yml
  # 固定版本：本任務鎖定 AceLib v1.2.0。比較直接使用字面量，避免 sourced 檔案函式在
  # set -u 下看不到同檔案全域變數的 bash 怪異行為（local 指派亦不受影響）。
  # 先將 plugin.yml 內容讀入變數，再經 sed/tr/head 處理，避免 pipefail 下 unzip 因
  # head 提前關閉管線而收到 SIGPIPE（141）導致版本擷取失敗。
  yml="$(unzip -p "$jar" plugin.yml 2>/dev/null)"
  ver="$(printf '%s\n' "$yml" | sed -n 's/^version:[[:space:]]*//p' | tr -d "'\"" | head -1)"
  if [[ "$ver" != "1.2.0" ]]; then
    echo "verify: plugin.yml 版本不符 — 預期 1.2.0，實際 '${ver:-<none>}'" >&2
    return 1
  fi
  local listing
  # 用 `unzip -Z1` 一次只列 entry name（一行一個，保留含空白 entry 的原始名稱），
  # 再以 `grep -Fxq` 做完整逐行字面比對：拒絕任何 prefix / suffix / 子字串 / 含空白 token
  # 假匹配。`unzip -l` 會把長格式欄位與 entry name 同行輸出，欄位數隨路徑含空白而變，
  # 用 awk 取 $NF 會被「evil com/.../AceLibVersion.class」這類 entry 誤判為精確命中
  # （$NF 剛好等於最後一個以空白分隔的 token，等於預期路徑）。
  listing="$(unzip -Z1 "$jar" 2>/dev/null)"
  if printf '%s\n' "$listing" | grep -Fxq "com/smile/acelib/AceLibVersion.class"; then
    : # 精確 ZIP entry 命中（完整名稱逐行字面比對）
  else
    echo "verify: 缺少 com/smile/acelib/AceLibVersion.class" >&2
    return 1
  fi
  if [[ -z "$expected_sha" ]]; then
    echo "verify: 預期 checksum 為空（安全失敗，不允許跳過完整性驗證）" >&2
    return 1
  fi
  local actual
  actual="$(shasum -a 256 "$jar" | awk '{print $1}')"
  if [[ "$actual" != "$expected_sha" ]]; then
    echo "verify: checksum 不符 — 預期 $expected_sha，實際 $actual" >&2
    return 1
  fi
  return 0
}
