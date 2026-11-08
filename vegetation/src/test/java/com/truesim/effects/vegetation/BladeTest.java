package com.truesim.effects.vegetation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import com.truesim.common.math.Vec3;
import com.truesim.effects.vegetation.BladeProbe.Vertex;
import com.truesim.effects.vegetation.materials.VegetationFieldParameters;
import com.truesim.effects.vegetation.materials.VegetationGrassParameters;
import com.truesim.effects.vegetation.materials.VegetationWindParameters;
import com.truesim.engine.vulkan.testing.TestGpu;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Blades as the GPU makes them (blade.slang through {@link BladeProbe}, what the grass type's displace() runs): at rest
 * in a calm, their length kept however the wind bends them, leaning with the speed squared, standing as densely as
 * the density says, thinning with distance as the template's detail levels do, pressed down by snow. Validated.
 */
class BladeTest {

    private static final int SEGMENTS = 4;
    private static final VegetationGrassParameters GRASS = VegetationGrassParameters.DEFAULTS.withHeightVariation(0)
        .withClumpHeight(0).withSpacing(0.07f).withLodError(VegetationSettings.lodError(8));
    private static final VegetationWindParameters STEADY = VegetationWindParameters.DEFAULTS
        .withDirection(new Vec3(1, 0, 0)).withGustStrength(0).withTurbulence(0).withFlutter(0);

    private TestGpu gpu;
    private BladeProbe probe;

    @BeforeEach
    void start() {
        gpu = TestGpu.open();
        probe = gpu.closing(new BladeProbe(gpu.device(), 1 << 16));
    }

    @AfterEach
    void stop() {
        if (gpu != null) {
            gpu.close(); // fails on validation errors
        }
    }

    /** The centre line of blade {@code id}: its root, each segment's end and the tip. */
    private static Vertex[] centreLine(int id, float x, float z) {
        Vertex[] line = new Vertex[SEGMENTS + 1];
        for (int i = 0; i <= SEGMENTS; i++) {
            line[i] = Vertex.of(id, x, z, (float) i / SEGMENTS, 0);
        }
        return line;
    }

    private static double length(float[][] line) {
        double sum = 0;
        for (int i = 1; i < line.length; i++) {
            double dx = line[i][0] - line[i - 1][0];
            double dy = line[i][1] - line[i - 1][1];
            double dz = line[i][2] - line[i - 1][2];
            sum += Math.sqrt(dx * dx + dy * dy + dz * dz);
        }
        return sum;
    }

    /** Standing blades among {@code count} blades' tips, each as {@code vertex} makes it from its number. */
    private int standing(VegetationGrassParameters grass, VegetationFieldParameters field,
        java.util.function.IntFunction<Vertex> vertex, int count) {
        Vertex[] tips = new Vertex[count];
        for (int i = 0; i < count; i++) {
            tips[i] = vertex.apply(i);
        }
        float[][] out = probe.run(grass, null, field, SEGMENTS, tips);
        int standing = 0;
        for (float[] o : out) {
            standing += o[3] > 0 && o[1] > 0.01 ? 1 : 0;
        }
        return standing;
    }

    @Test
    void aCalmLeavesEveryBladeAtRest() {
        List<Vertex> all = new ArrayList<>();
        for (int b = 0; b < 64; b++) {
            for (Vertex v : centreLine(b * 7919, b * 0.13f, -b * 0.07f)) {
                all.add(v);
            }
        }
        Vertex[] vertices = all.toArray(Vertex[]::new);
        float[][] rest = probe.run(GRASS, null, null, SEGMENTS, vertices);
        float[][] calm = probe.run(GRASS, VegetationWindParameters.DEFAULTS.withSpeed(0).withTime(3.7f), null, SEGMENTS,
            vertices);
        float[][] calmLater = probe.run(GRASS, VegetationWindParameters.DEFAULTS.withSpeed(0).withTime(91.2f), null,
            SEGMENTS, vertices);
        float[][] breeze = probe.run(GRASS, STEADY.withSpeed(3).withTime(3.7f), null, SEGMENTS, vertices);
        for (int i = 0; i < vertices.length; i++) {
            assertThat(calm[i]).as("vertex %d in a calm, as without wind", i).containsExactly(rest[i]);
            assertThat(calmLater[i]).as("vertex %d in a calm later", i).containsExactly(rest[i]);
        }
        double moved = 0;
        for (int i = 0; i < vertices.length; i++) {
            moved = Math.max(moved, Math.abs(breeze[i][0] - rest[i][0]));
        }
        assertThat(moved).as("a breeze moves them").isGreaterThan(0.01);
    }

    @Test
    void bladesKeepTheirLengthHoweverTheWindBendsThem() {
        for (int b = 0; b < 16; b++) {
            Vertex[] line = centreLine(b * 104729 + 3, b * 0.31f, b * 0.17f);
            double rest = length(probe.run(GRASS, null, null, SEGMENTS, line));
            assertThat(rest).as("blade %d stands", b).isGreaterThan(0.2);
            for (float speed : new float[] {1, 4, 9, 16, 30}) {
                for (float time : new float[] {0, 0.37f, 5.1f}) {
                    VegetationWindParameters wind = VegetationWindParameters.DEFAULTS.withSpeed(speed).withTime(time)
                        .withDirection(new Vec3(0.6, 0, 0.8));
                    double bent = length(probe.run(GRASS, wind, null, SEGMENTS, line));
                    assertThat(bent).as("blade %d at %s m/s, %s s", b, speed, time).isCloseTo(rest, within(rest * 2e-4));
                }
            }
        }
    }

    @Test
    void theSteadyLeanGrowsWithTheSpeedSquared() {
        // A straight blade (no rest lean or curve), no gusts, sway or flutter: the tip moves downwind by the lean
        VegetationGrassParameters straight = GRASS.withLean(0).withCurvature(0);
        Vertex[] line = centreLine(12345, 0, 0);
        float[][] at2 = probe.run(straight, STEADY.withSpeed(2), null, SEGMENTS, line);
        float[][] at4 = probe.run(straight, STEADY.withSpeed(4), null, SEGMENTS, line);
        float[][] calm = probe.run(straight, null, null, SEGMENTS, line);
        double tip2 = at2[SEGMENTS][0] - calm[SEGMENTS][0];
        double tip4 = at4[SEGMENTS][0] - calm[SEGMENTS][0];
        assertThat(tip2).as("downwind at 2 m/s").isGreaterThan(0.001);
        assertThat(tip4 / tip2).as("twice the speed, four times the lean").isCloseTo(4, within(0.4));
        assertThat((double) at4[SEGMENTS][2] - calm[SEGMENTS][2]).as("nothing across the wind").isCloseTo(0,
            within(1e-4));
        // A gale lays it over but never under the ground
        float[][] gale = probe.run(straight, STEADY.withSpeed(40), null, SEGMENTS, line);
        assertThat((double) gale[SEGMENTS][1]).as("the tip stays above the root").isGreaterThan(0);
        assertThat(gale[SEGMENTS][0]).as("lying downwind").isGreaterThan(at4[SEGMENTS][0]);
    }

    @Test
    void normalsAreUnitAndAcrossTheBlade() {
        Vertex[] line = centreLine(777, 1, 2);
        float[][] out = probe.run(GRASS.withRoundness(0), STEADY.withSpeed(6).withTurbulence(0.3f), null, SEGMENTS,
            line);
        for (int i = 0; i <= SEGMENTS; i++) {
            double nx = out[i][4];
            double ny = out[i][5];
            double nz = out[i][6];
            assertThat(Math.sqrt(nx * nx + ny * ny + nz * nz)).as("unit, vertex %d", i).isCloseTo(1, within(1e-4));
            if (i == 0 || i == SEGMENTS) {
                continue;
            }
            // the curve's tangent at the vertex, by its neighbours
            double tx = out[i + 1][0] - out[i - 1][0];
            double ty = out[i + 1][1] - out[i - 1][1];
            double tz = out[i + 1][2] - out[i - 1][2];
            double t = Math.sqrt(tx * tx + ty * ty + tz * tz);
            assertThat(Math.abs(nx * tx + ny * ty + nz * tz) / t).as("across the blade, vertex %d", i)
                .isLessThan(0.05);
        }
    }

    @Test
    void asManyBladesStandAsTheDensitySays() {
        int count = 8000;
        for (float density : new float[] {0.1f, 0.25f, 0.5f, 1f}) {
            int standing = standing(GRASS.withDensity(density), null,
                i -> Vertex.of(i, (i % 89) * 0.07f, (i / 89) * 0.07f, 1, 0), count);
            assertThat(standing / (double) count).as("density %s", density).isCloseTo(density, within(0.02));
        }
        assertThat(standing(GRASS.withDensity(0), null, i -> Vertex.of(i, i * 0.01f, 0, 1, 0), 1000)).as("none")
            .isZero();
    }

    @Test
    void bladesOutsideTheFieldsBoundsDoNotStand() {
        VegetationGrassParameters bounded = GRASS.withBoundsMin(new com.truesim.common.math.Vec2(0, 0))
            .withBoundsMax(new com.truesim.common.math.Vec2(10, 10)).withEdge(0.01f).withClumpPull(0);
        assertThat(standing(bounded, null, i -> Vertex.of(i, 5 + (i % 30) * 0.05f, 5, 1, 0), 600)).as("inside")
            .isEqualTo(600);
        assertThat(standing(bounded, null, i -> Vertex.of(i, 11 + (i % 30) * 0.05f, 5, 1, 0), 600)).as("outside")
            .isZero();
    }

    @Test
    void detailThinsBladesWithDistanceAsTheTemplatesLevelsDo() {
        // The engine's rule at 1080 lines, 60 degrees, a pixel: the first coarser level from 8 m
        VegetationFieldParameters field = VegetationFieldParameters.DEFAULTS.withViewerOn(1)
            .withProjection(VegetationSettings.REFERENCE_PROJECTION).withErrorPixels(1).withRadius(10_000);
        int count = 4096;
        double first = 8;
        double previous = 1.01;
        for (double d : new double[] {2, 3.9, 6, 8, 12, 16, 32, 64}) {
            int standing = standing(GRASS, field, i -> Vertex.of(i, 0, 0, 1, 0).viewer(d)
                .rank((i + 0.5f) / count), count);
            double share = standing / (double) count;
            double expected = Math.min(1, Math.pow(first / (2 * d), 2));
            assertThat(share).as("standing at %s m", d).isCloseTo(expected, within(0.02));
            assertThat(share).as("falling with distance, at %s m", d).isLessThanOrEqualTo(previous);
            previous = share;
        }
        // Where level k takes over (at 8 * 2^(k-1) m), no blade it lacks still stands: none of rank 4^-k or more
        for (int level = 1; level <= 3; level++) {
            double switchAt = first * Math.pow(2, level - 1);
            double kept = GrassPatch.share(level);
            int lacking = standing(GRASS, field, i -> Vertex.of(i, 0, 0, 1, 0).viewer(switchAt)
                .rank((float) (kept + (1 - kept) * (i + 0.5) / count)), count);
            assertThat(lacking).as("blades level %d lacks, standing at %s m", level, switchAt).isZero();
        }
        // And the grass ends at the radius
        assertThat(standing(GRASS, field.withRadius(50), i -> Vertex.of(i, 0, 0, 1, 0).viewer(50).rank(0), 100))
            .as("at the radius").isZero();
    }

    @Test
    void snowPressesBladesDown() {
        Vertex[] line = centreLine(4242, 0, 0);
        float[][] bare = probe.run(GRASS, null, VegetationFieldParameters.DEFAULTS, SEGMENTS, line);
        float[][] snowed = probe.run(GRASS, null, VegetationFieldParameters.DEFAULTS.withSnowDepth(0.2f), SEGMENTS,
            line);
        float[][] buried = probe.run(GRASS, null, VegetationFieldParameters.DEFAULTS.withSnowDepth(1f), SEGMENTS, line);
        assertThat(snowed[SEGMENTS][1]).as("lower under 20 cm of snow").isLessThan(bare[SEGMENTS][1] * 0.6f);
        assertThat(buried[SEGMENTS][1]).as("flat under a metre").isLessThan(0.12f);
    }
}
