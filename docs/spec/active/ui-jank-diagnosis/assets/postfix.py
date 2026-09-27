#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""修复后同口径复测（C1/C2/A1/B1），坐标基于 P0 改动后的新布局。

新坐标（uiautomator 逻辑坐标 1080x2400）：
  线路 (540,527) 方向 (540,719) 上车站 (540,911) 目的站 (540,1103)
  展开后选项列表出现在字段下方，单击字段头即收起。
"""
import subprocess, sys, time, os

ADB = r'D:/Android/sdk/platform-tools/adb.exe'
PKG = 'com.metrostop.reminder'
ART = r'D:/AI/AgentChat/Qoder/jank-artifacts'
DATA = os.path.join(ART, 'data')
TMP = os.path.join(ART, 'monkey', 'postfix.txt')

def sh(*a): return subprocess.run(list(a), capture_output=True, text=True)
def adb(*a): return sh(ADB, *a).stdout

def inject(events):
    base = int(float(adb('shell', 'cat', '/proc/uptime').split()[0]) * 1000) + 400
    L = ['type= raw events', f'count= {len(events)}', 'speed= 1.0', 'start data >>']
    for (t, x, y, k) in events:
        ap = {'d': 0, 'm': 2, 'u': 1}[k]; pr = 0.0 if k == 'u' else 1.0
        L.append(f'DispatchPointer({base+t},{base+t},{ap},{x},{y},1.0,0.0,0,{pr},1.0,0,0)')
    open(TMP, 'w').write('\n'.join(L) + '\n')
    sh(ADB, 'push', TMP, '/data/local/tmp/postfix.txt')
    r = adb('shell', 'monkey', '-p', PKG, '-f', '/data/local/tmp/postfix.txt', '-v', '1')
    return next((l.strip() for l in r.splitlines() if 'injected' in l), r[:80])

def collect(name):
    open(os.path.join(DATA, name + '_fs.txt'), 'w').write(adb('shell', 'dumpsys', 'gfxinfo', PKG, 'framestats'))
    open(os.path.join(DATA, name + '.txt'), 'w').write(adb('shell', 'dumpsys', 'gfxinfo', PKG))

def launch(monitoring=False):
    adb('shell', 'am', 'force-stop', PKG); time.sleep(1.0)
    adb('shell', 'am', 'start', '-n', f'{PKG}/.MainActivity'); time.sleep(3.0)
    if monitoring:
        adb('shell', 'am', 'start', '-n', f'{PKG}/.MainActivity', '--ez', 'auto_start', 'true')
        time.sleep(3.0)

LINE, DIR, BOARD, DEST = (540, 527), (540, 719), (540, 911), (540, 1103)
FROM, TO = (540, 1600), (540, 900)

def tap(t0, xy, hold=70): return [(t0, xy[0], xy[1], 'd'), (t0+hold, xy[0], xy[1], 'u')]

def swipe(t0, a, b, dur=800, steps=12):
    ev = [(t0, a[0], a[1], 'd')]
    for i in range(1, steps):
        ev.append((t0+dur*i//steps, a[0]+(b[0]-a[0])*i//steps, a[1]+(b[1]-a[1])*i//steps, 'm'))
    ev.append((t0+dur, b[0], b[1], 'u'))
    return ev

def scene(name, monitoring, actions, settle=1.5):
    launch(monitoring)
    adb('shell', 'dumpsys', 'gfxinfo', PKG, 'reset'); time.sleep(0.6)
    print(f'{name}:', inject(actions))
    time.sleep(settle)
    collect(name)

# P0-C1: 单框开合 ×10（点字段头开 / 再点字段头收 —— 新交互）
ev, t = [], 0
for i in range(10):
    ev += tap(t, BOARD); t += 600
    ev += tap(t, BOARD); t += 700
scene('AF1_openclose_x10', False, ev)

# P0-C2: 互切 ×10（开 BOARD → 点 LINE，单次点击即应切换）
ev, t = [], 0
for i in range(10):
    ev += tap(t, BOARD); t += 160
    ev += tap(t, LINE);  t += 700
scene('AF2_switch_x10', False, ev)

# P0-C3: 先收再开 ×5
ev, t = [], 0
for i in range(5):
    ev += tap(t, BOARD); t += 400
    ev += tap(t, BOARD); t += 400
    ev += tap(t, LINE);  t += 400
    ev += tap(t, LINE);  t += 700
scene('AF3_sequential_x5', False, ev)

# P1-A1: 监测中静止 10s
scene('AF4_monitored_idle', True, [], settle=10)

# P1-B1: 监测中滑动
ev, t = [], 0
for i in range(6):
    ev += swipe(t, FROM, TO); t += 1000
    ev += swipe(t, TO, FROM); t += 1200
scene('AF5_monitored_scroll', True, ev)

adb('shell', 'am', 'force-stop', PKG)
print('done')
