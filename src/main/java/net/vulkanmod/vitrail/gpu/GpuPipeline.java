package net.vulkanmod.vitrail.gpu;

import net.vulkanmod.render.vertex.CustomVertexFormat;
import net.vulkanmod.vulkan.shader.GraphicsPipeline;
import net.vulkanmod.vulkan.shader.Pipeline;
import net.vulkanmod.vulkan.shader.descriptor.ImageDescriptor;
import net.vulkanmod.vulkan.shader.descriptor.ManualUBO;
import net.vulkanmod.vulkan.shader.descriptor.UBO;
import net.vulkanmod.vulkan.shader.layout.PushConstants;
import com.mojang.blaze3d.vertex.VertexFormat;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * One full screen program, built from Vulkan GLSL and bound to its blocks by number and its
 * samplers by name.
 * <p>
 * A pack's stage is a vertex/fragment pair that declares a handful of uniform blocks and a list of
 * samplers; the engine needs those as descriptors before it can compile a pipeline layout. The
 * caller therefore names both here instead of having the facade re-read the GLSL with a regex: the
 * plan comes from the pack front end in P2, and this builder is what consumes it.
 * <p>
 * Samplers are addressed by name because that is how the pass binds them, and a name is stable
 * across a pack's recompilation while a slot is not. The slot is what {@code VTextureSelector}
 * writes; the binding is what the shader promised.
 */
public final class GpuPipeline implements AutoCloseable {

    private final String name;
    private final GraphicsPipeline pipeline;
    private final Map<String, Integer> samplerSlots;
    private final List<GpuBuffer> blocks;

    private GpuPipeline(String name, GraphicsPipeline pipeline, Map<String, Integer> samplerSlots,
                        List<GpuBuffer> blocks) {
        this.name = name;
        this.pipeline = pipeline;
        this.samplerSlots = samplerSlots;
        this.blocks = blocks;
    }

    public static Builder builder(String name, String vertexGlsl, String fragmentGlsl) {
        return new Builder(name, vertexGlsl, fragmentGlsl);
    }

    public GraphicsPipeline pipeline() {
        return this.pipeline;
    }

    public String name() {
        return this.name;
    }

    /** The selector slot a sampler name was declared at, or -1 when this program has no such name. */
    public int slotOf(String sampler) {
        return this.samplerSlots.getOrDefault(sampler, -1);
    }

    /**
     * Frees the blocks this pipeline was built over.
     * <p>
     * The engine copies a block's bytes at bind time and holds no reference of its own, so the
     * memory a pipeline was given is the pipeline's to keep alive and to release. A wrapped block
     * is left alone, because its memory belongs to whoever wrote it.
     */
    @Override
    public void close() {
        for (GpuBuffer block : this.blocks) {
            block.close();
        }
    }

    public static final class Builder {

        private final String name;
        private final String vertexGlsl;
        private final String fragmentGlsl;
        private final List<UBO> blocks = new ArrayList<>();
        private final List<GpuBuffer> buffers = new ArrayList<>();
        private final List<ImageDescriptor> samplers = new ArrayList<>();
        private final Map<String, Integer> slots = new LinkedHashMap<>();
        private VertexFormat vertexFormat = CustomVertexFormat.NONE;
        private PushConstants pushConstants;

        private Builder(String name, String vertexGlsl, String fragmentGlsl) {
            this.name = name;
            this.vertexGlsl = vertexGlsl;
            this.fragmentGlsl = fragmentGlsl;
        }

        /**
         * The attributes the vertex stage reads, when it is not a full screen stage.
         * <p>
         * A pack's geometry program reads the chunk mesh's own elements, so the format has to be
         * the one the draw binds. Left alone it is the empty format, which is what a stage taking
         * its corners from the vertex index needs.
         */
        public Builder vertexFormat(VertexFormat format) {
            this.vertexFormat = format;
            return this;
        }

        /**
         * The range the draw pushes, for a program that reads a block outside the descriptors.
         * <p>
         * The engine's terrain path pushes the chunk region's origin this way. A layout without
         * the range refuses the pipeline, and the draw that follows.
         */
        public Builder pushConstants(PushConstants pushConstants) {
            this.pushConstants = pushConstants;
            return this;
        }

        /**
         * Binds a block by its shader binding to host memory.
         * <p>
         * The block is over-declared exactly as the pack declares it, and the size is taken from
         * the buffer rather than from a guess: a descriptor range shorter than the shader's block
         * is refused at the first draw on some drivers and reads the next allocation on others.
         */
        public Builder block(int binding, int stages, GpuBuffer buffer) {
            if (buffer.size() % Float.BYTES != 0) {
                throw new IllegalArgumentException("A std140 block must be a whole number of floats");
            }

            ManualUBO block = new ManualUBO(binding, stages, buffer.size() / Float.BYTES);
            block.setSrc(buffer.address(), buffer.size());
            this.blocks.add(block);
            this.buffers.add(buffer);
            return this;
        }

        public Builder sampler(int binding, String name, int slot) {
            return sampler(binding, "sampler2D", name, slot);
        }

        /**
         * Binds a sampler of a declared type at a selector slot.
         * <p>
         * The type is the pack's, because a pack declares {@code sampler3D} and {@code usampler2D}
         * and an image as readily as a plain 2D sampler, and a descriptor declared as the wrong one
         * of those is refused when the pipeline is built. An image is recognised from its type the
         * same way the old path did it, since nothing else in the declaration says so.
         */
        public Builder sampler(int binding, String type, String name, int slot) {
            this.samplers.add(new ImageDescriptor(binding, type, name, slot, type.contains("image")));
            this.slots.put(name, slot);
            return this;
        }

        /**
         * Compiles both stages. A program that does not build is an error the caller must see:
         * unlike a pack program, which a chain can do without, this is the facade's own test.
         */
        public GpuPipeline build() {
            Pipeline.Builder builder = new Pipeline.Builder(this.vertexFormat);
            builder.compileShaders(this.name, this.vertexGlsl, this.fragmentGlsl);
            builder.setUniforms(this.blocks, this.samplers);

            if (this.pushConstants != null) {
                builder.setPushConstants(this.pushConstants);
            }

            return new GpuPipeline(this.name, builder.createGraphicsPipeline(), this.slots,
                    this.buffers);
        }
    }
}
