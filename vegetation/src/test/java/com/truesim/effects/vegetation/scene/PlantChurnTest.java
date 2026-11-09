package com.truesim.effects.vegetation.scene;

import static org.assertj.core.api.Assertions.assertThat;

import com.truesim.common.jobs.TaskScheduler;
import com.truesim.common.state.ChangeClock;
import com.truesim.effects.vegetation.VegetationSettings;
import com.truesim.effects.vegetation.materials.VegetationPlant;
import com.truesim.engine.render.GeometryPool;
import com.truesim.engine.render.GpuScene;
import com.truesim.engine.render.Materials;
import com.truesim.engine.render.Textures;
import com.truesim.engine.scene.SceneResources;
import com.truesim.engine.scene.SceneSync;
import com.truesim.engine.settings.EngineSettings;
import com.truesim.engine.vulkan.testing.TestGpu;
import com.truesim.scene.assets.AssetCache;
import com.truesim.scene.assets.AssetRef;
import com.truesim.scene.core.SceneGraph;
import com.truesim.scene.render.MaterialData;
import com.truesim.scene.render.ModelData;
import com.truesim.scene.render.RenderAssets;
import org.junit.jupiter.api.Test;

/**
 * Plants of a scatter with a radius coming and going round a moving viewer through the engine's scene sync, its
 * materials in a real table (effects-vegetation#2: "Material N is drawn by instances and did not displace", thrown from
 * {@code SceneResources.flush} as plant material blocks were let go and taken again). Waited for whole, as the
 * showcase draws.
 */
class PlantChurnTest {

    @Test
    void plantsComingAndGoingRoundTheViewerNeverChangeAMaterialUnderInstances() throws InterruptedException {
        try (TestGpu gpu = TestGpu.open(); TaskScheduler tasks = new TaskScheduler()) {
            var device = gpu.device();
            GeometryPool pool = gpu.closing(new GeometryPool(device, GeometryPool.Capacity.of(1 << 20, 1 << 14, 64)));
            Materials materials = gpu.closing(new Materials(device, 16));
            materials.register(VegetationPlant.TYPE);
            Textures textures = gpu.closing(new Textures(device));
            AssetCache assets = gpu.closing(new AssetCache(tasks));
            SceneResources resources = gpu.closing(new SceneResources(assets, pool, materials, textures))
                .loading(SceneResources.Loading.WHOLE);
            GpuScene gpuScene = gpu.closing(new GpuScene(device, pool, 1 << 14));
            gpuScene.setMaterials(materials);
            SceneGraph scene = new SceneGraph(new ChangeClock());
            SceneSync sceneSync = gpu.closing(new SceneSync(resources, scene, gpuScene));
            EngineSettings settings = new EngineSettings().set(VegetationSettings.PLANT_BUDGET, 64);
            VegetationSync sync = new VegetationSync(scene, assets, settings);
            // Three species of shrubs, 12 m round the viewer, over 200 m
            var sway = MaterialData.builder("vegetationPlant").set("height", 2f).build().values();
            for (int species = 0; species < 3; species++) {
                long node = scene.create();
                scene.update();
                AssetRef<ModelData> model = AssetRef.generated(RenderAssets.MODEL, VegetationSyncTest.TREES,
                    new VegetationSyncTest.TreeModel(20 + species), 0);
                scene.components().set(node, PlantScatter.TYPE, new PlantScatter(model, 100, 100, 3 + species, 0.2,
                    0.3, 60, -10, 10, species, sway, 12));
            }
            int most = 0;
            for (int frame = 0; frame < 600; frame++) {
                tasks.drainCompleted();
                // back and forth along x, 9 m a frame: rings come and go, blocks of plant materials with them
                double x = 60 * Math.sin(frame * 0.15);
                sync.update(x, 1.7, 0);
                scene.update();
                sceneSync.update(); // flushes the resources: set materials as they arrive
                most = Math.max(most, sync.plants());
                Thread.sleep(frame < 100 ? 2 : 0);
            }
            assertThat(most).as("plants came").isGreaterThan(20);
            assertThat(resources.materialSetCount()).as("blocks of materials in use").isLessThan(16);
            sync.close();
            device.waitIdle();
        }
    }
}
