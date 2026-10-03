#!/usr/bin/env python3
# Copyright (c) 1986-2026 Eldad Galker, eldad@galker.com, https://www.galker.com/software/
# This software is released under the BSD 3-Clause License.
# See the LICENSE.txt file in the project root for full license information.
# Version 1.3
"""Fails (exit 1) when data/manifest.json does not match the files in data/. Used by CI on every push."""

import hashlib
import json
import sys
from pathlib import Path

# =============================================================
# Parameters
# =============================================================
DATA_DIR = Path(__file__).resolve().parent.parent / "data"
TILE_SUFFIX = ".bin.gz"


def sha256_file(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def main() -> int:
    manifest = json.loads((DATA_DIR / "manifest.json").read_text(encoding="utf-8"))
    errors = []

    st = manifest["targets"]
    sp = DATA_DIR / st["path"]
    if not sp.is_file() or sha256_file(sp) != st["sha256"]:
        errors.append("targets.json hash differs from manifest")
    elif json.loads(sp.read_text(encoding="utf-8"))["version"] != st["version"]:
        errors.append("targets.json version differs from manifest")

    on_disk = {p.name[: -len(TILE_SUFFIX)] for p in (DATA_DIR / "tiles").glob(f"*{TILE_SUFFIX}")}
    listed = set(manifest["tiles"].keys())
    for k in sorted(on_disk - listed):
        errors.append(f"tile {k} on disk but not in manifest")
    for k in sorted(listed - on_disk):
        errors.append(f"tile {k} in manifest but missing on disk")
    for k in sorted(on_disk & listed):
        if sha256_file(DATA_DIR / manifest["tiles"][k]["path"]) != manifest["tiles"][k]["sha256"]:
            errors.append(f"tile {k} hash differs from manifest")

    sea_listed = set(manifest.get("sea_tiles", []))
    for k in sorted(sea_listed & listed):
        errors.append(f"tile {k} listed as both land and sea")
    sea_path = DATA_DIR / "tiles" / "sea.json"
    on_disk_sea = set(json.loads(sea_path.read_text())) if sea_path.is_file() else set()
    if on_disk_sea != sea_listed:
        errors.append("tiles/sea.json differs from manifest sea_tiles")

    for e in errors:
        print("ERROR:", e)
    print("manifest OK" if not errors else f"{len(errors)} error(s); run tools/build_manifest.py")
    return 1 if errors else 0


if __name__ == "__main__":
    sys.exit(main())
