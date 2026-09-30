#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""由用户提供的 OSM 成都地铁数据生成 app/src/main/assets/subway_lines.json。

用法：
    py gen_lines.py                     # 默认只生成 4 / 6 号线（本期范围）
    py gen_lines.py 4 6 1 2             # 指定线路 ref（站序取自数据文件，勿手改）

输入：D:\\AI\\AgentChat\\WorkBuddy\\chengdu_metro\\chengdu_metro.json
      （OSM via Overpass，WGS-84；sha256 见 docs/spec/active/real-line-data/需求.md 第四节）

规则（与需求文档「ID 与命名方案」一致）：
- 线路 id: cd<ref>；方向 id: cd<ref>_to_<终点站拼音>；
- 站 id: cd<ref>_sNN，NN = 数据文件 order（= 正向起点→终点顺序），从 01 起；
- 站名原样保留（含「成都西站」「中医大·省医院」等内含「站」的名称）；
- 方向显式写两遍（反向 = 正向站序倒置，站 id 不变）。

注意：输入的 stations 顺序即行车顺序，**不要**在输出里重排；
生成后请核对 stdout 打印的站数 / 端点 / 关键锚点，并跑 SubwayDataAssetTest。
"""
import json
import sys

SRC = r"D:\AI\AgentChat\WorkBuddy\chengdu_metro\chengdu_metro.json"
# 输出路径相对本脚本：docs/spec/active/real-line-data/assets/gen_lines.py
OUT = r"D:\Code\own-project\metro-stop\app\src\main\assets\subway_lines.json"

# 终点站 -> 拼音（方向 id 用；只覆盖本期 4/6 号线的端点）
TERMINAL_PINYIN = {
    4893005927: "wansheng",     # 4 号线 万盛
    4893006121: "xihe",         # 4 号线 西河
    6596545163: "wangcongzi",   # 6 号线 望丛祠
    6596725568: "lanjiagou",    # 6 号线 兰家沟
}

# 本期范围（用户指定）：只落 4 / 6 号线，其余以后再补
DEFAULT_REFS = ["4", "6"]


def main() -> int:
    refs = sys.argv[1:] or DEFAULT_REFS
    data = json.load(open(SRC, encoding="utf-8"))
    by_ref = {L["ref"]: L for L in data["lines"]}

    out_lines = []
    for ref in refs:
        L = by_ref.get(ref)
        if L is None:
            print(f"!! 数据文件里没有线路 ref={ref}", file=sys.stderr)
            return 2
        stations = L["stations"]
        # 站 id 与「正向」站序绑定：数据文件顺序 = 起点 → 终点
        sid = []
        for i, s in enumerate(stations, 1):
            sid.append(f"cd{ref}_s{i:02d}")
        # 正向 id 后缀取正向终点（最后一个站的拼音）
        start_name, end_name = stations[0]["name"], stations[-1]["name"]
        start_py = TERMINAL_PINYIN.get(stations[0]["id"])
        end_py = TERMINAL_PINYIN.get(stations[-1]["id"])
        if end_py is None or start_py is None:
            print(f"!! 线路 {ref} 端点缺少拼音映射：{start_name} -> {end_name}", file=sys.stderr)
            return 3

        fwd = [{"id": i, "name": s["name"]} for i, s in zip(sid, stations)]
        rev = list(reversed(fwd))
        out_lines.append({
            "id": f"cd{ref}",
            "name": f"{ref}号线",
            "directions": [
                {"id": f"cd{ref}_to_{end_py}", "name": f"开往 {end_name} 方向", "stations": fwd},
                {"id": f"cd{ref}_to_{start_py}", "name": f"开往 {start_name} 方向", "stations": rev},
            ],
        })
        print(f"[{ref}] {out_lines[-1]['name']}: {start_name} -> {end_name}, {len(fwd)} 站, "
              f"正向方向 id={out_lines[-1]['directions'][0]['id']}")

    out = {
        "schemaVersion": 1,
        "generatedAt": "2026-09-28",
        "city": "成都",
        "note": ("成都地铁 4/6 号线真实数据（本期只落 4/6，其余后续按同一流程补）。"
                 "来源：OpenStreetMap via Overpass API（WGS-84），"
                 "已与官网线路图（2026-09-24 版）及交通联合卡官方站码三源交叉核对；"
                 "生成脚本见 docs/spec/active/real-line-data/assets/gen_lines.py。方向显式写两遍，便于人工核对。"),
        "lines": out_lines,
    }
    with open(OUT, "w", encoding="utf-8", newline="\n") as f:
        json.dump(out, f, ensure_ascii=False, indent=2)
        f.write("\n")
    print(f"\n已写出 {OUT}（{len(out_lines)} 条线）")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
