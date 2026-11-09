package com.truesim.effects.vegetation;

import static org.lwjgl.vulkan.VK10.VK_FORMAT_D32_SFLOAT;
import static org.lwjgl.vulkan.VK10.VK_FORMAT_R16G16B16A16_SFLOAT;

import com.truesim.common.jobs.TaskScheduler;
import com.truesim.common.math.Vec3;
import com.truesim.common.procedural.assets.GenerationCache;
import com.truesim.common.procedural.assets.ModelDetail;
import com.truesim.common.procedural.assets.ProceduralAssets;
import com.truesim.common.procedural.assets.Recipe;
import com.truesim.common.procedural.tree.Foliage;
import com.truesim.common.procedural.tree.Season;
import com.truesim.common.procedural.tree.TreeSpecies;
import com.truesim.common.state.ChangeClock;
import com.truesim.effects.vegetation.materials.VegetationImpostor;
import com.truesim.effects.vegetation.materials.VegetationPlant;
import com.truesim.effects.vegetation.materials.VegetationWindParameters;
import com.truesim.effects.vegetation.scene.PlantPlacement;
import com.truesim.effects.vegetation.scene.PlantPlacer;
import com.truesim.effects.vegetation.scene.PlantScatter;
import com.truesim.effects.vegetation.scene.VegetationAssets;
import com.truesim.effects.vegetation.scene.VegetationSync;
import com.truesim.engine.graph.FrameGraph;
import com.truesim.engine.graph.Image;
import com.truesim.engine.graph.Key;
import com.truesim.engine.graph.ViewPort;
import com.truesim.engine.graph.testing.TestTarget;
import com.truesim.engine.render.Camera;
import com.truesim.engine.render.ClusteredLights;
import com.truesim.engine.render.DisplayTransform;
import com.truesim.engine.render.Environment;
import com.truesim.engine.render.EnvironmentLighting;
import com.truesim.engine.render.GeometryPool;
import com.truesim.engine.render.GpuScene;
import com.truesim.engine.render.Keys;
import com.truesim.engine.render.Lights;
import com.truesim.engine.render.Materials;
import com.truesim.engine.render.MeshletRenderer;
import com.truesim.engine.render.SceneGeometry;
import com.truesim.engine.render.SceneView;
import com.truesim.engine.render.SkyBackground;
import com.truesim.engine.render.TextureFormat;
import com.truesim.engine.render.Textures;
import com.truesim.engine.render.ViewOptions;
import com.truesim.engine.render.VisibilityBufferShading;
import com.truesim.engine.render.shadows.Shadows;
import com.truesim.engine.scene.SceneResources;
import com.truesim.engine.scene.SceneSync;
import com.truesim.engine.settings.EngineSettings;
import com.truesim.engine.vulkan.DisplayEncoding;
import com.truesim.engine.vulkan.Format;
import com.truesim.engine.vulkan.VulkanDevice;
import com.truesim.scene.assets.AssetCache;
import com.truesim.scene.assets.AssetRef;
import com.truesim.scene.assets.Generator;
import com.truesim.scene.assets.PathAssets;
import com.truesim.scene.assets.mesh.MeshData;
import com.truesim.scene.core.SceneGraph;
import com.truesim.scene.core.ShadowSpec;
import com.truesim.scene.render.MaterialData;
import com.truesim.scene.render.ModelData;
import com.truesim.scene.render.RenderAssets;
import com.truesim.scene.render.Renderable;
import com.truesim.scene.render.TextureData;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Line2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A forest of common-procedural's trees through the scene and the engine, for the impostors' tests, films and
 * benchmark: the ground and the trees as scene components (a {@link PlantScatter} per species and variant, its trees
 * given as instances), drawn by the engine's scene sync, the vegetation's sync baking impostors with an
 * {@link ImpostorBaker}, under a sun with cascaded shadows in a sky, shaded by the visibility buffer.
 */
final class Forest implements AutoCloseable {

    private static final Key<Image> SHOWN = Key.image("test.forest.shown");
    static final List<TreeSpecies> SPECIES = List.of(TreeSpecies.SPRUCE, TreeSpecies.LARCH, TreeSpecies.BEECH);

    final VulkanDevice device;
    final int width;
    final int height;
    final TaskScheduler tasks = new TaskScheduler();
    final AssetCache cache = new AssetCache(tasks);
    final PathAssets paths = new PathAssets(cache);
    final ProceduralAssets procedural;
    final GeometryPool pool;
    final Materials materials;
    final Textures textures;
    final SceneResources resources;
    final GpuScene gpu;
    final SceneGraph scene = new SceneGraph(new ChangeClock());
    final SceneSync sceneSync;
    final MeshletRenderer renderer;
    final Lights lights;
    final FrameGraph graph;
    final TestTarget target;
    final Camera camera = new Camera();
    final EngineSettings settings;
    final VegetationHooks hooks;
    final VegetationSync vegetation;
    final ImpostorBaker baker;
    final SceneView view;
    final Shadows shadows;
    private final Map<PlantScatter, List<PlantPlacement.Plant>> placements = new IdentityHashMap<>();
    private final Map<String, MaterialData> materialData = new ConcurrentHashMap<>();
    int trees;

    /**
     * A forest of {@code count} trees over a square of {@code size} metres round the origin (the middle
     * {@code clearing} metres left open), {@code width} x {@code height} pixels; impostors past the settings' distance
     * where {@code impostors}; the models' detail as {@code detail} says; generated meshes cached in {@code cacheDir}.
     */
    Forest(VulkanDevice device, int width, int height, EngineSettings settings, Path cacheDir, int count,
        double size, double clearing, boolean impostors, ModelDetail detail, long seed) {
        this.device = device;
        this.width = width;
        this.height = height;
        this.settings = settings;
        procedural = new ProceduralAssets(paths, new GenerationCache(cacheDir)).install().detail(detail);
        paths.mounts().add("forest", path -> materialData.containsKey(path) ? new ByteArrayInputStream(new byte[0])
            : null);
        paths.importers().add(RenderAssets.MATERIAL, "mat", input -> materialData.get(input.path().path()));
        pool = new GeometryPool(device, GeometryPool.Capacity.of(1 << 24, 1 << 18, 1 << 12));
        materials = new Materials(device, 256);
        materials.register(VegetationPlant.TYPE);
        materials.register(VegetationImpostor.TYPE);
        textures = new Textures(device);
        resources = new SceneResources(cache, pool, materials, textures).loading(SceneResources.Loading.WHOLE);
        gpu = new GpuScene(device, pool, 1 << 17);
        gpu.setMaterials(materials);
        sceneSync = new SceneSync(resources, scene, gpu);
        renderer = new MeshletRenderer(device, gpu, VK_FORMAT_R16G16B16A16_SFLOAT, VK_FORMAT_D32_SFLOAT);
        lights = new Lights(device, 4, 4);
        float[] sun = {-0.45f, 0.55f, -0.7f};
        double l = Math.sqrt(sun[0] * sun[0] + sun[1] * sun[1] + sun[2] * sun[2]);
        for (int i = 0; i < 3; i++) {
            sun[i] /= (float) l;
        }
        lights.directional(0, -sun[0], -sun[1], -sun[2], 4.2f, 3.9f, 3.4f);
        lights.shadow(0, ShadowSpec.cascades(4, 2048, 600));
        lights.setCount(1);
        graph = new FrameGraph(device, 2);
        target = new TestTarget(device, width, height, Format.R8G8B8A8_SRGB);
        hooks = new VegetationHooks(device, gpu).viewer(camera, 1);
        baker = new ImpostorBaker(device);
        vegetation = new VegetationSync(scene, cache, settings).placer(PlantPlacer.instances(placements::get))
            .impostors(impostors ? baker : null);
        // the view
        int sky = textures.add(TextureFormat.RGBA16F, 1024, 512, Textures.GENERATE, Meadow.skyTexels(1024, 512, sun));
        Environment environment = Environment.panorama("forest sky", sky, 1024);
        SceneGeometry geometry = SceneGeometry.meshlets(renderer);
        ViewPort port = graph.addView("main").size(width, height);
        view = geometry.createView(port, camera, ViewOptions.of(1 << 22, 1 << 23).withOcclusion(true));
        view.set(1, MeshletRenderer.MODE_LIT);
        shadows = new Shadows(geometry, lights);
        graph.settings().set(DisplayTransform.CURVE, "agx");
        graph.settings().set(DisplayTransform.DITHER, false);
        port.add(new VisibilityBufferShading(geometry, view, VK_FORMAT_R16G16B16A16_SFLOAT, VK_FORMAT_D32_SFLOAT))
            .add(new EnvironmentLighting(environment, view)).add(new SkyBackground(environment, view))
            .add(new ClusteredLights(lights, view)).add(shadows.view(view))
            .add(new DisplayTransform().transform(Keys.SCENE_COLOR, SHOWN, DisplayEncoding.SDR_SRGB))
            .output(SHOWN, target.output());
        hooks.wind(VegetationWindParameters.DEFAULTS.withSpeed(3).withDirection(new Vec3(1, 0, 0.4)));
        ground(size);
        plant(count, size, clearing, seed);
    }

    /** Level ground, {@code size} metres a side. */
    private void ground(double size) {
        AssetRef<MeshData> mesh = AssetRef.generated(RenderAssets.MESH, (Generator<Double, MeshData>) (s, x) ->
            Meadow.groundMesh(Meadow::flat, s, 64), size + 4000, 0);
        AssetRef<MaterialData> soil = AssetRef.generated(RenderAssets.MATERIAL, (Generator<String, MaterialData>)
            (s, x) -> MaterialData.builder(MaterialData.METALLIC_ROUGHNESS).set("baseColor", 0.05f, 0.09f, 0.025f, 1f)
                .set("roughness", 0.95f).set("metallic", 0f).build(), "soil", 0);
        long node = scene.create();
        scene.components().set(node, Renderable.TYPE, Renderable.of(mesh, List.of(soil)));
    }

    /** {@code count} trees: species and variants by chance, at least 4 m apart, scaled 0.8 to 1.2. */
    private void plant(int count, double size, double clearing, long seed) {
        Random random = new Random(seed);
        int variants = 2;
        Map<Integer, List<PlantPlacement.Plant>> byKind = new HashMap<>();
        double cell = 4;
        int cells = (int) (size / cell);
        boolean[] taken = new boolean[cells * cells];
        int placed = 0;
        for (int attempt = 0; attempt < count * 20 && placed < count; attempt++) {
            int i = random.nextInt(cells);
            int j = random.nextInt(cells);
            double x = (i + 0.2 + 0.6 * random.nextDouble()) * cell - size / 2;
            double z = (j + 0.2 + 0.6 * random.nextDouble()) * cell - size / 2;
            if (taken[j * cells + i] || Math.max(Math.abs(x), Math.abs(z)) < clearing / 2) {
                continue;
            }
            taken[j * cells + i] = true;
            int kind = random.nextInt(SPECIES.size() * variants);
            byKind.computeIfAbsent(kind, k -> new ArrayList<>()).add(new PlantPlacement.Plant(x, 0, z,
                random.nextDouble() * Math.PI * 2, 0.8 + 0.4 * random.nextDouble()));
            placed++;
        }
        trees = placed;
        for (Map.Entry<Integer, List<PlantPlacement.Plant>> e : byKind.entrySet()) {
            TreeSpecies species = SPECIES.get(e.getKey() / variants);
            Recipe recipe = recipe(species, 100 + e.getKey());
            AssetRef<ModelData> model = procedural.model(recipe);
            var sway = MaterialData.builder(VegetationAssets.PLANT_TYPE).set("windColors", 1).set("height", 20f)
                .set("radius", 4f).build().values();
            PlantScatter scatter = new PlantScatter(model, size / 2, size / 2, 4, 1, 1, 90, -1e4, 1e4, e.getKey(),
                sway, 0, true);
            placements.put(scatter, e.getValue());
            long node = scene.create();
            scene.components().set(node, PlantScatter.TYPE, scatter);
        }
    }

    /** A summer recipe of {@code species} with this forest's materials. */
    private Recipe recipe(TreeSpecies species, long seed) {
        String name = species.name();
        materialData.computeIfAbsent("bark/" + name + ".mat", k -> MaterialData.builder(MaterialData.METALLIC_ROUGHNESS)
            .set("baseColor", 0.16f, 0.12f, 0.09f, 1f).set("roughness", 0.95f).set("metallic", 0f).build());
        materialData.computeIfAbsent("leaves/" + name + ".mat", k -> MaterialData.builder(
            MaterialData.METALLIC_ROUGHNESS).set("baseColor", 1f, 1f, 1f, 1f)
            .texture("baseColorTexture", AssetRef.generated(RenderAssets.TEXTURE, ATLASES, name, 1))
            .set("roughness", 0.6f).set("metallic", 0f).alpha(MaterialData.AlphaMode.MASK, 0.5f).doubleSided(true)
            .build());
        materialData.computeIfAbsent("parts/" + name + ".mat", k -> MaterialData.builder(
            MaterialData.METALLIC_ROUGHNESS).set("baseColor", 0.3f, 0.18f, 0.1f, 1f).set("roughness", 0.7f)
            .set("metallic", 0f).build());
        return new Recipe("tree", name, seed, Season.SUMMER, 0, List.of("forest://bark/" + name + ".mat",
            "forest://leaves/" + name + ".mat", "forest://parts/" + name + ".mat"));
    }

    /** Leaf and needle atlases, by species name: each season's tile drawn alike. */
    static final Generator<String, TextureData> ATLASES = (name, seed) -> atlas(name);

    private static TextureData atlas(String name) {
        TreeSpecies species = SPECIES.stream().filter(s -> s.name().equals(name)).findFirst().orElseThrow();
        Foliage foliage = species.foliage();
        int tile = 128;
        int w = foliage.atlasColumns() * tile;
        int h = foliage.atlasRows() * tile;
        BufferedImage image = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = image.createGraphics();
        int base = switch (name) {
            case "spruce" -> 0x22421b;
            case "larch" -> 0x5f8f35;
            default -> 0x4d7a2a;
        };
        Random random = new Random(name.hashCode());
        for (int t = 0; t < foliage.atlasColumns() * foliage.atlasRows(); t++) {
            int ox = (t % foliage.atlasColumns()) * tile;
            int oy = (t / foliage.atlasColumns()) * tile;
            boolean leaves = foliage.kind() == Foliage.Kind.LEAF_CLUSTER;
            for (int k = 0; k < (leaves ? 40 : 160); k++) {
                double f = 0.75 + 0.5 * random.nextDouble();
                g.setColor(new Color((int) Math.min(255, (base >> 16 & 255) * f),
                    (int) Math.min(255, (base >> 8 & 255) * f), (int) Math.min(255, (base & 255) * f)));
                double y = oy + 4 + (tile - 12) * random.nextDouble();
                if (leaves) {
                    double x = ox + tile / 2.0 + (random.nextDouble() - 0.5) * tile * 0.6;
                    g.fill(new Ellipse2D.Double(x - 10, y - 15, 20, 30));
                } else {
                    // needles to both sides of a shoot down the tile's middle
                    double side = random.nextBoolean() ? 1 : -1;
                    double length = tile * (0.25 + 0.2 * random.nextDouble());
                    g.setStroke(new BasicStroke(4));
                    g.draw(new Line2D.Double(ox + tile / 2.0, y, ox + tile / 2.0 + side * length, y + 0.4 * length));
                }
            }
        }
        g.dispose();
        MemorySegment texels = Arena.ofAuto().allocate(4L * w * h);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int p = image.getRGB(x, y);
                long o = 4L * (y * (long) w + x);
                texels.set(ValueLayout.JAVA_BYTE, o, (byte) (p >> 16));
                texels.set(ValueLayout.JAVA_BYTE, o + 1, (byte) (p >> 8));
                texels.set(ValueLayout.JAVA_BYTE, o + 2, (byte) p);
                texels.set(ValueLayout.JAVA_BYTE, o + 3, (byte) ((p >>> 24) >= 128 ? 255 : 0));
            }
        }
        return new TextureData(w, h, TextureData.ColorSpace.SRGB, texels);
    }

    /** The camera at ({@code x}, {@code y}, {@code z}), turned {@code yaw} degrees left of -z, {@code pitch} up. */
    Forest look(double x, double y, double z, double yaw, double pitch, double fovDegrees) {
        double a = Math.toRadians(yaw);
        double b = Math.toRadians(pitch);
        camera.position(x, y, z).set(-Math.sin(a) * Math.cos(b), Math.sin(b), -Math.cos(a) * Math.cos(b), 0, 1, 0,
            Math.toRadians(fovDegrees), width, height, 0.1);
        return this;
    }

    /** The scene brought up to date for the camera: loads taken in, the vegetation following it. */
    void update(double seconds) {
        tasks.drainCompleted();
        vegetation.update(camera.position(0), camera.position(1), camera.position(2));
        scene.update();
        sceneSync.update();
        hooks.update(seconds, settings);
    }

    /** Updates until every scatter is placed and nothing waited for loads, then until the plants settle. */
    void settle(int frames) throws InterruptedException {
        long deadline = System.nanoTime() + 600_000_000_000L;
        update(0); // the scatters seen
        while (vegetation.waitingScatters() > 0 || !resources.loaded() || cache.loadingCount() > 0) {
            update(0);
            if (System.nanoTime() > deadline) {
                throw new IllegalStateException("The forest did not load: " + vegetation.waitingScatters()
                    + " scatters waiting");
            }
            Thread.sleep(2);
        }
        for (int i = 0; i < frames; i++) {
            update(0);
            target.frame(graph);
        }
    }

    /** One frame, {@code seconds} of wind after the last. */
    void frame(double seconds) {
        update(seconds);
        target.frame(graph);
    }

    BufferedImage shot() {
        return target.shot();
    }

    @Override
    public void close() {
        device.waitIdle();
        vegetation.close();
        graph.close();
        shadows.close();
        target.close();
        lights.close();
        baker.close();
        sceneSync.close();
        resources.close();
        cache.close();
        tasks.close();
        textures.close();
        gpu.setMaterials(null);
        materials.close();
        renderer.close();
        gpu.close();
        pool.close();
    }
}
