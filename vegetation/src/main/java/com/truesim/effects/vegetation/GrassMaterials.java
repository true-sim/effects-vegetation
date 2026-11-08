package com.truesim.effects.vegetation;

import com.truesim.annotations.AllowAllocation;
import com.truesim.common.math.color.Color;
import com.truesim.effects.vegetation.materials.VegetationGrass;
import com.truesim.effects.vegetation.materials.VegetationGrassParameters;
import com.truesim.engine.render.Material;
import com.truesim.engine.render.materials.DiffuseTransmissionParameters;

/**
 * Grass materials over a patch template: the type's parameters completed with what the template was built with, the
 * displacement their blades reach at most (the bounds culling grows by), drawn two-sided, and lit through from behind.
 */
public final class GrassMaterials {

    /** How much of a blade's diffuse light passes through it, by default. */
    public static final float TRANSMISSION = 0.4f;
    /** The light through a blade, by default: a warm green. */
    public static final Color TRANSMISSION_COLOR = new Color(0.55f, 0.75f, 0.2f, 1);

    private GrassMaterials() {
    }

    /** {@code p} with the template's size, spacing and first level's error, which blades are made by. */
    public static VegetationGrassParameters forTemplate(VegetationGrassParameters p, GrassPatch patch) {
        return p.withPatchSize(patch.size()).withSpacing(patch.spacing()).withLodError(patch.error());
    }

    /**
     * The farthest a blade's vertex moves from its slot in the template: the roots' jitter and clump pull, the longest
     * blade in any direction, its half width widened, and the ground's {@code relief} within a patch.
     */
    public static float displacement(VegetationGrassParameters p, GrassPatch patch, float relief, float maxWiden) {
        double jitter = (0.35 + 0.5) * patch.spacing() * Math.sqrt(2);
        double pull = p.clumpPull() * p.clumpSize() * 1.5;
        double length = p.height() * (1 + p.heightVariation()) * (1 + p.clumpHeight());
        double width = 0.5 * p.width() * 1.25 * maxWiden;
        return (float) (jitter + pull + length + width + relief);
    }

    /** A grass material of {@code p} over {@code patch}'s template. */
    @AllowAllocation("made as a field is set up")
    public static Material material(VegetationGrassParameters p, GrassPatch patch, float relief, float maxWiden) {
        VegetationGrassParameters full = forTemplate(p, patch);
        return new Material(VegetationGrass.TYPE, full).doubleSided(true)
            .displacement(displacement(full, patch, relief, maxWiden))
            .feature(DiffuseTransmissionParameters.DEFAULTS.withDiffuseTransmissionFactor(TRANSMISSION)
                .withDiffuseTransmissionColorFactor(TRANSMISSION_COLOR));
    }
}
