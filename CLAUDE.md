# Working on effects-vegetation

- Read `docs/pitfalls.md` here and the engine's (true-sim/engine `docs/pitfalls.md`) before changing per-frame code,
  shaders or benchmarks: they list mistakes made once, with their evidence and the rule that follows.
- Add to `docs/pitfalls.md` when a bug, a regression or a wrong assumption costs real time. Only verified things: a
  measurement, a reproduced failure, a test that fails without the fix, or code that shows the fault, with a link to
  the proof. A cause not found is written as an observation, never as a guess.
- Everything comes through the asset manager and the scene: meshes as generated assets, placement from scene
  components, wind and the field's values as the scene's geometry hook values. Every cap, budget, distance, rate and
  threshold is a setting (`VegetationSettings`) or a material or hook field, never a constant.
- Per-frame paths allocate nothing (`truesim.allocation-free`). Shaders live under `src/main/slang/vegetation/`.
- Visual changes ship a short film framed on the effect (1280 x 720, under 28 MB), watched before the PR.
- No libraries beyond the JDK, true-sim's own and LWJGL. MIT.
