package net.vulkanmod.gl;

import it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap;
import it.unimi.dsi.fastutil.ints.IntOpenHashSet;

import java.nio.IntBuffer;

public final class GlVertexArray {
    private static int nextId = 1;
    private static int boundId;

    private static final IntOpenHashSet ARRAYS = new IntOpenHashSet();

    /*
     * Packed key, note that:
     * high 16 bits = VAO id
     * low 16 bits = attribute index
     */
    private static final Int2IntOpenHashMap ATTRIB_DIVISORS = new Int2IntOpenHashMap();

    static {
        ATTRIB_DIVISORS.defaultReturnValue(0);
    }

    private GlVertexArray() {}

    public static int genVertexArray() {
        int id = nextId++;
        ARRAYS.add(id);
        return id;
    }

    public static void genVertexArrays(IntBuffer arrays) {
        for (int i = arrays.position(); i < arrays.limit(); i++)
            arrays.put(i, genVertexArray());
    }

    public static void bindVertexArray(int id) {
        if (id != 0 && !ARRAYS.contains(id))
            ARRAYS.add(id);

        boundId = id;
    }

    public static void deleteVertexArray(int id) {
        ARRAYS.remove(id);

        // Remove divisor state for this VAO
        ATTRIB_DIVISORS.keySet().removeIf(key -> (key >>> 16) == id);

        if (boundId == id)
            boundId = 0;
    }

    public static void deleteVertexArrays(IntBuffer arrays) {
        for (int i = arrays.position(); i < arrays.limit(); i++) {
            deleteVertexArray(arrays.get(i));
        }
    }

    public static void setAttribDivisor(int index, int divisor) {
        if (index < 0 || divisor < 0)
            return;

        int key = packKey(boundId, index);

        if (divisor == 0)
            // OpenGL default
            ATTRIB_DIVISORS.remove(key);
        else
            ATTRIB_DIVISORS.put(key, divisor);
    }

    public static int getAttribDivisor(int index) {
        if (index < 0) {
            return 0;
        }

        return ATTRIB_DIVISORS.get(packKey(boundId, index));
    }

    private static int packKey(int vao, int index) {
        if ((vao & 0xFFFF0000) != 0 || (index & 0xFFFF0000) != 0)
            throw new IllegalArgumentException(
                    "VAO/attribute index too large: vao=" + vao + ", index=" + index
            );

        return (vao << 16) | index;
    }

    public static boolean isVertexArray(int id) {
        return id == 0 || ARRAYS.contains(id);
    }

    public static int boundId() {
        return boundId;
    }
}