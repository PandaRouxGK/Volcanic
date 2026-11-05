package net.vulkanmod.vitrail;

import dev.vitrail.glsl.PackProgram;
import dev.vitrail.glsl.TranslatedUnit;
import dev.vitrail.pack.model.ProgramStage;
import dev.vitrail.pack.model.TargetFormat;
import dev.vitrail.pack.model.TargetSize;
import dev.vitrail.pack.program.ChainFilter;
import dev.vitrail.pack.target.ChainPlan;
import dev.vitrail.pack.target.SamplerPlan;
import dev.vitrail.pack.target.TargetPlan;
import dev.vitrail.pack.target.TargetSchedule;
import dev.vitrail.pack.source.ShaderPackSource;
import dev.vitrail.pack.source.ShaderProperties;
import dev.vitrail.uniform.NoiseTexture;
import net.vulkanmod.Initializer;
import net.vulkanmod.vitrail.gpu.GpuBuffer;
import net.vulkanmod.vitrail.gpu.GpuDevice;
import net.vulkanmod.vitrail.gpu.GpuFormat;
import net.vulkanmod.vitrail.gpu.GpuPass;
import net.vulkanmod.vitrail.gpu.GpuPipeline;
import net.vulkanmod.vitrail.gpu.GpuTexture;
import net.vulkanmod.vulkan.memory.ReadbackBuffer;
import net.vulkanmod.vulkan.texture.ImageUtil;
import net.vulkanmod.vulkan.texture.VTextureSelector;
import net.vulkanmod.vulkan.texture.VulkanImage;

import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkImageBlit;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Function;

import static org.lwjgl.vulkan.VK10.VK_FILTER_NEAREST;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_ASPECT_COLOR_BIT;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_ASPECT_DEPTH_BIT;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL;
import static org.lwjgl.vulkan.VK10.vkCmdBlitImage;
import static org.lwjgl.vulkan.VK10.vkCmdClearColorImage;

import javax.imageio.ImageIO;

/**
 * The chain a pack describes, allocated and compiled through the facade.
 * <p>
 * Everything here comes from the pack and from the front end's own plan: which colour targets
 * exist, what format and size each one is, which half of a ping pong pair a pass writes, and in
 * which order the passes run. None of it is a list in this file. That is the difference between
 * this and {@link VitrailChain}, whose stage list is written out by hand and whose buffers are
 * sixteen fixed indices — the hand-written one is what draws today, and this is what the pack says
 * should be drawn.
 * <p>
 * It builds, compiles, and then draws — but only for a session started with
 * {@code -Dvitrail.debug.draw=true}, because the hand-written chain is still what draws by default.
 * A build prints a report whether anything is drawn or not: which targets were allocated, which
 * programs compiled, which were refused and by what, and which samplers a program reads under a
 * name this engine has nothing behind. The draw prints its own, because the plan's answer and the
 * frame's can differ: a sampler the plan can serve is still white if the engine had no image for it
 * at that moment.
 * <p>
 * The blocks a program reads are filled from the game every frame ({@link VitrailGlobals}).
 */
public final class VitrailPackChain implements AutoCloseable {

    /** The size every pack block is given, as the screen path gives it: over-declared, zeroed. */
    private static final int BLOCK_FLOATS = 1024;

    /** How many bytes of the presented texel a probe reads: enough for any colour format here. */
    private static final int PROBE_BYTES = 16;

    /**
     * How many frames a world needs before its picture is the scene rather than the load.
     * <p>
     * The first frame after entering a world is drawn over a scene the engine has not filled yet:
     * the chunks are still being uploaded and the camera is where the game put it, not where the
     * player saved it. Measuring that frame says nothing about a shader — it was the fault this
     * comparison caught first.
     */
    private static final int PROBE_SETTLED_FRAME = 300;

    private final String packName;
    private final String place;
    private final Map<Integer, GpuTexture[]> targets = new LinkedHashMap<>();
    private final List<Stage> stages = new ArrayList<>();
    private final List<String> refusals = new ArrayList<>();

    /** Every sampler name that got the white texel, by pass, so the report says which. */
    private final List<String> unserved = new ArrayList<>();
    private int programsCompiled;
    private int programsRefused;
    private int samplersServed;
    private int samplersUnserved;
    private int samplersUnbindable;
    private String failure;

    private final Path pack;
    private ChainPlan plan;
    private int width;
    private int height;

    /** Targets whose ping-pong halves the last frame left flipped. */
    private final Set<Integer> flipped = new HashSet<>();

    /** How many frames the probe reports, from {@link #PROBE_SETTLED_FRAME} on. */
    private static final int PROBE_REPORTS = 6;
    private boolean drewOnce;
    private boolean reportedFeedback;
    private int passesDrawn;
    private int framesDrawn;
    private int probeReports;

    /** The pack's own {@code final}, and the image of the screen's size it writes. */
    private Stage finalStage;
    private GpuTexture presentation;

    /** The pack's noise, {@code noisetex}, and its pixels until the first frame uploads them. */
    private GpuTexture noise;
    private NoiseTexture.Image noisePixels;
    private boolean noiseUploaded;

    /** What the last drawn frame really bound, as opposed to what the plan could serve. */
    private int boundServed;
    private int boundWhite;
    private final List<String> whiteNames = new ArrayList<>();
    private boolean reportedBound;

    /** The three images read back for a capture, and whether their numbers have been said. */
    private ReadbackBuffer probe;
    private ReadbackBuffer sceneProbe;

    /**
     * The shadow cascade read back beside the frame, so the map says what it holds.
     * <p>
     * A pipeline installed is not a map drawn: the pass can name its own pipeline and still record
     * nothing into the image a composite pass reads, and a frame that samples an empty map looks
     * like a frame with no shadows whichever program filled it. The depth this reads is the one
     * {@code shadowtex0} is answered with, so a coverage next to the engine's own is the measure of
     * whether the pack's stage wrote the world's geometry into it.
     */
    private ReadbackBuffer shadowProbe;

    /** The one chain this session draws, if it draws one. */
    private static VitrailPackChain drawn;
    private static String drawnKey;

    /**
     * One pass of the chain, as the facade holds it.
     *
     * @param name     the bare program name, {@code composite4}
     * @param targets  the pack's buffers it writes, by index, in draw buffer order
     * @param pass     the pass over those buffers, null for the final, which writes the screen
     * @param pipeline the compiled program, null when it did not build
     * @param samplers what the plan says each of its sampler names reads, in binding order
     */
    public record Stage(String name, List<Integer> targets, GpuPass pass, GpuPipeline pipeline,
                        List<SamplerPlan.Binding> samplers, VitrailGlobals globals) {
    }

    /** One compiled program, with the block it reads its uniforms from. */
    private record Compiled(GpuPipeline pipeline, VitrailGlobals globals) {
    }

    private VitrailPackChain(String packName, String place, Path pack) {
        this.packName = packName;
        this.place = place;
        this.pack = pack;
    }

    public static VitrailPackChain build(Path pack, String dimension, int screenWidth, int screenHeight) {
        PackProgram.Chain chain;

        try {
            // One opening of the pack for the whole chain: the plan, every program and every
            // refusal come out of the same reading, which is the front end's own reason for
            // loadChain existing rather than a loop over load.
            chain = PackProgram.loadChain(pack, dimension, Map.of(), "", ChainFilter.ALL);
        } catch (IOException | RuntimeException exception) {
            VitrailPackChain failed = new VitrailPackChain(String.valueOf(pack.getFileName()), dimension, pack);
            failed.failure = exception.toString();
            Initializer.LOGGER.error("Vitrail: {} has no chain this engine can read", pack, exception);
            return failed;
        }

        VitrailPackChain built = new VitrailPackChain(chain.packName(), chain.place(), pack);
        built.plan = chain.chain();
        built.width = Math.max(1, screenWidth);
        built.height = Math.max(1, screenHeight);

        try {
            built.allocate(chain.targets(), built.width, built.height);
            // Before the compile, because the compile is what counts a sampler as served or not
            // and the noise is one of the names it decides.
            built.loadNoise();
            built.compile(chain);
        } catch (RuntimeException | OutOfMemoryError exception) {
            built.failure = exception.toString();
            Initializer.LOGGER.error("Vitrail: the chain of {} could not be built", pack, exception);
        }

        return built;
    }

    /**
     * The chain this session runs, built once per pack and size.
     * <p>
     * What {@code describeAndClose} is to the report, this is to the frame: a chain that lives as
     * long as the pack and the resolution do, because a pack's buffers carry one pass's output to
     * the next and, for a reprojecting pack, to the frame after. Rebuilt only when one of the two
     * changes, and never per frame.
     */
    public static VitrailPackChain drawing(Path pack, String dimension, int screenWidth, int screenHeight) {
        String key = pack.toAbsolutePath() + "|" + dimension;
        int width = Math.max(1, screenWidth);
        int height = Math.max(1, screenHeight);

        if (drawn != null && (!key.equals(drawnKey) || drawn.width != width || drawn.height != height)) {
            Initializer.LOGGER.info("Vitrail: rebuilding the drawn chain for {}x{} (was {}x{})",
                    width, height, drawn.width, drawn.height);
            drawn.close();
            drawn = null;
        }

        if (drawn == null) {
            drawn = build(pack, dimension, width, height);
            drawnKey = key;

            // Said here, once per build, because the alternative is a frame that draws nothing and
            // no line saying which pass the plan left out.
            Initializer.LOGGER.info("Vitrail: pack chain to draw, {}", drawn.report());
        }

        return drawn;
    }

    /** One texture per colour target, and a second one for every target the schedule ping pongs. */
    private void allocate(TargetPlan plan, int screenWidth, int screenHeight) {
        Set<Integer> doubled = plan.schedule().doubled();

        for (int index : plan.ordered()) {
            TargetFormat.Resolution declared = plan.directives().format(index);
            GpuFormat format = formatOf(declared.used());

            if (format == null) {
                this.refusals.add("colortex" + index + ": the pack declares " + declared.declared()
                        + ", which this facade cannot allocate");
                continue;
            }

            TargetSize size = plan.directives().size(index);
            int width = size.width(screenWidth);
            int height = size.height(screenHeight);
            GpuTexture main = GpuDevice.texture(format, width, height);
            GpuTexture alt = doubled.contains(index) ? GpuDevice.texture(format, width, height) : main;

            this.targets.put(index, new GpuTexture[]{main, alt});
        }
    }

    private void compile(PackProgram.Chain chain) {
        List<ChainPlan.Pass> passes = chain.chain().passes();

        for (ChainPlan.Pass pass : passes) {
            PackProgram.Loaded loaded = chain.programs().get(pass.program());

            if (loaded == null) {
                refusePass(pass, "the plan runs it and the pack serves neither of its stages");
                continue;
            }

            GpuTexture[] colors = attachments(pass);

            if (colors == null) {
                continue;
            }

            try {
                Compiled compiled = compileProgram(pass.program(), loaded);
                GpuPass gpuPass = colors.length == 0 ? null : GpuDevice.loadingPass(colors);

                this.stages.add(new Stage(pass.program(), pass.targets(), gpuPass, compiled.pipeline(),
                        loaded.samplers().bindings(), compiled.globals()));
                this.programsCompiled++;
            } catch (RuntimeException | OutOfMemoryError exception) {
                refusePass(pass, exception.toString());
            }
        }

        // The final writes the game's target and names no colortex, so the plan carries it apart
        // from the passes and the facade builds the one pass it needs over an image of the
        // screen's size. Skipping it is what leaves a pack's exposure and its gamma undone.
        Optional<ChainPlan.Pass> needed = chain.chain().last();

        if (needed.isPresent()) {
            ChainPlan.Pass last = needed.get();
            PackProgram.Loaded loaded = chain.programs().get(last.program());

            if (loaded == null) {
                refusePass(last, "the plan runs it and the pack serves neither of its stages");
            } else {
                try {
                    this.presentation = GpuDevice.texture(GpuFormat.RGBA8, this.width, this.height);
                    Compiled compiled = compileProgram(last.program(), loaded);
                    GpuPass gpuPass = GpuDevice.loadingPass(this.presentation);

                    this.finalStage = new Stage(last.program(), List.of(), gpuPass, compiled.pipeline(),
                            loaded.samplers().bindings(), compiled.globals());
                    this.programsCompiled++;
                } catch (RuntimeException | OutOfMemoryError exception) {
                    refusePass(last, exception.toString());
                }
            }
        }

        for (Map.Entry<String, PackProgram.Refusal> removed : chain.removed().entrySet()) {
            this.refusals.add(removed.getKey() + ": " + removed.getValue().reason());
            this.programsRefused++;
        }
    }

    /**
     * The pack's noise image, decoded once, uploaded on the first frame.
     * <p>
     * Every noise a pack reads is the one file, {@code shaders/noise.png} or whatever
     * {@code texture.noise} names, and it has to tile: the coordinates a pack feeds it are
     * arbitrary, so the image repeats rather than clamps. A pack that ships none gets the
     * generator's own, which is what Iris does instead of a white texel — noise drives grain, the
     * dither on clouds and the rotation of the shadow samples, and a flat white one turns each of
     * those into a constant.
     * <p>
     * Decoded here and nowhere else, because the decode is the expensive half and the file cannot
     * change under a built chain: only the upload waits for a command buffer.
     */
    private void loadNoise() {
        NoiseTexture.Image pixels;

        try (ShaderPackSource source = ShaderPackSource.open(this.pack)) {
            String declared = ShaderProperties.parse(source).noiseTexturePath(Map.of())
                    .orElse("noise.png");
            Optional<Path> file = source.file(declared);

            pixels = file.isPresent()
                    ? NoiseTexture.decode(Files.readAllBytes(file.get()))
                    : generatedNoise();

            Initializer.LOGGER.info("Vitrail: pack noise, {} {}x{}",
                    file.isPresent() ? declared : "none shipped, generated",
                    pixels.width(), pixels.height());
        } catch (IOException | RuntimeException exception) {
            Initializer.LOGGER.info("Vitrail: pack noise unreadable, {}; noisetex keeps the white texel",
                    exception.toString());
            return;
        }

        this.noisePixels = pixels;
    }

    /** Iris's own fallback for a pack that ships no noise: the generator at its usual size. */
    private static NoiseTexture.Image generatedNoise() {
        int resolution = 256;
        return new NoiseTexture.Image(resolution, resolution, NoiseTexture.rgba(resolution));
    }

    /**
     * Uploads the noise once, on the first frame that has a command buffer to record in.
     * <p>
     * The upload goes through the engine's own staging path, which submits its own command buffer
     * and transitions the image, so it must not run while this frame's pass is open: a chain is
     * built from inside the engine's pass, which is why the decode and the upload are split.
     */
    private void uploadNoise() {
        if (this.noiseUploaded || this.noisePixels == null) {
            return;
        }

        this.noiseUploaded = true;
        NoiseTexture.Image pixels = this.noisePixels;
        this.noisePixels = null;

        GpuTexture texture = GpuTexture.create(GpuFormat.RGBA8, pixels.width(), pixels.height(),
                true, false);

        ByteBuffer upload = MemoryUtil.memAlloc(pixels.rgba().length);

        try {
            upload.put(pixels.rgba()).flip();
            texture.image().uploadSubTextureAsync(0, pixels.width(), pixels.height(), 0, 0, 0, 0,
                    pixels.width(), upload);
            texture.image().readOnlyLayout();
        } catch (RuntimeException exception) {
            Initializer.LOGGER.info("Vitrail: pack noise could not be uploaded, {}; noisetex keeps the white texel",
                    exception.toString());
            texture.close();
            return;
        } finally {
            MemoryUtil.memFree(upload);
        }

        this.noise = texture;
    }

    /**
     * The textures one pass writes, or null when the pass cannot be built over the targets that
     * were allocated.
     * <p>
     * A pass writes attachments of one shared size, and a target whose own size directive
     * disagrees with that is a Vulkan framebuffer the driver refuses. Said here, once, rather than
     * as a device loss at the first frame.
     */
    private GpuTexture[] attachments(ChainPlan.Pass pass) {
        List<GpuTexture> colors = new ArrayList<>();
        int width = -1;
        int height = -1;

        for (ChainPlan.Attachment attachment : pass.attachments()) {
            GpuTexture[] pair = this.targets.get(attachment.target());

            if (pair == null) {
                refusePass(pass, "colortex" + attachment.target() + " was not allocated");
                return null;
            }

            GpuTexture texture = attachment.side() == TargetSchedule.Side.ALT ? pair[1] : pair[0];

            if (width < 0) {
                width = texture.width();
                height = texture.height();
            } else if (texture.width() != width || texture.height() != height) {
                refusePass(pass, "colortex" + attachment.target() + " is " + texture.width() + "x"
                        + texture.height() + " where the pass is " + width + "x" + height);
                return null;
            }

            colors.add(texture);
        }

        return colors.toArray(new GpuTexture[0]);
    }

    /**
     * Compiles one program of the chain.
     * <p>
     * The text is prepared exactly as the screen path prepares it — corners from the vertex index,
     * one binding map for both stages, varyings numbered by name — because the front end emits GLSL
     * for an engine that hands the numbers over at draw time, and Vulkan refuses a descriptor
     * without one. The samplers are then bound in the order the front end bound them, which is what
     * keeps a slot and a name together at the draw.
     */
    private Compiled compileProgram(String name, PackProgram.Loaded loaded) {
        TranslatedUnit vertex = loaded.program().stages().get(ProgramStage.VERTEX);
        TranslatedUnit fragment = loaded.program().stages().get(ProgramStage.FRAGMENT);

        if (vertex == null || fragment == null) {
            throw new IllegalStateException("the pack serves only one of the two stages");
        }

        VitrailProgram.Bound bound = VitrailProgram.assignVaryings(
                VitrailProgram.assignBindings(VitrailProgram.fullscreenCorners(vertex.text()),
                        fragment.text()));

        VitrailProgram.dump(name, bound.vertex(), bound.fragment());
        GpuPipeline.Builder builder = GpuDevice.program(name, bound.vertex(), bound.fragment());

        // The block is the pack's own list of uniforms, in the order the translation wrote it, which
        // is its std140 layout. The engine fills that list: clocks, camera matrices, view size, sun,
        // rain, eye in water. Wrapped rather than copied, so the buffer the pipeline reads is the one
        // that is rewritten every frame.
        List<VitrailProgram.BlockDecl> blocks = VitrailProgram.blockDecls(bound.vertex(), bound.fragment());
        VitrailGlobals globals = blocks.isEmpty() ? null : VitrailGlobals.of(vertex.blockMembers());

        for (VitrailProgram.BlockDecl block : blocks) {
            builder.block(block.binding(), block.stages(), globals == null
                    ? GpuDevice.buffer(BLOCK_FLOATS * 4)
                    : GpuBuffer.wrap(globals.address(), globals.size()));
        }

        Map<String, VitrailProgram.SamplerDecl> declarations = new LinkedHashMap<>();
        for (VitrailProgram.SamplerDecl declaration
                : VitrailProgram.samplerDecls(bound.vertex(), bound.fragment())) {
            declarations.put(declaration.name(), declaration);
        }

        int slot = 0;
        for (SamplerPlan.Binding binding : loaded.samplers().bindings()) {
            VitrailProgram.SamplerDecl declaration = declarations.get(binding.sampler());

            if (declaration == null) {
                // Declared by neither stage after translation: a name the plan still tracks and
                // the shader no longer reads.
                continue;
            }

            if (binding.kind() == SamplerPlan.Kind.UNBINDABLE) {
                this.samplersUnbindable++;
            }

            if (served(binding)) {
                this.samplersServed++;
            } else {
                this.samplersUnserved++;
                this.unserved.add(name + ": " + binding.sampler() + " as " + binding.kind());
            }

            if (slot >= VTextureSelector.SIZE) {
                this.refusals.add(name + ": more samplers than the selector holds, "
                        + binding.sampler() + " and everything after it are left out");
                break;
            }

            builder.sampler(declaration.binding(), declaration.type(), declaration.name(), slot++);
        }

        // A pipeline whose layout names no sampler while its shader declares some is refused at the
        // first draw, far from the cause. The cause is always the same one: the plan and the text
        // call the same descriptor by two names. Said here, once, with the count.
        if (slot == 0 && !declarations.isEmpty()) {
            this.refusals.add(name + ": the plan bound none of the " + declarations.size()
                    + " samplers its text declares, so the two disagree on their names");
        }

        return new Compiled(builder.build(), globals);
    }

    /** Whether this engine has an image behind a sampler name today. Only the colour targets it has. */
    private boolean served(SamplerPlan.Binding binding) {
        if (binding.kind() == SamplerPlan.Kind.NOISE) {
            return this.noisePixels != null || this.noise != null;
        }

        return binding.kind() == SamplerPlan.Kind.COLORTEX
                && this.targets.containsKey(binding.index());
    }

    private void refusePass(ChainPlan.Pass pass, String why) {
        this.programsRefused++;
        this.refusals.add(pass.program() + ": " + why);
    }

    /** What was built, in the pack's own terms, for the log. */
    public String report() {
        StringBuilder said = new StringBuilder();

        said.append("pack=").append(this.packName).append(" place=")
                .append(this.place.isEmpty() ? "(root)" : this.place).append(" targets=")
                .append(this.targets.size()).append(" passes=")
                .append(this.stages.size() + (this.finalStage != null ? 1 : 0))
                .append(" compiled=").append(this.programsCompiled)
                .append(" refused=").append(this.programsRefused)
                .append(" samplers=").append(this.samplersServed).append('/')
                .append(this.samplersServed + this.samplersUnserved)
                .append(" unbound=").append(this.samplersUnserved)
                .append(" unbindable=").append(this.samplersUnbindable);

        if (this.failure != null) {
            said.append(" failure=").append(this.failure);
        }

        if (!this.unserved.isEmpty()) {
            said.append("\n  unbound in the plan (colour targets and noise are what this build")
                    .append(" can serve; the draw's own line is the measure): ")
                    .append(String.join(", ", this.unserved));
        }

        for (String refusal : this.refusals) {
            said.append("\n  refused: ").append(refusal);
        }

        return said.toString();
    }

    /** The chains' names in frame order, which is what a later milestone will walk. */
    public List<String> order() {
        return this.stages.stream().map(Stage::name).toList();
    }

    public List<Stage> stages() {
        return Collections.unmodifiableList(this.stages);
    }

    /** Whether there is anything of the pack to draw: it built, and one pass of it compiled. */
    public boolean drawable() {
        return this.failure == null && !this.stages.isEmpty();
    }

    public int width() {
        return this.width;
    }

    public int height() {
        return this.height;
    }

    /**
     * Runs the pack's chain over the frame the engine has just drawn, and hands the picture to the
     * presenter.
     * <p>
     * The passes run in the pack's own order, each into the buffers its draw buffer directive
     * names, loading what the one before it wrote. The engine's picture is copied into the buffer
     * the plan seeds the scene with, which is where a pack starts reading: that copy is what makes
     * the chain post-process this engine's world rather than a black one.
     * <p>
     * The ping-pong halves are flipped after the presenter has read the result, never before: the
     * half a pack ends the frame on is the half that reaches the screen, and flipping first shows
     * the frame before this one.
     *
     * @param engine what this engine can answer for a name that is not one of the pack's buffers,
     *               or null for a name it has nothing behind
     */
    public boolean drawFrame(VkCommandBuffer commandBuffer, MemoryStack stack, VulkanImage scene,
                             Function<String, VulkanImage> engine, Consumer<VulkanImage> present) {
        if (!drawable()) {
            return false;
        }

        VitrailGlobals.advance();
        uploadNoise();

        this.boundServed = 0;
        this.boundWhite = 0;
        this.whiteNames.clear();

        GpuTexture seed = seedTarget();

        if (seed != null && scene != null) {
            copy(commandBuffer, stack, scene, seed.image());
        }

        int drawn = 0;

        for (Stage stage : this.stages) {
            if (stage.pass() == null || stage.pipeline() == null) {
                continue;
            }

            if (stage.globals() != null) {
                stage.globals().write(this.width, this.height);
            }

            stage.pass().beginLoaded(commandBuffer, stack);
            bind(stage, engine);
            stage.pass().drawFullscreen(stage.pipeline());
            stage.pass().end(commandBuffer);
            drawn++;
        }

        GpuTexture lastPass = lastPassTarget();
        boolean showedFinal = drawFinal(commandBuffer, stack, engine);

        if (showedFinal) {
            drawn++;
        }

        this.passesDrawn = drawn;

        if (!this.reportedBound) {
            this.reportedBound = true;
            Initializer.LOGGER.info("Vitrail: pack chain samplers at draw, {} with an image, {} on the white texel{}",
                    this.boundServed, this.boundWhite,
                    this.whiteNames.isEmpty() ? "" : ": " + String.join(", ", this.whiteNames));
        }

        if (!this.drewOnce) {
            this.drewOnce = true;
            Initializer.LOGGER.info("Vitrail: pack chain drawn, {} pass(es) at {}x{}, presenting {}",
                    drawn, this.width, this.height, showedFinal
                            ? "the pack's own final"
                            : this.plan != null && this.plan.present().isPresent()
                                    ? "colortex" + this.plan.present().get().target()
                                    : "the last pass's first buffer");
        }

        this.framesDrawn++;

        GpuTexture shown = showedFinal ? this.presentation : lastPass;

        if (shown != null) {
            probe(shown, lastPass, scene, commandBuffer, stack);
            present.accept(shown.image());
        }

        swapBack();
        return true;
    }

    /**
     * The pack's own {@code final}, drawn over the chain's result into an image of the screen's
     * size, which the presenter then brings to the swapchain.
     * <p>
     * The final is the one pass of a pack that writes the game's target and no colour buffer, so
     * the plan carries it with no attachment and the facade gives it a pass of its own. Without it
     * a pack's exposure, its gamma and its grain are all skipped, and what reaches the screen is
     * the last buffer the chain happened to write rather than the pack's own last word.
     *
     * @return whether it was drawn, and therefore whether the presentation image holds the frame
     */
    private boolean drawFinal(VkCommandBuffer commandBuffer, MemoryStack stack,
                              Function<String, VulkanImage> engine) {
        if (this.finalStage == null || this.finalStage.pipeline() == null
                || this.finalStage.pass() == null) {
            return false;
        }

        if (this.finalStage.globals() != null) {
            this.finalStage.globals().write(this.width, this.height);
        }

        this.finalStage.pass().beginLoaded(commandBuffer, stack);
        bind(this.finalStage, engine);
        this.finalStage.pass().drawFullscreen(this.finalStage.pipeline());
        this.finalStage.pass().end(commandBuffer);

        return true;
    }

    /** How many passes the last drawn frame recorded, which is the number a log can be checked against. */
    public int passesDrawn() {
        return this.passesDrawn;
    }

    /**
     * Reads the presented buffer back, once, under {@code -Dvitrail.debug.probes=true}.
     * <p>
     * A chain that runs is not a chain that drew: a pass can be recorded into images the driver then
     * ignores, and the screen shows whatever the presenter had. The frame's own numbers are the only
     * thing that says what the picture holds, and they say more than a texel: a mean near zero with a
     * range around it is a dark picture, a mean and a range both at zero is an empty one, and both at
     * a single value off zero is an aplat where an image should be.
     * <p>
     * Four bytes a texel, which is the packed eight bit form every buffer of the corpus ends on.
     */
    /**
     * Reads the presented buffer back over a short window of frames, under
     * {@code -Dvitrail.debug.probes=true}, with the engine's own frame beside it.
     * <p>
     * A chain that runs is not a chain that drew, and a frame that is right when the world opens can
     * stop being right later: one sample cannot tell a whole picture from a half of a buffer that no
     * pass ever wrote, and the two look the same in a mean. The window is what shows the onset.
     * <p>
     * Four bytes a texel, which is the packed eight bit form every buffer of the corpus ends on.
     */
    /**
     * Reads the presented buffer back over a short window of frames, under
     * {@code -Dvitrail.debug.probes=true}, with the engine's own frame beside it.
     * <p>
     * A chain that runs is not a chain that drew, and a frame that is right when the world opens can
     * stop being right later: one sample cannot tell a whole picture from a half of a buffer that no
     * pass ever wrote, and the two look the same in a mean. The window is what shows the onset.
     * <p>
     * The two buffers are allocated once and reused: freeing one per frame let the allocator hand the
     * same memory to the other read of the next frame, and a frame's numbers came out in the wrong
     * column.
     * <p>
     * Four bytes a texel, which is the packed eight bit form every buffer of the corpus ends on.
     */
    private void probe(GpuTexture shown, GpuTexture previous, VulkanImage scene,
                       VkCommandBuffer commandBuffer, MemoryStack stack) {
        if (!VitrailDebug.PROBES || this.probeReports >= PROBE_REPORTS
                || this.framesDrawn < PROBE_SETTLED_FRAME) {
            return;
        }

        VulkanImage image = shown.image();
        int bytes = image.width * image.height * 4;

        VulkanImage shadow = shadowMap();
        int shadowBytes = shadow == null ? 0 : shadow.width * shadow.height * 4;

        if (this.probe == null) {
            this.probe = new ReadbackBuffer(bytes);
            this.sceneProbe = scene != null && scene.width == image.width && scene.height == image.height
                    ? new ReadbackBuffer(bytes)
                    : null;
            this.shadowProbe = shadowBytes > 0 ? new ReadbackBuffer(shadowBytes) : null;
        } else {
            // What was copied into these buffers on the frame before this one, now that it is there.
            // Read through a fresh mapping: the persistent one is cached memory and can be stale.
            ByteBuffer presented = this.probe.copyOut();

            Initializer.LOGGER.info(
                    "Vitrail: pack chain probe, frame {} presented {}x{} fmt=0x{} from {}, {}",
                    this.framesDrawn - 1, image.width, image.height,
                    Integer.toHexString(shown.format().vkFormat), bufferName(shown),
                    stats(presented, image.width * image.height));

            Initializer.LOGGER.info("Vitrail: pack chain probe, frame {} ground {}",
                    this.framesDrawn - 1, shade(presented, image.width, image.height));

            if (this.probeReports == 1) {
                writeFrame(presented, image.width, image.height);
                frameState();
            }

            MemoryUtil.memFree(presented);

            if (this.sceneProbe != null) {
                ByteBuffer engineFrame = this.sceneProbe.copyOut();

                Initializer.LOGGER.info("Vitrail: pack chain probe, frame {} the engine's own frame, {}",
                        this.framesDrawn - 1, stats(engineFrame, image.width * image.height));

                MemoryUtil.memFree(engineFrame);
            }

            if (this.shadowProbe != null) {
                ByteBuffer depths = this.shadowProbe.copyOut();

                Initializer.LOGGER.info("Vitrail: pack chain probe, frame {} the shadow map {}x{}, {}",
                        this.framesDrawn - 1, shadow.width, shadow.height,
                        shadowStats(depths, shadow.width * shadow.height));

                MemoryUtil.memFree(depths);
            }

            this.probeReports++;
        }

        if (this.probeReports >= PROBE_REPORTS) {
            this.probe.freeBuffer();
            this.probe = null;

            if (this.sceneProbe != null) {
                this.sceneProbe.freeBuffer();
                this.sceneProbe = null;
            }

            if (this.shadowProbe != null) {
                this.shadowProbe.freeBuffer();
                this.shadowProbe = null;
            }

            return;
        }

        image.transitionImageLayout(stack, commandBuffer, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL);
        ImageUtil.copyImageToBuffer(commandBuffer, this.probe.getId(), image.getId(), 0,
                image.width, image.height, 0, 0, 0, 0, 0);
        image.transitionImageLayout(stack, commandBuffer, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);

        // The engine's own frame beside it, in the same frame: the only way to say whether a black
        // picture is the pack's doing or the world's own.
        if (this.sceneProbe != null) {
            scene.transitionImageLayout(stack, commandBuffer, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL);
            ImageUtil.copyImageToBuffer(commandBuffer, this.sceneProbe.getId(), scene.getId(), 0,
                    scene.width, scene.height, 0, 0, 0, 0, 0);
            scene.transitionImageLayout(stack, commandBuffer, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
        }

        if (this.shadowProbe != null) {
            shadow.transitionImageLayout(stack, commandBuffer, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL);
            ImageUtil.copyImageToBuffer(commandBuffer, this.shadowProbe.getId(), shadow.getId(), 0,
                    shadow.width, shadow.height, 0, 0, 0, 0, 0, VK_IMAGE_ASPECT_DEPTH_BIT);
            shadow.transitionImageLayout(stack, commandBuffer, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
        }
    }

    /** The cascade the pack reads as {@code shadowtex0}, or null when the engine is drawing none. */
    private static VulkanImage shadowMap() {
        net.vulkanmod.vulkan.pass.ShadowMap map = net.vulkanmod.vulkan.pass.ShadowMap.active();

        if (map == null || !map.isReady() || !Initializer.CONFIG.shadowsEnabled) {
            return null;
        }

        return map.getCascadeDepthImage(net.vulkanmod.vulkan.pass.ShadowMap.PACK_CASCADE);
    }

    /**
     * What a float depth image holds: how much of it a caster covered, and the range it spans.
     * <p>
     * The coverage is the number that answers the question. A map the engine drew holds the same
     * terrain the pack's stage is handed, so a coverage near the engine's own is the world in the
     * image; a coverage of zero is a completed pass that wrote nothing, which a frame cannot show.
     */
    private static String shadowStats(ByteBuffer depths, int texels) {
        FloatBuffer values = depths.asFloatBuffer();
        int covered = 0;
        // Where the casters sit along the depth axis, which a minimum and a maximum cannot say: a
        // map drawn by two different transforms can report the same range and still hold entirely
        // different geometry, and a band that fills where the engine's is empty is that difference.
        int near = 0;
        int low = 0;
        int mid = 0;
        int far = 0;
        float lowest = Float.MAX_VALUE;
        float highest = -Float.MAX_VALUE;

        for (int i = 0; i < texels; i++) {
            float depth = values.get(i);

            if (depth < 0.9999F) {
                covered++;
            }

            if (depth < 0.05F) {
                near++;
            } else if (depth < 0.3F) {
                low++;
            } else if (depth < 0.6F) {
                mid++;
            } else if (depth < 0.9999F) {
                far++;
            }

            lowest = Math.min(lowest, depth);
            highest = Math.max(highest, depth);
        }

        return String.format("casters %d/%d (%.1f%%), depth %.4f..%.4f, bands %.1f/%.1f/%.1f/%.1f%%",
                covered, texels, 100.0 * covered / texels, lowest, highest,
                100.0 * near / texels, 100.0 * low / texels, 100.0 * mid / texels,
                100.0 * far / texels);
    }

    /** The world and the camera at the frame being reported: another hour is another scene. */
    private static void frameState() {
        net.minecraft.client.Minecraft client = net.minecraft.client.Minecraft.getInstance();

        if (client.level != null) {
            Initializer.LOGGER.info("Vitrail: pack chain probe, world time {} (day {}), rain {}",
                    client.level.getDayTime() % 24000L, client.level.getDayTime() / 24000L,
                    client.level.getRainLevel(1.0F));
        }

        if (client.player != null) {
            Initializer.LOGGER.info("Vitrail: pack chain probe, camera at {}/{}/{}, yaw {}, pitch {}, fov {}",
                    String.format(java.util.Locale.ROOT, "%.2f", client.player.getX()),
                    String.format(java.util.Locale.ROOT, "%.2f", client.player.getY()),
                    String.format(java.util.Locale.ROOT, "%.2f", client.player.getZ()),
                    String.format(java.util.Locale.ROOT, "%.1f", client.player.getYRot()),
                    String.format(java.util.Locale.ROOT, "%.1f", client.player.getXRot()),
                    client.options.fov().get());
        }
    }

    /** Mean, low and high of an eight bit RGBA readback, which is what says the picture is an image. */
    /**
     * How dark the ground half of a frame is, as luminance percentiles.
     * <p>
     * A mean cannot say whether a shadow is there: a picture with hard shadow in it and one lit by a
     * lookup that measures the wrong thing both move a mean, and only one of them has ground that is
     * dark in patches. Percentiles say it without a threshold to argue about — the second is the
     * ground the sun does not reach, the median the field beside it — and the share under a quarter
     * of white is that same split as one number, which is what an A/B of the shadow map's sampler
     * needs.
     * <p>
     * The lower half is the ground: the sky sits in the upper one and is brighter than anything lit,
     * so a percentile over the whole frame would be a percentile of sky.
     */
    private static String shade(ByteBuffer read, int width, int height) {
        int start = (height / 2) * width;
        int texels = width * height - start;
        int[] histogram = new int[256];

        for (int texel = start; texel < width * height; texel++) {
            int at = texel * 4;
            int luminance = ((read.get(at) & 0xFF) * 77 + (read.get(at + 1) & 0xFF) * 150
                    + (read.get(at + 2) & 0xFF) * 29) >> 8;
            histogram[luminance]++;
        }

        long dark = 0;

        for (int value = 0; value < 64; value++) {
            dark += histogram[value];
        }

        return String.format(java.util.Locale.ROOT,
                "lower half p2=%d p10=%d p50=%d, %d/%d (%.1f%%) under 64",
                percentile(histogram, texels, 2), percentile(histogram, texels, 10),
                percentile(histogram, texels, 50), dark, texels, 100.0 * dark / texels);
    }

    /** The luminance below which {@code percent} of the counted texels fall. */
    private static int percentile(int[] histogram, int texels, int percent) {
        long target = (long) texels * percent / 100;
        long seen = 0;

        for (int value = 0; value < histogram.length; value++) {
            seen += histogram[value];

            if (seen > target) {
                return value;
            }
        }

        return 255;
    }

    private static String stats(ByteBuffer read, int texels) {
        long[] sum = new long[3];
        int[] low = {255, 255, 255};
        int[] high = {0, 0, 0};

        for (int texel = 0; texel < texels; texel++) {
            for (int channel = 0; channel < 3; channel++) {
                int value = read.get(texel * 4 + channel) & 0xFF;
                sum[channel] += value;
                low[channel] = Math.min(low[channel], value);
                high[channel] = Math.max(high[channel], value);
            }
        }

        return "mean=(" + sum[0] / texels + ", " + sum[1] / texels + ", " + sum[2] / texels
                + ") min=(" + low[0] + ", " + low[1] + ", " + low[2]
                + ") max=(" + high[0] + ", " + high[1] + ", " + high[2] + ")";
    }

    /**
     * How far one image sits from another, texel for texel, in eight bit steps.
     * <p>
     * The mean is what says whether the second image is a different picture or the same one with a
     * rounding difference; the maximum is what says the difference is spread rather than one texel;
     * the share of texels that moved by more than eight steps is what says whether the whole frame
     * moved or only the bright parts, which is what an exposure and a gamma do.
     */
    private static String difference(ByteBuffer after, ByteBuffer before, int texels) {
        long[] sum = new long[3];
        int[] high = {0, 0, 0};
        long touched = 0;
        long moved = 0;

        for (int texel = 0; texel < texels; texel++) {
            boolean any = false;
            boolean differs = false;

            for (int channel = 0; channel < 3; channel++) {
                int at = texel * 4 + channel;
                int delta = Math.abs((after.get(at) & 0xFF) - (before.get(at) & 0xFF));
                sum[channel] += delta;
                high[channel] = Math.max(high[channel], delta);
                any |= delta > 0;
                differs |= delta > 8;
            }

            if (any) {
                touched++;
            }

            if (differs) {
                moved++;
            }
        }

        // Two decimals, because an eight bit step hides the whole question: a rounding difference
        // and a real tone map can both round to nought at this mean.
        return String.format(java.util.Locale.ROOT,
                "mean |delta|=(%.2f, %.2f, %.2f) max |delta|=(%d, %d, %d) touched=%d%% moved by more than eight steps=%d%%",
                (double) sum[0] / texels, (double) sum[1] / texels, (double) sum[2] / texels,
                high[0], high[1], high[2], 100L * touched / texels, 100L * moved / texels);
    }

    /**
     * Writes the frame this engine presents to {@code vitrail-frame.png}, beside the game.
     * <p>
     * The numbers say whether the picture is an image; the file is what lets the other half of a
     * comparison be measured by the same program. A pack's frame and a reference renderer's
     * screenshot are then both eight bit RGBA PNGs read by one reader, instead of one of them being
     * a Vulkan readback and the other a PNG and the two disagreeing about channel order or rows in a
     * way that reads exactly like a shader fault.
     */
    private static void writeFrame(ByteBuffer read, int width, int height) {
        try {
            BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);

            for (int y = 0; y < height; y++) {
                for (int x = 0; x < width; x++) {
                    int at = (y * width + x) * 4;
                    int red = read.get(at) & 0xFF;
                    int green = read.get(at + 1) & 0xFF;
                    int blue = read.get(at + 2) & 0xFF;
                    image.setRGB(x, y, 0xFF000000 | (red << 16) | (green << 8) | blue);
                }
            }

            Path file = Path.of("vitrail-frame.png").toAbsolutePath();
            ImageIO.write(image, "png", file.toFile());
            Initializer.LOGGER.info("Vitrail: pack chain frame written to {}", file);
        } catch (IOException | RuntimeException exception) {
            Initializer.LOGGER.info("Vitrail: the frame could not be written, {}", exception.toString());
        }
    }

    /** Which buffer of the chain a texture is, for a log line that has to name what it measured. */
    private String bufferName(GpuTexture texture) {
        if (texture == null) {
            return "nothing";
        }

        for (Map.Entry<Integer, GpuTexture[]> entry : this.targets.entrySet()) {
            GpuTexture[] pair = entry.getValue();

            if (pair[0] == texture) {
                return "colortex" + entry.getKey();
            }

            if (pair[1] == texture) {
                return "colortex" + entry.getKey() + " (second half)";
            }
        }

        return this.presentation == texture ? "the screen-sized presentation image" : "an image of its own";
    }

    /** The buffer the plan seeds the scene with: where the engine's picture has to be copied. */
    private GpuTexture seedTarget() {
        if (this.plan == null) {
            return textureFor(0, TargetSchedule.Side.MAIN);
        }

        Optional<ChainPlan.Seed> seed = this.plan.seed();
        return seed.map(value -> textureFor(value.target(), value.side())).orElse(null);
    }

    /**
     * What the chain leaves behind: the plan's present target, or, for a pack that ships a
     * {@code final}, the first buffer of the last pass, which is what the final reads.
     */
    private GpuTexture lastPassTarget() {
        if (this.plan != null) {
            Optional<ChainPlan.Attachment> present = this.plan.present();

            if (present.isPresent()) {
                return textureFor(present.get().target(), present.get().side());
            }
        }

        for (int i = this.stages.size() - 1; i >= 0; i--) {
            GpuPass pass = this.stages.get(i).pass();

            if (pass != null) {
                return pass.color(0);
            }
        }

        return null;
    }

    /** Binds the plan's samplers for one pass, by the slot the facade gave each name at compile time. */
    private void bind(Stage stage, Function<String, VulkanImage> engine) {
        List<GpuTexture> attachments = new ArrayList<>();

        for (int i = 0; i < stage.targets().size(); i++) {
            attachments.add(stage.pass().color(i));
        }

        for (SamplerPlan.Binding binding : stage.samplers()) {
            int slot = stage.pipeline().slotOf(binding.sampler());

            if (slot < 0) {
                continue;
            }

            GpuTexture target = binding.kind() == SamplerPlan.Kind.COLORTEX
                    ? textureFor(binding.index(), binding.side())
                    : binding.kind() == SamplerPlan.Kind.NOISE ? this.noise : null;
            VulkanImage image = target != null ? target.image() : engineTexture(binding, engine);

            if (target != null && attachments.contains(target)) {
                // A pass cannot sample the image it is writing: Vulkan leaves that read undefined,
                // and this driver answers nothing at all, which is a black picture with no other
                // symptom. Said once, because the next frame says the same thing.
                if (!this.reportedFeedback) {
                    this.reportedFeedback = true;
                    Initializer.LOGGER.info("Vitrail: '{}' samples {} which it also writes, served the white texel",
                            stage.name(), binding.sampler());
                }

                image = null;
            }

            if (image != null) {
                this.boundServed++;
            } else {
                this.boundWhite++;
                this.whiteNames.add(stage.name() + ": " + binding.sampler());
            }

            stage.pass().bind(slot, image != null ? image : VTextureSelector.getWhiteTexture());
        }
    }

    /** The engine image behind a name that is not one of the pack's own buffers. */
    private static VulkanImage engineTexture(SamplerPlan.Binding binding, Function<String, VulkanImage> engine) {
        if (engine == null) {
            return null;
        }

        String name = switch (binding.kind()) {
            case DEPTH, CENTER_DEPTH -> "depthtex";
            case SHADOW_DEPTH -> "shadowtex0";
            // The shadow pass is the engine's, and it keeps a colour buffer per cascade: the depth
            // alone says where a caster is, the colour says what it let through. A pack asks for
            // shadowcolor0 and shadowcolor1 apart, so the index has to reach the engine with them.
            case SHADOW_COLOUR -> "shadowcolor" + binding.index();
            default -> null;
        };

        return name == null ? null : engine.apply(name);
    }

    /** One buffer of the chain, on the half the schedule and the last frame's flips put it. */
    private GpuTexture textureFor(int index, TargetSchedule.Side side) {
        GpuTexture[] pair = this.targets.get(index);

        if (pair == null) {
            return null;
        }

        int base = side == TargetSchedule.Side.ALT ? 1 : 0;
        return this.flipped.contains(index) ? pair[1 - base] : pair[base];
    }

    /** Flips the halves the plan swaps at end of frame, so the next frame reads what this one wrote. */
    private void swapBack() {
        if (this.plan == null) {
            return;
        }

        for (int index : this.plan.swapBack()) {
            if (!this.flipped.remove(index)) {
                this.flipped.add(index);
            }
        }
    }

    /**
     * One image into another, converting between their formats.
     * <p>
     * A blit rather than a draw: the engine's scene is eight bits a channel and the pack's buffers
     * are not, and {@code vkCmdBlitImage} converts between them without a pipeline.
     */
    private static void copy(VkCommandBuffer commandBuffer, MemoryStack stack, VulkanImage source,
                             VulkanImage target) {
        if (source == null || target == null || source == target) {
            return;
        }

        source.transitionImageLayout(stack, commandBuffer, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL);
        target.transitionImageLayout(stack, commandBuffer, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL);

        VkImageBlit.Buffer region = VkImageBlit.calloc(1, stack);
        region.srcSubresource().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).mipLevel(0).baseArrayLayer(0).layerCount(1);
        region.srcOffsets(0).set(0, 0, 0);
        region.srcOffsets(1).set(source.width, source.height, 1);
        region.dstSubresource().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).mipLevel(0).baseArrayLayer(0).layerCount(1);
        region.dstOffsets(0).set(0, 0, 0);
        region.dstOffsets(1).set(target.width, target.height, 1);

        vkCmdBlitImage(commandBuffer, source.getId(), VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
                target.getId(), VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, region, VK_FILTER_NEAREST);

        target.transitionImageLayout(stack, commandBuffer, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
        source.transitionImageLayout(stack, commandBuffer, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
    }

    @Override
    public void close() {
        if (drawn == this) {
            drawn = null;
            drawnKey = null;
        }

        Set<GpuTexture> freed = Collections.newSetFromMap(new IdentityHashMap<>());

        for (Stage stage : this.stages) {
            if (stage.pipeline() != null) {
                stage.pipeline().close();
            }

            if (stage.globals() != null) {
                stage.globals().free();
            }

            if (stage.pass() != null) {
                stage.pass().close();
            }
        }

        if (this.finalStage != null) {
            if (this.finalStage.pipeline() != null) {
                this.finalStage.pipeline().close();
            }

            if (this.finalStage.globals() != null) {
                this.finalStage.globals().free();
            }

            if (this.finalStage.pass() != null) {
                this.finalStage.pass().close();
            }

            this.finalStage = null;
        }

        if (this.presentation != null) {
            this.presentation.close();
            this.presentation = null;
        }

        if (this.noise != null) {
            this.noise.close();
            this.noise = null;
        }

        this.noisePixels = null;

        for (GpuTexture[] pair : this.targets.values()) {
            for (GpuTexture texture : pair) {
                if (freed.add(texture)) {
                    texture.close();
                }
            }
        }

        if (this.probe != null) {
            this.probe.freeBuffer();
            this.probe = null;
        }

        if (this.sceneProbe != null) {
            this.sceneProbe.freeBuffer();
            this.sceneProbe = null;
        }

        this.stages.clear();
        this.targets.clear();
    }

    /** The facade's format for a format the pack declared, or null when it has none. */
    private static GpuFormat formatOf(TargetFormat format) {
        return switch (format) {
            case RGBA8_UNORM -> GpuFormat.RGBA8;
            case RGBA16_UNORM -> GpuFormat.RGBA16;
            case RGBA16_FLOAT -> GpuFormat.RGBA16F;
            case RGBA32_FLOAT -> GpuFormat.RGBA32F;
            case RG8_UNORM -> GpuFormat.RG8;
            case R8_UNORM -> GpuFormat.R8;
            case RG16_FLOAT -> GpuFormat.RG16F;
            case R16_FLOAT -> GpuFormat.R16F;
            case R32_FLOAT -> GpuFormat.R32F;
            case RGB10A2_UNORM -> GpuFormat.RGB10A2;
            case RG11B10_FLOAT -> GpuFormat.RG11B10F;
            default -> null;
        };
    }

    /**
     * Builds and logs the pack's own chain, once, for a session started with
     * {@code -Dvitrail.debug.chain=true}.
     * <p>
     * Called at load beside the chain that draws, so a capture says both what the engine runs and
     * what the pack asks for. The result is closed immediately: nothing of it is drawn, and holding
     * a pack's worth of targets for a line in the log would be a leak with a purpose.
     */
    public static void describeAndClose(Path pack, String dimension, int screenWidth, int screenHeight) {
        try (VitrailPackChain chain = build(pack, dimension, screenWidth, screenHeight)) {
            Initializer.LOGGER.info("Vitrail: pack chain, {}", chain.report());
            Initializer.LOGGER.info("Vitrail: pack chain order, {}", chain.order());
        }
    }

}
