#!/usr/bin/env python3
# Copyright (c) 1986-2026 Eldad Galker, eldad@galker.com, https://www.galker.com/software/
# This software is released under the BSD 3-Clause License.
# See the LICENSE.txt file in the project root for full license information.
# Version 1.0
"""Self-check of the sea classification rules of build_dem_tiles.py. Run: python tools/test_sea_rules.py"""

import sys
from pathlib import Path

import numpy as np

sys.path.insert(0, str(Path(__file__).resolve().parent))
import build_dem_tiles as b  # noqa: E402

N = b.TILE_SAMPLES


def tile(value):
    return np.full((N, N), value, dtype=np.int16)


def check(name, condition):
    print(("ok   " if condition else "FAIL ") + name)
    if not condition:
        check.failed = True


check.failed = False

check("open sea (all 0 m) is sea", b.is_open_sea(tile(0)))
check("sea with 3 m noise below zero is sea", b.is_open_sea(tile(-3)))
check("Dead Sea (-430 m) is land, not sea", not b.is_open_sea(tile(-430)))
check("tile with land above 0 m is land", not b.is_open_sea(np.where(np.arange(N * N).reshape(N, N) == 5, 12, 0).astype(np.int16)))

grid = tile(0)
grid[0, 3] = 50
edges = b.edge_maxima(grid)
check("edge maxima: north edge sees the 50 m sample", edges["n"] == 50 and edges["s"] == 0 and edges["e"] == 0 and edges["w"] == 0)

# Missing tile (10, 10): its northern neighbour (11, 10) has a high south edge -> cannot be sea.
check("missing tile below a high edge stays unknown", not b.missing_tile_may_be_sea((10, 10), {(11, 10): {"s": 300, "n": 0, "e": 0, "w": 0}}))
check("missing tile next to flat edges may be sea", b.missing_tile_may_be_sea((10, 10), {(11, 10): {"s": 4, "n": 0, "e": 0, "w": 0}}))
check("missing tile with no stored neighbour may be sea", b.missing_tile_may_be_sea((10, 10), {}))
check("east neighbour uses its west edge", not b.missing_tile_may_be_sea((10, 10), {(10, 11): {"w": 80, "e": 0, "n": 0, "s": 0}}))

sys.exit(1 if check.failed else 0)
