package net.vulkanmod.vitrail.gpu;

/**
 * The facade's entry point: the five things a pack stage needs the engine to make.
 * <p>
 * Kept as static factories rather than an object because the engine itself is static
 * ({@code Renderer.getInstance()}, {@code Vulkan.getVkDevice()}), and a device handle here would
 * only pretend the lifetime is per-object. When the runtime is rewired onto real per-frame
 * resources this becomes the seam to split, and nothing else in the facade names a Vulkan type.
 */
public final class GpuDevice {

    private GpuDevice() {
    }

    /** A sampled, attachable, transferable image, filtered linearly. */
    public static GpuTexture texture(GpuFormat format, int width, int height) {
        return GpuTexture.create(format, width, height, true);
    }

    /** A nearest-filtered image, for the targets a pack reads texel by texel. */
    public static GpuTexture nearestTexture(GpuFormat format, int width, int height) {
        return GpuTexture.create(format, width, height, false);
    }

    /** Host memory for one std140 block. */
    public static GpuBuffer buffer(int sizeBytes) {
        return new GpuBuffer(sizeBytes);
    }

    /** A full screen program from Vulkan GLSL. */
    public static GpuPipeline.Builder program(String name, String vertexGlsl, String fragmentGlsl) {
        return GpuPipeline.builder(name, vertexGlsl, fragmentGlsl);
    }

    /** A pass writing the given textures, in fragment output order. */
    public static GpuPass pass(GpuTexture... colors) {
        return GpuPass.of(colors);
    }

    /** A pass that keeps what its textures hold, for a chain where one stage reads the last one. */
    public static GpuPass loadingPass(GpuTexture... colors) {
        return GpuPass.loading(colors);
    }
}
