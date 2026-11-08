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
        return VegetationGrassParameters.DEFAULTS;
    }

    @Test
    void still() throws IOException {
        try (Meadow meadow = new Meadow(device, WIDTH * SUPER, HEIGHT * SUPER, Meadow::flat, 400)) {
            meadow.grass(GrassPatch.of(meadow.graph.settings()), meadowGrass(), 0, 0, 110);
            meadow.look(0, 1.2, 0, 0, -6, 60).shade(true, true)
                .wind(VegetationWindParameters.DEFAULTS.withSpeed(4).withDirection(new Vec3(1, 0, -0.3)));
            meadow.frames(4, 1 / 30.0);
            write(meadow.shot(), folder("still"), 0);
            meadow.look(2, 12, 2, 0, -89, 60);
            meadow.frames(3, 0);
            write(meadow.shot(), folder("still"), 1);
        }
    }
}
