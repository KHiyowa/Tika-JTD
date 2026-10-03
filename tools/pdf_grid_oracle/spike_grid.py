#!/usr/bin/env python3
"""PDF グリッドオラクル・スパイク (段階 0)。

一太郎印刷 PDF から罫線ベクトルと座標付きテキストを抽出し、
セルグリッド正解（行/列境界・rowspan/colspan・セル内テキスト）を
機械可読 JSON とオーバーレイ PNG に再構築する。

使い方:
    tools/.venv-pdf/bin/python tools/pdf_grid_oracle/spike_grid.py <pdf> [--out DIR]

出力:
    <stem>.grid.json    ページ毎の正準グリッド構造（突合ハーネスの正解側）
    <stem>.grid.md      同一構造の GFM パイプ表ダンプ（目視・突合用）
    <stem>.pN.overlay.png  グリッド重ね描き（青=検出セル・赤=表外テキスト・緑点=文本あり）

セル内部表現（tuple）: (k, xi, xj, yi, yj, box)
    k = ページ内グローバル cell 索引 / box = (x0, y0, x1, y1)
"""

from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path

import pymupdf

# 水平/垂直判定とグリッド座標スナップの許容幅（pt）
LINE_AXIS_TOL = 0.8
SNAP_TOL = 1.5
EDGE_COVER_TOL = 2.0
# 点線（破線）の認証: 一太郎の「うす罫」は 1.7〜2.4pt の短線 dense 列で現れる。
# 1本では境界と認めず、クラスタの条件（本数・総延長・スパン）で構造境界化する。
DASH_MAX_LEN = 6.0
DOT_AXIS_MIN_COUNT = 3
DOT_AXIS_MIN_SPAN = 12.0
DOT_END_TOL = 6.0


def collect_segments(page: pymupdf.Page):
    """描画パスから水平・垂直セグメントを軸別クラスタへ拾う。

    返す構造: {固定軸座標: {"solid": [(始点,終点)], "dots": [(始点,終点)]}}
    長線（>= DASH_MAX_LEN）は solid、短線（点・破線）は dots に集約する。
    細 long-fill rect も罫として扱う。
    """
    h: dict[float, dict] = {}
    v: dict[float, dict] = {}

    def add(buckets: dict, fixed: float, a0: float, a1: float):
        key = next((k for k in buckets if abs(k - fixed) <= SNAP_TOL), fixed)
        b = buckets.setdefault(key, {"solid": [], "dots": []})
        (b["solid"] if (a1 - a0) >= DASH_MAX_LEN else b["dots"]).append((min(a0, a1), max(a0, a1)))

    for path in page.get_drawings():
        for item in path["items"]:
            kind = item[0]
            if kind == "l":
                p1, p2 = item[1], item[2]
                dx, dy = abs(p2.x - p1.x), abs(p2.y - p1.y)
                if dy <= LINE_AXIS_TOL and dx >= 1.0:
                    add(h, (p1.y + p2.y) / 2, p1.x, p2.x)
                elif dx <= LINE_AXIS_TOL and dy >= 1.0:
                    add(v, (p1.x + p2.x) / 2, p1.y, p2.y)
            elif kind == "re":
                r = item[1]
                if r.width <= 1.5 and r.height > 1.5:
                    add(v, (r.x0 + r.x1) / 2, r.y0, r.y1)
                elif r.height <= 1.5 and r.width > 1.5:
                    add(h, (r.y0 + r.y1) / 2, r.x0, r.x1)
                else:
                    add(h, r.y0, r.x0, r.x1)
                    add(h, r.y1, r.x0, r.x1)
                    add(v, r.x0, r.y0, r.y1)
                    add(v, r.x1, r.y0, r.y1)
    return h, v


def axis_positions(buckets: dict) -> list[float]:
    """構造軸として成立した座標のみ昇順で返す。

    solid 軸: 常に成立。点線軸: dots 本数 >= DOT_AXIS_MIN_COUNT かつ
    スパン >= DOT_AXIS_MIN_SPAN を満たすものだけを認める（ノイズ点の排除）。
    """
    out = []
    for axis, b in buckets.items():
        if b["solid"]:
            out.append(axis)
        elif len(b["dots"]) >= DOT_AXIS_MIN_COUNT:
            span = max(e for _, e in b["dots"]) - min(s for s, _ in b["dots"])
            if span >= DOT_AXIS_MIN_SPAN:
                out.append(axis)
    return sorted(out)


def axis_cover(buckets: dict, axis: float, a0: float, a1: float) -> bool:
    """軸 axis が区間 [a0, a1] を罫として被覆するか（実線 1 本 or 点線 dense）。

    被覆判定は「許容幅内で多少の不足・超過を認める」方向:
    実線は s <= a0 + EDGE_COVER_TOL かつ e >= a1 - EDGE_COVER_TOL。
    点線は終端に DOT_END_TOL（角丸 inset 対策）をadditionalに見る。
    """
    b = buckets.get(axis)
    if b is None:
        return False
    if any(s <= a0 + EDGE_COVER_TOL and e >= a1 - EDGE_COVER_TOL for s, e in b["solid"]):
        return True
    dots = [(s, e) for s, e in b["dots"] if e >= a0 - DOT_END_TOL and s <= a1 + DOT_END_TOL]
    if len(dots) < DOT_AXIS_MIN_COUNT:
        return False
    return min(s for s, _ in dots) <= a0 + DOT_END_TOL and max(e for _, e in dots) >= a1 - DOT_END_TOL


def snap_axis(vals: list[float], tol: float = SNAP_TOL) -> list[float]:
    """座標値を tol 幅でクラスタリングし、代表値の昇順リストを返す。"""
    if not vals:
        return []
    vals = sorted(vals)
    clusters = [[vals[0]]]
    for x in vals[1:]:
        if x - clusters[-1][-1] <= tol:
            clusters[-1].append(x)
        else:
            clusters.append([x])
    return [sum(c) / len(c) for c in clusters]


def build_cells(hb, vb):
    """罫クラスタから存在するセル矩形を列挙する。

    構造軸（実線＋認証済み点線）の全組み合わせ（全域矩形）を試し、
    内部に被覆された構造軸が無く、上下どちらかの水平罫と左右どちらかの
    垂直罫が揃う矩形をセルとする。包含される小セルは除去し大矩形を優先
    （rowspan/colspan・点線 colspan セルの検出）。
    返す cell タプルは (k, xi, xj, yi, yj, box)。
    """
    xs = axis_positions(vb)
    ys = axis_positions(hb)
    rects = []
    for yi, y0 in enumerate(ys):
        for yj in range(yi + 1, len(ys)):
            y1 = ys[yj]
            for xi, x0 in enumerate(xs):
                for xj in range(xi + 1, len(xs)):
                    x1 = xs[xj]
                    if any(axis_cover(vb, xs[k], y0, y1) for k in range(xi + 1, xj)):
                        continue
                    if any(axis_cover(hb, ys[k], x0, x1) for k in range(yi + 1, yj)):
                        continue
                    top = axis_cover(hb, y0, x0, x1)
                    bot = axis_cover(hb, y1, x0, x1)
                    left = axis_cover(vb, x0, y0, y1)
                    right = axis_cover(vb, x1, y0, y1)
                    if (top or bot) and (left or right):
                        rects.append((xi, xj, yi, yj, (x0, y0, x1, y1)))

    rects.sort(key=lambda r: -((r[4][2] - r[4][0]) * (r[4][3] - r[4][1])))
    kept = []
    for r in rects:
        x0, y0, x1, y1 = r[4]
        if any(kx0 <= x0 + 1 and ky0 <= y0 + 1 and x1 <= kx1 + 1 and y1 <= ky1 + 1
               for _, _, _, _, _, (kx0, ky0, kx1, ky1) in kept):
            continue
        kept.append((len(kept), *r))
    return xs, ys, kept


def page_spans(page: pymupdf.Page):
    """可視テキストスパン（bbox 付き）を返す。"""
    spans = []
    for block in page.get_text("dict")["blocks"]:
        if block.get("type") != 0:
            continue
        for line in block["lines"]:
            for span in line["spans"]:
                text = span["text"].strip()
                if text:
                    spans.append((pymupdf.Rect(span["bbox"]), text))
    return spans


def assign_spans(spans, cells):
    """スパン中心を包含する最小セルに紐付ける（未割り当ては None）。"""
    out = []
    for rect, text in spans:
        cx, cy = (rect.x0 + rect.x1) / 2, (rect.y0 + rect.y1) / 2
        best, best_area = None, None
        for k, _, _, _, _, (x0, y0, x1, y1) in cells:
            if x0 - 1 <= cx <= x1 + 1 and y0 - 1 <= cy <= y1 + 1:
                area = (x1 - x0) * (y1 - y0)
                if best_area is None or area < best_area:
                    best, best_area = k, area
        out.append((best, text))
    return out


def group_tables(cells):
    """辺共有で連結するセル群をテーブルとして束ねる（孤立セルは単独表）。"""
    parent = list(range(len(cells)))

    def find(a):
        while parent[a] != a:
            parent[a] = parent[parent[a]]
            a = parent[a]
        return a

    def union(a, b):
        ra, rb = find(a), find(b)
        if ra != rb:
            parent[rb] = ra

    for i in range(len(cells)):
        bi = cells[i][5]
        for j in range(i + 1, len(cells)):
            bj = cells[j][5]
            overlap_x = bi[0] < bj[2] and bj[0] < bi[2]
            overlap_y = bi[1] < bj[3] and bj[1] < bi[3]
            if overlap_x and overlap_y:
                continue  # 包含・重なりは build_cells 側で解消済み
            touch_x = (abs(bi[2] - bj[0]) <= SNAP_TOL or abs(bj[2] - bi[0]) <= SNAP_TOL
                       or bi[0] < bj[0] < bi[2] or bj[0] < bi[0] < bj[2])
            touch_y = (abs(bi[3] - bj[1]) <= SNAP_TOL or abs(bj[3] - bi[1]) <= SNAP_TOL
                       or bi[1] < bj[1] < bi[3] or bj[1] < bi[1] < bj[3])
            if (touch_x and overlap_y) or (touch_y and overlap_x):
                union(i, j)

    groups: dict[int, list] = {}
    for i in range(len(cells)):
        groups.setdefault(find(i), []).append(cells[i])
    return list(groups.values())


def process_page(page: pymupdf.Page, page_no: int):
    hb, vb = collect_segments(page)
    xs, ys, cells = build_cells(hb, vb)
    n_h = sum(len(b["solid"]) + len(b["dots"]) for b in hb.values())
    n_v = sum(len(b["solid"]) + len(b["dots"]) for b in vb.values())
    spans = page_spans(page)
    assign = assign_spans(spans, cells)

    cell_texts: dict[int, list[str]] = {}
    for k, text in assign:
        if k is not None:
            cell_texts.setdefault(k, []).append(text)

    tables = []
    for group in group_tables(cells):
        xs_t = snap_axis(sorted({c for cell in group for c in (cell[5][0], cell[5][2])}))
        ys_t = snap_axis(sorted({c for cell in group for c in (cell[5][1], cell[5][3])}))
        rows_n = max(1, len(ys_t) - 1)
        cols_n = max(1, len(xs_t) - 1)

        def ix(x, xs=xs_t):
            return min(range(len(xs)), key=lambda k: abs(xs[k] - x))

        def iy(y, ys=ys_t):
            return min(range(len(ys)), key=lambda k: abs(ys[k] - y))

        out_cells = []
        for k, _xi, _xj, _yi, _yj, box in group:
            out_cells.append({
                "row": iy(box[1]), "col": ix(box[0]),
                "rowspan": max(1, iy(box[3]) - iy(box[1])),
                "colspan": max(1, ix(box[2]) - ix(box[0])),
                "bbox": [round(v, 2) for v in box],
                "text": " ".join(cell_texts.get(k, [])),
            })
        out_cells.sort(key=lambda c: (c["row"], c["col"]))
        tables.append({"rows": rows_n, "cols": cols_n, "cells": out_cells})

    unassigned = [
        {"bbox": [round(rect.x0, 2), round(rect.y0, 2), round(rect.x1, 2), round(rect.y1, 2)], "text": text}
        for (k, _), (rect, text) in zip(assign, spans) if k is None]

    return {
        "page": page_no,
        "size": [round(page.rect.width, 1), round(page.rect.height, 1)],
        "h_lines": n_h,
        "v_lines": n_v,
        "tables": tables,
        "spans_total": len(spans),
        "spans_unassigned": unassigned,
    }


def draw_overlay(doc: pymupdf.Document, result, out_png: Path):
    page = doc[result["page"]]
    for tab in result["tables"]:
        for c in tab["cells"]:
            r = pymupdf.Rect(c["bbox"])
            page.draw_rect(r, color=(0, 0, 1), width=0.7)
            if c["text"]:
                page.draw_rect(pymupdf.Rect(r.x0 + 1, r.y0 + 1, r.x0 + 4, r.y0 + 4),
                              fill=(0, 0.8, 0), color=None)
    for u in result["spans_unassigned"]:
        b = u["bbox"]
        page.draw_rect(pymupdf.Rect(b), color=(1, 0, 0), width=0.7)
    pix = page.get_pixmap(matrix=pymupdf.Matrix(2, 2))
    pix.save(str(out_png))


def to_pipe_md(result) -> list[str]:
    """グリッドを GFM パイプ表に平坦化（突合・目視用）。"""
    lines = []
    for ti, tab in enumerate(result["tables"]):
        lines.append(f"<!-- table {ti}: {tab['rows']}x{tab['cols']} -->")
        occupied: dict[tuple[int, int], str] = {}
        skip: set[tuple[int, int]] = set()
        for c in tab["cells"]:
            occupied[(c["row"], c["col"])] = c["text"]
            for r in range(c["row"], c["row"] + c["rowspan"]):
                for col in range(c["col"], c["col"] + c["colspan"]):
                    if (r, col) != (c["row"], c["col"]):
                        skip.add((r, col))
        for r in range(tab["rows"]):
            row = []
            for col in range(tab["cols"]):
                if (r, col) in skip:
                    continue
                row.append(occupied.get((r, col), ""))
            lines.append("| " + " | ".join(row) + " |")
            if r == 0:
                lines.append("|" + "---|" * tab["cols"])
        lines.append("")
    return lines


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("pdf", type=Path)
    ap.add_argument("--out", type=Path, default=None)
    args = ap.parse_args()

    out_dir = args.out or args.pdf.parent
    out_dir.mkdir(parents=True, exist_ok=True)

    doc = pymupdf.open(args.pdf)
    stem = args.pdf.stem
    all_pages = []
    for pno in range(len(doc)):
        res = process_page(doc[pno], pno)
        all_pages.append(res)
        draw_overlay(doc, res, out_dir / f"{stem}.p{pno}.overlay.png")
        tdesc = ", ".join(f"{t['rows']}x{t['cols']}" for t in res["tables"])
        print(f"page {pno}: tables={len(res['tables'])} [{tdesc}] "
              f"cells={sum(len(t['cells']) for t in res['tables'])} "
              f"spans={res['spans_total']} unassigned={len(res['spans_unassigned'])}")

    (out_dir / f"{stem}.grid.json").write_text(
        json.dumps({"source": str(args.pdf), "pages": all_pages}, ensure_ascii=False, indent=1),
        encoding="utf-8")
    md = []
    for res in all_pages:
        md += to_pipe_md(res)
    (out_dir / f"{stem}.grid.md").write_text("\n".join(md) + "\n", encoding="utf-8")
    print(f"out: {out_dir / (stem + '.grid.json')}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
