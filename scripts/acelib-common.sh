#!/usr/bin/env bash
# acelib-common.sh — AceLib 固定來源 / 版本 / 完整性常數與驗證函式。
#
# 僅定義，不執行任何副作用；供 build-acelib.sh 與 test-build-acelib.sh source。
# 來源與版本皆寫死，不使用 mavenLocal() / SNAPSHOT / latest / 浮動下載。
#
# 鎖定依據（可查證）：
#   - 倉庫：smile-minecraft/AceLib（GitHub）
#   - 標籤：v1.2.0（annotated tag）
#   - commit：a2ceb90b18648623b8146ba1e68d9f3f6ec41aeb（annotated tag v1.2.0 指向的 commit）
#   - 產物：AceLib-1.2.0.jar（plugin JAR，供 Folia plugins/ 使用）
#   - checksum：本機自建產物 SHA-256（見 ACE_EXPECTED_SHA256_DEFAULT，建置後核對）
#     （本環境兩次 clean build 位元組一致；工具鏈不同時請以 ACE_EXPECTED_SHA256 覆寫）

ACE_REPO_URL_DEFAULT="https://github.com/smile-minecraft/AceLib.git"
ACE_TAG="v1.2.0"
ACE_COMMIT="a2ceb90b18648623b8146ba1e68d9f3f6ec41aeb"
ACE_VERSION="1.2.0"
ACE_EXPECTED_SHA256_DEFAULT="da9f196b47c2b28c6db443d102236b27c1a1bbdf7dd3e7c22470170420935278"

ACE_REPO_URL="${ACE_REPO_URL:-$ACE_REPO_URL_DEFAULT}"
ACE_EXPECTED_SHA256="${ACE_EXPECTED_SHA256:-$ACE_EXPECTED_SHA256_DEFAULT}"

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
  listing="$(unzip -l "$jar" 2>/dev/null)"
  if printf '%s\n' "$listing" | grep -q "com/smile/acelib/AceLibVersion.class"; then
    : # AceLibVersion.class 存在，通過
  else
    echo "verify: 缺少 com/smile/acelib/AceLibVersion.class" >&2
    return 1
  fi
  if [[ -n "$expected_sha" ]]; then
    local actual
    actual="$(shasum -a 256 "$jar" | awk '{print $1}')"
    if [[ "$actual" != "$expected_sha" ]]; then
      echo "verify: checksum 不符 — 預期 $expected_sha，實際 $actual" >&2
      return 1
    fi
  fi
  return 0
}
