package net.vulkanmod.vulkan.shader.descriptor;

import org.lwjgl.system.MemoryUtil;

public class ManualUBO extends UBO {

    private long srcPtr;
    private int srcSize;

    private boolean update = true;

    public ManualUBO(int binding, int type, int size) {
        super(binding, type, size * 4, null);
    }

    /** How many times a block has been copied to the GPU, for the front end's own diagnosis. */
    public static int UPDATES;

    @Override
    public void update(long ptr) {
        // update manually
        if (update) {
            MemoryUtil.memCopy(this.srcPtr, ptr, this.srcSize);
            UPDATES++;
        }
    }

    public void setSrc(long ptr, int size) {
        this.srcPtr = ptr;
        this.srcSize = size;
    }

    public void setUpdate(boolean update) {
        this.update = update;
    }
}
