package com.truesim.effects.vegetation;

import com.truesim.annotations.AllowAllocation;
import com.truesim.annotations.AllowImpure;
import com.truesim.common.math.Vec2;
import com.truesim.common.math.Vec3;
import com.truesim.effects.vegetation.materials.VegetationFieldParameters;
import com.truesim.effects.vegetation.materials.VegetationWindParameters;
import com.truesim.engine.render.Camera;
import com.truesim.engine.render.GeometryHooks;
import com.truesim.engine.render.GpuScene;
import com.truesim.engine.render.ShadingHookParameters;
import com.truesim.engine.render.ShadingHookValues;
import com.truesim.engine.settings.EngineSettings;
import com.truesim.engine.vulkan.VulkanDevice;
import java.util.Objects;

/**
 * Writes the vegetation's two geometry hooks into a scene's geometry hook block each frame
 * ({@link GpuScene#geometryHooks}), the same for every view of the scene:
 * <ul>
 *   <li>{@value #WIND}: the scene's values (engine-scene's {@code ShadingHookComponent}), or what code set with
 *   {@link #wind}, with the time this clock keeps;</li>
 *   <li>{@value #FIELD}: the viewer detail falls with distance from (the main camera, so its shadow maps and motion
 *   vectors displace as its image does), the settings' radius and widening, the ground map, and the scene's snow depth
 *   (a {@code ShadingHookComponent} for {@value #FIELD} with {@code snowDepth}).</li>
 * </ul>
 *
 * <pre>{@code
 * VegetationHooks hooks = new VegetationHooks(device, gpuScene).viewer(camera, 1).ground(groundMap);
 * // each frame, before it records
 * hooks.update(frame.delta(), settings);
 * }</pre>
 *
 * <p>The geometry hook block takes whole records ({@link GeometryHooks#set}), so each frame makes two (immutable,
 * generated) records: the engine has no in-place writer for a geometry hook as views have for theirs.
 */
public final class VegetationHooks {

    /** The wind's hook. */
    public static final String WIND = "vegetation.wind";
    /** The field's hook. */
    public static final String FIELD = "vegetation.field";

    private final GpuScene scene;
    private final GeometryHooks hooks;
    private final ShadingHookValues values;
    private final int windIndex;
    private final int fieldIndex;
    private VegetationWindParameters wind;
    private Camera viewer;
    private float errorPixels = 1;
    private GroundMap ground;
    private double time;

    /**
     * The hooks of {@code scene} on {@code device}: its shaders must have been built with them (the vegetation's
     * listing).
     */
    public VegetationHooks(VulkanDevice device, GpuScene scene) {
        this.scene = Objects.requireNonNull(scene, "scene");
        this.hooks = scene.geometryHooks();
        this.values = ShadingHookValues.of(device);
        this.windIndex = hooks.listing().require(WIND);
        this.fieldIndex = hooks.listing().require(FIELD);
    }

    /** The scene it writes. */
    public GpuScene scene() {
        return scene;
    }

    /**
     * The wind from now on, in place of the scene's values ({@code null}: the scene's again); its time is this clock's.
     */
    public VegetationHooks wind(VegetationWindParameters parameters) {
        this.wind = parameters;
        return this;
    }

    /** Detail falls with the distance from {@code camera}'s eye, drawn with {@code errorPixels} allowed; null: none. */
    public VegetationHooks viewer(Camera camera, float errorPixels) {
        this.viewer = camera;
        this.errorPixels = errorPixels;
        return this;
    }

    /** Blades stand on {@code map}'s ground and thin with its density; null: on their patches' planes. */
    public VegetationHooks ground(GroundMap map) {
        this.ground = map;
        return this;
    }

    /** The wind's time, seconds. */
    public double time() {
        return time;
    }

    /** Sets the wind's time (a film's frame, a test); {@link #update} moves on from it. */
    public VegetationHooks time(double seconds) {
        this.time = seconds;
        return this;
    }

    /**
     * The next frame: {@code seconds} passed (0: the wind stands still, as a paused world's). Writes both records for
     * the next frame recorded.
     */
    @AllowAllocation("two records a frame: the geometry hook block takes whole records")
    @AllowImpure("keeps the wind's clock")
    public void update(double seconds, EngineSettings settings) {
        if (!(seconds >= 0)) {
            throw new IllegalArgumentException("Time runs forwards: " + seconds);
        }
        time += seconds;
        VegetationWindParameters w = wind;
        boolean windOn = true;
        if (w == null) {
            ShadingHookParameters authored = values.authored(windIndex);
            w = authored instanceof VegetationWindParameters scene ? scene : VegetationWindParameters.DEFAULTS;
            windOn = authored != null && values.on(windIndex);
        }
        hooks.set(WIND, w.withTime((float) (time % 86_400.0)));
        hooks.turn(WIND, windOn);
        ShadingHookParameters authoredField = values.authored(fieldIndex);
        VegetationFieldParameters f = authoredField instanceof VegetationFieldParameters scene ? scene
            : VegetationFieldParameters.DEFAULTS;
        f = f.withRadius(settings.get(VegetationSettings.RADIUS)).withMaxWiden(settings.get(VegetationSettings.MAX_WIDEN));
        if (viewer != null) {
            double x = viewer.position(0) + viewer.eye(0);
            double y = viewer.position(1) + viewer.eye(1);
            double z = viewer.position(2) + viewer.eye(2);
            float hx = (float) x;
            float hy = (float) y;
            float hz = (float) z;
            f = f.withViewer(new Vec3(hx, hy, hz))
                .withViewerLow(new Vec3((float) (x - hx), (float) (y - hy), (float) (z - hz)))
                .withViewerOn(1).withProjection(viewer.projectionScale()).withErrorPixels(errorPixels);
        } else {
            f = f.withViewerOn(0);
        }
        if (ground != null) {
            f = f.withGroundMap(ground.slot()).withGroundMapSize((float) ground.size())
                .withGroundMapOrigin(new Vec2((float) ground.originX(), (float) ground.originZ()))
                .withGroundBase(ground.base()).withGroundTexel((float) ground.texel());
        } else {
            f = f.withGroundMap(-1);
        }
        hooks.set(FIELD, f);
    }
}
