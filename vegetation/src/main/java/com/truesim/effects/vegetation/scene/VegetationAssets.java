package com.truesim.effects.vegetation.scene;

import com.truesim.annotations.AllowAllocation;
import com.truesim.effects.vegetation.GrassMaterials;
import com.truesim.effects.vegetation.GrassPatch;
import com.truesim.effects.vegetation.Impostors;
import com.truesim.effects.vegetation.PlantMaterials;
import com.truesim.effects.vegetation.materials.VegetationGrassParameters;
import com.truesim.effects.vegetation.materials.VegetationPlantParameters;
import com.truesim.scene.assets.AssetRef;
import com.truesim.scene.assets.Generator;
import com.truesim.scene.render.MaterialData;
import com.truesim.engine.render.materials.DiffuseTransmissionParameters;
import com.truesim.scene.render.RenderAssets;
import com.truesim.scene.render.TextureData;
import com.truesim.scene.assets.mesh.MeshData;
import java.util.Objects;

/**
 * The vegetation's generated material assets: a field's grass material (its values completed with the template's and
 * the field's bounds, its displacement's bound, two-sided, lit through) and a model material made a plant material
 * (the model's values nested as metallic-roughness, the sway's beside them). Generated, so equal recipes are one asset
 * the renderer converts once.
 */
public final class VegetationAssets {

    /** A field's grass material: its values, the template, its bounds in world x and z, what its bound allows for. */
    public record GrassRecipe(MaterialData.Values grass, GrassPatch patch, float minX, float minZ, float maxX,
        float maxZ, float transmission, float relief, float maxWiden) {

        public GrassRecipe {
            Objects.requireNonNull(grass, "grass");
            Objects.requireNonNull(patch, "patch");
        }
    }

    /**
     * A model's material as a plant's: the model's data, the sway's values, the strongest wind it is bounded for, and
     * the band of metres from the viewer over which it fades into its impostor (0, 0: none).
     */
    public record PlantRecipe(MaterialData source, MaterialData.Values sway, float maxWind, float fadeStart,
        float fadeEnd) {

        public PlantRecipe {
            Objects.requireNonNull(source, "source");
            Objects.requireNonNull(sway, "sway");
        }

        /** A plant material that never fades. */
        public PlantRecipe(MaterialData source, MaterialData.Values sway, float maxWind) {
            this(source, sway, maxWind, 0, 0);
        }
    }

    /**
     * An impostor's material: its atlas (an {@link com.truesim.effects.vegetation.ImpostorBaker}'s), the grid's frames,
     * the sphere it covers in mesh space, the band it fades in over, and whether it is lit through from behind as
     * leaves are.
     */
    public record ImpostorRecipe(AssetRef<TextureData> atlas, int frames, float centerX, float centerY,
        float centerZ, float radius, float fadeStart, float fadeEnd, boolean leaves) {

        public ImpostorRecipe {
            Objects.requireNonNull(atlas, "atlas");
        }
    }

    /** An impostor's quad: the sphere it covers. */
    public record QuadRecipe(float centerX, float centerY, float centerZ, float radius) {
    }

    /** Makes a grass material from its recipe. */
    public static final Generator<GrassRecipe, MaterialData> GRASS = (recipe, seed) -> grass(recipe);
    /** Makes a plant material from its recipe. */
    public static final Generator<PlantRecipe, MaterialData> PLANT = (recipe, seed) -> plant(recipe);
    /** Makes an impostor material from its recipe. */
    public static final Generator<ImpostorRecipe, MaterialData> IMPOSTOR = (recipe, seed) -> impostor(recipe);
    /** Makes an impostor's quad. */
    public static final Generator<QuadRecipe, MeshData> QUAD = (recipe, seed) -> Impostors.quad(recipe.centerX(),
        recipe.centerY(), recipe.centerZ(), recipe.radius());

    /** The grass type's name. */
    public static final String GRASS_TYPE = "vegetationGrass";
    /** The plant type's name. */
    public static final String PLANT_TYPE = "vegetationPlant";
    /** The impostor type's name. */
    public static final String IMPOSTOR_TYPE = "vegetationImpostor";

    private VegetationAssets() {
    }

    /** The grass material of {@code recipe} as an asset. */
    @AllowAllocation("a reference made as a field is set up")
    public static AssetRef<MaterialData> grassRef(GrassRecipe recipe) {
        return AssetRef.generated(RenderAssets.MATERIAL, GRASS, recipe, 0);
    }

    /** The plant material of {@code recipe} as an asset. */
    @AllowAllocation("a reference made as plants are placed")
    public static AssetRef<MaterialData> plantRef(PlantRecipe recipe) {
        return AssetRef.generated(RenderAssets.MATERIAL, PLANT, recipe, 0);
    }

    /** The impostor material of {@code recipe} as an asset. */
    @AllowAllocation("a reference made as an impostor is baked")
    public static AssetRef<MaterialData> impostorRef(ImpostorRecipe recipe) {
        return AssetRef.generated(RenderAssets.MATERIAL, IMPOSTOR, recipe, 0);
    }

    /** The quad of {@code recipe} as an asset. */
    @AllowAllocation("a reference made as an impostor is baked")
    public static AssetRef<MeshData> quadRef(QuadRecipe recipe) {
        return AssetRef.generated(RenderAssets.MESH, QUAD, recipe, 0);
    }

    @AllowAllocation("made once per recipe, on a loader")
    static MaterialData impostor(ImpostorRecipe r) {
        MaterialData.Builder b = MaterialData.builder(IMPOSTOR_TYPE).texture("atlas", r.atlas())
            .set("frames", r.frames()).set("center", r.centerX(), r.centerY(), r.centerZ()).set("radius", r.radius())
            .set("fadeStart", r.fadeStart()).set("fadeEnd", r.fadeEnd())
            .set("displacement", Impostors.displacement(r.radius())).doubleSided(true);
        if (r.leaves()) {
            b.feature(DiffuseTransmissionParameters.NAME, f -> f.set("diffuseTransmissionFactor", 0.3f)
                .set("diffuseTransmissionColorFactor", 0.45f, 0.65f, 0.2f));
        }
        return b.build();
    }

    /** The grass parameters the bound is figured from: the values' or the defaults. */
    static VegetationGrassParameters grassBounds(MaterialData.Values v) {
        VegetationGrassParameters d = VegetationGrassParameters.DEFAULTS;
        return d.withHeight(v.f32("height", d.height())).withHeightVariation(v.f32("heightVariation",
            d.heightVariation())).withClumpHeight(v.f32("clumpHeight", d.clumpHeight()))
            .withClumpPull(v.f32("clumpPull", d.clumpPull())).withClumpSize(v.f32("clumpSize", d.clumpSize()))
            .withWidth(v.f32("width", d.width()));
    }

    @AllowAllocation("made once per recipe, on a loader")
    static MaterialData grass(GrassRecipe r) {
        MaterialData.Builder b = MaterialData.builder(GRASS_TYPE);
        copy(r.grass(), b, "");
        GrassPatch patch = r.patch();
        b.set("patchSize", patch.size()).set("spacing", patch.spacing()).set("lodError", patch.error())
            .set("boundsMin", r.minX(), r.minZ()).set("boundsMax", r.maxX(), r.maxZ());
        float bound = GrassMaterials.displacement(GrassMaterials.forTemplate(grassBounds(r.grass()), patch), patch,
            r.relief(), r.maxWiden());
        b.set("displacement", bound).doubleSided(true);
        if (r.transmission() > 0) {
            float t = r.transmission();
            b.feature(DiffuseTransmissionParameters.NAME, f -> f.set("diffuseTransmissionFactor", t)
                .set("diffuseTransmissionColorFactor", (float) GrassMaterials.TRANSMISSION_COLOR.r(),
                    (float) GrassMaterials.TRANSMISSION_COLOR.g(), (float) GrassMaterials.TRANSMISSION_COLOR.b()));
        }
        return b.build();
    }

    @AllowAllocation("made once per recipe, on a loader")
    static MaterialData plant(PlantRecipe r) {
        MaterialData source = r.source();
        MaterialData.Builder b = MaterialData.builder(PLANT_TYPE);
        copy(source.values(), b, "base.");
        boolean leaves = source.alphaMode() == MaterialData.AlphaMode.MASK || source.doubleSided();
        if (leaves && !r.sway().has("leafWeight")) {
            b.set("leafWeight", 1f);
        }
        copy(r.sway(), b, "");
        b.doubleSided(source.doubleSided());
        if (source.alphaMode() != MaterialData.AlphaMode.OPAQUE) {
            b.alpha(source.alphaMode(), source.alphaCutoff());
        }
        for (String feature : source.features()) {
            MaterialData.Values values = source.feature(feature);
            b.feature(feature, f -> copy(values, f, ""));
        }
        if (leaves && !source.features().contains(DiffuseTransmissionParameters.NAME)) {
            b.feature(DiffuseTransmissionParameters.NAME, f -> f.set("diffuseTransmissionFactor", 0.3f)
                .set("diffuseTransmissionColorFactor", 0.45f, 0.65f, 0.2f));
        }
        b.set("displacement", PlantMaterials.displacement(plantBounds(r.sway()), r.maxWind()));
        if (r.fadeEnd() > r.fadeStart()) {
            b.set("fadeStart", r.fadeStart()).set("fadeEnd", r.fadeEnd());
        }
        return b.build();
    }

    /** The plant parameters the bound is figured from: the values' or the defaults. */
    static VegetationPlantParameters plantBounds(MaterialData.Values v) {
        VegetationPlantParameters d = VegetationPlantParameters.DEFAULTS;
        return d.withHeight(v.f32("height", d.height())).withMaxLean(v.f32("maxLean", d.maxLean()))
            .withTrunkBend(v.f32("trunkBend", d.trunkBend())).withTrunkSway(v.f32("trunkSway", d.trunkSway()))
            .withBranchSway(v.f32("branchSway", d.branchSway())).withLeafFlutter(v.f32("leafFlutter",
                d.leafFlutter()));
    }

    /** Every value of {@code from} into {@code to}, named with {@code prefix}: textures, vectors, integers, floats. */
    @AllowAllocation("made once per recipe, on a loader")
    private static void copy(MaterialData.Values from, MaterialData.ValuesBuilder<?> to, String prefix) {
        for (String name : from.names()) {
            String named = prefix + name;
            try {
                to.texture(named, from.texture(name));
                continue;
            } catch (IllegalArgumentException notTexture) {
                // a number or a vector
            }
            try {
                float[] values = from.floats(name);
                float[] more = new float[values.length - 2];
                System.arraycopy(values, 2, more, 0, more.length);
                to.set(named, values[0], values[1], more);
                continue;
            } catch (IllegalArgumentException notVector) {
                // a number
            }
            try {
                to.set(named, from.i32(name, 0));
            } catch (IllegalArgumentException notInteger) {
                to.set(named, from.f32(name, 0));
            }
        }
    }
}
