# Pitfalls: mistakes made once, so they are not made twice

Each entry is a mistake that really happened in this repository, in the engine's
[docs/pitfalls.md](https://github.com/true-sim/engine/blob/main/docs/pitfalls.md) form: what went wrong, how that was
established, the rule that follows, and where the proof is. Only verified things go here: a measurement, a reproduced
failure, a test that fails without the fix, or a reading of the code that shows the fault.

The engine's entries apply here too, displacement's above all.

## Grass

**A rank order that reversed the dither's bit pairs gathered the kept blades into tufts.** The template's ranks were
first a 16 x 16 ordered dither with its bit pairs reversed (the coarsest pair most significant): the first sixteenth
of the order was then one 4 x 4 block of every tile, so a coarser level drew tufts on a 1.25 m lattice with bare ground
between (a film's still from 12 m up showed it; the order of the standard dither, the finest pair most significant,
spreads each quarter over every 2 x 2 block, and the same still showed even grass).
- Rule: the blades a level keeps must be the ones the dither's first quarters pick; check a coarse level from above.

**The display transform dithers: the same frame twice is not the same image.** Pixel-for-pixel comparisons (a calm
against no wind, culling with grown bounds, occlusion culling off) failed in the sky, where nothing differed, by one
level of 255: `display.dither` (on by default, engine#449) adds noise that changes each frame. With it off the images
matched.
- Rule: tests comparing images turn `DisplayTransform.DITHER` off, and check that the scene settled (the same image two
  frames running) before comparing.

**Clumps by grid cell left bare lanes along the grid.** Each root was drawn towards a jittered point in its own clump
cell (`floor(root / clumpSize)`), so the cells' edges emptied: low flights over the meadow showed straight lanes of
sparse grass converging on the horizon, every 0.55 m (the clump size). The blades' final roots counted across a seam
in 5 cm bands (blade.slang through the probe) held 18 to 25 blades in the bands at the cells' edges against 60 to 90
elsewhere. Clumps as the Voronoi cells of the jittered points (the nearest of the 3 x 3 round the root) counted 51 to
83 everywhere. `BladeTest.clumpsLeaveNoBareLanes` fails with the old pull.
- Rule: anything pulling positions towards points of a grid takes the nearest point, never its own cell's.
