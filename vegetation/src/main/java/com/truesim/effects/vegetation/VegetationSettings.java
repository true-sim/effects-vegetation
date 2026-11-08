package com.truesim.effects.vegetation;

import com.truesim.engine.settings.Setting;
import java.util.List;

/**
 * The vegetation's tunables, as engine settings: the grass template's density and detail, how far grass reaches round
 * the viewer, the bounds displacement may move a vertex within. The template's settings make a new template (a new
 * generated asset) when they change; the others take effect the next frame.
 */
public final class VegetationSettings {

    /** Metres round the viewer grass is drawn to: patches beyond are not in the scene, the last quarter fades out. */
    public static final Setting<Float> RADIUS = Setting.decimal("vegetation.grass.radius", 110f, 4f, 2000f,
        "metres round the viewer grass reaches");
    /** The patch template's side, metres. */
    public static final Setting<Float> PATCH_SIZE = Setting.decimal("vegetation.grass.patchSize", 8f, 1f, 64f,
        "metres a side of a grass patch");
    /** Blades a square metre at full detail. */
    public static final Setting<Float> DENSITY = Setting.decimal("vegetation.grass.density", 160f, 1f, 4000f,
        "grass blades a square metre at full detail");
    /** Segments of a blade at full detail. */
    public static final Setting<Integer> SEGMENTS = Setting.integer("vegetation.grass.segments", 4, 1, 8,
        "segments of a grass blade at full detail");
    /** Detail levels of the template: each keeps a quarter of the blades of the one before. */
    public static final Setting<Integer> LEVELS = Setting.integer("vegetation.grass.levels", 4, 1, 6,
        "detail levels of the grass template");
    /**
     * Metres from the viewer at which grass has a quarter of its blades, at 1080 lines and a 60 degree vertical view
     * with the engine's 1 pixel of error: full detail to half of it, a quarter of the blades at it, a sixteenth at
     * twice it, and so on. Other resolutions and fields of view scale it as the engine's detail rule does.
     */
    public static final Setting<Float> LOD_DISTANCE = Setting.decimal("vegetation.grass.lodDistance", 8f, 0.5f, 500f,
        "metres at which grass keeps a quarter of its blades (1080p, 60 degrees)");
    /** The tallest blade the template's groups allow for, metres (their spheres reach this high). */
    public static final Setting<Float> TEMPLATE_HEIGHT = Setting.decimal("vegetation.grass.templateHeight", 1f, 0f,
        10f, "metres the grass template's detail groups reach up");
    /** How much the ground may rise or fall within a patch from its middle, metres: what its bounds allow. */
    public static final Setting<Float> RELIEF = Setting.decimal("vegetation.grass.relief", 2f, 0f, 100f,
        "metres the ground may rise or fall within a grass patch");
    /** How much wider blades get at most as detail thins them. */
    public static final Setting<Float> MAX_WIDEN = Setting.decimal("vegetation.grass.maxWiden", 3f, 1f, 16f,
        "how much wider grass blades get at most as detail thins them");
    /** Patches a frame takes in or lets go of at most as the viewer moves: the rest follow in later frames. */
    public static final Setting<Integer> PATCH_BUDGET = Setting.integer("vegetation.grass.patchBudget", 512, 1,
        1 << 20, "grass patches a frame adds or removes at most");
    /** The strongest wind plants are bounded for, metres a second: their displacement's bound. */
    public static final Setting<Float> MAX_WIND = Setting.decimal("vegetation.wind.maxSpeed", 30f, 0f, 120f,
        "the strongest wind vegetation is bounded for, metres a second");

    /** The vertical pixels and field of view {@link #LOD_DISTANCE} is given at: the engine's projection scale. */
    public static final float REFERENCE_PROJECTION = (float) (1080 / (2 * Math.tan(Math.toRadians(30))));

    /** Every setting, to register. */
    public static final List<Setting<?>> ALL = List.of(RADIUS, PATCH_SIZE, DENSITY, SEGMENTS, LEVELS, LOD_DISTANCE,
        TEMPLATE_HEIGHT, RELIEF, MAX_WIDEN, PATCH_BUDGET, MAX_WIND);

    private VegetationSettings() {
    }

    /** The template's first coarser level's error for a {@link #LOD_DISTANCE} of {@code metres}. */
    public static float lodError(float metres) {
        return metres / REFERENCE_PROJECTION;
    }
}
