#!/usr/bin/env python3
# Copyright (c) 1986-2026 Eldad Galker, eldad@galker.com, https://www.galker.com/software/
# This software is released under the BSD 3-Clause License.
# See the LICENSE.txt file in the project root for full license information.
# Version 3.6
"""Builds data/targets.json (settlements + aimable landmarks) from OpenStreetMap (Overpass API).

Scope
  Core      : every object inside the OSM area tagged ISO3166-1=IL
  Border    : objects outside the core within NEIGHBOR_BUFFER_KM of any core settlement,
              i.e. only the neighbouring-country strip that can fall inside the app's target range
  Anchors   : core settlement coordinates are written to tools/anchors.json; build_dem_tiles.py uses
              them to keep terrain tiles only where an observer's line of sight can reach

Target kinds (code -> OSM selectors), must match domain/TargetKind.kt
  settlement  : place=city|town|village|hamlet
  peak        : natural=peak|hill|volcano
  tower       : man_made=tower|mast|communications_tower
  lighthouse  : man_made=lighthouse
  chimney     : man_made=chimney
  water_tower : man_made=water_tower
  castle      : historic=castle|fort
  ruins       : historic=ruins|archaeological_site
  monument    : historic=monument
  Only named objects are kept; ways/relations are reduced to their centre point.

Settlement areas (key "areas" of targets.json)
  A settlement is not a point: the app names the place the camera is aimed at by the outline that contains the aimed
  ground point. Outlines come from municipal boundaries (admin_level=8) and from settlements mapped as areas, are
  simplified to AREA_SIMPLIFY_M and attached to the settlement item of the same name (by name, lat, lon).
  { "name", "lat", "lon", "rings": [[lat, lon, lat, lon, ...], ...] }, every ring closed, holes ignored.
  When the area query fails, the published areas are kept, so a failing instance never removes them.

Conventions
  Coordinates: WGS84 decimal degrees
  Distances  : kilometres (local equirectangular approximation, adequate below ~100 km)
  Altitude   : OSM 'ele' (m) when present, else null (the app uses the DEM)
  Height     : OSM 'height' (m) when present, else null (the app uses the kind default)
  Name       : first available of NAME_KEYS
  Licence    : OSM data is ODbL; attribution "(c) OpenStreetMap contributors" is required
"""

import argparse
import json
import math
import re
import shutil
import sys
import time
from pathlib import Path

import numpy as np
import requests

# =============================================================
# Parameters
# =============================================================
# Public Overpass instances, tried in order; a failure on one moves to the next.
OVERPASS_ENDPOINTS = (
    "https://overpass-api.de/api/interpreter",
    "https://overpass.kumi.systems/api/interpreter",
    "https://overpass.private.coffee/api/interpreter",
)
# Overpass operators reject anonymous library user agents (HTTP 406/403); identify the client.
HTTP_HEADERS = {
    "User-Agent": "PointAndIdentify-data/3.2 (github.com/eldadgalker-dev/PointAndIdentify; eldad@galker.com)",
    "Accept": "application/json",
}
ENDPOINT_PAUSE_S = 15                     # wait before trying the next instance
CORE_AREA_FILTER = '["ISO3166-1"="IL"]["admin_level"="2"]'
SEARCH_BBOX = (28.9, 33.6, 34.0, 36.7)    # deg (min_lat, min_lon, max_lat, max_lon), ESTIMATE: core + buffer
NEIGHBOR_BUFFER_KM = 50.0                 # km, must match AppConfig.MAX_TARGET_RANGE_M / 1000
NAME_KEYS = ("name:he", "name:en", "name")  # priority; a Hebrew-script "name" outranks "name:en"
HEBREW_SCRIPT = re.compile("[\u0590-\u05FF]")
TIMEOUT_S = 900
COORD_DECIMALS = 6                        # ~0.1 m
ANCHOR_DECIMALS = 3                       # ~100 m, enough for buffer tests
DEDUPE_DECIMALS = 3                       # ~100 m, same name within this grid = duplicate
KM_PER_DEG_LAT = 111.32
DISTANCE_CHUNK = 2000                     # candidates per vectorised distance block
MIN_KEEP_RATIO = 0.8                      # a result with fewer items than this share of the published list is refused
# Outlines of settlements: municipal boundaries, and settlements mapped as an area (way or multipolygon).
AREA_SELECTORS = (
    'relation["boundary"="administrative"]["admin_level"="8"]["name"]',
    'wr["place"~"^(city|town|village|hamlet)$"]["name"]',
)
AREA_GRID = 2                             # the search box is queried for outlines in AREA_GRID x AREA_GRID parts
AREA_SIMPLIFY_M = 20.0                    # m, Douglas-Peucker tolerance of an outline (position accuracy of a phone)
AREA_COORD_DECIMALS = 5                   # ~1 m
NAME_PUNCT = re.compile("[\u05BE\u05F3\u05F4\u2010-\u2015\\-\"'.,()]")  # maqaf, geresh, dashes, quotes

# Kind code -> list of (OSM key, regex of values). Order = priority when an object matches several.
KIND_SELECTORS = [
    ("settlement", "place", "city|town|village|hamlet"),
    ("peak", "natural", "peak|hill|volcano"),
    ("lighthouse", "man_made", "lighthouse"),
    ("chimney", "man_made", "chimney"),
    ("water_tower", "man_made", "water_tower"),
    ("tower", "man_made", "tower|mast|communications_tower"),
    ("castle", "historic", "castle|fort"),
    ("ruins", "historic", "ruins|archaeological_site"),
    ("monument", "historic", "monument"),
]

# Validation
assert NEIGHBOR_BUFFER_KM > 0, "NEIGHBOR_BUFFER_KM must be positive"
assert 0 < MIN_KEEP_RATIO <= 1, "MIN_KEEP_RATIO must be in (0, 1]"
assert AREA_SIMPLIFY_M > 0 and AREA_COORD_DECIMALS >= 4, "Invalid settlement-area parameters"
assert isinstance(AREA_GRID, int) and 1 <= AREA_GRID <= 8, "AREA_GRID must be an integer in 1..8"
assert SEARCH_BBOX[0] < SEARCH_BBOX[2] and SEARCH_BBOX[1] < SEARCH_BBOX[3], "SEARCH_BBOX must satisfy MIN < MAX"


def overpass(query: str) -> list:
    """Runs the query on the first instance that answers with valid JSON; logs every failure."""
    errors = []
    for i, url in enumerate(OVERPASS_ENDPOINTS):
        if i:
            time.sleep(ENDPOINT_PAUSE_S)
        try:
            r = requests.post(url, data={"data": query}, headers=HTTP_HEADERS, timeout=TIMEOUT_S + 60)
            if r.status_code != 200:
                snippet = " ".join(r.text.split())[:300]
                raise RuntimeError(f"HTTP {r.status_code}: {snippet}")
            data = r.json()
            remark = str(data.get("remark", ""))
            if remark and (not data.get("elements") or "runtime error" in remark.lower()):
                # Overpass reports server-side timeouts / memory limits as a remark; a "runtime error" remark can come
                # together with PARTIAL elements, which must never be accepted as complete data.
                raise RuntimeError(f"server remark: {remark[:300]}")
            print(f"overpass: {url} -> {len(data.get('elements', []))} elements")
            return data.get("elements", [])
        except (requests.RequestException, ValueError, RuntimeError) as e:
            print(f"overpass: {url} failed: {e}")
            errors.append(f"{url}: {e}")
    raise RuntimeError("All Overpass instances failed:\n  " + "\n  ".join(errors))


def selectors(scope: str) -> str:
    """Overpass union of all kind selectors, named objects only, for one scope suffix."""
    parts = [f'nwr["{key}"~"^({values})$"]["name"]{scope};' for _, key, values in KIND_SELECTORS]
    return "(" + "".join(parts) + ");"


def query_core() -> list:
    q = (f'[out:json][timeout:{TIMEOUT_S}];area{CORE_AREA_FILTER}->.core;'
         f'{selectors("(area.core)")}out center tags;')
    return overpass(q)


def query_bbox(bbox) -> list:
    s, w, n, e = bbox
    q = f'[out:json][timeout:{TIMEOUT_S}];{selectors(f"({s},{w},{n},{e})")}out center tags;'
    return overpass(q)


def point_of(el):
    """Node coordinates, or the server-computed centre for ways/relations."""
    if "lat" in el:
        return el["lat"], el["lon"]
    c = el.get("center")
    return (c["lat"], c["lon"]) if c else None


def kind_of(tags: dict):
    for code, key, values in KIND_SELECTORS:
        if re.fullmatch(values, tags.get(key, "")):
            return code
    return None


def min_distance_km(cand_lat, cand_lon, anchor_lat, anchor_lon) -> np.ndarray:
    """Distance from each candidate to its nearest anchor; equirectangular projection per block."""
    out = np.empty(cand_lat.size)
    for start in range(0, cand_lat.size, DISTANCE_CHUNK):
        la = cand_lat[start:start + DISTANCE_CHUNK, None]
        lo = cand_lon[start:start + DISTANCE_CHUNK, None]
        k = np.cos(np.radians((la + anchor_lat[None, :]) / 2.0))
        dx = (lo - anchor_lon[None, :]) * k * KM_PER_DEG_LAT
        dy = (la - anchor_lat[None, :]) * KM_PER_DEG_LAT
        out[start:start + DISTANCE_CHUNK] = np.sqrt(dx * dx + dy * dy).min(axis=1)
    return out


def pick_name(tags: dict):
    """name:he, else a Hebrew-script name, else the remaining NAME_KEYS in order."""
    if tags.get("name:he"):
        return tags["name:he"]
    if tags.get("name") and HEBREW_SCRIPT.search(tags["name"]):
        return tags["name"]
    for key in NAME_KEYS:
        if tags.get(key):
            return tags[key]
    return None


def parse_metres(value):
    """Parses OSM numeric tags like '575', '575 m', '30.5'; feet or garbage -> None."""
    if value is None:
        return None
    text = str(value).strip().lower()
    if "ft" in text or "'" in text:
        return None
    m = re.match(r"^-?\d+(\.\d+)?(?=\s*(m|meters?|metres?)?$)", text.replace(",", "."))
    if not m:
        return None
    v = float(m.group(0))
    return round(v, 1) if math.isfinite(v) else None


def to_item(el):
    tags = el.get("tags", {})
    pt = point_of(el)
    kind = kind_of(tags)
    name = pick_name(tags)
    if pt is None or kind is None or not name:
        return None
    return {
        "name": name,
        "kind": kind,
        "lat": round(pt[0], COORD_DECIMALS),
        "lon": round(pt[1], COORD_DECIMALS),
        "alt": parse_metres(tags.get("ele")),
        "h": parse_metres(tags.get("height")),
    }


def dedupe(items: list) -> list:
    seen = set()
    out = []
    for it in items:
        key = (it["name"], it["kind"], round(it["lat"], DEDUPE_DECIMALS), round(it["lon"], DEDUPE_DECIMALS))
        if key in seen:
            continue
        seen.add(key)
        out.append(it)
    out.sort(key=lambda x: (x["kind"], x["name"], x["lat"], x["lon"]))
    return out


def query_areas(bbox) -> list:
    """Municipal boundaries and mapped settlement outlines, with the full geometry of every member way."""
    # One query for the whole box was refused by two of three public instances (2026-11-16, "at capacity" / 504) and
    # took 387 s on the third; AREA_GRID x AREA_GRID smaller queries are answered. "out geom" returns the whole outline
    # also when only a part of it lies in the box, so an outline on a seam arrives twice and is kept once.
    s, w, n, e = bbox
    found = {}
    for i in range(AREA_GRID):
        for j in range(AREA_GRID):
            s1, n1 = s + (n - s) * i / AREA_GRID, s + (n - s) * (i + 1) / AREA_GRID
            w1, e1 = w + (e - w) * j / AREA_GRID, w + (e - w) * (j + 1) / AREA_GRID
            scope = f"({s1},{w1},{n1},{e1})"
            q = f'[out:json][timeout:{TIMEOUT_S}];(' + "".join(f"{sel}{scope};" for sel in AREA_SELECTORS) + ");out geom;"
            for el in overpass(q):
                found[(el.get("type"), el.get("id"))] = el
    return list(found.values())


def norm_name(name: str) -> str:
    """Name without punctuation and hyphen variants, so that a boundary and its place node compare equal."""
    return " ".join(NAME_PUNCT.sub(" ", name).split())


def stitch(segments: list) -> list:
    """Joins way segments by their end points into closed rings; an unclosed chain (broken relation) is dropped."""
    segs = [list(s) for s in segments if len(s) >= 2]
    rings = []
    while segs:
        ring = segs.pop()
        while ring[0] != ring[-1]:
            for i, s in enumerate(segs):
                if s[0] == ring[-1]:
                    ring.extend(s[1:])
                    break
                if s[-1] == ring[-1]:
                    ring.extend(reversed(s[:-1]))
                    break
            else:
                break
            segs.pop(i)
        if ring[0] == ring[-1] and len(ring) >= 4:
            rings.append(ring)
    return rings


def rings_of(el: dict) -> list:
    """Outer rings of a way or a multipolygon / boundary relation as lists of (lat, lon); holes are ignored."""
    def pts(geometry):
        return [(p["lat"], p["lon"]) for p in geometry or [] if p]
    if el.get("type") == "way":
        return stitch([pts(el.get("geometry"))])
    return stitch([pts(m.get("geometry")) for m in el.get("members", [])
                   if m.get("type") == "way" and m.get("role") in ("outer", "")])


def to_metres(ring: list) -> list:
    """Local equirectangular projection around the first point of the ring, metres (x east, y north)."""
    lat0, lon0 = ring[0]
    k = math.cos(math.radians(lat0)) * KM_PER_DEG_LAT * 1000.0
    return [((lon - lon0) * k, (lat - lat0) * KM_PER_DEG_LAT * 1000.0) for lat, lon in ring]


def simplify(ring: list, tolerance_m: float) -> list:
    """Douglas-Peucker on a closed ring (first point = last point), iterative; keeps the ring closed."""
    xy = to_metres(ring)
    # Split at the point farthest from the start, so that both halves are open chains.
    far = max(range(len(xy)), key=lambda i: (xy[i][0] - xy[0][0]) ** 2 + (xy[i][1] - xy[0][1]) ** 2)
    keep = {0, far, len(xy) - 1}
    stack = [(0, far), (far, len(xy) - 1)]
    while stack:
        a, b = stack.pop()
        if b - a < 2:
            continue
        (ax, ay), (bx, by) = xy[a], xy[b]
        dx, dy = bx - ax, by - ay
        length = math.hypot(dx, dy)
        worst, worst_d = -1, tolerance_m
        for i in range(a + 1, b):
            px, py = xy[i]
            d = abs(dx * (py - ay) - dy * (px - ax)) / length if length > 0 else math.hypot(px - ax, py - ay)
            if d > worst_d:
                worst, worst_d = i, d
        if worst >= 0:
            keep.add(worst)
            stack.append((a, worst))
            stack.append((worst, b))
    out = []
    for i in sorted(keep):
        p = (round(ring[i][0], AREA_COORD_DECIMALS), round(ring[i][1], AREA_COORD_DECIMALS))
        if not out or out[-1] != p:
            out.append(p)
    return out


def ring_area_m2(ring: list) -> float:
    xy = to_metres(ring)
    return abs(sum(x1 * y2 - x2 * y1 for (x1, y1), (x2, y2) in zip(xy, xy[1:]))) / 2.0


def in_rings(rings: list, lat: float, lon: float) -> bool:
    """Even-odd point-in-polygon test over all rings of an outline (the same rule as the app's SettlementArea)."""
    hit = False
    for r in rings:
        for (y1, x1), (y2, x2) in zip(r, r[1:]):
            if (y1 > lat) != (y2 > lat) and lon < x1 + (lat - y1) * (x2 - x1) / (y2 - y1):
                hit = not hit
    return hit


def attach_areas(items: list, elements: list):
    """
    Gives a settlement its real outline. An outline is kept only when a settlement item of the same name lies inside
    it (so regional councils, which have no place of their own, drop out); the largest outline of a
    settlement wins (the municipal boundary over a mapped built-up area). Further items of that name inside the same
    outline are duplicates of the same place (node + boundary centre) and are removed.
    Returns (items without the duplicates, areas sorted by name).
    """
    by_name = {}
    for it in items:
        if it["kind"] == "settlement":
            by_name.setdefault(norm_name(it["name"]), []).append(it)
    found = []
    for el in elements:
        name = pick_name(el.get("tags", {}))
        same = by_name.get(norm_name(name)) if name else None
        if not same:
            continue
        rings = [r for r in (simplify(r, AREA_SIMPLIFY_M) for r in rings_of(el)) if len(r) >= 4]
        if not rings:
            continue
        inside = [it for it in same if in_rings(rings, it["lat"], it["lon"])]
        if inside:
            found.append((sum(ring_area_m2(r) for r in rings), rings, inside))
    found.sort(key=lambda f: -f[0])
    dropped, areas, owners = set(), [], set()
    for _, rings, inside in found:
        alive = [it for it in inside if id(it) not in dropped]
        if not alive:
            continue
        owner = next((it for it in alive if id(it) in owners), None) or \
            next((it for it in alive if it["alt"] is not None), alive[0])
        dropped.update(id(it) for it in alive if it is not owner)
        if id(owner) in owners:
            continue  # a larger outline of this settlement was taken already
        owners.add(id(owner))
        areas.append({"name": owner["name"], "lat": owner["lat"], "lon": owner["lon"],
                      "rings": [[c for p in r for c in p] for r in rings]})
    areas.sort(key=lambda a: (a["name"], a["lat"], a["lon"]))
    return [it for it in items if id(it) not in dropped], areas


def dump(version: int, items: list, areas: list) -> str:
    """The file text: items one value per line (readable diffs), every area on one compact line."""
    head = json.dumps({"version": version, "items": items}, ensure_ascii=False, indent=1)
    lines = ",\n".join("  " + json.dumps(a, ensure_ascii=False, separators=(",", ":")) for a in areas)
    return head[:-2] + ',\n "areas": [\n' + lines + "\n ]\n}\n"


def main() -> None:
    tools = Path(__file__).resolve().parent
    repo = tools.parent
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--buffer-km", type=float, default=NEIGHBOR_BUFFER_KM)
    ap.add_argument("--allow-shrink", action="store_true",
                    help=f"accept a result with fewer than {MIN_KEEP_RATIO:.0%} of the published items")
    args = ap.parse_args()
    if args.buffer_km <= 0:
        sys.exit("--buffer-km must be positive")

    core_items = [it for it in (to_item(el) for el in query_core()) if it]
    core_keys = {(it["name"], it["kind"], it["lat"], it["lon"]) for it in core_items}
    anchors = [it for it in core_items if it["kind"] == "settlement"]
    if not anchors:
        sys.exit("Core query returned no settlements; existing files left unchanged")
    a_lat = np.array([it["lat"] for it in anchors])
    a_lon = np.array([it["lon"] for it in anchors])

    others = [it for it in (to_item(el) for el in query_bbox(SEARCH_BBOX))
              if it and (it["name"], it["kind"], it["lat"], it["lon"]) not in core_keys]
    border = []
    if others:
        d = min_distance_km(np.array([it["lat"] for it in others]), np.array([it["lon"] for it in others]), a_lat, a_lon)
        border = [it for it, dist in zip(others, d) if dist <= args.buffer_km]

    items = dedupe(core_items + border)
    out = repo / "data" / "targets.json"
    prev = json.loads(out.read_text(encoding="utf-8")) if out.is_file() else {"version": 0, "items": []}
    try:
        items, areas = attach_areas(items, query_areas(SEARCH_BBOX))
    except RuntimeError as e:
        # No instance answered the (large) area query: keep the published outlines and the items they belong to.
        print(f"WARNING: settlement areas not rebuilt, the published ones are kept: {e}")
        keys = {(it["name"], it["lat"], it["lon"]) for it in items}
        areas = [a for a in prev.get("areas") or [] if (a["name"], a["lat"], a["lon"]) in keys]
    # A truncated answer of an Overpass instance (accepted as valid JSON) must not replace the published list: the
    # terrain mask is derived from it, so a shrunken list would also delete terrain tiles.
    prev_count = len(prev.get("items") or [])
    if len(items) < MIN_KEEP_RATIO * prev_count and not args.allow_shrink:
        sys.exit(f"Result has {len(items)} items, the published list has {prev_count} (below {MIN_KEEP_RATIO:.0%}); "
                 f"existing files left unchanged. Pass --allow-shrink when the drop is real.")
    # Version increments only when content changes, so the app does not re-download identical data.
    changed = prev.get("items") != items or (prev.get("areas") or []) != areas
    version = prev["version"] + 1 if changed else prev["version"]
    out.write_text(dump(version, items, areas), encoding="utf-8")
    shutil.copyfile(out, repo / "app" / "src" / "main" / "assets" / "targets.json")

    pts = sorted({(round(a, ANCHOR_DECIMALS), round(b, ANCHOR_DECIMALS)) for a, b in zip(a_lat, a_lon)})
    (tools / "anchors.json").write_text(json.dumps({"buffer_km": args.buffer_km, "points": pts}) + "\n")

    counts = {}
    for it in items:
        counts[it["kind"]] = counts.get(it["kind"], 0) + 1
    print(f"targets.json v{version}: {len(items)} items (core {len(core_items)}, border strip {len(border)})")
    print("by kind: " + ", ".join(f"{k}={v}" for k, v in sorted(counts.items())))
    print(f"anchors.json: {len(pts)} points")
    print(f"areas: {len(areas)} settlement outlines, {sum(len(r) // 2 for a in areas for r in a['rings'])} points")


if __name__ == "__main__":
    main()
