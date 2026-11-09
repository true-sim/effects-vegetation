package com.truesim.effects.vegetation;

import static org.assertj.core.api.Assertions.assertThat;

import com.truesim.common.procedural.assets.ModelDetail;
import com.truesim.engine.settings.EngineSettings;
import com.truesim.engine.vulkan.testing.TestGpu;
import java.awt.image.BufferedImage;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A forest of common-procedural's trees through the scene, its far trees impostors the sync baked through the engine:
 * near trees are their models, far ones a quad each, and the far forest looks as it does drawn whole (its coverage and
 * its brightness), while the view moves through the band nothing pops (no frame loses trees the one before had).
 */
class ForestImpostorTest {

    private static final int WIDTH = 256;
    private static final int HEIGHT = 144;

    @TempDir
    Path cache;
    private TestGpu gpu;

    @BeforeEach
    void start() {
        gpu = TestGpu.open();
    }

    @AfterEach
    void stop() {
        if (gpu != null) {
            gpu.close();
        }
    }

    private EngineSettings settings() {
        return new EngineSettings().set(VegetationSettings.IMPOSTOR_DISTANCE, 60f)
            .set(VegetationSettings.IMPOSTOR_FADE, 20f).set(VegetationSettings.IMPOSTOR_RESOLUTION, 48)
            .set(VegetationSettings.IMPOSTOR_BUDGET, 8).set(VegetationSettings.PLANT_BUDGET, 4096);
    }

    /** Pixels whose color is not the sky's (the forest and the ground differ from it): rows above the horizon. */
    private static double[] forest(BufferedImage image) {
        int covered = 0;
        double light = 0;
        for (int y = 0; y < HEIGHT / 2; y++) {
            for (int x = 0; x < WIDTH; x++) {
                int p = image.getRGB(x, y);
                int r = p >> 16 & 255;
                int g = p >> 8 & 255;
                int b = p & 255;
                if (b < r + g) { // the sky is blue; trees are green and brown
                    covered++;
                    light += r + g + b;
                }
            }
        }
        return new double[] {covered, covered == 0 ? 0 : light / covered};
    }

    @Test
    void farTreesAreImpostorsThatLookAsTheForestDoes() throws InterruptedException {
        double[][] seen = new double[2][];
        for (int run = 0; run < 2; run++) {
            boolean impostors = run == 1;
            try (Forest forest = new Forest(gpu.device(), WIDTH, HEIGHT, settings(), cache, 400, 400, 0, impostors,
                ModelDetail.DEFAULT, 5)) {
                forest.look(0, 3, 230, 0, 2, 50);
                forest.settle(4);
                System.out.println("FOREST impostors=" + impostors + " bakes=" + forest.baker.bakes() + " imp=" + forest.vegetation.impostorCount() + " plants=" + forest.vegetation.plants() + " trees=" + forest.trees);
                if (impostors) {
                    assertThat(forest.baker.bakes()).as("a bake a model").isEqualTo(6);
                    assertThat(forest.vegetation.impostorCount()).as("far trees as impostors").isGreaterThan(200);
                    assertThat(forest.vegetation.plants()).as("near trees as models").isBetween(1, 200);
                } else {
                    assertThat(forest.vegetation.impostorCount()).isZero();
                    assertThat(forest.vegetation.plants()).isEqualTo(forest.trees);
                }
                BufferedImage shot = forest.shot();
                GrassRenderTest.keep(shot, "forest-" + (impostors ? "impostors" : "models"));
                seen[run] = forest(shot);
            }
        }
        assertThat(seen[0][0]).as("the forest seen").isGreaterThan(WIDTH * HEIGHT / 20.0);
        assertThat(seen[1][0] / seen[0][0]).as("as much of it with impostors").isBetween(0.85, 1.15);
        assertThat(seen[1][1] / seen[0][1]).as("as bright").isBetween(0.8, 1.25);
    }

    @Test
    void movingThroughTheBandNothingPops() throws InterruptedException {
        try (Forest forest = new Forest(gpu.device(), WIDTH, HEIGHT, settings(), cache, 400, 400, 0, true,
            ModelDetail.DEFAULT, 9)) {
            forest.look(0, 3, 280, 0, 2, 50);
            forest.settle(4);
            double before = forest(forest.shot())[0];
            double least = 1;
            for (int f = 0; f < 90; f++) {
                forest.look(0, 3, 280 - f, 0, 2, 50);
                forest.frame(1 / 30.0);
                double now = forest(forest.shot())[0];
                least = Math.min(least, now / before);
                before = now;
            }
            assertThat(least).as("the forest's coverage from frame to frame").isGreaterThan(0.97);
        }
    }
}
