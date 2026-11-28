package com.jpyrust;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class NDArray {

    public enum DType {
        FLOAT32("<f4", 4),
        FLOAT64("<f8", 8),
        INT8("|i1", 1),
        INT16("<i2", 2),
        INT32("<i4", 4),
        INT64("<i8", 8),
        UINT8("|u1", 1),
        BOOL("|b1", 1);

        private final String descr;
        private final int itemSize;

        DType(String descr, int itemSize) {
            this.descr = descr;
            this.itemSize = itemSize;
        }

        public int itemSize() {
            return itemSize;
        }

        static DType fromDescr(String descr) {
            String normalized = descr.replace('=', '<');
            if (itemSizeOf(normalized) == 1) {
                normalized = "|" + normalized.substring(1);
            }
            for (DType type : values()) {
                if (type.descr.equals(normalized)) {
                    return type;
                }
            }
            throw new JPyRustException("Unsupported array dtype '" + descr + "' (supported: float32, float64, "
                    + "int8, int16, int32, int64, uint8, bool)");
        }

        private static int itemSizeOf(String descr) {
            try {
                return Integer.parseInt(descr.substring(2));
            } catch (RuntimeException e) {
                return -1;
            }
        }
    }

    private static final byte[] MAGIC = {(byte) 0x93, 'N', 'U', 'M', 'P', 'Y'};
    private static final Pattern DESCR = Pattern.compile("'descr':\\s*'([^']+)'");
    private static final Pattern FORTRAN = Pattern.compile("'fortran_order':\\s*(True|False)");
    private static final Pattern SHAPE = Pattern.compile("'shape':\\s*\\(([^)]*)\\)");

    private final DType dtype;
    private final int[] shape;
    private final byte[] npy;
    private final int offset;

    private NDArray(DType dtype, int[] shape, byte[] npy, int offset) {
        this.dtype = dtype;
        this.shape = shape;
        this.npy = npy;
        this.offset = offset;
    }

    public static NDArray of(float[] values, int... shape) {
        NDArray array = allocate(DType.FLOAT32, values.length, shape);
        array.buffer().asFloatBuffer().put(values);
        return array;
    }

    public static NDArray of(double[] values, int... shape) {
        NDArray array = allocate(DType.FLOAT64, values.length, shape);
        array.buffer().asDoubleBuffer().put(values);
        return array;
    }

    public static NDArray of(short[] values, int... shape) {
        NDArray array = allocate(DType.INT16, values.length, shape);
        array.buffer().asShortBuffer().put(values);
        return array;
    }

    public static NDArray of(int[] values, int... shape) {
        NDArray array = allocate(DType.INT32, values.length, shape);
        array.buffer().asIntBuffer().put(values);
        return array;
    }

    public static NDArray of(long[] values, int... shape) {
        NDArray array = allocate(DType.INT64, values.length, shape);
        array.buffer().asLongBuffer().put(values);
        return array;
    }

    public static NDArray of(boolean[] values, int... shape) {
        NDArray array = allocate(DType.BOOL, values.length, shape);
        for (int i = 0; i < values.length; i++) {
            array.npy[array.offset + i] = (byte) (values[i] ? 1 : 0);
        }
        return array;
    }

    public static NDArray ofUInt8(byte[] values, int... shape) {
        NDArray array = allocate(DType.UINT8, values.length, shape);
        System.arraycopy(values, 0, array.npy, array.offset, values.length);
        return array;
    }

    public static NDArray ofInt8(byte[] values, int... shape) {
        NDArray array = allocate(DType.INT8, values.length, shape);
        System.arraycopy(values, 0, array.npy, array.offset, values.length);
        return array;
    }

    public static NDArray wrap(DType dtype, ByteBuffer data, int... shape) {
        if (data.remaining() % dtype.itemSize != 0) {
            throw new IllegalArgumentException(data.remaining() + " bytes is not a whole number of " + dtype + " items");
        }
        NDArray array = allocate(dtype, data.remaining() / dtype.itemSize, shape);
        ByteBuffer source = data.duplicate();
        source.get(array.npy, array.offset, source.remaining());
        return array;
    }

    public static boolean isNpy(byte[] bytes) {
        if (bytes.length < MAGIC.length + 4) {
            return false;
        }
        for (int i = 0; i < MAGIC.length; i++) {
            if (bytes[i] != MAGIC[i]) {
                return false;
            }
        }
        return true;
    }

    public static NDArray fromNpy(byte[] npy) {
        if (!isNpy(npy)) {
            throw new JPyRustException("Not a NumPy .npy array (" + npy.length + " bytes)");
        }
        int major = npy[6];
        ByteBuffer header = ByteBuffer.wrap(npy).order(ByteOrder.LITTLE_ENDIAN);
        int headerLength = major == 1 ? Short.toUnsignedInt(header.getShort(8)) : header.getInt(8);
        int headerStart = major == 1 ? 10 : 12;
        String dict = new String(npy, headerStart, headerLength, StandardCharsets.ISO_8859_1);
        DType dtype = DType.fromDescr(group(DESCR, dict));
        if (group(FORTRAN, dict).equals("True")) {
            throw new JPyRustException("Fortran-ordered arrays are not supported; use numpy.ascontiguousarray");
        }
        int[] shape = Arrays.stream(group(SHAPE, dict).split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .mapToInt(Integer::parseInt)
                .toArray();
        int offset = headerStart + headerLength;
        long expected = (long) count(shape) * dtype.itemSize;
        if (npy.length - offset != expected) {
            throw new JPyRustException("Array data is " + (npy.length - offset) + " bytes, expected " + expected);
        }
        return new NDArray(dtype, shape, npy, offset);
    }

    public DType dtype() {
        return dtype;
    }

    public int[] shape() {
        return shape.clone();
    }

    public int ndim() {
        return shape.length;
    }

    public int size() {
        return count(shape);
    }

    public ByteBuffer data() {
        return buffer().asReadOnlyBuffer().order(ByteOrder.LITTLE_ENDIAN);
    }

    public float[] toFloatArray() {
        float[] out = new float[size()];
        typedBuffer(DType.FLOAT32).asFloatBuffer().get(out);
        return out;
    }

    public double[] toDoubleArray() {
        double[] out = new double[size()];
        typedBuffer(DType.FLOAT64).asDoubleBuffer().get(out);
        return out;
    }

    public short[] toShortArray() {
        short[] out = new short[size()];
        typedBuffer(DType.INT16).asShortBuffer().get(out);
        return out;
    }

    public int[] toIntArray() {
        int[] out = new int[size()];
        typedBuffer(DType.INT32).asIntBuffer().get(out);
        return out;
    }

    public long[] toLongArray() {
        long[] out = new long[size()];
        typedBuffer(DType.INT64).asLongBuffer().get(out);
        return out;
    }

    public boolean[] toBooleanArray() {
        typedBuffer(DType.BOOL);
        boolean[] out = new boolean[size()];
        for (int i = 0; i < out.length; i++) {
            out[i] = npy[offset + i] != 0;
        }
        return out;
    }

    public byte[] toByteArray() {
        if (dtype != DType.UINT8 && dtype != DType.INT8) {
            throw new IllegalStateException("Array is " + dtype + ", not UINT8 or INT8; use data() for raw bytes");
        }
        return Arrays.copyOfRange(npy, offset, npy.length);
    }

    public byte[] toNpy() {
        return npy.clone();
    }

    byte[] npyBytes() {
        return npy;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof NDArray that && dtype == that.dtype && Arrays.equals(shape, that.shape)
                && Arrays.equals(npy, offset, npy.length, that.npy, that.offset, that.npy.length);
    }

    @Override
    public int hashCode() {
        return 31 * (31 * dtype.hashCode() + Arrays.hashCode(shape))
                + Arrays.hashCode(Arrays.copyOfRange(npy, offset, Math.min(npy.length, offset + 64)));
    }

    @Override
    public String toString() {
        return "NDArray[" + dtype.name().toLowerCase() + ", shape=" + shapeTuple(shape) + "]";
    }

    private ByteBuffer buffer() {
        return ByteBuffer.wrap(npy, offset, npy.length - offset).slice().order(ByteOrder.LITTLE_ENDIAN);
    }

    private ByteBuffer typedBuffer(DType expected) {
        if (dtype != expected) {
            throw new IllegalStateException("Array is " + dtype + ", not " + expected);
        }
        return buffer();
    }

    private static NDArray allocate(DType dtype, int length, int[] shape) {
        int[] resolved = shape.length == 0 ? new int[] {length} : shape.clone();
        for (int dim : resolved) {
            if (dim < 0) {
                throw new IllegalArgumentException("Negative dimension in shape " + shapeTuple(resolved));
            }
        }
        if (count(resolved) != length) {
            throw new IllegalArgumentException("Shape " + shapeTuple(resolved) + " needs " + count(resolved)
                    + " values, got " + length);
        }
        String dict = "{'descr': '" + dtype.descr + "', 'fortran_order': False, 'shape': " + shapeTuple(resolved) + ", }";
        int padding = 63 - (10 + dict.length()) % 64;
        byte[] header = (dict + " ".repeat(padding) + "\n").getBytes(StandardCharsets.ISO_8859_1);
        int offset = 10 + header.length;
        byte[] npy = new byte[Math.addExact(offset, Math.multiplyExact(length, dtype.itemSize))];
        System.arraycopy(MAGIC, 0, npy, 0, MAGIC.length);
        npy[6] = 1;
        npy[7] = 0;
        npy[8] = (byte) header.length;
        npy[9] = (byte) (header.length >>> 8);
        System.arraycopy(header, 0, npy, 10, header.length);
        return new NDArray(dtype, resolved, npy, offset);
    }

    private static int count(int[] shape) {
        long total = 1;
        for (int dim : shape) {
            total *= dim;
        }
        return Math.toIntExact(total);
    }

    private static String shapeTuple(int[] shape) {
        if (shape.length == 1) {
            return "(" + shape[0] + ",)";
        }
        StringBuilder tuple = new StringBuilder("(");
        for (int i = 0; i < shape.length; i++) {
            if (i > 0) {
                tuple.append(", ");
            }
            tuple.append(shape[i]);
        }
        return tuple.append(')').toString();
    }

    private static String group(Pattern pattern, String dict) {
        Matcher matcher = pattern.matcher(dict);
        if (!matcher.find()) {
            throw new JPyRustException("Malformed .npy header: " + dict.trim());
        }
        return matcher.group(1);
    }
}
