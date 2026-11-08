package com.truesim.effects.vegetation.scene;

import com.truesim.annotations.AllowAllocation;
import com.truesim.effects.vegetation.VegetationGround;
import java.util.List;
import java.util.Objects;
import java.util.function.DoubleBinaryOperator;
import java.util.function.Function;

/**
 * Where a {@link PlantScatter}'s plants stand: the placement input of the {@link VegetationSync}, kept abstract so an
 * ecology (common-procedural's: suitability by altitude, slope, aspect, moisture, snow; belts and a treeline;
 * competition) can hand over its per-species output as a density field ({@link #densityField}) or as instances
 * ({@link #instances}); {@link #RULES} is the scatter's own rules ({@link PlantPlacement}). The scatter is the species:
 * its model, spacing, scales and seed.
 */
@FunctionalInterface
public interface PlantPlacer {

    /** The scatter's own rules: a jittered grid of its spacing, kept by the ground's density, slope and height. */
    PlantPlacer RULES = PlantPlacement::place;

    /** The plants of {@code scatter} round world ({@code centerX}, {@code centerZ}) on {@code ground}. */
    List<PlantPlacement.Plant> place(PlantScatter scatter, double centerX, double centerZ, VegetationGround ground);

    /**
     * The scatter's rules with each species' density from a field ({@code fieldOf(scatter)}: world x, z to 0 to 1,
     * multiplying the ground's), or the rules alone where it gives none.
     */
    @AllowAllocation("set up once")
    static PlantPlacer densityField(Function<PlantScatter, DoubleBinaryOperator> fieldOf) {
        Objects.requireNonNull(fieldOf, "fieldOf");
        return (scatter, x, z, ground) -> {
            DoubleBinaryOperator field = fieldOf.apply(scatter);
            if (field == null) {
                return PlantPlacement.place(scatter, x, z, ground);
            }
            VegetationGround weighted = new VegetationGround() {
                @Override
                public double height(double px, double pz) {
                    return ground.height(px, pz);
                }

                @Override
                public double density(double px, double pz) {
                    return ground.density(px, pz) * field.applyAsDouble(px, pz);
                }
            };
            return PlantPlacement.place(scatter, x, z, weighted);
        };
    }

    /**
     * Each species' plants as given ({@code instancesOf(scatter)}: positions on the ground, turns and scales, e.g. an
     * ecology's instance list), or the scatter's rules where it gives none.
     */
    @AllowAllocation("set up once")
    static PlantPlacer instances(Function<PlantScatter, List<PlantPlacement.Plant>> instancesOf) {
        Objects.requireNonNull(instancesOf, "instancesOf");
        return (scatter, x, z, ground) -> {
            List<PlantPlacement.Plant> given = instancesOf.apply(scatter);
            return given == null ? PlantPlacement.place(scatter, x, z, ground) : given;
        };
    }
}
