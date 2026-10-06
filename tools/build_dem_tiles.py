#!/usr/bin/env python3
# Copyright (c) 1986-2026 Eldad Galker, eldad@galker.com, https://www.galker.com/software/
# This software is released under the BSD 3-Clause License.
# See the LICENSE.txt file in the project root for full license information.
# Version 2.3
"""Cuts SRTM1 .hgt files into 0.1 x 0.1 degree gzip tiles for the PointAndIdentify app, then rebuilds the manifest.

Conventions
  Input      : SRTM1 HGT, 3601 x 3601 big-endian int16, metres MSL (EGM96), void = -32768
               file name N32E035.hgt = south-west corner at 32N 35E, row 0 = north edge
  Output     : data/tiles/<latIdx>_<lonIdx>.bin.gz, index = floor(deg * 10)
               payload = 361 x 361 int16 little-endian, row 0 = north edge, col 0 = west edge
  Mask       : with tools/anchors.json (from build_targets.py), only tiles whose centre lies within
               buffer_km + TILE_HALF_DIAG_KM of an anchor are processed (core + border strip only)
  Sea        : processed sub-tiles whose samples all lie between -SEA_FLOOR_M and 0 m go to data/tiles/sea.json
               (app uses 0 m). A tile entirely below sea level but deeper (Dead Sea, -430 m) is land and is stored.
               Masked tiles without an HGT file are reported and left unknown, unless --missing-as-sea; even then a
               missing tile that borders land higher than SEA_EDGE_MAX_M stays unknown (terrain cannot just stop)
  Determinism: gzip mtime = 0, so unchanged input yields byte-identical files (no git churn)
"""

import argparse
import gzip
import json
import re
import subprocess
import sys
from pathlib import Path

import numpy as np

# =============================================================
# Parameters
# =============================================================
HGT_SAMPLES = 3601          # samples per edge of SRTM1 (1 arc-second)
SUB_TILES = 10              # sub-tiles per degree per axis -> 0.1 deg tiles
SUB_STEP = 360              # samples between sub-tile edges = (HGT_SAMPLES - 1) / SUB_TILES
TILE_SAMPLES = SUB_STEP + 1 # 361, edges shared with neighbours
VOID = -32768               # SRTM void marker
VOID_FILL_ITER = 200        # max neighbour-averaging passes for void filling
GZIP_LEVEL = 9
BBOX_EPS = 1e-9             # deg, absorbs float error on tile/bbox edges
DEFAULT_BBOX = (28.9, 33.6, 34.0, 36.7)  # deg (min_lat, min_lon, max_lat, max_lon), ESTIMATE: core + buffer
DEFAULT_BUFFER_KM = 50.0    # km, must match AppConfig.FETCH_RADIUS_M / 1000
TILE_HALF_DIAG_KM = 8.0     # km, half-diagonal of a 0.1 deg tile at ~30N is ~7.3 km, rounded up
KM_PER_DEG_LAT = 111.32
SEA_FLOOR_M = 5             # m, open sea reads 0 m in SRTM; a tile reaching deeper than this below zero is land (Dead Sea)
SEA_EDGE_MAX_M = 10         # m, a missing tile cannot be sea when the shared edge of a neighbour is higher than this

HGT_NAME = re.compile(r"^([NS])(\d{2})([EW])(\d{3})\.hgt$", re.IGNORECASE)

# Validation of derived constants
assert SUB_STEP * SUB_TILES == HGT_SAMPLES - 1, "SUB_STEP inconsistent with HGT_SAMPLES"
assert TILE_SAMPLES == 361, "TILE_SAMPLES must match AppConfig.TILE_SAMPLES"


def parse_corner(name: str):
    """Returns (lat, lon) integer degrees of the HGT south-west corner."""
    m = HGT_NAME.match(name)
    if not m:
        return None
    lat = int(m.group(2)) * (1 if m.group(1).upper() == "N" else -1)
    lon = int(m.group(4)) * (1 if m.group(3).upper() == "E" else -1)
    return lat, lon


def read_hgt(path: Path) -> np.ndarray:
    raw = np.fromfile(path, dtype=">i2")
    if raw.size != HGT_SAMPLES * HGT_SAMPLES:
        sys.exit(f"{path.name}: expected SRTM1 ({HGT_SAMPLES}^2 samples), got {raw.size}")
    return raw.reshape(HGT_SAMPLES, HGT_SAMPLES).astype(np.int32)


def fill_voids(grid: np.ndarray) -> np.ndarray:
    """Iteratively replaces voids with the mean of valid 4-neighbours; leftovers become 0."""
    g = grid.astype(np.float64)
    mask = grid == VOID
    if not mask.any():
        return grid
    g[mask] = np.nan
    for _ in range(VOID_FILL_ITER):
        nan = np.isnan(g)
        if not nan.any():
            break
        padded = np.pad(g, 1, mode="edge")
        neigh = np.stack([padded[:-2, 1:-1], padded[2:, 1:-1], padded[1:-1, :-2], padded[1:-1, 2:]])
        count = np.sum(~np.isnan(neigh), axis=0)
        total = np.nansum(neigh, axis=0)
        fillable = nan & (count > 0)
        g[fillable] = total[fillable] / count[fillable]
    g[np.isnan(g)] = 0.0
    return np.rint(g).astype(np.int32)


def tile_intersects(lat_idx: int, lon_idx: int, bbox) -> bool:
    s, w = lat_idx / SUB_TILES, lon_idx / SUB_TILES
    n, e = s + 1.0 / SUB_TILES, w + 1.0 / SUB_TILES
    return not (n <= bbox[0] + BBOX_EPS or s >= bbox[2] - BBOX_EPS
                or e <= bbox[1] + BBOX_EPS or w >= bbox[3] - BBOX_EPS)


def wanted_tiles(bbox, anchors, buffer_km) -> set:
    """Tile keys (lat_idx, lon_idx) in the bbox, optionally restricted to the anchor buffer."""
    i0, i1 = int(np.floor(bbox[0] * SUB_TILES)), int(np.floor(bbox[2] * SUB_TILES))
    j0, j1 = int(np.floor(bbox[1] * SUB_TILES)), int(np.floor(bbox[3] * SUB_TILES))
    keys = [(i, j) for i in range(i0, i1 + 1) for j in range(j0, j1 + 1) if tile_intersects(i, j, bbox)]
    if anchors is None:
        return set(keys)
    a_lat, a_lon = anchors[:, 0], anchors[:, 1]
    limit = buffer_km + TILE_HALF_DIAG_KM
    kept = set()
    for i, j in keys:
        c_lat, c_lon = (i + 0.5) / SUB_TILES, (j + 0.5) / SUB_TILES
        dx = (a_lon - c_lon) * np.cos(np.radians(c_lat)) * KM_PER_DEG_LAT
        dy = (a_lat - c_lat) * KM_PER_DEG_LAT
        if np.sqrt(dx * dx + dy * dy).min() <= limit:
            kept.add((i, j))
    return kept


def is_open_sea(sub) -> bool:
    """True for a tile that is flat open sea: every sample between -SEA_FLOOR_M and 0 m."""
    return bool(sub.max() <= 0 and sub.min() >= -SEA_FLOOR_M)


def edge_maxima(sub) -> dict:
    """Highest sample on each border of a tile (row 0 = north edge, col 0 = west edge)."""
    return {"n": int(sub[0, :].max()), "s": int(sub[-1, :].max()), "w": int(sub[:, 0].max()), "e": int(sub[:, -1].max())}


def missing_tile_may_be_sea(key: tuple, edges: dict) -> bool:
    """A masked tile without input data may be sea only if no stored neighbour rises above SEA_EDGE_MAX_M on the shared edge."""
    lat, lon = key
    shared = (((lat + 1, lon), "s"), ((lat - 1, lon), "n"), ((lat, lon + 1), "w"), ((lat, lon - 1), "e"))
    return all(edges[nb][side] <= SEA_EDGE_MAX_M for nb, side in shared if nb in edges)


def cut(hgt_path: Path, out_dir: Path, wanted: set, sea: set, edges: dict) -> tuple:
    corner = parse_corner(hgt_path.name)
    if corner is None:
        print(f"skip {hgt_path.name}: unrecognised name")
        return 0, 0
    lat0, lon0 = corner
    cells = [(lat0 * SUB_TILES + i, lon0 * SUB_TILES + j, i, j)
             for i in range(SUB_TILES) for j in range(SUB_TILES)
             if (lat0 * SUB_TILES + i, lon0 * SUB_TILES + j) in wanted]
    if not cells:
        return 0, 0
    grid = fill_voids(read_hgt(hgt_path))
    written = sea_count = 0
    for lat_idx, lon_idx, i, j in cells:
        # HGT row 0 is the north edge: sub-tile i (from south) starts at row (9 - i) * 360.
        r0 = (SUB_TILES - 1 - i) * SUB_STEP
        c0 = j * SUB_STEP
        sub = grid[r0:r0 + TILE_SAMPLES, c0:c0 + TILE_SAMPLES]
        if is_open_sea(sub):
            sea.add(f"{lat_idx}_{lon_idx}")
            sea_count += 1
            continue
        edges[(lat_idx, lon_idx)] = edge_maxima(sub)
        payload = np.clip(sub, -32767, 32767).astype("<i2").tobytes()
        with (out_dir / f"{lat_idx}_{lon_idx}.bin.gz").open("wb") as f:
            with gzip.GzipFile(fileobj=f, mode="wb", compresslevel=GZIP_LEVEL, mtime=0) as gz:
                gz.write(payload)
        written += 1
    return written, sea_count


def main() -> None:
    tools = Path(__file__).resolve().parent
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--input", type=Path, required=True, help="directory with SRTM1 .hgt files")
    ap.add_argument("--bbox", type=float, nargs=4, default=DEFAULT_BBOX,
                    metavar=("MIN_LAT", "MIN_LON", "MAX_LAT", "MAX_LON"))
    ap.add_argument("--anchors", type=Path, default=tools / "anchors.json",
                    help="anchor points from build_targets.py; missing file = whole bbox")
    ap.add_argument("--buffer-km", type=float, default=None, help="default: value stored in anchors.json")
    ap.add_argument("--missing-as-sea", action="store_true",
                    help="treat masked tiles without an HGT file as sea (SRTM has no files for open sea)")
    ap.add_argument("--data", type=Path, default=tools.parent / "data")
    args = ap.parse_args()

    bbox = tuple(args.bbox)
    if not (bbox[0] < bbox[2] and bbox[1] < bbox[3]):
        sys.exit("bbox must satisfy MIN < MAX on both axes")

    anchors, buffer_km = None, args.buffer_km or DEFAULT_BUFFER_KM
    if args.anchors.is_file():
        a = json.loads(args.anchors.read_text())
        anchors = np.array(a["points"], dtype=np.float64)
        buffer_km = args.buffer_km or float(a.get("buffer_km", DEFAULT_BUFFER_KM))
        print(f"mask: {len(anchors)} anchors, buffer {buffer_km:g} km")
    else:
        print("mask: none (anchors.json not found), processing whole bbox")
    if buffer_km <= 0:
        sys.exit("buffer must be positive")

    wanted = wanted_tiles(bbox, anchors, buffer_km)
    print(f"tiles in mask: {len(wanted)}")

    out_dir = args.data / "tiles"
    out_dir.mkdir(parents=True, exist_ok=True)
    for old in out_dir.glob("*.bin.gz"):
        old.unlink()  # full rebuild: stale tiles outside the new mask must not survive

    files = sorted(p for p in args.input.iterdir() if p.suffix.lower() == ".hgt")
    if not files:
        sys.exit(f"No .hgt files in {args.input}")

    sea = set()
    edges = {}  # (latIdx, lonIdx) -> border maxima of every stored land tile
    covered_cells = set()
    total_w = 0
    for p in files:
        corner = parse_corner(p.name)
        if corner:
            covered_cells.add(corner)
        w, s = cut(p, out_dir, wanted, sea, edges)
        total_w += w
        if w or s:
            print(f"{p.name}: {w} land tiles, {s} sea tiles")

    # Masked tiles whose 1-degree cell had no HGT file.
    missing = sorted(k for k in wanted if (k[0] // SUB_TILES, k[1] // SUB_TILES) not in covered_cells)
    missing_cells = sorted({(k[0] // SUB_TILES, k[1] // SUB_TILES) for k in missing})
    if missing:
        if args.missing_as_sea:
            accepted = [k for k in missing if missing_tile_may_be_sea(k, edges)]
            rejected = [k for k in missing if k not in set(accepted)]
            sea.update(f"{i}_{j}" for i, j in accepted)
            print(f"{len(accepted)} tiles in {len(missing_cells)} cells without HGT treated as sea")
            if rejected:
                shown = ", ".join(f"{i}_{j}" for i, j in rejected[:20])
                print(f"WARNING: {len(rejected)} missing tiles border land above {SEA_EDGE_MAX_M} m and stay unknown: {shown}")
        else:
            names = ", ".join(f"{'N' if la >= 0 else 'S'}{abs(la):02d}{'E' if lo >= 0 else 'W'}{abs(lo):03d}"
                              for la, lo in missing_cells)
            print(f"WARNING: {len(missing)} masked tiles have no HGT input (cells: {names}); left unknown. "
                  f"Add the files, or pass --missing-as-sea if these cells are open sea.")

    coverage = {"min_lat": bbox[0], "min_lon": bbox[1], "max_lat": bbox[2], "max_lon": bbox[3]}
    (out_dir / "coverage.json").write_text(json.dumps(coverage, indent=1) + "\n")
    (out_dir / "sea.json").write_text(json.dumps(sorted(sea)) + "\n")
    print(f"total: {total_w} land tiles, {len(sea)} sea tiles")

    subprocess.run([sys.executable, str(tools / "build_manifest.py"), "--data", str(args.data)], check=True)


if __name__ == "__main__":
    main()
