package com.truesim.effects.vegetation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

import com.truesim.effects.vegetation.materials.VegetationGrassParameters;
import com.truesim.effects.vegetation.materials.VegetationPlantParameters;
import com.truesim.effects.vegetation.scene.GrassField;
import com.truesim.effects.vegetation.scene.PlantScatter;
import com.truesim.effects.vegetation.scene.VegetationPlugin;
import com.truesim.scene.assets.AssetRef;
import com.truesim.scene.assets.Generator;
import com.truesim.scene.render.MaterialData;
import com.truesim.scene.render.ModelData;
import com.truesim.scene.render.RenderAssets;
import java.util.function.Function;
import org.junit.jupiter.api.Test;

/** What the vegetation refuses: parameters out of their ranges, sizes and counts that cannot be, use before setup. */
class ValidationTest {

    private static final MaterialData.Values NONE = MaterialData.builder("vegetationGrass").build().values();
    private static final AssetRef<ModelData> MODEL = AssetRef.generated(RenderAssets.MODEL,
        (Generator<String, ModelData>) (a, b) -> null, "nothing", 0);

    @Test
    void patchesNeedASizeADensityAndSensibleCounts() {
        assertThatIllegalArgumentException().isThrownBy(() -> new GrassPatch(0, 100, 4, 4, 0.01f, 1));
        assertThatIllegalArgumentException().isThrownBy(() -> new GrassPatch(8, 0, 4, 4, 0.01f, 1));
        assertThatIllegalArgumentException().isThrownBy(() -> new GrassPatch(8, 100, 4, 4, 0, 1));
        assertThatIllegalArgumentException().isThrownBy(() -> new GrassPatch(8, 100, 4, 4, 0.01f, -1));
        assertThatIllegalArgumentException().isThrownBy(() -> new GrassPatch(8, 100, 0, 4, 0.01f, 1));
        assertThatIllegalArgumentException().isThrownBy(() -> new GrassPatch(8, 100, 9, 4, 0.01f, 1));
        assertThatIllegalArgumentException().isThrownBy(() -> new GrassPatch(8, 100, 4, 0, 0.01f, 1));
        assertThatIllegalArgumentException().isThrownBy(() -> new GrassPatch(8, 100, 4, 7, 0.01f, 1));
        assertThatIllegalArgumentException().isThrownBy(() -> new GrassPatch(64, 4000, 4, 4, 0.01f, 1));
        assertThat(new GrassPatch(0.5f, 1, 1, 1, 0.01f, 0).blades()).isOne();
    }

    @Test
    void fieldsAndScattersNeedASize() {
        assertThatIllegalArgumentException().isThrownBy(() -> new GrassField(0, 10, NONE, 0.4f, true));
        assertThatIllegalArgumentException().isThrownBy(() -> new GrassField(10, 0, NONE, 0.4f, true));
        assertThatIllegalArgumentException().isThrownBy(() -> new GrassField(10, 10, NONE, 1.5f, true));
        assertThatIllegalArgumentException().isThrownBy(() -> new GrassField(10, 10, NONE, -0.1f, true));
        assertThatIllegalArgumentException().isThrownBy(() -> new PlantScatter(MODEL, 0, 10, 2, 1, 1, 30, 0, 9, 1,
            NONE));
        assertThatIllegalArgumentException().isThrownBy(() -> new PlantScatter(MODEL, 10, 0, 2, 1, 1, 30, 0, 9, 1,
            NONE));
        assertThatIllegalArgumentException().isThrownBy(() -> new PlantScatter(MODEL, 10, 10, 0, 1, 1, 30, 0, 9, 1,
            NONE));
        assertThatIllegalArgumentException().isThrownBy(() -> new PlantScatter(MODEL, 10, 10, 2, 0, 1, 30, 0, 9, 1,
            NONE));
        assertThatIllegalArgumentException().isThrownBy(() -> new PlantScatter(MODEL, 10, 10, 2, 2, 1, 30, 0, 9, 1,
            NONE));
        assertThatIllegalArgumentException().isThrownBy(() -> new PlantScatter(MODEL, 10, 10, 2, 1, 1, 30, 0, 9, 1,
            NONE, -1));
        assertThat(new PlantScatter(MODEL, 10, 10, 2, 1, 1, 30, 0, 9, 1, NONE).radius()).isZero();
    }

    @Test
    void groundMapsNeedASlotASizeAndTexels() {
        assertThatIllegalArgumentException().isThrownBy(() -> new GroundMap(-1, 0, 0, 10, 0, 16));
        assertThatIllegalArgumentException().isThrownBy(() -> new GroundMap(1, 0, 0, 0, 0, 16));
        assertThatIllegalArgumentException().isThrownBy(() -> new GroundMap(1, 0, 0, 10, 0, 1));
        assertThat(new GroundMap(1, 0, 0, 10, 0, 16).texel()).isEqualTo(10 / 16.0);
        assertThat(VegetationGround.FLAT.density(3, 4)).isOne();
        assertThat(VegetationGround.FLAT.map()).isNull();
        assertThat(((VegetationGround) (x, z) -> x).slope(0, 0, 0.5)).isCloseTo(45, org.assertj.core.api.Assertions
            .within(1e-9));
    }

    /** Each value of a [Range] field outside its range is refused, inside it taken. */
    private static <R> void ranged(Function<Float, R> with) {
        assertThatIllegalArgumentException().isThrownBy(() -> with.apply(-0.5f));
        assertThatIllegalArgumentException().isThrownBy(() -> with.apply(2.5f));
        assertThat(with.apply(0.5f)).isNotNull();
    }

    @Test
    void theTypesRangesAreChecked() {
        VegetationGrassParameters g = VegetationGrassParameters.DEFAULTS;
        ranged(g::withDryness);
        ranged(g::withColorVariation);
        ranged(g::withHeightVariation);
        ranged(g::withClumpPull);
        ranged(g::withClumpHeight);
        ranged(g::withClumpFacing);
        ranged(g::withDensity);
        ranged(g::withRoundness);
        ranged(g::withRoughness);
        ranged(g::withDetailColors);
        VegetationPlantParameters p = VegetationPlantParameters.DEFAULTS;
        ranged(p::withLeafWeight);
        assertThatIllegalArgumentException().isThrownBy(() -> p.withTrunkSway(3));
        assertThatIllegalArgumentException().isThrownBy(() -> p.withTrunkSway(-1));
    }

    @Test
    void thePluginIsUsedOnceSetUp() {
        VegetationPlugin plugin = new VegetationPlugin();
        assertThat(plugin.key()).isEqualTo(VegetationPlugin.KEY);
        assertThat(plugin.requires()).hasSize(2);
        assertThatIllegalStateException().isThrownBy(plugin::sync);
        assertThatIllegalStateException().isThrownBy(plugin::hooks);
        assertThatIllegalStateException().isThrownBy(() -> plugin.world(null, null, null));
        assertThat(plugin.errorPixels(2)).isSameAs(plugin);
        plugin.stop(); // nothing to stop
    }
}
