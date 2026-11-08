package com.truesim.effects.vegetation;

import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.VK10.VK_QUERY_RESULT_64_BIT;
import static org.lwjgl.vulkan.VK10.VK_QUERY_RESULT_WAIT_BIT;
import static org.lwjgl.vulkan.VK10.VK_QUERY_TYPE_TIMESTAMP;
import static org.lwjgl.vulkan.VK10.VK_STRUCTURE_TYPE_QUERY_POOL_CREATE_INFO;
import static org.lwjgl.vulkan.VK10.vkCmdResetQueryPool;
import static org.lwjgl.vulkan.VK10.vkCreateQueryPool;
import static org.lwjgl.vulkan.VK10.vkDestroyQueryPool;
import static org.lwjgl.vulkan.VK10.vkGetPhysicalDeviceProperties;
import static org.lwjgl.vulkan.VK10.vkGetQueryPoolResults;
import static org.lwjgl.vulkan.VK13.VK_PIPELINE_STAGE_2_ALL_COMMANDS_BIT;
import static org.lwjgl.vulkan.VK13.vkCmdWriteTimestamp2;

import com.truesim.common.math.Vec3;
import com.truesim.effects.vegetation.materials.VegetationWindParameters;
import com.truesim.engine.graph.GpuTimings;
import com.truesim.engine.render.MeshletRenderer;
import com.truesim.engine.vulkan.VulkanDevice;
import com.truesim.engine.vulkan.VulkanInstance;
import java.nio.LongBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkPhysicalDeviceProperties;

/**
 * What a meadow to the horizon costs: 1920 x 1080, the eye 1.7 m over level ground looking at the horizon, grass of
 * the default template (160 blades a square metre at full detail, 4 segments, 4 levels) over the settings' radius, the
 * sun by 4 cascades of 2048, shaded by the visibility buffer (and forward shading), against the ground alone. GPU time
 * of whole frames by timestamps round batches of 8 frames, the configurations taking turns for 5 rounds after 2
 * uncounted ones; the ranges of the rounds' medians. {@code TRUESIM_PASSES=true} prints where each frame's time goes.
 *
 * <p>Run with {@code TRUESIM_BENCHMARK=true ./gradlew :effects-vegetation:test --tests '*VegetationBenchmark'}.
 */
@EnabledIfEnvironmentVariable(named = "TRUESIM_BENCHMARK", matches = "true")
class VegetationBenchmark {

    private static final int WIDTH = 1920;
    private static final int HEIGHT = 1080;
    private static final int BATCH = 8;
    private static final int BATCHES = 9;
    private static final int ROUNDS = 5;
    private static final VegetationWindParameters GUSTY = VegetationWindParameters.DEFAULTS
        .withDirection(new Vec3(1, 0, -0.35)).withSpeed(8).withGustStrength(0.9f).withTurbulence(0.3f)
        .withFlutter(0.1f);

    /** A configuration: its name and its meadow. */
    private record Configuration(String name, Meadow meadow) {
    }

    private static Meadow meadow(VulkanDevice device, boolean grass, boolean visibility, float radius,
        float density, int trees, VegetationWindParameters wind) {
        Meadow m = new Meadow(device, WIDTH, HEIGHT, Meadow::flat, 2000);
        m.graph.settings().set(VegetationSettings.RADIUS, radius);
        m.graph.settings().set(VegetationSettings.DENSITY, density);
        if (grass) {
            m.grass(GrassPatch.of(m.graph.settings()), VegetationFilms.meadowGrass(), 0, 0, radius);
        }
        if (trees > 0) {
            int[] tree = m.treeMeshAndMaterials(30);
            java.util.SplittableRandom random = new java.util.SplittableRandom(5);
            for (int i = 0; i < trees; i++) {
                double x = random.nextDouble(-150, 150);
                double z = random.nextDouble(-300, -15);
                m.tree(x, z, (float) random.nextDouble(0.7, 1.3), tree[0], tree[1]);
            }
        }
        m.look(0, 1.7, 0, 0, -3, 60).shade(visibility, true).wind(wind);
        return m;
    }

    @Test
    void meadowToTheHorizon() {
        try (VulkanInstance instance = VulkanInstance.builder().applicationName("vegetation benchmark")
                .surfaces(false).build();
             VulkanDevice device = VulkanDevice.createHeadless(instance.bestGpu());
             MemoryStack stack = stackPush()) {
            List<Configuration> configurations = new ArrayList<>();
            configurations.add(new Configuration("ground only", meadow(device, false, true, 110, 160, 0, GUSTY)));
            configurations.add(new Configuration("meadow 110 m, calm", meadow(device, true, true, 110, 160, 0,
                GUSTY.withSpeed(0))));
            configurations.add(new Configuration("meadow 110 m, gusts", meadow(device, true, true, 110, 160, 0,
                GUSTY)));
            configurations.add(new Configuration("meadow 110 m, gusts, forward", meadow(device, true, false, 110,
                160, 0, GUSTY)));
            configurations.add(new Configuration("meadow 110 m, gusts, 120 spruces", meadow(device, true, true, 110,
                160, 120, GUSTY)));
            configurations.add(new Configuration("meadow 250 m, gusts", meadow(device, true, true, 250, 160, 0,
                GUSTY)));
            configurations.add(new Configuration("meadow 110 m, 320 blades/m2, gusts", meadow(device, true, true, 110,
                320, 0, GUSTY)));
            VkPhysicalDeviceProperties properties = VkPhysicalDeviceProperties.malloc(stack);
            vkGetPhysicalDeviceProperties(device.gpu().handle(), properties);
            double period = properties.limits().timestampPeriod();
            LongBuffer handle = stack.mallocLong(1);
            vkCreateQueryPool(device.handle(), org.lwjgl.vulkan.VkQueryPoolCreateInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_QUERY_POOL_CREATE_INFO).queryType(VK_QUERY_TYPE_TIMESTAMP).queryCount(2),
                null, handle);
            long queries = handle.get(0);
            System.out.printf(Locale.ROOT, "Vegetation on %s, %d x %d%n", device.gpu().name(), WIDTH, HEIGHT);
            double[][] ms = new double[configurations.size()][ROUNDS];
            for (int round = -2; round < ROUNDS; round++) {
                for (int c = 0; c < configurations.size(); c++) {
                    double frame = measure(device, configurations.get(c).meadow(), queries, period);
                    if (round >= 0) {
                        ms[c][round] = frame;
                    }
                }
            }
            for (int c = 0; c < configurations.size(); c++) {
                Configuration configuration = configurations.get(c);
                double[] m = ms[c].clone();
                Arrays.sort(m);
                System.out.printf(Locale.ROOT, "  %-40s %.3f-%.3f ms  (%d patches, %d meshlets drawn)%n",
                    configuration.name(), m[0], m[ROUNDS - 1], configuration.meadow().patches(),
                    configuration.meadow().view.view().visibleMeshlets());
                if (System.getenv("TRUESIM_PASSES") != null) {
                    passes(device, configuration);
                }
            }
            vkDestroyQueryPool(device.handle(), queries, null);
            for (Configuration configuration : configurations) {
                configuration.meadow().close();
            }
        }
    }

    /** The median GPU time of a frame of {@code meadow}, over batches of frames recorded back to back. */
    private static double measure(VulkanDevice device, Meadow meadow, long queries, double period) {
        for (int i = 0; i < 4; i++) {
            meadow.hooks.update(1 / 60.0, meadow.graph.settings());
            meadow.view.set(1, MeshletRenderer.MODE_LIT);
            device.submitAndWait(device.graphics(), meadow.graph::record);
        }
        double[] gpu = new double[BATCHES];
        Consumer<org.lwjgl.vulkan.VkCommandBuffer> batch = commands -> {
            vkCmdResetQueryPool(commands, queries, 0, 2);
            vkCmdWriteTimestamp2(commands, VK_PIPELINE_STAGE_2_ALL_COMMANDS_BIT, queries, 0);
            for (int i = 0; i < BATCH; i++) {
                meadow.hooks.update(1 / 60.0, meadow.graph.settings());
                meadow.view.set(1, MeshletRenderer.MODE_LIT);
                meadow.graph.record(commands);
            }
            vkCmdWriteTimestamp2(commands, VK_PIPELINE_STAGE_2_ALL_COMMANDS_BIT, queries, 1);
        };
        for (int b = 0; b < BATCHES; b++) {
            device.submitAndWait(device.graphics(), batch::accept);
            try (MemoryStack stack = stackPush()) {
                LongBuffer stamps = stack.mallocLong(2);
                vkGetQueryPoolResults(device.handle(), queries, 0, 2, stamps, 8,
                    VK_QUERY_RESULT_64_BIT | VK_QUERY_RESULT_WAIT_BIT);
                gpu[b] = (stamps.get(1) - stamps.get(0)) * period / 1e6 / BATCH;
            }
        }
        if (meadow.renderer.chunksOverflowed() || meadow.view.view().overflowed()) {
            throw new IllegalStateException("Culling ran out of room: the frames drew less than the meadow");
        }
        Arrays.sort(gpu);
        return gpu[BATCHES / 2];
    }

    private static void passes(VulkanDevice device, Configuration configuration) {
        Meadow meadow = configuration.meadow();
        meadow.graph.timings(true);
        for (int i = 0; i < 8; i++) {
            meadow.hooks.update(1 / 60.0, meadow.graph.settings());
            meadow.view.set(1, MeshletRenderer.MODE_LIT);
            device.submitAndWait(device.graphics(), meadow.graph::record);
        }
        GpuTimings timings = meadow.graph.timings();
        StringBuilder line = new StringBuilder("      passes:");
        for (int i = 0; i < timings.count(); i++) {
            if (timings.millis(i) >= 0.03) {
                line.append(String.format(Locale.ROOT, " %s %.3f;", timings.pass(i), timings.millis(i)));
            }
        }
        System.out.println(line);
        meadow.graph.timings(false);
    }
}
