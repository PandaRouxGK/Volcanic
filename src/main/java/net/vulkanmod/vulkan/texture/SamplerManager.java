package net.vulkanmod.vulkan.texture;

import it.unimi.dsi.fastutil.shorts.Short2LongMap;
import it.unimi.dsi.fastutil.shorts.Short2LongOpenHashMap;
import net.vulkanmod.vulkan.device.DeviceManager;
import org.apache.commons.lang3.Validate;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkSamplerCreateInfo;
import org.lwjgl.vulkan.VkSamplerReductionModeCreateInfo;

import java.nio.LongBuffer;

import static net.vulkanmod.vulkan.Vulkan.getVkDevice;
import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.VK10.*;
import static org.lwjgl.vulkan.VK12.VK_SAMPLER_REDUCTION_MODE_MAX;
import static org.lwjgl.vulkan.VK12.VK_SAMPLER_REDUCTION_MODE_MIN;

public abstract class SamplerManager {
    static final float MIP_BIAS = -0.5f;

    static final Short2LongMap SAMPLERS = new Short2LongOpenHashMap();

    /** The one comparison sampler, built on first use and never keyed by flags. */
    private static long SHADOW_SAMPLER = 0L;

    public static long getTextureSampler(byte maxLod, byte flags) {
        short key = (short) (flags | (maxLod << 8));
        long sampler = SAMPLERS.getOrDefault(key, 0L);

        if (sampler == 0L) {
            sampler = createTextureSampler(maxLod, flags);
            SAMPLERS.put(key, sampler);
        }

        return sampler;
    }

    private static long createTextureSampler(byte maxLod, byte flags) {
        Validate.isTrue(
                (flags & (REDUCTION_MIN_BIT | REDUCTION_MAX_BIT)) != (REDUCTION_MIN_BIT | REDUCTION_MAX_BIT)
        );

        try (MemoryStack stack = stackPush()) {

            VkSamplerCreateInfo samplerInfo = VkSamplerCreateInfo.calloc(stack);
            samplerInfo.sType(VK_STRUCTURE_TYPE_SAMPLER_CREATE_INFO);

            boolean magLinear = (flags & LINEAR_FILTERING_BIT) != 0;
            boolean minLinear = magLinear || (flags & LINEAR_MIN_BIT) != 0;
            samplerInfo.magFilter(magLinear ? VK_FILTER_LINEAR : VK_FILTER_NEAREST);
            samplerInfo.minFilter(minLinear ? VK_FILTER_LINEAR : VK_FILTER_NEAREST);

            if ((flags & CLAMP_BIT) != 0) {
                samplerInfo.addressModeU(VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE);
                samplerInfo.addressModeV(VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE);
                samplerInfo.addressModeW(VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE);
            } else {
                samplerInfo.addressModeU(VK_SAMPLER_ADDRESS_MODE_REPEAT);
                samplerInfo.addressModeV(VK_SAMPLER_ADDRESS_MODE_REPEAT);
                samplerInfo.addressModeW(VK_SAMPLER_ADDRESS_MODE_REPEAT);
            }

            samplerInfo.anisotropyEnable(false);
            //samplerInfo.maxAnisotropy(16.0f);
            samplerInfo.borderColor(VK_BORDER_COLOR_INT_OPAQUE_WHITE);
            samplerInfo.unnormalizedCoordinates(false);
            samplerInfo.compareEnable(false);
            samplerInfo.compareOp(VK_COMPARE_OP_ALWAYS);

            if ((flags & USE_MIPMAPS_BIT) == 0) {
                samplerInfo.mipmapMode(VK_SAMPLER_MIPMAP_MODE_NEAREST);
                samplerInfo.maxLod(0.0F);
                samplerInfo.minLod(0.0F);
            } else {
                if ((flags & MIPMAP_LINEAR_FILTERING_BIT) != 0) {
                    samplerInfo.mipmapMode(VK_SAMPLER_MIPMAP_MODE_LINEAR);
                } else {
                    samplerInfo.mipmapMode(VK_SAMPLER_MIPMAP_MODE_NEAREST);
                }
                samplerInfo.maxLod(maxLod);
                samplerInfo.minLod(0.0F);
                samplerInfo.mipLodBias(MIP_BIAS);
            }

            //Reduction Mode
            if ((flags & (REDUCTION_MAX_BIT | REDUCTION_MIN_BIT)) != 0) {
                VkSamplerReductionModeCreateInfo reductionModeInfo = VkSamplerReductionModeCreateInfo.calloc(stack);
                reductionModeInfo.sType$Default();
                reductionModeInfo.reductionMode((flags & REDUCTION_MAX_BIT) != 0 ? VK_SAMPLER_REDUCTION_MODE_MAX : VK_SAMPLER_REDUCTION_MODE_MIN);
                samplerInfo.pNext(reductionModeInfo.address());
            }

            LongBuffer pTextureSampler = stack.mallocLong(1);

            if (vkCreateSampler(getVkDevice(), samplerInfo, null, pTextureSampler) != VK_SUCCESS) {
                throw new RuntimeException("Failed to create texture sampler");
            }

            return pTextureSampler.get(0);
        }
    }

    /**
     * The sampler a depth map is read through when a shader declares a shadow sampler.
     * <p>
     * A pack declares {@code sampler2DShadow} and calls {@code texture2DShadow}, which is a request
     * for the hardware to compare the reference it passes against the depth it fetches. Bound with
     * one of the ordinary samplers above, compare disabled, the same call returns the depth it found
     * instead of a comparison: a lit surface reads as occluded and an occluded one as lit, so a map
     * forty per cent full produced a frame with no shadow at all. The comparison is against
     * {@code LESS_OR_EQUAL}, which is what a shadow lookup means — a fragment is lit when it is no
     * further from the sun than the nearest thing the map recorded — and the same operator every
     * OpenGL pack is written against.
     * <p>
     * Outside the map the border reads as depth one, so the comparison answers lit rather than
     * casting the map's edge across the world. Filtering stays nearest, matching the depth the
     * engine writes to it, so the pack's own offset loop is what softens an edge.
     */
    public static long getShadowSampler() {
        if (SHADOW_SAMPLER != 0L) {
            return SHADOW_SAMPLER;
        }

        try (MemoryStack stack = stackPush()) {
            VkSamplerCreateInfo samplerInfo = VkSamplerCreateInfo.calloc(stack);
            samplerInfo.sType(VK_STRUCTURE_TYPE_SAMPLER_CREATE_INFO);

            samplerInfo.magFilter(VK_FILTER_NEAREST);
            samplerInfo.minFilter(VK_FILTER_NEAREST);
            samplerInfo.mipmapMode(VK_SAMPLER_MIPMAP_MODE_NEAREST);
            samplerInfo.maxLod(0.0F);
            samplerInfo.minLod(0.0F);

            samplerInfo.addressModeU(VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_BORDER);
            samplerInfo.addressModeV(VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_BORDER);
            samplerInfo.addressModeW(VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_BORDER);
            samplerInfo.borderColor(VK_BORDER_COLOR_FLOAT_OPAQUE_WHITE);

            samplerInfo.anisotropyEnable(false);
            samplerInfo.unnormalizedCoordinates(false);
            samplerInfo.compareEnable(true);
            samplerInfo.compareOp(VK_COMPARE_OP_LESS_OR_EQUAL);

            LongBuffer pTextureSampler = stack.mallocLong(1);

            if (vkCreateSampler(getVkDevice(), samplerInfo, null, pTextureSampler) != VK_SUCCESS) {
                throw new RuntimeException("Failed to create shadow sampler");
            }

            SHADOW_SAMPLER = pTextureSampler.get(0);
            return SHADOW_SAMPLER;
        }
    }

    public static void cleanUp() {
        for (long id : SAMPLERS.values()) {
            vkDestroySampler(DeviceManager.vkDevice, id, null);
        }

        if (SHADOW_SAMPLER != 0L) {
            vkDestroySampler(DeviceManager.vkDevice, SHADOW_SAMPLER, null);
            SHADOW_SAMPLER = 0L;
        }
    }

    public static final byte LINEAR_FILTERING_BIT = 0b1;
    public static final byte CLAMP_BIT = 0b10;
    public static final byte USE_MIPMAPS_BIT = 0b100;
    public static final byte MIPMAP_LINEAR_FILTERING_BIT = 0b1000;
    public static final byte REDUCTION_MIN_BIT = 0b10000;
    public static final byte REDUCTION_MAX_BIT = 0b100000;
    public static final byte LINEAR_MIN_BIT = 0b1000000;
}
