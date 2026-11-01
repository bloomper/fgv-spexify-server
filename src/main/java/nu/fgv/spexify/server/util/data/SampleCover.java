/*
 * Copyright 2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package nu.fgv.spexify.server.util.data;

import lombok.extern.slf4j.Slf4j;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;

/**
 * Draws a cover with the album's title on a gradient. When the runtime has no fonts, the cover is the gradient alone.
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
@Slf4j
final class SampleCover {

    private static final int SIZE = 600;

    private SampleCover() {
    }

    static byte[] draw(final String title, final int seed) throws IOException {
        final BufferedImage image = new BufferedImage(SIZE, SIZE, BufferedImage.TYPE_INT_RGB);
        final Graphics2D graphics = image.createGraphics();
        final Color from = Color.getHSBColor((seed * 0.137f) % 1f, 0.55f, 0.55f);
        final Color to = Color.getHSBColor((seed * 0.137f + 0.15f) % 1f, 0.65f, 0.25f);

        try {
            graphics.setPaint(new GradientPaint(0, 0, from, SIZE, SIZE, to));
            graphics.fillRect(0, 0, SIZE, SIZE);
            try {
                graphics.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
                graphics.setColor(Color.WHITE);
                graphics.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 44));

                int y = 260;

                for (final String line : wrap(title, 22)) {
                    graphics.drawString(line, 48, y);
                    y += 56;
                }
            } catch (final RuntimeException | Error e) {
                log.debug("No fonts for sample covers: {}", e.getMessage());
            }
        } finally {
            graphics.dispose();
        }

        final ByteArrayOutputStream out = new ByteArrayOutputStream();

        ImageIO.write(image, "jpg", out);
        return out.toByteArray();
    }

    private static java.util.List<String> wrap(final String text, final int width) {
        final java.util.List<String> lines = new java.util.ArrayList<>();
        final StringBuilder line = new StringBuilder();

        for (final String word : text.split(" ")) {
            if (!line.isEmpty() && line.length() + word.length() + 1 > width) {
                lines.add(line.toString());
                line.setLength(0);
            }
            if (!line.isEmpty()) {
                line.append(' ');
            }
            line.append(word);
        }
        if (!line.isEmpty()) {
            lines.add(line.toString());
        }
        return lines;
    }
}
