package net.vulkanmod.vitrail;

import net.vulkanmod.Initializer;
import net.vulkanmod.vitrail.gpu.GpuBuffer;
import net.vulkanmod.vitrail.gpu.GpuDevice;
import net.vulkanmod.vitrail.gpu.GpuFormat;
import net.vulkanmod.vitrail.gpu.GpuPass;
import net.vulkanmod.vitrail.gpu.GpuPipeline;
import net.vulkanmod.vitrail.gpu.GpuTexture;
import net.vulkanmod.vulkan.Renderer;
import net.vulkanmod.vulkan.memory.ReadbackBuffer;
import net.vulkanmod.vulkan.texture.ImageUtil;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkCommandBuffer;

import java.nio.ByteBuffer;

import static org.lwjgl.vulkan.VK10.*;

/**
 * Draws one full screen triangle through the facade and checks what landed in the target.
 * <p>
 * The point is not the picture: it is that every part of the path a pack will use has been
 * exercised against the real device at least once. Two textures are sampled at their centre, mixed
 * with a constant, multiplied by a uniform block, written to an attachment and one texel is copied
 * back. Every one of those is something a pack stage does, and every one has a way to be silently
 * wrong: a block bound at the wrong size reads garbage, a sampler at the wrong slot reads black, a
 * pass ending in the wrong layout reads black on the next stage. The arithmetic is chosen so all
 * three failures give a different number, and the expected value is computed here from the same
 * constants the shader gets, rather than written as a literal someone would later forget to update.
 * <p>
 * It runs once, at frame start, before the main pass opens, and only with
 * {@code -Dvitrail.debug.smoke=true}. Results are read on the following frame, because the copy is
 * recorded in this one; reading it immediately would read memory the GPU may not have written yet.
 */
public final class VitrailFacadeSmoke {

    private static final int SIZE = 64;
    private static final float TINT_R = 0.5f;
    private static final float TINT_G = 0.6f;
    private static final float TINT_B = 0.7f;
    private static final float MIX = 0.25f;
    private static final int TOLERANCE = 3;

    private static final String VERTEX = """
            #version 450

            void main() {
                vec2 corner = vec2((gl_VertexIndex << 1) & 2, gl_VertexIndex & 2);
                gl_Position = vec4(corner * 2.0 - 1.0, 0.0, 1.0);
            }
            """;

    private static final String FRAGMENT = """
            #version 450

            layout(binding = 0, std140) uniform Tint {
                vec4 color;
            };

            layout(binding = 1) uniform sampler2D texA;
            layout(binding = 2) uniform sampler2D texB;

            layout(location = 0) out vec4 fragColor;

            void main() {
                vec3 a = texture(texA, vec2(0.5)).rgb;
                vec3 b = texture(texB, vec2(0.5)).rgb;
                fragColor = vec4(mix(a, b, 0.25) * color.rgb, 1.0);
            }
            """;

    private static GpuTexture sourceA;
    private static GpuTexture sourceB;
    private static GpuTexture output;
    private static GpuBuffer tint;
    private static GpuPipeline program;
    private static GpuPass pass;
    private static ReadbackBuffer readback;

    private static boolean recorded;
    private static boolean reported;
    private static boolean released;
    private static int reportAge;

    private VitrailFacadeSmoke() {
    }

    /**
     * Called once per frame at a point where no pass is open.
     * <p>
     * The bound pass is checked rather than assumed: the engine has several entry points into the
     * frame and starting a pass inside another is a device loss, not a wrong colour.
     */
    public static void tick(VkCommandBuffer commandBuffer, MemoryStack stack) {
        if (!VitrailDebug.SMOKE || released) {
            return;
        }

        if (Renderer.getInstance().getBoundRenderPass() != null) {
            return;
        }

        if (reported) {
            if (++reportAge > 1) {
                release();
                released = true;
            }

            return;
        }

        if (!recorded) {
            record(commandBuffer, stack);
        } else {
            report();
        }
    }

    private static void record(VkCommandBuffer commandBuffer, MemoryStack stack) {
        try {
            sourceA = GpuDevice.texture(GpuFormat.RGBA8, SIZE, SIZE);
            sourceB = GpuDevice.texture(GpuFormat.RGBA8, SIZE, SIZE);
            output = GpuDevice.texture(GpuFormat.RGBA8, SIZE, SIZE);

            tint = GpuDevice.buffer(4 * Float.BYTES);
            tint.writeFloat(0, TINT_R);
            tint.writeFloat(1, TINT_G);
            tint.writeFloat(2, TINT_B);
            tint.writeFloat(3, 1.0f);

            program = GpuDevice.program("vitrail_facade_smoke", VERTEX, FRAGMENT)
                    .block(0, VK_SHADER_STAGE_VERTEX_BIT | VK_SHADER_STAGE_FRAGMENT_BIT, tint)
                    .sampler(1, "texA", 0)
                    .sampler(2, "texB", 1)
                    .build();

            pass = GpuDevice.pass(output);

            // Cleared rather than uploaded: a cleared image is the same on every driver, and the
            // smoke is about the path, not about the transfer queue.
            sourceA.clear(commandBuffer, stack, 1.0f, 0.0f, 0.0f, 1.0f);
            sourceB.clear(commandBuffer, stack, 0.0f, 1.0f, 0.0f, 1.0f);

            pass.begin(commandBuffer, stack, 0.0f, 0.0f, 0.0f, 1.0f);
            pass.bind(0, sourceA);
            pass.bind(1, sourceB);
            pass.drawFullscreen(program);
            pass.end(commandBuffer);

            readback = new ReadbackBuffer(4);
            output.image().transitionImageLayout(stack, commandBuffer, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL);
            ImageUtil.copyImageToBuffer(commandBuffer, readback.getId(), output.image().getId(),
                    0, 1, 1, SIZE / 2, SIZE / 2, 0, 0, 0);
            output.image().transitionImageLayout(stack, commandBuffer,
                    VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);

            recorded = true;
            Initializer.LOGGER.info(
                    "Vitrail: facade smoke recorded, {}x{} pass, expecting about ({}, {}, {})",
                    SIZE, SIZE, channel((1.0f - MIX) * TINT_R), channel(MIX * TINT_G), channel(0.0f));
        } catch (RuntimeException e) {
            // Reported so the next frames skip straight to releasing whatever half of the resources
            // were made, and the age is past the release threshold because there is nothing to read.
            reported = true;
            reportAge = 1;
            Initializer.LOGGER.error("Vitrail: facade smoke could not be recorded", e);
        }
    }

    private static void report() {
        reported = true;
        reportAge = 0;

        ByteBuffer bytes = readback.getByteBuffer();
        int r = bytes.get(0) & 0xFF;
        int g = bytes.get(1) & 0xFF;
        int b = bytes.get(2) & 0xFF;

        int expectedR = channel((1.0f - MIX) * TINT_R);
        int expectedG = channel(MIX * TINT_G);
        int expectedB = channel(0.0f);

        boolean pass = Math.abs(r - expectedR) <= TOLERANCE
                && Math.abs(g - expectedG) <= TOLERANCE
                && Math.abs(b - expectedB) <= TOLERANCE;

        if (pass) {
            Initializer.LOGGER.info("Vitrail: facade smoke PASS, read back ({}, {}, {})", r, g, b);
        } else {
            Initializer.LOGGER.error(
                    "Vitrail: facade smoke FAIL, read back ({}, {}, {}), expected about ({}, {}, {})",
                    r, g, b, expectedR, expectedG, expectedB);
        }
    }

    /** What an 8 bit UNORM attachment stores for a linear value the shader computed. */
    private static int channel(float linear) {
        return Math.round(Math.min(1.0f, Math.max(0.0f, linear)) * 255.0f);
    }

    /**
     * Frees the smoke's resources a couple of frames later.
     * <p>
     * The framebuffer the pass built is destroyed through the engine's free list, so freeing the
     * images it names in the same frame would race that list. Two frames is well past it, and the
     * cost of being wrong here is a validation message, not a crash worth optimising.
     */
    private static void release() {
        if (pass != null) {
            pass.close();
            pass = null;
        }

        if (sourceA != null) {
            sourceA.close();
            sourceA = null;
        }

        if (sourceB != null) {
            sourceB.close();
            sourceB = null;
        }

        if (output != null) {
            output.close();
            output = null;
        }

        if (tint != null) {
            tint.close();
            tint = null;
        }

        if (readback != null) {
            readback.freeBuffer();
            readback = null;
        }

        // The pipeline is left to the device teardown: the descriptor sets the engine caches per
        // frame hold the layout, and one leaked pipeline at shutdown is cheaper than proving none
        // of them is still referenced.
        program = null;
    }
}
