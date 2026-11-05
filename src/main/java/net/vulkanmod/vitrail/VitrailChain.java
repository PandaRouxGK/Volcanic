package net.vulkanmod.vitrail;

import net.vulkanmod.Initializer;
import net.minecraft.client.Minecraft;
import net.minecraft.world.level.Level;
import net.vulkanmod.render.framegraph.FrameGraph;
import net.vulkanmod.render.vertex.TerrainRenderType;
import net.vulkanmod.vulkan.Renderer;
import net.vulkanmod.vulkan.shader.PipelineManager;
import net.vulkanmod.vulkan.device.DeviceManager;
import net.vulkanmod.vulkan.framebuffer.Framebuffer;
import net.vulkanmod.vulkan.pass.ShadowMap;
import net.vulkanmod.vulkan.framebuffer.RenderPass;
import net.vulkanmod.vulkan.texture.VTextureSelector;
import net.vulkanmod.vulkan.texture.VulkanImage;
import net.vulkanmod.vulkan.util.DrawUtil;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkClearColorValue;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkImageBlit;
import org.lwjgl.vulkan.VkImageSubresourceRange;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.lwjgl.vulkan.VK10.*;

/**
 * The screen half of a Vitrail pack: its {@code deferred} passes, then its {@code composite*}
 * programs in the order the pack numbers them, then its {@code final}.
 * <p>
 * Everything a pack writes goes into one set of colour buffers, {@code colortex0} to
 * {@code colortex15}, held for the life of the chain so a pass can read what an earlier pass wrote
 * in the same frame — and, for a pack that reprojects, the frame before. All of them are
 * attachments of one framebuffer, because a pack's passes write several buffers at once and the
 * engine now declares as many attachments as the framebuffer carries.
 * <p>
 * What this still cannot do is the geometry family: {@code all_solid}, {@code all_translucent} and
 * the rest, which fill the buffers from the world's own vertices. The engine draws the world
 * instead, so its lit image is copied into {@code colortex0} and {@code colortex7} — the two names
 * a Chocapic13-family pack keeps its scene under — and every other buffer starts from the pack's
 * own clears. The picture is therefore the pack's post processing of the engine's lighting, not yet
 * the pack's lighting.
 */
public final class VitrailChain {

    private static final String[] DEFERRED = {"deferred", "deferred1", "deferred2"};

    private static final String[] COMPOSITES = {
            "composite", "composite1", "composite2", "composite3", "composite4", "composite5",
            "composite6", "composite7", "composite8", "composite9", "composite10", "composite11",
            "composite12", "composite13", "composite14", "composite15"
    };

    /** The pack's colour buffers, one attachment each, in the order outputs are numbered. */
    private static final int COLOR_TARGETS = 16;

    /** Where a Chocapic13-family pack expects the lit scene: its own colortex0 and colortex7. */
    private static final int[] SCENE_BUFFERS = {0, 7};

    /** The buffer the pack reads as albedo beside its material mask. */
    private static final int ALBEDO_BUFFER = 1;

    /** The buffer the pack reads as its encoded normal and lightmap pair. */
    private static final int NORMAL_BUFFER = 3;

    /**
     * Whether the passes that consume the pack's geometry buffers run.
     * <p>
     * {@code all_solid}, {@code all_translucent} and the rest are the half of a pack that turns the
     * world's vertices into albedo, normals and light; the world is still drawn by the engine here,
     * so those buffers are filled from what the engine does publish — its lit image as the albedo
     * and its normal target as the encoded normal. That is a stand-in, not the pack's geometry: no
     * block identifiers, no lightmap per vertex, no tangent space.
     * <p>
     * Measured, twice: with those stand-ins in place the passes run without crashing, but the fog
     * pass writes a black atmosphere over the scene from a material mask that is empty, so the
     * ground darkens with distance from about six chunks and flickers with the pack's own temporal
     * noise. That is not Bliss and it is worse than not running them, so the flag stays off until
     * the world is drawn by the pack's own geometry programs.
     * <p>
     * Measured again once the geometry was: {@code -Dvitrail.chain.geometry=true} runs six passes and
     * still presents an unlit frame, because the two that light it are refused — {@code deferred} and
     * {@code deferred1} sample {@code dhDepthTex0}, {@code vxDepthTexOpaque} and the pack's own 3D
     * light textures, none of which this chain answers for. So the flag changes which passes run and
     * not whether the world is lit, and it stays off. The chain that does light the frame is the
     * pack's own, drawn by {@link VitrailPackChain}.
     */
    private static final boolean GEOMETRY =
            "true".equalsIgnoreCase(System.getProperty("vitrail.chain.geometry", "false"));

    /**
     * One geometry program is built and logged, to measure the attribute bridge.
     * <p>
     * Measurement only: it compiles and creates a pipeline, and draws with neither. The black frame
     * it was switched off for came from the normal buffer's blit, which the resolver answers with a
     * depth image when the engine has none, and which failed the frame's command buffer; that blit
     * is guarded now, so the probe is back to answer whether the terrain format lines up with what
     * the pack's geometry stage declares.
     */
    private static final boolean GEOMETRY_PROBE = false;

    /**
     * Whether the pack's own geometry programs draw the world.
     * <p>
     * Off, and it has to stay off until the world is drawn into this chain's colour buffers instead
     * of into the engine's: a pack's fragment stage writes four targets, and in the engine's
     * framebuffer those four land on its colour, normal, light and material images. The engine's
     * lighting then reads albedo where it expects a normal and lights the scene a second time. What
     * is here is the registration the routing will switch on, not a behaviour that can be enabled
     * on its own.
     */
    private static final boolean DRAWS_WORLD =
            !"false".equalsIgnoreCase(System.getProperty("vitrail.world.pack", "true"));

    private static boolean worldRoleInstalled;

    private static final Map<String, VitrailChain> CACHE = new HashMap<>();

    private static final Pattern COLORTEX = Pattern.compile("colortex(\\d+)");

    /**
     * The names this engine can answer under another buffer's image without running the pass that
     * would have written them: its depth, its noise and its shadow tint.
     */
    private static final Map<String, String> STAND_IN = Map.ofEntries(
            Map.entry("depthtex0", "depthtex"),
            Map.entry("depthtex1", "depthtex"),
            Map.entry("depthtex2", "depthtex"),
            Map.entry("noisetex", "white"),
            Map.entry("shadowcolor0", "white"),
            Map.entry("shadowcolor1", "white"),
            // The chain has no shadow map, and saying otherwise was what broke the light: these names
            // were answered with the camera's own depth, so every shadow test in the pack compared
            // that depth with itself and measured nothing. A pack reads a filled map as the far
            // plane — `z - d` comes out negative, no blocker is counted, and the surface is lit as if
            // nothing occluded it. A scene without shadows is honest; a scene shaded by a comparison
            // that measures nothing is not, and it is what saturated the light at 240.
            Map.entry("shadow", "white"),
            Map.entry("shadowtex0", "white"),
            Map.entry("shadowtex1", "white"),
            Map.entry("shadowtex2", "white"),
            // Distant Horizons is not installed. Its depth textures read as the far plane, which is
            // exactly what "no distant terrain" means to every test a pack makes on them.
            Map.entry("dhDepthTex", "white"),
            Map.entry("dhDepthTex1", "white"),
            Map.entry("dhDepthTex2", "white"));

    /** The names a pack uses for the sun's depth map, which the engine already renders. */
    private static final Set<String> SHADOW_NAMES =
            Set.of("shadow", "shadowtex0", "shadowtex1", "shadowtex2");

    private static final Set<String> ENGINE = Set.of(
            "scene", "gcolor", "hdrBuffer", "colortex0", "color0",
            "depthtex", "fgdepth", "shadowtex0", "shadowtex1", "shadowtex2",
            "opaquedepth", "material", "gnormal", "materialdepth");

    /** Programs whose sampler bindings have been written to the log, once each. */
    private final Set<String> reportedSamplers = new java.util.HashSet<>();

    private final Path pack;
    private final List<VitrailProgram> programs = new ArrayList<>();
    private VitrailProgram last;
    private Framebuffer worldFramebuffer;
    /** The buffers {@link #worldFramebuffer} was built from, so a rebuilt chain cannot reuse it. */
    private VulkanImage[] worldColour;
    private VulkanImage worldDepth;
    private boolean failed;
    private boolean reportedMissingScene;
    private boolean reportedScene;
    private boolean reportedPresent;
    private boolean reportedFirstFrame;
    private int presentedWidth = -1;
    private int presentedHeight = -1;

    private final VulkanImage[] colortex = new VulkanImage[COLOR_TARGETS];

    /**
     * One copy per pool buffer that some pass reads while writing it.
     * <p>
     * A sampler naming an image that is an attachment of the pass being recorded is a feedback loop,
     * and Vulkan leaves what such a read returns undefined. On this driver it returns nothing, and
     * the pack notices: its first post pass accumulates the eye's adaptation by reading the same
     * {@code colortex4} it writes, so the average brightness it mixes towards was always nought, the
     * exposure it derives stayed at its floor, and every pass after it multiplied the picture by
     * zero — a black screen with the GUI drawn over it. The copy is taken before the pass opens, so
     * what the pass reads is what the buffer held.
     */
    private final VulkanImage[] aliases = new VulkanImage[COLOR_TARGETS];


    /**
     * One framebuffer per distinct list of targets, because a pass attaches exactly the buffers it
     * writes and in the order its outputs are numbered. A single framebuffer over the first eight
     * buffers was what put every pass's picture in the first buffers of the chain: this pack's world
     * asks for {@code RENDERTARGETS:1,8,15} and got {@code 0,1,2}, so its albedo landed where its
     * deferred stage reads something else, and a green sky with no scene in it is that mismatch.
     */
    private final Map<List<Integer>, Pass> passes = new HashMap<>();
    private List<Integer> worldTargets = List.of();
    private int width = -1;
    private int height = -1;

    private VitrailChain(Path pack) {
        this.pack = pack;
    }

    public static VitrailChain of(Path pack) {
        String folder = dimensionFolder();
        String key = pack.toAbsolutePath() + "|" + folder;
        VitrailChain chain = CACHE.get(key);

        if (chain == null) {
            chain = new VitrailChain(pack);

            try {
                chain.read(folder);
            } catch (Exception | OutOfMemoryError exception) {
                // A pack that cannot be built leaves the world to the engine, once, rather than
                // throwing on every frame that asks for it.
                chain.failed = true;
                Initializer.LOGGER.error("Vitrail: {} could not be built, leaving the engine's own image", pack,
                        exception);
            }

            CACHE.put(key, chain);
        }

        return chain;
    }

    /**
     * The directory OptiFine reads a dimension's programs out of. The overworld is {@code world0},
     * the nether {@code world-1} and the end {@code world1}; a pack overrides per dimension and
     * falls back to its root, which is why both are tried in that order.
     */
    public static String dimensionFolder() {
        Minecraft client = Minecraft.getInstance();

        if (client.level == null) {
            return "world0";
        }

        if (client.level.dimension() == Level.NETHER) {
            return "world-1";
        }

        if (client.level.dimension() == Level.END) {
            return "world1";
        }

        return "world0";
    }

    private VitrailProgram program(String name, String folder) {
        VitrailProgram program = VitrailProgram.load(this.pack, folder + "/" + name);

        if (program == null) {
            program = VitrailProgram.load(this.pack, name);
        }

        if (program == null) {
            // Some packs keep one generic set under dimensions/ and override it per world.
            program = VitrailProgram.load(this.pack, "dimensions/" + name);
        }

        return program;
    }

    private void read(String folder) {
        VitrailDebug.logFlags();

        // Debug only: the chain this file hand-wires draws, and the chain the pack itself
        // describes is built beside it and put in the log, so a capture says both. -Dvitrail.debug.chain=true
        if (VitrailDebug.CHAIN) {
            VitrailPackChain.describeAndClose(this.pack, folder,
                    Minecraft.getInstance().getWindow().getWidth(),
                    Minecraft.getInstance().getWindow().getHeight());
        }

        int skipped = 0;

        // Named, not counted. A pass the pack ships and this chain refuses is a hole in the pack's
        // own chain, and so is a pass the pack does not ship for this dimension: one is a sampler or
        // a target this chain cannot feed, the other is simply absent. A bare "N skipped" says the
        // picture is incomplete without saying where.
        List<String> absent = new ArrayList<>();
        List<String> refused = new ArrayList<>();

        // A pack's deferred and composite passes read the buffers its geometry family writes:
        // albedo, normals, the lightmap, the material mask. That family does not exist here, so
        // those buffers only ever hold their clears, and a pass that lights a black albedo with a
        // black normal does not approximate the pack's picture: it multiplies the engine's lit
        // scene by nought and darkens it frame after frame, with the pack's own temporal effects
        // flickering over the result. They stay off until the geometry stage can fill them; what
        // runs is the pack's last pass, which is its own tone mapping over a scene that is lit.
        if (GEOMETRY) {
            for (String name : concat(DEFERRED, COMPOSITES)) {
                VitrailProgram program = program(name, folder);

                if (program == null) {
                    absent.add(name);
                    continue;
                }

                if (!feedable(program)) {
                    skipped++;
                    refused.add(name + " (samples " + missing(program) + ")");
                    continue;
                }

                if (!fitsTargets(program)) {
                    skipped++;
                    refused.add(name + " (writes " + program.drawBuffers + ", more targets than one pass may attach)");
                    continue;
                }

                this.programs.add(program);
            }
        }

        this.last = program("final", folder);

        if (this.last != null && !feedable(this.last)) {
            List<String> missing = missing(this.last);
            Initializer.LOGGER.warn("Vitrail: 'final' of {} samples {} which this chain cannot supply yet,"
                    + " leaving the engine's image", this.pack.getFileName(), missing);
            this.last = null;
        }

        if (this.last == null) {
            // Composites whose result nothing would draw are work spent on a frame that is thrown
            // away, so a refused chain runs none of them.
            this.programs.clear();
        } else if (!GEOMETRY && this.reportedFirstFrame) {
            // The pass list is the pack's own, in its own order. Running only the last pass was
            // tried and it is a passthrough: this pack computes its exposure and its bloom in
            // deferred and composite and leaves the result in a buffer the last pass merely applies,
            // so with those skipped the picture is the engine's, unchanged, and the shader looks
            // like it is doing nothing. The empty material mask costs a dark fog past a few chunks;
            // a pass that is actually the pack's is worth that, and the mask is what the geometry
            // family will fill.
        }

        if (DRAWS_WORLD && !worldRoleInstalled) {
            worldRoleInstalled = true;

            PipelineManager manager = Renderer.getInstance().getPipelineManager();
            var engine = manager.terrainRole(PipelineManager.ROLE_TERRAIN_MAIN);
            var engineFade = manager.terrainRole(PipelineManager.ROLE_TERRAIN_FADE);

            // The pack's own geometry programs, compiled once against the mesh the engine draws.
            // Loading them here rather than in the draw keeps the draw path free of compilation, and
            // this is the one place that knows both the pack and the dimension's folder.
            VitrailWorld world = VitrailWorld.installed(this.pack, folder);

            if (world.drawable() && !world.targets().isEmpty()) {
                // The role is wrapped, not replaced: the pack draws the opaque world and the engine
                // keeps translucent, tripwire and the shadow roles, so a wrapper that forgot to
                // delegate would leave those types with no pipeline to draw with at all.
                //
                // The fade role too, and with the same provider: a chunk fading in is the same
                // geometry drawn with an alpha the pack's stage reads from its colour, and left with
                // the engine's pipeline it would be the one caster in the world writing the engine's
                // notion of the frame into the pack's albedo buffer.
                java.util.function.Function<TerrainRenderType, net.vulkanmod.vulkan.shader.GraphicsPipeline> pack =
                        type -> {
                            var own = VitrailWorld.pipelineFor(type);
                            return own != null ? own : engine != null ? engine.apply(type) : null;
                        };

                java.util.function.Function<TerrainRenderType, net.vulkanmod.vulkan.shader.GraphicsPipeline> packFade =
                        type -> {
                            var own = VitrailWorld.pipelineFor(type);
                            return own != null ? own : engineFade != null ? engineFade.apply(type) : null;
                        };

                manager.registerTerrainRole(PipelineManager.ROLE_TERRAIN_MAIN, pack);
                manager.registerTerrainRole(PipelineManager.ROLE_TERRAIN_FADE, packFade);

                // The geometry pass writes the buffers its own directive names, so the framebuffer
                // the world is drawn into is built from that list rather than from the first eight.
                this.worldTargets = world.targets();
                Initializer.LOGGER.info("Vitrail: '{}' now draws the world into {}", this.pack.getFileName(),
                        this.worldTargets);
            } else {
                Initializer.LOGGER.warn("Vitrail: the pack draws no opaque terrain, the engine keeps the world");
            }
        }

        if (GEOMETRY_PROBE) {
            // The same fallback tree the screen stages walk: this pack keeps its geometry only
            // under dimensions/, so the dimension's own path finds nothing and the root answers.
            String[] candidates = {folder + "/all_solid", "all_solid", "dimensions/all_solid"};
            String built = null;

            for (String candidate : candidates) {
                if (VitrailProgram.loadTerrain(this.pack, candidate) != null) {
                    built = candidate;
                    break;
                }
            }

            Initializer.LOGGER.info("Vitrail: geometry bridge: all_solid {}",
                    built != null ? "built from " + built + " against the engine's terrain format" : "not built");
        }

        if (!absent.isEmpty()) {
            Initializer.LOGGER.info("Vitrail: not shipped for {}: {}", folder, absent);
        }

        if (!refused.isEmpty()) {
            Initializer.LOGGER.warn("Vitrail: refused, this chain cannot feed them yet: {}", refused);
        }

        Initializer.LOGGER.info("Vitrail: {} -> {} pass(es){}, final {}, {} skipped",
                this.pack.getFileName(), this.programs.size(),
                GEOMETRY ? "" : " (geometry stage missing, only the last pass runs)",
                this.last != null ? "ready" : "missing", skipped);
    }

    private static List<String> concat(String[] first, String[] second) {
        List<String> names = new ArrayList<>(first.length + second.length);

        for (String name : first) {
            names.add(name);
        }

        for (String name : second) {
            names.add(name);
        }

        return names;
    }

    /** The names a program samples that no buffer and no stand-in answers for. */
    private static List<String> missing(VitrailProgram program) {
        List<String> missing = new ArrayList<>();

        for (String name : program.samplerNames) {
            if (!answerable(name) && !missing.contains(name)) {
                missing.add(name);
            }
        }

        return missing;
    }

    private static boolean answerable(String name) {
        return ENGINE.contains(name) || STAND_IN.containsKey(name) || COLORTEX.matcher(name).matches();
    }

    private static boolean feedable(VitrailProgram program) {
        return missing(program).isEmpty();
    }

    /**
     * Whether a pass may attach one of the buffers numbered past the device's attachment count.
     * <p>
     * Off, and not a switch to flip on its own. The three passes this pack puts in buffers 12, 13
     * and 14 are accumulation stages: {@code composite} opens with
     * {@code ofFragData0 = vec4(0,0,0,1)} and fills it only where there is a translucent, and
     * {@code composite1} writes {@code vec4(0,0,0, colortex14.a)} into the volumetric buffer. Both
     * expect the pack's own {@code blend.<program>} factors and its {@code colortexNClear} flags,
     * which this chain does not apply: run without them, they overwrite the scene with the zero they
     * mean to start from, and the screen goes black over a frame that was drawing. Turning this on
     * belongs after blending and the clear flags, not instead of them.
     */
    private static final boolean HIGH_TARGETS = true;

    /**
     * A program this chain can give attachments to.
     * <p>
     * The limit is on how many buffers one pass may attach, not on which buffers they are: a pass
     * writing {@code colortex13} attaches that image at slot zero and its shader still writes
     * location zero. Asking the index itself to stay under the device's attachment count is what
     * refused every pass this pack puts past its eighth buffer.
     */
    private static boolean fitsTargets(VitrailProgram program) {
        if (program.drawBuffers.size() > colorTargetLimit()) {
            return false;
        }

        for (int index : program.drawBuffers) {
            if (index < 0 || index >= COLOR_TARGETS
                    || (!HIGH_TARGETS && index >= colorTargetLimit())) {
                return false;
            }
        }

        return true;
    }

    /**
     * How many colour attachments a pass may declare here.
     * <p>
     * Vulkan only promises four, and the Intel driver does not refuse a render pass asking for
     * more: it reads past its own table and takes the process down. The count is therefore asked
     * for, and a pass writing past it is skipped rather than attempted. The pack's buffers past
     * the limit still exist and can be sampled; they just cannot be attachment of a pass.
     */
    private static int colorTargetLimit() {
        int max = DeviceManager.deviceProperties != null
                ? DeviceManager.deviceProperties.limits().maxColorAttachments()
                : 4;

        return Math.min(COLOR_TARGETS, Math.max(1, max));
    }

    /**
     * A framebuffer over this chain's own colour buffers, for the pass that draws the world to use.
     * <p>
     * This is the half of the routing that belongs to the chain: the buffers a pack's geometry stage
     * writes are these images, numbered the way its {@code DRAWBUFFERS} comment numbers them, so a
     * fragment stage writing four locations lands on {@code colortex0} to {@code colortex3} instead
     * of on the engine's colour, normal, light and material images. The depth is the engine's own,
     * lent for the frame: the terrain is still drawn from its mesh, and the pack reads
     * {@code depthtex} from that same image.
     * <p>
     * Nothing adopts it yet — {@link #DRAWS_WORLD} is off — because adopting it also means the engine
     * rebuilds its terrain pipelines against the render pass built for this framebuffer. Handing a
     * pipeline built for another render pass to a draw is refused, and this is exactly the pairing
     * that has to be done in one step.
     */
    /** Whether the world is drawn by the pack's own geometry programs, so the pass has to ask for it. */
    public static boolean drawsWorld() {
        return DRAWS_WORLD;
    }

    public Framebuffer worldFramebuffer(int width, int height, VulkanImage depth) {
        if (this.colortex[0] == null || width <= 0 || height <= 0
                || this.width != width || this.height != height) {
            return null;
        }

        VulkanImage[] colour = images(this.worldTargets);

        if (colour == null) {
            return null;
        }

        if (this.worldFramebuffer != null && this.worldDepth == depth
                && java.util.Arrays.equals(this.worldColour, colour)) {
            return this.worldFramebuffer;
        }

        this.worldColour = colour;

        this.worldDepth = depth;
        this.worldFramebuffer = Framebuffer.builder(colour, depth).build();
        return this.worldFramebuffer;
    }

    /** The images one target list names, in the order that pass's outputs are numbered. */
    private VulkanImage[] images(List<Integer> targets) {
        List<Integer> list = targets.isEmpty() ? List.of(0) : targets;
        VulkanImage[] images = new VulkanImage[list.size()];

        for (int i = 0; i < list.size(); i++) {
            int index = list.get(i);

            if (index < 0 || index >= this.colortex.length || this.colortex[index] == null) {
                return null;
            }

            images[i] = this.colortex[index];
        }

        return images;
    }

    /**
     * The attachments and render pass one target list draws into, built once per list.
     * <p>
     * The shader's outputs stay numbered from zero, which is how the pack wrote them; what changes
     * is which buffer sits at each attachment. {@code DRAWBUFFERS:73} then means "attachment zero is
     * colortex7, attachment one is colortex3" rather than a rewrite of the shader's locations, and a
     * pass asking for buffers past eight is a small framebuffer rather than a refused pass.
     */
    private Pass passFor(List<Integer> targets) {
        List<Integer> key = targets.isEmpty() ? List.of(0) : List.copyOf(targets);
        Pass built = this.passes.get(key);

        if (built != null) {
            return built;
        }

        // One image cannot be two attachments of one pass, which the driver refuses outright.
        if (new java.util.LinkedHashSet<>(key).size() != key.size()) {
            return null;
        }

        VulkanImage[] images = images(key);

        if (images == null) {
            return null;
        }

        Framebuffer framebuffer = Framebuffer.builder(images, null).build();
        RenderPass.Builder builder = RenderPass.builder(framebuffer);

        for (int i = 0; i < key.size(); i++) {
            // Load, not clear: a pass reads what the one before it wrote into the same buffers.
            builder.getColorAttachmentInfo(i)
                    .setOps(VK_ATTACHMENT_LOAD_OP_LOAD, VK_ATTACHMENT_STORE_OP_STORE)
                    .setFinalLayout(VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
        }

        Pass pass = new Pass(framebuffer, builder.build());
        this.passes.put(key, pass);
        return pass;
    }

    /**
     * Gives the pack's temporal buffer the frame it starts from.
     * <p>
     * This pack's {@code composite6} is a temporal anti-aliasing pass: it blends the frame it just
     * shaded with its own previous output, which it reads back as {@code colortex5} and which it
     * also writes. Iris never has to think about that, because the buffer is left undefined on load
     * and the pack's own reprojection test throws the first frame away. Here the buffer starts at
     * black and the reprojection is deliberately the current frame's, so the blend spends its whole
     * weight on a history that is nought and the picture crawls up from below a thousandth — the
     * tenth of a percent the probe reads in {@code colortex7}. Seeding the history with the colour
     * that would have been its first frame makes the blend start where it would have started.
     * <p>
     * Every frame, not once. The measurement is what says so: {@code composite1} hands the picture on
     * at 213, {@code composite5} still sees 214, and the pass's own output is 0.12. It keeps nine
     * tenths of a history that started below one, and its anti-ghosting clamp bounds every frame by
     * that history, so the buffer can never climb out of it. Seeding once cannot fix that either,
     * because the pass overwrites the seed in the same frame with its own blended output. Written
     * before every run of the pass, the history is the very frame being shaded, the blend becomes
     * the identity, and the stage passes the picture through. That is the honest arrangement for a
     * chain that has no previous frame's matrices to reproject with, and hands the pack the current
     * ones on purpose.
     */
    private void seedHistory(VitrailProgram program, VkCommandBuffer commandBuffer, MemoryStack stack) {
        if (!program.drawBuffers.contains(5) || !program.samplerNames.contains("colortex5")) {
            return;
        }

        // Into the copy the pass samples, never into the pass's own attachment: blitting into the
        // buffer a render pass is about to write leaves its layout bookkeeping out of step with what
        // the sampler sees, and every read of it comes back empty — which is what the last two
        // launches measured, on the one buffer nothing else in the chain writes by transfer.
        VulkanImage alias = alias(5);

        if (alias != null) {
            copy(commandBuffer, stack, this.colortex[3], alias);
        }
    }

    /**
     * Copies every buffer this pass writes into the copy it samples before the pass opens.
     * <p>
     * Done before {@code beginRenderPass} rather than inside it: the copy is a transfer, and a
     * transfer may not be recorded within a render pass at all.
     */
    private void refreshAliases(VitrailProgram program, VkCommandBuffer commandBuffer, MemoryStack stack) {
        for (int index : program.drawBuffers) {
            if (!program.samplerNames.contains("colortex" + index)) {
                continue;
            }

            if (index == 5) {
                // Seeded from the scene's colour in seedHistory, which runs just before this.
                continue;
            }

            VulkanImage alias = alias(index);

            if (alias != null) {
                copy(commandBuffer, stack, this.colortex[index], alias);
            }
        }
    }

    /** The copy a pass must read a buffer it writes from, or null when it writes nothing it reads. */
    private VulkanImage writtenBy(VitrailProgram program, String name) {
        Matcher own = COLORTEX.matcher(name);

        if (!own.matches()) {
            return null;
        }

        int index = Integer.parseInt(own.group(1));

        if (index < 0 || index >= this.aliases.length || !program.drawBuffers.contains(index)) {
            return null;
        }

        return this.aliases[index];
    }

    /** The copy for one pool buffer, created at the chain's size the first time a pass needs it. */
    private VulkanImage alias(int index) {
        VulkanImage existing = this.aliases[index];

        if (existing != null) {
            return existing;
        }

        VulkanImage image = VulkanImage.builder(this.width, this.height)
                .setFormat(VK_FORMAT_R16G16B16A16_SFLOAT)
                .setUsage(VK_IMAGE_USAGE_SAMPLED_BIT | VK_IMAGE_USAGE_TRANSFER_SRC_BIT
                        | VK_IMAGE_USAGE_TRANSFER_DST_BIT)
                .setLinearFiltering(true)
                .setClamp(true)
                .createVulkanImage();

        this.aliases[index] = image;
        return image;
    }

    /** One target list's attachments, with the render pass built for them. */
    private record Pass(Framebuffer framebuffer, RenderPass renderPass) {}

    /** The pool's colour buffers, in the order a fragment output location numbers them. */
    VulkanImage[] colourBuffers(int count) {
        int length = Math.min(Math.max(1, count), this.colortex.length);
        return java.util.Arrays.copyOf(this.colortex, length);
    }

    public boolean ready() {
        if (this.failed) {
            return false;
        }

        if (this.programs.isEmpty() && this.last == null) {
            return false;
        }

        for (VitrailProgram program : this.programs) {
            if (program.pipeline == null) {
                return false;
            }
        }

        return this.last == null || this.last.pipeline != null;
    }

    public boolean ensured(VkCommandBuffer commandBuffer, MemoryStack stack, int mainWidth, int mainHeight) {
        if (mainWidth <= 0 || mainHeight <= 0) {
            return false;
        }

        if (this.colortex[0] != null && this.width == mainWidth && this.height == mainHeight) {
            return true;
        }

        // A resize rebuilds the whole chain, and that is the step that has to be readable from the
        // log afterwards: a size the chain could not take is a black frame with no other symptom.
        Initializer.LOGGER.info("Vitrail: rebuilding the chain for {}x{} (was {}x{})",
                mainWidth, mainHeight, this.width, this.height);

        dispose();

        for (int i = 0; i < COLOR_TARGETS; i++) {
            VulkanImage image = VulkanImage.builder(mainWidth, mainHeight)
                    .setFormat(VK_FORMAT_R16G16B16A16_SFLOAT)
                    .setUsage(VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT | VK_IMAGE_USAGE_SAMPLED_BIT
                            | VK_IMAGE_USAGE_TRANSFER_DST_BIT)
                    .setLinearFiltering(true)
                    .setClamp(true)
                    .createVulkanImage();

            this.colortex[i] = image;
            image.transitionImageLayout(stack, commandBuffer, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL);

            // Every buffer the pack has not written yet reads as its own clear: black, except the
            // lightmap buffer, which a pack multiplies the fog by and white leaves it neutral.
            float level = i == 14 ? 1.0F : 0.0F;
            VkClearColorValue clear = VkClearColorValue.calloc(stack);
            clear.float32(0, level);
            clear.float32(1, level);
            clear.float32(2, level);
            clear.float32(3, level);

            VkImageSubresourceRange.Buffer range = VkImageSubresourceRange.calloc(1, stack);
            range.aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).baseMipLevel(0).levelCount(1)
                    .baseArrayLayer(0).layerCount(1);

            vkCmdClearColorImage(commandBuffer, image.getId(), VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
                    clear, range);
            image.transitionImageLayout(stack, commandBuffer, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
        }

        this.width = mainWidth;
        this.height = mainHeight;

        return true;
    }

    public boolean runFrame(VkCommandBuffer commandBuffer, MemoryStack stack, FrameGraph.ResourceResolver resolver,
                            java.util.function.Consumer<VulkanImage> present) {
        VulkanImage scene = resolver.resolve("scene");

        if (scene == null) {
            // Said once, because a frame graph that stops offering a scene is a change worth
            // reading and a line per frame is not.
            if (!this.reportedMissingScene) {
                this.reportedMissingScene = true;
                Initializer.LOGGER.warn("Vitrail: no scene to post-process, leaving the frame alone");
            }

            return false;
        }

        if (this.last == null) {
            // Nothing of the pack could be built for this dimension, so there is no picture to
            // present and the frame stays the engine's.
            if (!this.reportedPresent) {
                this.reportedPresent = true;
                Initializer.LOGGER.info("Vitrail: chain idle, the frame is the engine's");
            }

            return false;
        }

        VitrailGlobals.advance();
        fillScene(commandBuffer, stack, scene, resolver);

        for (VitrailProgram program : this.programs) {
            draw(program, commandBuffer, stack, resolver);
        }

        if (this.last == null) {
            return false;
        }

        draw(this.last, commandBuffer, stack, resolver);

        if (!this.reportedFirstFrame) {
            this.reportedFirstFrame = true;
            Initializer.LOGGER.info("Vitrail: drawing {} pass(es) at {}x{}, {} of {} colour buffer(s) attached",
                    this.programs.size() + 1, this.width, this.height, colorTargetLimit(), COLOR_TARGETS);
        }

        // A pass that declares no colour target of its own cannot have left a picture in the chain's
        // first buffer, and presenting that buffer anyway is a black frame whatever the scene holds:
        // it is what the last two black screens were. Such a pass is answered by handing the engine
        // back its own frame, which is the picture that was there before the pack ran.
        // A fragment program always writes location zero, whether or not its pack spelled out a
        // DRAWBUFFERS comment: Iris answers a program that declares neither directive with buffer
        // zero, and the translated outputs sit at the locations the pack numbered them at. An empty
        // list therefore means "the first target", not "no target" — reading it the other way was
        // what presented an unfilled buffer the two times the screen went black.
        boolean wroteTarget = true;
        VulkanImage presented = wroteTarget ? this.colortex[0] : scene;

        if (!this.reportedPresent) {
            this.reportedPresent = true;
            Initializer.LOGGER.info("Vitrail: presenting {}",
                    wroteTarget ? "the chain's first colour buffer" : "the engine's own frame");
        }

        if (this.presentedWidth != this.width || this.presentedHeight != this.height) {
            this.presentedWidth = this.width;
            this.presentedHeight = this.height;
            Initializer.LOGGER.info("Vitrail: presenting at {}x{}", this.width, this.height);
        }

        // The engine's present path does the transition, the pass, the viewport, the upscaler's
        // state and the channel and colour handling; the chain only hands it a finished picture.
        present.accept(presented);
        return true;
    }

    /**
     * Copies the engine's lit world into the buffers a pack reads as its scene.
     * <p>
     * A blit rather than a draw: the two images differ in format (the engine's target is eight bits
     * a channel, the pack's buffers are float), and {@code vkCmdBlitImage} converts between them
     * without a pipeline. Until the geometry family runs, this copy is what a pack's deferred and
     * composite passes light their picture from.
     */
    private void fillScene(VkCommandBuffer commandBuffer, MemoryStack stack, VulkanImage scene,
                           FrameGraph.ResourceResolver resolver) {
        if (DRAWS_WORLD) {
            // The world is drawn by the pack's own geometry programs, and it is drawn into these
            // very buffers: the scene the resolver answers with *is* colortex0, because the pass
            // that draws the world now uses the chain's framebuffer. Copying it here would blit an
            // image onto itself across two formats that are not compatible, which fails the whole
            // command buffer — nothing renders, and the screen keeps the last frame it had, which is
            // what a frozen picture over a menu looks like. The buffers are already filled.
            return;
        }

        // Copied, never sampled where it lies. The engine's picture is an attachment of a pass that
        // is still recorded around this call, and a sampled image that is also an attachment reads as
        // nothing: the pack's last pass then writes that nothing, which is a black world. A copy
        // crosses the two formats and leaves every pass reading a buffer of the pack's own.
        if (!this.reportedScene) {
            this.reportedScene = true;
            Initializer.LOGGER.info("Vitrail: the engine's scene is copied into the pack's buffers, {}x{} fmt=0x{}",
                    scene.width, scene.height, Integer.toHexString(scene.format));
        }

        for (int index : SCENE_BUFFERS) {
            copy(commandBuffer, stack, scene, this.colortex[index]);
        }

        copy(commandBuffer, stack, scene, this.colortex[ALBEDO_BUFFER]);

        VulkanImage normals = resolver.resolve("gnormal");

        // Guarded: this resolver answers a depth image when the engine has no normal target, and
        // a blit out of a depth format into a colour one is not a conversion the API allows — it
        // fails the whole command buffer, which is a black frame rather than a wrong normal.
        if (normals != null && !VulkanImage.isDepthFormat(normals.format)) {
            copy(commandBuffer, stack, normals, this.colortex[NORMAL_BUFFER]);
        }

        // Left where the rest of the frame expects to find it.
        scene.transitionImageLayout(stack, commandBuffer, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
    }

    /**
     * Copies one image into another, converting between their formats.
     * <p>
     * A blit rather than a draw: the engine's targets and the pack's buffers differ in format, and
     * {@code vkCmdBlitImage} converts between them without a pipeline of its own.
     */
    private static void copy(VkCommandBuffer commandBuffer, MemoryStack stack, VulkanImage source,
                             VulkanImage target) {
        if (source == null || target == null) {
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
        // Put the source back where the rest of the frame samples it from, whatever it was.
        source.transitionImageLayout(stack, commandBuffer, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
    }

    private void draw(VitrailProgram program, VkCommandBuffer commandBuffer, MemoryStack stack,
                      FrameGraph.ResourceResolver resolver) {
        Pass pass = passFor(program.drawBuffers);

        if (pass == null) {
            // A list naming one buffer twice, or one this chain does not hold, cannot be the
            // attachments of a single pass; the frame keeps what the previous pass left.
            return;
        }

        seedHistory(program, commandBuffer, stack);
        refreshAliases(program, commandBuffer, stack);

        Renderer.clearViewportScale();
        pass.framebuffer().beginRenderPass(commandBuffer, pass.renderPass(), stack);
        program.updateGlobals(this.width, this.height);
        bind(program, resolver);
        // Not bindShaderTextures: that one rewrites the slots from Minecraft's own shader texture
        // state, so the pack's samplers would end up holding the last texture the game bound — the
        // player's skin, drawn full screen. The images set by bind() above are what these slots must
        // keep, and binding the uniform buffers is what writes them into the descriptor set.
        DrawUtil.blit(program.pipeline);
        Renderer.getInstance().endRenderPass(commandBuffer);
    }

    private void bind(VitrailProgram program, FrameGraph.ResourceResolver resolver) {
        // Named once per program: a pack stage that samples the wrong image is not something to
        // reason about from the picture it paints, and the log is the only place to read the answer.
        boolean report = this.reportedSamplers.add(program.name);
        StringBuilder line = report ? new StringBuilder() : null;

        for (int i = 0; i < program.samplerNames.size(); i++) {
            String name = program.samplerNames.get(i);
            int slot = program.samplerSlots.get(i);
            VulkanImage image = imageFor(name, resolver);
            VulkanImage written = writtenBy(program, name);

            if (written != null) {
                // A buffer this pass writes is read through the copy taken before it opened.
                image = written;
            }

            if (image == null) {
                image = VTextureSelector.getWhiteTexture();
            }

            VTextureSelector.bindTexture(slot, image);

            if (report) {
                line.append(name).append("->slot ").append(slot)
                        .append(' ').append(image.width).append('x').append(image.height)
                        .append(" fmt=0x").append(Integer.toHexString(image.format))
                        .append(" id=").append(image.getId()).append("; ");
            }
        }

        if (report) {
            Initializer.LOGGER.info("Vitrail: '{}' writes {}, fed by {}",
                    program.name, program.drawBuffers, line);
        }
    }

    /**
     * The depth of the sun's map this frame, or null when the engine is not rendering one.
     * <p>
     * One cascade answers every {@code shadowtex} name: a pack is written against a single map and
     * reads its own distance from the settings it ships, while this engine splits the same range
     * into three. The farthest cascade is the one that reaches the distance a pack expects to see
     * shadows at, at the cost of the resolution the nearer ones would have given.
     */
    private static VulkanImage shadowDepth() {
        // The switch the world's own stage answers to, honoured here as well: an A/B of the shadow
        // term has to move the map for both of its readers, or it moves the one it changed and
        // measures the other. Answering nothing lets the stand-in table put the white texel here,
        // which is the same "no occluder anywhere" the other arm is built on.
        if ("white".equals(System.getProperty("vitrail.shadow.map"))) {
            return null;
        }

        ShadowMap map = ShadowMap.active();

        if (map == null || !map.isReady() || !Initializer.CONFIG.shadowsEnabled) {
            return null;
        }

        return map.getCascadeDepthImage(ShadowMap.PACK_CASCADE);
    }

    private VulkanImage imageFor(String name, FrameGraph.ResourceResolver resolver) {
        Matcher colortexName = COLORTEX.matcher(name);

        if (colortexName.matches()) {
            int index = Integer.parseInt(colortexName.group(1));

            if (index >= 0 && index < this.colortex.length) {
                return this.colortex[index];
            }
        }

        // The engine's sun map, before the stand-in table: those names were answered with a white
        // texel because the chain had no shadow map, and the engine has had one all along.
        if (SHADOW_NAMES.contains(name)) {
            VulkanImage sunMap = shadowDepth();

            if (sunMap != null) {
                return sunMap;
            }
        }

        String standIn = STAND_IN.get(name);

        if (standIn != null) {
            if (standIn.equals("white")) {
                return VTextureSelector.getWhiteTexture();
            }

            VulkanImage engine = resolver.resolve(standIn);

            if (engine != null) {
                return engine;
            }

            return VTextureSelector.getWhiteTexture();
        }

        return resolver.resolve(name);
    }

    void dispose() {
        // The world's framebuffer is built for the buffers this chain is about to free. Left alive,
        // it was handed back to the engine on the next frame, at the new size, still carrying the
        // freed attachments: the world was drawn into destroyed images and the screen went black
        // in full screen. Its images belong to the chain, so only the framebuffer objects go.
        if (this.worldFramebuffer != null) {
            this.worldFramebuffer.cleanUp(false);
            this.worldFramebuffer = null;
            this.worldColour = null;
            this.worldDepth = null;
        }

        for (Pass pass : this.passes.values()) {
            pass.renderPass().cleanUp();
            pass.framebuffer().cleanUp();
        }

        this.passes.clear();

        for (int i = 0; i < this.aliases.length; i++) {
            if (this.aliases[i] != null) {
                this.aliases[i].free();
                this.aliases[i] = null;
            }
        }

        for (int i = 0; i < this.colortex.length; i++) {
            if (this.colortex[i] != null) {
                this.colortex[i].free();
                this.colortex[i] = null;
            }
        }

        this.width = -1;
        this.height = -1;
    }
}
