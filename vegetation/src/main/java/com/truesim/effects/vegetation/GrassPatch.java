package com.truesim.effects.vegetation;

import com.truesim.common.math.Maths;

/**
 * What a grass patch template is built from ({@link GrassPatches}): a generated mesh asset's parameters, so equal
 * parameters are one asset.
 *
 * @param size     the patch's side, metres: patches tile the ground at this pitch
 * @param density  blades per square metre at full detail
 * @param segments segments of a blade at full detail; each coarser level has one fewer (at least one)
 * @param levels   detail levels: level k keeps the blades of rank below 4^-k
 * @param error    the first coarser level's error (metres): the detail rule leaves full detail where it projects to
 *                 the allowed pixels, and each level after at twice the distance
 * @param height   the tallest a blade stands, metres: the groups' spheres reach this high
 */
public record GrassPatch(float size, float density, int segments, int levels, float error, float height) {

    public GrassPatch {
        if (!(size > 0) || !(density > 0) || !(error > 0) || !(height >= 0)) {
            throw new IllegalArgumentException("A patch needs a size, a density, an error and a height: " + size + ", "
                + density + ", " + error + ", " + height);
        }
        if (segments < 1 || segments > 8) {
            throw new IllegalArgumentException("Blades have 1 to 8 segments: " + segments);
        }
        if (levels < 1 || levels > 6) {
            throw new IllegalArgumentException("Patches have 1 to 6 levels: " + levels);
        }
        if (size * size * density > (1 << 22)) {
            throw new IllegalArgumentException("Too many blades in a patch: " + size * size * density);
        }
    }

    /** The template from the vegetation settings. */
    public static GrassPatch of(com.truesim.engine.settings.EngineSettings settings) {
        return new GrassPatch(settings.get(VegetationSettings.PATCH_SIZE), settings.get(VegetationSettings.DENSITY),
            settings.get(VegetationSettings.SEGMENTS), settings.get(VegetationSettings.LEVELS),
            VegetationSettings.lodError(settings.get(VegetationSettings.LOD_DISTANCE)),
            settings.get(VegetationSettings.TEMPLATE_HEIGHT));
    }

    /** Blades along a side: the grid the roots stand on. */
    public int grid() {
        return Math.max(1, (int) Math.ceil(size * Math.sqrt(density)));
    }

    /** Blades at full detail. */
    public int blades() {
        return grid() * grid();
    }

    /** The spacing of the roots' grid, metres. */
    public float spacing() {
        return size / grid();
    }

    /** Segments of a blade at level {@code level} (0: full detail). */
    public int segments(int level) {
        return Math.max(1, segments - level);
    }

    /** The share of blades level {@code level} keeps: 4^-level. */
    public static double share(int level) {
        return Maths.pow(0.25, level);
    }

    /** Level {@code level}'s error: 0 at full detail, then {@code error} doubling each level. */
    public double levelError(int level) {
        return level == 0 ? 0 : error * Maths.pow(2, level - 1);
    }
}
