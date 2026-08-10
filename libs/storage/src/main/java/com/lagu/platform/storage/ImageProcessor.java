package com.lagu.platform.storage;

import lombok.extern.slf4j.Slf4j;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Iterator;
import java.util.Optional;

/**
 * Decodes uploaded images to measure them and to produce smaller copies.
 *
 * <p>This is the one place the platform deliberately puts image bytes through a JVM, and it is
 * worth being explicit about why, because the rest of {@link StorageService} exists to avoid
 * exactly that. Neither a dimension check nor a thumbnail can be derived from a ranged read: both
 * need a decoder, and a decoder needs the whole object. The alternative — an image proxy in front
 * of the bucket — trades a dependency here for a component to deploy and secure, which on-prem
 * k3s makes the more expensive of the two.
 *
 * <p><b>Memory is the risk, and it is bounded in two places.</b> A 25MB JPEG is only 25MB on
 * disk; decoded it is width × height × 4 bytes, which for a 50-megapixel photo is 200MB of heap.
 * {@link #dimensionsOf} therefore reads dimensions from the header without decoding pixels at
 * all, and {@link #scaleToFit} uses subsampling so the decoder writes a small raster directly
 * rather than a full one that is then thrown away.
 *
 * <p><b>Every method degrades rather than throws.</b> A format ImageIO cannot read — HEIC, AVIF,
 * anything video — is a legitimate upload that simply gets no thumbnail, so callers receive an
 * empty Optional and carry on. An upload must never fail because a derivative could not be built.
 */
@Slf4j
public final class ImageProcessor {

    /** Longest edge of the card derivative — sized for a search result tile at 2× density. */
    public static final int CARD_MAX_EDGE = 640;

    /** Longest edge of the display derivative — a gallery lightbox on a large screen. */
    public static final int FULL_MAX_EDGE = 1920;

    /** JPEG is the output for every derivative: universally decodable, and small. */
    public static final String DERIVATIVE_CONTENT_TYPE = "image/jpeg";

    private ImageProcessor() {
    }

    /** Pixel dimensions of an image. */
    public record Dimensions(int width, int height) {

        public int longestEdge() {
            return Math.max(width, height);
        }
    }

    /**
     * Reads an image's dimensions without decoding its pixels.
     *
     * <p>Readers parse the header to answer {@code getWidth}, so this costs a header parse rather
     * than a full raster — which is what makes it safe to run on every upload, including the ones
     * that would be too large to decode.
     *
     * @return empty when no reader can handle the format, or the bytes are not a readable image
     */
    public static Optional<Dimensions> dimensionsOf(byte[] content) {
        if (content == null || content.length == 0) return Optional.empty();
        try (ImageInputStream in = ImageIO.createImageInputStream(new ByteArrayInputStream(content))) {
            if (in == null) return Optional.empty();
            Iterator<ImageReader> readers = ImageIO.getImageReaders(in);
            if (!readers.hasNext()) return Optional.empty();

            ImageReader reader = readers.next();
            try {
                reader.setInput(in);
                return Optional.of(new Dimensions(reader.getWidth(0), reader.getHeight(0)));
            } finally {
                reader.dispose();
            }
        } catch (IOException | RuntimeException e) {
            log.debug("Could not read image dimensions: {}", e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * A JPEG copy of {@code content} scaled so its longest edge is at most {@code maxEdge}.
     *
     * <p>An image already within the bound is still re-encoded rather than returned untouched:
     * the caller wants a JPEG derivative at a predictable key, and handing back the original
     * would mean a WebP or PNG living at a {@code .jpg}-shaped key.
     *
     * @return empty when the format cannot be decoded — the upload is fine, it just gets no
     *         derivative
     */
    public static Optional<byte[]> scaleToFit(byte[] content, int maxEdge) {
        try (ImageInputStream in = ImageIO.createImageInputStream(new ByteArrayInputStream(content))) {
            if (in == null) return Optional.empty();
            Iterator<ImageReader> readers = ImageIO.getImageReaders(in);
            if (!readers.hasNext()) return Optional.empty();

            ImageReader reader = readers.next();
            BufferedImage source;
            try {
                reader.setInput(in);
                source = readSubsampled(reader, maxEdge);
            } finally {
                reader.dispose();
            }
            if (source == null) return Optional.empty();

            BufferedImage scaled = scale(source, maxEdge);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            if (!ImageIO.write(scaled, "jpg", out)) return Optional.empty();
            return Optional.of(out.toByteArray());
        } catch (IOException | RuntimeException | OutOfMemoryError e) {
            // OutOfMemoryError is caught deliberately. Subsampling makes it unlikely, but a
            // hostile image can still declare dimensions that are expensive to allocate for, and
            // one vendor's upload must not be able to take the service down.
            log.warn("Could not build a {}px derivative: {}", maxEdge, e.toString());
            return Optional.empty();
        }
    }

    /**
     * Decodes at a reduced resolution when the source is much larger than the target.
     *
     * <p>Subsampling happens inside the decoder, so a 50-megapixel photo destined for a 640px
     * thumbnail never becomes a 200MB raster on the way — the decoder skips pixels as it reads.
     * The factor is deliberately conservative: it never subsamples below the target, so the
     * final scale still has more detail than it needs and the result stays sharp.
     */
    private static BufferedImage readSubsampled(ImageReader reader, int maxEdge) throws IOException {
        int width = reader.getWidth(0);
        int height = reader.getHeight(0);
        int longest = Math.max(width, height);

        var params = reader.getDefaultReadParam();
        int factor = longest / Math.max(maxEdge, 1);
        if (factor >= 2) {
            params.setSourceSubsampling(factor, factor, 0, 0);
        }
        return reader.read(0, params);
    }

    private static BufferedImage scale(BufferedImage source, int maxEdge) {
        int longest = Math.max(source.getWidth(), source.getHeight());
        double ratio = longest <= maxEdge ? 1.0 : (double) maxEdge / longest;
        int targetWidth = Math.max(1, (int) Math.round(source.getWidth() * ratio));
        int targetHeight = Math.max(1, (int) Math.round(source.getHeight() * ratio));

        // TYPE_INT_RGB, not ARGB: the output is JPEG, which has no alpha channel. Writing an
        // image with alpha as JPEG produces the notorious red-tinted result on some encoders.
        BufferedImage target = new BufferedImage(targetWidth, targetHeight, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = target.createGraphics();
        try {
            // A transparent PNG composited onto an undefined background would darken at the
            // edges; filling white first is what a browser does with the same image.
            g.setColor(java.awt.Color.WHITE);
            g.fillRect(0, 0, targetWidth, targetHeight);
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                    RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            g.drawImage(source, 0, 0, targetWidth, targetHeight, null);
        } finally {
            g.dispose();
        }
        return target;
    }
}
