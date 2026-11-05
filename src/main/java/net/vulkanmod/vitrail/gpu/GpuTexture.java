package net.vulkanmod.vitrail.gpu;

import net.vulkanmod.vulkan.texture.VulkanImage;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkClearColorValue;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkImageSubresourceRange;

import static org.lwjgl.vulkan.VK10.*;

/**
 * One image the facade owns: a {@link VulkanImage} created with the usage every stage of a pack
 * needs, and the two layout moves a fresh image has to make before anything may use it.
 * <p>
 * The image is created as a colour attachment, a sampled image and a transfer destination, because
 * a pack's buffer is all three at once across a frame: a pass writes it, the next one samples it,
 * and a clear or a copy reaches it between the two. {@code TRANSFER_SRC} is added as well, since
 * the smoke test and every readback copy read from it.
 * <p>
 * The layout is tracked by {@link VulkanImage} itself, so nothing here guesses: the facade only
 * ever says where the image must be now, and the cost of a redundant transition is Vulkan's to
 * elide.
 */
public final class GpuTexture implements AutoCloseable {

    private final GpuFormat format;
    private final VulkanImage image;

    private GpuTexture(GpuFormat format, VulkanImage image) {
        this.format = format;
        this.image = image;
    }

    public static GpuTexture create(GpuFormat format, int width, int height, boolean linear) {
        return create(format, width, height, linear, true);
    }

    /**
     * The same, with the addressing mode named: a pack's noise is read at coordinates that leave the
     * image on purpose, so it has to repeat rather than smear its border across the screen.
     */
    public static GpuTexture create(GpuFormat format, int width, int height, boolean linear,
                                    boolean clamp) {
        VulkanImage image = VulkanImage.builder(width, height)
                .setFormat(format.vkFormat)
                .setUsage(VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT | VK_IMAGE_USAGE_SAMPLED_BIT
                        | VK_IMAGE_USAGE_TRANSFER_SRC_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT)
                .setLinearFiltering(linear)
                .setClamp(clamp)
                .createVulkanImage();

        return new GpuTexture(format, image);
    }

    /** Fills the image with one colour and leaves it where a sampler may read it. */
    public void clear(VkCommandBuffer commandBuffer, MemoryStack stack, float red, float green, float blue, float alpha) {
        this.image.transitionImageLayout(stack, commandBuffer, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL);

        VkClearColorValue clear = VkClearColorValue.calloc(stack);
        clear.float32(0, red);
        clear.float32(1, green);
        clear.float32(2, blue);
        clear.float32(3, alpha);

        VkImageSubresourceRange.Buffer range = VkImageSubresourceRange.calloc(1, stack);
        range.aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).baseMipLevel(0).levelCount(1)
                .baseArrayLayer(0).layerCount(1);

        vkCmdClearColorImage(commandBuffer, this.image.getId(), VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
                clear, range);

        this.image.transitionImageLayout(stack, commandBuffer, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
    }

    public VulkanImage image() {
        return this.image;
    }

    public GpuFormat format() {
        return this.format;
    }

    public int width() {
        return this.image.width;
    }

    public int height() {
        return this.image.height;
    }

    @Override
    public void close() {
        this.image.free();
    }
}
