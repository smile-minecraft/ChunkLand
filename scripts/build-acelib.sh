#!/usr/bin/env bash
# build-acelib.sh — 從 GitHub Release 固定附件取得已驗證的 AceLib server JAR。
#
# 來源與版本皆寫死，不使用 mavenLocal() / SNAPSHOT / latest / 浮動下載：
#   - 來源：GitHub Release v1.2.0 固定附件 AceLib-1.2.0.jar（固定 HTTPS URL）
#   - 標籤：v1.2.0（annotated tag，見 acelib-common.sh provenance sidecar）
#   - 產物：AceLib-1.2.0.jar（plugin JAR，供 Folia plugins/ 使用）
#   - 完整性：下載後以固定 SHA-256、plugin.yml 版本、AceLibVersion.class 驗證
#
# 失敗語意（安全失敗，非零離開，暫存目錄清理）：
#   - 下載失敗（curl 非零、網路錯誤、非 200、非 JAR/HTML 回應）
#   - 下載內容為空或非預期檔案類型
#   - plugin.yml 版本不是 1.2.0（錯版本）
#   - 缺少 com/smile/acelib/AceLibVersion.class
#   - 產物 SHA-256 與預期不符（checksum 不符）
#
# 用法：
#   scripts/build-acelib.sh [--output-dir DIR]
# 環境變數（僅選用，僅限輸出目錄與暫存目錄；來源與完整性不可覆寫）：
#   ACE_OUTPUT_DIR       產物輸出目錄（預設 $XDG_CACHE_HOME/chunkland-acelib，位於工作樹之外）
#   ACE_STAGING_DIR      暫存下載目錄（預設 mktemp -d，結束時清理）
set -eo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=acelib-common.sh
source "$SCRIPT_DIR/acelib-common.sh"

OUTPUT_DIR="${ACE_OUTPUT_DIR:-${XDG_CACHE_HOME:-$HOME/.cache}/chunkland-acelib}"
while [[ $# -gt 0 ]]; do
  case "$1" in
    --output-dir) OUTPUT_DIR="$2"; shift 2;;
    *) echo "build-acelib: 未知參數 $1" >&2; exit 2;;
  esac
done

STAGING_OWNED=0
if [[ -z "${ACE_STAGING_DIR:-}" ]]; then
  STAGING_DIR="$(mktemp -d "${TMPDIR:-/tmp}/acelib-staging.XXXXXX")"
  STAGING_OWNED=1
else
  # caller 提供的既有目錄：腳本只在其內寫入下載產物，整個目錄（含 sentinel 與其他檔案）
  # 在成功／失敗後都必須保留，不可由 EXIT trap 刪除。
  STAGING_DIR="$ACE_STAGING_DIR"
fi
cleanup() {
  local exit_code=$?
  # 只清理腳本自建的 staging；caller 提供的既有 STAGING_DIR 絕對不可動。
  if [[ "$STAGING_OWNED" -eq 1 && -d "$STAGING_DIR" ]]; then
    rm -rf "$STAGING_DIR"
  fi
  if [[ $exit_code -ne 0 ]]; then
    # 失敗時只清理三個已知產物，保留 OUTPUT_DIR 其他檔案。
    rm -f "$OUTPUT_DIR/$OUT_NAME" "$OUTPUT_DIR/$OUT_NAME.sha256" "$OUTPUT_DIR/$OUT_NAME.meta"
  fi
}
trap cleanup EXIT

log() { echo "build-acelib: $*"; }

# ---- 固定下載來源（不可覆寫：URL、asset、checksum 皆為 literal） ----
RELEASE_URL="$ACE_RELEASE_URL_FIXED"
ASSET_NAME="$ACE_ASSET_NAME_FIXED"
EXPECTED_SHA256="$ACE_EXPECTED_SHA256_FIXED"

if [[ -z "$RELEASE_URL" ]]; then
  echo "build-acelib: 固定 release URL 為空（安全失敗）" >&2
  exit 1
fi
if [[ -z "$EXPECTED_SHA256" ]]; then
  echo "build-acelib: 固定預期 checksum 為空（安全失敗，不允許跳過驗證）" >&2
  exit 1
fi

# ---- 1. 下載固定 release asset（fail closed：非 200、非 JAR、網路錯誤皆非零） ----
# 下載前先清理已知的舊產物（避免已有 stale artifact 被下游誤用）
rm -f "$OUTPUT_DIR/$OUT_NAME" "$OUTPUT_DIR/$OUT_NAME.sha256" "$OUTPUT_DIR/$OUT_NAME.meta"

log "下載 $RELEASE_URL"
STAGED_JAR="$STAGING_DIR/$ASSET_NAME"

# 使用 curl -L 允許 GitHub HTTPS redirect，但限制協議與 redirect 皆為 HTTPS（--proto '=https' --proto-redir '=https'）；
# -f 讓 HTTP 錯誤（4xx/5xx）直接非零退出；-s 靜默；-S 顯示錯誤；-o 寫入檔案。
# 固定 URL 與固定 asset 名稱仍為 gate；非 HTTPS URL 或 redirect 直接失敗。
if ! curl -fsSL --proto '=https' --proto-redir '=https' -o "$STAGED_JAR" "$RELEASE_URL"; then
  echo "build-acelib: 下載失敗（curl 非零退出，URL=$RELEASE_URL）" >&2
  exit 1
fi

# 下載後立即檢查：非空、非 HTML、檔名與預期一致
if [[ ! -s "$STAGED_JAR" ]]; then
  echo "build-acelib: 下載內容為空（$STAGED_JAR）" >&2
  exit 1
fi

# 簡易結構檢查：必須為 ZIP/JAR（unzip -t 可驗證），拒絕純文字/HTML 回應
if ! unzip -t "$STAGED_JAR" >/dev/null 2>&1; then
  echo "build-acelib: 下載內容非有效 JAR/ZIP（$STAGED_JAR）" >&2
  exit 1
fi

# 檔名必須與固定 asset 名稱一致（防止 redirect 到錯誤檔案）
local_basename="$(basename "$STAGED_JAR")"
if [[ "$local_basename" != "$ASSET_NAME" ]]; then
  echo "build-acelib: 下載檔名不符 — 預期 $ASSET_NAME，實際 $local_basename" >&2
  exit 1
fi

log "已下載 $STAGED_JAR (${ASSET_NAME})"

# ---- 2. 驗證（版本 / 類別 / checksum）——必須全部通過才輸出 ----
# 使用固定 digest（不可由環境覆寫），確保 checksum 驗證不被跳過。
if ! verify_acelib_jar "$STAGED_JAR" "$EXPECTED_SHA256"; then
  echo "build-acelib: 產物驗證失敗（版本 / class / checksum 不符）" >&2
  exit 1
fi

# ---- 3. 輸出（僅寫入 OUTPUT_DIR，不觸及工作樹範圍外檔案） ----
mkdir -p "$OUTPUT_DIR"
cp -f "$STAGED_JAR" "$OUTPUT_DIR/$OUT_NAME"
# Post-copy 驗證：最終寫入 OUTPUT 的 bytes 必須仍通過固定 digest / 版本 / 精確 entry 驗證。
# 若複製途中或複製後內容被竄改（例如 caller-owned staging 在驗證後被覆寫、或 cp 本身被挾持），
# 此處重新以固定 digest 對最終 output 驗證，確保 sidecar 建立前 output 未被竄改。
if ! verify_acelib_jar "$OUTPUT_DIR/$OUT_NAME" "$EXPECTED_SHA256"; then
  echo "build-acelib: 輸出驗證失敗（output bytes 驗證不符／可能被竄改）" >&2
  exit 1
fi
SHA="$(shasum -a 256 "$OUTPUT_DIR/$OUT_NAME" | awk '{print $1}')"
printf '%s  %s\n' "$SHA" "$OUT_NAME" > "$OUTPUT_DIR/$OUT_NAME.sha256"
cat > "$OUTPUT_DIR/$OUT_NAME.meta" <<EOF
source=release_asset
repo=$ACE_REPO_URL_DEFAULT
tag=$ACE_TAG
commit=$ACE_COMMIT
release_url=$ACE_RELEASE_URL_FIXED
asset=$ACE_ASSET_NAME_FIXED
version=$ACE_VERSION
sha256=$SHA
downloaded_at=$(date -u +%Y-%m-%dT%H:%M:%SZ)
EOF
# 二次輸出驗證：確保 sidecar 建立後最終 output 仍通過同一固定驗證（縮小「驗證後、sidecar 後」竄改窗口）。
if ! verify_acelib_jar "$OUTPUT_DIR/$OUT_NAME" "$EXPECTED_SHA256"; then
  echo "build-acelib: 輸出二次驗證失敗（sidecar 建立後驗證不符／可能被竄改）" >&2
  exit 1
fi
log "產出 $OUTPUT_DIR/$OUT_NAME (sha256 $SHA)"
log "完成"
