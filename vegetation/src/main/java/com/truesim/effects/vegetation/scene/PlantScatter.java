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
 * <p><b>Meadow flowers and other small plants</b> are a scatter per species (a gentian clump, an arnica, a grass
 * clump from common-procedural's recipes), each with its own spacing, so species mix by density over the same meadow
 * and the ground's density map; with a {@code radius} they exist only within it of the viewer, as grass patches do,
 * coming and going as it moves (thousands of small plants are never all in the scene). Trees take radius 0: all of
 * them, always, their detail by their meshes' cluster hierarchies.
 *
 * <p><b>Forests</b> take {@code impostors}: past the settings' impostor distance each plant is an octahedral impostor
 * baked from its model through the engine (one quad, {@code vegetationImpostor}), faded into it over the settings'
 * band by a dither in every pass, so a far forest costs a quad a tree and nothing pops
 * ({@link com.truesim.effects.vegetation.VegetationSettings#IMPOSTOR_DISTANCE}). The sync bakes it once the model
 * has loaded, with the {@link com.truesim.effects.vegetation.ImpostorBaker} it is given.
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
 * @param radius    metres from the viewer within which its plants are in the scene; 0: all of them, always
 * @param impostors whether far plants are its model's octahedral impostor
 */
public record PlantScatter(AssetRef<ModelData> model, double halfWidth, double halfDepth, double spacing,
    double minScale, double maxScale, double maxSlope, double minHeight, double maxHeight, long seed,
    MaterialData.Values sway, double radius, boolean impostors) {

    /** A scatter without impostors. */
    public PlantScatter(AssetRef<ModelData> model, double halfWidth, double halfDepth, double spacing,
        double minScale, double maxScale, double maxSlope, double minHeight, double maxHeight, long seed,
        MaterialData.Values sway, double radius) {
        this(model, halfWidth, halfDepth, spacing, minScale, maxScale, maxSlope, minHeight, maxHeight, seed, sway,
            radius, false);
    }

    /** A scatter of plants always in the scene: trees. */
    public PlantScatter(AssetRef<ModelData> model, double halfWidth, double halfDepth, double spacing,
        double minScale, double maxScale, double maxSlope, double minHeight, double maxHeight, long seed,
        MaterialData.Values sway) {
        this(model, halfWidth, halfDepth, spacing, minScale, maxScale, maxSlope, minHeight, maxHeight, seed, sway, 0,
            false);
    }

    /** This scatter with impostors far away, or without. */
    public PlantScatter withImpostors(boolean on) {
        return new PlantScatter(model, halfWidth, halfDepth, spacing, minScale, maxScale, maxSlope, minHeight,
            maxHeight, seed, sway, radius, on);
    }

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
        if (!(radius >= 0)) {
            throw new IllegalArgumentException("A radius from 0: " + radius);
        }
        if (!(minScale > 0) || !(maxScale >= minScale)) {
            throw new IllegalArgumentException("Scales from above 0, the largest last: " + minScale + ", " + maxScale);
        }
    }
}
