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

    private static boolean flagsLogged;

    private VitrailDebug() {
    }

    /** Says once, per run, which flags this session was started with. */
    static void logFlags() {
        if (flagsLogged || !ENABLED) {
            return;
        }

        flagsLogged = true;
        Initializer.LOGGER.info("Vitrail: debug flags, programs={}, probes={}, smoke={}, chain={}, draw={}",
                PROGRAMS, PROBES, SMOKE, CHAIN, DRAW);
    }
}
