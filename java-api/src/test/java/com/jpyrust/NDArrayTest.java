package com.jpyrust;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

public class NDArrayTest {

    @Test
    public void typedArraysRoundTripThroughNpy() {
        NDArray floats = NDArray.of(new float[] {1.5f, -2f, 3f, 4f, 5f, 6f}, 2, 3);
        NDArray copy = NDArray.fromNpy(floats.toNpy());
        assertEquals(floats, copy);
        assertEquals(NDArray.DType.FLOAT32, copy.dtype());
        assertArrayEquals(new int[] {2, 3}, copy.shape());
        assertArrayEquals(new float[] {1.5f, -2f, 3f, 4f, 5f, 6f}, copy.toFloatArray());

        assertArrayEquals(new double[] {1, 2}, NDArray.fromNpy(NDArray.of(new double[] {1, 2}).toNpy()).toDoubleArray());
        assertArrayEquals(new short[] {-1, 7}, NDArray.fromNpy(NDArray.of(new short[] {-1, 7}).toNpy()).toShortArray());
        assertArrayEquals(new int[] {3, 4}, NDArray.fromNpy(NDArray.of(new int[] {3, 4}).toNpy()).toIntArray());
        assertArrayEquals(new long[] {1L << 40}, NDArray.fromNpy(NDArray.of(new long[] {1L << 40}).toNpy()).toLongArray());
        assertArrayEquals(new boolean[] {true, false},
                NDArray.fromNpy(NDArray.of(new boolean[] {true, false}).toNpy()).toBooleanArray());
        assertArrayEquals(new byte[] {(byte) 255, 0},
                NDArray.fromNpy(NDArray.ofUInt8(new byte[] {(byte) 255, 0}).toNpy()).toByteArray());
    }

    @Test
    public void headerIsPaddedToSixtyFourBytes() {
        byte[] npy = NDArray.of(new float[24], 2, 3, 4).toNpy();
        int headerLength = (npy[8] & 0xff) | (npy[9] & 0xff) << 8;
        assertEquals(0, (10 + headerLength) % 64);
        assertEquals('\n', npy[10 + headerLength - 1]);
        String header = new String(npy, 10, headerLength, StandardCharsets.ISO_8859_1);
        assertTrue(header.startsWith("{'descr': '<f4', 'fortran_order': False, 'shape': (2, 3, 4), }"), header);
    }

    @Test
    public void readsArraysWrittenByNumpy() {
        String dict = "{'descr': '<i8', 'fortran_order': False, 'shape': (2,), }";
        String header = dict + " ".repeat(63 - (10 + dict.length()) % 64) + "\n";
        ByteBuffer npy = ByteBuffer.allocate(10 + header.length() + 16).order(ByteOrder.LITTLE_ENDIAN);
        npy.put(new byte[] {(byte) 0x93, 'N', 'U', 'M', 'P', 'Y', 1, 0});
        npy.putShort((short) header.length());
        npy.put(header.getBytes(StandardCharsets.ISO_8859_1));
        npy.putLong(-5).putLong(9);
        NDArray array = NDArray.fromNpy(npy.array());
        assertArrayEquals(new long[] {-5, 9}, array.toLongArray());
        assertEquals("NDArray[int64, shape=(2,)]", array.toString());
    }

    @Test
    public void scalarsAndEmptyArraysKeepTheirShape() {
        NDArray scalar = NDArray.fromNpy(NDArray.of(new double[] {2.5}, new int[0]).toNpy());
        assertEquals(1, scalar.size());
        assertEquals(1, scalar.ndim());
        NDArray empty = NDArray.fromNpy(NDArray.of(new float[0], 0, 3).toNpy());
        assertArrayEquals(new int[] {0, 3}, empty.shape());
        assertEquals(0, empty.size());
    }

    @Test
    public void wrapCopiesLittleEndianBuffers() {
        ByteBuffer data = ByteBuffer.allocateDirect(8).order(ByteOrder.LITTLE_ENDIAN).putFloat(1f).putFloat(2f).flip();
        NDArray array = NDArray.wrap(NDArray.DType.FLOAT32, data, 2);
        assertArrayEquals(new float[] {1f, 2f}, array.toFloatArray());
        assertEquals(8, data.remaining());
    }

    @Test
    public void rejectsMismatchedShapesAndTypes() {
        assertThrows(IllegalArgumentException.class, () -> NDArray.of(new float[5], 2, 3));
        assertThrows(IllegalArgumentException.class, () -> NDArray.of(new float[0], -1));
        NDArray floats = NDArray.of(new float[] {1f});
        assertThrows(IllegalStateException.class, floats::toIntArray);
        assertThrows(IllegalStateException.class, floats::toByteArray);
        assertFalse(NDArray.isNpy("{\"not\": 1}".getBytes(StandardCharsets.UTF_8)));
        assertThrows(JPyRustException.class, () -> NDArray.fromNpy(new byte[] {1, 2, 3}));
    }

    @Test
    public void rejectsUnsupportedDtypes() {
        String dict = "{'descr': '<U3', 'fortran_order': False, 'shape': (1,), }";
        String header = dict + " ".repeat(63 - (10 + dict.length()) % 64) + "\n";
        ByteBuffer npy = ByteBuffer.allocate(10 + header.length() + 12).order(ByteOrder.LITTLE_ENDIAN);
        npy.put(new byte[] {(byte) 0x93, 'N', 'U', 'M', 'P', 'Y', 1, 0}).putShort((short) header.length());
        npy.put(header.getBytes(StandardCharsets.ISO_8859_1));
        JPyRustException error = assertThrows(JPyRustException.class, () -> NDArray.fromNpy(npy.array()));
        assertTrue(error.getMessage().contains("<U3"), error.getMessage());
    }
}
