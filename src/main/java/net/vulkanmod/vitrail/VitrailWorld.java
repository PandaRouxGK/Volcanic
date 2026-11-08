package net.vulkanmod.vitrail;

import com.mojang.blaze3d.vertex.VertexFormat;
import dev.vitrail.glsl.PackProgram;
import dev.vitrail.glsl.TranslatedUnit;
import dev.vitrail.glsl.VertexInputs;
import dev.vitrail.pack.model.ProgramStage;
import dev.vitrail.pack.program.TerrainPass;
import dev.vitrail.pack.source.OpenedPack;
import net.minecraft.client.Minecraft;
import net.vulkanmod.Initializer;
import net.vulkanmod.render.vertex.TerrainRenderType;
import net.vulkanmod.vitrail.gpu.GpuBuffer;
import net.vulkanmod.vitrail.gpu.GpuDevice;
import net.vulkanmod.vitrail.gpu.GpuPipeline;
import net.vulkanmod.vulkan.pass.ShadowMap;
import net.vulkanmod.vulkan.shader.GraphicsPipeline;
import net.vulkanmod.vulkan.shader.layout.PushConstants;
import net.vulkanmod.vulkan.texture.VTextureSelector;
import net.vulkanmod.vulkan.texture.VulkanImage;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The pack's own geometry programs, drawn over the engine's chunk mesh.
 * <p>
 * This is the other half of a pack. Its {@code shadow} program puts the casters in the sun's map,
 * and its {@code gbuffers_*} family turns the world's vertices into the albedo, normals and material
 * the pack's {@code deferred} and {@code composite} passes light. Without this half the pack's
 * screen chain runs over buffers that only ever held their clears — which is what the two images
 * measured at the same hour said: forty per cent more casters in the map, and a frame identical to
 * one bit, because nothing it shaded had any geometry in it.
 * <p>
 * The mesh is the same one {@link VitrailShadow} draws: twenty byte compressed terrain, positions
 * relative to their section, the region's origin pushed at every chunk area. So the compilation is
 * the same job — the pack's stage written against {@link VertexInputs#TERRAIN}, the engine's format
 * on the pipeline, the region block in the layout — and the one difference is the transform: a
 * shadow stage is placed by the sun's pair, this one by the camera's, which is what
 * {@link VitrailGlobals#of(List, boolean)} with {@code fullscreen = false} publishes.
 * <p>
 * <strong>Water is not among these.</strong> {@code gbuffers_water} reads the pack's own buffers —
 * {@code depthtex1}, {@code gaux1}, {@code colortex8} and {@code colortex9} — and this engine feeds
 * a terrain draw exactly three images, the atlas, the light map and the PBR atlas, by the names a
 * pack gives them. Serving those other names is a piece of work of its own; drawn with them white,
 * water would be the pack's program reading nothing, which is not an improvement on the engine's
 * water. The translucent type therefore stays on the engine and writes what it writes into the
 * first buffer of the pack, which is honest for a first frame and is not the finished picture.
 */
public final class VitrailWorld implements AutoCloseable {

    /** The world's geometry pass currently installed, or null when the engine draws the world. */
    private static VitrailWorld active;
    private static String installedKey;

    /**
     * The A/B switch: {@code -Dvitrail.world.pack=false} leaves the engine's terrain pipelines in
     * the world's slot.
     * <p>
     * Same frame, same pose, same hour — what changes is who turns the mesh into the pack's albedo,
     * which is the one thing that says whether the geometry half reached the chain at all.
     */
    private static final boolean PACK_WORLD =
            !"false".equalsIgnoreCase(System.getProperty("vitrail.world.pack", "true"));

    private final String packName;
    private final GpuPipeline solid;
    private final GpuPipeline cutout;
    /** One block per program, because two programs need not declare the same members. */
    private final List<VitrailGlobals> blocks;
    /** What each program writes, so a program whose outputs do not fit is left to the engine. */
    private final Map<GpuPipeline, List<Integer>> outputs;
    /**
     * The colour buffers the world is drawn into, in the order the fragment outputs number them.
     * <p>
     * Taken from the program with the most outputs, and only when every other program's list is a
     * prefix of it: one framebuffer serves the whole world, so an output of one program that would
     * land on a different buffer than the same output of another is not something a single
     * attachment list can answer. A program that disagrees is left to the engine, and the log says
     * which.
     */
    private final List<Integer> targets;
    private final String failure;

    private VitrailWorld(String packName, GpuPipeline solid, GpuPipeline cutout,
                         List<VitrailGlobals> blocks, Map<GpuPipeline, List<Integer>> outputs,
                         List<Integer> targets, String failure) {
        this.packName = packName;
        this.solid = solid;
        this.cutout = cutout;
        this.blocks = blocks;
        this.outputs = outputs;
        this.targets = targets;
        this.failure = failure;
    }

    /** Whether this session asked the pack to draw the world at all. */
    public static boolean requested() {
        return PACK_WORLD;
    }

    public static VitrailWorld active() {
        return active;
    }

    /**
     * The pack's own geometry pass for this pack, built once and kept.
     * <p>
     * Keyed by pack and place, like the shadow pass: the world framebuffer is rebuilt when the
     * window resizes, and the programs it draws with are not.
     */
    public static VitrailWorld installed(Path pack, String place) {
        String key = pack.toAbsolutePath() + "|" + place;

        if (active != null && key.equals(installedKey)) {
            return active;
        }

        if (active != null) {
            active.close();
            active = null;
        }

        VitrailWorld built = open(pack, place);
        active = built;
        installedKey = key;

        if (built.drawable()) {
            Initializer.LOGGER.info("Vitrail: pack world installed, {}", built.report());
        }

        return built;
    }

    private static VitrailWorld open(Path pack, String place) {
        String name = String.valueOf(pack.getFileName());

        try (OpenedPack opened = OpenedPack.open(pack, Map.of(), "")) {
            PackProgram.Terrain terrain = PackProgram.loadTerrain(opened, place, VertexInputs.TERRAIN);
            PackProgram.Loaded solid = terrain.programs().get(TerrainPass.SOLID);
            PackProgram.Loaded cutout = terrain.programs().get(TerrainPass.CUTOUT);

            if (solid == null && cutout == null) {
                Initializer.LOGGER.info("Vitrail: pack world, {} serves no gbuffers_terrain for {}, "
                        + "the engine keeps drawing the world", name, place);
                return new VitrailWorld(name, null, null, List.of(), Map.of(), List.of(),
                        "no geometry program");
            }

            VertexFormat format = net.vulkanmod.vulkan.Renderer.getInstance()
                    .getPipelineManager().TERRAIN_VERTEX_FORMAT;

            Program solidProgram = solid != null ? compile("gbuffers_solid", solid, format) : null;
            Program cutoutProgram = cutout != null && cutout != solid
                    ? compile("gbuffers_cutout", cutout, format)
                    : solidProgram;

            List<Integer> targets = commonTargets(List.of(
                    new Named("gbuffers_terrain", solidProgram),
                    new Named("gbuffers_terrain_cutout", cutoutProgram)));

            Initializer.LOGGER.info("Vitrail: pack world, {} drawn from the engine's mesh, "
                    + "solid={} cutout={}, outputs {} into {}, region block {} bytes",
                    name, solidProgram != null, cutoutProgram != null,
                    outputs(solidProgram, cutoutProgram), targets, PushConstants.region().getSize());

            List<VitrailGlobals> blocks = new ArrayList<>(2);
            Map<GpuPipeline, List<Integer>> written = new java.util.LinkedHashMap<>();

            for (Program program : new Program[] {solidProgram, cutoutProgram}) {
                if (program == null) {
                    continue;
                }

                if (program.globals() != null && !blocks.contains(program.globals())) {
                    blocks.add(program.globals());
                }

                written.put(program.pipeline(), program.targets());
            }

            return new VitrailWorld(name,
                    solidProgram == null ? null : solidProgram.pipeline(),
                    cutoutProgram == null ? null : cutoutProgram.pipeline(),
                    List.copyOf(blocks), Map.copyOf(written), targets, null);
        } catch (IOException | RuntimeException exception) {
            Initializer.LOGGER.error("Vitrail: the geometry pass of {} could not be built, "
                    + "the engine keeps drawing the world", pack, exception);
            return new VitrailWorld(name, null, null, List.of(), Map.of(), List.of(),
                    exception.toString());
        }
    }

    /** A program under the name the log and the compile error want for it. */
    private record Named(String name, Program program) {
    }

    /**
     * The attachment list one framebuffer can give every program, or an empty list when none can.
     * <p>
     * The widest list is the candidate; a program whose list is not its prefix keeps its own
     * ordering only if the framebuffer were rebuilt per draw, which a world pass cannot do.
     */
    private static List<Integer> commonTargets(List<Named> programs) {
        List<Integer> widest = List.of();

        for (Named named : programs) {
            if (named.program() != null && named.program().targets().size() > widest.size()) {
                widest = named.program().targets();
            }
        }

        return List.copyOf(widest);
    }

    private static String outputs(Program solid, Program cutout) {
        return "solid " + (solid == null ? "none" : solid.targets())
                + ", cutout " + (cutout == null ? "none" : cutout.targets());
    }

    /**
     * Compiles one geometry program of the pack for the engine's mesh.
     * <p>
     * The block is the camera's: this stage is placed by the view and the projection the engine has
     * just applied, through {@link VitrailGlobals#of(List, boolean)} with {@code fullscreen = false}
     * — which is also what publishes the sun's pair under the shadow names a terrain stage samples
     * its occlusion with. {@code ManualUBO} copies those bytes into the frame's uniform buffer at
     * every bind, so nothing here needs a buffer of its own.
     */
    private static Program compile(String name, PackProgram.Loaded loaded, VertexFormat format) {
        TranslatedUnit vertex = loaded.program().stages().get(ProgramStage.VERTEX);
        TranslatedUnit fragment = loaded.program().stages().get(ProgramStage.FRAGMENT);

        if (vertex == null || fragment == null) {
            throw new IllegalStateException(name + ": the pack serves only one of the two stages");
        }

        VitrailProgram.Bound bound = VitrailProgram.shadowProbe(VitrailProgram.assignVaryings(
                VitrailProgram.assignBindings(vertex.text(), fragment.text())), name);

        VitrailProgram.dump(name, bound.vertex(), bound.fragment());

        GpuPipeline.Builder builder = GpuDevice.program(name, bound.vertex(), bound.fragment())
                .vertexFormat(format)
                // The chunk area's origin, which is what places a section's vertices in the world.
                .pushConstants(PushConstants.region());

        VitrailGlobals globals = VitrailGlobals.of(vertex.blockMembers(), false);

        for (VitrailProgram.BlockDecl block : VitrailProgram.blockDecls(bound.vertex(), bound.fragment())) {
            builder.block(block.binding(), block.stages(),
                    GpuBuffer.wrap(globals.address(), globals.size()));
        }

        // Slots by the name the engine fills, not by declaration order: a chunk draw binds the
        // atlas at nought, the light map at two and the PBR atlas at four, and a pack names those
        // three rather than numbering them. Every other name this stage reads goes to the last slot,
        // which no engine code writes and which therefore holds white.
        for (VitrailProgram.SamplerDecl declaration
                : VitrailProgram.samplerDecls(bound.vertex(), bound.fragment())) {
            builder.sampler(declaration.binding(), declaration.type(), declaration.name(),
                    VitrailProgram.terrainSlot(declaration.name()));
        }

        return new Program(builder.build(), globals, fragment.drawBuffers());
    }

    /**
     * The pipeline this draw should use for one terrain type, or null to leave the engine's in place.
     * <p>
     * Translucent stays with the engine on purpose, for the reason the class describes: the pack's
     * water stage reads buffers this draw cannot feed yet.
     */
    public static GraphicsPipeline pipelineFor(TerrainRenderType type) {
        VitrailWorld world = active;

        if (!PACK_WORLD || world == null) {
            return null;
        }

        return world.drawable(switch (type) {
            case SOLID -> world.solid;
            case CUTOUT, CUTOUT_MIPPED -> world.cutout;
            default -> null;
        });
    }

    /** One program, when its outputs land where the world framebuffer puts them. */
    private GraphicsPipeline drawable(GpuPipeline pipeline) {
        if (pipeline == null) {
            return null;
        }

        return fits(pipeline) ? pipeline.pipeline() : null;
    }

    /** Whether this program's outputs are the world framebuffer's prefix. */
    private boolean fits(GpuPipeline pipeline) {
        List<Integer> written = this.outputs.get(pipeline);

        if (written == null || written.size() > this.targets.size()) {
            return false;
        }

        return written.equals(this.targets.subList(0, written.size()));
    }

    /**
     * Fills the blocks of the world's stage for the frame about to be drawn.
     * <p>
     * Called from the world draw itself, after the engine has applied this frame's view and
     * projection: the block is read from those two, so filling it any earlier would publish the
     * previous frame's camera. Written per terrain type rather than once, because a program that
     * only draws water is not drawn by a frame with no water in sight; a few dozen float stores and
     * two matrix inversions per type are not worth a frame counter to avoid.
     */
    public static void frame() {
        VitrailWorld world = active;

        if (world == null || world.blocks.isEmpty()) {
            return;
        }

        Minecraft client = Minecraft.getInstance();
        int width = client.getWindow() != null ? client.getWindow().getWidth() : 1;
        int height = client.getWindow() != null ? client.getWindow().getHeight() : 1;

        for (VitrailGlobals block : world.blocks) {
            block.write(Math.max(1, width), Math.max(1, height));
        }

        if (!REPORTED_SHADOW_PAIR && VitrailDebug.ENABLED) {
            REPORTED_SHADOW_PAIR = true;
            Initializer.LOGGER.info("Vitrail: pack world shadow pair, {}", VitrailGlobals.shadowReport());

            for (VitrailGlobals block : world.blocks) {
                Initializer.LOGGER.info("Vitrail: pack world block, {} uniform(s) [{}]",
                        block.size() / 4, block.names());
            }
        }
    }

    /** Whether the pair the terrain stage decides its occlusion with has been said in the log. */
    private static boolean REPORTED_SHADOW_PAIR;

    /**
     * Hands the sun's map to the slot a terrain stage samples its occlusion from.
     * <p>
     * The chunk draw fills three images by name, the atlas, the light map and the PBR atlas, and a
     * pack's terrain stage reads a fourth: {@code shadowtex0}. That name was going to the last slot
     * there is, which holds the white texel, so every occlusion test in the stage compared a
     * reference against one — which answers lit — and a map forty per cent full left the ground
     * exactly as bright as an empty one. It is bound here rather than in {@link #frame()} because
     * the engine's own pass over those bindings runs between the two and answers every pack name it
     * does not know with that same white texel; a binding written before it would be a binding
     * overwritten.
     */
    public static void bindTextures() {
        if (active == null || active.blocks.isEmpty()) {
            return;
        }

        VulkanImage answer = shadowDepth();
        VTextureSelector.bindTexture(VTextureSelector.SHADOW_SLOT, answer);

        if (!REPORTED_SHADOW_SLOT) {
            REPORTED_SHADOW_SLOT = true;
            ShadowMap map = ShadowMap.active();
            Initializer.LOGGER.info("Vitrail: terrain shadow slot, map {} ready {} enabled {} answer {}",
                    map != null, map != null && map.isReady(), Initializer.CONFIG.shadowsEnabled,
                    answer == VTextureSelector.getWhiteTexture() ? "the white texel" : "the sun's map");
        }
    }

    /** Whether the slot a terrain stage samples its occlusion from has been said in the log. */
    private static boolean REPORTED_SHADOW_SLOT;

    /**
     * The depth of the sun's map this frame, or the white texel when the engine draws none.
     * <p>
     * {@code -Dvitrail.shadow.map=white} answers the white texel even with a map in hand, which is
     * the arm an A/B of the shadow term needs: the same frame, the same pose, the same sun, and the
     * one difference being what the terrain's occlusion test reads. Anything else that moves between
     * the two runs then belongs to the scene rather than to the term.
     */
    private static VulkanImage shadowDepth() {
        if ("white".equals(System.getProperty("vitrail.shadow.map"))) {
            return VTextureSelector.getWhiteTexture();
        }

        ShadowMap map = ShadowMap.active();

        if (map == null || !map.isReady() || !Initializer.CONFIG.shadowsEnabled) {
            return VTextureSelector.getWhiteTexture();
        }

        return map.getCascadeDepthImage(ShadowMap.PACK_CASCADE);
    }

    public List<Integer> targets() {
        return this.targets;
    }

    public boolean drawable() {
        return this.solid != null || this.cutout != null;
    }

    public String report() {
        return "pack=" + this.packName + " solid=" + (this.solid != null)
                + " cutout=" + (this.cutout != null) + " outputs=" + this.targets
                + (this.failure == null ? "" : " failure=" + this.failure);
    }

    @Override
    public void close() {
        if (this.solid != null) {
            this.solid.close();
        }

        if (this.cutout != null && this.cutout != this.solid) {
            this.cutout.close();
        }
    }

    /** One compiled geometry program, with the block its uniforms are read from. */
    private record Program(GpuPipeline pipeline, VitrailGlobals globals, List<Integer> targets) {
    }
}
