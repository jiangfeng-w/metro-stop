# -*- coding: utf-8 -*-
"""生产主链路 cell 流交叉验证脚本（2026-09-29 晚通勤学习趟存档）。

用途：验证主链路 CellMonitorCollector 产出的会话 cell 流（cell_<stamp>.csv）在 12 个
停稳 MARK 窗内的驻留小区，是否覆盖 lab cellid 学到的站区映射——同一趟车、两条独立
采集通路应逐站吻合。2026-09-29 晚通勤实测结果：11/12 命中（观东 MISS 系 ALERT_ARRIVED
后 30 s 自动结束早于停稳 MARK 6 s 的会话截断，非缺陷）。

⚠️ 存档说明：本脚本在分析目录内运行（输入为同目录的 marks_cd6_to_lanjiagou.json 与
cell_20260929_202817.csv，cell_zones.json 为仓库内绝对路径），原样存档供复现参考，
不能直接从 assets/ 目录执行。分析原件在仓库外
D:\AI\AgentChat\ZCode\tmp\metro-analysis\2026-09-29-evening-commute\（含 GPS 不入库）。
"""
import json, io, csv

marks = json.load(io.open('marks_cd6_to_lanjiagou.json', encoding='utf-8'))
data = json.load(io.open('D:/Code/own-project/metro-stop/app/src/main/assets/cell_zones.json', encoding='utf-8'))
line = [l for l in data['lines'] if l['directionId'] == 'cd6_to_lanjiagou'][0]
zones = {s['stationId']: set(s['cells']) for s in line['stations']}

rows = []
with io.open('cell_20260929_202817.csv', encoding='utf-8') as f:
    for r in csv.DictReader(f):
        toks = [t for t in r['cells'].split('|') if t]
        ids = [':'.join(t.split(':')[:2]) for t in toks]
        rows.append((int(r['t_ms']), ids))
print('monitor cell 流行数:', len(rows))

WIN_B, WIN_A, MIN_DWELL = 15000, 40000, 15000

def dwell_cells(mark):
    a, b = mark - WIN_B, mark + WIN_A
    dwell = {}; cur = None; start = None
    for t, ids in rows:
        ident = ids[0] if ids else ''
        if ident != cur:
            if cur and start is not None:
                dwell.setdefault(cur, [start, t])
                dwell[cur][1] = max(dwell[cur][1], t)
            cur, start = ident, t
        if t > b:
            break
    if cur and start is not None:
        dwell.setdefault(cur, [start, rows[-1][0]])
    out = set()
    for ident, (s, e) in dwell.items():
        if ident and min(e, b) - max(s, a) >= MIN_DWELL:
            out.add(ident)
    return out

ok = 0
for m in marks:
    got = dwell_cells(int(m['t_ms']))
    want = zones[m['stationId']]
    hit = got & want
    tag = 'OK  ' if hit else 'MISS'
    if hit:
        ok += 1
    print('{} {}: 生产流驻留={} 映射命中={}'.format(tag, m['name'], sorted(got), sorted(hit)))
print('生产流交叉验证: {}/12 站命中映射小区'.format(ok))
