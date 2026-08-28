#!/usr/bin/env bash
# [TEST:P2] AceLib 自建流程的 Red/Green 與邊界驗證。
#
# 設計：本腳本同時承擔 TDD 的 Red 與 Green。
#   - Red：實作（acelib-common.sh / build-acelib.sh）不存在時，source 失敗，
#     整個套件紅掉；或 build-acelib.sh 不存在時整合測試紅掉。
#   - Green：實作就位後，單元（錯誤輸入安全失敗）+ 整合（真實建置產出合法 jar）
#     + 可重現（兩次建置 SHA 一致）全綠。
#
# 用法：scripts/test-build-acelib.sh
# 依賴：bash、git、unzip、zip、shasum（macOS/Linux 預裝）。
set -o pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
COMMON="$SCRIPT_DIR/acelib-common.sh"
BUILD="$SCRIPT_DIR/build-acelib.sh"

# 若共用定義不存在，直接 Red 失敗（實作尚未就位）。
if [[ ! -f "$COMMON" ]]; then
  echo "RED: $COMMON 不存在（實作尚未就位）"
  exit 1
fi
# shellcheck source=acelib-common.sh
source "$COMMON"

pass=0
fail=0
# check 描述 預期_rc 實際_rc
check() {
  local desc="$1" exp="$2" act="$3"
  if [[ "$exp" == "$act" ]]; then
    echo "PASS: $desc"
    pass=$((pass + 1))
  else
    echo "FAIL: $desc (預期 rc=$exp, 實際 rc=$act)"
    fail=$((fail + 1))
  fi
}

TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

# ---------------------------------------------------------------------------
# 單元：verify_acelib_jar 對錯誤輸入安全失敗（Red 邏輯的核心）
# ---------------------------------------------------------------------------

# 缺 jar
verify_acelib_jar "$TMP/missing.jar" "" ; check "缺 jar 應失敗" 1 $?

# 錯版本 jar（plugin.yml 版本不是 1.1.2）
mkdir -p "$TMP/badver/com/smile/acelib"
printf 'x' > "$TMP/badver/com/smile/acelib/AceLibVersion.class"
printf 'version: 9.9.9\n' > "$TMP/badver/plugin.yml"
( cd "$TMP/badver" && zip -q -r "$TMP/badver.jar" . )
verify_acelib_jar "$TMP/badver.jar" "" ; check "錯版本應失敗" 1 $?

# 缺 AceLibVersion.class
printf 'version: %s\n' "$ACE_VERSION" > "$TMP/noclass/plugin.yml" 2>/dev/null || {
  mkdir -p "$TMP/noclass"; printf 'version: %s\n' "$ACE_VERSION" > "$TMP/noclass/plugin.yml"
}
( cd "$TMP/noclass" && zip -q -r "$TMP/noclass.jar" . )
verify_acelib_jar "$TMP/noclass.jar" "" ; check "缺 class 應失敗" 1 $?

# 正確版本 jar（不檢 checksum）
mkdir -p "$TMP/good/com/smile/acelib"
printf 'x' > "$TMP/good/com/smile/acelib/AceLibVersion.class"
printf 'version: %s\n' "$ACE_VERSION" > "$TMP/good/plugin.yml"
( cd "$TMP/good" && zip -q -r "$TMP/good.jar" . )
verify_acelib_jar "$TMP/good.jar" "" ; check "正確版本應通過" 0 $?

# checksum 不符
verify_acelib_jar "$TMP/good.jar" "deadbeefdeadbeef" ; check "checksum 不符應失敗" 1 $?

# ---------------------------------------------------------------------------
# 整合：執行 build-acelib.sh（Green）
# ---------------------------------------------------------------------------
if [[ ! -f "$BUILD" ]]; then
  echo "RED: $BUILD 不存在（實作尚未就位）"
  fail=$((fail + 1))
else
  OUT="$TMP/out"
  bash "$BUILD" --output-dir "$OUT" > "$TMP/build.log" 2>&1
  build_rc=$?
  check "build-acelib.sh 成功" 0 $build_rc
  if [[ $build_rc -eq 0 ]]; then
    if [[ -s "$OUT/$OUT_NAME" ]]; then check "產出 jar 非空" 0 0; else check "產出 jar 非空" 0 1; fi
    # 用 verifier 再驗一次（含 checksum）
    verify_acelib_jar "$OUT/$OUT_NAME" "$ACE_EXPECTED_SHA256" ; check "產出 jar 通過完整驗證(版本+class+checksum)" 0 $?
    # 側車檔存在
    [[ -f "$OUT/$OUT_NAME.sha256" ]] && [[ -f "$OUT/$OUT_NAME.meta" ]] \
      && check "產出 sidecar(.sha256/.meta) 存在" 0 0 \
      || check "產出 sidecar(.sha256/.meta) 存在" 0 1
  else
    echo "---- build log ----"; tail -20 "$TMP/build.log"
  fi

  # -------------------------------------------------------------------------
  # 可重現：再跑一次，SHA 應相同
  # -------------------------------------------------------------------------
  OUT2="$TMP/out2"
  bash "$BUILD" --output-dir "$OUT2" > "$TMP/build2.log" 2>&1
  if [[ $? -eq 0 && -s "$OUT2/$OUT_NAME" ]]; then
    s1="$(shasum -a 256 "$OUT/$OUT_NAME" | awk '{print $1}')"
    s2="$(shasum -a 256 "$OUT2/$OUT_NAME" | awk '{print $1}')"
    if [[ "$s1" == "$s2" ]]; then check "重複建置 SHA 一致（可重現）" 0 0
    else check "重複建置 SHA 一致（可重現）" 0 1; fi
  else
    check "重複建置 SHA 一致（可重現）" 0 1
  fi
fi

echo "----"
echo "PASS=$pass FAIL=$fail"
[[ $fail -eq 0 ]]
