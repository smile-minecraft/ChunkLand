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
# 契約：來源固定與安全約束未被放寬（防止 verification 被全域關閉）
# ---------------------------------------------------------------------------
# 來源仍鎖定 v1.2.0 / commit / checksum
grep -q 'ACE_TAG="v1.2.0"' "$COMMON" ; check "來源 tag 固定 v1.2.0" 0 $?
grep -q 'ACE_COMMIT="a2ceb90b18648623b8146ba1e68d9f3f6ec41aeb"' "$COMMON" ; check "來源 commit 固定 a2ceb90" 0 $?
grep -q 'ACE_EXPECTED_SHA256_DEFAULT="da9f196b47c2b28c6db443d102236b27c1a1bbdf7dd3e7c22470170420935278"' "$COMMON" ; check "產物 checksum 固定 da9f196" 0 $?
# 輸出名稱與驗證函式仍存在
grep -q 'OUT_NAME="AceLib-${ACE_VERSION}.jar"' "$COMMON" ; check "OUT_NAME 固定" 0 $?
grep -q 'verify_acelib_jar' "$BUILD" ; check "build-acelib 仍呼叫 verify_acelib_jar" 0 $?
grep -q 'ACE_EXPECTED_SHA256' "$BUILD" ; check "build-acelib 仍核對 checksum" 0 $?
# 未使用全域 lenient / off / mavenLocal 繞過 verification（忽略註解行，避免誤判）
if grep -v '^[[:space:]]*#' "$BUILD" | grep -q -- '--dependency-verification.*off'; then check "未使用 --dependency-verification off 繞過" 1 0; else check "未使用 --dependency-verification off 繞過" 0 0; fi
if grep -v '^[[:space:]]*#' "$BUILD" | grep -q 'lenient'; then check "未使用 lenient 繞過" 1 0; else check "未使用 lenient 繞過" 0 0; fi
if grep -v '^[[:space:]]*#' "$BUILD" | grep -q 'mavenLocal()'; then check "未使用 mavenLocal() 繞過" 1 0; else check "未使用 mavenLocal() 繞過" 0 0; fi
if grep -v '^[[:space:]]*#' "$COMMON" | grep -q 'mavenLocal()'; then check "common 未使用 mavenLocal()" 1 0; else check "common 未使用 mavenLocal()" 0 0; fi
# 建置僅用 clean jar，避免觸發僅測試配置需要的 adventure-bom verification
grep -q './gradlew clean jar' "$BUILD" ; check "build-acelib 使用 clean jar（避免測試配置 BOM）" 0 $?
# workflow 仍以 ACE_OUTPUT_DIR 共享且在 compile 前建立 AceLib
WF="$SCRIPT_DIR/../.github/workflows/build.yml"
if [[ -f "$WF" ]]; then
  grep -q 'ACE_OUTPUT_DIR' "$WF" ; check "workflow 仍使用 ACE_OUTPUT_DIR" 0 $?
  grep -q 'Build AceLib' "$WF" ; check "workflow 仍有 Build AceLib 步驟" 0 $?
  # 確保 Build AceLib 在 Build and test 之前
  ace_line=$(grep -n 'Build AceLib' "$WF" | head -1 | cut -d: -f1)
  gradle_line=$(grep -n 'Build and test with Gradle' "$WF" | head -1 | cut -d: -f1)
  if [[ -n "$ace_line" && -n "$gradle_line" && "$ace_line" -lt "$gradle_line" ]]; then check "workflow AceLib 建置在 Gradle 編譯前" 0 0; else check "workflow AceLib 建置在 Gradle 編譯前" 0 1; fi
  if grep -v '^[[:space:]]*#' "$WF" | grep -q -- '--dependency-verification.*off'; then check "workflow 未關閉 verification" 1 0; else check "workflow 未關閉 verification" 0 0; fi
  if grep -v '^[[:space:]]*#' "$WF" | grep -q 'mavenLocal()'; then check "workflow 未使用 mavenLocal" 1 0; else check "workflow 未使用 mavenLocal" 0 0; fi
else
  check "workflow 檔案存在" 0 1
fi

# ---------------------------------------------------------------------------
# 單元：verify_acelib_jar 對錯誤輸入安全失敗（Red 邏輯的核心）
# ---------------------------------------------------------------------------

# 缺 jar
verify_acelib_jar "$TMP/missing.jar" "" ; check "缺 jar 應失敗" 1 $?

# 錯版本 jar（plugin.yml 版本不是 1.2.0）
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
