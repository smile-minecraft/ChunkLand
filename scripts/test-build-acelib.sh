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

# contains_forbidden FILE PATTERN
#   在 FILE 中尋找符合 PATTERN（awk ERE）的「可執行」選項列。
#   前置：跳過註解行（以 # 開頭，允許前導空白）。
#   前置：合併以反斜線結尾的續行（shell line continuation），
#         使 `--dependency-verification \` 下一行 `off` 不會被拆成兩行而漏報。
#   使用單一 awk 程序讀入整檔後正規化，避免 `grep -v | grep -q` pipeline
#   在 `pipefail` 下因上游 SIGPIPE（grep -q 提前退出）而誤判為未命中。
#   回傳：0=找到（forbidden），1=未找到。
contains_forbidden() {
  local file="$1" pattern="$2"
  awk -v pat="$pattern" '
    /^[[:space:]]*#/ { next }
    {
      buf = $0
      while (buf ~ /\\$/) {
        sub(/\\$/, "", buf)
        if ((getline ln) <= 0) break
        buf = buf ln
      }
      if (buf ~ pat) { found=1; exit }
    }
    END { exit found ? 0 : 1 }
  ' "$file"
}

# ---------------------------------------------------------------------------
# 契約：來源固定與安全約束未被放寬（防止 verification 被全域關閉）
# ---------------------------------------------------------------------------
# 來源仍鎖定 v1.3.0 / commit / checksum
grep -q 'ACE_TAG="v1.3.0"' "$COMMON" ; check "來源 tag 固定 v1.3.0" 0 $?
grep -q 'ACE_COMMIT="e9c110e9fe19727c56cb0652b17b32d324082c90"' "$COMMON" ; check "來源 commit 固定 e9c110e" 0 $?
grep -q 'ACE_EXPECTED_SHA256="363871a3f38081105603243e0996946bf4f7bd3181cdd47d66ae13fc3b097f8d"' "$COMMON" ; check "產物 checksum 固定 363871a" 0 $?
# 輸出名稱與驗證函式仍存在
grep -q 'OUT_NAME="AceLib-${ACE_VERSION}.jar"' "$COMMON" ; check "OUT_NAME 固定" 0 $?
grep -q 'verify_acelib_jar' "$BUILD" ; check "build-acelib 仍呼叫 verify_acelib_jar" 0 $?
grep -q 'ACE_EXPECTED_SHA256' "$BUILD" ; check "build-acelib 仍核對 checksum" 0 $?
# 未使用全域 lenient / off / mavenLocal 繞過 verification
# 以 contains_forbidden 做正規化比對（跳過註解、合併續行、單一程序），
# 避免 multiline `--dependency-verification \` 下一行 `off` 漏報，
# 也避免 `grep -v | grep -q` 在 pipefail 下因 SIGPIPE 誤判。
if contains_forbidden "$BUILD" '--dependency-verification.*off'; then check "未使用 --dependency-verification off 繞過" 1 0; else check "未使用 --dependency-verification off 繞過" 0 0; fi
if contains_forbidden "$BUILD" 'lenient'; then check "未使用 lenient 繞過" 1 0; else check "未使用 lenient 繞過" 0 0; fi
if contains_forbidden "$BUILD" 'mavenLocal\(\)'; then check "未使用 mavenLocal() 繞過" 1 0; else check "未使用 mavenLocal() 繞過" 0 0; fi
if contains_forbidden "$COMMON" 'mavenLocal\(\)'; then check "common 未使用 mavenLocal()" 1 0; else check "common 未使用 mavenLocal()" 0 0; fi

# ---- 合成 forbidden fixture：multiline / 等號 / 註解 不得漏報、不得誤報 ----
# 以 contains_forbidden 對臨時 fixture 檔做正向（應找到）與負向（不應找到）比對，
# 證明 parser 能處理 shell line continuation、等號形式，且註解文字不被當成 option。
FX="$TMP/forbidden-fixture"
mkdir -p "$FX"
# 正向：multiline `--dependency-verification \` 下一行 `off`（舊 pipeline 會漏報）
printf '#!/usr/bin/env bash\n# 註解：--dependency-verification off 只是說明，不應被當成 option\n./gradlew clean jar \\\n  --dependency-verification \\\n  off \\\n  --no-daemon --console=plain\n' > "$FX/multiline-off.sh"
if contains_forbidden "$FX/multiline-off.sh" '--dependency-verification.*off'; then check "multiline off 應被判定 forbidden" 0 0; else check "multiline off 應被判定 forbidden" 0 1; fi
# 正向：等號形式
printf '#!/usr/bin/env bash\n./gradlew clean jar --dependency-verification=off --no-daemon\n' > "$FX/eq-off.sh"
if contains_forbidden "$FX/eq-off.sh" '--dependency-verification.*off'; then check "等號 off 應被判定 forbidden" 0 0; else check "等號 off 應被判定 forbidden" 0 1; fi
# 正向：multiline lenient
printf '#!/usr/bin/env bash\n# 註解 lenient 只是說明\n./gradlew clean jar \\\n  --dependency-verification \\\n  lenient \\\n  --no-daemon\n' > "$FX/multiline-lenient.sh"
if contains_forbidden "$FX/multiline-lenient.sh" 'lenient'; then check "multiline lenient 應被判定 forbidden" 0 0; else check "multiline lenient 應被判定 forbidden" 0 1; fi
# 負向：註解內文字不應被當成 option
printf '#!/usr/bin/env bash\n# build-acelib: --dependency-verification off 與 lenient 只是安全說明\n./gradlew clean jar --no-daemon\n' > "$FX/comment-only.sh"
if contains_forbidden "$FX/comment-only.sh" '--dependency-verification.*off'; then check "註解 off 不應誤報" 1 0; else check "註解 off 不應誤報" 1 1; fi
if contains_forbidden "$FX/comment-only.sh" 'lenient'; then check "註解 lenient 不應誤報" 1 0; else check "註解 lenient 不應誤報" 1 1; fi
if contains_forbidden "$FX/comment-only.sh" 'mavenLocal\(\)'; then check "註解 mavenLocal() 不應誤報" 1 0; else check "註解 mavenLocal() 不應誤報" 1 1; fi
# 負向：乾淨命令列
printf '#!/usr/bin/env bash\n./gradlew clean jar --no-daemon --console=plain\n' > "$FX/clean.sh"
if contains_forbidden "$FX/clean.sh" '--dependency-verification.*off'; then check "乾淨命令列不應誤報 off" 1 0; else check "乾淨命令列不應誤報 off" 1 1; fi
if contains_forbidden "$FX/clean.sh" 'lenient'; then check "乾淨命令列不應誤報 lenient" 1 0; else check "乾淨命令列不應誤報 lenient" 1 1; fi
# 來源固定：release URL 與 asset 名稱寫死在 common（不可覆寫）
if grep -q 'ACE_RELEASE_URL=' "$COMMON"; then check "common 有固定 ACE_RELEASE_URL" 0 0; else check "common 有固定 ACE_RELEASE_URL" 0 1; fi
if grep -q 'ACE_ASSET_NAME=' "$COMMON"; then check "common 有固定 ACE_ASSET_NAME" 0 0; else check "common 有固定 ACE_ASSET_NAME" 0 1; fi
# 固定 checksum 不可由環境覆寫（ACE_EXPECTED_SHA256_FIXED 為 literal，無 :- fallback）
if grep -q 'ACE_EXPECTED_SHA256_FIXED=' "$COMMON"; then check "common 有固定 ACE_EXPECTED_SHA256_FIXED" 0 0; else check "common 有固定 ACE_EXPECTED_SHA256_FIXED" 0 1; fi
if grep -q 'ACE_EXPECTED_SHA256="\${ACE_EXPECTED_SHA256:-' "$COMMON"; then check "checksum 無環境 fallback" 1 0; else check "checksum 無環境 fallback" 0 0; fi
# Exact contract：固定 URL、asset、版本、digest 皆為 literal（不可由環境覆寫）
grep -q 'ACE_RELEASE_URL="https://github.com/smile-minecraft/AceLib/releases/download/v1.3.0/AceLib-1.3.0.jar"' "$COMMON" ; check "固定 Release URL exact literal" 0 $?
grep -q 'ACE_ASSET_NAME="AceLib-1.3.0.jar"' "$COMMON" ; check "固定 asset name exact literal" 0 $?
grep -q 'ACE_VERSION="1.3.0"' "$COMMON" ; check "固定版本 exact literal" 0 $?
grep -q 'ACE_EXPECTED_SHA256="363871a3f38081105603243e0996946bf4f7bd3181cdd47d66ae13fc3b097f8d"' "$COMMON" ; check "固定 digest exact literal" 0 $?
# 環境覆寫被忽略：caller 設定 ACE_RELEASE_URL / ACE_EXPECTED_SHA256 不改變 fixed literal
# （無網路 assertion：僅驗證 source 後 fixed 值仍為固定 HTTPS URL 與完整 digest）
override_result="$(env ACE_RELEASE_URL='http://example.invalid/bad.jar' ACE_EXPECTED_SHA256='' bash -c 'source '"$COMMON"'; echo URL_FIXED="$ACE_RELEASE_URL_FIXED"; echo SHA_FIXED="$ACE_EXPECTED_SHA256_FIXED"')"
if printf '%s\n' "$override_result" | grep -q 'URL_FIXED=https://github.com/smile-minecraft/AceLib/releases/download/v1.3.0/AceLib-1.3.0.jar'; then
  check "env override URL 被忽略（仍為固定 HTTPS URL）" 0 0
else
  check "env override URL 被忽略（仍為固定 HTTPS URL）" 0 1
fi
if printf '%s\n' "$override_result" | grep -q 'SHA_FIXED=363871a3f38081105603243e0996946bf4f7bd3181cdd47d66ae13fc3b097f8d'; then
  check "env override checksum 被忽略（仍為固定 digest）" 0 0
else
  check "env override checksum 被忽略（仍為固定 digest）" 0 1
fi
# 不再使用 source clone / gradle build
if grep -q 'git clone' "$BUILD"; then check "build 不再使用 git clone" 1 0; else check "build 不再使用 git clone" 0 0; fi
if grep -q './gradlew' "$BUILD"; then check "build 不再使用 ./gradlew" 1 0; else check "build 不再使用 ./gradlew" 0 0; fi
# 使用 curl 下載固定 release asset（必須限制 HTTPS）
if grep -q 'curl' "$BUILD"; then check "build 使用 curl 下載" 0 0; else check "build 使用 curl 下載" 0 1; fi
# 使用 grep -- 避免 --proto 被當成選項
if grep -F -- '--proto' "$BUILD" >/dev/null 2>&1 && grep -F -- "'=https'" "$BUILD" >/dev/null 2>&1; then check "curl 限制 HTTPS 協議" 0 0; else check "curl 限制 HTTPS 協議" 0 1; fi
if grep -F -- '--proto-redir' "$BUILD" >/dev/null 2>&1 && grep -F -- "'=https'" "$BUILD" >/dev/null 2>&1; then check "curl 限制 HTTPS redirect" 0 0; else check "curl 限制 HTTPS redirect" 0 1; fi
# workflow 仍以 ACE_OUTPUT_DIR 共享且在 compile 前建立 AceLib
WF="$SCRIPT_DIR/../.github/workflows/build.yml"
if [[ -f "$WF" ]]; then
  grep -q 'ACE_OUTPUT_DIR' "$WF" ; check "workflow 仍使用 ACE_OUTPUT_DIR" 0 $?
  grep -q 'Fetch AceLib' "$WF" ; check "workflow 有 Fetch AceLib 步驟" 0 $?
  # 確保 Fetch AceLib 在 Build and test 之前
  ace_line=$(grep -n 'Fetch AceLib' "$WF" | head -1 | cut -d: -f1)
  gradle_line=$(grep -n 'Build and test with Gradle' "$WF" | head -1 | cut -d: -f1)
  if [[ -n "$ace_line" && -n "$gradle_line" && "$ace_line" -lt "$gradle_line" ]]; then check "workflow AceLib 建置在 Gradle 編譯前" 0 0; else check "workflow AceLib 建置在 Gradle 編譯前" 0 1; fi
  # workflow 兩輪都明確產生 main + sources JAR（sourcesJar 出現兩次）
  sources_jar_count=$(grep -c 'sourcesJar' "$WF" || true)
  if [[ "$sources_jar_count" -ge 2 ]]; then check "workflow 兩輪都有 sourcesJar" 0 0; else check "workflow 兩輪都有 sourcesJar" 0 1; fi
  # workflow 使用雙向 manifest/hash 比較（不再使用單向 ls|xargs loop）
  if grep -q 'ls .*\.jar' "$WF"; then check "workflow 不再使用 ls|xargs 單向 loop" 1 0; else check "workflow 不再使用 ls|xargs 單向 loop" 0 0; fi
  if grep -q 'sha256sum' "$WF"; then check "workflow 有 sha256sum 比較" 0 0; else check "workflow 有 sha256sum 比較" 0 1; fi
  # workflow 有雙向比較（同時反映 A/B 兩邊）
  if grep -q 'repro-b' "$WF"; then check "workflow 有 repro-b 目錄（雙向比較）" 0 0; else check "workflow 有 repro-b 目錄（雙向比較）" 0 1; fi
  if contains_forbidden "$WF" '--dependency-verification.*off'; then check "workflow 未關閉 verification" 1 0; else check "workflow 未關閉 verification" 0 0; fi
  if contains_forbidden "$WF" 'mavenLocal\(\)'; then check "workflow 未使用 mavenLocal" 1 0; else check "workflow 未使用 mavenLocal" 0 0; fi
else
  check "workflow 檔案存在" 0 1
fi

# ---------------------------------------------------------------------------
# 單元：verify_acelib_jar 對錯誤輸入安全失敗（Red 邏輯的核心）
# ---------------------------------------------------------------------------

# 缺 jar（固定 digest，驗證完整性不被跳過）
verify_acelib_jar "$TMP/missing.jar" "$ACE_EXPECTED_SHA256" ; check "缺 jar 應失敗" 1 $?

# 錯版本 jar（固定 digest，驗證完整性不被跳過）
mkdir -p "$TMP/badver/com/smile/acelib"
printf 'x' > "$TMP/badver/com/smile/acelib/AceLibVersion.class"
printf 'version: 9.9.9\n' > "$TMP/badver/plugin.yml"
( cd "$TMP/badver" && zip -q -r "$TMP/badver.jar" . )
verify_acelib_jar "$TMP/badver.jar" "$ACE_EXPECTED_SHA256" ; check "錯版本應失敗" 1 $?

# 缺 AceLibVersion.class（固定 digest，驗證完整性不被跳過）
mkdir -p "$TMP/noclass"
printf 'version: %s\n' "$ACE_VERSION" > "$TMP/noclass/plugin.yml"
( cd "$TMP/noclass" && zip -q -r "$TMP/noclass.jar" . )
verify_acelib_jar "$TMP/noclass.jar" "$ACE_EXPECTED_SHA256" ; check "缺 class 應失敗" 1 $?

# 正確版本 jar（合成 jar，含其實際 digest，驗證完整性不被跳過）
mkdir -p "$TMP/good/com/smile/acelib"
printf 'x' > "$TMP/good/com/smile/acelib/AceLibVersion.class"
printf 'version: %s\n' "$ACE_VERSION" > "$TMP/good/plugin.yml"
( cd "$TMP/good" && zip -q -r "$TMP/good.jar" . )
GOOD_SHA="$(shasum -a 256 "$TMP/good.jar" | awk '{print $1}')"
verify_acelib_jar "$TMP/good.jar" "$GOOD_SHA" ; check "正確版本應通過（含固定 checksum，合成 jar）" 0 $?

# 負向驗證：直接呼叫 verify_acelib_jar 傳空 digest 必須失敗（安全失敗，不允許跳過完整性驗證）
mkdir -p "$TMP/empty-sha/com/smile/acelib"
printf 'x' > "$TMP/empty-sha/com/smile/acelib/AceLibVersion.class"
printf 'version: %s\n' "$ACE_VERSION" > "$TMP/empty-sha/plugin.yml"
( cd "$TMP/empty-sha" && zip -q -r "$TMP/empty-sha.jar" . )
verify_acelib_jar "$TMP/empty-sha.jar" "" ; check "直接 verifier 空 checksum 必須失敗" 1 $?

# checksum 不符（合成 jar 傳入錯誤 digest，不依賴未定義的 OUT）
mkdir -p "$TMP/checksum-mismatch/com/smile/acelib"
printf 'x' > "$TMP/checksum-mismatch/com/smile/acelib/AceLibVersion.class"
printf 'version: %s\n' "$ACE_VERSION" > "$TMP/checksum-mismatch/plugin.yml"
( cd "$TMP/checksum-mismatch" && zip -q -r "$TMP/checksum-mismatch.jar" . )
verify_acelib_jar "$TMP/checksum-mismatch.jar" "deadbeefdeadbeefdeadbeefdeadbeefdeadbeefdeadbeefdeadbeefdeadbeefdeadbeef" ; check "checksum 不符應失敗" 1 $?

# evil prefix class path（substring 會誤通過，精確 entry 必須失敗）。
# 必須使用 fixture 自身 SHA 作為預期 digest，避免 checksum mismatch 掩蓋 class-path bug。
mkdir -p "$TMP/evil-prefix/evilcom/smile/acelib"
printf 'x' > "$TMP/evil-prefix/evilcom/smile/acelib/AceLibVersion.class"
printf 'version: %s\n' "$ACE_VERSION" > "$TMP/evil-prefix/plugin.yml"
( cd "$TMP/evil-prefix" && zip -q -r "$TMP/evil-prefix.jar" . )
EVIL_PREFIX_SHA="$(shasum -a 256 "$TMP/evil-prefix.jar" | awk '{print $1}')"
verify_acelib_jar "$TMP/evil-prefix.jar" "$EVIL_PREFIX_SHA" ; check "evil prefix class path 應失敗（fixture 自身 SHA）" 1 $?

# evil whitespace entry（awk \$NF 假匹配：'evil com/smile/acelib/AceLibVersion.class' 在
# `unzip -l` 輸出中，$NF = 'com/smile/acelib/AceLibVersion.class' 與預期字串相等；
# `unzip -Z1 + grep -Fx` 才能擋下）。使用 fixture 自身 SHA，避免被 checksum 不符掩蓋。
mkdir -p "$TMP/evil-space/com/smile/acelib"
printf 'x' > "$TMP/evil-space/com/smile/acelib/AceLibVersion.class"
printf 'version: %s\n' "$ACE_VERSION" > "$TMP/evil-space/plugin.yml"
( cd "$TMP/evil-space" && zip -q -r "$TMP/evil-space.jar" . )
# `zip` 不接受 entry 名稱含空白；以 zipfile 重寫為含空白 entry 的 fixture
python3 - << PYEOF
import zipfile
src = "$TMP/evil-space.jar"
dst = "$TMP/evil-space-renamed.jar"
with zipfile.ZipFile(src, 'r') as zin:
    with zipfile.ZipFile(dst, 'w', zipfile.ZIP_DEFLATED) as zout:
        for item in zin.infolist():
            data = zin.read(item.filename)
            new_name = item.filename
            if item.filename == "com/smile/acelib/AceLibVersion.class":
                new_name = "evil com/smile/acelib/AceLibVersion.class"
            new_info = zipfile.ZipInfo(filename=new_name, date_time=item.date_time)
            new_info.compress_type = item.compress_type
            new_info.external_attr = item.external_attr
            zout.writestr(new_info, data)
PYEOF
EVIL_SPACE_SHA="$(shasum -a 256 "$TMP/evil-space-renamed.jar" | awk '{print $1}')"
verify_acelib_jar "$TMP/evil-space-renamed.jar" "$EVIL_SPACE_SHA" ; check "evil whitespace entry 應失敗（fixture 自身 SHA）" 1 $?

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

# ---------------------------------------------------------------------------
# Stale cleanup：fake-curl 與既有產物 → 確認三個已知產物在失敗下載後不存在。
# fake-curl 提早建立，供下方 staging-ownership 失敗路徑共用。
# ---------------------------------------------------------------------------
FAKE_CURL_DIR="$TMP/fakecurl"
mkdir -p "$FAKE_CURL_DIR"
cat > "$FAKE_CURL_DIR/curl" << 'EOF'
#!/bin/bash
echo "fake curl: simulated download failure" >&2
exit 1
EOF
chmod +x "$FAKE_CURL_DIR/curl"

STAGING_OUT="$TMP/stale-out"
mkdir -p "$STAGING_OUT"
touch "$STAGING_OUT/$OUT_NAME"
touch "$STAGING_OUT/$OUT_NAME.sha256"
touch "$STAGING_OUT/$OUT_NAME.meta"
PATH="$FAKE_CURL_DIR:$PATH" bash "$BUILD" --output-dir "$STAGING_OUT" > "$TMP/stale.log" 2>&1
stale_rc=$?
check "stale cleanup: 失敗下載應非零退出" 1 $stale_rc
if [[ -f "$STAGING_OUT/$OUT_NAME" || -f "$STAGING_OUT/$OUT_NAME.sha256" || -f "$STAGING_OUT/$OUT_NAME.meta" ]]; then
  check "stale cleanup: 三個已知產物均不存在" 0 1
else
  check "stale cleanup: 三個已知產物均不存在" 0 0
fi

# ---------------------------------------------------------------------------
# Staging ownership：caller 提供的既有 ACE_STAGING_DIR 在成功／失敗後
# 都必須完整保留（含 sentinel 與其他檔案）；腳本不可 rm -rf 整個目錄。
# ---------------------------------------------------------------------------

# 失敗路徑：caller-owned staging + fake-curl 失敗 → staging 完整保留
STAGING_FAIL="$TMP/caller-staging-fail"
mkdir -p "$STAGING_FAIL/extra"
echo "important caller data" > "$STAGING_FAIL/caller-data.txt"
touch "$STAGING_FAIL/sentinel"
echo "extra" > "$STAGING_FAIL/extra/x.txt"
OUT_STAGE_FAIL="$TMP/out-staging-fail"
mkdir -p "$OUT_STAGE_FAIL"
PATH="$FAKE_CURL_DIR:$PATH" ACE_STAGING_DIR="$STAGING_FAIL" \
  bash "$BUILD" --output-dir "$OUT_STAGE_FAIL" > "$TMP/staging-fail.log" 2>&1 || true
if [[ -d "$STAGING_FAIL" \
   && -f "$STAGING_FAIL/sentinel" \
   && -f "$STAGING_FAIL/caller-data.txt" \
   && -f "$STAGING_FAIL/extra/x.txt" ]]; then
  check "caller-owned staging（失敗路徑）：目錄、sentinel、其他檔案都保留" 0 0
else
  check "caller-owned staging（失敗路徑）：目錄、sentinel、其他檔案都保留" 0 1
fi

# 成功路徑：caller-owned staging + 真實 release 下載 → staging 完整保留
STAGING_OK="$TMP/caller-staging-ok"
mkdir -p "$STAGING_OK/extra"
echo "important caller data" > "$STAGING_OK/caller-data.txt"
touch "$STAGING_OK/sentinel"
echo "extra" > "$STAGING_OK/extra/x.txt"
OUT_STAGE_OK="$TMP/out-staging-ok"
mkdir -p "$OUT_STAGE_OK"
ACE_STAGING_DIR="$STAGING_OK" \
  bash "$BUILD" --output-dir "$OUT_STAGE_OK" > "$TMP/staging-ok.log" 2>&1
stage_ok_rc=$?
check "caller-owned staging（成功路徑）：build 應成功" 0 $stage_ok_rc
if [[ -d "$STAGING_OK" \
   && -f "$STAGING_OK/sentinel" \
   && -f "$STAGING_OK/caller-data.txt" \
   && -f "$STAGING_OK/extra/x.txt" ]]; then
  check "caller-owned staging（成功路徑）：目錄、sentinel、其他檔案都保留" 0 0
else
  check "caller-owned staging（成功路徑）：目錄、sentinel、其他檔案都保留" 0 1
fi

# ---------------------------------------------------------------------------
# Stale cleanup preserve other files：失敗下載時只清理三個已知產物，
# 保留 OUTPUT_DIR 其他檔案（含子目錄）。
# ---------------------------------------------------------------------------
PRESERVE_OUT="$TMP/preserve-out"
mkdir -p "$PRESERVE_OUT/sub"
echo "caller-owned important file" > "$PRESERVE_OUT/caller.txt"
echo "more data" > "$PRESERVE_OUT/sub/data.txt"
touch "$PRESERVE_OUT/$OUT_NAME"
touch "$PRESERVE_OUT/$OUT_NAME.sha256"
touch "$PRESERVE_OUT/$OUT_NAME.meta"
PATH="$FAKE_CURL_DIR:$PATH" bash "$BUILD" --output-dir "$PRESERVE_OUT" > "$TMP/preserve.log" 2>&1 || true
if [[ -f "$PRESERVE_OUT/caller.txt" \
   && -f "$PRESERVE_OUT/sub/data.txt" \
   && ! -f "$PRESERVE_OUT/$OUT_NAME" \
   && ! -f "$PRESERVE_OUT/$OUT_NAME.sha256" \
   && ! -f "$PRESERVE_OUT/$OUT_NAME.meta" ]]; then
  check "stale cleanup preserve: OUTPUT_DIR 其他檔案保留、三個已知產物清理" 0 0
else
  check "stale cleanup preserve: OUTPUT_DIR 其他檔案保留、三個已知產物清理" 0 1
fi

# ---------------------------------------------------------------------------
# 回歸：post-copy 竄改必須 fail-closed（Red → Green）
#  使用 fake curl（從 deterministic local fixture 取得合法 JAR）+ fake cp
#  （命中預期 output target 時先寫 cp-hit marker，再寫入 tampered bytes）。
#  fixture 來自前段整合測試已驗證的 $OUT/$OUT_NAME（合法、可重複），不依賴
#  $HOME/.cache 或 /usr/bin/curl fallback；fixture 缺失時計入 FAIL。
#  fake cp 命中預期 target 時必須先寫 marker，測試明確檢查 marker；缺 marker
#  計入 FAIL（不可被其他 rc / 產物清理條件誤判為通過）。
#  修復後必須非零退出、三個已知產物被清理、其他檔案與 caller staging 保留。
#  marker / fixture / target path 一律以環境變數傳入 child process，避免
#  heredoc 意外展開。
# ---------------------------------------------------------------------------
REGRESS_TMP="$TMP/regress-postcopy"
mkdir -p "$REGRESS_TMP"
FAKE_REGRESS_BIN="$REGRESS_TMP/fakebin"
mkdir -p "$FAKE_REGRESS_BIN"
CP_HIT_MARKER="$REGRESS_TMP/cp-hit"
REGRESS_FIXTURE="$OUT/$OUT_NAME"
if [[ -s "$REGRESS_FIXTURE" ]]; then
  check "回歸 post-copy：deterministic local fixture 存在" 0 0
else
  check "回歸 post-copy：deterministic local fixture 存在" 0 1
fi
# fake curl：使用 $FAKE_CURL_FIXTURE 作為合法 JAR 來源；缺 fixture 或 dest 時明確非零退出。
cat > "$FAKE_REGRESS_BIN/curl" <<'REGCP'
#!/usr/bin/env bash
dest=""
prev=""
for arg in "$@"; do
  if [[ "$prev" == "-o" ]]; then dest="$arg"; fi
  prev="$arg"
done
if [[ -z "$dest" ]]; then
  echo "fake curl: 找不到 -o dest（fake curl 必須有 dest 才能提供 fixture）" >&2
  exit 1
fi
fixture="${FAKE_CURL_FIXTURE:-}"
if [[ -z "$fixture" || ! -s "$fixture" ]]; then
  echo "fake curl: deterministic fixture 缺失或為空 (FAKE_CURL_FIXTURE=$fixture)" >&2
  exit 1
fi
/bin/cp -f "$fixture" "$dest"
echo "fake curl: staged $dest from fixture" >&2
exit 0
REGCP
chmod +x "$FAKE_REGRESS_BIN/curl"
# fake cp：命中 $FAKE_CP_TARGET_PATTERN 時先寫 $FAKE_CP_HIT_MARKER，再寫入 tampered bytes；
# 未命中則 exec real cp（不污染其他 cp 呼叫）。
cat > "$FAKE_REGRESS_BIN/cp" <<'REGCP'
#!/usr/bin/env bash
marker="${FAKE_CP_HIT_MARKER:-}"
target_pat="${FAKE_CP_TARGET_PATTERN:-}"
dest="${@: -1}"
if [[ -n "$marker" && -n "$target_pat" && "$dest" == *"$target_pat"* ]]; then
  : > "$marker"
  printf 'tampered-after-verification' > "$dest"
  echo "fake cp: hit marker=$marker, tampered $dest" >&2
  exit 0
else
  exec /bin/cp "$@"
fi
REGCP
chmod +x "$FAKE_REGRESS_BIN/cp"

STAGING_REGRESS="$REGRESS_TMP/caller-staging"
OUTPUT_REGRESS="$REGRESS_TMP/output"
mkdir -p "$STAGING_REGRESS" "$OUTPUT_REGRESS"
echo "sentinel" > "$STAGING_REGRESS/sentinel"
echo "caller data" > "$STAGING_REGRESS/caller-data.txt"
mkdir -p "$STAGING_REGRESS/extra"
echo "extra" > "$STAGING_REGRESS/extra/x.txt"
mkdir -p "$OUTPUT_REGRESS/sub"
echo "preserve" > "$OUTPUT_REGRESS/caller.txt"
echo "more" > "$OUTPUT_REGRESS/sub/data.txt"

set +e
PATH="$FAKE_REGRESS_BIN:$PATH" \
  ACE_STAGING_DIR="$STAGING_REGRESS" \
  FAKE_CURL_FIXTURE="$REGRESS_FIXTURE" \
  FAKE_CP_HIT_MARKER="$CP_HIT_MARKER" \
  FAKE_CP_TARGET_PATTERN="$OUT_NAME" \
  bash "$BUILD" --output-dir "$OUTPUT_REGRESS" > "$REGRESS_TMP/build.log" 2>&1
regress_rc=$?
set -e
if [[ $regress_rc -ne 0 ]]; then check "回歸 post-copy 竄改：應非零退出" 0 0; else check "回歸 post-copy 竄改：應非零退出" 0 1; fi
# cp-hit 契約：fake cp 必須真的命中預期 output target；缺 marker 計入 FAIL。
# 沒有這個斷言會出現 false green：fake cp 即使從未命中（fake curl 寫 garbage 也能讓
# verify_acelib_jar 抓到 SHA 不符），其餘 4 個斷言仍全 PASS（見 red-harness 紀錄）。
if [[ -f "$CP_HIT_MARKER" ]]; then
  check "回歸 post-copy 竄改：fake cp 命中預期 output target" 0 0
else
  check "回歸 post-copy 竄改：fake cp 命中預期 output target" 0 1
fi
if [[ ! -f "$OUTPUT_REGRESS/$OUT_NAME" && ! -f "$OUTPUT_REGRESS/$OUT_NAME.sha256" && ! -f "$OUTPUT_REGRESS/$OUT_NAME.meta" ]]; then check "回歸 post-copy 竄改：三個已知產物被清理" 0 0; else check "回歸 post-copy 竄改：三個已知產物被清理" 0 1; fi
if [[ -f "$OUTPUT_REGRESS/caller.txt" && -f "$OUTPUT_REGRESS/sub/data.txt" ]]; then check "回歸 post-copy 竄改：OUTPUT 其他檔案保留" 0 0; else check "回歸 post-copy 竄改：OUTPUT 其他檔案保留" 0 1; fi
if [[ -d "$STAGING_REGRESS" && -f "$STAGING_REGRESS/sentinel" && -f "$STAGING_REGRESS/caller-data.txt" && -f "$STAGING_REGRESS/extra/x.txt" ]]; then check "回歸 post-copy 竄改：caller staging 完整保留" 0 0; else check "回歸 post-copy 竄改：caller staging 完整保留" 0 1; fi
if [[ -f "$OUTPUT_REGRESS/$OUT_NAME" ]] && grep -q "tampered-after-verification" "$OUTPUT_REGRESS/$OUT_NAME" 2>/dev/null; then check "回歸 post-copy 竄改：不留下竄改 bytes" 1 0; else check "回歸 post-copy 竄改：不留下竄改 bytes" 0 0; fi
# 契約：build 腳本必須在 cp 後、sidecar 前對 OUTPUT 呼叫固定 verifier
if grep -q 'verify_acelib_jar.*OUTPUT_DIR.*EXPECTED_SHA256' "$BUILD"; then check "契約 post-copy verifier 存在（OUTPUT_DIR + EXPECTED_SHA256）" 0 0; else check "契約 post-copy verifier 存在（OUTPUT_DIR + EXPECTED_SHA256）" 0 1; fi
# 正常路徑：sidecar 建立前後 output 均通過同一 verifier，且 sidecar SHA 與實際一致
if [[ -f "$OUT/$OUT_NAME" ]]; then
  verify_acelib_jar "$OUT/$OUT_NAME" "$ACE_EXPECTED_SHA256" ; check "正常 output 側車建立後仍通過固定 verifier" 0 $?
  sidecar_sha="$(awk '{print $1}' "$OUT/$OUT_NAME.sha256" 2>/dev/null || true)"
  actual_sha="$(shasum -a 256 "$OUT/$OUT_NAME" 2>/dev/null | awk '{print $1}' || true)"
  if [[ -n "$sidecar_sha" && "$sidecar_sha" == "$actual_sha" && "$actual_sha" == "$ACE_EXPECTED_SHA256" ]]; then check "正常 sidecar SHA 與實際 output 一致且等於固定 digest" 0 0; else check "正常 sidecar SHA 與實際 output 一致且等於固定 digest" 0 1; fi
  meta_sha="$(grep '^sha256=' "$OUT/$OUT_NAME.meta" 2>/dev/null | cut -d= -f2 || true)"
  if [[ "$meta_sha" == "$ACE_EXPECTED_SHA256" && "$meta_sha" == "$actual_sha" ]]; then check "正常 meta sha256 與實際 output 一致" 0 0; else check "正常 meta sha256 與實際 output 一致" 0 1; fi
else
  check "正常 output 側車建立後仍通過固定 verifier" 0 1
  check "正常 sidecar SHA 與實際 output 一致且等於固定 digest" 0 1
  check "正常 meta sha256 與實際 output 一致" 0 1
fi

# ---------------------------------------------------------------------------
# 側車 provenance：commit 必須為實際 tag object commit
# ---------------------------------------------------------------------------
if [[ -f "$OUT/$OUT_NAME.meta" ]]; then
  meta_commit="$(grep '^commit=' "$OUT/$OUT_NAME.meta" | cut -d= -f2)"
  if [[ "$meta_commit" == "e9c110e9fe19727c56cb0652b17b32d324082c90" ]]; then
    check "sidecar provenance commit 正確" 0 0
  else
    check "sidecar provenance commit 正確" 0 1
  fi
else
  check "sidecar provenance commit 正確" 0 1
fi

echo "----"
echo "PASS=$pass FAIL=$fail"
[[ $fail -eq 0 ]]
