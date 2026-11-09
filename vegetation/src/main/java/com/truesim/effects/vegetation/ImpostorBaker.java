package com.truesim.effects.vegetation;

import static org.lwjgl.vulkan.VK10.VK_FORMAT_D32_SFLOAT;
import static org.lwjgl.vulkan.VK10.VK_FORMAT_R16G16B16A16_SFLOAT;
import static org.lwjgl.vulkan.VK10.VK_BUFFER_USAGE_TRANSFER_DST_BIT;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_ASPECT_COLOR_BIT;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_LAYOUT_UNDEFINED;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_USAGE_TRANSFER_SRC_BIT;
import static org.lwjgl.vulkan.VK10.vkCmdCopyImageToBuffer;

import com.truesim.annotations.AllowAllocation;
import com.truesim.common.math.Vec2;
import com.truesim.common.math.Vec3;
import com.truesim.common.math.Vec4;
import com.truesim.common.math.color.Color;
import com.truesim.effects.vegetation.materials.VegetationImpostorBake;
import com.truesim.effects.vegetation.materials.VegetationImpostorBakeParameters;
import com.truesim.engine.graph.ExternalImage;
import com.truesim.engine.graph.FrameGraph;
import com.truesim.engine.graph.ViewPort;
import com.truesim.engine.render.Camera;
import com.truesim.engine.render.ClusteredForwardShading;
import com.truesim.engine.render.GeometryPool;
import com.truesim.engine.render.GpuScene;
import com.truesim.engine.render.Keys;
import com.truesim.engine.render.Material;
import com.truesim.engine.render.MaterialValues;
import com.truesim.engine.render.Materials;
import com.truesim.engine.render.MeshletRenderer;
import com.truesim.engine.render.SceneGeometry;
import com.truesim.engine.render.SceneView;
import com.truesim.engine.render.Textures;
import com.truesim.engine.render.ViewOptions;
import com.truesim.engine.render.materials.UnlitParameters;
import com.truesim.engine.vulkan.Format;
import com.truesim.engine.vulkan.GpuBuffer;
import com.truesim.engine.vulkan.GpuImage;
import com.truesim.engine.vulkan.VulkanDevice;
import com.truesim.scene.assets.AssetRef;
import com.truesim.scene.assets.mesh.MeshData;
import com.truesim.scene.render.MaterialData;
import com.truesim.scene.render.TextureData;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkBufferImageCopy;

/**
 * Bakes a model into an octahedral impostor's atlas through the engine: its meshes and materials drawn by the engine's
 * own renderer (meshlets, its material's textures and masks) from {@code frames} x {@code frames} directions over the
 * upper hemisphere (hemi-octahedral, as impostor.slang reads them), each an orthographic view of the bounding sphere,
 * {@code resolution} pixels square, three times: the albedo (as its square root), the normal and the position, unlit
 * ({@code vegetationImpostorBake}). The result is one texture, {@code frames * resolution} wide and twice as tall:
 * above, sqrt(albedo) and coverage; below, the normal (n / 2 + 1 / 2) and the depth toward each frame's camera
 * (-radius to radius as 0 to 1), each frame's empty texels filled from their covered neighbours so filtering draws no
 * dark fringes.
 *
 * <p>Its renderer is its own (a geometry pool, scene, material table and textures on the device, a view and a graph),
 * set up on the first bake. Bakes run on the caller's thread, one at a time, waiting for the GPU: the vegetation's
 * sync bakes on the scene's thread, within its budget a frame.
 */
public final class ImpostorBaker implements AutoCloseable {

    /** A part of a model: a mesh, its submeshes' materials, where it stands in the model (translation, rotation, scale). */
    public record Part(MeshData mesh, List<MaterialData> materials, double tx, double ty, double tz, double qx,
        double qy, double qz, double qw, double sx, double sy, double sz) {

        public Part {
            Objects.requireNonNull(mesh, "mesh");
            materials = List.copyOf(materials);
        }

        /** A part at the model's origin. */
        public Part(MeshData mesh, List<MaterialData> materials) {
            this(mesh, materials, 0, 0, 0, 0, 0, 0, 1, 1, 1, 1);
        }
    }

    private final VulkanDevice device;
    private GeometryPool pool;
    private GpuScene scene;
    private Materials materials;
    private Textures textures;
    private MeshletRenderer renderer;
    private FrameGraph graph;
    private GpuImage target;
    private GpuBuffer readback;
    private final Camera camera = new Camera();
    private int resolution;
    private int bakes;

    /** A baker on {@code device}. */
    public ImpostorBaker(VulkanDevice device) {
        this.device = Objects.requireNonNull(device, "device");
    }

    /** How many models it baked. */
    public int bakes() {
        return bakes;
    }

    /**
     * The atlas of a model of {@code parts}, its textures by {@code textures} (loaded: null draws it untextured), within
     * the sphere ({@code cx}, {@code cy}, {@code cz}) of {@code radius}, as {@code frames} x {@code frames} views of
     * {@code resolution} pixels; {@code windColors}: the meshes' vertex colors carry wind data, not a tint.
     */
    @AllowAllocation("baking, once per model")
    public synchronized TextureData bake(List<Part> parts, Function<AssetRef<TextureData>, TextureData> textureData,
        double cx, double cy, double cz, double radius, int frames, int resolution, boolean windColors) {
        if (frames < 2 || resolution < 4 || !(radius > 0)) {
            throw new IllegalArgumentException("An impostor of " + frames + " frames of " + resolution + " pixels, "
                + "radius " + radius);
        }
        setUp(resolution);
        Map<AssetRef<TextureData>, Integer> slots = new HashMap<>();
        List<Integer> meshes = new ArrayList<>();
        List<Integer> firsts = new ArrayList<>();
        List<List<VegetationImpostorBakeParameters>> parameters = new ArrayList<>();
        List<List<MaterialData>> sources = new ArrayList<>();
        int n = resolution * resolution;
        float[][] color = new float[frames * frames][];
        float[][] normal = new float[frames * frames][];
        float[][] position = new float[frames * frames][];
        try {
            for (int p = 0; p < parts.size(); p++) {
                Part part = parts.get(p);
                int mesh = pool.add(part.mesh());
                meshes.add(mesh);
                int slotsOfMesh = Math.max(1, part.mesh().materialSlots());
                List<VegetationImpostorBakeParameters> ofPart = new ArrayList<>();
                List<MaterialData> sourceOfPart = new ArrayList<>();
                List<Material> initial = new ArrayList<>();
                for (int s = 0; s < slotsOfMesh; s++) {
                    MaterialData source = part.materials().isEmpty() ? MaterialData.DEFAULT
                        : part.materials().get(Math.min(s, part.materials().size() - 1));
                    VegetationImpostorBakeParameters params = VegetationImpostorBakeParameters.fromValues(
                        new Values(source.values(), "", slots, textureData))
                        .withWindColors(windColors ? 1 : 0).withCenter(new Vec3((float) cx, (float) cy, (float) cz))
                        .withRadius((float) radius);
                    ofPart.add(params);
                    sourceOfPart.add(source);
                    initial.add(material(params, source, 0));
                }
                parameters.add(ofPart);
                sources.add(sourceOfPart);
                int first = materials.addAll(initial);
                firsts.add(first);
                scene.set(p, mesh, first, part.tx(), part.ty(), part.tz(), (float) part.qx(), (float) part.qy(),
                    (float) part.qz(), (float) part.qw(), (float) part.sx(), (float) part.sy(), (float) part.sz());
            }
            for (int mode = 0; mode < 3; mode++) {
                for (int p = 0; p < parts.size(); p++) {
                    for (int s = 0; s < parameters.get(p).size(); s++) {
                        materials.set(firsts.get(p) + s, material(parameters.get(p).get(s), sources.get(p).get(s),
                            mode));
                    }
                }
                float[][] into = mode == 0 ? color : mode == 1 ? normal : position;
                for (int j = 0; j < frames; j++) {
                    for (int i = 0; i < frames; i++) {
                        Vec3 f = Impostors.direction(i, j, frames);
                        double[] basis = Impostors.basis(f);
                        double r = radius;
                        camera.position(0, 0, 0).setOrthographic(cx + 2 * r * f.x(), cy + 2 * r * f.y(),
                            cz + 2 * r * f.z(), -f.x(), -f.y(), -f.z(), basis[3], basis[4], basis[5], r, r, 4 * r,
                            resolution, resolution);
                        into[j * frames + i] = render(n);
                    }
                }
            }
        } finally {
            for (int p = 0; p < meshes.size(); p++) {
                scene.remove(p);
            }
            for (int first : firsts) {
                materials.remove(first);
            }
            for (int mesh : meshes) {
                pool.remove(mesh);
            }
            for (int slot : slots.values()) {
                if (slot >= 0) {
                    textures.remove(slot);
                }
            }
        }
        bakes++;
        return atlas(color, normal, position, frames, resolution, cx, cy, cz, radius);
    }

    /** The bake material of {@code params} in {@code mode}, masked and two-sided as {@code source}. */
    private static Material material(VegetationImpostorBakeParameters params, MaterialData source, int mode) {
        Material m = new Material(params.withMode(mode)).feature(UnlitParameters.DEFAULTS)
            .doubleSided(source.doubleSided());
        return source.alphaMode() == MaterialData.AlphaMode.MASK ? m.alphaMask(source.alphaCutoff()) : m;
    }

    /** One frame drawn and read back: rgba as floats. */
    @AllowAllocation("baking, once per model")
    private float[] render(int texels) {
        int size = resolution;
        device.submitAndWait(device.graphics(), commands -> {
            graph.record(commands);
            try (MemoryStack stack = MemoryStack.stackPush()) {
                VkBufferImageCopy.Buffer region = VkBufferImageCopy.calloc(1, stack);
                region.imageSubresource().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).layerCount(1);
                region.imageExtent().set(size, size, 1);
                vkCmdCopyImageToBuffer(commands, target.handle(), VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
                    readback.handle(), region);
            }
        });
        MemorySegment memory = readback.memory();
        ValueLayout.OfShort half = ValueLayout.JAVA_SHORT_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN);
        float[] out = new float[4 * texels];
        for (int i = 0; i < 4 * texels; i++) {
            out[i] = Float.float16ToFloat(memory.get(half, 2L * i));
        }
        return out;
    }

    /** The frames' readbacks as the atlas, empty texels filled from their neighbours. */
    private static TextureData atlas(float[][] color, float[][] normal, float[][] position, int frames, int res,
        double cx, double cy, double cz, double radius) {
        int width = frames * res;
        int height = 2 * width;
        MemorySegment texels = Arena.ofAuto().allocate(4L * width * height);
        float[] top = new float[4 * res * res];
        float[] bottom = new float[4 * res * res];
        for (int j = 0; j < frames; j++) {
            for (int i = 0; i < frames; i++) {
                int frame = j * frames + i;
                Vec3 f = Impostors.direction(i, j, frames);
                float[] c = color[frame];
                float[] nn = normal[frame];
                float[] p = position[frame];
                boolean[] covered = new boolean[res * res];
                for (int t = 0; t < res * res; t++) {
                    covered[t] = nn[4 * t] + nn[4 * t + 1] + nn[4 * t + 2] > 0.01f;
                    top[4 * t] = c[4 * t];
                    top[4 * t + 1] = c[4 * t + 1];
                    top[4 * t + 2] = c[4 * t + 2];
                    top[4 * t + 3] = covered[t] ? 1 : 0;
                    bottom[4 * t] = nn[4 * t];
                    bottom[4 * t + 1] = nn[4 * t + 1];
                    bottom[4 * t + 2] = nn[4 * t + 2];
                    // the position back in mesh space, its depth along the frame's direction
                    double px = (p[4 * t] - 0.5) * 2 * radius;
                    double py = (p[4 * t + 1] - 0.5) * 2 * radius;
                    double pz = (p[4 * t + 2] - 0.5) * 2 * radius;
                    double depth = (px * f.x() + py * f.y() + pz * f.z()) / radius;
                    bottom[4 * t + 3] = covered[t] ? (float) (0.5 + 0.5 * Math.max(-1, Math.min(1, depth))) : 0.5f;
                }
                dilate(top, bottom, covered, res);
                write(texels, width, i * res, j * res, res, top);
                write(texels, width, i * res, width + j * res, res, bottom);
            }
        }
        return new TextureData(width, height, TextureData.ColorSpace.LINEAR, texels);
    }

    /** Fills the color, normal and depth of empty texels from their covered neighbours, ring by ring (8 rings). */
    private static void dilate(float[] top, float[] bottom, boolean[] covered, int res) {
        boolean[] known = covered.clone();
        for (int ring = 0; ring < 8; ring++) {
            boolean[] next = known.clone();
            for (int y = 0; y < res; y++) {
                for (int x = 0; x < res; x++) {
                    int t = y * res + x;
                    if (known[t]) {
                        continue;
                    }
                    float[] sum = new float[7];
                    int count = 0;
                    for (int dy = -1; dy <= 1; dy++) {
                        for (int dx = -1; dx <= 1; dx++) {
                            int xx = x + dx;
                            int yy = y + dy;
                            if (xx < 0 || yy < 0 || xx >= res || yy >= res || !known[yy * res + xx]) {
                                continue;
                            }
                            int o = yy * res + xx;
                            for (int k = 0; k < 3; k++) {
                                sum[k] += top[4 * o + k];
                                sum[3 + k] += bottom[4 * o + k];
                            }
                            sum[6] += bottom[4 * o + 3];
                            count++;
                        }
                    }
                    if (count > 0) {
                        for (int k = 0; k < 3; k++) {
                            top[4 * t + k] = sum[k] / count;
                            bottom[4 * t + k] = sum[3 + k] / count;
                        }
                        bottom[4 * t + 3] = sum[6] / count;
                        next[t] = true;
                    }
                }
            }
            known = next;
        }
    }

    private static void write(MemorySegment texels, int width, int x0, int y0, int res, float[] rgba) {
        for (int y = 0; y < res; y++) {
            for (int x = 0; x < res; x++) {
                int t = y * res + x;
                long o = 4L * ((long) (y0 + y) * width + x0 + x);
                for (int k = 0; k < 4; k++) {
                    int v = Math.round(Math.max(0, Math.min(1, rgba[4 * t + k])) * 255);
                    texels.set(ValueLayout.JAVA_BYTE, o + k, (byte) v);
                }
            }
        }
    }

    /** Its renderer, made the first time and again for another resolution. */
    @AllowAllocation("set up once per resolution")
    private void setUp(int size) {
        if (pool == null) {
            pool = new GeometryPool(device, GeometryPool.Capacity.of(1 << 22, 1 << 16, 64));
            scene = new GpuScene(device, pool, 256);
            materials = new Materials(device, 64);
            materials.register(VegetationImpostorBake.TYPE);
            scene.setMaterials(materials);
            textures = new Textures(device);
            renderer = new MeshletRenderer(device, scene, VK_FORMAT_R16G16B16A16_SFLOAT, VK_FORMAT_D32_SFLOAT);
        }
        if (size == resolution && graph != null) {
            return;
        }
        if (graph != null) {
            device.waitIdle();
            graph.close();
            target.close();
            readback.close();
        }
        resolution = size;
        graph = new FrameGraph(device, 2);
        int usage = VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT | VK_IMAGE_USAGE_TRANSFER_SRC_BIT;
        target = new GpuImage(device, size, size, VK_FORMAT_R16G16B16A16_SFLOAT, usage, VK_IMAGE_ASPECT_COLOR_BIT);
        readback = GpuBuffer.hostVisible(device, 8L * size * size, VK_BUFFER_USAGE_TRANSFER_DST_BIT);
        SceneGeometry geometry = SceneGeometry.meshlets(renderer);
        ViewPort port = graph.addView("impostor bake").size(size, size);
        SceneView view = geometry.createView(port, camera, ViewOptions.of(1 << 16, 1 << 21));
        view.set(1, MeshletRenderer.MODE_LIT);
        port.add(new ClusteredForwardShading(geometry, view, VK_FORMAT_R16G16B16A16_SFLOAT, VK_FORMAT_D32_SFLOAT))
            .output(Keys.SCENE_COLOR, new ExternalImage("impostor bake", size, size, Format.R16G16B16A16_SFLOAT, usage,
                VK_IMAGE_LAYOUT_UNDEFINED, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL).set(target.handle(), target.view()));
    }

    @Override
    public synchronized void close() {
        if (pool == null) {
            return;
        }
        device.waitIdle();
        graph.close();
        target.close();
        readback.close();
        scene.setMaterials(null);
        renderer.close();
        textures.close();
        materials.close();
        scene.close();
        pool.close();
        pool = null;
    }

    /** A material's data as the record's values, its textures uploaded on first use (null data: none). */
    private final class Values implements MaterialValues {
        private final MaterialData.Values values;
        private final String prefix;
        private final Map<AssetRef<TextureData>, Integer> slots;
        private final Function<AssetRef<TextureData>, TextureData> data;

        Values(MaterialData.Values values, String prefix, Map<AssetRef<TextureData>, Integer> slots,
            Function<AssetRef<TextureData>, TextureData> data) {
            this.values = values;
            this.prefix = prefix;
            this.slots = slots;
            this.data = data;
        }

        /** The source's name for {@code name}: the bake's {@code base.x} is the source's {@code x}. */
        @AllowAllocation("baking, once per model")
        private String name(String name) {
            String full = prefix + name;
            return full.startsWith("base.") ? full.substring("base.".length()) : "\u0000" + full;
        }

        @Override
        public float f32(String name, float fallback) {
            return values.f32(name(name), fallback);
        }

        @Override
        public int i32(String name, int fallback) {
            return values.i32(name(name), fallback);
        }

        private float[] floats(String name) {
            return values.floats(name(name));
        }

        @Override
        public Vec2 vec2(String name, Vec2 fallback) {
            float[] v = floats(name);
            return v == null || v.length != 2 ? fallback : new Vec2(v[0], v[1]);
        }

        @Override
        public Vec3 vec3(String name, Vec3 fallback) {
            float[] v = floats(name);
            return v == null || v.length != 3 ? fallback : new Vec3(v[0], v[1], v[2]);
        }

        @Override
        public Vec4 vec4(String name, Vec4 fallback) {
            float[] v = floats(name);
            return v == null || v.length != 4 ? fallback : new Vec4(v[0], v[1], v[2], v[3]);
        }

        @Override
        public Color color(String name, Color fallback) {
            float[] v = floats(name);
            return v == null || v.length < 3 ? fallback
                : new Color(v[0], v[1], v[2], v.length == 4 ? v[3] : fallback.a());
        }

        @Override
        @AllowAllocation("baking, once per model")
        public int texture(String name, boolean srgb, int fallback) {
            AssetRef<TextureData> ref = values.texture(name(name));
            if (ref == null) {
                return fallback;
            }
            Integer slot = slots.get(ref);
            if (slot == null) {
                TextureData texture = data.apply(ref);
                slot = texture == null || texture.volume() || texture.format() != TextureData.Format.RGBA8 ? -1
                    : texture.colorSpace() == TextureData.ColorSpace.SRGB
                        ? textures.color(texture.width(), texture.height(), texture.texels())
                        : textures.data(texture.width(), texture.height(), texture.texels());
                slots.put(ref, slot);
            }
            return slot < 0 ? fallback : slot;
        }

        @Override
        @AllowAllocation("baking, once per model")
        public MaterialValues nested(String name) {
            return new Values(values, prefix + name + ".", slots, data);
        }
    }
}
