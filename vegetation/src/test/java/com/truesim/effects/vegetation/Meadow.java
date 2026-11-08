package com.truesim.effects.vegetation;

import static org.lwjgl.vulkan.VK10.VK_FORMAT_D32_SFLOAT;
import static org.lwjgl.vulkan.VK10.VK_FORMAT_R16G16B16A16_SFLOAT;

import com.truesim.common.math.color.Color;
import com.truesim.effects.vegetation.materials.VegetationGrassParameters;
import com.truesim.effects.vegetation.materials.VegetationPlant;
import com.truesim.effects.vegetation.materials.VegetationPlantParameters;
import com.truesim.effects.vegetation.materials.VegetationWindParameters;
import com.truesim.engine.graph.FrameGraph;
import com.truesim.engine.graph.Image;
import com.truesim.engine.graph.Key;
import com.truesim.engine.graph.Technique;
import com.truesim.engine.graph.ViewPort;
import com.truesim.engine.graph.testing.TestTarget;
import com.truesim.engine.render.Camera;
import com.truesim.engine.render.ClusteredForwardShading;
import com.truesim.engine.render.ClusteredLights;
import com.truesim.engine.render.DisplayTransform;
import com.truesim.engine.render.Environment;
import com.truesim.engine.render.EnvironmentLighting;
import com.truesim.engine.render.GeometryPool;
import com.truesim.engine.render.GpuScene;
import com.truesim.engine.render.Keys;
import com.truesim.engine.render.Lights;
import com.truesim.engine.render.Material;
import com.truesim.engine.render.Materials;
import com.truesim.engine.render.MeshletRenderer;
import com.truesim.engine.render.SceneGeometry;
import com.truesim.engine.render.SceneView;
import com.truesim.engine.render.SkyBackground;
import com.truesim.engine.render.TextureFormat;
import com.truesim.engine.render.Textures;
import com.truesim.engine.render.ViewOptions;
import com.truesim.engine.render.VisibilityBufferShading;
import com.truesim.engine.render.materials.DiffuseTransmissionParameters;
import com.truesim.engine.render.materials.MetallicRoughnessParameters;
import com.truesim.engine.render.shadows.Shadows;
import com.truesim.engine.vulkan.DisplayEncoding;
import com.truesim.engine.vulkan.Format;
import com.truesim.engine.vulkan.VulkanDevice;
import com.truesim.scene.assets.mesh.MeshData;
import com.truesim.scene.assets.mesh.VertexAttribute;
import com.truesim.scene.assets.mesh.VertexFormat;
import com.truesim.scene.core.ShadowSpec;
import com.truesim.scene.meshbuild.MeshBuilder;
import java.awt.image.BufferedImage;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;
import java.util.function.DoubleBinaryOperator;

/**
 * A meadow for the vegetation's GPU tests, films and benchmark, drawn by the engine's renderer straight (no scene
 * graph: a test's fixture, as the sync places patches through the scene): a ground mesh over a height function, grass
 * patches over it within a radius of a point (each on the ground at its middle, turned by a quarter turn chosen by its
 * cell), placeholder trees of the plant type, the sun with cascaded shadows in a sky, shaded by forward shading or the
 * visibility buffer into half floats and shown through the engine's display transform in an sRGB target.
 */
final class Meadow implements AutoCloseable {

    private static final Key<Image> SHOWN = Key.image("test.vegetation.shown");
    /** The ground's color: the dark green brown under a sward. */
    static final Color SOIL = new Color(0.035f, 0.05f, 0.018f, 1);

    final VulkanDevice device;
    final int width;
    final int height;
    final GeometryPool pool;
    final GpuScene scene;
    final MeshletRenderer renderer;
    final Materials materials;
    final Textures textures;
    final Lights lights;
    final FrameGraph graph;
    final TestTarget target;
    final Camera camera = new Camera();
    final VegetationHooks hooks;
    final DoubleBinaryOperator ground;
    final int soil;
    GroundMap groundMap;
    GrassPatch patch;
    int patchMesh = -1;
    int grassMaterial = -1;
    private int nextId;
    private final List<Integer> patchIds = new ArrayList<>();
    SceneView view;
    Shadows shadows;
    private final float[] sun = normalize(-0.45f, 0.55f, -0.7f);
    private int sky = -1;
    private com.truesim.engine.vulkan.testing.Readback colorBack;
    private boolean readBack;

    /**
     * A meadow of {@code width} x {@code height} pixels on {@code device}, the ground at {@code ground(x, z)} drawn as a
     * mesh {@code groundSize} metres a side round the origin.
     */
    Meadow(VulkanDevice device, int width, int height, DoubleBinaryOperator ground, double groundSize) {
        this.device = device;
        this.width = width;
        this.height = height;
        this.ground = ground;
        pool = new GeometryPool(device, GeometryPool.Capacity.of(1 << 20, 1 << 16, 16));
        scene = new GpuScene(device, pool, 1 << 14);
        renderer = new MeshletRenderer(device, scene, VK_FORMAT_R16G16B16A16_SFLOAT, VK_FORMAT_D32_SFLOAT);
        materials = new Materials(device, 64);
        materials.register(com.truesim.effects.vegetation.materials.VegetationGrass.TYPE);
        materials.register(VegetationPlant.TYPE);
        textures = new Textures(device);
        lights = new Lights(device, 4, 4);
        graph = new FrameGraph(device, 2);
        target = new TestTarget(device, width, height, Format.R8G8B8A8_SRGB);
        scene.setMaterials(materials);
        hooks = new VegetationHooks(device, scene);
        int groundMesh = pool.add(groundMesh(ground, groundSize, Math.min(256, (int) groundSize)));
        soil = materials.add(new Material(MetallicRoughnessParameters.DEFAULTS
            .withBaseColor(SOIL).withRoughness(0.95f).withMetallic(0)));
        scene.set(nextId++, groundMesh, soil, 0, 0, 0, 0, 0, 0, 1, 1, 1, 1);
        lights.directional(0, -sun[0], -sun[1], -sun[2], 3.2f, 3.0f, 2.7f);
        lights.shadow(0, ShadowSpec.cascades(4, 2048, 120));
        lights.setCount(1);
    }

    /** Level ground at 0. */
    static double flat(double x, double z) {
        return 0;
    }

    /** Rolling hills: a slope rising to the north-west, with swells. */
    static double hills(double x, double z) {
        return 0.12 * (-x - z) + 3 * Math.sin(x / 23.0) * Math.cos(z / 31.0) + 1.2 * Math.sin((x + 2 * z) / 11.0);
    }

    /** The ground as a {@link VegetationGround}. */
    VegetationGround vegetationGround() {
        return (x, z) -> ground.applyAsDouble(x, z);
    }

    /** Grass of {@code grass} over patches of {@code patch} within {@code radius} of (x, z), with a ground map. */
    Meadow grass(GrassPatch patch, VegetationGrassParameters grass, double x, double z, double radius) {
        this.patch = patch;
        try (MeshData mesh = GrassPatches.build(patch)) {
            patchMesh = pool.add(mesh);
        }
        grassMaterial = materials.add(GrassMaterials.material(grass, patch, 3, 3));
        double size = patch.size();
        int reach = (int) Math.ceil(radius / size);
        int cx = (int) Math.floor(x / size);
        int cz = (int) Math.floor(z / size);
        for (int j = cz - reach; j <= cz + reach; j++) {
            for (int i = cx - reach; i <= cx + reach; i++) {
                double px = (i + 0.5) * size;
                double pz = (j + 0.5) * size;
                if (Math.hypot(px - x, pz - z) > radius + size * Math.sqrt(0.5)) {
                    continue;
                }
                int id = nextId++;
                double angle = Math.PI / 2 * GrassPatches.quarterTurns(i, j);
                scene.set(id, patchMesh, grassMaterial, px, ground.applyAsDouble(px, pz), pz, 0,
                    (float) Math.sin(angle / 2), 0, (float) Math.cos(angle / 2), 1, 1, 1);
                patchIds.add(id);
            }
        }
        double mapSize = 2 * (radius + 2 * size);
        groundMap = GroundMap.upload(textures, vegetationGround(), x - mapSize / 2, z - mapSize / 2, mapSize,
            Math.min(2048, (int) Math.ceil(mapSize * 2)));
        hooks.ground(groundMap);
        return this;
    }

    /** Takes the grass away again. */
    void clearGrass() {
        for (int id : patchIds) {
            scene.remove(id);
        }
        patchIds.clear();
    }

    /** The grass's patches' instance ids. */
    List<Integer> patchIds() {
        return patchIds;
    }

    /** How many grass patches it has. */
    int patches() {
        return patchIds.size();
    }

    /** A placeholder tree: a trunk and a cone of three tiers as its crown, of the plant type, at (x, z). */
    Meadow tree(double x, double z, float scale, int mesh, int bark) {
        float angle = (float) (GrassPatches.unit(GrassPatches.hash((int) (x * 31 + z * 17))) * Math.PI * 2);
        scene.set(nextId++, mesh, bark, x, ground.applyAsDouble(x, z) - 0.1, z, 0, (float) Math.sin(angle / 2), 0,
            (float) Math.cos(angle / 2), scale, scale, scale);
        return this;
    }

    /** The placeholder tree's mesh, and its two materials (bark, then needles) from the returned first one. */
    int[] treeMeshAndMaterials(float maxWind) {
        int mesh = pool.add(TreeMeshes.spruce(10, 2.6));
        VegetationPlantParameters base = VegetationPlantParameters.DEFAULTS.withHeight(10).withRadius(2.6f);
        VegetationPlantParameters bark = base.withBase(MetallicRoughnessParameters.DEFAULTS
            .withBaseColor(new Color(0.12f, 0.08f, 0.05f, 1)).withRoughness(0.9f).withMetallic(0));
        VegetationPlantParameters needles = base.withLeafWeight(1).withBase(MetallicRoughnessParameters.DEFAULTS
            .withBaseColor(new Color(0.03f, 0.08f, 0.03f, 1)).withRoughness(0.7f).withMetallic(0));
        int first = materials.add(PlantMaterials.material(bark, maxWind));
        materials.add(PlantMaterials.material(needles, maxWind).doubleSided(true)
            .feature(DiffuseTransmissionParameters.DEFAULTS.withDiffuseTransmissionFactor(0.25f)
                .withDiffuseTransmissionColorFactor(new Color(0.3f, 0.6f, 0.2f, 1))));
        return new int[] {mesh, first};
    }

    /** Shades by the visibility buffer ({@code visibility}) or forward shading, the view's lists sized for the meadow. */
    Meadow shade(boolean visibility, boolean occlusion) {
        return shade(visibility, occlusion, null);
    }

    /**
     * {@link #shade(boolean, boolean)}, and with {@code motion} its motion vectors there too (the visibility buffer's
     * IDs: {@code visibility} must be true).
     */
    Meadow shade(boolean visibility, boolean occlusion, com.truesim.engine.graph.ExternalImage motion) {
        if (sky < 0) {
            sky = textures.add(TextureFormat.RGBA16F, 1024, 512, Textures.GENERATE, sky(1024, 512, sun));
        }
        Environment environment = Environment.panorama("meadow sky", sky, 1024);
        SceneGeometry geometry = SceneGeometry.meshlets(renderer);
        ViewPort port = graph.addView("main").size(width, height);
        view = geometry.createView(port, camera, ViewOptions.of(1 << 21, 1 << 22).withOcclusion(occlusion));
        view.set(1, MeshletRenderer.MODE_LIT);
        if (shadows == null) {
            shadows = new Shadows(geometry, lights);
        }
        Technique shading = visibility
            ? new VisibilityBufferShading(geometry, view, VK_FORMAT_R16G16B16A16_SFLOAT, VK_FORMAT_D32_SFLOAT)
            : new ClusteredForwardShading(geometry, view, VK_FORMAT_R16G16B16A16_SFLOAT, VK_FORMAT_D32_SFLOAT);
        graph.settings().set(DisplayTransform.CURVE, "agx");
        graph.settings().set(DisplayTransform.DITHER, false); // the same image for the same frame, for comparisons
        port.add(shading).add(new EnvironmentLighting(environment, view)).add(new SkyBackground(environment, view))
            .add(new ClusteredLights(lights, view)).add(shadows.view(view))
            .add(new DisplayTransform().transform(Keys.SCENE_COLOR, SHOWN, DisplayEncoding.SDR_SRGB))
            .output(SHOWN, target.output());
        if (motion != null) {
            port.add(new com.truesim.engine.render.MotionVectors(geometry, view.view(), view, VK_FORMAT_D32_SFLOAT))
                .output(Keys.MOTION, motion);
        }
        hooks.viewer(camera, 1);
        return this;
    }

    /** The wind from now on. */
    Meadow wind(VegetationWindParameters wind) {
        hooks.wind(wind);
        return this;
    }

    /** The camera at ({@code x}, {@code y}, {@code z}), turned {@code yaw} degrees left of -z, {@code pitch} up. */
    Meadow look(double x, double y, double z, double yaw, double pitch, double fovDegrees) {
        double a = Math.toRadians(yaw);
        double b = Math.toRadians(pitch);
        camera.position(x, y, z).set(-Math.sin(a) * Math.cos(b), Math.sin(b), -Math.cos(a) * Math.cos(b), 0, 1, 0,
            Math.toRadians(fovDegrees), width, height, 0.05);
        return this;
    }

    /** {@code count} frames, {@code seconds} of wind time each, the last read back. */
    void frames(int count, double seconds) {
        frames(count, seconds, null);
    }

    /** {@link #frames(int, double)}, recording {@code after} after each frame (a readback of another target). */
    void frames(int count, double seconds, java.util.function.Consumer<org.lwjgl.vulkan.VkCommandBuffer> after) {
        for (int i = 0; i < count; i++) {
            hooks.update(seconds, graph.settings());
            view.set(1, MeshletRenderer.MODE_LIT);
            if (after == null) {
                target.frame(graph);
                readBack = false;
            } else {
                if (colorBack == null) {
                    colorBack = new com.truesim.engine.vulkan.testing.Readback(device, width, height);
                }
                device.submitAndWait(device.graphics(), commands -> {
                    graph.record(commands);
                    colorBack.record(commands, target.image().handle(),
                        org.lwjgl.vulkan.VK10.VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL);
                    after.accept(commands);
                });
                readBack = true;
            }
        }
    }

    BufferedImage shot() {
        return readBack ? colorBack.image() : target.shot();
    }

    /** A ground mesh over {@code ground}, {@code size} metres a side in {@code n} x {@code n} quads, with normals. */
    static MeshData groundMesh(DoubleBinaryOperator ground, double size, int n) {
        int count = (n + 1) * (n + 1);
        float[] positions = new float[count * 3];
        for (int z = 0; z <= n; z++) {
            for (int x = 0; x <= n; x++) {
                int v = z * (n + 1) + x;
                double wx = (x / (double) n - 0.5) * size;
                double wz = (z / (double) n - 0.5) * size;
                positions[3 * v] = (float) wx;
                positions[3 * v + 1] = (float) ground.applyAsDouble(wx, wz);
                positions[3 * v + 2] = (float) wz;
            }
        }
        int[] indices = new int[n * n * 6];
        int at = 0;
        for (int z = 0; z < n; z++) {
            for (int x = 0; x < n; x++) {
                int a = z * (n + 1) + x;
                int[] quad = {a, a + n + 1, a + 1, a + 1, a + n + 1, a + n + 2};
                System.arraycopy(quad, 0, indices, at, 6);
                at += 6;
            }
        }
        return new MeshBuilder(VertexFormat.of(VertexAttribute.NORMAL), count).positions(positions)
            .attribute(VertexAttribute.NORMAL, com.truesim.engine.render.testing.TestMeshes.normals(positions, indices))
            .submesh(indices).buildHierarchy();
    }

    private static float[] normalize(float x, float y, float z) {
        float l = (float) Math.sqrt(x * x + y * y + z * z);
        return new float[] {x / l, y / l, z / l};
    }

    /** A clear day's panorama: a blue zenith to a pale horizon, a dim ground, the sun's disc and glow. */
    private static MemorySegment sky(int width, int height, float[] sun) {
        MemorySegment texels = Arena.ofAuto().allocate(8L * width * height);
        ValueLayout.OfShort half = ValueLayout.JAVA_SHORT_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN);
        for (int y = 0; y < height; y++) {
            double theta = Math.PI * (y + 0.5) / height;
            for (int x = 0; x < width; x++) {
                double phi = 2 * Math.PI * ((x + 0.5) / width - 0.5);
                double dx = Math.sin(theta) * Math.cos(phi);
                double dy = Math.cos(theta);
                double dz = Math.sin(theta) * Math.sin(phi);
                double up = Math.max(dy, 0);
                double horizon = Math.pow(1 - up, 3);
                double[] c = dy >= 0
                    ? new double[] {0.18 + 0.55 * horizon, 0.32 + 0.5 * horizon, 0.75 + 0.3 * horizon}
                    : new double[] {0.09, 0.1, 0.07};
                double cos = dx * sun[0] + dy * sun[1] + dz * sun[2];
                double angle = Math.acos(Math.min(1, cos));
                double glow = 1.5 * Math.exp(-angle * angle / (2 * 0.08 * 0.08));
                double disc = angle < Math.toRadians(0.6) ? 2000 : 0;
                long at = 8L * (y * (long) width + x);
                for (int k = 0; k < 3; k++) {
                    texels.set(half, at + 2L * k, Float.floatToFloat16((float) (c[k] + glow + disc)));
                }
                texels.set(half, at + 6, Float.floatToFloat16(1));
            }
        }
        return texels;
    }

    private boolean closed;

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        device.waitIdle();
        graph.close();
        if (colorBack != null) {
            colorBack.close();
        }
        if (shadows != null) {
            shadows.close();
        }
        target.close();
        lights.close();
        textures.close();
        scene.setMaterials(null);
        materials.close();
        renderer.close();
        scene.close();
        pool.close();
    }
}
