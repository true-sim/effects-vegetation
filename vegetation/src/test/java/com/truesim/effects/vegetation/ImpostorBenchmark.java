package com.truesim.effects.vegetation;

import com.truesim.common.procedural.assets.ModelDetail;
import com.truesim.engine.settings.EngineSettings;
import com.truesim.engine.vulkan.VulkanDevice;
import com.truesim.engine.vulkan.VulkanInstance;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Locale;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/**
 * A forest's frame on the real GPU, by how its trees carry their detail: models flat (common-procedural's level 1,
 * drawn whole), as cluster hierarchies from level 1 or from level 0, and hierarchies from level 1 with impostors past
 * the settings' distance. 8,000 trees over 1.2 km a side, the camera in a clearing in its middle at 1920 x 1080,
 * looking along the forest and over it: the median and the slowest tenth of 120 frames (each recorded, submitted and
 * waited for), the meshlets the view draws, and how long the bakes took.
 *
 * <p>Run with {@code TRUESIM_BENCHMARK=true ./gradlew :effects-vegetation:test --tests '*ImpostorBenchmark'}, holding
 * the GPU (nothing else timed on it).
 */
@EnabledIfEnvironmentVariable(named = "TRUESIM_BENCHMARK", matches = "true")
class ImpostorBenchmark {

    @Test
    void forests() throws Exception {
        VulkanInstance instance = VulkanInstance.builder().applicationName("impostor benchmark").surfaces(false).build();
        VulkanDevice device = VulkanDevice.createHeadless(instance.bestGpu());
        System.out.println("impostor benchmark on " + instance.bestGpu().name());
        Path cache = Path.of(System.getProperty("java.io.tmpdir"), "truesim-forest-cache");
        Files.createDirectories(cache);
        try {
            run(device, cache, "flat, level 0 (before)", ModelDetail.FLAT, false);
            run(device, cache, "flat, level 1", new ModelDetail(false, 1, 0), false);
            run(device, cache, "hierarchy, level 1", ModelDetail.DEFAULT, false);
            run(device, cache, "hierarchy, level 0", new ModelDetail(true, 0, 0), false);
            run(device, cache, "hierarchy, level 1, impostors", ModelDetail.DEFAULT, true);
        } finally {
            device.close();
            instance.close();
        }
    }

    private static void run(VulkanDevice device, Path cache, String name, ModelDetail detail, boolean impostors)
        throws Exception {
        EngineSettings settings = new EngineSettings();
        long start = System.nanoTime();
        try (Forest forest = new Forest(device, 1920, 1080, settings, cache, 8000, 1200, 40, impostors, detail, 21)) {
            double[][] views = {{0, 4, 0, 0, 1}, {0, 40, 0, 30, -8}};
            forest.look(0, 4, 0, 0, 1, 60);
            forest.settle(10);
            double setup = (System.nanoTime() - start) / 1e9;
            StringBuilder line = new StringBuilder(String.format(Locale.ROOT, "%-32s set up %5.1f s, %d bakes",
                name, setup, forest.baker.bakes()));
            for (double[] v : views) {
                forest.look(v[0], v[1], v[2], v[3], v[4], 60);
                for (int i = 0; i < 30; i++) {
                    forest.frame(1 / 60.0);
                }
                double[] ms = new double[120];
                for (int i = 0; i < ms.length; i++) {
                    long t = System.nanoTime();
                    forest.frame(1 / 60.0);
                    ms[i] = (System.nanoTime() - t) / 1e6;
                }
                Arrays.sort(ms);
                line.append(String.format(Locale.ROOT, " | view %s m: median %6.2f ms, slowest tenth %6.2f ms, %d "
                    + "meshlets", (int) v[1], ms[60], ms[108], forest.view.view().visibleMeshlets()));
            }
            line.append(String.format(Locale.ROOT, " | %d trees: %d models, %d impostors", forest.trees,
                forest.vegetation.plants(), forest.vegetation.impostorCount()));
            System.out.println("BENCH " + line);
        }
    }
}
