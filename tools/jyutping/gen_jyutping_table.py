#!/usr/bin/env python3
"""Generate toneless Jyutping table from vendored Unihan kCantonese extract.

Unicode Unihan kCantonese is Unicode License data. See SOURCES.md.
CI must not download; this script only reads tools/jyutping/unihan_kcantonese.tsv.
"""
from __future__ import annotations

import json
import pathlib
import re
import sys

ROOT = pathlib.Path(__file__).resolve().parents[2]
TSV = pathlib.Path(__file__).resolve().parent / "unihan_kcantonese.tsv"
POI = ROOT / "assets" / "poi" / "hk_poi_master.json"
OUT = ROOT / "lib" / "core" / "poi" / "jyutping_table.g.dart"
TONE = re.compile(r"\d+$")
HAN = re.compile(r"[\u4e00-\u9fff]")


def toneless(raw: str) -> str:
    first = raw.split()[0]
    return TONE.sub("", first)


def load_tsv(path: pathlib.Path) -> dict[str, str]:
    table: dict[str, str] = {}
    for line in path.read_text(encoding="utf-8").splitlines():
        if not line or line.startswith("#"):
            continue
        ch, reading = line.split("\t", 1)
        table[ch] = toneless(reading)
    return table


def poi_hans(path: pathlib.Path) -> set[str]:
    doc = json.loads(path.read_text(encoding="utf-8"))
    found: set[str] = set()
    for poi in doc["pois"]:
        blob = "".join([poi.get("zh", ""), poi.get("en", ""), poi.get("d", ""), *poi.get("a", [])])
        found.update(HAN.findall(blob))
    return found


def main() -> int:
    table = load_tsv(TSV)
    hans = poi_hans(POI)
    missing = sorted(ch for ch in hans if ch not in table)
    if missing:
        print("unmapped", "".join(missing), file=sys.stderr)
        return 1
    forced = hans | set("狼毫")
    # Keep every POI han, then fill from the vendored extract until >= 3000.
    selected = dict(table)
    if len(selected) < 3000:
        print("vendored table smaller than 3000", file=sys.stderr)
        return 1
    lines = ["// GENERATED — do not edit", "// Source: tools/jyutping/unihan_kcantonese.tsv", "const String kJyutpingPacked = '''"]
    for ch in sorted(selected):
        lines.append(f"{ch}{selected[ch]}")
    lines.append("''';")
    text = "\n".join(lines) + "\n"
    OUT.write_text(text, encoding="utf-8", newline="\n")
    print(f"chars={len(selected)} poi_hans={len(hans)} bytes={OUT.stat().st_size} forced_ok={all(c in selected for c in forced)}")
    if OUT.stat().st_size > 60 * 1024:
        print("over 60KB", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
