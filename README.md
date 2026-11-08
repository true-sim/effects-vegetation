# effects-vegetation

Vegetation for [true-sim](https://github.com/true-sim): meadows of grass generated on the GPU, and trees and plants
placed from scene components, all swaying in the scene's wind through the engine's per-material displacement
(true-sim/engine#454). Everything loads through the asset manager and goes through the scene.

| Module | Package | What |
|---|---|---|
| `effects-vegetation` | `com.truesim.effects.vegetation` | grass fields, plant scatters, the vegetation material types and the wind |

## Building

Sibling checkouts of true-sim's repositories are used as included builds when present (`../engine`, `../scene`, ...;
`-Ptruesim.<repo>=<path>` or `false` overrides). Until the engine's displacement is released, build against its branch:
`./gradlew build -Ptruesim.engine=<a checkout of material-displacement>` (CI does the same, `ENGINE_REF` in ci.yml).

MIT licence.
