package com.truesim.effects.vegetation.scene;

import com.truesim.annotations.AllowAllocation;
import com.truesim.annotations.AllowImpure;
import com.truesim.common.math.Transform;
import com.truesim.effects.vegetation.GrassPatch;
import com.truesim.effects.vegetation.GrassPatches;
import com.truesim.effects.vegetation.VegetationGround;
import com.truesim.effects.vegetation.VegetationSettings;
import com.truesim.engine.settings.EngineSettings;
import com.truesim.scene.assets.AssetCache;
import com.truesim.scene.assets.AssetRef;
import com.truesim.scene.assets.AssetState;
import com.truesim.scene.assets.mesh.MeshData;
import com.truesim.scene.core.ComponentStore;
import com.truesim.scene.core.SceneGraph;
import com.truesim.scene.render.MaterialData;
import com.truesim.scene.render.ModelData;
import com.truesim.scene.render.Renderable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * A scene's vegetation as renderables: each {@link GrassField} as patches of the grass template (a generated mesh asset)
 * with the field's generated grass material, within the settings' radius of the viewer and as they come into it (at
 * most the settings' patch budget a frame, the nearest first is not promised: a ring fills over a few frames), each on
 * the ground at its middle and turned a quarter turn by its cell; each {@link PlantScatter} as copies of its model at
 * {@link PlantPlacement}'s places, once the model and its materials have loaded, their materials made plant
 * materials. The nodes it makes are children of a node of its own per component, on the component node's layers; it
 * removes them when the component goes or changes.
 *
 * <pre>{@code
 * VegetationSync vegetation = new VegetationSync(scene, assets.cache(), settings).ground(terrain);
 * // each frame, after the game's update and before the scene sync:
 * vegetation.update(camera.position(0), camera.position(1), camera.position(2));
 * }</pre>
 *
 * <p>Runs on the scene's thread. A frame where the viewer stays in its patch cell and no component changed does nothing
 * but compare.
 */
public final class VegetationSync implements AutoCloseable {

    private final SceneGraph scene;
    private final AssetCache assets;
    private final EngineSettings settings;
    private VegetationGround ground = VegetationGround.FLAT;
    private final Map<Long, Field> fields = new HashMap<>();
    private final Map<Long, Scatter> scatters = new HashMap<>();
    private long fieldsSeen = -1;
    private long scattersSeen = -1;
    private int patchesAdded;

    /** A field drawn: its component, its container, its patches by cell, its references. */
    private static final class Field {
        GrassField component;
        long container;
        double x;
        double z;
        GrassPatch patch;
        AssetRef<MeshData> mesh;
        AssetRef<MaterialData> material;
        final Map<Long, Long> patches = new HashMap<>();
        long viewerCell = Long.MIN_VALUE;
    }

    /** A scatter placed or waiting for its model: its component, its container, what it holds. */
    private static final class Scatter {
        PlantScatter component;
        long container;
        boolean placed;
        final List<AssetRef<MaterialData>> sources = new ArrayList<>();
        int plants;
    }

    /** The vegetation of {@code scene}, its assets through {@code assets}, tuned by {@code settings}. */
    public VegetationSync(SceneGraph scene, AssetCache assets, EngineSettings settings) {
        this.scene = Objects.requireNonNull(scene, "scene");
        this.assets = Objects.requireNonNull(assets, "assets");
        this.settings = Objects.requireNonNull(settings, "settings");
    }

    /** The ground fields and scatters stand on (patches at their middles' height, plants where they stand). */
    public VegetationSync ground(VegetationGround newGround) {
        this.ground = Objects.requireNonNull(newGround, "ground");
        return this;
    }

    /** The grass patches in the scene now. */
    public int patches() {
        int count = 0;
        for (Field f : fields.values()) {
            count += f.patches.size();
        }
        return count;
    }

    /** The plants placed so far. */
    public int plants() {
        int count = 0;
        for (Scatter s : scatters.values()) {
            count += s.plants;
        }
        return count;
    }

    /**
     * The next frame, the viewer at world ({@code x}, {@code y}, {@code z}): fields follow it, scatters whose models
     * loaded are placed. Changes the scene, so call it before the scene updates.
     */
    @AllowImpure("follows the scene's components and the viewer")
    public void update(double x, double y, double z) {
        patchesAdded = 0;
        ComponentStore<GrassField> grass = scene.components().store(GrassField.TYPE);
        if (grass.version() != fieldsSeen) {
            fieldsSeen = grass.version();
            reconcileFields(grass);
        }
        for (Field field : fields.values()) {
            follow(field, x, z);
        }
        ComponentStore<PlantScatter> plants = scene.components().store(PlantScatter.TYPE);
        if (plants.version() != scattersSeen) {
            scattersSeen = plants.version();
            reconcileScatters(plants);
        }
        for (Scatter scatter : scatters.values()) {
            if (!scatter.placed) {
                tryPlace(scatter);
            }
        }
    }

    // ---------------------------------------------------------------- grass

    @AllowAllocation("when fields come, go or change")
    private void reconcileFields(ComponentStore<GrassField> store) {
        Map<Long, GrassField> now = new HashMap<>();
        for (int i = 0; i < store.size(); i++) {
            now.put(store.node(i), store.value(i));
        }
        for (Iterator<Map.Entry<Long, Field>> it = fields.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<Long, Field> e = it.next();
            GrassField component = now.get(e.getKey());
            if (component == null || !component.equals(e.getValue().component) || moved(e.getKey(), e.getValue())) {
                drop(e.getValue());
                it.remove();
            }
        }
        for (Map.Entry<Long, GrassField> e : now.entrySet()) {
            if (!fields.containsKey(e.getKey())) {
                fields.put(e.getKey(), field(e.getKey(), e.getValue()));
            }
        }
    }

    private boolean moved(long node, Field field) {
        Transform world = scene.world(node);
        return world.translation().x() != field.x || world.translation().z() != field.z;
    }

    @AllowAllocation("when a field comes or changes")
    private Field field(long node, GrassField component) {
        Field f = new Field();
        f.component = component;
        Transform world = scene.world(node);
        f.x = world.translation().x();
        f.z = world.translation().z();
        f.container = scene.create();
        scene.setLayers(f.container, scene.layers(node));
        f.patch = GrassPatch.of(settings);
        f.mesh = GrassPatches.ref(f.patch);
        f.material = VegetationAssets.grassRef(new VegetationAssets.GrassRecipe(component.grass(), f.patch,
            (float) (f.x - component.halfWidth()), (float) (f.z - component.halfDepth()),
            (float) (f.x + component.halfWidth()), (float) (f.z + component.halfDepth()), component.transmission(),
            settings.get(VegetationSettings.RELIEF), settings.get(VegetationSettings.MAX_WIDEN)));
        return f;
    }

    @AllowAllocation("when a field goes")
    private void drop(Field f) {
        if (scene.contains(f.container)) {
            scene.remove(f.container); // its patches with it
        }
        f.patches.clear();
    }

    /** Patches within the radius of the viewer and in the field: those leaving go, those coming are added. */
    @AllowAllocation("when the viewer crosses into another patch cell, or the ring is still filling")
    private void follow(Field f, double x, double z) {
        float size = f.patch.size();
        long cell = key((int) Math.floor(x / size), (int) Math.floor(z / size));
        boolean filling = f.viewerCell == Long.MIN_VALUE;
        if (cell == f.viewerCell && !filling) {
            return;
        }
        double radius = settings.get(VegetationSettings.RADIUS);
        double reach = radius + size * Math.sqrt(0.5);
        GrassField c = f.component;
        int i0 = (int) Math.floor((Math.max(x - reach, f.x - c.halfWidth())) / size);
        int i1 = (int) Math.floor((Math.min(x + reach, f.x + c.halfWidth())) / size);
        int j0 = (int) Math.floor((Math.max(z - reach, f.z - c.halfDepth())) / size);
        int j1 = (int) Math.floor((Math.min(z + reach, f.z + c.halfDepth())) / size);
        // Those leaving
        for (Iterator<Map.Entry<Long, Long>> it = f.patches.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<Long, Long> e = it.next();
            int i = (int) (e.getKey() >> 32);
            int j = (int) (long) e.getKey();
            if (i < i0 || i > i1 || j < j0 || j > j1 || Math.hypot((i + 0.5) * size - x, (j + 0.5) * size - z) > reach) {
                scene.remove(e.getValue());
                it.remove();
            }
        }
        // Those coming, within the budget
        int budget = settings.get(VegetationSettings.PATCH_BUDGET);
        boolean complete = true;
        for (int j = j0; j <= j1; j++) {
            for (int i = i0; i <= i1; i++) {
                double px = (i + 0.5) * size;
                double pz = (j + 0.5) * size;
                long k = key(i, j);
                if (Math.hypot(px - x, pz - z) > reach || f.patches.containsKey(k)) {
                    continue;
                }
                if (patchesAdded >= budget) {
                    complete = false;
                    continue;
                }
                long node = scene.create(f.container);
                double angle = Math.PI / 2 * GrassPatches.quarterTurns(i, j);
                scene.setLocal(node, px, ground.height(px, pz), pz, 0, Math.sin(angle / 2), 0, Math.cos(angle / 2),
                    1, 1, 1);
                scene.components().set(node, Renderable.TYPE, Renderable.of(f.mesh, List.of(f.material))
                    .withCastsShadows(c.castsShadows()));
                f.patches.put(k, node);
                patchesAdded++;
            }
        }
        f.viewerCell = complete ? cell : Long.MIN_VALUE;
    }

    private static long key(int i, int j) {
        return (long) i << 32 | (j & 0xffffffffL);
    }

    // ---------------------------------------------------------------- plants

    @AllowAllocation("when scatters come, go or change")
    private void reconcileScatters(ComponentStore<PlantScatter> store) {
        Map<Long, PlantScatter> now = new HashMap<>();
        for (int i = 0; i < store.size(); i++) {
            now.put(store.node(i), store.value(i));
        }
        for (Iterator<Map.Entry<Long, Scatter>> it = scatters.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<Long, Scatter> e = it.next();
            PlantScatter component = now.get(e.getKey());
            if (component == null || !component.equals(e.getValue().component)) {
                release(e.getValue());
                it.remove();
            }
        }
        for (Map.Entry<Long, PlantScatter> e : now.entrySet()) {
            if (!scatters.containsKey(e.getKey())) {
                Scatter s = new Scatter();
                s.component = e.getValue();
                s.container = scene.create();
                scene.setLayers(s.container, scene.layers(e.getKey()));
                Transform world = scene.world(e.getKey());
                scene.setTranslation(s.container, 0, 0, 0);
                s.placed = false;
                containerOrigin.put(s, new double[] {world.translation().x(), world.translation().z()});
                assets.acquire(s.component.model());
                scatters.put(e.getKey(), s);
            }
        }
    }

    private final Map<Scatter, double[]> containerOrigin = new HashMap<>();

    @AllowAllocation("when a scatter goes")
    private void release(Scatter s) {
        if (scene.contains(s.container)) {
            scene.remove(s.container);
        }
        assets.release(s.component.model());
        for (AssetRef<MaterialData> source : s.sources) {
            assets.release(source);
        }
        containerOrigin.remove(s);
    }

    /** Places a scatter once its model and the model's materials have loaded. */
    @AllowAllocation("placing a scatter, once")
    private void tryPlace(Scatter s) {
        if (assets.state(s.component.model()) == AssetState.FAILED) {
            s.placed = true; // nothing to place: the cache reported it
            return;
        }
        ModelData model = assets.get(s.component.model());
        if (model == null) {
            return;
        }
        if (s.sources.isEmpty()) {
            for (ModelData.Node n : model.nodes()) {
                for (AssetRef<MaterialData> m : n.materials()) {
                    if (!s.sources.contains(m)) {
                        s.sources.add(m);
                        assets.acquire(m);
                    }
                }
            }
        }
        Map<AssetRef<MaterialData>, AssetRef<MaterialData>> plantMaterials = new HashMap<>();
        for (AssetRef<MaterialData> source : s.sources) {
            if (assets.state(source) == AssetState.LOADING) {
                return;
            }
            MaterialData data = assets.get(source);
            plantMaterials.put(source, VegetationAssets.plantRef(new VegetationAssets.PlantRecipe(
                data == null ? MaterialData.DEFAULT : data, s.component.sway(),
                settings.get(VegetationSettings.MAX_WIND))));
        }
        double[] origin = containerOrigin.get(s);
        List<PlantPlacement.Plant> plants = PlantPlacement.place(s.component, origin[0], origin[1], ground);
        for (PlantPlacement.Plant p : plants) {
            long root = scene.create(s.container);
            scene.setLocal(root, p.x(), p.y(), p.z(), 0, Math.sin(p.yaw() / 2), 0, Math.cos(p.yaw() / 2), p.scale(),
                p.scale(), p.scale());
            for (long node : model.spawn(scene, root)) {
                Renderable r = scene.components().get(node, Renderable.TYPE);
                if (r == null) {
                    continue;
                }
                List<AssetRef<MaterialData>> mapped = new ArrayList<>(r.materials().size());
                for (AssetRef<MaterialData> m : r.materials()) {
                    mapped.add(plantMaterials.getOrDefault(m, m));
                }
                Renderable.setMaterials(scene, node, mapped);
            }
        }
        s.plants = plants.size();
        s.placed = true;
    }

    /** Takes the vegetation out of the scene and lets go of what it holds. */
    @Override
    @AllowAllocation("closing")
    public void close() {
        for (Field f : fields.values()) {
            drop(f);
        }
        fields.clear();
        for (Scatter s : scatters.values()) {
            release(s);
        }
        scatters.clear();
    }
}
