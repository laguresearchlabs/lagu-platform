package com.lagu.platform.storage;

import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Decoding is the one place the platform deliberately puts image bytes through the JVM, so what
 * matters here is that it stays bounded and that anything it cannot handle degrades to "no
 * derivative" rather than to a failed upload.
 */
class ImageProcessorTest {

    private static byte[] jpeg(int width, int height) throws IOException {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        g.setColor(Color.BLUE);
        g.fillRect(0, 0, width, height);
        g.setColor(Color.ORANGE);
        g.fillOval(0, 0, width / 2, height / 2);
        g.dispose();

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "jpg", out);
        return out.toByteArray();
    }

    private static byte[] pngWithAlpha(int width, int height) throws IOException {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "png", out);
        return out.toByteArray();
    }

    @Test
    void readsDimensionsWithoutDecoding() throws IOException {
        Optional<ImageProcessor.Dimensions> dimensions = ImageProcessor.dimensionsOf(jpeg(800, 450));

        assertTrue(dimensions.isPresent());
        assertEquals(800, dimensions.get().width());
        assertEquals(450, dimensions.get().height());
        assertEquals(800, dimensions.get().longestEdge());
    }

    /** A PDF is a legitimate upload with no pixel dimensions; that is an absent measurement,
     *  not an error. */
    @Test
    void returnsNoDimensionsForSomethingThatIsNotAnImage() {
        assertTrue(ImageProcessor.dimensionsOf("%PDF-1.4\nnot an image".getBytes()).isEmpty());
        assertTrue(ImageProcessor.dimensionsOf(new byte[0]).isEmpty());
        assertTrue(ImageProcessor.dimensionsOf(null).isEmpty());
    }

    @Test
    void scalesDownToTheRequestedLongestEdge() throws IOException {
        Optional<byte[]> scaled = ImageProcessor.scaleToFit(jpeg(2000, 1000), 640);

        assertTrue(scaled.isPresent());
        ImageProcessor.Dimensions result = ImageProcessor.dimensionsOf(scaled.get()).orElseThrow();
        assertEquals(640, result.width());
        assertEquals(320, result.height());   // aspect ratio preserved
        assertTrue(scaled.get().length < jpeg(2000, 1000).length);
    }

    @Test
    void preservesAspectRatioWhenHeightIsTheLongestEdge() throws IOException {
        Optional<byte[]> scaled = ImageProcessor.scaleToFit(jpeg(500, 1500), 300);

        ImageProcessor.Dimensions result = ImageProcessor.dimensionsOf(scaled.orElseThrow()).orElseThrow();
        assertEquals(100, result.width());
        assertEquals(300, result.height());
    }

    /**
     * An image already inside the bound is still re-encoded rather than passed through: the
     * caller stores the result at a {@code .jpg}-shaped key, and handing back the original would
     * put a PNG there.
     */
    @Test
    void reEncodesAnImageThatIsAlreadySmallEnough() throws IOException {
        Optional<byte[]> scaled = ImageProcessor.scaleToFit(pngWithAlpha(100, 80), 640);

        assertTrue(scaled.isPresent());
        assertTrue(ContentTypeSniffer.matches(scaled.get(), ImageProcessor.DERIVATIVE_CONTENT_TYPE));
        ImageProcessor.Dimensions result = ImageProcessor.dimensionsOf(scaled.get()).orElseThrow();
        assertEquals(100, result.width());
        assertEquals(80, result.height());
    }

    @Test
    void derivativesAreJpegWhateverWentIn() throws IOException {
        byte[] scaled = ImageProcessor.scaleToFit(pngWithAlpha(900, 900), 200).orElseThrow();

        assertTrue(ContentTypeSniffer.matches(scaled, "image/jpeg"));
    }

    /** A format with no decoder — HEIC, AVIF, video — must leave the upload usable rather than
     *  failing it. */
    @Test
    void returnsNothingForAFormatItCannotDecode() {
        assertTrue(ImageProcessor.scaleToFit("%PDF-1.4\nnot an image".getBytes(), 640).isEmpty());
        assertTrue(ImageProcessor.scaleToFit(new byte[]{1, 2, 3}, 640).isEmpty());
    }

    /** Subsampling makes a large source cheap to scale; this is the case it exists for. */
    @Test
    void handlesASourceMuchLargerThanTheTarget() throws IOException {
        Optional<byte[]> scaled = ImageProcessor.scaleToFit(jpeg(4000, 3000), 640);

        ImageProcessor.Dimensions result = ImageProcessor.dimensionsOf(scaled.orElseThrow()).orElseThrow();
        assertEquals(640, result.width());
        assertEquals(480, result.height());
    }
}
