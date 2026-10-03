#!/usr/bin/env python3
# Copyright (c) 1986-2026 Eldad Galker, eldad@galker.com, https://www.galker.com/software/
# This software is released under the BSD 3-Clause License.
# See the LICENSE.txt file in the project root for full license information.
# Version 1.0
"""Downloads the 1-degree elevation cells needed by build_dem_tiles.py, without any account.

Source
  Terrain Tiles open dataset, "skadi" layout: one gzip HGT per 1-degree cell, SRTM-compatible
  naming (N32E035.hgt). Attribution for the underlying sources is required (see LICENSE.txt).

Scope
  Only cells that contain at least one tile of the build_dem_tiles.py mask
  (anchors.json + buffer) are downloaded.

Conventions
  Output     : <out>/<NAME>.hgt, 3601 x 3601 big-endian int16 (SRTM1 layout)
  Resolution : a 1201 x 1201 (3 arc-second) cell is upsampled to 3601 x 3601 by bilinear
               interpolation and reported, so the tile builder always receives SRTM1 layout
  Missing    : HTTP 404 = no data for that cell (open sea); listed in <out>/missing.json.
               Any other failure after retries stops the script (never silently treated as sea).
"""

import argparse
import gzip
import json
import sys
import time
from pathlib import Path

import numpy as np
import requests

sys.path.insert(0, str(Path(__file__).resolve().parent))
import build_dem_tiles as bdt  # noqa: E402  (shared mask logic and constants)

# =============================================================
# Parameters
# =============================================================
SOURCE_URL = "https://s3.amazonaws.com/elevation-tiles-prod/skadi/{lat_dir}/{name}.hgt.gz"
RETRIES = 3
RETRY_WAIT_S = 10
TIMEOUT_S = 120
SRTM1 = 3601                  # samples per edge, 1 arc-second
SRTM3 = 1201                  # samples per edge, 3 arc-second


def cell_name(lat: int, lon: int) -> str:
    return f"{'N' if lat >= 0 else 'S'}{abs(lat):02d}{'E' if lon >= 0 else 'W'}{abs(lon):03d}"


def needed_cells(bbox, anchors_path: Path, buffer_km):
    anchors, buf = None, buffer_km or bdt.DEFAULT_BUFFER_KM
    if anchors_path.is_file():
        a = json.loads(anchors_path.read_text())
        anchors = np.array(a["points"], dtype=np.float64)
        buf = buffer_km or float(a.get("buffer_km", bdt.DEFAULT_BUFFER_KM))
    tiles = bdt.wanted_tiles(bbox, anchors, buf)
    return sorted({(i // bdt.SUB_TILES, j // bdt.SUB_TILES) for i, j in tiles})


def upsample_srtm3(grid: np.ndarray) -> np.ndarray:
    """Bilinear 1201 -> 3601 (factor 3); voids are kept as voids for build_dem_tiles.py."""
    g = grid.astype(np.float64)
    void = g == bdt.VOID
    g[void] = np.nan
    src = np.linspace(0.0, 1.0, SRTM3)
    dst = np.linspace(0.0, 1.0, SRTM1)
    rows = np.array([np.interp(dst, src, r) for r in g])           # along columns
    full = np.array([np.interp(dst, src, c) for c in rows.T]).T     # along rows
    full[np.isnan(full)] = bdt.VOID
    return np.rint(full).astype(">i2")


def fetch(lat: int, lon: int, out_dir: Path):
    """Returns 'ok', 'upsampled' or 'missing'; raises after repeated non-404 failures."""
    name = cell_name(lat, lon)
    url = SOURCE_URL.format(lat_dir=name[:3], name=name)
    last = None
    for attempt in range(1, RETRIES + 1):
        try:
            r = requests.get(url, timeout=TIMEOUT_S)
            if r.status_code == 404:
                return "missing"
            r.raise_for_status()
            raw = np.frombuffer(gzip.decompress(r.content), dtype=">i2")
            if raw.size == SRTM1 * SRTM1:
                raw.tofile(out_dir / f"{name}.hgt")
                return "ok"
            if raw.size == SRTM3 * SRTM3:
                upsample_srtm3(raw.reshape(SRTM3, SRTM3)).tofile(out_dir / f"{name}.hgt")
                return "upsampled"
            raise ValueError(f"{name}: unexpected size {raw.size} samples")
        except (requests.RequestException, OSError, ValueError) as e:
            last = e
            print(f"{name}: attempt {attempt} failed: {e}")
            time.sleep(RETRY_WAIT_S)
    raise RuntimeError(f"{name}: download failed after {RETRIES} attempts: {last}")


def main() -> None:
    tools = Path(__file__).resolve().parent
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--out", type=Path, required=True)
    ap.add_argument("--bbox", type=float, nargs=4, default=bdt.DEFAULT_BBOX,
                    metavar=("MIN_LAT", "MIN_LON", "MAX_LAT", "MAX_LON"))
    ap.add_argument("--anchors", type=Path, default=tools / "anchors.json")
    ap.add_argument("--buffer-km", type=float, default=None)
    args = ap.parse_args()

    args.out.mkdir(parents=True, exist_ok=True)
    cells = needed_cells(tuple(args.bbox), args.anchors, args.buffer_km)
    print(f"cells to fetch: {len(cells)}")

    result = {"ok": [], "upsampled": [], "missing": []}
    for lat, lon in cells:
        status = fetch(lat, lon, args.out)
        result[status].append(cell_name(lat, lon))
        print(f"{cell_name(lat, lon)}: {status}")

    (args.out / "missing.json").write_text(json.dumps(result, indent=1) + "\n")
    print(f"ok {len(result['ok'])}, upsampled {len(result['upsampled'])}, missing (sea) {len(result['missing'])}")
    if result["upsampled"]:
        print("WARNING: some cells were 3 arc-second and were upsampled; terrain detail there is ~90 m.")
    if not result["ok"] and not result["upsampled"]:
        sys.exit("No elevation cell could be downloaded")


if __name__ == "__main__":
    main()
