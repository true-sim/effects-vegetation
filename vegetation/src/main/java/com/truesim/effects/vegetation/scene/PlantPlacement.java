package com.truesim.effects.vegetation.scene;

import com.truesim.annotations.AllowAllocation;
import com.truesim.effects.vegetation.VegetationGround;
import java.util.ArrayList;
import java.util.List;
import java.util.SplittableRandom;

/**
 * Where a {@link PlantScatter}'s plants stand: one candidate per cell of its spacing over its rectangle, jittered in its
 * cell, kept where the ground's density (a hash under it), its slope and its height allow. The same scatter on the same
 * ground places the same plants.
 */
public final class PlantPlacement {

    /** A plant: where it stands (on the ground), turned {@code yaw} radians about the vertical, scaled. */
    public record Plant(double x, double y, double z, double yaw, double scale) {
    }

    private PlantPlacement() {
    }

    /** The plants of {@code scatter} round world ({@code centerX}, {@code centerZ}) on {@code ground}. */
    @AllowAllocation("placing a scatter, once its model loads")
    public static List<Plant> place(PlantScatter scatter, double centerX, double centerZ, VegetationGround ground) {
        List<Plant> plants = new ArrayList<>();
        double spacing = scatter.spacing();
        int nx = Math.max(1, (int) Math.round(2 * scatter.halfWidth() / spacing));
        int nz = Math.max(1, (int) Math.round(2 * scatter.halfDepth() / spacing));
        double x0 = centerX - scatter.halfWidth();
        double z0 = centerZ - scatter.halfDepth();
        double cellX = 2 * scatter.halfWidth() / nx;
        double cellZ = 2 * scatter.halfDepth() / nz;
        SplittableRandom random = new SplittableRandom(scatter.seed());
        for (int j = 0; j < nz; j++) {
            for (int i = 0; i < nx; i++) {
                double x = x0 + (i + 0.1 + 0.8 * random.nextDouble()) * cellX;
                double z = z0 + (j + 0.1 + 0.8 * random.nextDouble()) * cellZ;
                double keep = random.nextDouble();
                double yaw = random.nextDouble(2 * Math.PI);
                double scale = scatter.minScale() + (scatter.maxScale() - scatter.minScale()) * random.nextDouble();
                if (keep >= ground.density(x, z)) {
                    continue;
                }
                double y = ground.height(x, z);
                if (y < scatter.minHeight() || y > scatter.maxHeight()) {
                    continue;
                }
                if (ground.slope(x, z, Math.min(1, 0.25 * spacing)) > scatter.maxSlope()) {
                    continue;
                }
                plants.add(new Plant(x, y, z, yaw, scale));
            }
        }
        return plants;
    }
}
