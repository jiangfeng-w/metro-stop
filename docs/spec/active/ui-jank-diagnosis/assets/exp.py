#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""UI 掉帧对照实验编排（A/B/C 组）。

注入配方（本次会话实测打通）：DispatchPointer + 真实 uptime 时间戳(ms) + 按下 pressure=1.0。
坐标 = uiautomator 逻辑坐标（截图为 1080x2400）。
"""
import subprocess, sys, time, os

ADB = r'D:/Android/sdk/platform-tools/adb.exe'
PKG = 'com.metrostop.reminder'
ART = r'D:/AI/AgentChat/Qoder/jank-artifacts'
DATA = os.path.join(ART, 'data')
TMP = os.path.join(ART, 'monkey', 'exp.txt')

def sh(*args, **kw):
    return subprocess.run(list(args), capture_output=True, text=True, **kw)

def adb(*args):
    return sh(ADB, *args).stdout

def uptime_ms():
    out = adb('shell', 'cat', '/proc/uptime')
    return int(float(out.split()[0]) * 1000)

def inject(events, label=''):
    """events: list of (t_rel_ms, x, y, kind) kind in d/m/u 末尾自动补一次 up 可选"""
    base = uptime_ms() + 400
    lines = ['type= raw events', f'count= {len(events)}', 'speed= 1.0', 'start data >>']
    for (t, x, y, kind) in events:
        ap = {'d': 0, 'm': 2, 'u': 1}[kind]
        pr = 0.0 if kind == 'u' else 1.0
        lines.append(f'DispatchPointer({base+t},{base+t},{ap},{x},{y},1.0,0.0,0,{pr},1.0,0,0)')
    with open(TMP, 'w') as f:
        f.write('\n'.join(lines) + '\n')
    sh(ADB, 'push', TMP, '/data/local/tmp/exp.txt')
    r = adb('shell', 'monkey', '-p', PKG, '-f', '/data/local/tmp/exp.txt', '-v', '1')
    for ln in r.splitlines():
        if 'injected' in ln:
            return ln.strip()
    return r.strip()[:120]

def reset_gfx():
    adb('shell', 'dumpsys', 'gfxinfo', PKG, 'reset')

def collect(name):
    out = adb('shell', 'dumpsys', 'gfxinfo', PKG)
    with open(os.path.join(DATA, name), 'w') as f:
        f.write(out)

def launch(monitoring=False):
    adb('shell', 'am', 'force-stop', PKG)
    time.sleep(1.0)
    adb('shell', 'am', 'start', '-n', f'{PKG}/.MainActivity')
    time.sleep(3.0)
    if monitoring:
        adb('shell', 'am', 'start', '-n', f'{PKG}/.MainActivity', '--ez', 'auto_start', 'true')
        time.sleep(3.0)

# ---------------- 坐标（uiautomator 逻辑坐标） ----------------
LINE     = (540, 533)    # 线路
DIR      = (540, 737)    # 方向
BOARD    = (540, 941)    # 上车站
DEST     = (540, 1145)   # 目的站
BLANK_TOP = (540, 380)   # 菜单上方空白（关闭用）
SCROLL_FROM = (540, 1650)
SCROLL_TO   = (540, 950)

def tap_seq(t0, xy, hold=60):
    return [(t0, xy[0], xy[1], 'd'), (t0 + hold, xy[0], xy[1], 'u')]

def swipe_seq(t0, a, b, dur=280, steps=6):
    ev = [(t0, a[0], a[1], 'd')]
    for i in range(1, steps):
        ev.append((t0 + dur * i // steps,
                   a[0] + (b[0]-a[0]) * i // steps,
                   a[1] + (b[1]-a[1]) * i // steps, 'm'))
    ev.append((t0 + dur, b[0], b[1], 'u'))
    return ev

# ---------------- 场景 ----------------
def scene_B0():
    launch()
    reset_gfx()
    ev = []
    t = 0
    for i in range(6):
        ev += swipe_seq(t, SCROLL_FROM, SCROLL_TO, 800, 12); t += 900
        ev += swipe_seq(t, SCROLL_TO, SCROLL_FROM, 800, 12); t += 1100
    print('B0 inject:', inject(ev))
    time.sleep(1.5)
    collect('B0_scroll_unmonitored.txt')

def scene_C1():
    launch()
    reset_gfx()
    ev = []
    t = 0
    for i in range(5):
        ev += tap_seq(t, BOARD); t += 500          # 开
        ev += tap_seq(t, BLANK_TOP); t += 600      # 关
    print('C1 inject:', inject(ev))
    time.sleep(1.2)
    collect('C1_single_openclose_x5.txt')

def scene_C2():
    launch()
    reset_gfx()
    ev = []
    t = 0
    for i in range(5):
        ev += tap_seq(t, BOARD); t += 150          # 开上车站
        ev += tap_seq(t, LINE); t += 150           # 立即点线路（上方字段）
        ev += tap_seq(t + 250, LINE); t += 700     # 补第二下（若第一下被吞则此处开线路）
        ev += tap_seq(t, BLANK_TOP); t += 600      # 清场
    print('C2 inject:', inject(ev))
    time.sleep(1.2)
    collect('C2_switch_overlap_x5.txt')

def scene_C3():
    launch()
    reset_gfx()
    ev = []
    t = 0
    for i in range(5):
        ev += tap_seq(t, BOARD); t += 400          # 开上车站
        ev += tap_seq(t, BLANK_TOP); t += 400      # 关
        ev += tap_seq(t, LINE); t += 400           # 开线路
        ev += tap_seq(t, BLANK_TOP); t += 600      # 关
    print('C3 inject:', inject(ev))
    time.sleep(1.2)
    collect('C3_sequential_openclose_x5.txt')

def scene_C4():
    launch()
    reset_gfx()
    ev = []
    t = 0
    ev += tap_seq(t, BOARD); t += 700              # 开上车站（长菜单）
    for i in range(4):
        ev += swipe_seq(t, (540, 1750), (540, 1150), 260); t += 420
        ev += swipe_seq(t, (540, 1150), (540, 1750), 260); t += 600
    print('C4 inject:', inject(ev))
    time.sleep(1.2)
    collect('C4_menu_scroll.txt')

def scene_A1b():
    launch(monitoring=True)
    reset_gfx()
    time.sleep(10)
    collect('A1b_monitoring_idle.txt')

def scene_B1():
    launch(monitoring=True)
    reset_gfx()
    ev = []
    t = 0
    for i in range(6):
        ev += swipe_seq(t, SCROLL_FROM, SCROLL_TO, 800, 12); t += 900
        ev += swipe_seq(t, SCROLL_TO, SCROLL_FROM, 800, 12); t += 1100
    print('B1 inject:', inject(ev))
    time.sleep(1.5)
    collect('B1_scroll_monitored.txt')

def scene_C5_monitored():
    """监测中下拉框应为禁用不可展开 —— 验证状态域互斥（报告 §2.1）"""
    launch(monitoring=True)
    reset_gfx()
    ev = []
    t = 0
    for i in range(4):
        ev += tap_seq(t, BOARD); t += 500
        ev += tap_seq(t, LINE); t += 600
    print('C5 inject:', inject(ev))
    time.sleep(1.2)
    collect('C5_dropdown_blocked_monitored.txt')

SCENES = {
    'B0': scene_B0, 'C1': scene_C1, 'C2': scene_C2, 'C3': scene_C3, 'C4': scene_C4,
    'A1': scene_A1b, 'B1': scene_B1, 'C5': scene_C5_monitored,
}

if __name__ == '__main__':
    which = sys.argv[1:] or list(SCENES)
    for name in which:
        print(f'===== scene {name} =====')
        SCENES[name]()
        print(f'----- scene {name} done -----')
    adb('shell', 'am', 'force-stop', PKG)  # 收尾
