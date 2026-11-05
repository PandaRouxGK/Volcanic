package net.vulkanmod.vulkan.memory;

import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;

import static org.lwjgl.vulkan.VK10.VK_BUFFER_USAGE_TRANSFER_DST_BIT;

public class ReadbackBuffer extends Buffer {

    public ReadbackBuffer(int size) {
        super(VK_BUFFER_USAGE_TRANSFER_DST_BIT, MemoryTypes.HOST_MEM);
        this.createBuffer(size);
    }

    public ByteBuffer getByteBuffer() {
        return this.data.getByteBuffer(0, this.bufferSize);
    }

    /**
     * Copies the whole buffer out through a fresh mapping, which the allocator invalidates on unmap.
     * <p>
     * Host memory here is cached, not coherent: the bytes a copy wrote become readable once the mapping
     * has been invalidated. Reading through the persistent mapping of {@link #getByteBuffer()} can hand
     * back stale zeros — it made a frame whose own PNG had content measure as black. The caller owns the
     * returned buffer and releases it with {@link MemoryUtil#memFree}.
     */
    public ByteBuffer copyOut() {
        ByteBuffer out = MemoryUtil.memAlloc(this.bufferSize);

        MemoryManager.MapAndCopy(this.allocation, data ->
                MemoryUtil.memCopy(data.get(0), MemoryUtil.memAddress(out), this.bufferSize));

        return out;
    }
}
