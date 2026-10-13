#!/usr/bin/env bash
# [TEST:P2] AceLib 自建流程的 Red/Green 與邊界驗證。
#
# 設計：本腳本同時承擔 TDD 的 Red 與 Green。
#   - Red：實作（acelib-common.sh / build-acelib.sh）不存在或未升級時，
#     整個套件紅掉；或未產出第二個附件時整合測試紅掉。
#   - Green：實作就位後，單元（錯誤輸入安全失敗）+ 整合（真實建置產出合法 jar）
#     + 可重現（兩次建置 SHA 一致）全綠。
#
# 用法：scripts/test-build-acelib.sh
# 依賴：bash、git、unzip、zip、shasum、python3（macOS/Linux 預裝）。
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
# 來源鎖定 v1.5.0 / commit / tag object / checksum
grep -q 'ACE_TAG="v1.5.0"' "$COMMON" ; check "來源 tag 固定 v1.5.0" 0 $?
grep -q 'ACE_TAG_OBJECT="891fa4ccf7698bdb46e346e91bedb30365f17c43"' "$COMMON" ; check "來源 tag object 固定 891fa4c" 0 $?
grep -q 'ACE_COMMIT="4ace7db641a4517faac6208dac28f7fcd72f6f7c"' "$COMMON" ; check "來源 commit 固定 4ace7db" 0 $?
grep -q 'ACE_EXPECTED_SHA256="346be2a8ecee247bf67fef71e804f829bdb8cc4f5be8980adc0b2b594facca9b"' "$COMMON" ; check "產物 checksum 固定 346be2a" 0 $?
grep -q 'ACE_FIXTURES_EXPECTED_SHA256="6c12c677c6beed17b4893050c42f547bfcf588496631673b52b557de3748a85f"' "$COMMON" ; check "fixtures checksum 固定 6c12c67" 0 $?

# 輸出名稱與驗證函式仍存在
grep -q 'OUT_NAME="AceLib-${ACE_VERSION}.jar"' "$COMMON" ; check "OUT_NAME 固定" 0 $?
grep -q 'FIXTURES_OUT_NAME="AceLib-test-fixtures-${ACE_VERSION}.jar"' "$COMMON" ; check "FIXTURES_OUT_NAME 固定" 0 $?
grep -q 'verify_acelib_jar' "$BUILD" ; check "build-acelib 仍呼叫 verify_acelib_jar" 0 $?
grep -q 'verify_acelib_fixtures_jar' "$BUILD" ; check "build-acelib 呼叫 verify_acelib_fixtures_jar" 0 $?
grep -q 'ACE_EXPECTED_SHA256' "$BUILD" ; check "build-acelib 仍核對 runtime checksum" 0 $?
grep -q 'ACE_FIXTURES_EXPECTED_SHA256' "$BUILD" ; check "build-acelib 核對 fixtures checksum" 0 $?

# 未使用全域 lenient / off / mavenLocal 繞過 verification
if contains_forbidden "$BUILD" '--dependency-verification.*off'; then check "未使用 --dependency-verification off 繞過" 1 0; else check "未使用 --dependency-verification off 繞過" 0 0; fi
if contains_forbidden "$BUILD" 'lenient'; then check "未使用 lenient 繞過" 1 0; else check "未使用 lenient 繞過" 0 0; fi
if contains_forbidden "$BUILD" 'mavenLocal\(\)'; then check "未使用 mavenLocal() 繞過" 1 0; else check "未使用 mavenLocal() 繞過" 0 0; fi
if contains_forbidden "$COMMON" 'mavenLocal\(\)'; then check "common 未使用 mavenLocal()" 1 0; else check "common 未使用 mavenLocal()" 0 0; fi

# 合成 forbidden fixture
FX="$TMP/forbidden-fixture"
mkdir -p "$FX"
printf '#!/usr/bin/env bash\n./gradlew clean jar \\\n  --dependency-verification \\\n  off \\\n  --no-daemon\n' > "$FX/multiline-off.sh"
if contains_forbidden "$FX/multiline-off.sh" '--dependency-verification.*off'; then check "multiline off 應被判定 forbidden" 0 0; else check "multiline off 應被判定 forbidden" 0 1; fi
printf '#!/usr/bin/env bash\n./gradlew clean jar --dependency-verification=off --no-daemon\n' > "$FX/eq-off.sh"
if contains_forbidden "$FX/eq-off.sh" '--dependency-verification.*off'; then check "等號 off 應被判定 forbidden" 0 0; else check "等號 off 應被判定 forbidden" 0 1; fi
printf '#!/usr/bin/env bash\n./gradlew clean jar \\\n  --dependency-verification \\\n  lenient \\\n  --no-daemon\n' > "$FX/multiline-lenient.sh"
if contains_forbidden "$FX/multiline-lenient.sh" 'lenient'; then check "multiline lenient 應被判定 forbidden" 0 0; else check "multiline lenient 應被判定 forbidden" 0 1; fi
printf '#!/usr/bin/env bash\n# build-acelib: --dependency-verification off 與 lenient 只是安全說明\n./gradlew clean jar --no-daemon\n' > "$FX/comment-only.sh"
if contains_forbidden "$FX/comment-only.sh" '--dependency-verification.*off'; then check "註解 off 不應誤報" 1 0; else check "註解 off 不應誤報" 1 1; fi
if contains_forbidden "$FX/comment-only.sh" 'lenient'; then check "註解 lenient 不應誤報" 1 0; else check "註解 lenient 不應誤報" 1 1; fi
if contains_forbidden "$FX/comment-only.sh" 'mavenLocal\(\)'; then check "註解 mavenLocal() 不應誤報" 1 0; else check "註解 mavenLocal() 不應誤報" 1 1; fi
printf '#!/usr/bin/env bash\n./gradlew clean jar --no-daemon --console=plain\n' > "$FX/clean.sh"
if contains_forbidden "$FX/clean.sh" '--dependency-verification.*off'; then check "乾淨命令列不應誤報 off" 1 0; else check "乾淨命令列不應誤報 off" 1 1; fi
if contains_forbidden "$FX/clean.sh" 'lenient'; then check "乾淨命令列不應誤報 lenient" 1 0; else check "乾淨命令列不應誤報 lenient" 1 1; fi

# 來源固定：release URL 與 asset 名稱寫死在 common
if grep -q 'ACE_RELEASE_URL=' "$COMMON"; then check "common 有固定 ACE_RELEASE_URL" 0 0; else check "common 有固定 ACE_RELEASE_URL" 0 1; fi
if grep -q 'ACE_ASSET_NAME=' "$COMMON"; then check "common 有固定 ACE_ASSET_NAME" 0 0; else check "common 有固定 ACE_ASSET_NAME" 0 1; fi
if grep -q 'ACE_FIXTURES_RELEASE_URL=' "$COMMON"; then check "common 有固定 ACE_FIXTURES_RELEASE_URL" 0 0; else check "common 有固定 ACE_FIXTURES_RELEASE_URL" 0 1; fi
if grep -q 'ACE_FIXTURES_ASSET_NAME=' "$COMMON"; then check "common 有固定 ACE_FIXTURES_ASSET_NAME" 0 0; else check "common 有固定 ACE_FIXTURES_ASSET_NAME" 0 1; fi

# 固定 checksum 不可由環境覆寫
if grep -q 'ACE_EXPECTED_SHA256_FIXED=' "$COMMON"; then check "common 有固定 ACE_EXPECTED_SHA256_FIXED" 0 0; else check "common 有固定 ACE_EXPECTED_SHA256_FIXED" 0 1; fi
if grep -q 'ACE_FIXTURES_EXPECTED_SHA256_FIXED=' "$COMMON"; then check "common 有固定 ACE_FIXTURES_EXPECTED_SHA256_FIXED" 0 0; else check "common 有固定 ACE_FIXTURES_EXPECTED_SHA256_FIXED" 0 1; fi
if grep -q 'ACE_EXPECTED_SHA256="\${ACE_EXPECTED_SHA256:-' "$COMMON"; then check "checksum 無環境 fallback" 1 0; else check "checksum 無環境 fallback" 0 0; fi
if grep -q 'ACE_FIXTURES_EXPECTED_SHA256="\${ACE_FIXTURES_EXPECTED_SHA256:-' "$COMMON"; then check "fixtures checksum 無環境 fallback" 1 0; else check "fixtures checksum 無環境 fallback" 0 0; fi

# Exact contract：固定 URL、asset、版本、digest 皆為 literal
grep -q 'ACE_RELEASE_URL="https://github.com/smile-minecraft/AceLib/releases/download/v1.5.0/AceLib-1.5.0.jar"' "$COMMON" ; check "固定 Release URL exact literal" 0 $?
grep -q 'ACE_ASSET_NAME="AceLib-1.5.0.jar"' "$COMMON" ; check "固定 asset name exact literal" 0 $?
grep -q 'ACE_FIXTURES_RELEASE_URL="https://github.com/smile-minecraft/AceLib/releases/download/v1.5.0/AceLib-1.5.0-test-fixtures.jar"' "$COMMON" ; check "固定 Fixtures Release URL exact literal" 0 $?
grep -q 'ACE_FIXTURES_ASSET_NAME="AceLib-1.5.0-test-fixtures.jar"' "$COMMON" ; check "固定 Fixtures asset name exact literal" 0 $?
grep -q 'ACE_VERSION="1.5.0"' "$COMMON" ; check "固定版本 exact literal" 0 $?
grep -q 'ACE_EXPECTED_SHA256="346be2a8ecee247bf67fef71e804f829bdb8cc4f5be8980adc0b2b594facca9b"' "$COMMON" ; check "固定 digest exact literal" 0 $?
grep -q 'ACE_FIXTURES_EXPECTED_SHA256="6c12c677c6beed17b4893050c42f547bfcf588496631673b52b557de3748a85f"' "$COMMON" ; check "固定 fixtures digest exact literal" 0 $?

# 環境覆寫被忽略
override_result="$(env ACE_RELEASE_URL='http://example.invalid/bad.jar' ACE_EXPECTED_SHA256='' ACE_FIXTURES_RELEASE_URL='http://example.invalid/bad-fix.jar' ACE_FIXTURES_EXPECTED_SHA256='' bash -c 'source '"$COMMON"'; echo URL_FIXED="$ACE_RELEASE_URL_FIXED"; echo SHA_FIXED="$ACE_EXPECTED_SHA256_FIXED"; echo FIX_URL_FIXED="$ACE_FIXTURES_RELEASE_URL_FIXED"; echo FIX_SHA_FIXED="$ACE_FIXTURES_EXPECTED_SHA256_FIXED"')"
if printf '%s\n' "$override_result" | grep -q 'URL_FIXED=https://github.com/smile-minecraft/AceLib/releases/download/v1.5.0/AceLib-1.5.0.jar'; then
  check "env override URL 被忽略（仍為固定 HTTPS URL）" 0 0
else
  check "env override URL 被忽略（仍為固定 HTTPS URL）" 0 1
fi
if printf '%s\n' "$override_result" | grep -q 'SHA_FIXED=346be2a8ecee247bf67fef71e804f829bdb8cc4f5be8980adc0b2b594facca9b'; then
  check "env override checksum 被忽略（仍為固定 digest）" 0 0
else
  check "env override checksum 被忽略（仍為固定 digest）" 0 1
fi
if printf '%s\n' "$override_result" | grep -q 'FIX_URL_FIXED=https://github.com/smile-minecraft/AceLib/releases/download/v1.5.0/AceLib-1.5.0-test-fixtures.jar'; then
  check "env override fixtures URL 被忽略（仍為固定 HTTPS URL）" 0 0
else
  check "env override fixtures URL 被忽略（仍為固定 HTTPS URL）" 0 1
fi
if printf '%s\n' "$override_result" | grep -q 'FIX_SHA_FIXED=6c12c677c6beed17b4893050c42f547bfcf588496631673b52b557de3748a85f'; then
  check "env override fixtures checksum 被忽略（仍為固定 digest）" 0 0
else
  check "env override fixtures checksum 被忽略（仍為固定 digest）" 0 1
fi

# 不再使用 source clone / gradle build
if grep -q 'git clone' "$BUILD"; then check "build 不再使用 git clone" 1 0; else check "build 不再使用 git clone" 0 0; fi
if grep -q './gradlew' "$BUILD"; then check "build 不再使用 ./gradlew" 1 0; else check "build 不再使用 ./gradlew" 0 0; fi
if grep -q 'curl' "$BUILD"; then check "build 使用 curl 下載" 0 0; else check "build 使用 curl 下載" 0 1; fi
if grep -F -- '--proto' "$BUILD" >/dev/null 2>&1 && grep -F -- "'=https'" "$BUILD" >/dev/null 2>&1; then check "curl 限制 HTTPS 協議" 0 0; else check "curl 限制 HTTPS 協議" 0 1; fi
if grep -F -- '--proto-redir' "$BUILD" >/dev/null 2>&1 && grep -F -- "'=https'" "$BUILD" >/dev/null 2>&1; then check "curl 限制 HTTPS redirect" 0 0; else check "curl 限制 HTTPS redirect" 0 1; fi

# workflow
WF="$SCRIPT_DIR/../.github/workflows/build.yml"
if [[ -f "$WF" ]]; then
  grep -q 'ACE_OUTPUT_DIR' "$WF" ; check "workflow 仍使用 ACE_OUTPUT_DIR" 0 $?
  grep -q 'Fetch AceLib' "$WF" ; check "workflow 有 Fetch AceLib 步驟" 0 $?
  ace_line=$(grep -n 'Fetch AceLib' "$WF" | head -1 | cut -d: -f1)
  gradle_line=$(grep -n 'Build and test with Gradle' "$WF" | head -1 | cut -d: -f1)
  if [[ -n "$ace_line" && -n "$gradle_line" && "$ace_line" -lt "$gradle_line" ]]; then check "workflow AceLib 建置在 Gradle 編譯前" 0 0; else check "workflow AceLib 建置在 Gradle 編譯前" 0 1; fi
  sources_jar_count=$(grep -c 'sourcesJar' "$WF" || true)
  if [[ "$sources_jar_count" -ge 2 ]]; then check "workflow 兩輪都有 sourcesJar" 0 0; else check "workflow 兩輪都有 sourcesJar" 0 1; fi
  if grep -q 'ls .*\.jar' "$WF"; then check "workflow 不再使用 ls|xargs 單向 loop" 1 0; else check "workflow 不再使用 ls|xargs 單向 loop" 0 0; fi
  if grep -q 'sha256sum' "$WF"; then check "workflow 有 sha256sum 比較" 0 0; else check "workflow 有 sha256sum 比較" 0 1; fi
  if grep -q 'repro-b' "$WF"; then check "workflow 有 repro-b 目錄（雙向比較）" 0 0; else check "workflow 有 repro-b 目錄（雙向比較）" 0 1; fi
  if contains_forbidden "$WF" '--dependency-verification.*off'; then check "workflow 未關閉 verification" 1 0; else check "workflow 未關閉 verification" 0 0; fi
  if contains_forbidden "$WF" 'mavenLocal\(\)'; then check "workflow 未使用 mavenLocal" 1 0; else check "workflow 未使用 mavenLocal" 0 0; fi
else
  check "workflow 檔案存在" 0 1
fi

# ---------------------------------------------------------------------------
# 單元：verify_acelib_jar（Runtime）對錯誤輸入安全失敗
# ---------------------------------------------------------------------------
# 缺 jar
verify_acelib_jar "$TMP/missing.jar" "$ACE_EXPECTED_SHA256" ; check "缺 jar 應失敗" 1 $?

# 錯版本 jar
mkdir -p "$TMP/badver/com/smile/acelib"
printf 'x' > "$TMP/badver/com/smile/acelib/AceLibVersion.class"
printf 'version: 9.9.9\n' > "$TMP/badver/plugin.yml"
( cd "$TMP/badver" && zip -q -r "$TMP/badver.jar" . )
verify_acelib_jar "$TMP/badver.jar" "$ACE_EXPECTED_SHA256" ; check "錯版本應失敗" 1 $?

# 缺 AceLibVersion.class
mkdir -p "$TMP/noclass"
printf 'version: %s\n' "$ACE_VERSION" > "$TMP/noclass/plugin.yml"
( cd "$TMP/noclass" && zip -q -r "$TMP/noclass.jar" . )
verify_acelib_jar "$TMP/noclass.jar" "$ACE_EXPECTED_SHA256" ; check "缺 class 應失敗" 1 $?

# 正確版本 runtime jar（合成 jar）
mkdir -p "$TMP/good/com/smile/acelib"
printf 'x' > "$TMP/good/com/smile/acelib/AceLibVersion.class"
printf 'version: %s\n' "$ACE_VERSION" > "$TMP/good/plugin.yml"
( cd "$TMP/good" && zip -q -r "$TMP/good.jar" . )
GOOD_SHA="$(shasum -a 256 "$TMP/good.jar" | awk '{print $1}')"
verify_acelib_jar "$TMP/good.jar" "$GOOD_SHA" ; check "正確版本應通過（含固定 checksum，合成 jar）" 0 $?

# verifier 空 checksum 失敗
verify_acelib_jar "$TMP/good.jar" "" ; check "直接 verifier 空 checksum 必須失敗" 1 $?

# checksum 不符
verify_acelib_jar "$TMP/good.jar" "deadbeefdeadbeefdeadbeefdeadbeefdeadbeefdeadbeefdeadbeefdeadbeefdeadbeef" ; check "checksum 不符應失敗" 1 $?

# evil prefix class path
mkdir -p "$TMP/evil-prefix/evilcom/smile/acelib"
printf 'x' > "$TMP/evil-prefix/evilcom/smile/acelib/AceLibVersion.class"
printf 'version: %s\n' "$ACE_VERSION" > "$TMP/evil-prefix/plugin.yml"
( cd "$TMP/evil-prefix" && zip -q -r "$TMP/evil-prefix.jar" . )
EVIL_PREFIX_SHA="$(shasum -a 256 "$TMP/evil-prefix.jar" | awk '{print $1}')"
verify_acelib_jar "$TMP/evil-prefix.jar" "$EVIL_PREFIX_SHA" ; check "evil prefix class path 應失敗（fixture 自身 SHA）" 1 $?

# evil whitespace entry
mkdir -p "$TMP/evil-space/com/smile/acelib"
printf 'x' > "$TMP/evil-space/com/smile/acelib/AceLibVersion.class"
printf 'version: %s\n' "$ACE_VERSION" > "$TMP/evil-space/plugin.yml"
( cd "$TMP/evil-space" && zip -q -r "$TMP/evil-space.jar" . )
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
# 單元：verify_acelib_fixtures_jar（Test Fixtures）對錯誤輸入安全失敗
# ---------------------------------------------------------------------------
if declare -F verify_acelib_fixtures_jar >/dev/null 2>&1; then
  check "verify_acelib_fixtures_jar 函式已定義" 0 0
else
  check "verify_acelib_fixtures_jar 函式已定義" 0 1
fi

# 1. 缺 fixtures jar / 空檔
verify_acelib_fixtures_jar "$TMP/missing-fix.jar" "$ACE_FIXTURES_EXPECTED_SHA256" ; check "fixtures 缺 jar 應失敗" 1 $?
touch "$TMP/empty-fix.jar"
verify_acelib_fixtures_jar "$TMP/empty-fix.jar" "$ACE_FIXTURES_EXPECTED_SHA256" ; check "fixtures 空檔應失敗" 1 $?

# 2. HTML / 純文字回應（非 ZIP）
printf '<html><body>404 Not Found</body></html>\n' > "$TMP/html-fix.jar"
HTML_FIX_SHA="$(shasum -a 256 "$TMP/html-fix.jar" | awk '{print $1}')"
verify_acelib_fixtures_jar "$TMP/html-fix.jar" "$HTML_FIX_SHA" ; check "fixtures HTML 回應應失敗（即使帶自身 SHA）" 1 $?

# 3. 缺 FakeSafeScheduler.class
mkdir -p "$TMP/nofixclass/com/smile/acelib/testing"
printf 'x' > "$TMP/nofixclass/com/smile/acelib/testing/OtherClass.class"
( cd "$TMP/nofixclass" && zip -q -r "$TMP/nofixclass.jar" . )
NOFIX_SHA="$(shasum -a 256 "$TMP/nofixclass.jar" | awk '{print $1}')"
verify_acelib_fixtures_jar "$TMP/nofixclass.jar" "$NOFIX_SHA" ; check "fixtures 缺 FakeSafeScheduler.class 應失敗" 1 $?

# 4. evil prefix class path
mkdir -p "$TMP/evil-prefix-fix/evilcom/smile/acelib/testing"
printf 'x' > "$TMP/evil-prefix-fix/evilcom/smile/acelib/testing/FakeSafeScheduler.class"
( cd "$TMP/evil-prefix-fix" && zip -q -r "$TMP/evil-prefix-fix.jar" . )
EVIL_PREFIX_FIX_SHA="$(shasum -a 256 "$TMP/evil-prefix-fix.jar" | awk '{print $1}')"
verify_acelib_fixtures_jar "$TMP/evil-prefix-fix.jar" "$EVIL_PREFIX_FIX_SHA" ; check "fixtures evil prefix class path 應失敗" 1 $?

# 5. evil whitespace entry
mkdir -p "$TMP/evil-space-fix/com/smile/acelib/testing"
printf 'x' > "$TMP/evil-space-fix/com/smile/acelib/testing/FakeSafeScheduler.class"
( cd "$TMP/evil-space-fix" && zip -q -r "$TMP/evil-space-fix.jar" . )
python3 - << PYEOF
import zipfile
src = "$TMP/evil-space-fix.jar"
dst = "$TMP/evil-space-fix-renamed.jar"
with zipfile.ZipFile(src, 'r') as zin:
    with zipfile.ZipFile(dst, 'w', zipfile.ZIP_DEFLATED) as zout:
        for item in zin.infolist():
            data = zin.read(item.filename)
            new_name = item.filename
            if item.filename == "com/smile/acelib/testing/FakeSafeScheduler.class":
                new_name = "evil com/smile/acelib/testing/FakeSafeScheduler.class"
            new_info = zipfile.ZipInfo(filename=new_name, date_time=item.date_time)
            new_info.compress_type = item.compress_type
            new_info.external_attr = item.external_attr
            zout.writestr(new_info, data)
PYEOF
EVIL_SPACE_FIX_SHA="$(shasum -a 256 "$TMP/evil-space-fix-renamed.jar" | awk '{print $1}')"
verify_acelib_fixtures_jar "$TMP/evil-space-fix-renamed.jar" "$EVIL_SPACE_FIX_SHA" ; check "fixtures evil whitespace entry 應失敗" 1 $?

# 6. 正確 fixtures 合成 jar
mkdir -p "$TMP/good-fix/com/smile/acelib/testing"
printf 'x' > "$TMP/good-fix/com/smile/acelib/testing/FakeSafeScheduler.class"
( cd "$TMP/good-fix" && zip -q -r "$TMP/good-fix.jar" . )
GOOD_FIX_SHA="$(shasum -a 256 "$TMP/good-fix.jar" | awk '{print $1}')"
verify_acelib_fixtures_jar "$TMP/good-fix.jar" "$GOOD_FIX_SHA" ; check "fixtures 正確合成 jar 應通過" 0 $?

# 7. fixtures 空 checksum / 錯誤 checksum
verify_acelib_fixtures_jar "$TMP/good-fix.jar" "" ; check "fixtures verifier 空 checksum 應失敗" 1 $?
verify_acelib_fixtures_jar "$TMP/good-fix.jar" "deadbeefdeadbeefdeadbeefdeadbeefdeadbeefdeadbeefdeadbeefdeadbeefdeadbeef" ; check "fixtures checksum 不符應失敗" 1 $?

# 8. 交叉冒充拒絕：
# (a) 用 runtime 附件冒充 fixtures（缺少 FakeSafeScheduler.class）
verify_acelib_fixtures_jar "$TMP/good.jar" "$GOOD_SHA" ; check "runtime 附件冒充 fixtures 應失敗" 1 $?
# (b) 用 fixtures 附件冒充 runtime（缺少 plugin.yml 與 AceLibVersion.class）
verify_acelib_jar "$TMP/good-fix.jar" "$GOOD_FIX_SHA" ; check "fixtures 附件冒充 runtime 應失敗" 1 $?

# ---------------------------------------------------------------------------
# 整合：執行 build-acelib.sh（產出兩個附件）
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
    # Runtime jar 產出
    if [[ -s "$OUT/$OUT_NAME" ]]; then check "產出 runtime jar 非空" 0 0; else check "產出 runtime jar 非空" 0 1; fi
    verify_acelib_jar "$OUT/$OUT_NAME" "$ACE_EXPECTED_SHA256" ; check "產出 runtime jar 通過完整驗證" 0 $?
    [[ -f "$OUT/$OUT_NAME.sha256" && -f "$OUT/$OUT_NAME.meta" ]] \
      && check "產出 runtime sidecar 存在" 0 0 \
      || check "產出 runtime sidecar 存在" 0 1

    # Fixtures jar 產出
    if [[ -s "$OUT/$FIXTURES_OUT_NAME" ]]; then check "產出 fixtures jar 非空" 0 0; else check "產出 fixtures jar 非空" 0 1; fi
    verify_acelib_fixtures_jar "$OUT/$FIXTURES_OUT_NAME" "$ACE_FIXTURES_EXPECTED_SHA256" ; check "產出 fixtures jar 通過完整驗證" 0 $?
    [[ -f "$OUT/$FIXTURES_OUT_NAME.sha256" && -f "$OUT/$FIXTURES_OUT_NAME.meta" ]] \
      && check "產出 fixtures sidecar 存在" 0 0 \
      || check "產出 fixtures sidecar 存在" 0 1

    # flatDir 檔名相容性檢查：[artifact]-[revision].jar
    if [[ "$FIXTURES_OUT_NAME" =~ ^([a-zA-Z0-9_-]+)-([0-9]+\.[0-9]+\.[0-9]+)\.jar$ ]]; then
      artifact_part="${BASH_REMATCH[1]}"
      revision_part="${BASH_REMATCH[2]}"
      if [[ "$artifact_part" == "AceLib-test-fixtures" && "$revision_part" == "$ACE_VERSION" ]]; then
        check "fixtures 快取檔名符合 flatDir [artifact]-[revision].jar 規範" 0 0
      else
        check "fixtures 快取檔名符合 flatDir [artifact]-[revision].jar 規範" 0 1
      fi
    else
      check "fixtures 快取檔名符合 flatDir [artifact]-[revision].jar 規範" 0 1
    fi

    # sidecar 來源 asset 記錄
    fix_meta_asset="$(grep '^asset=' "$OUT/$FIXTURES_OUT_NAME.meta" 2>/dev/null | cut -d= -f2 || true)"
    if [[ "$fix_meta_asset" == "AceLib-1.5.0-test-fixtures.jar" ]]; then
      check "fixtures meta 記錄原始 asset 名稱" 0 0
    else
      check "fixtures meta 記錄原始 asset 名稱" 0 1
    fi
  else
    echo "---- build log ----"; tail -20 "$TMP/build.log"
  fi

  # 可重現：再跑一次，兩份 jar 的 SHA 皆相同
  OUT2="$TMP/out2"
  bash "$BUILD" --output-dir "$OUT2" > "$TMP/build2.log" 2>&1
  if [[ $? -eq 0 && -s "$OUT2/$OUT_NAME" && -s "$OUT2/$FIXTURES_OUT_NAME" ]]; then
    r_s1="$(shasum -a 256 "$OUT/$OUT_NAME" | awk '{print $1}')"
    r_s2="$(shasum -a 256 "$OUT2/$OUT_NAME" | awk '{print $1}')"
    f_s1="$(shasum -a 256 "$OUT/$FIXTURES_OUT_NAME" | awk '{print $1}')"
    f_s2="$(shasum -a 256 "$OUT2/$FIXTURES_OUT_NAME" | awk '{print $1}')"
    if [[ "$r_s1" == "$r_s2" && "$f_s1" == "$f_s2" ]]; then
      check "重複建置兩份 jar SHA 一致（可重現）" 0 0
    else
      check "重複建置兩份 jar SHA 一致（可重現）" 0 1
    fi
  else
    check "重複建置兩份 jar SHA 一致（可重現）" 0 1
  fi
fi

# ---------------------------------------------------------------------------
# Stale cleanup：fake-curl 下載失敗，確認六個已知產物在失敗下載後均不存在
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
touch "$STAGING_OUT/$OUT_NAME" "$STAGING_OUT/$OUT_NAME.sha256" "$STAGING_OUT/$OUT_NAME.meta"
touch "$STAGING_OUT/$FIXTURES_OUT_NAME" "$STAGING_OUT/$FIXTURES_OUT_NAME.sha256" "$STAGING_OUT/$FIXTURES_OUT_NAME.meta"
PATH="$FAKE_CURL_DIR:$PATH" bash "$BUILD" --output-dir "$STAGING_OUT" > "$TMP/stale.log" 2>&1
stale_rc=$?
check "stale cleanup: 失敗下載應非零退出" 1 $stale_rc
if [[ -f "$STAGING_OUT/$OUT_NAME" || -f "$STAGING_OUT/$OUT_NAME.sha256" || -f "$STAGING_OUT/$OUT_NAME.meta" || \
      -f "$STAGING_OUT/$FIXTURES_OUT_NAME" || -f "$STAGING_OUT/$FIXTURES_OUT_NAME.sha256" || -f "$STAGING_OUT/$FIXTURES_OUT_NAME.meta" ]]; then
  check "stale cleanup: 六個已知產物均不存在" 0 1
else
  check "stale cleanup: 六個已知產物均不存在" 0 0
fi

# ---------------------------------------------------------------------------
# Staging ownership：caller 提供的既有 ACE_STAGING_DIR 必須完整保留
# ---------------------------------------------------------------------------
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
# Stale cleanup preserve other files：失敗下載時只清理六個已知產物，保留其他檔案
# ---------------------------------------------------------------------------
PRESERVE_OUT="$TMP/preserve-out"
mkdir -p "$PRESERVE_OUT/sub"
echo "caller-owned important file" > "$PRESERVE_OUT/caller.txt"
echo "more data" > "$PRESERVE_OUT/sub/data.txt"
touch "$PRESERVE_OUT/$OUT_NAME" "$PRESERVE_OUT/$OUT_NAME.sha256" "$PRESERVE_OUT/$OUT_NAME.meta"
touch "$PRESERVE_OUT/$FIXTURES_OUT_NAME" "$PRESERVE_OUT/$FIXTURES_OUT_NAME.sha256" "$PRESERVE_OUT/$FIXTURES_OUT_NAME.meta"
PATH="$FAKE_CURL_DIR:$PATH" bash "$BUILD" --output-dir "$PRESERVE_OUT" > "$TMP/preserve.log" 2>&1 || true
if [[ -f "$PRESERVE_OUT/caller.txt" \
   && -f "$PRESERVE_OUT/sub/data.txt" \
   && ! -f "$PRESERVE_OUT/$OUT_NAME" \
   && ! -f "$PRESERVE_OUT/$OUT_NAME.sha256" \
   && ! -f "$PRESERVE_OUT/$OUT_NAME.meta" \
   && ! -f "$PRESERVE_OUT/$FIXTURES_OUT_NAME" \
   && ! -f "$PRESERVE_OUT/$FIXTURES_OUT_NAME.sha256" \
   && ! -f "$PRESERVE_OUT/$FIXTURES_OUT_NAME.meta" ]]; then
  check "stale cleanup preserve: OUTPUT_DIR 其他檔案保留、六個已知產物清理" 0 0
else
  check "stale cleanup preserve: OUTPUT_DIR 其他檔案保留、六個已知產物清理" 0 1
fi

# ---------------------------------------------------------------------------
# 回歸：post-copy 竄改必須 fail-closed
# ---------------------------------------------------------------------------
REGRESS_TMP="$TMP/regress-postcopy"
mkdir -p "$REGRESS_TMP"
FAKE_REGRESS_BIN="$REGRESS_TMP/fakebin"
mkdir -p "$FAKE_REGRESS_BIN"
CP_HIT_MARKER="$REGRESS_TMP/cp-hit"
REGRESS_FIXTURE="$OUT/$OUT_NAME"
REGRESS_FIXTURES_FIXTURE="$OUT/$FIXTURES_OUT_NAME"
if [[ -s "$REGRESS_FIXTURE" && -s "$REGRESS_FIXTURES_FIXTURE" ]]; then
  check "回歸 post-copy：deterministic local fixture 存在" 0 0
else
  check "回歸 post-copy：deterministic local fixture 存在" 0 1
fi

cat > "$FAKE_REGRESS_BIN/curl" <<'REGCP'
#!/usr/bin/env bash
dest=""
prev=""
for arg in "$@"; do
  if [[ "$prev" == "-o" ]]; then dest="$arg"; fi
  prev="$arg"
done
if [[ -z "$dest" ]]; then
  echo "fake curl: 找不到 -o dest" >&2
  exit 1
fi
if [[ "$dest" == *"fixtures"* ]]; then
  fixture="${FAKE_CURL_FIXTURES_FIXTURE:-}"
else
  fixture="${FAKE_CURL_FIXTURE:-}"
fi
if [[ -z "$fixture" || ! -s "$fixture" ]]; then
  echo "fake curl: deterministic fixture 缺失或為空 (fixture=$fixture, dest=$dest)" >&2
  exit 1
fi
/bin/cp -f "$fixture" "$dest"
echo "fake curl: staged $dest from fixture" >&2
exit 0
REGCP
chmod +x "$FAKE_REGRESS_BIN/curl"

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
  FAKE_CURL_FIXTURES_FIXTURE="$REGRESS_FIXTURES_FIXTURE" \
  FAKE_CP_HIT_MARKER="$CP_HIT_MARKER" \
  FAKE_CP_TARGET_PATTERN="$OUT_NAME" \
  bash "$BUILD" --output-dir "$OUTPUT_REGRESS" > "$REGRESS_TMP/build.log" 2>&1
regress_rc=$?
set -e
if [[ $regress_rc -ne 0 ]]; then check "回歸 post-copy 竄改：應非零退出" 0 0; else check "回歸 post-copy 竄改：應非零退出" 0 1; fi
if [[ -f "$CP_HIT_MARKER" ]]; then
  check "回歸 post-copy 竄改：fake cp 命中預期 output target" 0 0
else
  check "回歸 post-copy 竄改：fake cp 命中預期 output target" 0 1
fi
if [[ ! -f "$OUTPUT_REGRESS/$OUT_NAME" && ! -f "$OUTPUT_REGRESS/$OUT_NAME.sha256" && ! -f "$OUTPUT_REGRESS/$OUT_NAME.meta" && \
      ! -f "$OUTPUT_REGRESS/$FIXTURES_OUT_NAME" && ! -f "$OUTPUT_REGRESS/$FIXTURES_OUT_NAME.sha256" && ! -f "$OUTPUT_REGRESS/$FIXTURES_OUT_NAME.meta" ]]; then
  check "回歸 post-copy 竄改：六個已知產物被清理" 0 0
else
  check "回歸 post-copy 竄改：六個已知產物被清理" 0 1
fi
if [[ -f "$OUTPUT_REGRESS/caller.txt" && -f "$OUTPUT_REGRESS/sub/data.txt" ]]; then check "回歸 post-copy 竄改：OUTPUT 其他檔案保留" 0 0; else check "回歸 post-copy 竄改：OUTPUT 其他檔案保留" 0 1; fi
if [[ -d "$STAGING_REGRESS" && -f "$STAGING_REGRESS/sentinel" && -f "$STAGING_REGRESS/caller-data.txt" && -f "$STAGING_REGRESS/extra/x.txt" ]]; then check "回歸 post-copy 竄改：caller staging 完整保留" 0 0; else check "回歸 post-copy 竄改：caller staging 完整保留" 0 1; fi
if [[ -f "$OUTPUT_REGRESS/$OUT_NAME" ]] && grep -q "tampered-after-verification" "$OUTPUT_REGRESS/$OUT_NAME" 2>/dev/null; then check "回歸 post-copy 竄改：不留下竄改 bytes" 1 0; else check "回歸 post-copy 竄改：不留下竄改 bytes" 0 0; fi

# 契約：build 腳本必須在 cp 後、sidecar 前對 OUTPUT 呼叫固定 verifier
if grep -q 'verify_acelib_jar.*OUTPUT_DIR.*EXPECTED_SHA256' "$BUILD"; then check "契約 post-copy verifier 存在（runtime）" 0 0; else check "契約 post-copy verifier 存在（runtime）" 0 1; fi
if grep -q 'verify_acelib_fixtures_jar.*OUTPUT_DIR.*FIXTURES_EXPECTED_SHA256' "$BUILD"; then check "契約 post-copy verifier 存在（fixtures）" 0 0; else check "契約 post-copy verifier 存在（fixtures）" 0 1; fi

# 正常路徑：sidecar 建立前後 output 均通過 verifier，且 sidecar SHA 與實際一致
if [[ -f "$OUT/$OUT_NAME" && -f "$OUT/$FIXTURES_OUT_NAME" ]]; then
  verify_acelib_jar "$OUT/$OUT_NAME" "$ACE_EXPECTED_SHA256" ; check "正常 runtime output 側車建立後仍通過固定 verifier" 0 $?
  verify_acelib_fixtures_jar "$OUT/$FIXTURES_OUT_NAME" "$ACE_FIXTURES_EXPECTED_SHA256" ; check "正常 fixtures output 側車建立後仍通過固定 verifier" 0 $?

  r_sidecar_sha="$(awk '{print $1}' "$OUT/$OUT_NAME.sha256" 2>/dev/null || true)"
  r_actual_sha="$(shasum -a 256 "$OUT/$OUT_NAME" 2>/dev/null | awk '{print $1}' || true)"
  if [[ -n "$r_sidecar_sha" && "$r_sidecar_sha" == "$r_actual_sha" && "$r_actual_sha" == "$ACE_EXPECTED_SHA256" ]]; then check "正常 runtime sidecar SHA 與實際 output 一致且等於固定 digest" 0 0; else check "正常 runtime sidecar SHA 與實際 output 一致且等於固定 digest" 0 1; fi

  f_sidecar_sha="$(awk '{print $1}' "$OUT/$FIXTURES_OUT_NAME.sha256" 2>/dev/null || true)"
  f_actual_sha="$(shasum -a 256 "$OUT/$FIXTURES_OUT_NAME" 2>/dev/null | awk '{print $1}' || true)"
  if [[ -n "$f_sidecar_sha" && "$f_sidecar_sha" == "$f_actual_sha" && "$f_actual_sha" == "$ACE_FIXTURES_EXPECTED_SHA256" ]]; then check "正常 fixtures sidecar SHA 與實際 output 一致且等於固定 digest" 0 0; else check "正常 fixtures sidecar SHA 與實際 output 一致且等於固定 digest" 0 1; fi

  r_meta_sha="$(grep '^sha256=' "$OUT/$OUT_NAME.meta" 2>/dev/null | cut -d= -f2 || true)"
  if [[ "$r_meta_sha" == "$ACE_EXPECTED_SHA256" && "$r_meta_sha" == "$r_actual_sha" ]]; then check "正常 runtime meta sha256 與實際 output 一致" 0 0; else check "正常 runtime meta sha256 與實際 output 一致" 0 1; fi

  f_meta_sha="$(grep '^sha256=' "$OUT/$FIXTURES_OUT_NAME.meta" 2>/dev/null | cut -d= -f2 || true)"
  if [[ "$f_meta_sha" == "$ACE_FIXTURES_EXPECTED_SHA256" && "$f_meta_sha" == "$f_actual_sha" ]]; then check "正常 fixtures meta sha256 與實際 output 一致" 0 0; else check "正常 fixtures meta sha256 與實際 output 一致" 0 1; fi
else
  check "正常 runtime output 側車建立後仍通過固定 verifier" 0 1
  check "正常 fixtures output 側車建立後仍通過固定 verifier" 0 1
  check "正常 runtime sidecar SHA 與實際 output 一致且等於固定 digest" 0 1
  check "正常 fixtures sidecar SHA 與實際 output 一致且等於固定 digest" 0 1
  check "正常 runtime meta sha256 與實際 output 一致" 0 1
  check "正常 fixtures meta sha256 與實際 output 一致" 0 1
fi

# ---------------------------------------------------------------------------
# 側車 provenance：commit 與 tag_object 必須為實際 tag object 與 commit
# ---------------------------------------------------------------------------
if [[ -f "$OUT/$OUT_NAME.meta" && -f "$OUT/$FIXTURES_OUT_NAME.meta" ]]; then
  meta_commit="$(grep '^commit=' "$OUT/$OUT_NAME.meta" | cut -d= -f2)"
  meta_tag_obj="$(grep '^tag_object=' "$OUT/$OUT_NAME.meta" | cut -d= -f2)"
  fix_commit="$(grep '^commit=' "$OUT/$FIXTURES_OUT_NAME.meta" | cut -d= -f2)"
  fix_tag_obj="$(grep '^tag_object=' "$OUT/$FIXTURES_OUT_NAME.meta" | cut -d= -f2)"
  if [[ "$meta_commit" == "4ace7db641a4517faac6208dac28f7fcd72f6f7c" && \
        "$meta_tag_obj" == "891fa4ccf7698bdb46e346e91bedb30365f17c43" && \
        "$fix_commit" == "4ace7db641a4517faac6208dac28f7fcd72f6f7c" && \
        "$fix_tag_obj" == "891fa4ccf7698bdb46e346e91bedb30365f17c43" ]]; then
    check "sidecar provenance tag object 與 commit 正確" 0 0
  else
    check "sidecar provenance tag object 與 commit 正確" 0 1
  fi
else
  check "sidecar provenance tag object 與 commit 正確" 0 1
fi

echo "----"
echo "PASS=$pass FAIL=$fail"
[[ $fail -eq 0 ]]
