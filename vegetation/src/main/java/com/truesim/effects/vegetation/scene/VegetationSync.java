package com.truesim.effects.vegetation.scene;

import com.truesim.common.math.Maths;
import com.truesim.annotations.AllowAllocation;
import com.truesim.annotations.AllowImpure;
import com.truesim.common.math.Transform;
import com.truesim.effects.vegetation.GrassPatch;
import com.truesim.effects.vegetation.GrassPatches;
import com.truesim.effects.vegetation.ImpostorBaker;
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
import com.truesim.scene.render.TextureData;
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
    private PlantPlacer placer = PlantPlacer.RULES;
    private ImpostorBaker baker;
    private int bakesThisFrame;
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

    /** A plant not in the scene, as a model or an impostor. */
    private static final long NONE = Long.MIN_VALUE;

    /** A scatter placed or waiting for its model: its component, its container, what it holds. */
    private static final class Scatter {
        PlantScatter component;
        long container;
        double originX;
        double originZ;
        boolean placed;
        final List<AssetRef<MaterialData>> sources = new ArrayList<>();
        final List<AssetRef<?>> held = new ArrayList<>(); // plant materials, the impostor's: held while it stands
        final List<AssetRef<?>> bakeInputs = new ArrayList<>(); // held while its impostor is baked
        int plants;
        int impostors;
        ModelData model;
        Map<AssetRef<MaterialData>, AssetRef<MaterialData>> materials;
        List<PlantPlacement.Plant> placements;
        long[] meshRoots; // by placement: the root node of its model in the scene, or NONE
        long[] farNodes; // by placement: its impostor's node, or NONE
        long viewerCell = Long.MIN_VALUE;
        // the impostor: its atlas, quad and material, the sphere it covers (mesh space), its fade band (metres)
        AssetRef<TextureData> atlas;
        AssetRef<MeshData> quad;
        AssetRef<MaterialData> pendingMaterial;
        AssetRef<MaterialData> impostorMaterial;
        Renderable impostor;
        int frames;
        boolean leaves;
        double boundX;
        double boundY;
        double boundZ;
        double boundRadius;
        float fadeStart;
        float fadeEnd;
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

    /**
     * Where scatters' plants stand from now on (scatters placed before keep theirs): their own rules by default, or an
     * ecology's per-species density fields or instances.
     */
    public VegetationSync placer(PlantPlacer newPlacer) {
        this.placer = Objects.requireNonNull(newPlacer, "placer");
        return this;
    }

    /**
     * Bakes the impostors of scatters asking for them ({@link PlantScatter#impostors}) with {@code impostorBaker}, from
     * scatters placed after this call; null: none (their plants are their models at any distance).
     */
    public VegetationSync impostors(ImpostorBaker impostorBaker) {
        this.baker = impostorBaker;
        return this;
    }

    /** The plants in the scene as impostors now. */
    public int impostorCount() {
        int count = 0;
        for (Scatter s : scatters.values()) {
            count += s.impostors;
        }
        return count;
    }

    /** How many scatters are not placed yet: their models, materials or impostors still coming. */
    public int waitingScatters() {
        int count = 0;
        for (Scatter s : scatters.values()) {
            count += s.placed ? 0 : 1;
        }
        return count;
    }

    /** The grass patches in the scene now. */
    public int patches() {
        int count = 0;
        for (Field f : fields.values()) {
            count += f.patches.size();
        }
        return count;
    }

    /** The plants in the scene as their models now. */
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
        bakesThisFrame = 0;
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
            if (scatter.placements != null && (scatter.component.radius() > 0 || scatter.impostor != null)) {
                followPlants(scatter, x, y, z);
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
            if (i < i0 || i > i1 || j < j0 || j > j1 || Maths.hypot((i + 0.5) * size - x, (j + 0.5) * size - z) > reach) {
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
                if (Maths.hypot(px - x, pz - z) > reach || f.patches.containsKey(k)) {
                    continue;
                }
                if (patchesAdded >= budget) {
                    complete = false;
                    continue;
                }
                long node = scene.create(f.container);
                double angle = Math.PI / 2 * GrassPatches.quarterTurns(i, j);
                scene.setLocal(node, px, ground.height(px, pz), pz, 0, Maths.sin(angle / 2), 0, Maths.cos(angle / 2),
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
                s.originX = world.translation().x();
                s.originZ = world.translation().z();
                assets.acquire(s.component.model());
                scatters.put(e.getKey(), s);
            }
        }
    }

    @AllowAllocation("when a scatter goes")
    private void release(Scatter s) {
        if (scene.contains(s.container)) {
            scene.remove(s.container);
        }
        assets.release(s.component.model());
        for (AssetRef<MaterialData> source : s.sources) {
            assets.release(source);
        }
        for (AssetRef<?> held : s.held) {
            assets.release(held);
        }
        s.held.clear();
        releaseBakeInputs(s);
    }

    /** Lets go of what a scatter held to bake its impostor: its meshes and its materials' textures. */
    @AllowAllocation("once per scatter")
    private void releaseBakeInputs(Scatter s) {
        for (AssetRef<?> input : s.bakeInputs) {
            assets.release(input);
        }
        s.bakeInputs.clear();
    }

    /** Whether the scatter's far plants are impostors: asked for, and a baker to bake them. */
    private boolean impostors(Scatter s) {
        return s.component.impostors() && baker != null;
    }

    /**
     * Places a scatter once its model, the model's materials and the plant materials made of them have loaded. The
     * plant materials are held while the scatter stands: plants coming and going round the viewer (a radius, or the
     * impostors' distance) find them loaded, so the renderer never draws a plant with the default first and then its
     * displacing material (engine: a material instances drew keeps displacing or not; effects-vegetation#2).
     */
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
        for (AssetRef<MaterialData> source : s.sources) {
            if (assets.state(source) == AssetState.LOADING) {
                return;
            }
        }
        boolean impostors = impostors(s);
        float distance = settings.get(VegetationSettings.IMPOSTOR_DISTANCE);
        float fade = settings.get(VegetationSettings.IMPOSTOR_FADE);
        if (s.materials == null) {
            Map<AssetRef<MaterialData>, AssetRef<MaterialData>> plantMaterials = new HashMap<>();
            for (AssetRef<MaterialData> source : s.sources) {
                MaterialData data = assets.get(source);
                plantMaterials.put(source, VegetationAssets.plantRef(new VegetationAssets.PlantRecipe(
                    data == null ? MaterialData.DEFAULT : data, s.component.sway(),
                    settings.get(VegetationSettings.MAX_WIND), impostors ? distance : 0,
                    impostors ? distance + fade : 0)));
            }
            s.materials = plantMaterials;
            for (AssetRef<MaterialData> plant : plantMaterials.values()) {
                if (!s.held.contains(plant)) {
                    s.held.add(plant);
                    assets.acquire(plant);
                }
            }
        }
        if (impostors && !impostorReady(s, model, distance, fade)) {
            return;
        }
        for (AssetRef<?> held : s.held) {
            if (assets.state(held) == AssetState.LOADING) {
                return;
            }
        }
        s.model = model;
        s.placements = placer.place(s.component, s.originX, s.originZ, ground);
        s.meshRoots = new long[s.placements.size()];
        s.farNodes = new long[s.placements.size()];
        java.util.Arrays.fill(s.meshRoots, NONE);
        java.util.Arrays.fill(s.farNodes, NONE);
        s.fadeStart = impostors ? distance : 0;
        s.fadeEnd = impostors ? distance + fade : 0;
        if (s.component.radius() == 0 && !impostors) {
            for (int i = 0; i < s.placements.size(); i++) {
                spawn(s, i);
            }
        }
        s.placed = true;
    }

    /**
     * Whether the scatter's impostor is ready: its model's meshes and its materials' textures held and loaded, baked
     * (within the frame's budget), its atlas, material and quad loaded. Holds what it needs as it goes.
     */
    @AllowAllocation("baking a scatter's impostor, once")
    private boolean impostorReady(Scatter s, ModelData model, float distance, float fade) {
        if (s.impostorMaterial != null) {
            return true;
        }
        if (s.atlas == null) {
            if (s.bakeInputs.isEmpty()) {
                for (ModelData.Node n : model.nodes()) {
                    if (n.mesh() != null && !s.bakeInputs.contains(n.mesh())) {
                        s.bakeInputs.add(n.mesh());
                    }
                }
                for (AssetRef<MaterialData> source : s.sources) {
                    MaterialData data = assets.get(source);
                    if (data != null) {
                        for (AssetRef<TextureData> texture : data.textures()) {
                            if (!s.bakeInputs.contains(texture)) {
                                s.bakeInputs.add(texture);
                            }
                        }
                    }
                }
                for (AssetRef<?> input : s.bakeInputs) {
                    assets.acquire(input);
                }
            }
            for (AssetRef<?> input : s.bakeInputs) {
                if (assets.state(input) == AssetState.LOADING) {
                    return false;
                }
            }
            if (bakesThisFrame >= settings.get(VegetationSettings.IMPOSTOR_BUDGET)) {
                return false;
            }
            bakesThisFrame++;
            bake(s, model);
            releaseBakeInputs(s);
        }
        if (s.atlas == null) {
            return false; // nothing to bake: no meshes
        }
        if (s.quad == null) {
            s.quad = VegetationAssets.quadRef(new VegetationAssets.QuadRecipe((float) s.boundX, (float) s.boundY,
                (float) s.boundZ, (float) s.boundRadius));
            AssetRef<MaterialData> material = VegetationAssets.impostorRef(new VegetationAssets.ImpostorRecipe(
                s.atlas, s.frames, (float) s.boundX, (float) s.boundY, (float) s.boundZ, (float) s.boundRadius,
                distance, distance + fade, s.leaves));
            s.held.add(s.quad);
            assets.acquire(s.quad);
            s.held.add(material);
            assets.acquire(material);
            s.pendingMaterial = material;
        }
        if (assets.state(s.atlas) == AssetState.LOADING || assets.state(s.quad) == AssetState.LOADING
            || assets.state(s.pendingMaterial) == AssetState.LOADING) {
            return false;
        }
        s.impostorMaterial = s.pendingMaterial;
        s.impostor = Renderable.of(s.quad, List.of(s.impostorMaterial));
        return true;
    }

    /** Bakes the scatter's model into its atlas, an asset the atlases' generator hands over. */
    @AllowAllocation("baking a scatter's impostor, once")
    private void bake(Scatter s, ModelData model) {
        List<ImpostorBaker.Part> parts = new ArrayList<>();
        double minX = Double.MAX_VALUE;
        double minY = Double.MAX_VALUE;
        double minZ = Double.MAX_VALUE;
        double maxX = -Double.MAX_VALUE;
        double maxY = -Double.MAX_VALUE;
        double maxZ = -Double.MAX_VALUE;
        boolean wind = false;
        boolean leaves = false;
        for (ModelData.Node n : model.nodes()) {
            MeshData mesh = n.mesh() == null ? null : assets.get(n.mesh());
            if (mesh == null) {
                continue; // a node's own transform is taken; its parents' are not (plants are a node or a few)
            }
            List<MaterialData> materials = new ArrayList<>();
            for (AssetRef<MaterialData> m : n.materials()) {
                MaterialData data = assets.get(m);
                MaterialData material = data == null ? MaterialData.DEFAULT : data;
                materials.add(material);
                leaves |= material.alphaMode() == MaterialData.AlphaMode.MASK || material.doubleSided();
            }
            parts.add(new ImpostorBaker.Part(mesh, materials, n.tx(), n.ty(), n.tz(), n.rx(), n.ry(), n.rz(), n.rw(),
                n.sx(), n.sy(), n.sz()));
            wind |= mesh.format().has(com.truesim.scene.assets.mesh.VertexAttribute.UV1);
            for (int c = 0; c < 8; c++) {
                double x = mesh.boxMin(0) + ((c & 1) != 0 ? mesh.boxExtent(0) : 0);
                double y = mesh.boxMin(1) + ((c & 2) != 0 ? mesh.boxExtent(1) : 0);
                double z = mesh.boxMin(2) + ((c & 4) != 0 ? mesh.boxExtent(2) : 0);
                minX = Math.min(minX, n.tx() + x * n.sx());
                minY = Math.min(minY, n.ty() + y * n.sy());
                minZ = Math.min(minZ, n.tz() + z * n.sz());
                maxX = Math.max(maxX, n.tx() + x * n.sx());
                maxY = Math.max(maxY, n.ty() + y * n.sy());
                maxZ = Math.max(maxZ, n.tz() + z * n.sz());
            }
        }
        if (parts.isEmpty()) {
            return;
        }
        s.boundX = (minX + maxX) / 2;
        s.boundY = (minY + maxY) / 2;
        s.boundZ = (minZ + maxZ) / 2;
        s.boundRadius = 0.5 * Math.sqrt((maxX - minX) * (maxX - minX) + (maxY - minY) * (maxY - minY)
            + (maxZ - minZ) * (maxZ - minZ));
        s.frames = settings.get(VegetationSettings.IMPOSTOR_FRAMES);
        s.leaves = leaves;
        int resolution = settings.get(VegetationSettings.IMPOSTOR_RESOLUTION);
        boolean windColors = s.component.sway().i32("windColors", 0) != 0 && wind;
        TextureData atlas = baker.bake(parts, ref -> assets.get(ref), s.boundX, s.boundY, s.boundZ, s.boundRadius,
            s.frames, resolution, windColors);
        s.atlas = Atlases.hand(s.component.model(), s.frames, resolution, atlas);
        s.held.add(s.atlas);
        assets.acquire(s.atlas);
    }

    /** Plant {@code i} of {@code s} in the scene as its model, its materials made plants'. */
    @AllowAllocation("placing plants, as they come into the scene")
    private void spawn(Scatter s, int i) {
        PlantPlacement.Plant p = s.placements.get(i);
        long root = scene.create(s.container);
        scene.setLocal(root, p.x(), p.y(), p.z(), 0, Maths.sin(p.yaw() / 2), 0, Maths.cos(p.yaw() / 2), p.scale(),
            p.scale(), p.scale());
        for (long node : s.model.spawn(scene, root)) {
            Renderable r = scene.components().get(node, Renderable.TYPE);
            if (r == null) {
                continue;
            }
            List<AssetRef<MaterialData>> mapped = new ArrayList<>(r.materials().size());
            for (AssetRef<MaterialData> m : r.materials()) {
                mapped.add(s.materials.getOrDefault(m, m));
            }
            Renderable.setMaterials(scene, node, mapped);
        }
        s.meshRoots[i] = root;
        s.plants++;
    }

    /** Plant {@code i} of {@code s} in the scene as its impostor. */
    @AllowAllocation("placing plants, as they come into the scene")
    private void spawnImpostor(Scatter s, int i) {
        PlantPlacement.Plant p = s.placements.get(i);
        long node = scene.create(s.container);
        scene.setLocal(node, p.x(), p.y(), p.z(), 0, Maths.sin(p.yaw() / 2), 0, Maths.cos(p.yaw() / 2), p.scale(),
            p.scale(), p.scale());
        scene.components().set(node, Renderable.TYPE, s.impostor);
        s.farNodes[i] = node;
        s.impostors++;
    }

    /**
     * A scatter with a radius or impostors, the viewer at ({@code x}, {@code y}, {@code z}): its plants within the radius
     * (if it has one) as their model up to the impostors' band's end, as their impostor from its start, each added
     * before the other goes (no frame without the plant), within the budget a frame; the rest follow in later frames.
     */
    @AllowAllocation("when the viewer crosses into another cell of the scatter, or the ring is still filling")
    private void followPlants(Scatter s, double x, double y, double z) {
        double radius = s.component.radius();
        boolean far = s.impostor != null;
        double step = far ? Math.max(1, (s.fadeEnd - s.fadeStart) / 4) : Math.max(1, radius / 8);
        if (radius > 0) {
            step = Math.min(step, Math.max(1, radius / 8));
        }
        long cell = key((int) Math.floor(x / step), (int) Math.floor(z / step));
        if (cell == s.viewerCell) {
            return;
        }
        int budget = settings.get(VegetationSettings.PLANT_BUDGET);
        int added = 0;
        boolean complete = true;
        for (int i = 0; i < s.placements.size(); i++) {
            PlantPlacement.Plant p = s.placements.get(i);
            double flat = Maths.hypot(p.x() - x, p.z() - z);
            double dy = p.y() - y;
            double d = Math.sqrt(flat * flat + dy * dy);
            boolean inRadius = radius == 0 || flat <= radius;
            boolean keepRadius = radius == 0 || flat <= radius + step;
            boolean wantMesh = inRadius;
            boolean keepMesh = keepRadius;
            boolean wantFar = false;
            boolean keepFar = false;
            if (far) {
                double slack = s.boundRadius * p.scale() + step;
                wantMesh = inRadius && d < s.fadeEnd + slack;
                keepMesh = keepRadius && d < s.fadeEnd + slack + step;
                wantFar = inRadius && d > s.fadeStart - slack;
                keepFar = keepRadius && d > s.fadeStart - slack - step;
            }
            long mesh = s.meshRoots[i];
            long imp = s.farNodes[i];
            if (wantMesh && mesh == NONE) {
                if (added < budget) {
                    spawn(s, i);
                    added++;
                } else {
                    complete = false;
                }
            }
            if (wantFar && imp == NONE) {
                if (added < budget) {
                    spawnImpostor(s, i);
                    added++;
                } else {
                    complete = false;
                }
            }
            // the one going only once the other is there (or the plant leaves the radius)
            if (s.meshRoots[i] != NONE && !keepMesh && (s.farNodes[i] != NONE || !keepRadius)) {
                scene.remove(s.meshRoots[i]);
                s.meshRoots[i] = NONE;
                s.plants--;
            }
            if (s.farNodes[i] != NONE && !keepFar && (s.meshRoots[i] != NONE || !keepRadius)) {
                scene.remove(s.farNodes[i]);
                s.farNodes[i] = NONE;
                s.impostors--;
            }
        }
        s.viewerCell = complete ? cell : Long.MIN_VALUE;
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
