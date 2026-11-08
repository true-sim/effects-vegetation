package com.truesim.effects.vegetation;

import com.truesim.annotations.AllowAllocation;
import com.truesim.engine.render.TextureFormat;
import com.truesim.engine.render.Textures;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.Objects;

/**
 * The ground as grass blades read it on the GPU (vegetation.field's ground map, blade.slang): a square texture over
 * world x and z of half floats, red and green the height over {@code base} in two parts (red twice a whole number of
 * metres, exact to 4,096 m; green the rest, within half a millimetre), blue the density (0 to 1). Sampled bilinearly,
 * which the two parts survive, as each is interpolated alike.
 *
 * @param slot    its sampled slot in the bindless set
 * @param originX the world x of its corner (texel 0's outer edge)
 * @param originZ the world z of its corner
 * @param size    metres it covers, a side
 * @param base    the height its texels count from
 * @param texels  texels a side
 */
public record GroundMap(int slot, double originX, double originZ, double size, float base, int texels) {

    public GroundMap {
        if (slot < 0 || !(size > 0) || texels < 2) {
            throw new IllegalArgumentException("A ground map needs a slot, a size and texels: " + slot + ", " + size
                + ", " + texels);
        }
    }

    /** Metres per texel. */
    public double texel() {
        return size / texels;
    }

    /**
     * Samples {@code ground} at the middles of {@code texels} x {@code texels} texels over {@code size} metres from world
     * ({@code originX}, {@code originZ}) and adds the map to {@code textures}. The caller removes its slot from them when
     * it is done with it.
     */
    @AllowAllocation("made where the ground is set up")
    public static GroundMap upload(Textures textures, VegetationGround ground, double originX, double originZ,
        double size, int texels) {
        Objects.requireNonNull(ground, "ground");
        double texel = size / texels;
        float[] heights = new float[texels * texels];
        float min = Float.MAX_VALUE;
        for (int z = 0; z < texels; z++) {
            for (int x = 0; x < texels; x++) {
                float h = (float) ground.height(originX + (x + 0.5) * texel, originZ + (z + 0.5) * texel);
                heights[z * texels + x] = h;
                min = Math.min(min, h);
            }
        }
        float base = (float) Math.floor(min);
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment data = arena.allocate(8L * texels * texels, 8);
            for (int z = 0; z < texels; z++) {
                for (int x = 0; x < texels; x++) {
                    int i = z * texels + x;
                    double over = heights[i] - base;
                    if (over > 4096) {
                        throw new IllegalArgumentException("The ground rises more than 4,096 m over its lowest point");
                    }
                    double whole = Math.floor(over / 2);
                    long o = 8L * i;
                    data.set(ValueLayout.JAVA_SHORT_UNALIGNED, o, Float.floatToFloat16((float) whole));
                    data.set(ValueLayout.JAVA_SHORT_UNALIGNED, o + 2, Float.floatToFloat16((float) (over - 2 * whole)));
                    float density = (float) Math.clamp(ground.density(originX + (x + 0.5) * texel,
                        originZ + (z + 0.5) * texel), 0, 1);
                    data.set(ValueLayout.JAVA_SHORT_UNALIGNED, o + 4, Float.floatToFloat16(density));
                    data.set(ValueLayout.JAVA_SHORT_UNALIGNED, o + 6, Float.floatToFloat16(1f));
                }
            }
            int slot = textures.add(TextureFormat.RGBA16F, texels, texels, 1, data);
            return new GroundMap(slot, originX, originZ, size, base, texels);
        }
    }
}
