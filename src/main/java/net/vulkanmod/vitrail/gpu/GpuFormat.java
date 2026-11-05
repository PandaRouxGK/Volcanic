package net.vulkanmod.vitrail.gpu;

import static org.lwjgl.vulkan.VK10.*;

/**
 * The pixel formats the facade can create, named the way a shader pack names them rather than the
 * way Vulkan does.
 * <p>
 * This is deliberately a closed list. A pack asks for four or five formats; a port that carries
 * every {@code VkFormat} would carry a mapping table nobody exercises, and the first format nobody
 * tested is the one that comes out black. Each constant here has a known texel size and a known
 * colour aspect, which is what the readback and the blit paths need.
 * <p>
 * The list grew with the target plan in P2: the packs in hand declare {@code RGBA8},
 * {@code RGBA16}, {@code RGBA16F}, {@code RGBA32F}, {@code RGB10_A2} and {@code RG11B10F} for their
 * colour buffers, and a plan asking for one of those is not a reason to fall back to eight bits.
 * Anything outside it is refused by name rather than silently narrowed.
 */
public enum GpuFormat {

    RGBA8(VK_FORMAT_R8G8B8A8_UNORM, 4),
    RGBA16(VK_FORMAT_R16G16B16A16_UNORM, 8),
    RGBA16F(VK_FORMAT_R16G16B16A16_SFLOAT, 8),
    RGBA32F(VK_FORMAT_R32G32B32A32_SFLOAT, 16),
    RG8(VK_FORMAT_R8G8_UNORM, 2),
    R8(VK_FORMAT_R8_UNORM, 1),
    RG16F(VK_FORMAT_R16G16_SFLOAT, 4),
    R16F(VK_FORMAT_R16_SFLOAT, 2),
    R32F(VK_FORMAT_R32_SFLOAT, 4),
    RGB10A2(VK_FORMAT_A2B10G10R10_UNORM_PACK32, 4),
    /**
     * What BSL declares for its scene buffer, {@code R11F_G11F_B10F}.
     * <p>
     * Vulkan names the components of a packed format from the low bits up, so the format GL calls
     * {@code RGB11F_G11F_B10F} is {@code B10G11R11} here. Reading that as a different format is the
     * easy mistake, and it shows as a red and blue scene rather than as an error.
     */
    RG11B10F(VK_FORMAT_B10G11R11_UFLOAT_PACK32, 4);

    public final int vkFormat;
    public final int bytesPerTexel;

    GpuFormat(int vkFormat, int bytesPerTexel) {
        this.vkFormat = vkFormat;
        this.bytesPerTexel = bytesPerTexel;
    }

    public boolean isFloat() {
        return switch (this) {
            case RGBA16F, RGBA32F, RG16F, R16F, R32F, RG11B10F -> true;
            default -> false;
        };
    }

    /** The four bytes of one texel as the readback path decodes them, in the format's own order. */
    public int channels() {
        return switch (this) {
            case RGBA8, RGBA16, RGBA16F, RGBA32F -> 4;
            case RG8, RG16F, RGB10A2, RG11B10F -> 2;
            case R8, R16F, R32F -> 1;
        };
    }
}
