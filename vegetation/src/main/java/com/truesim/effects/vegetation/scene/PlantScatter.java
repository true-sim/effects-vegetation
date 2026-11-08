package com.truesim.effects.vegetation.scene;

import com.truesim.scene.assets.AssetRef;
import com.truesim.scene.core.ComponentType;
import com.truesim.scene.render.MaterialData;
import com.truesim.scene.render.ModelData;
import java.util.Objects;

/**
 * Trees, shrubs or plants scattered on a node: copies of a model (an asset: a showcase's Poly Haven tree, a procedural
 * tree) over a rectangle of the ground round the node's world position, where the placement rules allow, swaying in
 * the wind. Their materials become the {@code vegetationPlant} type: each model material's values as its nested
 * metallic-roughness ({@code base.*}), with {@code sway}'s values (VegetationPlantParameters: height, radius,
 * trunkBend, branchSway, leafFlutter, windColors, ...); masked or two-sided materials count as leaves (leafWeight 1,
 * lit through from behind). Saved with the scene; the {@link VegetationSync} places them once the model has loaded.
 *
 * @param model     what is placed
 * @param halfWidth metres either side of the node along world x
 * @param halfDepth metres either side along world z
 * @param spacing   metres between plants where they stand densest: one per cell of this side, jittered in it
 * @param minScale  the smallest copy, times the model
 * @param maxScale  the largest copy
 * @param maxSlope  degrees from level past which none stands
 * @param minHeight the lowest ground (world height) any stands on
 * @param maxHeight the highest
 * @param seed      which of the possible scatters it is
 * @param sway      the plant type's values by field name (what it lacks keeps the defaults)
 */
public record PlantScatter(AssetRef<ModelData> model, double halfWidth, double halfDepth, double spacing,
    double minScale, double maxScale, double maxSlope, double minHeight, double maxHeight, long seed,
    MaterialData.Values sway) {

    /** The component type. */
    public static final ComponentType<PlantScatter> TYPE = ComponentType.of("truesim.vegetation.plant-scatter",
        PlantScatter.class);

    public PlantScatter {
        Objects.requireNonNull(model, "model");
        Objects.requireNonNull(sway, "sway");
        if (!(halfWidth > 0) || !(halfDepth > 0) || !(spacing > 0)) {
            throw new IllegalArgumentException("A scatter needs a size and a spacing: " + halfWidth + " x " + halfDepth
                + ", " + spacing);
        }
        if (!(minScale > 0) || !(maxScale >= minScale)) {
            throw new IllegalArgumentException("Scales from above 0, the largest last: " + minScale + ", " + maxScale);
        }
    }
}
