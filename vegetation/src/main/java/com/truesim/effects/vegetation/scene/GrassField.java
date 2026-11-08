package com.truesim.effects.vegetation.scene;

import com.truesim.scene.core.ComponentType;
import com.truesim.scene.render.MaterialData;
import java.util.Objects;

/**
 * A meadow on a node: grass over a rectangle of the ground round the node's world position (its axes the world's), of
 * the {@code vegetationGrass} type with {@code grass}'s values by field name (VegetationGrassParameters: colors, height,
 * width, lean, clumps, density, stiffness, ...; what it lacks keeps the defaults). The {@link VegetationSync} draws it
 * as patches of the grass template within the settings' radius of the viewer, standing on its ground. Saved with the
 * scene.
 *
 * <pre>{@code
 * scene.components().set(meadow, GrassField.TYPE, new GrassField(200, 150,
 *     MaterialData.builder("vegetationGrass").set("height", 0.5f).set("density", 0.8f).build().values(), 0.4f, true));
 * }</pre>
 *
 * @param halfWidth    metres either side of the node along world x
 * @param halfDepth    metres either side along world z
 * @param grass        the grass type's values by field name
 * @param transmission how much of a blade's diffuse light passes through it (lit from behind), 0 to 1
 * @param castsShadows whether its blades cast shadows
 */
public record GrassField(double halfWidth, double halfDepth, MaterialData.Values grass, float transmission,
    boolean castsShadows) {

    /** The component type. */
    public static final ComponentType<GrassField> TYPE = ComponentType.of("truesim.vegetation.grass-field",
        GrassField.class);

    public GrassField {
        Objects.requireNonNull(grass, "grass");
        if (!(halfWidth > 0) || !(halfDepth > 0)) {
            throw new IllegalArgumentException("A field needs a size: " + halfWidth + " x " + halfDepth);
        }
        if (!(transmission >= 0 && transmission <= 1)) {
            throw new IllegalArgumentException("Transmission is 0 to 1: " + transmission);
        }
    }
}
