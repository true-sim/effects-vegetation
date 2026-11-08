package com.truesim.effects.vegetation.scene;

import static org.assertj.core.api.Assertions.assertThat;

import com.truesim.common.jobs.TaskScheduler;
import com.truesim.common.state.ChangeClock;
import com.truesim.effects.vegetation.GrassPatch;
import com.truesim.effects.vegetation.GrassPatches;
import com.truesim.effects.vegetation.VegetationGround;
import com.truesim.effects.vegetation.scene.PlantPlacement;
import com.truesim.effects.vegetation.VegetationSettings;
import com.truesim.engine.render.materials.DiffuseTransmissionParameters;
import com.truesim.engine.settings.EngineSettings;
import com.truesim.scene.assets.AssetCache;
import com.truesim.scene.assets.AssetRef;
import com.truesim.scene.assets.Generator;
import com.truesim.scene.assets.mesh.MeshData;
import com.truesim.scene.core.SceneGraph;
import com.truesim.scene.render.MaterialData;
import com.truesim.scene.render.ModelData;
import com.truesim.scene.render.RenderAssets;
import com.truesim.scene.render.Renderable;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * The vegetation through the scene: fields as patches of the template round the viewer and inside the field, following
 * it within a budget a frame; scatters placed by their rules once their model loads, their materials made plants';
 * the generated materials' data. On the CPU: what the scene sync then draws is the engine's.
 */
class VegetationSyncTest {

    private final TaskScheduler tasks = new TaskScheduler();
    private final AssetCache assets = new AssetCache(tasks);
    private final SceneGraph scene = new SceneGraph(new ChangeClock());
    private final EngineSettings settings = new EngineSettings();

    @AfterEach
    void stop() {
        assets.close();
        tasks.close();
    }

    private VegetationSync sync() {
        settings.set(VegetationSettings.RADIUS, 20f);
        settings.set(VegetationSettings.PATCH_SIZE, 8f);
        settings.set(VegetationSettings.DENSITY, 20f);
        return new VegetationSync(scene, assets, settings);
    }

    private long field(double x, double z, double halfWidth, double halfDepth) {
        long node = scene.create();
        scene.setTranslation(node, x, 0, z);
        scene.update();
        scene.components().set(node, GrassField.TYPE, new GrassField(halfWidth, halfDepth,
            MaterialData.builder("vegetationGrass").set("height", 0.5f).build().values(), 0.4f, true));
        return node;
    }

    /** The patch cells now in the scene: their middles, by the renderables' nodes. */
    private Set<String> cells() {
        Set<String> cells = new HashSet<>();
        scene.update();
        var store = scene.components().store(Renderable.TYPE);
        for (int i = 0; i < store.size(); i++) {
            var t = scene.world(store.node(i)).translation();
            cells.add((int) Math.floor(t.x() / 8) + "," + (int) Math.floor(t.z() / 8));
        }
        return cells;
    }

    @Test
    void aFieldIsPatchesRoundTheViewerInsideTheField() {
        VegetationSync sync = sync();
        field(0, 0, 100, 100);
        sync.update(0, 1.7, 0);
        int reachable = 0;
        for (int j = -10; j < 10; j++) {
            for (int i = -10; i < 10; i++) {
                reachable += Math.hypot((i + 0.5) * 8, (j + 0.5) * 8) <= 20 + 8 * Math.sqrt(0.5) ? 1 : 0;
            }
        }
        assertThat(sync.patches()).as("every cell within the radius").isEqualTo(reachable);
        assertThat(cells()).hasSize(reachable).contains("0,0", "-1,-1", "2,0").doesNotContain("4,0", "0,-4");
        // Each a renderable of the template and the field's grass material, on the ground at its middle
        var store = scene.components().store(Renderable.TYPE);
        Renderable r = store.value(0);
        GrassPatch patch = GrassPatch.of(settings);
        assertThat(r.mesh()).isEqualTo(GrassPatches.ref(patch));
        assertThat(r.materials()).hasSize(1);
        assertThat(r.castsShadows()).isTrue();
        // The viewer moves 40 m east: the ring follows, west cells gone, east ones come
        sync.update(40, 1.7, 0);
        assertThat(cells()).hasSize(reachable).contains("5,0", "7,-1").doesNotContain("0,0", "-1,-1");
        // At the field's edge (it ends at x 100): nothing past it
        sync.update(100, 1.7, 0);
        assertThat(cells()).allMatch(c -> Integer.parseInt(c.split(",")[0]) <= 12);
        assertThat(sync.patches()).isLessThan(reachable);
    }

    @Test
    void patchesStandOnTheGround() {
        VegetationSync sync = sync().ground((x, z) -> 0.1 * x + 3);
        field(0, 0, 50, 50);
        sync.update(0, 1.7, 0);
        scene.update();
        var store = scene.components().store(Renderable.TYPE);
        for (int i = 0; i < store.size(); i++) {
            var t = scene.world(store.node(i)).translation();
            assertThat(t.y()).isCloseTo(0.1 * t.x() + 3, org.assertj.core.api.Assertions.within(1e-9));
        }
    }

    @Test
    void aFrameAddsNoMoreThanTheBudget() {
        VegetationSync sync = sync();
        settings.set(VegetationSettings.PATCH_BUDGET, 5);
        field(0, 0, 100, 100);
        sync.update(0, 1.7, 0);
        assertThat(sync.patches()).isEqualTo(5);
        sync.update(0, 1.7, 0);
        assertThat(sync.patches()).isEqualTo(10);
        for (int i = 0; i < 10; i++) {
            sync.update(0, 1.7, 0);
        }
        int full = sync.patches();
        sync.update(0, 1.7, 0);
        assertThat(sync.patches()).as("filled, then still").isEqualTo(full).isGreaterThan(20);
    }

    @Test
    void aFieldGoneTakesItsPatches() {
        VegetationSync sync = sync();
        long node = field(0, 0, 100, 100);
        sync.update(0, 1.7, 0);
        assertThat(sync.patches()).isPositive();
        scene.components().remove(node, GrassField.TYPE);
        sync.update(0, 1.7, 0);
        assertThat(sync.patches()).isZero();
        assertThat(cells()).isEmpty();
    }

    @Test
    void theGrassMaterialCompletesTheFieldsValues() {
        GrassPatch patch = new GrassPatch(8, 100, 4, 4, 0.01f, 1);
        MaterialData data = VegetationAssets.grass(new VegetationAssets.GrassRecipe(
            MaterialData.builder("vegetationGrass").set("height", 0.6f).set("tipColor", 0.2f, 0.4f, 0.1f).build()
                .values(), patch, -10, -20, 30, 40, 0.4f, 2, 3));
        assertThat(data.type()).isEqualTo("vegetationGrass");
        assertThat(data.doubleSided()).isTrue();
        assertThat(data.values().f32("height", 0)).isEqualTo(0.6f);
        assertThat(data.values().floats("tipColor")).containsExactly(0.2f, 0.4f, 0.1f);
        assertThat(data.values().f32("patchSize", 0)).isEqualTo(8);
        assertThat(data.values().f32("spacing", 0)).isEqualTo(patch.spacing());
        assertThat(data.values().f32("lodError", 0)).isEqualTo(0.01f);
        assertThat(data.values().floats("boundsMin")).containsExactly(-10f, -20f);
        assertThat(data.values().floats("boundsMax")).containsExactly(30f, 40f);
        assertThat(data.values().f32("displacement", 0)).as("its blades' reach and the relief")
            .isGreaterThan(0.6f * 1.35f + 2);
        assertThat(data.features()).contains(DiffuseTransmissionParameters.NAME);
    }

    // ---------------------------------------------------------------- plants

    /** A generated placeholder tree model: one node of a mesh with a bark and a masked leaves material. */
    record TreeModel(int variant) {
    }

    static final AssetRef<MaterialData> BARK = AssetRef.generated(RenderAssets.MATERIAL,
        (Generator<String, MaterialData>) (name, seed) -> MaterialData.builder("metallicRoughness")
            .set("baseColor", 0.2f, 0.1f, 0.05f, 1f).set("roughness", 0.9f).build(), "bark", 0);
    static final AssetRef<MaterialData> LEAVES = AssetRef.generated(RenderAssets.MATERIAL,
        (Generator<String, MaterialData>) (name, seed) -> MaterialData.builder("metallicRoughness")
            .set("baseColor", 0.1f, 0.3f, 0.05f, 1f).alpha(MaterialData.AlphaMode.MASK, 0.5f).doubleSided(true)
            .build(), "leaves", 0);
    static final AssetRef<MeshData> TRUNK_MESH = AssetRef.generated(RenderAssets.MESH,
        (Generator<String, MeshData>) (name, seed) -> com.truesim.effects.vegetation.TreeMeshes.spruce(10, 2.6),
        "spruce", 0);
    static final Generator<TreeModel, ModelData> TREES = (params, seed) -> new ModelData(List.of(
        ModelData.Node.of("tree", -1, TRUNK_MESH, List.of(BARK, LEAVES))));

    private void await(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + 10_000_000_000L;
        while (!condition.getAsBoolean()) {
            tasks.drainCompleted();
            assertThat(System.nanoTime()).isLessThan(deadline);
            Thread.sleep(1);
        }
    }

    @Test
    void aScatterPlacesItsModelByItsRulesWithPlantMaterials() throws InterruptedException {
        VegetationSync sync = sync().ground((x, z) -> x > 0 ? 50 + 0.0 * z : 0);
        long node = scene.create();
        scene.update();
        AssetRef<ModelData> model = AssetRef.generated(RenderAssets.MODEL, TREES, new TreeModel(1), 0);
        // Over x -40 to 40, but only on ground below 10 m: the west half
        scene.components().set(node, PlantScatter.TYPE, new PlantScatter(model, 40, 20, 5, 0.8, 1.2, 30, -5, 10, 7,
            MaterialData.builder("vegetationPlant").set("height", 10f).build().values()));
        sync.update(0, 2, 0);
        assertThat(sync.plants()).as("not before the model loads").isZero();
        await(() -> {
            sync.update(0, 2, 0);
            return sync.plants() > 0;
        });
        scene.update();
        var store = scene.components().store(Renderable.TYPE);
        assertThat(store.size()).isEqualTo(sync.plants());
        List<AssetRef<MaterialData>> materials = new ArrayList<>();
        for (int i = 0; i < store.size(); i++) {
            var t = scene.world(store.node(i)).translation();
            assertThat(t.x()).as("on the low ground only").isLessThan(0);
            materials.addAll(store.value(i).materials());
        }
        // About one per 25 square metres over the west half's 40 x 40
        assertThat(sync.plants()).isBetween(40, 80);
        AssetRef<MaterialData> bark = store.value(0).materials().get(0);
        AssetRef<MaterialData> leaves = store.value(0).materials().get(1);
        assertThat(bark).isNotEqualTo(BARK);
        assets.acquire(bark);
        assets.acquire(leaves);
        await(() -> assets.get(bark) != null && assets.get(leaves) != null);
        MaterialData barkData = assets.get(bark);
        MaterialData leavesData = assets.get(leaves);
        assertThat(barkData.type()).isEqualTo("vegetationPlant");
        assertThat(barkData.values().floats("base.baseColor")).containsExactly(0.2f, 0.1f, 0.05f, 1f);
        assertThat(barkData.values().f32("height", 0)).isEqualTo(10f);
        assertThat(barkData.values().has("leafWeight")).as("bark is no leaves").isFalse();
        assertThat(barkData.values().f32("displacement", 0)).isPositive();
        assertThat(leavesData.values().f32("leafWeight", 0)).as("masked: leaves").isEqualTo(1f);
        assertThat(leavesData.alphaMode()).isEqualTo(MaterialData.AlphaMode.MASK);
        assertThat(leavesData.features()).contains(DiffuseTransmissionParameters.NAME);
        assets.release(bark);
        assets.release(leaves);
        sync.close();
    }

    @Test
    void meadowFlowersMixBySpeciesWithinTheirRadiusOfTheViewer() throws InterruptedException {
        // Two species over one meadow, each its own density (spacing), in the scene within 15 m of the viewer only
        VegetationSync sync = sync();
        long node = scene.create();
        scene.update();
        AssetRef<ModelData> gentian = AssetRef.generated(RenderAssets.MODEL, TREES, new TreeModel(3), 0);
        AssetRef<ModelData> arnica = AssetRef.generated(RenderAssets.MODEL, TREES, new TreeModel(4), 0);
        var sway = MaterialData.builder("vegetationPlant").set("height", 0.3f).build().values();
        scene.components().set(node, PlantScatter.TYPE, new PlantScatter(gentian, 60, 60, 2, 1, 1, 40, -10, 10, 1,
            sway, 15));
        long other = scene.create();
        scene.update();
        scene.components().set(other, PlantScatter.TYPE, new PlantScatter(arnica, 60, 60, 4, 1, 1, 40, -10, 10, 2,
            sway, 15));
        await(() -> {
            sync.update(0, 1.7, 0);
            return sync.plants() > 0 && scatteredWithin(15, 0, 0);
        });
        for (int i = 0; i < 20; i++) {
            sync.update(0, 1.7, 0); // the budget lets the rings fill over frames
        }
        int near = sync.plants();
        // pi 15^2 / 2^2 + pi 15^2 / 4^2: about 177 + 44
        assertThat(near).isBetween(170, 270);
        assertThat(scatteredWithin(15 + 2, 0, 0)).as("none past the radius").isTrue();
        // The viewer walks 40 m east: the plants follow it
        for (int i = 0; i < 20; i++) {
            sync.update(40, 1.7, 0);
        }
        assertThat(scatteredWithin(15 + 2, 40, 0)).isTrue();
        assertThat(sync.plants()).isBetween(170, 270);
        sync.close();
    }

    /** Whether every plant root (a scatter container's child) lies within {@code r} of (x, z). */
    private boolean scatteredWithin(double r, double x, double z) {
        scene.update();
        var store = scene.components().store(Renderable.TYPE);
        for (int i = 0; i < store.size(); i++) {
            var t = scene.world(store.node(i)).translation();
            if (Math.hypot(t.x() - x, t.z() - z) > r) {
                return false;
            }
        }
        return true;
    }

    @Test
    void placementTakesAnEcologysDensityFieldsOrInstances() {
        PlantScatter gentian = new PlantScatter(AssetRef.generated(RenderAssets.MODEL, TREES, new TreeModel(5), 0),
            50, 50, 4, 1, 1, 20, 0, 100, 3, MaterialData.builder("vegetationPlant").build().values());
        PlantScatter other = new PlantScatter(AssetRef.generated(RenderAssets.MODEL, TREES, new TreeModel(6), 0),
            50, 50, 4, 1, 1, 20, 0, 100, 4, MaterialData.builder("vegetationPlant").build().values());
        VegetationGround level = (x, z) -> 10;
        // A field for one species: it grows only east; the other keeps its rules
        PlantPlacer fields = PlantPlacer.densityField(s -> s == gentian ? (x, z) -> x > 0 ? 1 : 0 : null);
        assertThat(fields.place(gentian, 0, 0, level)).isNotEmpty().allMatch(p -> p.x() > -1);
        assertThat(fields.place(other, 0, 0, level)).hasSize(625);
        // An instance list for one species, as given
        List<PlantPlacement.Plant> given = List.of(new PlantPlacement.Plant(3, 10, 4, 0.5, 1.2));
        PlantPlacer instances = PlantPlacer.instances(s -> s == gentian ? given : null);
        assertThat(instances.place(gentian, 0, 0, level)).isEqualTo(given);
        assertThat(instances.place(other, 0, 0, level)).hasSize(625);
        assertThat(PlantPlacer.RULES.place(other, 0, 0, level)).isEqualTo(PlantPlacement.place(other, 0, 0, level));
    }

    @Test
    void aSyncPlacesAnEcologysInstances() throws InterruptedException {
        VegetationSync sync = sync();
        List<PlantPlacement.Plant> given = List.of(new PlantPlacement.Plant(3, 0, 4, 0.5, 1.2),
            new PlantPlacement.Plant(-7, 0, 1, 1.5, 0.8));
        sync.placer(PlantPlacer.instances(s -> given));
        long node = scene.create();
        scene.update();
        scene.components().set(node, PlantScatter.TYPE, new PlantScatter(AssetRef.generated(RenderAssets.MODEL,
            TREES, new TreeModel(7), 0), 50, 50, 4, 1, 1, 20, 0, 100, 3,
            MaterialData.builder("vegetationPlant").build().values()));
        await(() -> {
            sync.update(0, 2, 0);
            return sync.plants() > 0;
        });
        assertThat(sync.plants()).isEqualTo(2);
        // The scatter goes: its plants with it
        scene.components().remove(node, PlantScatter.TYPE);
        sync.update(0, 2, 0);
        assertThat(sync.plants()).isZero();
        assertThat(cells()).isEmpty();
        sync.close();
    }

    @Test
    void aFieldChangedIsPatchedAgain() {
        VegetationSync sync = sync();
        long node = field(0, 0, 100, 100);
        sync.update(0, 1.7, 0);
        var before = scene.components().store(Renderable.TYPE).value(0).materials();
        scene.components().set(node, GrassField.TYPE, new GrassField(100, 100,
            MaterialData.builder("vegetationGrass").set("height", 0.9f).build().values(), 0, false));
        sync.update(0, 1.7, 0);
        Renderable r = scene.components().store(Renderable.TYPE).value(0);
        assertThat(r.materials()).isNotEqualTo(before);
        assertThat(r.castsShadows()).isFalse();
        assertThat(sync.patches()).isPositive();
        sync.close();
        assertThat(sync.patches()).isZero();
    }

    @Test
    void placementFollowsSlopeHeightAndDensity() {
        PlantScatter scatter = new PlantScatter(AssetRef.generated(RenderAssets.MODEL, TREES, new TreeModel(2), 0),
            50, 50, 4, 1, 1, 20, 0, 100, 3, MaterialData.builder("vegetationPlant").build().values());
        VegetationGround steepEast = (x, z) -> x > 0 ? 2 * x : 0;
        assertThat(PlantPlacement.place(scatter, 0, 0, steepEast)).allMatch(p -> p.x() < 0.5);
        VegetationGround highNorth = (x, z) -> z > 0 ? 200 : 10;
        assertThat(PlantPlacement.place(scatter, 0, 0, highNorth)).allMatch(p -> p.z() <= 0);
        VegetationGround half = new VegetationGround() {
            public double height(double x, double z) {
                return 10;
            }

            public double density(double x, double z) {
                return 0.5;
            }
        };
        int all = PlantPlacement.place(scatter, 0, 0, (x, z) -> 10).size();
        int halved = PlantPlacement.place(scatter, 0, 0, half).size();
        assertThat(all).isEqualTo(625);
        assertThat(halved / (double) all).isBetween(0.42, 0.58);
        assertThat(PlantPlacement.place(scatter, 0, 0, half)).as("the same each time")
            .isEqualTo(PlantPlacement.place(scatter, 0, 0, half));
    }
}
