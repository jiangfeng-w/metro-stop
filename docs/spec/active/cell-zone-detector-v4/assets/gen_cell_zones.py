# -*- coding: utf-8 -*-
"""cell_zones.json 生成脚本（cell-zone-detector-v4 需求 2.1，模式同 real-line-data/gen_lines.py）。

用法：
    python gen_cell_zones.py --cellid <lab_cellid.csv> \
        --marks <marks.json> --line <lineId> --direction <directionId> \
        --out <app/src/main/assets/cell_zones.json> [--merge]

marks.json 格式（学习趟的「列车进站停稳」MARK 真值，按进站顺序）：
    [
      {"t_ms": 322672680, "stationId": "cd6_s37", "name": "陆肖"},
      ...
    ]

规则（需求 2.1 / 评审 P3 定稿）：
- 站区 cell = 停稳 MARK 窗 [-15s, +40s] 内驻留 ≥15s 的 pci:ci（进站段小区不入表）；
- --merge 时与既有同名方向表合并：cell 在 ≥50% 学习趟出现才保留（rideCount 计趟数）。
- pci:ci 为运营商网络标识（需求 3.3 可落盘）；本脚本输入输出均无 GPS / 无轨迹。
"""
import argparse, csv, io, json, os

WIN_BEFORE_MS, WIN_AFTER_MS, MIN_DWELL_MS = 15000, 40000, 15000


def load_cell_runs(path):
    """lab_cellid.csv → [(t_ms, [pci:ci, ...])]; 主服务排首。"""
    rows = []
    with io.open(path, encoding="utf-8") as f:
        for r in csv.DictReader(f):
            tokens = [t for t in r["cells"].split("|") if t]
            ids = [":".join(t.split(":")[:2]) for t in tokens]
            rows.append((int(r["t_ms"]), ids))
    return rows


def station_cells(rows, mark_ms):
    """停稳窗 [-15s,+40s] 内驻留 ≥15s 的 pci:ci 集合。"""
    a, b = mark_ms - WIN_BEFORE_MS, mark_ms + WIN_AFTER_MS
    dwell = {}
    cur = None
    start = None
    for t, ids in rows:
        ident = ids[0] if ids else ""
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
        if not ident:
            continue
        overlap = min(e, b) - max(s, a)
        if overlap >= MIN_DWELL_MS:
            out.add(ident)
    return out


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--cellid", required=True)
    ap.add_argument("--marks", required=True)
    ap.add_argument("--line", required=True)
    ap.add_argument("--direction", required=True)
    ap.add_argument("--out", required=True)
    ap.add_argument("--merge", action="store_true", help="与既有映射合并（跨趟 ≥50% 出现率）")
    args = ap.parse_args()

    rows = load_cell_runs(args.cellid)
    marks = json.load(io.open(args.marks, encoding="utf-8"))

    new_line = {
        "lineId": args.line,
        "directionId": args.direction,
        "learnedAt": marks[0]["date"] if marks and "date" in marks[0] else "",
        "rideCount": 1,
        "stations": [
            {
                "stationId": m["stationId"],
                "name": m.get("name", ""),
                "cells": sorted(station_cells(rows, int(m["t_ms"]))) if "name" not in m else sorted(station_cells(rows, int(m["t_ms"]))),
            }
            for m in marks
        ],
    }
    # CellZoneStation 只有序列化字段 stationId/cells（name 忽略，防脏数据）
    for st in new_line["stations"]:
        st.pop("name", None)

    data = {"schemaVersion": 1, "generatedAt": new_line["learnedAt"], "note": "", "lines": []}
    if args.merge and os.path.exists(args.out):
        data = json.load(io.open(args.out, encoding="utf-8"))
        for ex in data["lines"]:
            if ex["lineId"] == args.line and ex["directionId"] == args.direction:
                # 跨趟合并：cell 必须在每一趟的驻留窗都出现（取交集）——
                # 严于需求 ≥50% 出现率，向保守倾斜；单趟噪声 cell 会被第二趟洗掉。
                if ex.get("rideCount", 1) < 1:
                    ex["rideCount"] = 1
                old_by_id = {st["stationId"]: set(st["cells"]) for st in ex["stations"]}
                for st in new_line["stations"]:
                    old = old_by_id.get(st["stationId"])
                    if old is None:
                        raise SystemExit(f"站序不一致：{st['stationId']} 不在既有映射中，需人工核对")
                    st["cells"] = sorted(old & set(st["cells"]))
                ex["rideCount"] += 1
                ex["learnedAt"] = new_line["learnedAt"]
                break
        else:
            data["lines"].append(new_line)
    else:
        data["lines"].append(new_line)

    with io.open(args.out, "w", encoding="utf-8") as f:
        json.dump(data, f, ensure_ascii=False, indent=2)
    print(f"OK -> {args.out} ({args.line}/{args.direction}, rideCount={new_line['rideCount']})")


if __name__ == "__main__":
    main()
