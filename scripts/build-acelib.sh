#!/usr/bin/env bash
# build-acelib.sh — 自建固定版本 AceLib server JAR（可重現）。
#
# 來源與版本皆寫死，不使用 mavenLocal() / SNAPSHOT / latest / 浮動下載：
#   - 倉庫：smile-minecraft/AceLib（GitHub）
#   - 標籤：v1.2.0（annotated tag → 固定 commit，見 acelib-common.sh）
#   - 產物：AceLib-1.2.0.jar（plugin JAR，供 Folia plugins/ 使用）
#   - 完整性：產物 SHA-256 與 ACE_EXPECTED_SHA256 比對（預設為已驗證建置值）
#
# 失敗語意（安全失敗，非零離開，暫存目錄清理）：
#   - 來源無法取得（clone 失敗 / 網路 / 憑證）
#   - checkout 的 commit 與 ACE_COMMIT 不符（來源被移動或 tag 被改指）
#   - 產物不存在 / 為空
#   - plugin.yml 版本不是 1.2.0（錯版本）
#   - 產物 SHA-256 與預期不符（checksum 不符）
#
# 用法：
#   scripts/build-acelib.sh [--output-dir DIR]
# 環境變數（皆選用）：
#   ACE_OUTPUT_DIR       產物輸出目錄（預設 $XDG_CACHE_HOME/chunkland-acelib，位於工作樹之外）
#   ACE_STAGING_DIR      暫存 clone/build 目錄（預設 mktemp -d，結束時清理）
#   ACE_REPO_URL         覆寫來源倉庫（測試用；預設 acelib-common.sh 常數）
#   ACE_EXPECTED_SHA256  覆寫預期 checksum（預設 acelib-common.sh 常數）
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

STAGING_DIR="${ACE_STAGING_DIR:-$(mktemp -d "${TMPDIR:-/tmp}/acelib-staging.XXXXXX")}"
cleanup() { [[ -d "$STAGING_DIR" ]] && rm -rf "$STAGING_DIR"; }
trap cleanup EXIT

log() { echo "build-acelib: $*"; }

# ---- 1. 取得來源（缺 source 安全失敗） ----
log "clone $ACE_REPO_URL @ $ACE_TAG"
if ! git clone --depth 1 --branch "$ACE_TAG" "$ACE_REPO_URL" "$STAGING_DIR" 2>&1 | tail -3; then
  echo "build-acelib: 來源取得失敗（clone 非零退出）" >&2
  exit 1
fi

HEAD_COMMIT="$(git -C "$STAGING_DIR" rev-parse HEAD)"
if [[ "$HEAD_COMMIT" != "$ACE_COMMIT" ]]; then
  echo "build-acelib: commit 不符 — 預期 $ACE_COMMIT，實際 $HEAD_COMMIT（來源被移動？）" >&2
  exit 1
fi
tagout="$(git -C "$STAGING_DIR" tag --points-at HEAD 2>/dev/null)"
if printf '%s\n' "$tagout" | grep -qx "$ACE_TAG"; then
  : # tag 指向 HEAD，通過
else
  echo "build-acelib: tag $ACE_TAG 未指向 HEAD（來源不一致）" >&2
  exit 1
fi
log "來源已鎖定 commit $HEAD_COMMIT"

# ---- 2. 建置（自建 server JAR） ----
log "gradle clean build"
( cd "$STAGING_DIR" && ./gradlew clean build --no-daemon --console=plain ) \
  || { echo "build-acelib: 建置失敗" >&2; exit 1; }

JAR_SRC="$STAGING_DIR/build/libs/AceLib-${ACE_VERSION}.jar"
if [[ ! -f "$JAR_SRC" ]]; then
  echo "build-acelib: 產物不存在 $JAR_SRC" >&2
  exit 1
fi

# ---- 3. 驗證（版本 / 類別 / checksum） ----
if ! verify_acelib_jar "$JAR_SRC" "$ACE_EXPECTED_SHA256"; then
  echo "build-acelib: 產物驗證失敗" >&2
  exit 1
fi

# ---- 4. 輸出（僅寫入 OUTPUT_DIR，不觸及工作樹範圍外檔案） ----
mkdir -p "$OUTPUT_DIR"
cp -f "$JAR_SRC" "$OUTPUT_DIR/$OUT_NAME"
SHA="$(shasum -a 256 "$OUTPUT_DIR/$OUT_NAME" | awk '{print $1}')"
printf '%s  %s\n' "$SHA" "$OUT_NAME" > "$OUTPUT_DIR/$OUT_NAME.sha256"
cat > "$OUTPUT_DIR/$OUT_NAME.meta" <<EOF
repo=$ACE_REPO_URL
tag=$ACE_TAG
commit=$ACE_COMMIT
version=$ACE_VERSION
sha256=$SHA
built_at=$(date -u +%Y-%m-%dT%H:%M:%SZ)
EOF
log "產出 $OUTPUT_DIR/$OUT_NAME (sha256 $SHA)"
log "完成"
