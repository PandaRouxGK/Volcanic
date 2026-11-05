package net.vulkanmod.vitrail.gpu;

import net.vulkanmod.vulkan.Renderer;
import net.vulkanmod.vulkan.VRenderSystem;
import net.vulkanmod.vulkan.framebuffer.Framebuffer;
import net.vulkanmod.vulkan.framebuffer.RenderPass;
import net.vulkanmod.vulkan.texture.VTextureSelector;
import net.vulkanmod.vulkan.util.DrawUtil;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VkCommandBuffer;

import java.util.Arrays;

/**
 * A render pass over images the caller owns, ending with them readable by the next pass.
 * <p>
 * The pass always ends in {@code SHADER_READ_ONLY_OPTIMAL}, because that is the only thing the
 * caller does with the result: a pack stage hands its colour to the next stage as a texture. It
 * also means the transitions are the render pass's job, not the caller's, which is where a
 * hand-written chain usually gets them wrong.
 */
public final class GpuPass implements AutoCloseable {

    private final GpuTexture[] colors;
    private final Framebuffer framebuffer;
    private final RenderPass renderPass;

    private GpuPass(GpuTexture[] colors, Framebuffer framebuffer, RenderPass renderPass) {
        this.colors = colors;
        this.framebuffer = framebuffer;
        this.renderPass = renderPass;
    }

    /**
     * A pass whose colour attachments are the given textures, in fragment output order.
     */
    public static GpuPass of(GpuTexture... colors) {
        return over(colors, true);
    }

    /**
     * A pass that keeps what its attachments already hold.
     * <p>
     * What a chain needs: a pass reads what the one before it wrote, in the same buffers, and a
     * pass that cleared them would throw that away. A pack's stages are written against a target
     * that is loaded, so a chain built out of clearing passes draws its first stage and blanks
     * every one after it.
     */
    public static GpuPass loading(GpuTexture... colors) {
        return over(colors, false);
    }

    private static GpuPass over(GpuTexture[] colors, boolean clear) {
        if (colors.length == 0) {
            throw new IllegalArgumentException("A pass needs at least one colour attachment");
        }

        for (int i = 1; i < colors.length; i++) {
            if (colors[i].width() != colors[0].width() || colors[i].height() != colors[0].height()) {
                throw new IllegalArgumentException("Colour attachments of one pass must share a size");
            }
        }

        Framebuffer framebuffer = Framebuffer.builder(images(colors), null).build();

        RenderPass.Builder builder = RenderPass.builder(framebuffer);
        for (int i = 0; i < colors.length; i++) {
            builder.getColorAttachmentInfo(i)
                    .setOps(clear ? VK10.VK_ATTACHMENT_LOAD_OP_CLEAR : VK10.VK_ATTACHMENT_LOAD_OP_LOAD,
                            VK10.VK_ATTACHMENT_STORE_OP_STORE)
                    .setFinalLayout(VK10.VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
        }

        return new GpuPass(colors, framebuffer, builder.build());
    }

    private static net.vulkanmod.vulkan.texture.VulkanImage[] images(GpuTexture[] colors) {
        net.vulkanmod.vulkan.texture.VulkanImage[] images =
                new net.vulkanmod.vulkan.texture.VulkanImage[colors.length];

        for (int i = 0; i < colors.length; i++) {
            images[i] = colors[i].image();
        }

        return images;
    }

    public int width() {
        return this.colors[0].width();
    }

    public int height() {
        return this.colors[0].height();
    }

    public GpuTexture color(int index) {
        return this.colors[index];
    }

    /** Records the pass, clearing every attachment to {@code clear} in linear space. */
    public void begin(VkCommandBuffer commandBuffer, MemoryStack stack, float r, float g, float b, float a) {
        float[] previous = {
                VRenderSystem.clearColor.get(0), VRenderSystem.clearColor.get(1),
                VRenderSystem.clearColor.get(2), VRenderSystem.clearColor.get(3)
        };

        VRenderSystem.clearColor.put(0, r).put(1, g).put(2, b).put(3, a);
        this.framebuffer.beginRenderPass(commandBuffer, this.renderPass, stack);

        // The pass snapshot of the clear is taken at vkCmdBeginRenderPass, so the engine's own
        // value can go back before any draw is recorded: the main pass sets it again anyway.
        VRenderSystem.clearColor.put(0, previous[0]).put(1, previous[1])
                .put(2, previous[2]).put(3, previous[3]);
    }

    /**
     * Records the pass without clearing, for a chain whose attachments carry the previous stage's
     * output. The images are the render pass's to transition, as in {@link #begin}.
     */
    public void beginLoaded(VkCommandBuffer commandBuffer, MemoryStack stack) {
        this.framebuffer.beginRenderPass(commandBuffer, this.renderPass, stack);
    }

    /** Makes a texture readable at the given selector slot for the next draw. */
    public void bind(int slot, GpuTexture texture) {
        VTextureSelector.bindTexture(slot, texture.image());
    }

    /**
     * The same, for an image the facade does not own — the engine's depth, its shadow map, the
     * white texel a name this engine cannot answer falls back to.
     */
    public void bind(int slot, net.vulkanmod.vulkan.texture.VulkanImage image) {
        VTextureSelector.bindTexture(slot, image);
    }

    public void drawFullscreen(GpuPipeline pipeline) {
        DrawUtil.blit(pipeline.pipeline());
    }

    public void end(VkCommandBuffer commandBuffer) {
        Renderer.getInstance().endRenderPass(commandBuffer);
    }

    @Override
    public void close() {
        this.renderPass.cleanUp();
        this.framebuffer.cleanUp(false);
        Arrays.fill(this.colors, null);
    }
}
