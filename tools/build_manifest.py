#!/usr/bin/env python3
# Copyright (c) 1986-2026 Eldad Galker, eldad@galker.com, https://www.galker.com/software/
# This software is released under the BSD 3-Clause License.
# See the LICENSE.txt file in the project root for full license information.
# Version 2.1
"""Builds data/manifest.json from data/tiles/*.bin.gz, coverage.json, sea.json and data/targets.json.

Conventions
  Paths      : relative to the data/ directory (as fetched by the app)
  Hashes     : SHA-256 hex of the exact file bytes in the repository
  Version    : monotonically increasing integer (UTC timestamp, seconds)
"""

import argparse
import hashlib
import json
import sys
import time
from pathlib import Path

# =============================================================
# Parameters
# =============================================================
TILE_DEG = 0.1            # deg, must match AppConfig.TILE_DEG
TILE_SAMPLES = 361        # samples per edge, must match AppConfig.TILE_SAMPLES
TILE_SUFFIX = ".bin.gz"
EMPTY_COVERAGE = {"min_lat": 0.0, "min_lon": 0.0, "max_lat": 0.0, "max_lon": 0.0}


def sha256_file(path: Path) -> str:
    h = hashlib.sha256()
    with path.open("rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def build(data_dir: Path) -> dict:
    tiles_dir = data_dir / "tiles"
    targets_path = data_dir / "targets.json"
    if not targets_path.is_file():
        sys.exit(f"Missing {targets_path}")

    # Coverage is written by build_dem_tiles.py; without it the app treats all terrain as unknown.
    coverage_path = tiles_dir / "coverage.json"
    coverage = json.loads(coverage_path.read_text()) if coverage_path.is_file() else EMPTY_COVERAGE
    if coverage["min_lat"] > coverage["max_lat"] or coverage["min_lon"] > coverage["max_lon"]:
        sys.exit("Invalid coverage box")

    tiles = {}
    for p in sorted(tiles_dir.glob(f"*{TILE_SUFFIX}")):
        key = p.name[: -len(TILE_SUFFIX)]
        tiles[key] = {
            "path": f"tiles/{p.name}",
            "sha256": sha256_file(p),
            "bytes": p.stat().st_size,
        }

    # Explicit sea list: keys absent from both tiles and sea_tiles are unknown to the app.
    sea_path = tiles_dir / "sea.json"
    sea_tiles = json.loads(sea_path.read_text()) if sea_path.is_file() else []
    overlap = set(sea_tiles) & set(tiles)
    if overlap:
        sys.exit(f"Tiles listed both as land and sea: {sorted(overlap)[:5]}")

    targets = json.loads(targets_path.read_text(encoding="utf-8"))
    if not isinstance(targets.get("version"), int) or not targets.get("items"):
        sys.exit("targets.json must contain integer 'version' and non-empty 'items'")

    return {
        "version": int(time.time()),
        "tile_deg": TILE_DEG,
        "tile_samples": TILE_SAMPLES,
        "coverage": coverage,
        "tiles": tiles,
        "sea_tiles": sorted(sea_tiles),
        "targets": {
            "version": targets["version"],
            "path": "targets.json",
            "sha256": sha256_file(targets_path),
        },
    }


def main() -> None:
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--data", type=Path, default=Path(__file__).resolve().parent.parent / "data")
    args = ap.parse_args()
    manifest = build(args.data)
    out = args.data / "manifest.json"
    out.write_text(json.dumps(manifest, ensure_ascii=False, indent=1, sort_keys=True) + "\n", encoding="utf-8")
    total = sum(t["bytes"] for t in manifest["tiles"].values())
    print(f"manifest.json: {len(manifest['tiles'])} land tiles, {len(manifest['sea_tiles'])} sea tiles, "
          f"{total / 1e6:.1f} MB")


if __name__ == "__main__":
    main()
