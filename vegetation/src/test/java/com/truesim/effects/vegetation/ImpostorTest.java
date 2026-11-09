package com.truesim.effects.vegetation;

import static org.assertj.core.api.Assertions.assertThat;

import com.truesim.common.math.Vec3;
import com.truesim.engine.vulkan.testing.TestGpu;
import com.truesim.scene.assets.mesh.MeshData;
import com.truesim.scene.render.MaterialData;
import com.truesim.scene.render.TextureData;
import java.awt.image.BufferedImage;
import java.lang.foreign.ValueLayout;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Octahedral impostors baked through the engine and drawn by the impostor type: the atlas holds the tree in every
 * frame, and the impostor covers what the tree's mesh covers, seen level and from above, the tree turned or not; the
 * fade dithers the mesh out and the impostor in over the band, never leaving the tree thinner than either.
 */
class ImpostorTest {

    private static final int SIZE = 160;
    static final MaterialData BARK = MaterialData.builder(MaterialData.METALLIC_ROUGHNESS)
        .set("baseColor", 0.12f, 0.08f, 0.05f, 1f).set("roughness", 0.9f).set("metallic", 0f).build();
    static final MaterialData NEEDLES = MaterialData.builder(MaterialData.METALLIC_ROUGHNESS)
        .set("baseColor", 0.03f, 0.08f, 0.03f, 1f).set("roughness", 0.7f).set("metallic", 0f).doubleSided(true)
        .build();

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

    /** A spruce's atlas and the sphere it covers (center x, y, z, radius). */
    private TextureData bake(MeshData spruce, float[] sphere, int frames, int resolution) {
        double[] min = {spruce.boxMin(0), spruce.boxMin(1), spruce.boxMin(2)};
        double[] ext = {spruce.boxExtent(0), spruce.boxExtent(1), spruce.boxExtent(2)};
        sphere[0] = (float) (min[0] + ext[0] / 2);
        sphere[1] = (float) (min[1] + ext[1] / 2);
        sphere[2] = (float) (min[2] + ext[2] / 2);
        sphere[3] = (float) (0.5 * Math.sqrt(ext[0] * ext[0] + ext[1] * ext[1] + ext[2] * ext[2]));
        try (ImpostorBaker baker = new ImpostorBaker(gpu.device())) {
            TextureData atlas = baker.bake(List.of(new ImpostorBaker.Part(spruce, List.of(BARK, NEEDLES))),
                ref -> null, sphere[0], sphere[1], sphere[2], sphere[3], frames, resolution, false);
            assertThat(baker.bakes()).isOne();
            return atlas;
        }
    }

    @Test
    void theAtlasHoldsTheTreeInEveryFrame() {
        MeshData spruce = TreeMeshes.spruce(10, 2.6);
        float[] sphere = new float[4];
        TextureData atlas = bake(spruce, sphere, 4, 32);
        assertThat(atlas.width()).isEqualTo(128);
        assertThat(atlas.height()).isEqualTo(256);
        for (int j = 0; j < 4; j++) {
            for (int i = 0; i < 4; i++) {
                int covered = 0;
                double green = 0;
                double up = 0;
                for (int y = 0; y < 32; y++) {
                    for (int x = 0; x < 32; x++) {
                        long o = 4L * ((long) (j * 32 + y) * 128 + i * 32 + x);
                        if ((atlas.texels().get(ValueLayout.JAVA_BYTE, o + 3) & 0xff) > 127) {
                            covered++;
                            green += Math.pow((atlas.texels().get(ValueLayout.JAVA_BYTE, o + 1) & 0xff) / 255.0, 2);
                            long n = o + 4L * 128 * 128; // the lower half: the normal
                            up += (atlas.texels().get(ValueLayout.JAVA_BYTE, n + 1) & 0xff) / 255.0 * 2 - 1;
                        }
                    }
                }
                assertThat(covered).as("frame %d, %d covered", i, j).isBetween(32 * 32 / 10, 32 * 32 * 9 / 10);
                assertThat(green / covered).as("its albedo, green as the needles' (squared back)").isBetween(0.02,
                    0.2);
                Vec3 f = Impostors.direction(i, j, 4);
                if (f.y() > 0.8) {
                    assertThat(up / covered).as("seen from above, its normals face up").isPositive();
                }
            }
        }
    }

    /** Pixels of {@code image} differing from {@code bare}. */
    private static boolean[] mask(BufferedImage image, BufferedImage bare) {
        boolean[] m = new boolean[SIZE * SIZE];
        for (int i = 0; i < m.length; i++) {
            int a = image.getRGB(i % SIZE, i / SIZE);
            int b = bare.getRGB(i % SIZE, i / SIZE);
            int d = Math.abs((a >> 16 & 255) - (b >> 16 & 255)) + Math.abs((a >> 8 & 255) - (b >> 8 & 255))
                + Math.abs((a & 255) - (b & 255));
            m[i] = d > 24;
        }
        return m;
    }

    private static double overlap(boolean[] a, boolean[] b) {
        int both = 0;
        int either = 0;
        for (int i = 0; i < a.length; i++) {
            both += a[i] && b[i] ? 1 : 0;
            either += a[i] || b[i] ? 1 : 0;
        }
        return either == 0 ? 0 : both / (double) either;
    }

    private static int count(boolean[] m) {
        int c = 0;
        for (boolean b : m) {
            c += b ? 1 : 0;
        }
        return c;
    }

    @Test
    void theImpostorCoversWhatTheMeshCovers() {
        MeshData spruce = TreeMeshes.spruce(10, 2.6);
        float[] sphere = new float[4];
        TextureData atlas = bake(spruce, sphere, 8, 64);
        try (Meadow meadow = new Meadow(gpu.device(), SIZE, SIZE, Meadow::flat, 200)) {
            int[] tree = meadow.treeMeshAndMaterials(0);
            int barkOnly = tree[1];
            int[] impostor = meadow.impostor(atlas, 8, new float[] {sphere[0], sphere[1], sphere[2]}, sphere[3], 0,
                0);
            // the placeholder's plant materials are a bark and a needles one after it: a block of two
            int treeMaterials = barkOnly;
            double[][] views = {{0, 4, 30, 0, -2}, {0, 20, 22, 0, -38}, {0, 2, 26, 0, 6}};
            meadow.look(0, 4, 30, 0, -2, 50).shade(true, false);
            for (double[] v : views) {
                for (double yaw : new double[] {0, 1.1}) {
                    meadow.look(v[0], v[1], v[2], v[3], v[4], 50);
                    meadow.frames(4, 0);
                    BufferedImage bare = meadow.shot();
                    int id = meadow.place(tree[0], treeMaterials, 0, 0, yaw, 1, false);
                    meadow.frames(4, 0);
                    BufferedImage mesh = meadow.shot();
                    meadow.scene.remove(id);
                    id = meadow.place(impostor[0], impostor[1], 0, 0, yaw, 1, false);
                    meadow.frames(4, 0);
                    BufferedImage card = meadow.shot();
                    meadow.scene.remove(id);
                    String name = "impostor-" + (int) v[1] + "-" + yaw;
                    GrassRenderTest.keep(mesh, name + "-mesh");
                    GrassRenderTest.keep(card, name + "-impostor");
                    boolean[] a = mask(mesh, bare);
                    boolean[] b = mask(card, bare);
                    assertThat(count(a)).as("the tree seen, %s", name).isGreaterThan(400);
                    assertThat(overlap(a, b)).as("the impostor where the tree is, %s", name).isGreaterThan(0.7);
                }
            }
        }
    }
}
