#!/usr/bin/env bash
# check-performance-gate.sh — 保護熱路徑 p99 效能門檻與 sentinel 的 CI 閘門。
#
# 三項門檻來自規格（implementation-plan §5.5／企劃書 §99，
# task-breakdown 效能回歸門檻條目）：
#   chunk lookup（已快取）          p99 < 5µs  = 5_000ns
#   permission decision（快取命中）  p99 < 50µs = 50_000ns
#   protection decision 整體        p99 < 100µs = 100_000ns
# 對應到 record-only 基線測試的三個量測（依序）：
#   engine-decide-wilderness-allow／engine-decide-in-land-deny／
#   listener-block-break-deny-cooldown-hit。
#
# 設計說明（wall-clock 不當唯一 correctness gate）：
#   時間斷言不住在單元測試裡（基線維持 record-only，deterministic
#   gate 只斷言門檻字面值與 poisoned-seam 行為），所以完整套件永遠不會
#   因為機器噪聲 flaky；wall-clock 比較只住在這個
#   script 裡，和確定性 sentinel（source scanner＋poisoned SQL／
#   Economy／chunk-load 接縫）一起構成閘門。門檻相對實測有 10 倍以上
#   headroom，超出即視為真實回歸（例如有人把 SQL 帶回熱路徑）。
#
# 用法：bash scripts/check-performance-gate.sh
# 失敗語意：任一 gate／structure／sentinel 測試失敗、門檻字面值對不上、
#   基線 XML 缺 p99 行、或任一 p99 超標，皆非零離開並指名原因。
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"

# ---- p99 門檻（ns），與 gate 測試的字面值雙向鎖定，不可單邊放寬 ----
CHUNK_LOOKUP_P99_NS=5000
PERMISSION_DECISION_P99_NS=50000
PROTECTION_DECISION_P99_NS=100000

GATE_TEST="$ROOT_DIR/chunkland-plugin/src/test/java/com/smile/chunkland/protection/ProtectionPerformanceGateTest.java"
RESULT_DIR="$ROOT_DIR/chunkland-plugin/build/test-results/test"
BASELINE_XML="$RESULT_DIR/TEST-com.smile.chunkland.protection.ProtectionPerformanceBaselineTest.xml"

log() { echo "perf-gate: $*"; }
fail() { echo "PERF-GATE FAIL: $*" >&2; exit 1; }

# ---- 1. 門檻字面值雙向鎖定：script 與 Java 門檻常數必須一致 ----
log "checking threshold literals are locked between script and gate test"
grep -q "CHUNK_LOOKUP_P99_NS = 5_000L" "$GATE_TEST" \
    || fail "gate test chunk-lookup threshold moved (expected 5_000L)"
grep -q "PERMISSION_DECISION_P99_NS = 50_000L" "$GATE_TEST" \
    || fail "gate test permission-decision threshold moved (expected 50_000L)"
grep -q "PROTECTION_DECISION_P99_NS = 100_000L" "$GATE_TEST" \
    || fail "gate test protection-decision threshold moved (expected 100_000L)"
log "threshold literals locked: 5000/50000/100000 ns"

# ---- 2. 確定性 gate＋structure 測試（sentinel：SQL／Economy／World query）----
log "running deterministic gate + hot-path structure tests"
if ! ./gradlew :chunkland-plugin:test \
    --tests "com.smile.chunkland.protection.ProtectionPerformanceGateTest" \
    --tests "com.smile.chunkland.protection.ProtectionHotPathStructureTest" \
    --rerun-tasks \
    --no-daemon; then
    fail "deterministic gate/structure tests failed — hot-path sentinel tripped (SQL/Economy/chunk-load?)"
fi
log "deterministic gate + structure tests passed"

# ---- 3. 基線量測（record）＋p99 門檻比較 ----
log "running record-only performance baseline"
if ! ./gradlew :chunkland-plugin:test \
    --tests "com.smile.chunkland.protection.ProtectionPerformanceBaselineTest" \
    --rerun-tasks \
    --no-daemon; then
    fail "baseline tests failed (decision correctness broke, not a timing issue)"
fi
[[ -f "$BASELINE_XML" ]] \
    || fail "missing baseline XML: $BASELINE_XML"

# 從 XML 的 system-out 抽三行 [baseline] ... p99Ns=...，逐項比對門檻。
export BASELINE_XML CHUNK_LOOKUP_P99_NS PERMISSION_DECISION_P99_NS PROTECTION_DECISION_P99_NS
if ! python3 - "$BASELINE_XML" <<'EOF'
import os, re, sys, xml.etree.ElementTree as ET

budgets = {
    "engine-decide-wilderness-allow": int(os.environ["CHUNK_LOOKUP_P99_NS"]),
    "engine-decide-in-land-deny": int(os.environ["PERMISSION_DECISION_P99_NS"]),
    "listener-block-break-deny-cooldown-hit": int(os.environ["PROTECTION_DECISION_P99_NS"]),
}
text = "".join((e.text or "") for e in ET.parse(sys.argv[1]).getroot().iter("system-out"))
found = dict(re.findall(r"\[baseline\] (\S+) p99Ns=(\d+)", text))
missing = [k for k in budgets if k not in found]
if missing:
    print(f"PERF-GATE FAIL: baseline missing p99 lines: {missing}", file=sys.stderr)
    sys.exit(1)
failed = False
for name, budget in budgets.items():
    p99 = int(found[name])
    status = "OK" if p99 < budget else "OVER"
    print(f"perf-gate: {name} p99Ns={p99} budgetNs={budget} [{status}]")
    if p99 >= budget:
        failed = True
if failed:
    print("PERF-GATE FAIL: at least one p99 exceeded its budget", file=sys.stderr)
    sys.exit(1)
print("perf-gate: all three p99 figures are inside budget")
EOF
then
    fail "p99 budget comparison failed (see lines above)"
fi

log "performance gate passed: sentinels clean, p99 inside 5000/50000/100000 ns"
