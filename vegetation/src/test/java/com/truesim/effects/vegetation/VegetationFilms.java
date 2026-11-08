package com.truesim.effects.vegetation;

import com.truesim.common.math.Vec3;
import com.truesim.effects.vegetation.materials.VegetationGrassParameters;
import com.truesim.effects.vegetation.materials.VegetationWindParameters;
import com.truesim.engine.vulkan.VulkanDevice;
import com.truesim.engine.vulkan.VulkanInstance;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/**
 * Films of the vegetation, as PNG frames at 30 a second, 1280 x 720 (drawn at twice that and averaged down: the engine
 * has no anti-aliasing yet), one folder per film under {@code build/films} (or the {@code films} Gradle property):
 * <ul>
 *   <li>{@code meadow-flight}: a low flight through a meadow as the wind rises from a calm to a breeze to gusts;</li>
 *   <li>{@code hillside}: a hillside meadow and spruces in a gusty wind, seen wide;</li>
 *   <li>{@code lod}: the detail levels as the viewer moves (blades thinning, widening, ending at the radius), from
 *   above and behind the viewer.</li>
 * </ul>
 * Run with {@code TRUESIM_FILMS=true ./gradlew :effects-vegetation:test --tests '*VegetationFilms'}; encode each with
 * {@code ffmpeg -framerate 30 -i <film>/f%04d.png -c:v libx264 -pix_fmt yuv420p -crf 20 <film>.mp4}.
 */
@EnabledIfEnvironmentVariable(named = "TRUESIM_FILMS", matches = "true")
class VegetationFilms {

    private static final int WIDTH = 1280;
    private static final int HEIGHT = 720;
    private static final int SUPER = 2;

    private VulkanInstance instance;
    private VulkanDevice device;

    @BeforeEach
    void start() {
        instance = VulkanInstance.builder().applicationName("vegetation films").surfaces(false).build();
        device = VulkanDevice.createHeadless(instance.bestGpu());
        System.out.println("vegetation films on " + instance.bestGpu().name());
    }

    @AfterEach
    void stop() {
        device.close();
        instance.close();
    }

    private static Path folder(String film) throws IOException {
        String root = System.getProperty("truesim.films", "build/films");
        Path out = Path.of(root, film);
        Files.createDirectories(out);
        return out;
    }

    private static void write(BufferedImage big, Path folder, int frame) throws IOException {
        BufferedImage small = new BufferedImage(WIDTH, HEIGHT, BufferedImage.TYPE_INT_RGB);
        // a box filter over SUPER x SUPER pixels, in the sRGB values (close enough for a film)
        for (int y = 0; y < HEIGHT; y++) {
            for (int x = 0; x < WIDTH; x++) {
                int r = 0;
                int g = 0;
                int b = 0;
                for (int j = 0; j < SUPER; j++) {
                    for (int i = 0; i < SUPER; i++) {
                        int p = big.getRGB(SUPER * x + i, SUPER * y + j);
                        r += p >> 16 & 0xff;
                        g += p >> 8 & 0xff;
                        b += p & 0xff;
                    }
                }
                int n = SUPER * SUPER;
                small.setRGB(x, y, (r / n) << 16 | (g / n) << 8 | b / n);
            }
        }
        ImageIO.write(small, "png", folder.resolve(String.format(Locale.ROOT, "f%04d.png", frame)).toFile());
    }

    static VegetationGrassParameters meadowGrass() {
        return VegetationGrassParameters.DEFAULTS.withWidth(0.014f).withColorVariation(0.4f).withDryness(0.18f);
    }

    private static final Vec3 WEST_WIND = new Vec3(1, 0, -0.35);

    /** The wind at {@code t} seconds of the flight: a calm, a breeze rising over a second, then gusts. */
    private static VegetationWindParameters flightWind(double t) {
        VegetationWindParameters base = VegetationWindParameters.DEFAULTS.withDirection(WEST_WIND);
        if (t < 3) {
            return base.withSpeed(0);
        }
        if (t < 6.5) {
            double up = Math.min(1, (t - 3) / 1.0);
            return base.withSpeed((float) (3.5 * up)).withGustStrength(0.3f).withTurbulence(0.3f);
        }
        double up = Math.min(1, (t - 6.5) / 1.0);
        return base.withSpeed((float) (3.5 + 6 * up)).withGustStrength(0.9f).withGustScale(14).withTurbulence(0.35f)
            .withFlutter(0.12f);
    }

    @Test
    void meadowFlight() throws IOException {
        Path out = folder("meadow-flight");
        int frames = 300;
        try (Meadow meadow = new Meadow(device, WIDTH * SUPER, HEIGHT * SUPER, Meadow::hills, 500)) {
            meadow.grass(GrassPatch.of(meadow.graph.settings()), meadowGrass(), 0, -10, 130);
            meadow.look(0, 1, 0, 0, -4, 60).shade(true, true);
            for (int f = 0; f < frames; f++) {
                double t = f / 30.0;
                double z = 2 - 1.4 * t;
                double x = 0.6 * Math.sin(t * 0.4);
                double y = Meadow.hills(x, z) + 0.75 + 0.1 * Math.sin(t * 0.7);
                meadow.look(x, y, z, 8 * Math.sin(t * 0.3), -5, 60).wind(flightWind(t));
                meadow.frames(f == 0 ? 4 : 1, 1 / 30.0);
                write(meadow.shot(), out, f);
            }
        }
    }

    @Test
    void hillside() throws IOException {
        Path out = folder("hillside");
        int frames = 270;
        try (Meadow meadow = new Meadow(device, WIDTH * SUPER, HEIGHT * SUPER, Meadow::hillside, 700)) {
            meadow.grass(GrassPatch.of(meadow.graph.settings()), meadowGrass(), 0, -60, 115);
            int[] tree = meadow.treeMeshAndMaterials(30);
            var scatter = new com.truesim.effects.vegetation.scene.PlantScatter(
                com.truesim.scene.assets.AssetRef.generated(com.truesim.scene.render.RenderAssets.MODEL,
                    (com.truesim.scene.assets.Generator<String, com.truesim.scene.render.ModelData>) (a, b) -> null,
                    "unused", 0), 140, 70, 7, 0.7, 1.4, 30, 14, 60, 11,
                com.truesim.scene.render.MaterialData.builder("vegetationPlant").build().values());
            for (var p : com.truesim.effects.vegetation.scene.PlantPlacement.place(scatter, 0, -150,
                    meadow.vegetationGround())) {
                // a forest over the crest, a few spruces down the slope
                if (p.z() < -105 || GrassPatches.unit(GrassPatches.hash((int) (p.x() * 7 + p.z() * 13))) < 0.08) {
                    meadow.tree(p.x(), p.z(), (float) p.scale(), tree[0], tree[1]);
                }
            }
            VegetationWindParameters gusty = VegetationWindParameters.DEFAULTS.withDirection(WEST_WIND).withSpeed(8)
                .withGustStrength(0.9f).withGustScale(20).withTurbulence(0.3f).withFlutter(0.1f);
            meadow.look(0, Meadow.hillside(0, 10) + 2.2, 10, 0, 7, 60).shade(true, true).wind(gusty);
            for (int f = 0; f < frames; f++) {
                double t = f / 30.0;
                double x = -6 + 1.2 * t;
                meadow.look(x, Meadow.hillside(x, 10) + 2.2, 10, 10 - 2.2 * t, 7, 60);
                meadow.frames(f == 0 ? 4 : 1, 1 / 30.0);
                write(meadow.shot(), out, f);
            }
        }
    }

    @Test
    void lod() throws IOException {
        Path out = folder("lod");
        int frames = 240;
        try (Meadow meadow = new Meadow(device, WIDTH * SUPER, HEIGHT * SUPER, Meadow::flat, 500)) {
            meadow.grass(GrassPatch.of(meadow.graph.settings()), meadowGrass().withDetailColors(1), 0, 0, 115);
            meadow.look(0, 2, 0, 0, -8, 60).shade(true, true)
                .wind(VegetationWindParameters.DEFAULTS.withDirection(WEST_WIND).withSpeed(4));
            // Walking at eye height, then rising to 7 m looking down the meadow: bands of detail round the camera
            // (green full detail, yellow a quarter of the blades, orange a sixteenth, red the coarsest), thinning
            for (int f = 0; f < frames; f++) {
                double t = f / 30.0;
                double blend = Math.clamp((t - 3) / 3, 0, 1);
                blend = blend * blend * (3 - 2 * blend);
                double y = 1.7 + blend * 10.3;
                double pitch = -6 - blend * 12;
                meadow.look(0, y, 30 - 1.4 * t, 10 * Math.sin(t * 0.25), pitch, 60);
                meadow.frames(f == 0 ? 4 : 1, 1 / 30.0);
                write(meadow.shot(), out, f);
            }
        }
    }

    @Test
    void still() throws IOException {
        try (Meadow meadow = new Meadow(device, WIDTH * SUPER, HEIGHT * SUPER, Meadow::flat, 400)) {
            meadow.grass(GrassPatch.of(meadow.graph.settings()), meadowGrass(), 0, 0, 110);
            meadow.look(0, 1.2, 0, 0, -6, 60).shade(true, true)
                .wind(VegetationWindParameters.DEFAULTS.withSpeed(4).withDirection(WEST_WIND));
            meadow.frames(4, 1 / 30.0);
            write(meadow.shot(), folder("still"), 0);
        }
    }
}
