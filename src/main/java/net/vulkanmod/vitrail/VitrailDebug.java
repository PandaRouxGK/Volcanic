package net.vulkanmod.vitrail;

import net.vulkanmod.Initializer;

/**
 * The diagnostic switches of the port harness, read once from system properties.
 * <p>
 * They exist so that a capture session can ask for more than a normal session without rebuilding
 * the mod, and so that none of that work is paid by a player: every flag costs one property read at
 * class initialisation and a branch that is constant-folded afterwards. Nothing here prints or
 * reads back a frame unless a flag was given on the command line.
 * <p>
 * {@code -Dvitrail.debug=true} is the master switch. On its own it logs the active flags once, when
 * a pack chain is built, which is what makes a capture reproducible: the log says how the run was
 * configured.
 * <p>
 * {@code -Dvitrail.debug.programs=true} additionally writes every translated program beside the
 * log, under {@code logs/vitrail/<program>.vsh|.fsh}. A picture that is wrong in colour, brightness
 * or orientation is read in the GLSL that produced it, not in the frame, and the translation is the
 * one thing a pack cannot show you.
 * <p>
 * {@code -Dvitrail.debug.probes=true} is reserved for the per-buffer readbacks the chain grows back
 * in P3. It is recorded and logged here so a capture can name it, and it does nothing else yet.
 */
public final class VitrailDebug {

    /** Master switch: {@code -Dvitrail.debug=true}. */
    public static final boolean ENABLED = Boolean.getBoolean("vitrail.debug");

    /** Writes the translated stages beside the log: {@code -Dvitrail.debug.programs=true}. */
    public static final boolean PROGRAMS = ENABLED && Boolean.getBoolean("vitrail.debug.programs");

    /** Reserved for the chain's readback probes, which P3 brings back behind this flag. */
    public static final boolean PROBES = ENABLED && Boolean.getBoolean("vitrail.debug.probes");

    /**
     * Runs the facade smoke test once at frame start: {@code -Dvitrail.debug.smoke=true}.
     * <p>
     * It draws one full screen triangle through {@code net.vulkanmod.vitrail.gpu}, copies a texel
     * back and compares it with the value the shader must have produced. This is the only check that
     * the facade really reaches the GPU, and it costs one 64 by 64 pass on the first frame.
     */
    public static final boolean SMOKE = ENABLED && Boolean.getBoolean("vitrail.debug.smoke");

    /**
     * Builds the pack's own chain through the facade and puts the result in the log:
     * {@code -Dvitrail.debug.chain=true}.
     * <p>
     * The chain the screen uses is still the hand-wired one; this builds the chain the pack
     * describes, allocates its targets and compiles every program of it, then says what did not
     * build and why. It draws nothing, so it costs one pack opening and one shader compile per
     * program at load.
     */
    public static final boolean CHAIN = ENABLED && Boolean.getBoolean("vitrail.debug.chain");

    /**
     * Draws the pack's own chain instead of the hand-wired one: {@code -Dvitrail.debug.draw=true}.
     * <p>
     * The chain runs in the pack's order, into the pack's buffers, with the pack's ping-pong, and
     * the buffer the plan says reaches the screen is what the engine presents. Its uniform blocks
     * are still zeroed and the names this engine has no image behind are still served white, so a
     * picture that is wrong is expected: what this flag answers is whether the passes run, in the
     * right order, over the right buffers, at all.
     */
    public static final boolean DRAW = ENABLED && Boolean.getBoolean("vitrail.debug.draw");

    /**
     * Answers the terrain stage's first target with the three factors that decide whether the sun
     * reaches a fragment: {@code -Dvitrail.debug.shadowprobe=true}.
     * <p>
     * The light a pack adds for the sun is {@code mix(ambient, sun, fullShadow * shadowMult)}, and
     * {@code fullShadow} is the shadow map's answer multiplied by {@code NoL}, itself gated on the
     * light map's sky level. Any one of the three at nought leaves the frame lit by ambient alone,
     * and no picture distinguishes that from a map that was never sampled. So the stage is made to
     * write {@code (shadow, fullShadow, NoL)} into its first target instead, where the chain probe
     * reads it back as three channels: the answer is then a number per fragment, not a guess. The
     * first two are the map's own answer and the sun term it then scales, so a comparison that moves
     * the first and not the second says the read is not reaching the light.
     */
    public static final boolean SHADOW_PROBE = ENABLED && Boolean.getBoolean("vitrail.debug.shadowprobe");

    /**
     * Turns the sun a stage lights by into a fixed direction, the arm an A/B of the sun term needs.
     * <p>
     * The pack it is written for takes its sun as a vertex attribute, {@code in vec3 sunVec}, which
     * a chunk mesh of this engine carries no element for: the attribute reads nought, so the light
     * vector is nought, so {@code NoL} is nought and the sun's term is multiplied by it. A frame
     * that does not move when the direction is pinned down is a frame whose sun never arrives.
     */
    public static final boolean SUN_FIXED = ENABLED && Boolean.getBoolean("vitrail.debug.sunvec");

    /**
     * Answers a terrain stage's first target with its lighting instead of its albedo:
     * {@code -Dvitrail.debug.lightprobe=true}.
     * <p>
     * The three channels are the lighting the fragment really receives, the sun's share of it, and
     * the shadow factor that gates that share. A frame's mean cannot answer the question this does,
     * because the sky fills most of the buffer and reads no shadow map: at the reference pose the
     * pack's first target moves by a few tenths of a percent between a real map and one forced fully
     * shadowed, while the terrain's lighting term measured here moves by a third. Read it with
     * {@code chain.limit=0}, so the buffer still holds what the world stage wrote.
     */
    public static final boolean LIGHT_PROBE = ENABLED && Boolean.getBoolean("vitrail.debug.lightprobe");

    /**
     * Answers a chain stage's target with the depth it reads instead of what it computed:
     * {@code -Dvitrail.debug.depthprobe=true}.
     * <p>
     * BSL's deferred stage samples {@code depthtex0} once, calls that {@code z}, and linearises it
     * through {@code gbufferProjectionInverse} for its ambient occlusion. Read back at the reference
     * pose, that stage's own target holds nought in every texel, the sky included, where the pack
     * starts its occlusion term at one and never touches it — a value the pack's code cannot produce
     * unless the depth it was handed is nought. This probe replaces the answer with {@code z} itself,
     * so one capture says whether the buffer is wrong or the sample is.
     */
    public static final boolean DEPTH_PROBE = ENABLED && Boolean.getBoolean("vitrail.debug.depthprobe");

    /**
     * Runs only the first few passes of the chain: {@code -Dvitrail.debug.chain.limit=0}.
     *
     * <p>nought or more leaves that many of the pack's own passes undrawn and skips the final one;
     * minus one, the default, draws them all. Its use is to read a buffer at the moment the world
     * stage wrote it: the passes after it answer the same target, so the terrain's own figure never
     * reaches the end of a frame, and a probe read at the end would be measuring the pack's last
     * writer rather than the one under test.
     */
    public static final int CHAIN_LIMIT = ENABLED ? Integer.getInteger("vitrail.debug.chain.limit", -1) : -1;

    private static boolean flagsLogged;

    private VitrailDebug() {
    }

    /** Says once, per run, which flags this session was started with. */
    static void logFlags() {
        if (flagsLogged || !ENABLED) {
            return;
        }

        flagsLogged = true;
        Initializer.LOGGER.info("Vitrail: debug flags, programs={}, probes={}, smoke={}, chain={}, draw={}, shadowprobe={}, lightprobe={}, chainLimit={}",
                PROGRAMS, PROBES, SMOKE, CHAIN, DRAW, SHADOW_PROBE, LIGHT_PROBE, CHAIN_LIMIT);
    }
}
