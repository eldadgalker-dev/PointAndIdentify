#!/usr/bin/env python3
# Copyright (c) 1986-2026 Eldad Galker, eldad@galker.com, https://www.galker.com/software/
# This software is released under the BSD 3-Clause License.
# See the LICENSE.txt file in the project root for full license information.
# Version 3.1
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
from pathlib import Path

import numpy as np
import requests

# =============================================================
# Parameters
# =============================================================
OVERPASS_ENDPOINT = "https://overpass-api.de/api/interpreter"
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
assert SEARCH_BBOX[0] < SEARCH_BBOX[2] and SEARCH_BBOX[1] < SEARCH_BBOX[3], "SEARCH_BBOX must satisfy MIN < MAX"


def overpass(query: str) -> list:
    r = requests.post(OVERPASS_ENDPOINT, data={"data": query}, timeout=TIMEOUT_S + 60)
    r.raise_for_status()
    return r.json().get("elements", [])


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
    m = re.match(r"^-?\d+(\.\d+)?", text.replace(",", "."))
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


def main() -> None:
    tools = Path(__file__).resolve().parent
    repo = tools.parent
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--buffer-km", type=float, default=NEIGHBOR_BUFFER_KM)
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
    # Version increments only when content changes, so the app does not re-download identical data.
    version = prev["version"] + 1 if prev.get("items") != items else prev["version"]
    out.write_text(json.dumps({"version": version, "items": items}, ensure_ascii=False, indent=1) + "\n",
                   encoding="utf-8")
    shutil.copyfile(out, repo / "app" / "src" / "main" / "assets" / "targets.json")

    pts = sorted({(round(a, ANCHOR_DECIMALS), round(b, ANCHOR_DECIMALS)) for a, b in zip(a_lat, a_lon)})
    (tools / "anchors.json").write_text(json.dumps({"buffer_km": args.buffer_km, "points": pts}) + "\n")

    counts = {}
    for it in items:
        counts[it["kind"]] = counts.get(it["kind"], 0) + 1
    print(f"targets.json v{version}: {len(items)} items (core {len(core_items)}, border strip {len(border)})")
    print("by kind: " + ", ".join(f"{k}={v}" for k, v in sorted(counts.items())))
    print(f"anchors.json: {len(pts)} points")


if __name__ == "__main__":
    main()
