package com.lagu.platform.storage;

import com.lagu.platform.common.exception.ValidationException;

/**
 * Pixel bounds an uploaded image must satisfy.
 *
 * <p>The other half of holding listing quality up, alongside the count and format rules in
 * {@link MediaPolicy}: a 200px photo passes every byte-level check the platform makes and still
 * looks broken on a search card. Like everything else here these come from admin configuration
 * rather than code.
 *
 * <p>All four bounds are optional, and {@link #NONE} is the common case — most fields do not care.
 */
public record ImageConstraints(Integer minWidth, Integer minHeight,
                                Integer maxWidth, Integer maxHeight) {

    public static final ImageConstraints NONE = new ImageConstraints(null, null, null, null);

    public boolean isEmpty() {
        return minWidth == null && minHeight == null && maxWidth == null && maxHeight == null;
    }

    /**
     * @throws ValidationException when {@code dimensions} falls outside these bounds
     */
    public void check(ImageProcessor.Dimensions dimensions) {
        if (below(dimensions.width(), minWidth) || below(dimensions.height(), minHeight)) {
            throw new ValidationException(String.format(
                    "Image is %d×%d, smaller than the required minimum of %s×%s",
                    dimensions.width(), dimensions.height(), or(minWidth), or(minHeight)));
        }
        if (above(dimensions.width(), maxWidth) || above(dimensions.height(), maxHeight)) {
            throw new ValidationException(String.format(
                    "Image is %d×%d, larger than the permitted maximum of %s×%s",
                    dimensions.width(), dimensions.height(), or(maxWidth), or(maxHeight)));
        }
    }

    private static boolean below(int actual, Integer bound) {
        return bound != null && actual < bound;
    }

    private static boolean above(int actual, Integer bound) {
        return bound != null && actual > bound;
    }

    private static String or(Integer bound) {
        return bound == null ? "any" : bound.toString();
    }
}
