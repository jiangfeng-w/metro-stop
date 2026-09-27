#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""第二轮：正式采集（每场景 reset → 动作 → 冒烟+ framestats 双收）。

坐标：uiautomator 逻辑坐标（1080x2400）。
"""
import subprocess, sys, time, os

ADB = r'D:/Android/sdk/platform-tools/adb.exe'
PKG = 'com.metrostop.reminder'
ART = r'D:/AI/AgentChat/Qoder/jank-artifacts'
DATA = os.path.join(ART, 'data')
TMP = os.path.join(ART, 'monkey', 'exp2.txt')

def sh(*args):
    return subprocess.run(list(args), capture_output=True, text=True)

def adb(*args):
    return sh(ADB, *args).stdout

def inject(events):
    base = int(float(adb('shell', 'cat', '/proc/uptime').split()[0]) * 1000) + 400
    lines = ['type= raw events', f'count= {len(events)}', 'speed= 1.0', 'start data >>']
    for (t, x, y, kind) in events:
        ap = {'d': 0, 'm': 2, 'u': 1}[kind]
        pr = 0.0 if kind == 'u' else 1.0
        lines.append(f'DispatchPointer({base+t},{base+t},{ap},{x},{y},1.0,0.0,0,{pr},1.0,0,0)')
    open(TMP, 'w').write('\n'.join(lines) + '\n')
    sh(ADB, 'push', TMP, '/data/local/tmp/exp2.txt')
    r = adb('shell', 'monkey', '-p', PKG, '-f', '/data/local/tmp/exp2.txt', '-v', '1')
    return next((l.strip() for l in r.splitlines() if 'injected' in l), r[:80])

def collect_pair(name):
    with open(os.path.join(DATA, name + '.txt'), 'w') as f:
        f.write(adb('shell', 'dumpsys', 'gfxinfo', PKG))
    with open(os.path.join(DATA, name + '_framestats.txt'), 'w') as f:
        f.write(adb('shell', 'dumpsys', 'gfxinfo', PKG, 'framestats'))

def launch(monitoring=False, tab=None):
    adb('shell', 'am', 'force-stop', PKG); time.sleep(1.0)
    adb('shell', 'am', 'start', '-n', f'{PKG}/.MainActivity'); time.sleep(3.0)
    if monitoring:
        adb('shell', 'am', 'start', '-n', f'{PKG}/.MainActivity', '--ez', 'auto_start', 'true')
        time.sleep(3.0)
    if tab:
        tap_now, y = tab
        inject([(0, tap_now, y, 'd'), (70, tap_now, y, 'u')])
        time.sleep(1.2)

LINE, DIR, BOARD, DEST = (540, 533), (540, 737), (540, 941), (540, 1145)
BLANK = (540, 380)
FROM, TO = (540, 1650), (540, 950)
SETTAB, DBGTAB = (540, 2277), (873, 2277)

def tap_seq(t0, xy, hold=70):
    return [(t0, xy[0], xy[1], 'd'), (t0 + hold, xy[0], xy[1], 'u')]

def swipe_seq(t0, a, b, dur=800, steps=12):
    ev = [(t0, a[0], a[1], 'd')]
    for i in range(1, steps):
        ev.append((t0 + dur * i // steps,
                   a[0] + (b[0]-a[0]) * i // steps,
                   a[1] + (b[1]-a[1]) * i // steps, 'm'))
    ev.append((t0 + dur, b[0], b[1], 'u'))
    return ev

def scene(name, monitoring, tab, actions):
    launch(monitoring=monitoring, tab=tab)
    adb('shell', 'dumpsys', 'gfxinfo', PKG, 'reset')
    time.sleep(0.6)
    print(f'{name}:', inject(actions))
    time.sleep(1.5)
    collect_pair(name)

def scroll_seq():
    ev, t = [], 0
    for i in range(6):
        ev += swipe_seq(t, FROM, TO); t += 1000
        ev += swipe_seq(t, TO, FROM); t += 1200
    return ev

def open_close_seq(target):
    ev, t = [], 0
    for i in range(5):
        ev += tap_seq(t, target); t += 600
        ev += tap_seq(t, BLANK); t += 700
    return ev

def switch_seq():
    ev, t = [], 0
    for i in range(5):
        ev += tap_seq(t, BOARD); t += 160
        ev += tap_seq(t, LINE); t += 160
        ev += tap_seq(t + 260, LINE); t += 700
        ev += tap_seq(t, BLANK); t += 650
    return ev

SCENES = {
    'B0s': lambda: scene('B0s_settings_scroll', False, SETTAB, scroll_seq()),
    'B0d': lambda: scene('B0d_debug_scroll', False, DBGTAB, scroll_seq()),
    'B0m': lambda: scene('B0m_monitor_scroll', False, None, scroll_seq()),
    'B1m': lambda: scene('B1m_monitor_scroll_monitored', True, None, scroll_seq()),
    'C1b': lambda: scene('C1b_single_openclose', False, None, open_close_seq(BOARD)),
    'C2b': lambda: scene('C2b_switch_overlap', False, None, switch_seq()),
}

if __name__ == '__main__':
    for n in (sys.argv[1:] or SCENES):
        SCENES[n]()
    adb('shell', 'am', 'force-stop', PKG)
