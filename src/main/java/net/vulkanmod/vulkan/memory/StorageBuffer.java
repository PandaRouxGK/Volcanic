package net.vulkanmod.vulkan.memory;

import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;

import static org.lwjgl.vulkan.VK10.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT;

public class StorageBuffer extends Buffer {

    public StorageBuffer(int size, MemoryType type) {
        super(VK_BUFFER_USAGE_STORAGE_BUFFER_BIT, type);
        this.createBuffer(size);
    }

    public void recordCopyCmd(ByteBuffer byteBuffer) {
        int size = byteBuffer.remaining();

        if (size > this.bufferSize - this.usedBytes) {
            resizeBuffer(this.usedBytes + size);
        }

        this.type.copyToBuffer(this, size, byteBuffer);

        offset = usedBytes;
        usedBytes += size;
    }

    private void resizeBuffer(int minSize) {
        MemoryManager.getInstance().addToFreeable(this);
        int newSize = Math.max(minSize, this.bufferSize + (this.bufferSize >> 1));
        this.createBuffer(newSize);
        this.usedBytes = 0;
    }

    public void putInt(int offset, int value) {
        if (!this.type.mappable())
            throw new RuntimeException("Buffer is not mappable");

        if (offset + Integer.BYTES > this.bufferSize)
            throw new RuntimeException("Buffer overflow");

        MemoryUtil.memPutInt(this.data.get(0) + offset, value);
    }
}
