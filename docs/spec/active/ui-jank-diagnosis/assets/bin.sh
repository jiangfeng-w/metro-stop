#!/bin/bash
# UI 掉帧实验辅助工具（诚实记录：注入配方 = DispatchPointer + 真实 uptime 时间戳 + pressure=1.0）
ADB='D:/Android/sdk/platform-tools/adb.exe'
ART='D:/AI/AgentChat/Qoder/jank-artifacts'
PKG='com.metrostop.reminder'

uptime_ms() { MSYS_NO_PATHCONV=1 "$ADB" shell cat /proc/uptime | awk '{printf "%d", $1*1000}'; }

# inject: 把本地脚本推到设备并执行；打印注入事件数
inject() {
  MSYS_NO_PATHCONV=1 "$ADB" push "$1" /data/local/tmp/inj.txt >/dev/null 2>&1
  MSYS_NO_PATHCONV=1 "$ADB" shell monkey -p "$PKG" -f /data/local/tmp/inj.txt -v 1 2>&1 | grep -oE "Events injected: [0-9]+"
}

# gen <out> <base_uptime> <python-list-literal-of-events>
# event = (t_ms, x, y, kind) kind: d=down, m=move, u=up
gen() {
  python - "$1" "$2" "$3" << 'PYEOF'
import sys
out, base, spec = sys.argv[1], int(sys.argv[2]), eval(sys.argv[3])
lines = ["type= raw events", f"count= {len(spec)}", "speed= 1.0", "start data >>"]
for (t, x, y, kind) in spec:
    ap = {"d": 0, "m": 2, "u": 1}[kind]
    pt = 0 if kind == "u" else 1
    pr = 1.0 if kind != "u" else 0.0
    lines.append(f"DispatchPointer({base+t},{base+t},{ap},{x},{y},1.0,0.0,0,{pr},1.0,0,0)")
open(out, "w").write("\n".join(lines) + "\n")
PYEOF
}

# tap <x> <y> [gap_ms]  (单次点击，事件间隔 gap 默认 80ms)
tap() {
  local U; U=$(uptime_ms)
  local gap=${3:-80}
  gen "$ART/monkey/auto.txt" "$((U+250))" "[ (0, $1, $2, 'd'), ($gap, $1, $2, 'u') ]"
  inject "$ART/monkey/auto.txt"
}

# swipe <x1> <y1> <x2> <y2> [dur_ms]  (线性滑动，steps 6)
swipe() {
  local U; U=$(uptime_ms)
  local dur=${5:-300}
  python - "$ART/monkey/auto.txt" "$((U+250))" "$1" "$2" "$3" "$4" "$dur" << 'PYEOF'
import sys
out, base, x1, y1, x2, y2, dur = sys.argv[1], int(sys.argv[2]), int(sys.argv[3]), int(sys.argv[4]), int(sys.argv[5]), int(sys.argv[6]), int(sys.argv[7])
steps = 6
spec = [(0, x1, y1, 'd')]
for i in range(1, steps):
    t = dur * i // steps
    spec.append((t, x1 + (x2-x1)*i//steps, y1 + (y2-y1)*i//steps, 'm'))
spec.append((dur, x2, y2, 'u'))
lines = ["type= raw events", f"count= {len(spec)}", "speed= 1.0", "start data >>"]
for (t, x, y, kind) in spec:
    ap = {"d": 0, "m": 2, "u": 1}[kind]
    pt = 0 if kind == "u" else 1
    pr = 1.0 if kind != "u" else 0.0
    lines.append(f"DispatchPointer({base+t},{base+t},{ap},{x},{y},1.0,0.0,0,{pr},1.0,0,0)")
open(out, "w").write("\n".join(lines) + "\n")
PYEOF
  inject "$ART/monkey/auto.txt"
}

reset_gfx() { "$ADB" shell dumpsys gfxinfo "$PKG" reset >/dev/null 2>&1; }
collect_gfx() { "$ADB" shell dumpsys gfxinfo "$PKG" > "$1" 2>&1; }
dump_ui() {
  MSYS_NO_PATHCONV=1 "$ADB" shell uiautomator dump /data/local/tmp/d.xml >/dev/null 2>&1
  MSYS_NO_PATHCONV=1 "$ADB" exec-out cat /data/local/tmp/d.xml > "$1" 2>/dev/null
}
summarize() {
  echo "--- $1 ---"
  grep -E "Total frames rendered|Janky frames:|Number Missed Vsync|Number Slow UI thread|Number Slow issue draw|50th percentile|90th percentile|99th percentile" "$1" | head -8
}
