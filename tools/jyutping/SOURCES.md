# Jyutping table source

- Dataset: Unicode Unihan `kCantonese` (Unihan_Readings.txt, Unicode 16.0.0).
- URL: https://www.unicode.org/Public/16.0.0/ucd/Unihan.zip
- License: Unicode License (https://www.unicode.org/license.txt). Readings may be used and redistributed with this notice.
- Extract: `tools/jyutping/unihan_kcantonese.tsv` — one `char<TAB>reading` per line, first kCantonese reading kept, tone digits stripped by `gen_jyutping_table.py`.
- Selection: every `hk_poi_master` Han character, plus `狼`／`毫`, plus other BMP kCantonese characters until the vendored extract has at least 3000 entries. Multi-reading characters use the first Unihan reading.
- Generator: `python tools/jyutping/gen_jyutping_table.py` (no network). Output: `lib/core/poi/jyutping_table.g.dart`.
- Lazy-sound rules in `lib/core/poi/jyutping.dart` are marked `// 真機調校前預設` and are not part of this table.
