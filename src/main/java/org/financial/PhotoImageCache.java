package org.financial;

import javafx.scene.image.Image;
import javafx.scene.image.PixelWriter;
import javafx.scene.image.WritableImage;
import javafx.scene.paint.Color;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Loads portrait images from disk, applies cover-style fit for a fixed box, and caches by path + box size.
 * Missing files resolve to a shared placeholder image.
 */
public final class PhotoImageCache {

    private static final Image PLACEHOLDER = buildPlaceholder(160, 200);
    private static final Map<String, Image> CACHE = new ConcurrentHashMap<>(256);

    private PhotoImageCache() {}

    public static void clear() {
        CACHE.clear();
    }

    /**
     * Returns an image suitable for drawing inside a box of the given size (cover crop applied at draw time
     * in {@link IdImageGenerator}; here we cache the loaded source or placeholder at a stable resolution).
     */
    public static Image getImageForPath(String photoPath, int maxEdgePx) {
        if (photoPath == null || photoPath.isBlank()) {
            return PLACEHOLDER;
        }
        Path p = Path.of(photoPath.trim());
        if (!Files.isRegularFile(p)) {
            return PLACEHOLDER;
        }
        String key = p.toAbsolutePath().normalize() + "|" + maxEdgePx;
        return CACHE.computeIfAbsent(key, k -> loadRaw(p, maxEdgePx));
    }

    public static Image placeholder() {
        return PLACEHOLDER;
    }

    private static Image loadRaw(Path p, int maxEdgePx) {
        try {
            String uri = p.toUri().toString();
            Image img = new Image(uri, maxEdgePx, maxEdgePx, true, true, false);
            if (!img.isError() && img.getWidth() > 0 && img.getHeight() > 0) {
                return img;
            }
        } catch (Exception ignored) {
        }
        return PLACEHOLDER;
    }

    private static Image buildPlaceholder(int w, int h) {
        WritableImage wi = new WritableImage(w, h);
        PixelWriter pw = wi.getPixelWriter();
        for (int y = 0; y < h; y++) {
            double t = (double) y / Math.max(1, h - 1);
            int g = (int) (210 - t * 40);
            Color c = Color.rgb(g, g, g + 5);
            for (int x = 0; x < w; x++) {
                pw.setColor(x, y, c);
            }
        }
        return wi;
    }
}
