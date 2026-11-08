package com.truesim.effects.vegetation;

import com.truesim.common.math.Maths;

/**
 * The ground vegetation stands on: its height, slope and how densely things grow, at world x and z (metres), for
 * placing patches and plants on the CPU, and the same as a {@link GroundMap} for the blades on the GPU. A terrain
 * library implements it over its heightfield (effects-terrain); {@link #FLAT} is level ground at 0 everywhere.
 *
 * <p>Read on the scene's thread, as patches and plants are placed.
 */
public interface VegetationGround {

    /** Level ground at height 0, growing everywhere. */
    VegetationGround FLAT = (x, z) -> 0;

    /** The ground's height at world x, z. */
    double height(double x, double z);

    /** How densely vegetation grows at world x, z: 0 nothing, 1 as its field or scatter says. */
    default double density(double x, double z) {
        return 1;
    }

    /** The slope at world x, z: degrees from level, by central differences over {@code step} metres. */
    default double slope(double x, double z, double step) {
        double dx = (height(x + step, z) - height(x - step, z)) / (2 * step);
        double dz = (height(x, z + step) - height(x, z - step)) / (2 * step);
        return (180 / Math.PI) * (Maths.atan(Math.sqrt(dx * dx + dz * dz)));
    }

    /** The ground on the GPU, for blades standing on it and thinning with its density; null: each patch's plane. */
    default GroundMap map() {
        return null;
    }
}
