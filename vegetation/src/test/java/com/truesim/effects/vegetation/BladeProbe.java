package com.truesim.effects.vegetation;

import static org.lwjgl.vulkan.VK10.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT;
import static org.lwjgl.vulkan.VK12.VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT;

import com.truesim.effects.vegetation.materials.ProbePush;
import com.truesim.effects.vegetation.materials.VegetationFieldParameters;
import com.truesim.effects.vegetation.materials.VegetationGrassParameters;
import com.truesim.effects.vegetation.materials.VegetationWindParameters;
import com.truesim.engine.vulkan.GpuBuffer;
import com.truesim.engine.vulkan.Pipeline;
import com.truesim.engine.vulkan.ShaderModule;
import com.truesim.engine.vulkan.VulkanDevice;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;

/**
 * Evaluates blade.slang's {@code bladeVertex} on the GPU for listed vertices (test/probe.slang): what the grass type's
 * displace() computes, read back. A vertex is its root (world x, z and the plane's height), its place along and across
 * its blade, its blade's number, rank and patch cell, and where the field's viewer is from its root.
 */
final class BladeProbe implements AutoCloseable {

    private static final int USAGE = VK_BUFFER_USAGE_STORAGE_BUFFER_BIT | VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT;
    private static final ValueLayout.OfFloat F32 = ValueLayout.JAVA_FLOAT_UNALIGNED;

    /** A vertex to evaluate. */
    record Vertex(double viewerX, double viewerY, double viewerZ, float along, float rootX, float rootZ,
        float rootHeight, float side, int cellX, int cellZ, float rank, int id) {

        /** Vertex {@code along} of blade {@code id} at the root (x, z), rank 0, cell 0, the viewer far off. */
        static Vertex of(int id, float rootX, float rootZ, float along, float side) {
            return new Vertex(0, 0, 0, along, rootX, rootZ, 0, side, 0, 0, 0, id);
        }

        Vertex viewer(double distance) {
            return new Vertex(distance, 0, 0, along, rootX, rootZ, rootHeight, side, cellX, cellZ, rank, id);
        }

        Vertex rank(float r) {
            return new Vertex(viewerX, viewerY, viewerZ, along, rootX, rootZ, rootHeight, side, cellX, cellZ, r, id);
        }
    }

    private final VulkanDevice device;
    private final ShaderModule shaders;
    private final Pipeline pipeline;
    private final GpuBuffer records;
    private final int capacity;
    private final GpuBuffer inputs;
    private final GpuBuffer outputs;

    BladeProbe(VulkanDevice device, int capacity) {
        this.device = device;
        this.capacity = capacity;
        shaders = ShaderModule.load(device, "shaders/vegetation/test/probe.spv");
        pipeline = Pipeline.compute(device, shaders, "probeMain", ProbePush.BYTES);
        records = GpuBuffer.hostVisible(device, 4096, USAGE);
        inputs = GpuBuffer.hostVisible(device, 48L * capacity, USAGE);
        outputs = GpuBuffer.hostVisible(device, 32L * capacity, USAGE);
    }

    /**
     * Evaluates {@code vertices} of blades of {@code segments} segments: per vertex its offset from the root (x, y, z),
     * the blade's scale, and its normal (x, y, z), 7 floats each.
     */
    float[][] run(VegetationGrassParameters grass, VegetationWindParameters wind, VegetationFieldParameters field,
        int segments, Vertex... vertices) {
        if (vertices.length > capacity) {
            throw new IllegalArgumentException("More vertices than room: " + vertices.length);
        }
        MemorySegment r = records.memory();
        grass.write(r, 0);
        (wind == null ? VegetationWindParameters.DEFAULTS : wind).write(r, 1024);
        (field == null ? VegetationFieldParameters.DEFAULTS : field).write(r, 2048);
        MemorySegment in = inputs.memory();
        for (int i = 0; i < vertices.length; i++) {
            Vertex v = vertices[i];
            long o = 48L * i;
            float[] values = {(float) v.viewerX(), (float) v.viewerY(), (float) v.viewerZ(), v.along(), v.rootX(),
                v.rootZ(), v.rootHeight(), v.side(), v.cellX(), v.cellZ(), v.rank(), Float.intBitsToFloat(v.id())};
            for (int k = 0; k < 12; k++) {
                in.set(F32, o + 4L * k, values[k]);
            }
        }
        long base = records.deviceAddress();
        ProbePush push = new ProbePush().grass(base).wind(base + 1024).field(base + 2048)
            .inputs(inputs.deviceAddress()).outputs(outputs.deviceAddress()).count(vertices.length)
            .windOn(wind == null ? 0 : 1).fieldOn(field == null ? 0 : 1).segments(segments);
        device.submitAndWait(device.graphics(), commands -> {
            device.bindless().bind(commands, false);
            pipeline.bind(commands);
            pipeline.push(commands, push);
            org.lwjgl.vulkan.VK10.vkCmdDispatch(commands, (vertices.length + 63) / 64, 1, 1);
        });
        float[][] result = new float[vertices.length][7];
        MemorySegment out = outputs.memory();
        for (int i = 0; i < vertices.length; i++) {
            for (int k = 0; k < 3; k++) {
                result[i][k] = out.get(F32, 32L * i + 4L * k);
                result[i][4 + k] = out.get(F32, 32L * i + 16 + 4L * k);
            }
            result[i][3] = out.get(F32, 32L * i + 12);
        }
        return result;
    }

    @Override
    public void close() {
        device.waitIdle();
        outputs.close();
        inputs.close();
        records.close();
        pipeline.close();
        shaders.close();
    }
}
