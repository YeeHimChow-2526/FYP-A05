#!/usr/bin/env python3
"""Build android/app/src/main/assets/hk_poi_master.json (Step 3.1).

Standard library only (Python 3.11+).
Usage (from repo root):  py tools/poi/build_poi_master.py --raw ../poi_raw

Mall trim order when over 153,600 bytes (malls only): malls with no TD car park within
150 m go first, then TD-near malls, then malls that are alias targets in aliases_overlay.json;
within each tier, largest id (string sort) is removed first.

Raw downloads are cached in --raw; an existing raw file is never re-downloaded.
TLS is always verified. If a download fails (SSL / HTTP), the script prints the
URL and the exact filename to save manually into --raw, then exits non-zero.
--cafile adds an EXTRA CA/intermediate PEM to the default trust store (verification
stays on); useful for open.hkmapservice.gov.hk, whose server omits its intermediate
("Hongkong Post e-Cert SSL CA 3 - 17").
"""
import argparse
import datetime
import hashlib
import json
import math
import os
import ssl
import sys
import unicodedata
import urllib.error
import urllib.request
import zipfile

HERE = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.normpath(os.path.join(HERE, "..", ".."))
DEFAULT_OUT = os.path.join(REPO, "android", "app", "src", "main", "assets", "hk_poi_master.json")
OVERLAY = os.path.join(HERE, "aliases_overlay.json")

SIZE_CAP = 153600
MALL_TD_RADIUS_M = 150.0
ATTRIBUTION = "資料來源：地政總署、運輸署、民政事務總署、DATA.GOV.HK、CSDI Portal"

WFS = ("https://portal.csdi.gov.hk/server/services/common/landsd_rcd_1648571595120_89752/"
       "MapServer/WFSServer?service=wfs&version=2.0.0&request=GetFeature&typeNames={layer}"
       "&outputFormat=geojson")
SOURCES = {
    "pn": ("csdi_PLACE_NAME.geojson", WFS.format(layer="csdi:PLACE_NAME")),
    "gpn": ("csdi_GEO_PLACE_NAME.geojson", WFS.format(layer="csdi:GEO_PLACE_NAME")),
    "ig": ("iGeoCom_GEOJSON.zip",
           "https://open.hkmapservice.gov.hk/OpenData/directDownload?productName=iGeoCom"
           "&sheetName=iGeoCom&productFormat=GEOJSON"),
    "td": ("td_basic_info_all.json", "https://resource.data.one.gov.hk/td/carpark/basic_info_all.json"),
    "had": ("hksar_18_district_boundary.json",
            "https://www.had.gov.hk/psi/hong-kong-administrative-boundaries/hksar_18_district_boundary.json"),
}

# ---------------------------------------------------------------- normalise
SUFFIXES = sorted(["停車場", "停車塲", "商場", "購物中心", "carpark", "carparks", "parking",
                   "shoppingmall", "shoppingcentre", "mall"], key=lambda s: -len(s))


def normalize(s: str) -> str:
    s = unicodedata.normalize("NFKC", s or "").lower()
    s = "".join(ch for ch in s if unicodedata.category(ch)[0] not in ("P", "Z"))
    for suf in SUFFIXES:
        if s.endswith(suf):
            stripped = s[: -len(suf)]
            return stripped if stripped else s
    return s


def keys_of(p):
    out = []
    for raw in [p["zh"], p["en"]] + list(p["a"]):
        if not raw:
            continue
        k = normalize(raw)
        if k and k not in out:
            out.append(k)
    return out


# ---------------------------------------------------------------- download
def fetch(raw_dir, key, cafile):
    name, url = SOURCES[key]
    path = os.path.join(raw_dir, name)
    if os.path.exists(path) and os.path.getsize(path) > 0:
        return path
    ctx = ssl.create_default_context()  # verified; never disabled
    if cafile:
        ctx.load_verify_locations(cafile=cafile)
    print(f"downloading {url}", flush=True)
    try:
        req = urllib.request.Request(url, headers={"User-Agent": "Mozilla/5.0 (build_poi_master.py)"})
        with urllib.request.urlopen(req, context=ctx, timeout=600) as r:
            data = r.read()
    except (urllib.error.URLError, ssl.SSLError, OSError) as e:
        print(f"\nDOWNLOAD FAILED: {e!r}", file=sys.stderr)
        print(f"  URL : {url}", file=sys.stderr)
        print(f"  Save it manually (e.g. with a browser) as: {path}", file=sys.stderr)
        print(f"  filename: {name}", file=sys.stderr)
        sys.exit(2)
    tmp = path + ".part"
    with open(tmp, "wb") as f:
        f.write(data)
    os.replace(tmp, path)
    return path


def sha256(path):
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for b in iter(lambda: f.read(1 << 20), b""):
            h.update(b)
    return h.hexdigest()


def load_json(path):
    with open(path, "r", encoding="utf-8-sig") as f:
        return json.load(f)


# ---------------------------------------------------------------- geometry
def ring_contains(ring, x, y):
    inside = False
    n = len(ring)
    j = n - 1
    for i in range(n):
        xi, yi = ring[i][0], ring[i][1]
        xj, yj = ring[j][0], ring[j][1]
        if (yi > y) != (yj > y):
            xc = (xj - xi) * (y - yi) / (yj - yi) + xi
            if x < xc:
                inside = not inside
        j = i
    return inside


def polys_of(geom):
    if geom["type"] == "Polygon":
        return [geom["coordinates"]]
    if geom["type"] == "MultiPolygon":
        return geom["coordinates"]
    raise ValueError("unsupported geometry " + geom["type"])


def geom_contains(polys, x, y):
    for poly in polys:
        if ring_contains(poly[0], x, y) and not any(ring_contains(h, x, y) for h in poly[1:]):
            return True
    return False


def ring_area_centroid(ring):
    a = cx = cy = 0.0
    n = len(ring)
    for i in range(n):
        x0, y0 = ring[i][0], ring[i][1]
        x1, y1 = ring[(i + 1) % n][0], ring[(i + 1) % n][1]
        c = x0 * y1 - x1 * y0
        a += c
        cx += (x0 + x1) * c
        cy += (y0 + y1) * c
    return a / 2.0, cx, cy


def geom_centroid(polys):
    """Area-weighted centroid of all outer rings minus holes."""
    A = CX = CY = 0.0
    for poly in polys:
        for idx, ring in enumerate(poly):
            a, cx, cy = ring_area_centroid(ring)
            sign = 1.0 if idx == 0 else -1.0
            a = abs(a) * sign
            # cx,cy are 6*A*centroid with orientation sign; normalise via centroid
            if a == 0:
                continue
            raw_a = ring_area_centroid(ring)[0]
            gx, gy = cx / (6.0 * raw_a), cy / (6.0 * raw_a)
            A += a
            CX += a * gx
            CY += a * gy
    return CX / A, CY / A


def haversine(lat1, lon1, lat2, lon2):
    r = 6371008.8
    p1, p2 = math.radians(lat1), math.radians(lat2)
    dp = p2 - p1
    dl = math.radians(lon2 - lon1)
    h = math.sin(dp / 2) ** 2 + math.cos(p1) * math.cos(p2) * math.sin(dl / 2) ** 2
    return 2 * r * math.asin(math.sqrt(h))


# ---------------------------------------------------------------- main
def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--raw", required=True, help="raw download cache dir")
    ap.add_argument("--out", default=DEFAULT_OUT)
    ap.add_argument("--overlay", default=OVERLAY)
    ap.add_argument("--cafile", default=None, help="extra CA/intermediate PEM (verification stays ON)")
    ap.add_argument("--built", default=None, help="override built date YYYY-MM-DD")
    args = ap.parse_args()
    os.makedirs(args.raw, exist_ok=True)
    paths = {k: fetch(args.raw, k, args.cafile) for k in SOURCES}

    # ---- D: districts
    had = load_json(paths["had"])
    districts = []  # (short_zh, full_zh, en, polys, cx, cy)
    for f in had["features"]:
        pr = f["properties"]
        full = pr.get("地區") or pr.get("District_TC") or pr.get("NAME_TC")
        en = pr.get("District") or pr.get("NAME_EN") or ""
        short = full[:-1] if full.endswith("區") else full
        polys = polys_of(f["geometry"])
        cx, cy = geom_centroid(polys)
        districts.append((short, full, en, polys, cx, cy))
    if len(districts) != 18:
        print(f"expected 18 districts, got {len(districts)}", file=sys.stderr)
        sys.exit(3)
    fallbacks = []

    def district_of(lon, lat, label):
        for short, _full, _en, polys, _cx, _cy in districts:
            if geom_contains(polys, lon, lat):
                return short
        best = min(districts, key=lambda d: (d[4] - lon) ** 2 + ((d[5] - lat) ** 2))
        fallbacks.append((label, best[0]))
        return best[0]

    pois = []
    dropped_short = []

    def add(pid, t, zh, en, lat, lon, d=None):
        zh = (zh or "").strip()
        en = (en or "").strip()
        if len(normalize(zh)) < 2:
            dropped_short.append(pid)
            return
        if d is None:
            d = district_of(lon, lat, pid)
        pois.append({"id": pid, "t": t, "zh": zh, "en": en, "a": [], "d": d,
                     "la": round(float(lat), 5), "lo": round(float(lon), 5)})

    for short, full, en, polys, cx, cy in districts:
        # zh = full name (e.g. 南區) so 南／東／北 survive the 2-char rule; short form as alias when >= 2 chars
        add(f"dist:{short}", "D", full, en, cy, cx, d=short)
        if len(normalize(short)) >= 2 and pois and pois[-1]["id"] == f"dist:{short}":
            pois[-1]["a"].append(short)

    # ---- A: Lands Dept place names
    pn = load_json(paths["pn"])["features"]
    gpn = load_json(paths["gpn"])["features"]
    geo = {}
    for f in gpn:
        pr = f["properties"]
        if f.get("geometry") and f["geometry"].get("type") == "Point":
            geo[str(pr["GEO_NAME_ID"])] = (pr, f["geometry"]["coordinates"])
    n_area = 0
    for f in pn:
        pr = f["properties"]
        if pr.get("NAME_STATUS") != "Official":
            continue
        g = geo.get(str(pr["GEO_NAME_ID"]))
        if not g or g[0].get("PLACE_TYPE") not in ("Area", "Town"):
            continue
        lon, lat = g[1][0], g[1][1]
        add(f"pn:{pr['GEO_NAME_ID']}", "A", pr.get("NAME_TC"), pr.get("NAME_EN"), lat, lon)
        n_area += 1

    # ---- C: TD car parks
    td = load_json(paths["td"])["car_park"]
    td_pts = []
    for c in td:
        add(f"td:{c['park_id']}", "C", c.get("name_tc"), c.get("name_en"), c["latitude"], c["longitude"])
        td_pts.append((float(c["latitude"]), float(c["longitude"])))

    # ---- M: iGeoCom malls
    with zipfile.ZipFile(paths["ig"]) as z:
        name = next(n for n in z.namelist() if n.lower().endswith(".geojson"))
        ig = json.loads(z.read(name).decode("utf-8-sig"))["features"]
    malls_raw = [f for f in ig if f["properties"].get("TYPE") == "MAL"]
    for f in malls_raw:
        pr = f["properties"]
        lon, lat = f["geometry"]["coordinates"][:2]
        add(f"ig:{pr['GEONAMEID']}", "M", pr.get("CHINESENAME"), pr.get("ENGLISHNAME"), lat, lon)

    # ---- mall dedup: same normalize(zh) AND same d -> keep smallest id
    seen = {}
    dedup_removed = []
    for p in sorted((p for p in pois if p["t"] == "M"), key=lambda p: p["id"]):
        k = (normalize(p["zh"]), p["d"])
        if k in seen:
            dedup_removed.append(p["id"])
        else:
            seen[k] = p["id"]
    rm = set(dedup_removed)
    pois = [p for p in pois if p["id"] not in rm]
    ids = [p["id"] for p in pois]
    if len(ids) != len(set(ids)):
        print("duplicate ids", file=sys.stderr)
        sys.exit(4)

    # ---- aliases
    overlay = load_json(args.overlay)
    alias_rows = []
    for row in overlay:
        tk = normalize(row["zh"])
        targets = [p for p in pois if p["t"] == row["t"] and (normalize(p["zh"]) == tk or (p["en"] and normalize(p["en"]) == tk))]
        alias_rows.append((row, targets))
        for p in targets:
            if row["alias"] not in p["a"]:
                p["a"].append(row["alias"])

    pois.sort(key=lambda p: p["id"])
    built = args.built or datetime.date.today().isoformat()
    dist_names = sorted(d[0] for d in districts)

    def render(ps):
        doc = {"v": 1, "built": built, "attribution": ATTRIBUTION, "districts": dist_names, "pois": ps}
        return json.dumps(doc, ensure_ascii=False, separators=(",", ":")).encode("utf-8")

    def pbytes(p):
        return len(json.dumps(p, ensure_ascii=False, separators=(",", ":")).encode("utf-8")) + 1

    # ---- size cap: trim malls only
    total = len(render(pois))
    trimmed = []
    if total > SIZE_CAP:
        malls = [p for p in pois if p["t"] == "M"]
        near = set()
        for p in malls:
            if any(haversine(p["la"], p["lo"], la, lo) <= MALL_TD_RADIUS_M for la, lo in td_pts):
                near.add(p["id"])
        # protected = malls that are targets of an overlay alias (e.g. 朗豪坊商場 has no TD car park
        # within 150 m, IFC is 155 m away) -> removed last, so curated aliases never silently vanish.
        protected = {p["id"] for _row, ts in alias_rows for p in ts if p["t"] == "M"}
        def tier(p):
            return 2 if p["id"] in protected else (1 if p["id"] in near else 0)
        order = []
        for tr in (0, 1, 2):
            order += sorted((p for p in malls if tier(p) == tr), key=lambda p: p["id"], reverse=True)
        drop = set()
        for p in order:
            if total <= SIZE_CAP:
                break
            total -= pbytes(p)
            drop.add(p["id"])
            trimmed.append(p["id"])
        pois = [p for p in pois if p["id"] not in drop]
        print(f"malls near TD car park (<= {MALL_TD_RADIUS_M:.0f} m): {len(near)} of {len(malls)}; "
              f"alias-protected malls: {len(protected)} ({sum(1 for i in protected if i not in near)} of them not near TD)")
    out = render(pois)
    while len(out) > SIZE_CAP:  # safety net (separator accounting)
        ms = [p for p in pois if p["t"] == "M"]
        if not ms:
            print(f"still {len(out)} bytes > {SIZE_CAP} after removing all malls", file=sys.stderr)
            sys.exit(5)
        victim = ms[-1]["id"]
        pois = [p for p in pois if p["id"] != victim]
        trimmed.append(victim)
        out = render(pois)

    # ---- alias resolution report (against final, post-trim set)
    final_ids = {p["id"] for p in pois}
    unresolved, multi = [], []
    for row, targets in alias_rows:
        live = [p["id"] for p in targets if p["id"] in final_ids]
        if not live:
            unresolved.append(row["alias"])
            print(f"UNRESOLVED {row['alias']}")
        elif len(live) > 1:
            multi.append((row["alias"], live))
    os.makedirs(os.path.dirname(os.path.abspath(args.out)), exist_ok=True)
    with open(args.out, "wb") as f:
        f.write(out)

    # ---- report
    cnt = {}
    for p in pois:
        cnt[p["t"]] = cnt.get(p["t"], 0) + 1
    print("=== hk_poi_master.json ===")
    print(f"output: {args.out}")
    print(f"built: {built}")
    print("counts: " + ", ".join(f"{t}={cnt.get(t, 0)}" for t in "DACM") + f", total={len(pois)}")
    print(f"source rows: place-name A/T official={n_area}, TD car parks={len(td)}, iGeoCom MAL={len(malls_raw)}")
    print(f"dropped (normalize(zh) < 2 chars): {len(dropped_short)} {dropped_short[:20]}")
    print(f"mall dedup removed: {len(dedup_removed)}")
    print(f"file bytes: {len(out)} (cap {SIZE_CAP})")
    print(f"malls trimmed: {len(trimmed)}")
    print(f"nearest-centroid fallbacks: {len(fallbacks)} {fallbacks}")
    print(f"UNRESOLVED: {unresolved}")
    for a, live in multi:
        print(f"MULTI alias {a} -> {live}")
    for k, (name, url) in SOURCES.items():
        print(f"sha256 {name} {sha256(paths[k])}")
    if unresolved:
        sys.exit(6)


if __name__ == "__main__":
    main()
