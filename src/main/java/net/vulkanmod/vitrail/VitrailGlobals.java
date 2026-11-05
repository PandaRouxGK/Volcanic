package net.vulkanmod.vitrail;

import dev.vitrail.glsl.TranslatedUnit;
import net.minecraft.client.Camera;
import net.vulkanmod.Initializer;
import net.minecraft.client.Minecraft;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.vulkanmod.vulkan.VRenderSystem;
import net.vulkanmod.vulkan.pass.ShadowMap;
import org.joml.Matrix3f;
import org.joml.Matrix3fc;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Vector3f;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The values behind one program's {@code OfGlobals} block.
 * <p>
 * The translation moves every plain uniform a pack declares into one block and hands back the
 * members in the order it wrote them, because that order <em>is</em> the std140 layout. This class
 * walks that same list, works out where each member sits, and fills the ones the game can answer
 * for: the clocks, the camera, the gbuffer matrices and the conversion the translation reads for
 * depth. A member nothing fills stays zero, which is what the block held before any of this.
 * <p>
 * <strong>The matrices are published in the OpenGL form, not the form the engine draws with.</strong>
 * {@code VRenderSystem.applyProjectionMatrix} halves the z row into Vulkan's zero-to-one clip
 * volume before the game ever sees it, and a shader pack is written against minus one to one. The
 * halving is undone here, member by member, which is exact: {@code drawn = (legacy + w) / 2}, so
 * {@code legacy = 2 * drawn - w} on the same row. The depth the pack samples needs no conversion at
 * all, because a zero-to-one depth of the halved volume is the same number the OpenGL volume's
 * depth would have been — which is why {@code of_DepthConv} reads back with weight one and offset
 * nought.
 * <p>
 * One instance per program rather than one shared: two of a pack's programs need not declare the
 * same members, and std140 matches by offset, so a shared buffer would put one program's values
 * under another program's names.
 */
final class VitrailGlobals {

    /** The block name {@link dev.vitrail.glsl.Emitter} writes, and the only one filled here. */
    static final String BLOCK = "OfGlobals";

    /** The engine's over-declared block range, which is what the pipeline's descriptor promises. */
    private static final int FLOATS = 1024;

    /** The wrap OptiFine puts on {@code frameTimeCounter}, so a long session stays a float. */
    private static final float COUNTER_WRAP = 3600.0F;

    /** A stall of a second is not a frame; it is a load, and a full second of motion is not wanted. */
    private static final long MAX_FRAME_NANOS = 100_000_000L;

    /**
     * What turns the cascade's depth into the minus five to five a pack's shadow projection is
     * expected to produce: the pack divides by five with {@code z * 0.2}, so this multiplies by
     * five. The cascade's pair is handed out unhalved, in the minus one to one of the pack's own
     * convention and with no translation on that axis, which is why there is no offset to add. See
     * {@link #shadowProjection()}.
     */
    private static final float SHADOW_DEPTH_SCALE = 5.0F;
    private static final float SHADOW_DEPTH_OFFSET = 0.0F;

    private static final Pattern ARRAY = Pattern.compile("\\[(\\d+)]");

    /**
     * What a full screen stage is given as its fixed function transform.
     * <p>
     * The pack draws its quad with {@code gl_Position = gl_ModelViewProjectionMatrix * of_Vertex},
     * the corners already in clip space, and then remaps {@code gl_Position.xy} itself. OptiFine
     * hands that stage identity matrices for exactly this reason; handing it the camera's
     * projection instead projects a quad that is already in clip space through a perspective
     * transform, which lands it off screen and leaves the frame black whatever the pass sampled.
     */
    private static final Matrix4f IDENTITY = new Matrix4f();
    private static final Matrix3f IDENTITY3 = new Matrix3f();
    /**
     * The light map's unit of a pack's texture matrix: a level arrives as nought to two hundred and
     * forty and leaves as nought to nought and ninety-three per cent, which is where the sixteen
     * levels of the light map sit inside a texture's own coordinates.
     */
    private static final Matrix4f LIGHTMAP_SCALE = new Matrix4f().scale(1.0F / 256.0F, 1.0F / 256.0F, 1.0F);

    private static int frameCounter;
    private static float frameTime;
    private static float frameTimeCounter;
    private static long lastNanos;

    /** Whether this program draws the pack's quad, whose corners are already clip space. */
    private final boolean fullscreen;

    /**
     * Whether this program draws the casters into the sun's map rather than the camera's.
     * <p>
     * The distinction is the fixed function transform and nothing else. A pack's shadow vertex
     * stage is written as {@code shadowModelViewInverse * shadowProjectionInverse * ftransform()}
     * and then projects that position again with the sun's pair, which unwinds to the fixed function
     * transform itself: hand the stage the camera's matrix there and the round trip no longer
     * cancels, so every caster lands where the camera would have put it in the shadow image instead
     * of where the sun would.
     */
    private boolean sun;
    private final List<Slot> slots = new ArrayList<>(64);

    /** The members this frame found nothing to write into, reported once per block. */
    private final java.util.LinkedHashSet<String> unanswered = new java.util.LinkedHashSet<>();
    private boolean reportedUnanswered;
    private final ByteBuffer buffer = MemoryUtil.memAlloc(FLOATS * 4);
    private final FloatBuffer values = this.buffer.asFloatBuffer();

    /** One member of the block: where it starts, how far apart its elements are, how many. */
    private record Slot(String name, int offset, int stride, int count) {
    }

    private VitrailGlobals(boolean fullscreen) {
        this.fullscreen = fullscreen;
    }

    /**
     * Advances the clocks once for the frame. Every program of the frame reads the same numbers, so
     * two passes of one frame cannot be handed two different times.
     */
    static void advance() {
        long now = System.nanoTime();

        if (lastNanos != 0L) {
            frameTime = Math.min(now - lastNanos, MAX_FRAME_NANOS) / 1.0e9F;
        }

        lastNanos = now;
        frameCounter++;
        frameTimeCounter += frameTime;

        if (frameTimeCounter >= COUNTER_WRAP) {
            frameTimeCounter -= COUNTER_WRAP;
        }
    }

    /** Lays out the block's members as std140, in the order the translation wrote them. */
    static VitrailGlobals of(List<TranslatedUnit.Uniform> members) {
        return of(members, true);
    }

    /**
     * The block of a stage that draws into the sun's map: its transform is the sun's pair, while the
     * camera's own matrices stay published under the gbuffer names, where a fragment stage reads
     * them for the waving and the reflections.
     */
    static VitrailGlobals ofSunPass(List<TranslatedUnit.Uniform> members) {
        VitrailGlobals globals = of(members, false);
        globals.sun = true;
        return globals;
    }

    /**
     * @param fullscreen whether the program draws the pack's full screen quad. A geometry stage
     *                   draws the world instead, and reads the camera's transform out of the fixed
     *                   function names rather than the identity a quad is placed with.
     */
    static VitrailGlobals of(List<TranslatedUnit.Uniform> members, boolean fullscreen) {
        VitrailGlobals globals = new VitrailGlobals(fullscreen);
        int offset = 0;

        for (TranslatedUnit.Uniform member : members) {
            int count = 1;
            Matcher array = ARRAY.matcher(member.declaration());

            if (array.find()) {
                count = Integer.parseInt(array.group(1));
            }

            int[] shape = shapeOf(member.type());
            int stride = shape[0];
            int alignment = shape[1];

            // std140 rounds an array's element stride up to a vec4, and a lone member keeps its own
            // size: a vec3 followed by a float starts that float at twelve bytes.
            if (count > 1) {
                stride = toVec4(stride);
            }

            offset = align(offset, alignment);
            globals.slots.add(new Slot(member.name(), offset, stride, count));
            offset += stride * count;
        }

        return globals;
    }

    /**
     * The names this block publishes for its program, in the order the pack declares them.
     * <p>
     * A value that reaches the block still has to be a name the stage declared, and a stage whose
     * block is missing one is a stage a value can be written for and read by nobody. This is the
     * list a probe reads when a shadow map is bound and compared and the picture does not move.
     */
    /**
     * The names the block carries that this engine left at nought, once, for a capture to read.
     * A pack reads such a member without knowing it was never written, and one of them is enough to
     * remove a whole term from the frame, so the list is the first thing to look at when a pack's
     * lighting is flatter than its own settings say it should be.
     */
    void reportUnanswered() {
        this.reportedUnanswered = true;

        if (!this.unanswered.isEmpty()) {
            Initializer.LOGGER.info("Vitrail: pack block leaves {} name(s) at nought: {}",
                    this.unanswered.size(), String.join(", ", this.unanswered));
        }
    }

    String names() {
        StringBuilder names = new StringBuilder();

        for (Slot slot : this.slots) {
            if (names.length() > 0) {
                names.append(' ');
            }

            names.append(slot.name());
        }

        return names.toString();
    }

    long address() {
        return MemoryUtil.memAddress(this.buffer);
    }

    /**
     * Releases the block's memory.
     * <p>
     * A chain that is rebuilt at a new size builds one of these per program, and a block the
     * pipeline only wraps is nobody else's to free.
     */
    void free() {
        MemoryUtil.memFree(this.buffer);
    }

    int size() {
        return FLOATS * 4;
    }

    /** Writes this frame's values into the buffer the pipeline copies from at every draw. */
    /**
     * The twilight band over which a pack fades the sun's whole light away, as Iris answers it: one
     * while the sun stands clear of the horizon and nought once it is well under.
     */
    private static float shadowFade(long dayTime, float partial) {
        double warped = ((dayTime + partial) / 24000.0 - 0.25) % 1.0;

        if (warped < 0.0) {
            warped += 1.0;
        }

        double eased = 0.5 - StrictMath.cos(warped * Math.PI) / 2.0;
        float skyAngle = (float) ((warped * 2.0 + eased) / 3.0);
        float sunAngle = skyAngle < 0.75F ? skyAngle + 0.25F : skyAngle - 0.75F;
        float fade = 1.0F - (Math.abs(Math.abs(sunAngle - 0.5F) - 0.25F) - 0.23F) * 100.0F;

        return Math.max(0.0F, Math.min(1.0F, fade));
    }

    void write(int width, int height) {
        Minecraft client = Minecraft.getInstance();
        Level level = client.level;

        if (level == null) {
            return;
        }

        Camera camera = client.gameRenderer.getMainCamera();
        Vec3 position = camera.getPosition();

        Matrix4fc projection = legacyProjection();
        Matrix4fc modelView = modelView();
        Matrix4fc inverseProjection = new Matrix4f(projection).invert();
        Matrix4fc inverseModelView = new Matrix4f(modelView).invert();

        // A perspective's clip planes, read back off the matrix the pack is given rather than off a
        // setting, so a mod that moves the near plane is answered for as well.
        float near = projection.m23() / (projection.m22() - 1.0F);
        float far = projection.m23() / (projection.m22() + 1.0F);

        float partial = client.getTimer().getGameTimeDeltaPartialTick(false);
        long dayTime = level.getDayTime();
        // The sun's direction, taken from the reading the engine's own shadow pass draws the map
        // with rather than from a formula of this class's own.
        float sunAngle = VRenderSystem.smoothTimeOfDay(client) * (float) (Math.PI * 2.0);
        float sunX = -(float) Math.sin(sunAngle);
        float sunHeight = (float) Math.cos(sunAngle);
        float sunElevation = sunHeight * VRenderSystem.SUN_TILT_COS;

        // Into the view's own space, which is where the pack reads a sun direction from: it maps
        // the world into that space with `gbufferModelView` and takes the direction back out with
        // the inverse of the same matrix. Published in the world's space instead, a pack is handed
        // the sun a camera facing due north would see whatever way this one is facing, which on the
        // reference pose is twelve degrees of yaw and on any other is whatever the player turned.
        Vector3f sun = new Vector3f(sunX, sunElevation, sunHeight * VRenderSystem.SUN_TILT_SIN)
                .mul(new Matrix3f(modelView));

        for (Slot slot : this.slots) {
            switch (slot.name()) {
                case "frameCounter" -> putInt(slot, frameCounter);
                case "frameTime" -> put(slot, frameTime);
                case "frameTimeCounter" -> put(slot, frameTimeCounter);
                case "worldTime" -> putInt(slot, (int) (dayTime % 24000L));
                // The day as a fraction with the sun's own phase: nought at sunrise, a quarter at
                // noon, a half at sunset, three quarters at midnight. A pack does not read it as an
                // angle but as the time its own celestial path is built from — BSL takes
                // `fract(timeAngle - 0.25)` and runs it through the sine and cosine of the sun's
                // path — so leaving it at the block's nought hands that pack a sun on the eastern
                // horizon at every hour of the day, whose light no surface faces.
                case "timeAngle" -> put(slot, (float) ((dayTime % 24000L + partial) / 24000.0));
                // How much of the sun's own light reaches the frame at this hour. It is not the
                // shadow map's fade, which a pack computes per fragment from the sampled position,
                // but the one that scales the whole sun term: BSL multiplies it into
                // `shadowMult` and mixes ambient with sunlight by the result, so a nought here
                // leaves a world lit entirely by ambient, with the map sampled and its answer
                // multiplied out again, which is a picture no comparison of frames can attribute.
                // Left at the block's nought this was the reason a pack's shadows never appeared.
                case "shadowFade" -> put(slot, shadowFade(dayTime, partial));
                case "worldDay" -> putInt(slot, (int) (dayTime / 24000L));
                case "moonPhase" -> putInt(slot, (int) ((dayTime / 24000L) % 8L));

                case "cameraPosition" -> put(slot, (float) position.x, (float) position.y,
                        (float) position.z);
                case "previousCameraPosition" -> put(slot, (float) position.x, (float) position.y,
                        (float) position.z);
                case "eyeAltitude" -> put(slot, (float) position.y);
                case "near" -> put(slot, near);
                case "far" -> put(slot, far);
                case "aspectRatio" -> put(slot, height == 0 ? 1.0F : (float) width / height);
                // The pair a pack divides a texel offset by, and the size it compares a screen space
                // vector against. Left at zero, a pack that computes `offset / viewWidth` produces an
                // infinite step and samples the same texel for every pixel of its blur.
                case "viewWidth" -> put(slot, width);
                case "viewHeight" -> put(slot, height);
                case "texelSize" -> put(slot, 1.0F / Math.max(width, 1),
                        1.0F / Math.max(height, 1));

                // The names a *geometry* stage places its vertices with, which are the pack's own
                // spelling of the two matrices and not the gbuffer pair. Left at nought, the
                // texture matrix alone is enough to ruin a frame: the terrain prologue multiplies
                // its uv by this entry before sampling, so every texel of every block came out of
                // the atlas at nought, and a world of grass and leaves was drawn uniformly white
                // from the one colour that sits at the atlas' origin.
                case "of_ModelViewMatrix" -> put(slot, this.fullscreen ? IDENTITY
                        : this.sun ? shadowView() : modelView);
                case "of_ProjectionMatrix" -> put(slot, this.fullscreen ? IDENTITY
                        : this.sun ? shadowProjection() : projection);
                // The inverse transpose of the view's rotation, which is how a normal survives a
                // transform that has a scale in it. A terrain mesh of this engine carries no normal
                // element at all and the prologue answers with its own, so this is for the packs
                // that read it back rather than for the geometry drawn today.
                case "of_NormalMatrix" -> put(slot, this.fullscreen ? IDENTITY3
                        : this.sun ? normalMatrix(shadowView()) : normalMatrix(modelView));
                // Unit nought maps a block's uv, which every family already stores scaled to the
                // atlas, so it is the identity there. Unit one maps the light map, and that pair is
                // handed over raw: a level runs from nought to two hundred and forty, one byte a
                // side of the position's last word. The scale belongs here and not in the vertex
                // prologue, because the pack applies it itself and because it brings the half texel
                // that centres the sample on its level with it: a pack maps this pair with
                // {@code (lm - 0.03125) * 1.06667}, which lands a full level on one only when the
                // raw two hundred and forty has already become nought and ninety-three per cent.
                // Answering the identity instead left every fragment on the same saturated pair, so
                // the world was lit by ambient alone and the sun's own term had nothing to reach.
                case "of_TextureMatrix" -> putTextureMatrices(slot);

                case "gbufferModelView" -> put(slot, modelView);
                case "gbufferModelViewInverse" -> put(slot, inverseModelView);
                case "gbufferProjection" -> put(slot, projection);
                case "gbufferProjectionInverse" -> put(slot, inverseProjection);
                // There is no previous frame captured yet, so a reprojection has nothing older to
                // read. Handing it this frame's matrices makes it a no-op rather than a tear.
                case "gbufferPreviousModelView" -> put(slot, modelView);
                case "gbufferPreviousProjection" -> put(slot, projection);
                // The fixed function transform of a full screen stage is the identity: its corners
                // are already the clip coordinates the pack samples with. The camera's matrices are
                // published separately, under the gbuffer names below, where the pack expects them.
                // A geometry stage draws the world, so the fixed function transform is the camera's:
                // view and projection in one matrix, in the pack's minus one to one clip volume.
                // Built in the branch rather than once per frame, so a full screen stage's frame is
                // untouched by a matrix it will never read.
                // A stage drawn into the sun's map is placed by the sun: the pack's own shadow
                // vertex stage unwinds this matrix with the sun's inverse pair and immediately
                // projects the result with the sun's pair again, so this is the transform that
                // cancels and leaves the casters where the sun sees them. The camera's matrices are
                // still published under the gbuffer names above.
                case "of_ModelViewProjectionMatrix" -> put(slot, this.fullscreen ? IDENTITY
                        : this.sun ? new Matrix4f(shadowProjection()).mul(shadowView())
                        : new Matrix4f(projection).mul(modelView));
                case "modelViewMatrix" -> put(slot, this.fullscreen ? IDENTITY
                        : this.sun ? shadowView() : modelView);
                case "modelViewMatrixInverse" -> put(slot, this.fullscreen ? IDENTITY
                        : this.sun ? new Matrix4f(shadowView()).invert() : inverseModelView);
                case "projectionMatrix" -> put(slot, this.fullscreen ? IDENTITY
                        : this.sun ? shadowProjection() : projection);
                case "projectionMatrixInverse" -> put(slot, this.fullscreen ? IDENTITY
                        : this.sun ? new Matrix4f(shadowProjection()).invert() : inverseProjection);

                // The sun's own map: a pack projects the camera-relative world position through these
                // two and compares the result with the depth the engine rendered from the sun. Both
                // are published in the pack's clip volume, which is the untransformed pair the engine
                // halves only for its own draws — and the geometry the engine renders into that map is
                // camera relative, which is the space a pack's shadow position is computed in.
                case "shadowModelView" -> put(slot, shadowView());
                case "shadowModelViewInverse" -> put(slot, new Matrix4f(shadowView()).invert());
                case "shadowProjection" -> put(slot, shadowProjection());
                case "shadowProjectionInverse" -> put(slot, new Matrix4f(shadowProjection()).invert());

                case "sunPosition" -> put(slot, sun.x * 100.0F, sun.y * 100.0F, sun.z * 100.0F);
                case "shadowLightPosition" -> put(slot, sun.x * 100.0F, sun.y * 100.0F, sun.z * 100.0F);
                case "shadowLightPositionVector" -> put(slot, sun.x, sun.y, sun.z);
                // The reach of the cascade a pack is handed, so a pack that reads its own distance
                // from this rather than from its settings fades shadows out where they end.
                case "shadowDistance" -> put(slot, ShadowMap.packRadius());
                case "sunVec" -> put(slot, sun.x, sun.y, sun.z);
                case "sunElevation" -> put(slot, sunElevation);
                case "rainStrength" -> put(slot, level.getRainLevel(partial));
                case "isEyeInWater" -> putInt(slot, eyeInWater(client));

                // Iris's gameplay uniforms, which a pack reads to know whether to paint the death
                // and damage overlays. Left at nought they claim the player has no health at all:
                // Bliss reads exactly that and covers the frame in its dead vignette, which is a
                // red dark picture no matter what the scene holds.
                case "currentPlayerHealth", "maxPlayerHealth" -> put(slot, 20.0F);
                case "CriticalDamageTaken" -> put(slot, 0.0F);
                case "hideGUI" -> putInt(slot, 0);

                // The pair the translation reads to write and to read a depth: the clip volume this
                // engine rasterises in is zero to one, the pack's is minus one to one, and the
                // sampled depth already matches, so only the write half moves.
                case "of_DepthConv" -> put(slot, 0.5F, 0.5F, 1.0F, 0.0F);

                // Every other name is a member this engine has nothing to say about, and it is
                // reported rather than left to be discovered from a picture: a single one of these
                // at nought is enough to remove a term from the frame.
                default -> this.unanswered.add(slot.name());
            }
        }

        if (!this.reportedUnanswered && VitrailDebug.ENABLED) {
            reportUnanswered();
        }
    }

    /** What the pack calls a water eye, a lava eye, or a snow eye, in Iris's numbering. */
    private static int eyeInWater(Minecraft client) {
        if (client.player == null) {
            return 0;
        }

        if (client.player.isEyeInFluid(FluidTags.WATER)) {
            return 1;
        }

        if (client.player.isEyeInFluid(FluidTags.LAVA)) {
            return 2;
        }

        // Powder snow is not a fluid in this version, so the eye test is the vector the entity
        // carries rather than a tag: it stands for the same third value Iris gives the pack.
        return client.player.isInPowderSnow ? 3 : 0;
    }

    /**
     * The sun's pair as the pack is given it, next to the one the engine draws this cascade with.
     * <p>
     * A stage placed here that lands nowhere is answered by these two lines: the pack's matrix is
     * the engine's with the depth row stretched and moved, so every other entry has to be equal, and
     * the translation is where a cascade centred wrongly would show.
     */
    static String sunReport() {
        Matrix4f pack = new Matrix4f(shadowProjection()).mul(shadowView());
        Matrix4f engine = new Matrix4f(VRenderSystem.getProjectionMatrix().buffer.asFloatBuffer())
                .mul(new Matrix4f(VRenderSystem.getModelViewMatrix().buffer.asFloatBuffer()));

        return String.format("pack [%s] engine [%s]", row(pack, 0) + row(pack, 1) + row(pack, 2)
                + row(pack, 3), row(engine, 0) + row(engine, 1) + row(engine, 2) + row(engine, 3));
    }

    /**
     * What this block holds for the sun's matrix, as a figure to put the shader's own reading of it
     * against: the member count says whether the block was laid out at all, and the two entries are
     * the diagonal and the translation a stage samples the map through.
     */
    String mvpReport() {
        for (Slot slot : this.slots) {
            if (slot.name().equals("of_ModelViewProjectionMatrix")) {
                return String.format("members=%d offset=%d m00=%.4f m31=%.4f",
                        this.slots.size(), slot.offset(), this.values.get(slot.offset()),
                        this.values.get(slot.offset() + 13));
            }
        }

        return "members=" + this.slots.size() + " and no matrix among them";
    }

    static String row(Matrix4f matrix, int row) {
        return String.format("%.4f %.4f %.4f %.4f / ",
                row == 0 ? matrix.m00() : row == 1 ? matrix.m10() : row == 2 ? matrix.m20() : matrix.m30(),
                row == 0 ? matrix.m01() : row == 1 ? matrix.m11() : row == 2 ? matrix.m21() : matrix.m31(),
                row == 0 ? matrix.m02() : row == 1 ? matrix.m12() : row == 2 ? matrix.m22() : matrix.m32(),
                row == 0 ? matrix.m03() : row == 1 ? matrix.m13() : row == 2 ? matrix.m23() : matrix.m33());
    }

    /**
     * The sun's view for the cascade the pack samples, described in the camera's frame.
     * <p>
     * The engine's sun view looks from the sun at the origin, and the vertices its chunk mesh holds
     * are already relative to the camera, so that pair is the one this mesh is in: the camera sits
     * at the origin of it and nothing else has to be taken out. Subtracting the camera here as well
     * put every caster a camera's length to one side of the cascade, which is what an empty map
     * looks like — the pack's shadow stage multiplies these vertices by this pair directly.
     * <p>
     * The camera's frame is also the one a pack samples in: it reads a position out of
     * {@code gbufferModelViewInverse}, which is camera relative, and projects it through this pair.
     * Draw and sample therefore speak one space, which is the condition for the two to agree.
     */
    /**
     * The sun's pair as this frame publishes it, so a probe can place a fragment itself.
     * <p>
     * A stage that returns before it samples leaves no number behind to say why: the map is full,
     * the sampler compares, and the frame does not move. The pair is what such a stage decides with
     * first, and this is the only place it can be read from outside the shader.
     */
    static String shadowReport() {
        ShadowMap map = ShadowMap.active();

        if (map == null || !map.isReady()) {
            return "no active cascade";
        }

        Matrix4f view = shadowView();
        Matrix4f projection = shadowProjection();

        return "shadowModelView " + row(view, 0) + row(view, 1) + row(view, 2) + row(view, 3)
                + "| shadowProjection " + row(projection, 0) + row(projection, 1) + row(projection, 2)
                + row(projection, 3);
    }

    private static Matrix4f shadowView() {
        ShadowMap map = ShadowMap.active();

        if (map == null || !map.isReady()) {
            return new Matrix4f();
        }

        return new Matrix4f(map.sunView(ShadowMap.PACK_CASCADE));
    }

    /**
     * The sun's orthographic projection, with the depth axis in the range a pack reads its shadow
     * map in.
     * <p>
     * Packs project a position through this pair and compare the z it comes out with against the
     * depth the sun's map holds, and both sides have to be in one range for that comparison to mean
     * anything. The pack's range is minus five to five: its shadow vertex stage maps what this
     * returns with {@code z * 0.2}, and the translation then writes that through
     * {@code 0.5 * z + 0.5 * w} into the zero-to-one volume this engine rasterises in. The
     * cascade's projection is the engine's, zero to one, so the z row is stretched by ten and moved
     * down by five, and {@code (10 * drawn - 5) * 0.2 * 0.5 + 0.5} is exactly the drawn depth again.
     * <p>
     * Only the z row moves, because the pack reads the other two through an orthographic diagonal.
     * The inverse is taken from this matrix rather than from the engine's, so a pack that walks
     * back out of the map lands where the map was drawn.
     * <p>
     * The distortion — the {@code 1 / distortFactor} a pack spreads its shadow texels with — is
     * deliberately not in here. A pack applies it itself, on both sides: its shadow stage divides
     * the clip position by that factor before writing, and its lighting divides the sampled position
     * by the same factor before reading, each computed from this same undistorted pair. Putting the
     * distortion into the published pair as well would apply it twice, and the texel a composite
     * asked for would not be the texel the caster was drawn in. What the map holds is therefore a
     * distorted image of straight clip coordinates, which is exactly what a pack's own sampling
     * expects to find there.
     */
    private static Matrix4f shadowProjection() {
        ShadowMap map = ShadowMap.active();

        if (map == null || !map.isReady()) {
            return new Matrix4f();
        }

        Matrix4f matrix = new Matrix4f(map.sunProjection(ShadowMap.PACK_CASCADE));
        // The row that produces a clip z, which JOML names by its second index: m02, m12, m22 and
        // the fourth column's m32. Stretched here into the pack's range instead of halved into the
        // engine's own, which is the only difference from the pair the engine draws with.
        float m02 = matrix.m02() * SHADOW_DEPTH_SCALE;
        float m12 = matrix.m12() * SHADOW_DEPTH_SCALE;
        float m22 = matrix.m22() * SHADOW_DEPTH_SCALE;
        float m32 = matrix.m32() * SHADOW_DEPTH_SCALE + SHADOW_DEPTH_OFFSET;

        matrix.m02(m02).m12(m12).m22(m22).m32(m32);
        return matrix;
    }

    /** The normal matrix of a view: the inverse transpose of its rotation. */
    private static Matrix3fc normalMatrix(Matrix4fc modelView) {
        return new Matrix3f().set(modelView).invert().transpose();
    }

    private static Matrix4f modelView() {
        Matrix4f matrix = new Matrix4f();
        matrix.set(VRenderSystem.getModelViewMatrix().buffer.asFloatBuffer());
        return matrix;
    }

    /**
     * The projection as the pack's OpenGL volume has it, undoing the engine's halving of the z row
     * into zero to one: {@code drawn = (legacy + w) / 2} per column, so
     * {@code legacy = 2 * drawn - w} with the w row left as it is.
     */
    private static Matrix4f legacyProjection() {
        Matrix4f matrix = new Matrix4f();
        matrix.set(VRenderSystem.getProjectionMatrix().buffer.asFloatBuffer());

        float m02 = 2.0F * matrix.m02() - matrix.m03();
        float m12 = 2.0F * matrix.m12() - matrix.m13();
        float m22 = 2.0F * matrix.m22() - matrix.m23();
        float m32 = 2.0F * matrix.m32() - matrix.m33();

        matrix.m02(m02).m12(m12).m22(m22).m32(m32);
        return matrix;
    }

    /** Size in floats, then alignment in floats, for every type a pack's block can hold. */
    private static int[] shapeOf(String type) {
        return switch (type) {
            case "float", "int", "uint", "bool" -> new int[] {1, 1};
            case "vec2", "ivec2", "uvec2" -> new int[] {2, 2};
            case "vec3", "ivec3", "uvec3" -> new int[] {3, 4};
            case "vec4", "ivec4", "uvec4" -> new int[] {4, 4};
            case "mat2" -> new int[] {4, 2};
            case "mat3" -> new int[] {12, 4};
            case "mat4" -> new int[] {16, 4};
            default -> new int[] {1, 1};
        };
    }

    private static int toVec4(int size) {
        return (size + 3) / 4 * 4;
    }

    private static int align(int offset, int alignment) {
        return (offset + alignment - 1) / alignment * alignment;
    }

    private void put(Slot slot, float x) {
        this.values.put(slot.offset(), x);
    }

    private void put(Slot slot, float x, float y) {
        this.values.put(slot.offset(), x);
        this.values.put(slot.offset() + 1, y);
    }

    private void put(Slot slot, float x, float y, float z) {
        this.values.put(slot.offset(), x);
        this.values.put(slot.offset() + 1, y);
        this.values.put(slot.offset() + 2, z);
    }

    private void put(Slot slot, float x, float y, float z, float w) {
        this.values.put(slot.offset(), x);
        this.values.put(slot.offset() + 1, y);
        this.values.put(slot.offset() + 2, z);
        this.values.put(slot.offset() + 3, w);
    }

    private void put(Slot slot, Matrix4fc matrix) {
        // JOML writes a matrix at the buffer's position, so the position is moved and left alone
        // afterwards: every other write here is absolute.
        this.values.position(slot.offset());
        matrix.get(this.values);
    }

    private void put(Slot slot, Matrix3fc matrix) {
        this.values.position(slot.offset());
        matrix.get(this.values);
    }

    /** One identity per element of an array of matrices, at the stride the layout gave it. */
    /**
     * The eight units of a pack's texture matrix: identity for the atlas, the light map's raw pair
     * scaled to its own levels for unit one, and identity for the six the frame leaves alone.
     */
    private void putTextureMatrices(Slot slot) {
        for (int i = 0; i < slot.count(); i++) {
            this.values.position(slot.offset() + i * slot.stride());

            if (i == 1) {
                LIGHTMAP_SCALE.get(this.values);
            } else {
                IDENTITY.get(this.values);
            }
        }
    }

    private void putIdentities(Slot slot) {
        for (int i = 0; i < slot.count(); i++) {
            this.values.position(slot.offset() + i * slot.stride());
            IDENTITY.get(this.values);
        }
    }

    private void putInt(Slot slot, int value) {
        this.values.put(slot.offset(), (float) value);
    }
}
