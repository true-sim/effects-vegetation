package com.truesim.effects.vegetation.scene;

import com.truesim.annotations.AllowAllocation;
import com.truesim.effects.vegetation.VegetationGround;
import com.truesim.effects.vegetation.VegetationHooks;
import com.truesim.effects.vegetation.VegetationSettings;
import com.truesim.effects.vegetation.materials.VegetationGrass;
import com.truesim.effects.vegetation.materials.VegetationPlant;
import com.truesim.engine.app.Frame;
import com.truesim.engine.app.Plugin;
import com.truesim.engine.app.PluginKey;
import com.truesim.engine.app.Stage;
import com.truesim.engine.app.Startup;
import com.truesim.engine.graph.Rendering;
import com.truesim.engine.render.Camera;
import com.truesim.engine.render.GpuScene;
import com.truesim.engine.scene.Assets;
import com.truesim.engine.settings.EngineSettings;
import com.truesim.scene.core.SceneGraph;
import java.util.List;

/**
 * Vegetation as a plugin: the settings ({@link VegetationSettings}), the grass and plant material types registered with
 * the assets plugin's materials, and a world's vegetation kept each frame after the game's update: its components as
 * renderables ({@link VegetationSync}) and the wind and field hooks in its scene's geometry hook block
 * ({@link VegetationHooks}), detail falling with the distance from the viewer's camera; far plants of scatters with
 * impostors baked through the engine ({@link com.truesim.effects.vegetation.ImpostorBaker}).
 *
 * <pre>{@code
 * app.use(new Rendering(), new Assets(), new VegetationPlugin());
 * VegetationPlugin vegetation = engine.get(VegetationPlugin.KEY);
 * vegetation.world(scene, gpuScene, camera).ground(terrain);
 * }</pre>
 *
 * <p>One world per plugin. The wind's time is the frame's delta ({@link Frame#delta()}): a paused world's wind stands.
 */
public final class VegetationPlugin implements Plugin {

    /** Its key. */
    public static final PluginKey<VegetationPlugin> KEY = PluginKey.of("vegetation", VegetationPlugin.class);

    private Rendering rendering;
    private Assets assets;
    private EngineSettings settings;
    private VegetationSync sync;
    private VegetationHooks hooks;
    private com.truesim.effects.vegetation.ImpostorBaker baker;
    private Camera viewer;
    private float errorPixels = 1;

    @Override
    public PluginKey<VegetationPlugin> key() {
        return KEY;
    }

    @Override
    public List<PluginKey<?>> requires() {
        return List.of(Rendering.KEY, Assets.KEY);
    }

    @Override
    @AllowAllocation("starting up")
    public void start(Stage stage, Startup startup) {
        if (stage == Stage.BEFORE_INSTANCE) {
            settings = startup.settings();
            startup.registry().addHidden(VegetationSettings.ALL.toArray(new com.truesim.engine.settings.Setting<?>[0]));
        } else if (stage == Stage.DEVICE) {
            rendering = startup.get(Rendering.KEY);
            assets = startup.get(Assets.KEY);
            assets.resources().materials().register(VegetationGrass.TYPE);
            assets.resources().materials().register(VegetationPlant.TYPE);
            assets.resources().materials().register(com.truesim.effects.vegetation.materials.VegetationImpostor.TYPE);
        }
    }

    /**
     * The vegetation of {@code scene}, drawn in {@code gpu} (the scene sync's table), its detail by {@code camera}'s
     * distance; once.
     */
    @AllowAllocation("set up once")
    public VegetationSync world(SceneGraph scene, GpuScene gpu, Camera camera) {
        if (sync != null) {
            throw new IllegalStateException("The vegetation plugin keeps one world already");
        }
        if (assets == null) {
            throw new IllegalStateException("The vegetation plugin sets up worlds from " + Stage.DEVICE + " on");
        }
        baker = new com.truesim.effects.vegetation.ImpostorBaker(rendering.device());
        sync = new VegetationSync(scene, assets.cache(), settings).impostors(baker);
        hooks = new VegetationHooks(rendering.device(), gpu).viewer(camera, errorPixels);
        viewer = camera;
        return sync;
    }

    /** The ground the world's vegetation stands on, its map on the GPU too where it has one. */
    public VegetationPlugin ground(VegetationGround ground) {
        sync().ground(ground);
        hooks().ground(ground.map());
        return this;
    }

    /** The detail rule's allowed pixels the camera draws with (the engine's 1 by default). */
    public VegetationPlugin errorPixels(float pixels) {
        errorPixels = pixels;
        if (hooks != null) {
            hooks.viewer(viewer, pixels);
        }
        return this;
    }

    /** The world's sync. */
    public VegetationSync sync() {
        if (sync == null) {
            throw new IllegalStateException("No world yet: world(scene, gpu, camera)");
        }
        return sync;
    }

    /** The world's hooks: the wind (set it from code, or leave it to the scene's values) and the field. */
    public VegetationHooks hooks() {
        if (hooks == null) {
            throw new IllegalStateException("No world yet: world(scene, gpu, camera)");
        }
        return hooks;
    }

    @Override
    public void afterUpdate(Frame frame) {
        if (sync != null) {
            double x = viewer.position(0) + viewer.eye(0);
            double y = viewer.position(1) + viewer.eye(1);
            double z = viewer.position(2) + viewer.eye(2);
            sync.update(x, y, z);
            hooks.update(frame.delta(), settings);
        }
    }

    /** Takes the world's vegetation out of its scene. */
    @Override
    public void stop() {
        if (sync != null) {
            sync.close();
            sync = null;
        }
        if (baker != null) {
            baker.close();
            baker = null;
        }
    }
}
