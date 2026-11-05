package net.vulkanmod.vulkan.framebuffer;

import net.vulkanmod.vulkan.Renderer;
import net.vulkanmod.vulkan.VRenderSystem;
import net.vulkanmod.vulkan.Vulkan;
import net.vulkanmod.vulkan.memory.MemoryManager;
import net.vulkanmod.vulkan.texture.VulkanImage;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;

import java.nio.LongBuffer;
import java.util.Arrays;
import java.util.Objects;

import static org.lwjgl.vulkan.KHRSwapchain.VK_IMAGE_LAYOUT_PRESENT_SRC_KHR;
import static org.lwjgl.vulkan.VK10.*;

public class RenderPass {
    Framebuffer framebuffer;
    long id;

    private static int creations;

    public static int consumeCreations() {
        int value = creations;
        creations = 0;
        return value;
    }

    final int attachmentCount;
    private final int compatibilityKey;
    private final int colorFormat;
    private final int colorFormat2;
    private final int depthAttachmentFormat;
    private final int colorFinalLayout;
    private final int depthFinalLayout;
    private final int fbColorAttachmentCount;
    private final int fbDepthFormat;
    AttachmentInfo colorAttachmentInfo;
    AttachmentInfo colorAttachmentInfo2;
    /** Colour attachments past the second, in the order fragment output locations number them. */
    AttachmentInfo[] extraColorAttachmentInfos = new AttachmentInfo[0];
    AttachmentInfo depthAttachmentInfo;

    private static int formatOf(AttachmentInfo info) {
        return info != null ? info.format : VK_FORMAT_UNDEFINED;
    }

    private static int finalLayoutOf(AttachmentInfo info) {
        return info != null ? info.finalLayout : VK_IMAGE_LAYOUT_UNDEFINED;
    }

    private static int colorAttachmentCountOf(Framebuffer framebuffer) {
        return framebuffer != null ? framebuffer.getColorAttachmentCount() : 0;
    }

    private static int depthFormatOf(Framebuffer framebuffer) {
        return framebuffer != null ? framebuffer.getDepthFormat() : VK_FORMAT_UNDEFINED;
    }

    /** How many colour attachments this pass declares, in the order outputs are numbered. */
    int colorCount() {
        return (this.colorAttachmentInfo != null ? 1 : 0)
                + (this.colorAttachmentInfo2 != null ? 1 : 0)
                + this.extraColorAttachmentInfos.length;
    }

    /** One colour attachment by output location. */
    AttachmentInfo colorInfoAt(int index) {
        if (index == 0) {
            return this.colorAttachmentInfo;
        }

        if (index == 1) {
            return this.colorAttachmentInfo2;
        }

        return this.extraColorAttachmentInfos[index - 2];
    }

    private int[] extraFormats() {
        int[] formats = new int[this.extraColorAttachmentInfos.length];

        for (int i = 0; i < formats.length; i++) {
            formats[i] = formatOf(this.extraColorAttachmentInfos[i]);
        }

        return formats;
    }

    public int getCompatibilityKey() {
        return this.compatibilityKey;
    }

    public static boolean compatible(RenderPass a, RenderPass b) {
        if (a == b) {
            return true;
        }
        if (a == null || b == null) {
            return false;
        }

        return a.attachmentCount == b.attachmentCount
                && a.colorFormat == b.colorFormat
                && a.colorFormat2 == b.colorFormat2
                && a.depthAttachmentFormat == b.depthAttachmentFormat
                && a.colorFinalLayout == b.colorFinalLayout
                && a.depthFinalLayout == b.depthFinalLayout
                && a.fbColorAttachmentCount == b.fbColorAttachmentCount
                && a.fbDepthFormat == b.fbDepthFormat
                && Arrays.equals(a.extraFormats(), b.extraFormats());
    }

    public RenderPass(Framebuffer framebuffer, AttachmentInfo colorAttachmentInfo, AttachmentInfo colorAttachmentInfo2, AttachmentInfo depthAttachmentInfo) {
        this(framebuffer, colorAttachmentInfo, colorAttachmentInfo2, new AttachmentInfo[0], depthAttachmentInfo);
    }

    public RenderPass(Framebuffer framebuffer, AttachmentInfo colorAttachmentInfo, AttachmentInfo colorAttachmentInfo2,
                      AttachmentInfo[] extraColorAttachmentInfos, AttachmentInfo depthAttachmentInfo) {
        this.framebuffer = framebuffer;
        this.colorAttachmentInfo = colorAttachmentInfo;
        this.colorAttachmentInfo2 = colorAttachmentInfo2;
        this.extraColorAttachmentInfos = extraColorAttachmentInfos != null ? extraColorAttachmentInfos : new AttachmentInfo[0];
        this.depthAttachmentInfo = depthAttachmentInfo;

        int count = colorCount();
        if (depthAttachmentInfo != null)
            count++;

        this.attachmentCount = count;
        this.colorFormat = formatOf(colorAttachmentInfo);
        this.colorFormat2 = formatOf(colorAttachmentInfo2);
        this.depthAttachmentFormat = formatOf(depthAttachmentInfo);
        this.colorFinalLayout = finalLayoutOf(colorAttachmentInfo);
        this.depthFinalLayout = finalLayoutOf(depthAttachmentInfo);
        this.fbColorAttachmentCount = colorAttachmentCountOf(framebuffer);
        this.fbDepthFormat = depthFormatOf(framebuffer);
        this.compatibilityKey = Objects.hash(count, this.colorFormat, this.colorFormat2,
                this.depthAttachmentFormat, this.colorFinalLayout, this.depthFinalLayout,
                this.fbColorAttachmentCount, this.fbDepthFormat, Arrays.hashCode(extraFormats()));

        if (!Vulkan.DYNAMIC_RENDERING) {
            framebuffer.addRenderPass(this);

            createRenderPass();
        }

    }

    private void createRenderPass() {
        creations++;

        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkAttachmentDescription.Buffer attachments = VkAttachmentDescription.calloc(attachmentCount, stack);
            VkAttachmentReference.Buffer attachmentRefs = VkAttachmentReference.calloc(attachmentCount, stack);

            VkSubpassDescription.Buffer subpass = VkSubpassDescription.calloc(1, stack);
            subpass.pipelineBindPoint(VK_PIPELINE_BIND_POINT_GRAPHICS);

            int colorCount = colorCount();
            VkAttachmentReference.Buffer colorRefs = colorCount > 0 ? VkAttachmentReference.calloc(colorCount, stack) : null;
            int i = 0;
            int c = 0;

            for (int index = 0; index < colorCount; index++) {
                AttachmentInfo info = colorInfoAt(index);

                attachments.get(i).format(info.format)
                        .samples(VK_SAMPLE_COUNT_1_BIT)
                        .loadOp(info.loadOp)
                        .storeOp(info.storeOp)
                        .stencilLoadOp(VK_ATTACHMENT_LOAD_OP_DONT_CARE)
                        .stencilStoreOp(VK_ATTACHMENT_STORE_OP_DONT_CARE)
                        .initialLayout(VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL)
                        .finalLayout(info.finalLayout);
                colorRefs.get(c++).attachment(i).layout(VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL);
                ++i;
            }

            if (colorCount > 0) {
                subpass.colorAttachmentCount(colorCount);
                subpass.pColorAttachments(colorRefs);
            }

            if (depthAttachmentInfo != null) {
                attachments.get(i).format(depthAttachmentInfo.format)
                        .samples(VK_SAMPLE_COUNT_1_BIT)
                        .loadOp(depthAttachmentInfo.loadOp)
                        .storeOp(depthAttachmentInfo.storeOp)
                        .stencilLoadOp(depthAttachmentInfo.loadOp)
                        .stencilStoreOp(VK_ATTACHMENT_STORE_OP_STORE)
                        .initialLayout(VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL)
                        .finalLayout(depthAttachmentInfo.finalLayout);

                VkAttachmentReference depthAttachmentRef = VkAttachmentReference.calloc(stack)
                        .attachment(i)
                        .layout(VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL);

                subpass.pDepthStencilAttachment(depthAttachmentRef);
            }

            VkRenderPassCreateInfo renderPassInfo = VkRenderPassCreateInfo.calloc(stack);
            renderPassInfo.sType$Default()
                    .pAttachments(attachments)
                    .pSubpasses(subpass);

            if (colorAttachmentInfo != null && colorAttachmentInfo.finalLayout == VK_IMAGE_LAYOUT_PRESENT_SRC_KHR) {
                VkSubpassDependency.Buffer subpassDependencies = VkSubpassDependency.calloc(1, stack);
                subpassDependencies.get(0)
                        .srcSubpass(VK_SUBPASS_EXTERNAL)
                        .dstSubpass(0)
                        .srcStageMask(VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT)
                        .dstStageMask(VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT)
                        .srcAccessMask(0)
                        .dstAccessMask(0);

                renderPassInfo.pDependencies(subpassDependencies);
            } else if (colorAttachmentInfo != null && colorAttachmentInfo.finalLayout == VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL) {
                int srcStages = VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT;
                int srcAccess = VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT;
                if (depthAttachmentInfo != null && depthAttachmentInfo.finalLayout == VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL) {
                    srcStages |= VK_PIPELINE_STAGE_EARLY_FRAGMENT_TESTS_BIT | VK_PIPELINE_STAGE_LATE_FRAGMENT_TESTS_BIT;
                    srcAccess |= VK_ACCESS_DEPTH_STENCIL_ATTACHMENT_WRITE_BIT;
                }
                VkSubpassDependency.Buffer subpassDependencies = VkSubpassDependency.calloc(1, stack);
                subpassDependencies.get(0)
                        .srcSubpass(0)
                        .dstSubpass(VK_SUBPASS_EXTERNAL)
                        .srcStageMask(srcStages)
                        .dstStageMask(VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT)
                        .srcAccessMask(srcAccess)
                        .dstAccessMask(VK_ACCESS_SHADER_READ_BIT);

                renderPassInfo.pDependencies(subpassDependencies);
            }

            LongBuffer pRenderPass = stack.mallocLong(1);

            if (vkCreateRenderPass(Vulkan.getVkDevice(), renderPassInfo, null, pRenderPass) != VK_SUCCESS) {
                throw new RuntimeException("Failed to create render pass");
            }

            id = pRenderPass.get(0);
        }
    }

    public void beginRenderPass(VkCommandBuffer commandBuffer, long framebufferId, MemoryStack stack) {

        int colorCount = colorCount();

        for (int index = 0; index < colorCount; index++) {
            VulkanImage image = framebuffer.getColorAttachment(index);

            if (image != null && image.getCurrentLayout() != VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL)
                image.transitionImageLayout(stack, commandBuffer, VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL);
        }

        if (depthAttachmentInfo != null
                && framebuffer.getDepthAttachment().getCurrentLayout() != VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL)
            framebuffer.getDepthAttachment().transitionImageLayout(stack, commandBuffer, VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL);

        VkRenderPassBeginInfo renderPassInfo = VkRenderPassBeginInfo.calloc(stack);
        renderPassInfo.sType$Default();
        renderPassInfo.renderPass(this.id);
        renderPassInfo.framebuffer(framebufferId);

        VkRect2D renderArea = VkRect2D.malloc(stack);
        renderArea.offset().set(0, 0);
        renderArea.extent().set(framebuffer.getWidth(), framebuffer.getHeight());
        renderPassInfo.renderArea(renderArea);

        VkClearValue.Buffer clearValues = VkClearValue.malloc(this.attachmentCount, stack);
        int cv = 0;

        for (int index = 0; index < colorCount; index++) {
            if (index == 0)
                clearValues.get(cv++).color().float32(VRenderSystem.clearColor);
            else
                clearValues.get(cv++).color().float32(stack.floats(0.0f, 0.0f, 0.0f, 0.0f));
        }

        if (depthAttachmentInfo != null)
            clearValues.get(cv).depthStencil().set(VRenderSystem.clearDepthValue, VRenderSystem.clearStencilValue);

        renderPassInfo.pClearValues(clearValues);

        vkCmdBeginRenderPass(commandBuffer, renderPassInfo, VK_SUBPASS_CONTENTS_INLINE);

        Renderer.getInstance().setBoundRenderPass(this);
    }

    public void endRenderPass(VkCommandBuffer commandBuffer) {
        vkCmdEndRenderPass(commandBuffer);

        int colorCount = colorCount();

        for (int index = 0; index < colorCount; index++) {
            AttachmentInfo info = colorInfoAt(index);
            VulkanImage image = framebuffer.getColorAttachment(index);

            if (image != null)
                image.setCurrentLayout(info.finalLayout);
        }

        if (depthAttachmentInfo != null)
            framebuffer.getDepthAttachment().setCurrentLayout(depthAttachmentInfo.finalLayout);

        Renderer.getInstance().setBoundRenderPass(null);
    }

    public void beginDynamicRendering(VkCommandBuffer commandBuffer, MemoryStack stack) {
        VkRect2D renderArea = VkRect2D.malloc(stack);
        renderArea.offset().set(0, 0);
        renderArea.extent().set(framebuffer.getWidth(), framebuffer.getHeight());

        VkClearValue.Buffer clearValues = VkClearValue.malloc(2, stack);
        clearValues.get(0).color().float32(stack.floats(0.0f, 0.0f, 0.0f, 1.0f));
        clearValues.get(1).depthStencil().set(VRenderSystem.clearDepthValue, VRenderSystem.clearStencilValue);

        VkRenderingInfo renderingInfo = VkRenderingInfo.calloc(stack);
        renderingInfo.sType(KHRDynamicRendering.VK_STRUCTURE_TYPE_RENDERING_INFO_KHR);
        renderingInfo.renderArea(renderArea);
        renderingInfo.layerCount(1);

        int colorCount = colorCount();

        if (colorCount > 0) {
            VkRenderingAttachmentInfo.Buffer colorAttachments = VkRenderingAttachmentInfo.calloc(colorCount, stack);

            for (int index = 0; index < colorCount; index++) {
                AttachmentInfo info = colorInfoAt(index);
                VkRenderingAttachmentInfo colorAttachment = colorAttachments.get(index);
                colorAttachment.sType(KHRDynamicRendering.VK_STRUCTURE_TYPE_RENDERING_ATTACHMENT_INFO_KHR);
                colorAttachment.imageView(framebuffer.getColorAttachment(index).getImageView());
                colorAttachment.imageLayout(VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL);
                colorAttachment.loadOp(info.loadOp);
                colorAttachment.storeOp(info.storeOp);
                colorAttachment.clearValue(clearValues.get(0));
            }

            renderingInfo.pColorAttachments(colorAttachments);
        }

        if (depthAttachmentInfo != null) {
            VkRenderingAttachmentInfo depthAttachment = VkRenderingAttachmentInfo.calloc(stack);
            depthAttachment.sType(KHRDynamicRendering.VK_STRUCTURE_TYPE_RENDERING_ATTACHMENT_INFO_KHR);
            depthAttachment.imageView(framebuffer.getDepthAttachment().getImageView());
            depthAttachment.imageLayout(VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL);
            depthAttachment.loadOp(depthAttachmentInfo.loadOp);
            depthAttachment.storeOp(depthAttachmentInfo.storeOp);
            depthAttachment.clearValue(clearValues.get(1));

            renderingInfo.pDepthAttachment(depthAttachment);

            if (VulkanImage.hasStencilComponent(depthAttachmentInfo.format)) {
                VkRenderingAttachmentInfo stencilAttachment = VkRenderingAttachmentInfo.calloc(stack);
                stencilAttachment.sType(KHRDynamicRendering.VK_STRUCTURE_TYPE_RENDERING_ATTACHMENT_INFO_KHR);
                stencilAttachment.imageView(framebuffer.getDepthAttachment().getImageView());
                stencilAttachment.imageLayout(VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL);
                stencilAttachment.loadOp(depthAttachmentInfo.loadOp);
                stencilAttachment.storeOp(depthAttachmentInfo.storeOp);
                stencilAttachment.clearValue(clearValues.get(1));

                renderingInfo.pStencilAttachment(stencilAttachment);
            }
        }

        KHRDynamicRendering.vkCmdBeginRenderingKHR(commandBuffer, renderingInfo);
    }

    public void endDynamicRendering(VkCommandBuffer commandBuffer) {
        KHRDynamicRendering.vkCmdEndRenderingKHR(commandBuffer);
    }

    public Framebuffer getFramebuffer() {
        return framebuffer;
    }

    public void cleanUp() {

        if (!Vulkan.DYNAMIC_RENDERING)
            MemoryManager.getInstance().addFrameOp(
                    () -> vkDestroyRenderPass(Vulkan.getVkDevice(), this.id, null));

    }

    public long getId() {
        return id;
    }

    public static class AttachmentInfo {
        final Type type;
        final int format;
        int finalLayout;
        int loadOp;
        int storeOp;

        public AttachmentInfo(Type type, int format) {
            this.type = type;
            this.format = format;
            this.finalLayout = type.defaultLayout;

            this.loadOp = VK_ATTACHMENT_LOAD_OP_DONT_CARE;
            this.storeOp = VK_ATTACHMENT_STORE_OP_STORE;
        }

        public AttachmentInfo setOps(int loadOp, int storeOp) {
            this.loadOp = loadOp;
            this.storeOp = storeOp;

            return this;
        }

        public AttachmentInfo setLoadOp(int loadOp) {
            this.loadOp = loadOp;

            return this;
        }

        public AttachmentInfo setFinalLayout(int finalLayout) {
            this.finalLayout = finalLayout;

            return this;
        }

        public enum Type {
            COLOR(VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL),
            DEPTH(VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL);

            final int defaultLayout;

            Type(int layout) {
                defaultLayout = layout;
            }
        }
    }

    public static Builder builder(Framebuffer framebuffer) {
        return new Builder(framebuffer);
    }

    public static class Builder {
        Framebuffer framebuffer;
        AttachmentInfo colorAttachmentInfo;
        AttachmentInfo colorAttachmentInfo2;
        AttachmentInfo[] extraColorAttachmentInfos = new AttachmentInfo[0];
        AttachmentInfo depthAttachmentInfo;

        public Builder(Framebuffer framebuffer) {
            this.framebuffer = framebuffer;

            // Declared from the framebuffer's metadata, not from its images: the swapchain answers
            // its own named accessors and keeps no images in this object at all.
            if (framebuffer.hasColorAttachment)
                this.colorAttachmentInfo = new AttachmentInfo(AttachmentInfo.Type.COLOR, framebuffer.format)
                        .setOps(VK_ATTACHMENT_LOAD_OP_CLEAR, VK_ATTACHMENT_STORE_OP_STORE);

            if (framebuffer.format2 != 0)
                this.colorAttachmentInfo2 = new AttachmentInfo(AttachmentInfo.Type.COLOR, framebuffer.format2)
                        .setOps(VK_ATTACHMENT_LOAD_OP_CLEAR, VK_ATTACHMENT_STORE_OP_STORE);

            int extraCount = framebuffer.getExtraColorAttachmentCount();
            this.extraColorAttachmentInfos = new AttachmentInfo[extraCount];

            for (int index = 0; index < extraCount; index++) {
                this.extraColorAttachmentInfos[index] = new AttachmentInfo(AttachmentInfo.Type.COLOR,
                        framebuffer.getColorAttachmentFormat(index + 2))
                        .setOps(VK_ATTACHMENT_LOAD_OP_CLEAR, VK_ATTACHMENT_STORE_OP_STORE);
            }

            if (framebuffer.hasDepthAttachment)
                depthAttachmentInfo = new AttachmentInfo(AttachmentInfo.Type.DEPTH, framebuffer.depthFormat).setOps(VK_ATTACHMENT_LOAD_OP_CLEAR, VK_ATTACHMENT_STORE_OP_DONT_CARE);
        }

        public RenderPass build() {
            return new RenderPass(framebuffer, colorAttachmentInfo, colorAttachmentInfo2, extraColorAttachmentInfos, depthAttachmentInfo);
        }

        public Builder setLoadOp(int loadOp) {
            if (colorAttachmentInfo != null) {
                colorAttachmentInfo.setLoadOp(loadOp);
            }
            for (AttachmentInfo extra : extraColorAttachmentInfos) {
                extra.setLoadOp(loadOp);
            }
            if (depthAttachmentInfo != null) {
                depthAttachmentInfo.setLoadOp(loadOp);
            }

            return this;
        }

        public AttachmentInfo getColorAttachmentInfo() {
            return colorAttachmentInfo;
        }

        public AttachmentInfo getColorAttachmentInfo2() {
            return colorAttachmentInfo2;
        }

        /** One colour attachment by output location, for a pass that declares more than two. */
        public AttachmentInfo getColorAttachmentInfo(int index) {
            if (index == 0) {
                return colorAttachmentInfo;
            }

            if (index == 1) {
                return colorAttachmentInfo2;
            }

            return extraColorAttachmentInfos[index - 2];
        }

        public AttachmentInfo getDepthAttachmentInfo() {
            return depthAttachmentInfo;
        }
    }
}
