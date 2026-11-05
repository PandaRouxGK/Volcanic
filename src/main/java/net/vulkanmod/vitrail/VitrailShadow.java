package net.vulkanmod.vitrail;

import com.mojang.blaze3d.vertex.VertexFormat;
import dev.vitrail.glsl.PackProgram;
import dev.vitrail.glsl.TranslatedUnit;
import dev.vitrail.glsl.VertexInputs;
import dev.vitrail.pack.model.ProgramStage;
import dev.vitrail.pack.program.TerrainPass;
import dev.vitrail.pack.source.OpenedPack;
import dev.vitrail.pack.target.SamplerPlan;
import net.minecraft.client.Minecraft;
import net.vulkanmod.Initializer;
import net.vulkanmod.render.pipeline.RenderPipeline;
import net.vulkanmod.render.vertex.TerrainRenderType;
import net.vulkanmod.vitrail.gpu.GpuBuffer;
import net.vulkanmod.vitrail.gpu.GpuDevice;
import net.vulkanmod.vitrail.gpu.GpuPipeline;
import net.vulkanmod.vulkan.memory.MemoryTypes;
import net.vulkanmod.vulkan.memory.UniformBuffer;
import net.vulkanmod.vulkan.shader.GraphicsPipeline;
import net.vulkanmod.vulkan.shader.descriptor.UBO;
import net.vulkanmod.vulkan.shader.layout.PushConstants;
import net.vulkanmod.vulkan.texture.VTextureSelector;

import org.lwjgl.system.MemoryUtil;

import java.io.IOException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The pack's own shadow map, drawn over the engine's chunk mesh.
 * <p>
 * The engine has always had a shadow map: three cascades of sun depth, and a colour buffer per
 * cascade that the translucent casters tint. A pack, though, does not only want the depth — it
 * wants its own program to write it, because that program is where its shadow colour, its waving
 * and its own cutout live. This class compiles exactly those programs, for the engine's mesh, and
 * hands them to the shadow pass in place of the engine's own.
 * <p>
 * The mesh already carries the region offset: {@link PushConstants#region()} is the twenty byte
 * range the chunk draw pushes, {@code SodiumVertex} declares it as {@code of_RegionOffset}, and the
 * vertex buffer holds positions relative to their section. Nothing here has to know about chunks.
 * <p>
 * The map drawn here is the cascade the pack's uniforms describe
 * ({@link net.vulkanmod.vulkan.pass.ShadowMap#PACK_CASCADE}), which is the one
 * {@code shadowView()} and {@code shadowProjection()} are built from and the one {@code shadowtex0}
 * is answered with. Drawing it anywhere else would place every shadow by a matrix that does not
 * describe the image.
 * <p>
 * That cascade is written by the pack's program and by nothing else: the three opaque chunk types
 * are all served by it, so no geometry lands in the distorted image through a stage that did not
 * distort it. A single undistorted caster in this map would be in the wrong texel for every
 * composite pass that reads it, and the map would look right in a coverage count while being wrong
 * in place.
 */
public final class VitrailShadow implements AutoCloseable {

    /** The shadow pass currently installed, or null when the engine's own is drawing the map. */
    private static VitrailShadow active;
    private static String installedKey;

    /**
     * The A/B switch: {@code -Dvitrail.shadow.pack=false} leaves the engine's own map in place.
     * <p>
     * Same frame, same time, same pose — what changes is only who drew the depth a composite pass
     * compares against, which is the one thing that shows whether the pack's program writes it.
     */
    private static final boolean PACK_SHADOW =
            !"false".equalsIgnoreCase(System.getProperty("vitrail.shadow.pack", "true"));

    /** Whether the pack's stage has bound to a handle once, for the log of the experiment. */
    private static final java.util.Set<String> REPORTED_BINDINGS = new java.util.HashSet<>();

    /** Sections handed to the pack's stage, and the frames they were counted over. */
    private static long drawnSections;
    private static int drawnFrames;

    private final String packName;
    private final GpuPipeline solid;
    private final GpuPipeline cutout;
    private final VitrailGlobals globals;

    /**
     * The block's own device buffer, written once a frame by {@link #frame()}.
     * <p>
     * The engine's shared uniform arenas are written by whichever pipeline binds next, and this
     * block is read by two pipelines that are handed the same bytes; one small buffer of our own is
     * the simplest thing that is true for both.
     */
    private final UniformBuffer block;
    private final String failure;

    private VitrailShadow(String packName, GpuPipeline solid, GpuPipeline cutout,
                          VitrailGlobals globals, UniformBuffer block, String failure) {
        this.packName = packName;
        this.solid = solid;
        this.cutout = cutout;
        this.globals = globals;
        this.block = block;
        this.failure = failure;
    }

    public static VitrailShadow active() {
        return active;
    }

    /**
     * The pack's own shadow pass for this pack, built once and kept.
     * <p>
     * Keyed by pack and place rather than by size: a shadow map's size is the pack's own setting,
     * so a window resize must not rebuild it.
     */
    public static VitrailShadow installed(Path pack, String place) {
        String key = pack.toAbsolutePath() + "|" + place;

        if (active != null && key.equals(installedKey)) {
            return active;
        }

        if (active != null) {
            active.close();
            active = null;
        }

        VitrailShadow built = open(pack, place);
        active = built;
        installedKey = key;

        if (built.drawable()) {
            Initializer.LOGGER.info("Vitrail: pack shadow installed, {}", built.report());
        }

        return built;
    }

    private static VitrailShadow open(Path pack, String place) {
        String name = String.valueOf(pack.getFileName());

        try (OpenedPack opened = OpenedPack.open(pack, Map.of(), "")) {
            PackProgram.Terrain terrain = PackProgram.loadTerrain(opened, place, VertexInputs.TERRAIN);
            PackProgram.Loaded solid = terrain.programs().get(TerrainPass.SHADOW_SOLID);
            PackProgram.Loaded cutout = terrain.programs().get(TerrainPass.SHADOW_CUTOUT);

            if (solid == null && cutout == null) {
                Initializer.LOGGER.info("Vitrail: pack shadow, {} serves no shadow program for {}, "
                        + "the engine's own map keeps drawing it", name, place);
                return new VitrailShadow(name, null, null, null, null, "no shadow program");
            }

            VertexFormat format = net.vulkanmod.vulkan.Renderer.getInstance()
                    .getPipelineManager().TERRAIN_VERTEX_FORMAT;
            Program compiled = compile("shadow", solid != null ? solid : cutout, format);
            GpuPipeline cutoutPipeline = cutout != null && cutout != solid
                    ? compile("shadow_cutout", cutout, format).pipeline()
                    : compiled.pipeline();

            Initializer.LOGGER.info("Vitrail: pack shadow, {} drawn from the engine's mesh, "
                    + "solid={} cutout={}, region block {} bytes, mesh {}",
                    name, solid != null, cutout != null, PushConstants.region().getSize(),
                    formatReport());

            UniformBuffer block = compiled.globals() == null ? null
                    : new UniformBuffer(compiled.globals().size(), MemoryTypes.HOST_MEM);

            if (block != null) {
                for (UBO ubo : compiled.pipeline().pipeline().getBuffers()) {
                    ubo.setUniformBuffer(block);
                }

                if (cutoutPipeline != compiled.pipeline()) {
                    for (UBO ubo : cutoutPipeline.pipeline().getBuffers()) {
                        ubo.setUniformBuffer(block);
                    }
                }
            }

            var built = compiled.pipeline().pipeline();
            Initializer.LOGGER.info("Vitrail: pack shadow pipeline, {} block(s) [{}]",
                    built.getBuffers().size(), built.getBuffers().stream()
                            .map(ubo -> ubo.getBinding() + ":" + ubo.getSize())
                            .collect(java.util.stream.Collectors.joining(", ")));

            return new VitrailShadow(name, compiled.pipeline(), cutoutPipeline, compiled.globals(),
                    block, null);
        } catch (IOException | RuntimeException exception) {
            Initializer.LOGGER.error("Vitrail: the shadow pass of {} could not be built, "
                    + "the engine's own map keeps drawing it", pack, exception);
            return new VitrailShadow(name, null, null, null, null, exception.toString());
        }
    }

    /**
     * Compiles one chunk pass of the pack for the engine's mesh.
     * <p>
     * The vertex text is the one the front end wrote against the chunk contract, so it declares the
     * mesh's elements and decodes them itself; what it must not get is the full screen path's
     * corners, which is why this does not go through the chain's own compile.
     */
    private static Program compile(String name, PackProgram.Loaded loaded, VertexFormat format) {
        TranslatedUnit vertex = loaded.program().stages().get(ProgramStage.VERTEX);
        TranslatedUnit fragment = loaded.program().stages().get(ProgramStage.FRAGMENT);

        if (vertex == null || fragment == null) {
            throw new IllegalStateException(name + ": the pack serves only one of the two stages");
        }

        VitrailProgram.Bound bound = VitrailProgram.assignVaryings(
                VitrailProgram.assignBindings(vertex.text(), fragment.text()));

        VitrailProgram.dump(name, bound.vertex(), bound.fragment());

        GpuPipeline.Builder builder = GpuDevice.program(name, probeVertex(bound.vertex()), bound.fragment())
                .vertexFormat(format)
                .pushConstants(PushConstants.region());

        List<VitrailProgram.BlockDecl> blocks =
                VitrailProgram.blockDecls(bound.vertex(), bound.fragment());
        // The sun's transform, not the camera's: this stage projects with the sun's pair itself, and
        // the matrix it unwinds has to be the one that put the casters in this image.
        VitrailGlobals globals = blocks.isEmpty()
                ? null
                : VitrailGlobals.ofSunPass(vertex.blockMembers());

        for (VitrailProgram.BlockDecl block : blocks) {
            builder.block(block.binding(), block.stages(), globals == null
                    ? GpuDevice.buffer(256)
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

            if (declaration == null || slot >= VTextureSelector.SIZE) {
                continue;
            }

            builder.sampler(declaration.binding(), declaration.type(), declaration.name(), slot++);
        }

        return new Program(builder.build(), globals);
    }

    /**
     * Whether this binding is the first of its kind, which is what the caller logs.
     * <p>
     * A pack pipeline that resolves to no handle binds nothing at all and the draw that follows is
     * recorded against whatever was bound before, so a map that stays empty has no error to read:
     * the answer has to be said here, once per pass and per type.
     */
    public static boolean reportBinding(boolean bound, TerrainRenderType type, int sections) {
        return REPORTED_BINDINGS.add(type + "|" + bound + "|" + sections);
    }

    /**
     * The probe switch, which places the casters by one step of the pack's own transform instead of
     * by all of it. {@code -Dvitrail.shadow.probe=} answers one question per value:
     * <ul>
     * <li>{@code flat} — the position the mesh decoded to, which says whether it is real geometry;</li>
     * <li>{@code mvp} — that position through the pack's own transform, which says whether the
     * matrix the sun's pair is published as lands anything;</li>
     * <li>{@code roundtrip} — the position after the pack's unwind of that matrix, which is where a
     * stage that projects with the sun's pair twice goes wrong.</li>
     * </ul>
     * Every one of them writes the middle of the volume, so what the map shows is coverage and not
     * the depth of a transform that has not been measured yet.
     */
    private static String probeVertex(String vertex) {
        String probe = System.getProperty("vitrail.shadow.probe");

        if (probe == null) {
            return vertex;
        }

        String placed = switch (probe) {
            case "flat" -> "vec4(of_Vertex.xy * 0.01, 0.5, 1.0)";
            case "mvp" -> "vec4((of_ModelViewProjectionMatrix * of_Vertex).xy, 0.5, 1.0)";
            case "roundtrip" -> "vec4((shadowModelViewInverse * shadowProjectionInverse"
                    + " * (of_ModelViewProjectionMatrix * of_Vertex)).xy * 0.01, 0.5, 1.0)";
            // The two diagonal entries the sun's pair is logged with, scaled so that the values it
            // is expected to hold land inside the volume. A full map says the block carries the
            // matrix Java computed; anything else says the offsets the pack reads it at are not the
            // ones it was written to.
            case "matdiag" -> "vec4(of_ModelViewProjectionMatrix[0].x * 50.0,"
                    + " of_ModelViewProjectionMatrix[1].y * 50.0, 0.5, 1.0)";
            // The same entry, placed in the depth the probe reports as a number instead: it reads
            // back as {@code 0.5 + 15 * m00} over the whole map, so the block's value is a figure to
            // compare with the one the log prints, and not a sign that the casters landed somewhere.
            // The y translation of the pair, which is the entry that tells the camera's frame apart
            // from the world's: it reads back as {@code 0.5 + 0.2 * m31}, so a block carrying the
            // matrix Java computed reports about 0.09 and one carrying the world's reports 0.5.
            case "matvalue" -> "vec4(of_Vertex.xy * 0.01,"
                    + " 0.5 + 0.2 * of_ModelViewProjectionMatrix[3].y, 1.0)";
            // A member this front end writes itself, and to a value no stage could leave there by
            // accident: {@code of_DepthConv} is half, half, one, nought, so the probe reports 0.9
            // when the block arrives and 0.0 when the descriptor describes empty memory.
            case "uboblock" -> "vec4(of_Vertex.xy * 0.01, 0.9 * of_DepthConv.z, 1.0)";
            // Whether the twenty byte region offset this pass pushes ever reached the stage, which
            // is what the spreadsheet could not settle: every section of a region collapsed onto the
            // region's own corner looks exactly like a real vertex that merely sits far from the
            // camera. Zero across the map says the push constant is not delivered; anything up to a
            // region's two hundred and fifty six blocks says it is.
            case "region" -> "vec4(of_Vertex.xy * 0.01, length(of_RegionOffset) * 0.01, 1.0)";
            // The depth the published pair gives that vertex, written the way the pack's own stage
            // writes it, with the xy left alone so the footprint is the real one. Comparing the
            // bands it reports with the engine's own map says whether the vertex or the transform
            // is what puts the casters where they land.
            case "zfromvertex" -> "vec4((shadowProjection * shadowModelView * of_Vertex).xy,"
                    + " 0.1 * (shadowProjection * shadowModelView * of_Vertex).z + 0.5, 1.0)";
            // How far from the camera the decoded vertex really is, in hundreds of blocks, across
            // the whole footprint. The probe reports it as a depth range: a few hundredths is a
            // position that never got its region offset, one that reaches one is terrain a cascade
            // away, and a range that starts above one is a decodethat has left the world behind.
            case "spread" -> "vec4(of_Vertex.xy * 0.01,"
                    + " length(of_Vertex.xy) * 0.01, 1.0)";
            default -> null;
        };

        if (placed == null) {
            return vertex;
        }

        String epilogue = "gl_Position.z = of_DepthConv.x * gl_Position.z"
                + " + of_DepthConv.y * gl_Position.w; }";

        return vertex.replace(epilogue, "gl_Position.z = of_DepthConv.x * gl_Position.z"
                + " + of_DepthConv.y * gl_Position.w; gl_Position = " + placed + "; }");
    }

    /** Counts the sections this pass handed to the pack's stage, and says so now and then. */
    public static void noteDraws(int sections) {
        drawnSections += sections;
        drawnFrames++;

        if (drawnFrames % 300 == 0) {
            Initializer.LOGGER.info("Vitrail: pack shadow, {} sections over {} frames",
                    drawnSections, drawnFrames);
        }
    }

    /** The vertex format the pack's chunk stage was built for, as the log wants it. */
    public static String formatReport() {
        String format = String.valueOf(net.vulkanmod.vulkan.Renderer.getInstance()
                .getPipelineManager().TERRAIN_VERTEX_FORMAT);
        int size = net.vulkanmod.vulkan.Renderer.getInstance()
                .getPipelineManager().TERRAIN_VERTEX_FORMAT.getVertexSize();

        return format.substring(format.lastIndexOf(':') + 1) + " " + size + " bytes";
    }

    /** The pipeline this pass draws a chunk pass with, or null to leave the engine's own in place. */    public static RenderPipeline pipelineFor(TerrainRenderType type) {
        VitrailShadow shadow = active;

        if (!PACK_SHADOW || shadow == null || shadow.solid == null) {
            return null;
        }

        // A pack's shadow stage spends the same code on water as on stone: it projects, distorts and
        // writes. Serving the translucent type from the engine's stage instead would leave water in
        // this image placed by an undistorted pair, in a texel no composite pass ever asks for.
        GpuPipeline pipeline = switch (type) {
            case SOLID, TRANSLUCENT -> shadow.solid;
            case CUTOUT, CUTOUT_MIPPED -> shadow.cutout;
            default -> null;
        };

        return pipeline == null ? null : pipeline.pipeline();
    }

    /**
     * Fills the pack's uniform block for this frame, once, before the casters are drawn.
     * <p>
     * The block is the one the chain also fills, so the sun's matrices, the camera and the clocks
     * are the same numbers a composite pass reads. Called from the shadow render because that is
     * the frame's first use of it.
     */
    public static void frame() {
        VitrailShadow shadow = active;

        if (shadow == null || shadow.globals == null) {
            return;
        }

        Minecraft client = Minecraft.getInstance();
        int width = client.getWindow() != null ? client.getWindow().getWidth() : 1;
        int height = client.getWindow() != null ? client.getWindow().getHeight() : 1;

        shadow.globals.write(Math.max(1, width), Math.max(1, height));

        if (shadow.block != null) {
            MemoryUtil.memCopy(shadow.globals.address(), shadow.block.getPointer(),
                    shadow.globals.size());
        }

        if (Boolean.getBoolean("vitrail.shadow.matrices") && drawnFrames % 300 == 0) {
            Initializer.LOGGER.info("Vitrail: pack shadow, the sun's pair {}",
                    VitrailGlobals.sunReport());
            Initializer.LOGGER.info("Vitrail: pack shadow, the block holds {}, {} copies so far",
                    shadow.globals.mvpReport(),
                    net.vulkanmod.vulkan.shader.descriptor.ManualUBO.UPDATES);
            Initializer.LOGGER.info("Vitrail: client state, chunks={} player={} screen={}",
                    client.level == null ? -1 : client.level.getChunkSource().getLoadedChunksCount(),
                    client.player != null,
                    client.screen == null ? "none" : client.screen.getClass().getSimpleName());
        }
    }

    public boolean drawable() {
        return this.solid != null;
    }

    public String report() {
        return "pack=" + this.packName + " solid=" + (this.solid != null)
                + " cutout=" + (this.cutout != null)
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

    /** One compiled chunk pass, with the block its uniforms are read from. */
    private record Program(GpuPipeline pipeline, VitrailGlobals globals) {
    }
}
