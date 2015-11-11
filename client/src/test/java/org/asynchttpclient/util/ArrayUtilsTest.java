package org.asynchttpclient.util;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertNull;
import static org.testng.Assert.assertTrue;

import java.util.Arrays;

import org.testng.annotations.Test;

public class ArrayUtilsTest {

    @Test
    public void copyOfObject() {

        /*
         * copyOf null object
         */
        Object[] nullArray = null;
        assertNull(ArrayUtils.copyOf(nullArray));

        Object[] original1 = new Object[0];
        Object[] copied = ArrayUtils.copyOf(original1);

        /*
         * Test contents are same
         */
        assertTrue(Arrays.equals(original1, copied));

        /*
         * Test reference is different
         */
        assertTrue(original1 != copied);

    }

    @Test
    public void copyOfString() {
        assertNull(ArrayUtils.copyOf((String[]) null));
        String[] original = new String[] { new String("a"), "b" };
        String[] cloned = ArrayUtils.copyOf(original);

        /*
         * Test references are different
         */
        assertTrue(original != cloned);

        /*
         * Test original contents are not modified
         */
        cloned[0] = "y";
        cloned[1] = new String("z");

        assertTrue(original[0] != cloned[0]);
        assertTrue(original[1] != cloned[1]);
    }

    @Test
    public void copyOfBoolean() {
        assertEquals(null, ArrayUtils.copyOf((boolean[]) null));
        boolean[] original = new boolean[] { true, false };
        boolean[] cloned = ArrayUtils.copyOf(original);
        assertTrue(Arrays.equals(original, cloned));
        assertTrue(original != cloned);
    }

    @Test
    public void copyOfLong() {
        assertEquals(null, ArrayUtils.copyOf((long[]) null));
        long[] original = new long[] { 0L, 1L };
        long[] cloned = ArrayUtils.copyOf(original);
        assertTrue(Arrays.equals(original, cloned));
        assertTrue(original != cloned);
    }

    @Test
    public void copyOfInt() {
        assertEquals(null, ArrayUtils.copyOf((int[]) null));
        int[] original = new int[] { 5, 8 };
        int[] cloned = ArrayUtils.copyOf(original);
        assertTrue(Arrays.equals(original, cloned));
        assertTrue(original != cloned);
    }

    @Test
    public void copyOfShort() {
        assertEquals(null, ArrayUtils.copyOf((short[]) null));
        short[] original = new short[] { 1, 4 };
        short[] cloned = ArrayUtils.copyOf(original);
        assertTrue(Arrays.equals(original, cloned));
        assertTrue(original != cloned);
    }

    @Test
    public void copyOfChar() {
        assertEquals(null, ArrayUtils.copyOf((char[]) null));
        char[] original = new char[] { 'a', '4' };
        char[] cloned = ArrayUtils.copyOf(original);
        assertTrue(Arrays.equals(original, cloned));
        assertTrue(original != cloned);
    }

    @Test
    public void copyOfByte() {
        assertEquals(null, ArrayUtils.copyOf((byte[]) null));
        byte[] original = new byte[] { 1, 6 };
        byte[] cloned = ArrayUtils.copyOf(original);
        assertTrue(Arrays.equals(original, cloned));
        assertTrue(original != cloned);
    }

    @Test
    public void clonedouble() {
        assertEquals(null, ArrayUtils.copyOf((double[]) null));
        double[] original = new double[] { 2.4d, 5.7d };
        double[] cloned = ArrayUtils.copyOf(original);
        assertTrue(Arrays.equals(original, cloned));
        assertTrue(original != cloned);
    }

    @Test
    public void copyOfFloat() {
        assertEquals(null, ArrayUtils.copyOf((float[]) null));
        float[] original = new float[] { 2.6f, 6.4f };
        float[] cloned = ArrayUtils.copyOf(original);
        assertTrue(Arrays.equals(original, cloned));
        assertTrue(original != cloned);
    }

}