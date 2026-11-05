package net.vulkanmod.vulkan.shader.layout;

import java.util.List;

public class PushConstants extends AlignedStruct {

    protected PushConstants(List<Uniform.Info> infoList, int size) {
        super(infoList, size);
    }

    /**
     * The range the engine's chunk draw pushes into a geometry stage.
     * <p>
     * Three floats at offset nought: the origin of the region of chunks being drawn, which the
     * translated vertex stage adds to a vertex's local position before the camera transform. The
     * pack's block carries a tail as well, an age and an id, so the range covers those too — a
     * pipeline layout that promises less than the shader declares is refused when the pipeline is
     * created, while one that promises more than a draw pushes is allowed.
     */
    public static PushConstants region() {
        return new PushConstants(List.of(Uniform.createUniformInfo("vec3", "of_RegionOffset")), 20);
    }

}
