package net.vulkanmod.vitrail.gpu;

import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * A CPU-written buffer the GPU reads, which for a full screen stage means one std140 block.
 * <p>
 * Host memory rather than a Vulkan buffer on purpose: the engine copies a block into its own
 * per-frame uniform buffer at bind time, so the facade only has to keep the bytes alive and hand
 * over an address. That is the same contract {@code ManualUBO} already implements, and it means a
 * pass writes its uniforms with a float store instead of a mapping call.
 * <p>
 * The order is native, because the shader reads the block as words and std140 says nothing about
 * the host's byte order. A buffer must outlive the pipeline built over it.
 */
public final class GpuBuffer implements AutoCloseable {

    private final ByteBuffer data;
    private final boolean owned;

    public GpuBuffer(int sizeBytes) {
        // Cleared, not merely allocated: a pack block the engine has not filled yet must read as
        // zeros, and host memory arrives with whatever the previous allocation left in it.
        this.data = MemoryUtil.memCalloc(sizeBytes).order(ByteOrder.nativeOrder());
        this.owned = true;
    }

    private GpuBuffer(ByteBuffer data, boolean owned) {
        this.data = data.order(ByteOrder.nativeOrder());
        this.owned = owned;
    }

    /**
     * A block over memory the caller already owns, which is what a frame-written block needs.
     * <p>
     * Nothing is allocated and nothing is freed: the engine copies the bytes at bind time, so the
     * only thing this adds is the facade's handle on a range it did not make. Closing it is a
     * no-op, which is why a pipeline may own it without owning the memory behind it.
     */
    public static GpuBuffer wrap(long address, int sizeBytes) {
        return new GpuBuffer(MemoryUtil.memByteBuffer(address, sizeBytes), false);
    }

    public long address() {
        return MemoryUtil.memAddress(this.data);
    }

    public int size() {
        return this.data.capacity();
    }

    public ByteBuffer data() {
        return this.data;
    }

    public void writeFloat(int floatIndex, float value) {
        this.data.putFloat(floatIndex * Float.BYTES, value);
    }

    public void writeInt(int floatIndex, int value) {
        this.data.putInt(floatIndex * Float.BYTES, value);
    }

    @Override
    public void close() {
        if (this.owned) {
            MemoryUtil.memFree(this.data);
        }
    }
}
