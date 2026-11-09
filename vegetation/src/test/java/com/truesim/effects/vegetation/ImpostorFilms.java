package com.truesim.effects.vegetation;

import com.truesim.common.procedural.assets.ModelDetail;
import com.truesim.engine.settings.EngineSettings;
import com.truesim.engine.vulkan.VulkanDevice;
import com.truesim.engine.vulkan.VulkanInstance;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/**
 * The impostors' film, 1280 x 720 at 30 frames a second: a forest of 9,000 common-procedural trees, its trees past
 * 140 m octahedral impostors baked through the engine, faded in over 30 m. First a dolly towards the forest's edge
 * from 600 m away (every tree crossing the band), then the same dolly split down the middle: the left half every tree
 * its model, the right half impostors past 140 m. Frames go to {@code -Pfilms=<dir>}/impostors; encode with
 * {@code ffmpeg -framerate 30 -i f%04d.png -c:v libx264 -pix_fmt yuv420p -crf 20 impostors.mp4}.
 */
@EnabledIfEnvironmentVariable(named = "TRUESIM_FILMS", matches = "true")
class ImpostorFilms {

    private static final int WIDTH = 1280;
    private static final int HEIGHT = 720;

    @Test
    void dolly() throws Exception {
        VulkanInstance instance = VulkanInstance.builder().applicationName("impostor films").surfaces(false).build();
        VulkanDevice device = VulkanDevice.createHeadless(instance.bestGpu());
        Path out = Path.of(System.getProperty("truesim.films", "build/films"), "impostors");
        Files.createDirectories(out);
        Path cache = Path.of(System.getProperty("java.io.tmpdir"), "truesim-forest-cache");
        Files.createDirectories(cache);
        int frame = 0;
        try (Forest impostors = new Forest(device, WIDTH, HEIGHT, new EngineSettings(), cache, 9000, 1000, 0, true,
            ModelDetail.DEFAULT, 33);
            Forest models = new Forest(device, WIDTH, HEIGHT, new EngineSettings(), cache, 9000, 1000, 0, false,
                ModelDetail.DEFAULT, 33)) {
            look(impostors, 0);
            impostors.settle(8);
            look(models, 0);
            models.settle(8);
            int dolly = 240;
            for (int f = 0; f < dolly; f++) {
                look(impostors, f / (double) dolly);
                impostors.frame(1 / 30.0);
                write(label(impostors.shot(), "impostors past 140 m, faded over 30 m", null), out, frame++);
            }
            for (int f = 0; f < dolly; f++) {
                double t = f / (double) dolly;
                look(impostors, t);
                look(models, t);
                impostors.frame(1 / 30.0);
                models.frame(1 / 30.0);
                BufferedImage left = models.shot();
                BufferedImage right = impostors.shot();
                BufferedImage split = new BufferedImage(WIDTH, HEIGHT, BufferedImage.TYPE_INT_RGB);
                for (int y = 0; y < HEIGHT; y++) {
                    for (int x = 0; x < WIDTH; x++) {
                        split.setRGB(x, y, x < WIDTH / 2 ? left.getRGB(x, y) : right.getRGB(x, y));
                    }
                }
                write(label(split, "every tree its model", "impostors past 140 m"), out, frame++);
            }
            System.out.printf(Locale.ROOT, "impostors film: %d frames, %d trees, %d bakes%n", frame, impostors.trees,
                impostors.baker.bakes());
        } finally {
            device.close();
            instance.close();
        }
    }

    /** The camera at {@code t} (0 to 1) of the dolly: from 600 m to 40 m before the forest's edge, 8 m up. */
    private static void look(Forest forest, double t) {
        double s = t * t * (3 - 2 * t);
        double z = 500 + 600 - 560 * s;
        forest.look(0, 8, z, 6, -1.5, 55);
    }

    private static BufferedImage label(BufferedImage image, String left, String right) {
        BufferedImage copy = new BufferedImage(image.getWidth(), image.getHeight(), BufferedImage.TYPE_INT_RGB);
        Graphics2D g = copy.createGraphics();
        g.drawImage(image, 0, 0, null);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 22));
        g.setColor(Color.WHITE);
        g.drawString(left, 24, 40);
        if (right != null) {
            g.drawString(right, WIDTH / 2 + 24, 40);
            g.setColor(new Color(255, 255, 255, 160));
            g.fillRect(WIDTH / 2 - 1, 0, 2, HEIGHT);
        }
        g.dispose();
        return copy;
    }

    private static void write(BufferedImage image, Path folder, int frame) throws IOException {
        ImageIO.write(image, "png", folder.resolve(String.format(Locale.ROOT, "f%04d.png", frame)).toFile());
    }
}
