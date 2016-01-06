package org.asynchttpclient.util;

import java.util.Arrays;

/**
 * <p>
 * Operations on arrays, primitive arrays (like {@code int[]}) and primitive
 * wrapper arrays (like {@code Integer[]}).
 * </p>
 * <p/>
 * <p>
 * This class tries to handle {@code null} input gracefully. An exception will
 * not be thrown for a {@code null} array input.
 * </p>
 */
public final class ArrayUtils {

    private ArrayUtils() {
    }

    /**
     * <p>
     * Return a copy of original array and handling {@code null}.
     * </p>
     * 
     * @param array
     *            the array to be copy, may be {@code null}
     * @return a copy of the original array, {@code null} if {@code null} input
     */
    public static boolean[] copyOf(final boolean[] array) {
        if (array == null) {
            return null;
        }
        return Arrays.copyOf(array, array.length);
    }

    /**
     * <p>
     * Return a copy of original array and handling {@code null}.
     * </p>
     * 
     * @param array
     *            the array to be copy, may be {@code null}
     * @return a copy of the original array, {@code null} if {@code null} input
     */
    public static byte[] copyOf(final byte[] array) {
        if (array == null) {
            return null;
        }
        return Arrays.copyOf(array, array.length);
    }

    /**
     * <p>
     * Return a copy of original array and handling {@code null}.
     * </p>
     * 
     * @param array
     *            the array to be copy, may be {@code null}
     * @return a copy of the original array, {@code null} if {@code null} input
     */
    public static short[] copyOf(final short[] array) {
        if (array == null) {
            return null;
        }
        return Arrays.copyOf(array, array.length);
    }

    /**
     * <p>
     * Return a copy of original array and handling {@code null}.
     * </p>
     * 
     * @param array
     *            the array to be copy, may be {@code null}
     * @return a copy of the original array, {@code null} if {@code null} input
     */
    public static int[] copyOf(final int[] array) {
        if (array == null) {
            return null;
        }
        return Arrays.copyOf(array, array.length);
    }

    /**
     * <p>
     * Return a copy of original array and handling {@code null}.
     * </p>
     * 
     * @param array
     *            the array to be copy, may be {@code null}
     * @return a copy of the original array, {@code null} if {@code null} input
     */
    public static long[] copyOf(final long[] array) {
        if (array == null) {
            return null;
        }
        return Arrays.copyOf(array, array.length);
    }

    /**
     * <p>
     * Return a copy of original array and handling {@code null}.
     * </p>
     * 
     * @param array
     *            the array to be copy, may be {@code null}
     * @return a copy of the original array, {@code null} if {@code null} input
     */
    public static float[] copyOf(final float[] array) {
        if (array == null) {
            return null;
        }
        return Arrays.copyOf(array, array.length);
    }

    /**
     * <p>
     * Return a copy of original array and handling {@code null}.
     * </p>
     * 
     * @param array
     *            the array to be copy, may be {@code null}
     * @return a copy of the original array, {@code null} if {@code null} input
     */
    public static double[] copyOf(final double[] array) {
        if (array == null) {
            return null;
        }
        return Arrays.copyOf(array, array.length);
    }

    /**
     * <p>
     * Return a copy of original array and handling {@code null}.
     * </p>
     * 
     * @param array
     *            the array to be copy, may be {@code null}
     * @return a copy of the original array, {@code null} if {@code null} input
     */
    public static char[] copyOf(final char[] array) {
        if (array == null) {
            return null;
        }
        return Arrays.copyOf(array, array.length);
    }

    /**
     * <p>
     * Return a copy of original array and handling {@code null}.
     * </p>
     * 
     * @param array
     *            the array to be copy, may be {@code null}
     * @return a copy of the original array, {@code null} if {@code null} input
     */
    public static <T> T[] copyOf(final T[] array) {
        if (array == null) {
            return null;
        }
        return Arrays.copyOf(array, array.length);
    }

}