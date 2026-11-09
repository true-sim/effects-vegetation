# effects-vegetation

Vegetation for [true-sim](https://github.com/true-sim): meadows of grass made on the GPU, and trees and plants placed
from scene components, all swaying in the scene's wind through the engine's per-material displacement
(true-sim/engine#454). Everything loads through the asset manager and goes through the scene.

| Module | Package | What |
|---|---|---|
| `effects-vegetation` | `com.truesim.effects.vegetation` | the grass template and its material type, the plant type, the wind and field hooks, the ground |
| | `com.truesim.effects.vegetation.scene` | `GrassField` and `PlantScatter` components, `VegetationSync`, `VegetationPlugin` |

```java
app.use(new Rendering(), new Assets(), new VegetationPlugin());
VegetationPlugin vegetation = engine.get(VegetationPlugin.KEY);
vegetation.world(scene, gpuScene, camera).ground(terrain);           // detail by the camera's distance

scene.components().set(meadow, GrassField.TYPE, new GrassField(200, 150,
    MaterialData.builder("vegetationGrass").set("height", 0.5f).build().values(), 0.4f, true));
scene.components().set(forest, PlantScatter.TYPE, new PlantScatter(paths.ref(MODEL, "game://trees/fir.gltf"),
    120, 80, 9, 0.8, 1.3, 30, 900, 1900, 7, MaterialData.builder("vegetationPlant").set("height", 18f).build().values()));
scene.components().set(weather, ShadingHookComponent.TYPE, new ShadingHookComponent("vegetation.wind",
    MaterialData.builder("vegetation.wind").set("speed", 6f).set("direction", 1f, 0f, -0.3f).build().values(), true));
```

## Grass

### No blade is stored

A grass patch is one generated mesh asset, the **template** (`GrassPatches`, `GrassPatch`: 8 m a side, 160 blades a
square metre, 4 segments, 4 detail levels by default), shared by every patch of every field. Its vertices hold no
blade, only a blade's *slot*: every vertex lies at its blade's root on the patch's plane, with where it is along the
blade (u) and across it (v), the blade's **rank** (second UV set) and its number (vertex color bytes; alpha: its
segments). The material type `vegetationGrass` (`grass.slang`) displaces each vertex into its blade in the vertex and
mesh stages of **every pass** (the engine's `RASTER` displacement: color, the visibility buffer's IDs and shading,
depth, every shadow map and tile, motion vectors), by `blade.slang`:

- **Identity from the world:** a blade's seed is its number and its patch's cell (the patch's origin over its size),
  so the same blade is the same in every pass and every frame. Its root is jittered within its slot and drawn towards
  its **clump**'s middle (world cells of `clumpSize`), clumps varying length and facing.
- **Posture:** length, width, facing, rest lean and curvature from the seed. A blade is a curve of **fixed length**:
  each of its segments points along the vertical tilted by an angle growing along the blade (rest lean plus curvature,
  the wind's lean by a cantilever's profile `s (2 - s)`, the tip's flutter by `sÂ²`, snow pressing it), and each vertex
  is the sum of the segments before it. However far the wind bends it, the polyline keeps its length (tested to 2e-4).
- **Normals** are the curve's: across the blade times its tangent at the vertex, turned towards each edge by
  `roundness` so a flat blade reads round.
- **Density:** a blade stands where a hash of its seed is below the material's `density` times the ground map's density
  (and inside the field's bounds, thinning over `edge`). Blades not standing collapse to their root (no area: nothing
  rasterized).
- **Look:** root to tip colors, a share of dry tips, color variation per blade, occlusion darkening the roots; lit
  through from behind by the engine's diffuse transmission feature (`KHR_materials_diffuse_transmission`, which the
  type allows and the materials carry), so thin blades glow against a low sun.

### Wind

The scene's geometry hook block carries the hook `vegetation.wind` (`vegetationWind.slang`: direction, speed, gust
strength, scale and speed, turbulence, flutter, time), the same for every view of the scene. Content sets it as the
scene's values (engine-scene's `ShadingHookComponent`), code with `VegetationHooks.wind`; the sync adds the time.

- **Steady lean** grows with the **speed squared** (`windBend * speedÂ² / stiffness`, each blade's stiffness varied),
  saturating smoothly (`tanh`) at `maxLean`.
- **Gusts:** two octaves of value noise travelling downwind at `gustSpeed` times the speed, scaling the lean.
- **Each blade's sway** at its own frequency and phase (and a little across the wind), and its **tip's flutter**.
- A **calm** (speed 0, or the hook off) is every blade at rest, exactly: tested on the GPU and pixel for pixel.
- **Motion vectors** are the engine's for displacing types: it evaluates this frame's and the frame before's block, so
  the wind's motion is in them (tested: a frame warped by its vectors matches the one before).

### Detail and culling

- **Levels by rank, authored:** the template carries its own cluster hierarchy. Level k keeps the blades of rank below
  4^-k (ranks follow a 16 x 16 ordered dither, so each level's blades spread evenly), each with one segment fewer, in
  groups of cells halving towards full detail (8, 4, 2 m and 1 m cells by default). A group's error is the template's
  error doubling each level, so the **engine's detail rule** picks the level: full detail to half the `lodDistance`
  (8 m at 1080 lines, 60 degrees), a quarter of the blades at it, a sixteenth at twice it, and so on, each culled as a
  cluster.
- **No popping:** the field hook (`vegetation.field`) gives the shaders the viewer (the main camera, so its shadow maps
  and motion vectors displace alike) and its projection: a blade fades out (shrinks) as the share of blades kept at its
  distance, `(first / 2d)Â²`, falls past its rank, so the blades a level drops are gone before the engine leaves it
  (tested: none of them stands at the switch). Kept blades widen (up to `maxWiden`) to keep the cover.
- **Range:** patches exist within `vegetation.grass.radius` of the viewer (the sync adds and removes them as it moves,
  within a budget a frame); blades fade out over the last quarter, leaving the terrain's own color beyond (its material
  should match the sward's far color).
- **Culling** is the engine's: instance and meshlet spheres grown by the material's declared displacement (its blades'
  reach plus the ground's relief within a patch), frustum and two-phase occlusion culling per detail group and meshlet.
  Tested: bounds grown 10 times, or occlusion culling off, draw the same image to the pixel.

### Shadows, snow, ray tracing

- **Shadow maps** draw the blades displaced (cascades, atlas tiles), as every pass does; tested on a patch's shadow.
  Displaced casters cannot be cached: their tiles are redrawn each frame (the engine's rule).
- **Snow:** the field's `snowDepth` (metres, the scene's values for `vegetation.field`) presses blades down and buries
  their lower part; flattened blades face up, so effects-weather's `weather.snow` surface hook lays snow on them like on
  the ground. The snow hook is untouched: the grass type is an ordinary material to it.
- **Ray tracing (documented strategy):** grass is displaced in the raster stages only. The template's undisplaced
  triangles have no area (every vertex at its root), so **rays see no grass**: traced shadows fall on the ground under
  it, global illumination's probes see the ground's color. Blades receive traced shadows as any surface. A coarse
  proxy (the `COMPUTE` displacement mode, copies refitted each frame) costs too much for a meadow
  (engine#454: 4.7 M triangles took 2 ms of deformation and 822 MiB of bottom levels); not done.

## Trees and plants

`PlantScatter` places copies of a **model asset** (a showcase's Poly Haven glTF, a procedural tree) over a rectangle:
one candidate per cell of its spacing, jittered, kept where the ground's density (a hash under it), slope and height
allow (`PlantPlacement`, deterministic by seed). Once the model and its materials have loaded the sync spawns it and
makes its materials **`vegetationPlant`** (`plant.slang`): the model material's values nested as metallic-roughness
(`base.*`) and the scatter's sway values beside them (a generated material asset per source). Masked or two-sided
materials count as leaves (flutter, lit through).

- **Wind:** the trunk leans about its foot with the steady wind (speed squared, gusts at the plant's place) and sways
  slowly at the plant's own phase, keeping its length (the top drops as it leans); branches swing about their pivot,
  keeping their length; leaves flutter along their normals. Every motion grows with the speed: a calm leaves the model
  as it is (tested pixel for pixel). The weights per vertex follow common-procedural's convention for its trees
  (`ProceduralVertex`, `windColors` 1): the vertex color's red is the trunk's weight, green the main branch's, blue a
  byte of the leaf weight (high nibble, 15ths) and the phase (low nibble, 16ths), alpha 1; the second UV set the main
  branch's pivot height and reach. Models without them (Poly Haven's) take `windColors` 0: the
  trunk's weight by height over `height`, squared; a branch's by distance from the trunk over `radius` times height;
  leaves' `leafWeight` (1 for masked or two-sided materials) by height; the phase from the position.
- **Detail:** the model's own cluster hierarchy (glTF meshes import as one, common-procedural's models are one from
  0.4), culled and chosen by the engine; far away, its octahedral impostor (below).
- **Meadow flowers** (common-procedural's flower and grass clumps: gentian, arnica, buttercup, ...) are a scatter per
  species over the meadow, each with its own spacing, so species mix by density beside the GPU grass; a scatter with a
  `radius` keeps its plants in the scene only within it of the viewer, adding and removing them as it moves
  (`vegetation.plants.budget` a frame over every scatter, so a camera cut spreads over frames), as grass patches do. Trees take radius 0.
- **Plants are spawned only once their plant materials have loaded,** and the scatter holds them while it stands:
  plants coming and going round the viewer find them loaded, so the renderer never draws a plant as the default
  material first and then its displacing one (effects-vegetation#2, `PlantChurnTest`; the engine's half of the cause,
  material ids remembering the instances of the block before, is engine#467).
- **Placement input is abstract** (`PlantPlacer`): the scatter's own rules by default; an ecology (common-procedural's
  `procedural-ecology`: suitability, belts, treeline, competition) hands its per-species output over as density fields
  (`PlantPlacer.densityField(species -> field)`, multiplying the ground's) or instance lists
  (`PlantPlacer.instances(species -> plants)`), given to the sync with `VegetationSync.placer`.

## Impostors: the far forest

A cluster hierarchy cannot carry a forest: leaf and needle cards are separate pieces, so simplification stops early (a
common-procedural spruce from its level 1 keeps 15,472 of 23,006 triangles at its coarsest). A scatter with
`impostors` (`PlantScatter.withImpostors(true)`) draws each plant past `vegetation.impostors.distance` as an
**octahedral impostor** (Brucks): one quad.

- **Baked through the engine** (`ImpostorBaker`): once the model, its materials and their textures have loaded, the
  sync draws the model with the engine's own meshlet renderer (its materials as `vegetationImpostorBake`: the source's
  metallic-roughness values, masks and textures, unlit) from 8 x 8 directions over the upper hemisphere
  (hemi-octahedral), orthographic over the model's bounding sphere, 96 pixels square each: albedo, normal and position.
  The atlas (one texture, frames x resolution wide, twice as tall) holds sqrt(albedo) and coverage above, the
  mesh-space normal and the depth along each frame's direction below, empty texels filled from their neighbours. It is
  handed to the cache as a generated texture asset; the quad and the `vegetationImpostor` material are generated
  assets too. At most `vegetation.impostors.budget` bakes a frame, on the scene's thread.
- **Drawn** (`impostor.slang`): the quad turned to the viewer (the field hook's viewer, so shadows and motion vectors
  agree with the image) by its displacement; the four frames nearest the direction it is seen from, blended
  bilinearly, each frame's point moved along the view to the depth the frame holds (so frames line up); lit by the
  normals it holds, with the leaves' diffuse transmission. Holes where the frames' coverage is below half, in every
  pass (`GEOMETRY_KEEP`).
- **No popping:** over `vegetation.impostors.fade` metres past the distance the mesh's points thin out and the
  impostor's fill in by a dither fixed to their texture coordinates (stable as the viewer moves, in every pass), each
  pixel by its own distance. The two overlap: the impostor is whole from the band's middle, the mesh until it, so no
  frame shows less than one of them. The sync keeps both in the scene over the band (plus the plant's size), adding
  the one coming before the other goes, within `vegetation.plants.budget` a frame.
- **Limits:** plants stand upright, turned about y (the frame's axes come from that); the quad faces the camera in
  shadow views too, so a far tree's shadow is its silhouette as the camera sees it; a node's own transform is baked,
  its parents' are not.

`ForestImpostorTest` checks a forest of 400 common-procedural trees: far trees impostors, as much of the forest seen
and as bright as with models, and while the camera moves through the band the forest's coverage never drops from one
frame to the next. `ImpostorTest` checks the atlas (the tree in every frame, its albedo, normals up from above) and
that the impostor covers what the mesh covers, level, from above and below, turned.

## Ground

`VegetationGround` is what fields and scatters stand on: height, density and slope on the CPU, and a `GroundMap` on the
GPU (half floats: the height in two parts, exact to 4,096 m and half a millimetre, and the density) the blades sample
at their roots. `VegetationGround.FLAT` is level ground. Over effects-terrain (in progress beside this) the ground is
its `TerrainSurface`: `(x, z) -> surface.heightAt(x, z, spacing)` with the density from its land cover, and a ground map
uploaded from it (`GroundMap.upload`) round the viewer, or the terrain's own height textures once it publishes them.

## Settings

| Setting | Default | What |
|---|---|---|
| `vegetation.grass.radius` | 110 | metres round the viewer grass reaches |
| `vegetation.grass.patchSize` | 8 | metres a side of a patch |
| `vegetation.grass.density` | 160 | blades a square metre at full detail |
| `vegetation.grass.segments` | 4 | segments of a blade at full detail |
| `vegetation.grass.levels` | 4 | detail levels of the template |
| `vegetation.grass.lodDistance` | 8 | metres at which a quarter of the blades stand (1080p, 60 degrees) |
| `vegetation.grass.templateHeight` | 1 | metres the template's detail groups reach up |
| `vegetation.grass.relief` | 2 | metres the ground may rise or fall within a patch (the bounds) |
| `vegetation.grass.maxWiden` | 3 | how much wider blades get as detail thins them |
| `vegetation.grass.patchBudget` | 512 | patches a frame adds or removes |
| `vegetation.plants.budget` | 1024 | plants a frame adds over every scatter with a radius or impostors |
| `vegetation.impostors.distance` | 140 | metres from the viewer where plants start to become impostors |
| `vegetation.impostors.fade` | 30 | metres over which they fade into them |
| `vegetation.impostors.frames` | 8 | views per side of an impostor's octahedral grid |
| `vegetation.impostors.resolution` | 96 | pixels per side of each view |
| `vegetation.impostors.budget` | 1 | impostors baked a frame |
| `vegetation.wind.maxSpeed` | 30 | the strongest wind plants are bounded for |

## Tests

Every test that renders runs validated (`TestGpu`); the whole suite also ran clean with GPU-assisted validation
(`TRUESIM_GPU_ASSISTED_VALIDATION=true`) on the RX 9070 XT.

- `BladeTest` (the blade function on the GPU, through a probe shader): a calm is the rest posture, whatever the time;
  length kept at 1 to 30 m/s; the steady lean four times at twice the speed, nothing across the wind, a gale never
  below the root; normals unit and across the blade; blades standing as the density says; none outside the field's
  bounds; blades thinning with distance as `(first / 2d)²` and none a level lacks standing at its switch, none at the
  radius; clumps leaving no bare lanes; snow pressing blades down.
- `GrassPatchesTest`: the template's levels (ranks below their shares, segments, errors doubling, meshlet limits), roots
  to the patch's edges, each level spread evenly, one asset per parameters.
- `GrassRenderTest` (the engine drawing it): blades seen grow with the density; meshlets drawn fall with distance;
  bounds grown 10 times and occlusion culling off draw the same image; a calm draws exactly what no wind draws; the
  scene's wind values move the grass and their switch stops it; motion vectors carry a frame onto the next; blades cast
  shadows; trees sway in the wind and rest in a calm, their foot standing; trees with common-procedural's wind
  attributes sway by them and are not tinted by them.
- `VegetationSyncTest`: fields as patches round the viewer within the field, following it, on the ground, within the
  budget, changed and gone with the component; the generated grass and plant materials; scatters placed by their rules
  once their model loads, gone with the component; meadow flowers of two species within their radius of the viewer,
  following it; placement from an ecology's density fields or instances.
- `ValidationTest`: what the components, the template, the ground map, the types' ranges and the plugin refuse.

## Benchmark

`VegetationBenchmark` (`TRUESIM_BENCHMARK=true`; `TRUESIM_PASSES=true` adds the passes), RX 9070 XT, 1920 x 1080,
2026-11-08, without JaCoCo: the eye 1.7 m over level ground looking at the horizon, grass of the default template (160
blades a square metre at full detail, 4 segments, 4 levels, the first coarser level from 8 m) to the radius and the
ground beyond, a gusty 8 m/s wind moving every frame, the sun by 4 cascades of 2048; GPU time of whole frames
(timestamps round batches of 8), the configurations taking turns for 5 rounds after 2 uncounted ones, ranges of the
rounds' medians:

| Configuration | GPU per frame | Patches | Meshlets drawn | Main passes |
|---|---|---|---|---|
| ground only (visibility buffer) | 0.354-0.700 ms | 0 | 94 | shading 0.42 ms |
| meadow to 110 m, calm | 1.416-1.421 ms | 648 | 8,740 | IDs 0.24, shadow atlas 0.52, shading 1.52 ms |
| meadow to 110 m, gusts | 1.520-1.524 ms | 648 | 8,740 | IDs 0.18, shadow atlas 0.41, shading 1.00 ms |
| meadow to 110 m, gusts, forward shading | 0.921-0.928 ms | 648 | 8,740 | shadow atlas 0.58, forward 0.94 ms |
| meadow to 110 m, gusts, 120 spruces | 1.575-1.578 ms | 648 | 9,071 | IDs 0.17, shadow atlas 0.41, shading 0.99 ms |
| meadow to 250 m, gusts | 1.681-1.684 ms | 3,212 | 14,266 | IDs 0.35, shadow atlas 0.68, shading 1.25 ms |
| meadow to 110 m, 320 blades/m², gusts | 1.788-1.800 ms | 648 | 16,835 | IDs 0.48, shadow atlas 0.92, shading 1.66 ms |

(Pass timings come from a separate run with the graph's timings on and do not add up to the frame.) A meadow to the
horizon costs about 1.2 ms over the bare ground on the visibility buffer and 0.6 ms with forward shading. The
visibility buffer pays most in its shading pass, which rebuilds each pixel's displaced triangle and so runs the
blade's displacement for three vertices per pixel; forward shading displaces each vertex once. Shadow maps redraw the
blades' cascade tiles every frame (displaced casters are never cached).

### Forests: models, hierarchies, impostors

`ImpostorBenchmark` (`TRUESIM_BENCHMARK=true`), RX 9070 XT, 1920 x 1080, 2026-11-09: 8,000 common-procedural trees
(spruce, larch, beech, two variants each) over 1.2 km a side, the camera in a 40 m clearing in the middle, 4 m and
40 m up, the sun with 4 cascades; each frame recorded, submitted and waited for (wall time, 120 frames after 30):

| Trees as | Set up | Median (4 m / 40 m up) | Slowest tenth | Meshlets drawn |
|---|---|---|---|---|
| level 0 flat (before common-procedural 0.4) | 3.8 s | 177.1 / 128.4 ms | 183.5 / 182.2 ms | 1,981,674 / 2,252,637 |
| level 1 flat | 12.1 s | 53.2 / 55.3 ms | 63.8 / 76.2 ms | 805,991 / 908,651 |
| level 1 hierarchy (common-procedural's default) | 5.1 s | 44.2 / 45.8 ms | 67.6 / 73.9 ms | 655,180 / 734,076 |
| level 0 hierarchy | 5.3 s | 76.1 / 76.3 ms | 83.0 / 82.9 ms | 1,148,926 / 1,308,077 |
| level 1 hierarchy, impostors past 140 m | 5.0 s (6 bakes) | 13.2 / 12.9 ms | 13.8 / 13.8 ms | 64,032 / 65,240 |

With impostors 647 trees are models and 7,784 impostors (the band holds both). The hierarchy from level 1 beats the
one from level 0 (the near trees' detail costs more than it shows at 1080p), which is why it is common-procedural's
default; impostors take the frame from 44 to 13 ms.

## Films

Rendered by `VegetationFilms` (`TRUESIM_FILMS=true`) at 2560 x 1440 and averaged down to 1280 x 720 (the engine has
no anti-aliasing yet): a low flight through a meadow on rolling hills as the wind rises from a calm to a breeze to
gusts; a valley meadow below a hillside with spruces, in a gusty wind; the detail levels as debug colors (green full
detail, yellow, orange, red the coarsest) walking and rising over the meadow.

## What the engine lacks for vegetation

- **Geometry made in a type's own mesh stage.** Displacement moves a mesh's vertices; it cannot emit more or fewer.
  Blades therefore need the template (a stored mesh of slots, about 3 MB at the defaults), and blades the density or
  detail removes still run their vertices (collapsed to their roots). A hook letting a material type's task/mesh stage emit its own
  vertices and triangles per meshlet (with IDs the visibility buffer and motion vectors resolve) would let a patch
  emit exactly the blades it needs. A `GeometryProvider` plugin cannot do it today: its IDs are not resolvable by
  `VisibilityBufferShading` and the motion vector pass does not know its geometry.
- **An in-place writer for geometry hooks**, as views have (`ShadingHookSource`): `GeometryHooks.set` takes whole
  records, so the wind's time and the viewer cost two record allocations a frame.
- **The surface stage cannot read the scene's geometry hook block or the instance**: per-blade color variation comes
  from the template's attributes, so it repeats per patch (patches are turned by quarter turns against it).
- **A snow depth field on the geometry side.** effects-weather's snow keeps its accumulation per view, for the surface
  hook; grass can only take a uniform `snowDepth` (the field hook's value), not the snow lying where it stands.
- **Distance culling per instance.** The engine draws every instance's coarsest level at any distance; grass ends by
  the sync's ring of patches round the viewer instead.
- **Anti-aliasing.** Thin blades alias and shimmer without temporal anti-aliasing; the films are supersampled.
- **Rays and displaced raster geometry.** `RASTER` displacement is invisible to rays; there is no per-instance switch
  to keep an instance out of the top level, so the template's zero-area triangles sit in a bottom level for nothing.
- **Shadow caches for displaced casters**: grass redraws its cascade tiles every frame (0.4-0.9 ms above).
- **The visibility buffer's shading of displaced types** rebuilds the triangle per pixel, three displacements per
  pixel: on this scene forward shading is 0.6 ms faster.

## Building

Sibling checkouts of true-sim's repositories are used as included builds when present (`../engine`, `../scene`, ...;
`-Ptruesim.<repo>=<path>` or `false` overrides). Until the engine's displacement (engine#454) is released, build against
its branch: `./gradlew build -Ptruesim.engine=<a checkout of material-displacement>` (CI does the same, `ENGINE_REF` in
ci.yml).

MIT licence.
