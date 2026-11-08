package com.truesim.effects.vegetation;

import com.truesim.annotations.AllowAllocation;
import com.truesim.effects.vegetation.materials.VegetationPlant;
import com.truesim.effects.vegetation.materials.VegetationPlantParameters;
import com.truesim.engine.render.Material;

/** Plant materials: the plant type's parameters with the displacement their sway reaches at most. */
public final class PlantMaterials {

    private PlantMaterials() {
    }

    /**
     * The farthest {@code p} moves a vertex in a wind of {@code maxWind} metres a second, mesh units: the trunk's lean
     * at its top, a branch's swing, a leaf's flutter.
     */
    public static float displacement(VegetationPlantParameters p, float maxWind) {
        double lean = Math.min(p.maxLean(), p.trunkBend() * maxWind * maxWind * 2.5 * (1 + p.trunkSway()));
        double trunk = p.height() * 2 * Math.sin(lean / 2); // the chord of the top's arc
        double branch = p.branchSway() * maxWind * Math.sqrt(1 + 0.36);
        double leaf = p.leafFlutter() * maxWind;
        return (float) (trunk + branch + leaf + 1e-3);
    }

    /** A plant material of {@code p}, bounded for winds up to {@code maxWind} metres a second. */
    @AllowAllocation("made as plants are set up")
    public static Material material(VegetationPlantParameters p, float maxWind) {
        return new Material(VegetationPlant.TYPE, p).displacement(displacement(p, maxWind));
    }
}
