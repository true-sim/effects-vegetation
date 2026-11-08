package com.truesim.effects.vegetation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.lwjgl.vulkan.VK10.VK_BUFFER_USAGE_TRANSFER_DST_BIT;
import static org.lwjgl.vulkan.VK10.VK_FORMAT_R16G16_SFLOAT;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_ASPECT_COLOR_BIT;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_LAYOUT_UNDEFINED;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_USAGE_TRANSFER_DST_BIT;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_USAGE_TRANSFER_SRC_BIT;
import static org.lwjgl.vulkan.VK10.vkCmdCopyImageToBuffer;

import com.truesim.common.math.Vec3;
import com.truesim.effects.vegetation.materials.VegetationGrassParameters;
import com.truesim.effects.vegetation.materials.VegetationWindParameters;
import com.truesim.engine.graph.ExternalImage;
import com.truesim.engine.vulkan.Format;
import com.truesim.engine.vulkan.GpuBuffer;
import com.truesim.engine.vulkan.GpuImage;
import com.truesim.engine.vulkan.testing.TestGpu;
import java.awt.image.BufferedImage;
import java.lang.foreign.ValueLayout;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkBufferImageCopy;

/**
 * Grass drawn by the engine (the vegetationGrass type over its patch template, displaced in every pass), validated:
 * the blades seen follow the density, the template's detail falls with distance, culling by the displacement's bounds
 * and occlusion never drops a blade that is seen, a calm draws exactly what no wind draws, motion vectors carry a
 * frame onto the next, blades cast shadows. Small patches and images, for CI's software GPU.
 */
class GrassRenderTest {

    private static final int SIZE = 160;
    /** The engine's projection scale at this size and 60 degrees: the template's first level at 8 m there. */
    private static final double PROJECTION = SIZE / (2 * Math.tan(Math.toRadians(30)));
    private static final GrassPatch PATCH = new GrassPatch(4, 60, 3, 3, (float) (8 / PROJECTION), 1);
    private static final VegetationGrassParameters GRASS = VegetationGrassParameters.DEFAULTS
        .withTipColor(new com.truesim.common.math.color.Color(0.3f, 0.6f, 0.1f, 1))
        .withRootColor(new com.truesim.common.math.color.Color(0.2f, 0.45f, 0.06f, 1)).withDryness(0);
    private static final VegetationWindParameters GUSTY = VegetationWindParameters.DEFAULTS.withSpeed(7)
        .withDirection(new Vec3(1, 0, 0.4)).withGustStrength(0.8f).withTurbulence(0.4f).withFlutter(0.1f);

    private TestGpu gpu;

    @BeforeEach
    void start() {
        gpu = TestGpu.open();
    }

    @AfterEach
    void stop() {
        if (gpu != null) {
            gpu.close(); // fails on validation errors
        }
    }

    private Meadow meadow(double groundSize) {
        return gpu.closing(new Meadow(gpu.device(), SIZE, SIZE, Meadow::flat, groundSize));
    }

    private static int[] pixels(BufferedImage image) {
        return image.getRGB(0, 0, image.getWidth(), image.getHeight(), null, 0, image.getWidth());
    }

    /** Pixels differing from {@code bare} by more than a little: blades. */
    private static int blades(BufferedImage image, BufferedImage bare) {
        int count = 0;
        int[] a = pixels(image);
        int[] b = pixels(bare);
        for (int i = 0; i < a.length; i++) {
            count += difference(a[i], b[i]) > 24 ? 1 : 0;
        }
        return count;
    }

    /** Writes {@code image} for a look, where {@code truesim.testShots} names a folder. */
    static void keep(BufferedImage image, String name) {
        String folder = System.getProperty("truesim.testShots");
        if (folder != null) {
            try {
                java.nio.file.Files.createDirectories(java.nio.file.Path.of(folder));
                javax.imageio.ImageIO.write(image, "png", java.nio.file.Path.of(folder, name + ".png").toFile());
            } catch (java.io.IOException e) {
                throw new java.io.UncheckedIOException(e);
            }
        }
    }

    @Test
    void theBladesSeenFollowTheDensity() {
        Meadow meadow = meadow(60).grass(PATCH, GRASS, 0, 0, 8);
        meadow.look(0.3, 2.5, 0.2, 0, -89, 60).shade(true, true);
        meadow.hooks.viewer(null, 1); // full detail: only the density thins them
        int previous = -1;
        int[] seen = new int[4];
        float[] densities = {0, 0.25f, 0.5f, 1};
        BufferedImage bare = null;
        for (int i = 0; i < densities.length; i++) {
            meadow.materials.set(meadow.grassMaterial, GrassMaterials.material(GRASS.withDensity(densities[i]), PATCH,
                3, 3));
            meadow.frames(i == 0 ? 8 : 3, 0);
            BufferedImage shot = meadow.shot();
            keep(shot, "density-" + densities[i]);
            bare = i == 0 ? shot : bare;
            seen[i] = blades(shot, bare);
            assertThat(seen[i]).as("blades seen at density %s", densities[i]).isGreaterThan(previous);
            previous = seen[i];
        }
        assertThat(seen[1]).as("some at a quarter").isGreaterThan(SIZE * SIZE / 50);
        assertThat(seen[2] / (double) seen[1]).as("about twice as many at half (overlap takes some)").isBetween(1.5,
            2.2);
    }

    @Test
    void theTemplatesDetailFallsWithDistance() {
        Meadow meadow = meadow(1).grass(PATCH, GRASS, 0, 0, 1); // the four patches round the origin
        meadow.shade(true, false);
        // full detail to 8 m, a quarter of the blades to 16 m, a sixteenth past it (the coarsest of three levels)
        int previous = Integer.MAX_VALUE;
        for (double d : new double[] {3, 11, 20, 40}) {
            meadow.look(0, 1.5, d, 0, -Math.toDegrees(Math.atan2(1.5, d)), 60);
            meadow.frames(2, 0);
            int meshlets = meadow.view.view().visibleMeshlets();
            if (d < 40) {
                assertThat(meshlets).as("meshlets drawn from %s m", d).isLessThan(previous);
            } else {
                assertThat(meshlets).as("the coarsest level from %s m", d).isLessThanOrEqualTo(previous);
            }
            previous = meshlets;
        }
        assertThat(previous).as("a few at 40 m").isLessThan(40);
    }

    @Test
    void cullingByTheDisplacementsBoundsDropsNoBladeThatIsSeen() {
        Meadow meadow = meadow(60).grass(PATCH, GRASS, 0, 0, 14).wind(GUSTY);
        meadow.look(0, 1.2, 6, 30, -8, 60).shade(true, true);
        meadow.hooks.time(17.3);
        meadow.frames(8, 0);
        int[] bounded = pixels(meadow.shot());
        keep(meadow.shot(), "culling");
        // Bounds grown ten times: nothing more is drawn, so nothing was culled that is seen
        meadow.materials.set(meadow.grassMaterial, GrassMaterials.material(GRASS, PATCH, 3, 3).displacement(40));
        meadow.frames(3, 0);
        assertThat(pixels(meadow.shot())).as("the same image with bounds of 40 m").isEqualTo(bounded);
    }

    @Test
    void occlusionCullingDropsNoBladeThatIsSeen() {
        int[][] images = new int[2][];
        for (int run = 0; run < 2; run++) {
            Meadow meadow = meadow(60).grass(PATCH, GRASS, 0, 0, 14).wind(GUSTY);
            meadow.look(0, 0.6, 6, 10, -4, 60).shade(true, run == 1);
            meadow.hooks.time(4.2);
            meadow.frames(8, 0);
            keep(meadow.shot(), "occlusion-" + run);
            images[run] = pixels(meadow.shot());
            meadow.close();
        }
        assertThat(images[1]).as("occlusion culling on, as off").isEqualTo(images[0]);
    }

    @Test
    void aCalmDrawsExactlyWhatNoWindDraws() {
        Meadow meadow = meadow(60).grass(PATCH, GRASS, 0, 0, 12);
        meadow.look(0, 1.2, 5, 0, -10, 60).shade(true, true);
        meadow.frames(8, 0.1);
        int[] still = pixels(meadow.shot());
        meadow.frames(3, 0.1);
        assertThat(pixels(meadow.shot())).as("settled: the same image again").isEqualTo(still);
        meadow.wind(GUSTY.withSpeed(0)).frames(3, 0.1);
        assertThat(pixels(meadow.shot())).as("a calm, the wind's time running").isEqualTo(still);
        meadow.wind(GUSTY).frames(3, 0.1);
        assertThat(pixels(meadow.shot())).as("a wind moves them").isNotEqualTo(still);
    }

    @Test
    void motionVectorsCarryAFrameOntoTheNext() {
        Assumptions.assumeTrue(gpu.device().primitiveIds(), "the visibility buffer needs primitive IDs");
        try (GpuImage motion = new GpuImage(gpu.device(), SIZE, SIZE, VK_FORMAT_R16G16_SFLOAT,
                VK_IMAGE_USAGE_TRANSFER_SRC_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT
                    | org.lwjgl.vulkan.VK10.VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT
                    | org.lwjgl.vulkan.VK10.VK_IMAGE_USAGE_STORAGE_BIT, VK_IMAGE_ASPECT_COLOR_BIT);
             GpuBuffer back = GpuBuffer.hostVisible(gpu.device(), 4L * SIZE * SIZE, VK_BUFFER_USAGE_TRANSFER_DST_BIT)) {
            ExternalImage output = new ExternalImage("motion", SIZE, SIZE, Format.R16G16_SFLOAT,
                VK_IMAGE_USAGE_TRANSFER_SRC_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT
                    | org.lwjgl.vulkan.VK10.VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT
                    | org.lwjgl.vulkan.VK10.VK_IMAGE_USAGE_STORAGE_BIT,
                VK_IMAGE_LAYOUT_UNDEFINED, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL).set(motion.handle(), motion.view());
            Meadow meadow = meadow(60).grass(PATCH, GRASS.withHeight(0.9f), 0, 0, 10).wind(GUSTY.withSpeed(9));
            meadow.look(0, 0.9, 3, 0, -12, 60).shade(true, false, output);
            java.util.function.Consumer<org.lwjgl.vulkan.VkCommandBuffer> copy = commands -> {
                try (MemoryStack stack = MemoryStack.stackPush()) {
                    VkBufferImageCopy.Buffer region = VkBufferImageCopy.calloc(1, stack);
                    region.imageSubresource().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).layerCount(1);
                    region.imageExtent().set(SIZE, SIZE, 1);
                    vkCmdCopyImageToBuffer(commands, motion.handle(), VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
                        back.handle(), region);
                }
            };
            meadow.hooks.time(2);
            meadow.frames(8, 0, copy);
            float[] still = motion(back);
            double stillMax = 0;
            for (float m : still) {
                stillMax = Math.max(stillMax, Math.abs(m));
            }
            assertThat(stillMax).as("the wind's time standing: nothing moves").isLessThan(0.01);
            BufferedImage before = meadow.shot();
            meadow.frames(1, 1 / 15.0, copy);
            BufferedImage after = meadow.shot();
            keep(before, "motion-before");
            keep(after, "motion-after");
            float[] vectors = motion(back);
            // Each pixel now, fetched where its vector says it was: closer to the frame before than the same pixel
            double warped = 0;
            double unwarped = 0;
            int moving = 0;
            for (int y = 4; y < SIZE - 4; y++) {
                for (int x = 4; x < SIZE - 4; x++) {
                    int p = y * SIZE + x;
                    double mx = vectors[2 * p];
                    double my = vectors[2 * p + 1];
                    if (Math.hypot(mx, my) < 0.25) {
                        continue;
                    }
                    moving++;
                    int px = (int) Math.round(x + mx);
                    int py = (int) Math.round(y + my);
                    if (px < 0 || py < 0 || px >= SIZE || py >= SIZE) {
                        continue;
                    }
                    warped += difference(after.getRGB(x, y), before.getRGB(px, py));
                    unwarped += difference(after.getRGB(x, y), before.getRGB(x, y));
                }
            }
            assertThat(moving).as("blades moving").isGreaterThan(SIZE * SIZE / 20);
            assertThat(warped).as("warped by the vectors, against not").isLessThan(unwarped * 0.7);
            meadow.close();
        }
    }

    private static float[] motion(GpuBuffer back) {
        float[] m = new float[2 * SIZE * SIZE];
        for (int i = 0; i < m.length; i++) {
            m[i] = Float.float16ToFloat(back.memory().getAtIndex(ValueLayout.JAVA_SHORT, i));
        }
        return m;
    }

    private static double difference(int a, int b) {
        return Math.abs((a >> 16 & 0xff) - (b >> 16 & 0xff)) + Math.abs((a >> 8 & 0xff) - (b >> 8 & 0xff))
            + Math.abs((a & 0xff) - (b & 0xff));
    }

    @Test
    void bladesCastShadows() {
        // One small patch of tall grass, the sun low from the side: the soil beyond it, where its shadow falls, darker
        GrassPatch small = new GrassPatch(2, 60, 3, 1, VegetationSettings.lodError(8), 2);
        Meadow meadow = meadow(60).grass(small, GRASS.withHeight(1.5f).withWidth(0.06f).withLean(0).withCurvature(0.1f), 1, 1, 0.1);
        assertThat(meadow.patches()).isOne();
        meadow.materials.set(meadow.soil, new com.truesim.engine.render.Material(
            com.truesim.engine.render.materials.MetallicRoughnessParameters.DEFAULTS.withBaseColor(
                new com.truesim.common.math.color.Color(0.4f, 0.4f, 0.4f, 1)).withRoughness(1).withMetallic(0)));
        meadow.look(1.8, 6, 1.8, 0, -89.9, 70).shade(true, true);
        meadow.hooks.viewer(null, 1);
        // the sun shines along -(sun): shadows fall away from it, along the ground
        double[] away = {0.45, 0.7}; // Meadow's sun is (-0.45, 0.55, -0.7): shadows fall towards +x, +z
        double l = Math.hypot(away[0], away[1]);
        double sx = 1 + 2.2 * away[0] / l;
        double sz = 1 + 2.2 * away[1] / l;
        meadow.frames(8, 0);
        keep(meadow.shot(), "shadow-grass");
        double shaded = soil(meadow.shot(), meadow, sx, sz);
        meadow.clearGrass();
        meadow.frames(8, 0);
        keep(meadow.shot(), "shadow-bare");
        double bare = soil(meadow.shot(), meadow, sx, sz);
        assertThat(shaded).as("the soil in the grass's shadow, against bare").isLessThan(bare * 0.85);
    }

    /** The mean brightness of the image round world (x, 0, z), looking straight down. */
    private static double soil(BufferedImage image, Meadow meadow, double x, double z) {
        float[] m = meadow.camera.viewProjection();
        double rx = x - meadow.camera.position(0);
        double ry = -meadow.camera.position(1);
        double rz = z - meadow.camera.position(2);
        double cx = m[0] * rx + m[4] * ry + m[8] * rz + m[12];
        double cy = m[1] * rx + m[5] * ry + m[9] * rz + m[13];
        double w = m[3] * rx + m[7] * ry + m[11] * rz + m[15];
        int px = (int) ((cx / w * 0.5 + 0.5) * SIZE);
        int py = (int) ((cy / w * 0.5 + 0.5) * SIZE);
        double sum = 0;
        int n = 0;
        for (int y = py - 3; y <= py + 3; y++) {
            for (int x2 = px - 3; x2 <= px + 3; x2++) {
                int p = image.getRGB(Math.clamp(x2, 0, SIZE - 1), Math.clamp(y, 0, SIZE - 1));
                sum += (p >> 16 & 0xff) + (p >> 8 & 0xff) + (p & 0xff);
                n++;
            }
        }
        return sum / n;
    }
}
