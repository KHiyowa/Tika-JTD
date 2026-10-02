#!/usr/bin/env python3
"""突合: PDF グリッドオラクル vs 現行 XHTML（--xml 出力）。

XHTML の <table> 構造を PDF オラクルと同一の正準形（行/列・rowspan/colspan・セル文本）に
射影し、表単位の差分レポートを出力する。段階 0 スパイク（PDF 1 対象・表対応付けは y 中心順）。

使い方:
    tools/.venv-pdf/bin/python tools/pdf_grid_oracle/compare_grid.py <grid.json> <ours.xhtml>
"""

from __future__ import annotations

import argparse
import json
import re
import sys
from html.parser import HTMLParser
from pathlib import Path


class TableScrape(HTMLParser):
    """<table> を行<List[cell]> に射影（cell = text|rowspan|colspan）。"""

    def __init__(self):
        super().__init__()
        self.tables: list[list[list[tuple[str, int, int]]]] = []
        self._row: list | None = None
        self._cell_tag: str | None = None
        self._buf: list[str] = []
        self._rs = 1
        self._cs = 1

    def handle_starttag(self, tag, attrs):
        if tag == "table":
            self.tables.append([])
        elif tag == "tr" and self.tables:
            self._row = []
        elif tag in ("td", "th") and self._row is not None:
            a = dict(attrs)
            self._cell_tag = tag
            self._buf = []
            self._rs = int(a.get("rowspan", 1) or 1)
            self._cs = int(a.get("colspan", 1) or 1)

    def handle_endtag(self, tag):
        if tag in ("td", "th") and self._cell_tag == tag:
            text = re.sub(r"\s+", " ", "".join(self._buf)).strip()
            self._row.append((text, self._rs, self._cs))
            self._cell_tag = None
        elif tag == "tr" and self._row is not None:
            self.tables[-1].append(self._row)
            self._row = None

    def handle_data(self, data):
        if self._cell_tag:
            self._buf.append(data)

    def handle_entityref(self, name):
        if self._cell_tag:
            self._buf.append({"nbsp": " "}.get(name, f"&{name};"))

    def handle_charref(self, ref):
        if self._cell_tag:
            self._buf.append(chr(int(ref[1:], 16)) if ref.startswith("x") else chr(int(ref[1:])))


def norm(s: str) -> str:
    return re.sub(r"\s+", "", s)


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("grid_json", type=Path)
    ap.add_argument("xhtml", type=Path)
    args = ap.parse_args()

    grid = json.loads(args.grid_json.read_text(encoding="utf-8"))
    scrape = TableScrape()
    scrape.feed(args.xhtml.read_text(encoding="utf-8"))

    print(f"oracle tables: {sum(len(p['tables']) for p in grid['pages'])}"
          f"  ours tables: {len(scrape.tables)}")

    # ページ横断でオラクル行を平坦化（段階 0: y 順序＝ページ順・行順）
    oracle_rows = []
    for page in grid["pages"]:
        for tab in page["tables"]:
            for r in range(tab["rows"]):
                cells = [c for c in tab["cells"] if c["row"] == r]
                oracle_rows.append((page["page"], "|".join(norm(c["text"]) for c in cells if norm(c["text"]))))
    ours_rows = ["|".join(norm(t) for t, _, _ in row if norm(t)) for row in sum(scrape.tables, [])]

    oracle_set = {s for _, s in oracle_rows if s}
    ours_set = {s for s in ours_rows if s}
    matched = sorted(oracle_set & ours_set)
    print(f"row-anchor match: {len(matched)}/{len(oracle_set)} oracle rows "
          f"({len(matched) * 100 // max(1, len(oracle_set))}%)")
    miss = sorted(oracle_set - ours_set)
    extra = sorted(ours_set - oracle_set)
    for s in miss:
        print(f"  MISSING {s[:110]}")
    for s in extra:
        print(f"  EXTRA   {s[:110]}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
