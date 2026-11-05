package net.vulkanmod.vitrail;

import com.mojang.blaze3d.vertex.VertexFormat;
import dev.vitrail.glsl.PackProgram;
import dev.vitrail.glsl.TranslatedUnit;
import dev.vitrail.glsl.VertexInputs;
import dev.vitrail.pack.model.ProgramStage;
import net.vulkanmod.Initializer;
import net.vulkanmod.vitrail.gpu.GpuBuffer;
import net.vulkanmod.vitrail.gpu.GpuDevice;
import net.vulkanmod.vitrail.gpu.GpuPipeline;
import net.vulkanmod.vulkan.shader.GraphicsPipeline;
import net.vulkanmod.vulkan.shader.layout.PushConstants;
import net.vulkanmod.vulkan.texture.VTextureSelector;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.lwjgl.vulkan.VK10.VK_SHADER_STAGE_FRAGMENT_BIT;
import static org.lwjgl.vulkan.VK10.VK_SHADER_STAGE_VERTEX_BIT;

/**
 * One full screen program of a pack, translated by Vitrail's front end and compiled by the engine.
 * <p>
 * The translation hands back Vulkan GLSL as text, which is the whole point of the front end: the
 * compiler this project already has turns it into SPIR-V, so no legacy GLSL survives past the
 * first load. Bindings are read back out of that text rather than guessed, because a pipeline
 * layout that disagrees with the shader on a binding number is refused at the first draw.
 * <p>
 * One layout serves both stages, so a binding the two stages share is one descriptor carrying both
 * stage bits. Declaring it twice would put the same binding number in the set layout twice, which
 * the driver refuses.
 */
final class VitrailProgram {

    private static final Pattern SAMPLER = Pattern.compile(
            "layout\\s*\\(([^)]*)\\bbinding\\s*=\\s*(\\d+)[^)]*\\)\\s*uniform\\s+"
                    + "((?:[iu]?sampler|texture|[iu]?image)\\w*)\\s+(\\w+)\\s*;");
    private static final Pattern BLOCK = Pattern.compile(
            "layout\\s*\\(([^)]*)\\bbinding\\s*=\\s*(\\d+)[^)]*\\)\\s*(?:uniform|buffer)\\s+(\\w+)\\s*\\{");

    /**
     * A declaration shaderc refuses without an explicit binding, before one has been written into
     * it.
     * <p>
     * {@link dev.vitrail.glsl.Emitter} writes the pack's block and its samplers with no
     * {@code layout(binding = )} at all, because the renderer those shaders were written for hands
     * the numbers over when it builds the pipeline. Shaderc compiles GLSL to SPIR-V for Vulkan,
     * where a descriptor with no binding is refused outright, so the numbers are written here
     * before the text reaches the compiler.
     */
    private static final Pattern BLOCK_DECL = Pattern.compile(
            "^(\\s*)(layout\\s*\\(([^)]*)\\)\\s*)?"
                    + "((?:(?:coherent|readonly|writeonly|volatile|restrict)\\s+)*)"
                    + "(uniform|buffer)\\s+(\\w+)\\s*\\{");

    private static final Pattern OPAQUE_DECL = Pattern.compile(
            "^(\\s*)(layout\\s*\\(([^)]*)\\)\\s*)?"
                    + "((?:(?:coherent|readonly|writeonly|volatile|restrict)\\s+)*)"
                    + "uniform\\s+((?:[iu]?sampler|texture|[iu]?image)\\w*)\\s+(\\w+)\\s*;");

    /** A std140 block is a few hundred bytes; the range is over-declared on purpose, which Vulkan allows. */
    private static final int BLOCK_FLOATS = 1024;

    final String name;
    /** The facade's handle: it owns the blocks this program was built over. */
    private final GpuPipeline facade;
    final GraphicsPipeline pipeline;
    final List<String> samplerNames;
    final List<Integer> samplerSlots;
    /** The colour targets the fragment stage declares as outputs, in the order it numbers them. */
    final List<Integer> drawBuffers;
    private final VitrailGlobals globals;

    private VitrailProgram(String name, GpuPipeline facade, List<String> samplerNames,
                           List<Integer> samplerSlots, List<Integer> drawBuffers,
                           VitrailGlobals globals) {
        this.name = name;
        this.facade = facade;
        this.pipeline = facade.pipeline();
        this.samplerNames = samplerNames;
        this.samplerSlots = samplerSlots;
        this.drawBuffers = drawBuffers;
        this.globals = globals;
    }

    /**
     * Whether this program writes one colour target, which is the most this chain can give a pass.
     * One target is a picture; several are a pack's internal buffers, and dropping the extra
     * outputs leaves the next pass reading white where its own work should have been.
     */
    boolean singleTarget() {
        return this.drawBuffers.size() <= 1;
    }

    /** Fills this program's block from the frame that is about to be drawn. */
    void updateGlobals(int width, int height) {
        this.globals.write(width, height);
    }

    static VitrailProgram load(Path pack, String name) {
        Optional<PackProgram.Loaded> loaded;

        try {
            loaded = PackProgram.load(pack, name, true);
        } catch (Exception | OutOfMemoryError exception) {
            // One program a pack cannot supply is a program the chain does without. Nothing here
            // is worth taking the game down for.
            Initializer.LOGGER.warn("Vitrail: could not read '{}' from {}", name, pack, exception);
            return null;
        }

        if (loaded.isEmpty()) {
            return null;
        }

        TranslatedUnit vertex = loaded.get().program().stages().get(ProgramStage.VERTEX);
        TranslatedUnit fragment = loaded.get().program().stages().get(ProgramStage.FRAGMENT);

        if (vertex == null || fragment == null) {
            return null;
        }

        String vertexText = fullscreenCorners(vertex.text());
        Bound bound = assignVaryings(assignBindings(vertexText, fragment.text()));
        dump(name, bound.vertex(), bound.fragment());

        VitrailGlobals globals = VitrailGlobals.of(vertex.blockMembers());

        Layout layout = new Layout();
        layout.scan(bound.vertex(), VK_SHADER_STAGE_VERTEX_BIT);
        layout.scan(bound.fragment(), VK_SHADER_STAGE_FRAGMENT_BIT);

        try {
            GpuPipeline.Builder builder = GpuDevice.program(name, bound.vertex(), bound.fragment());
            layout.blocks(globals, builder);
            layout.samplers(builder, false);

            return new VitrailProgram(name, builder.build(),
                    layout.samplerNames, layout.samplerSlots, fragment.drawBuffers(), globals);
        } catch (RuntimeException exception) {
            Initializer.LOGGER.error("Vitrail: '{}' of {} did not compile", name, pack, exception);
            return null;
        }
    }

    /**
     * Loads one of the pack's geometry programs against the engine's own terrain vertex format.
     * <p>
     * A full screen stage takes its corners from the vertex index and binds no attribute; a
     * geometry stage is the opposite, and the whole point of the terrain path is that its inputs
     * are the mesh's: {@link VertexInputs#TERRAIN} declares the attributes Sodium's chunk mesh
     * carries and answers the names a pack reads out of them — which is what this engine's
     * compressed terrain format is too, twenty bytes of position, colour, atlas uv and light map.
     * <p>
     * Nothing draws with the result yet: it is built and logged so the bridge can be measured
     * against the format that is really bound, rather than assumed to line up.
     */
    static VitrailProgram loadTerrain(Path pack, String name) {
        Optional<PackProgram.Loaded> loaded;

        try {
            loaded = PackProgram.load(pack, name, VertexInputs.TERRAIN, Map.of(), "");
        } catch (Exception | OutOfMemoryError exception) {
            Initializer.LOGGER.warn("Vitrail: could not read geometry '{}' from {}", name, pack, exception);
            return null;
        }

        if (loaded.isEmpty()) {
            return null;
        }

        TranslatedUnit vertex = loaded.get().program().stages().get(ProgramStage.VERTEX);
        TranslatedUnit fragment = loaded.get().program().stages().get(ProgramStage.FRAGMENT);

        if (vertex == null || fragment == null) {
            return null;
        }

        Bound bound = assignVaryings(assignBindings(vertex.text(), fragment.text()));
        dump(name, bound.vertex(), bound.fragment());

        // A geometry stage draws the world, so the fixed function names carry the camera's
        // transform rather than the identity a full screen quad is placed with.
        VitrailGlobals globals = VitrailGlobals.of(vertex.blockMembers(), false);

        Layout layout = new Layout();
        layout.scan(bound.vertex(), VK_SHADER_STAGE_VERTEX_BIT);
        layout.scan(bound.fragment(), VK_SHADER_STAGE_FRAGMENT_BIT);

        try {
            GpuPipeline.Builder builder = GpuDevice.program(name, bound.vertex(), bound.fragment())
                    .vertexFormat(terrainVertexFormat())
                    // The translated stage declares the engine's chunk push constant block, the
                    // region's origin in twelve bytes, and the engine pushes it at every chunk
                    // area. A layout that does not promise that range refuses both the pipeline
                    // and the draw.
                    .pushConstants(PushConstants.region());
            layout.blocks(globals, builder);
            layout.samplers(builder, true);

            return new VitrailProgram(name, builder.build(),
                    layout.samplerNames, layout.samplerSlots, fragment.drawBuffers(), globals);
        } catch (RuntimeException exception) {
            Initializer.LOGGER.error("Vitrail: geometry '{}' of {} did not build", name, pack, exception);
            return null;
        }
    }

    /** The format the engine binds when it draws a chunk mesh. */
    private static VertexFormat terrainVertexFormat() {
        return net.vulkanmod.vulkan.Renderer.getInstance().getPipelineManager().TERRAIN_VERTEX_FORMAT;
    }

    /**
     * Writes the two translated stages beside the log, and only under
     * {@link VitrailDebug#PROGRAMS}: a capture session reads the GLSL, a player never pays for it.
     */
    static void dump(String name, String vertex, String fragment) {
        if (!VitrailDebug.PROGRAMS) {
            return;
        }

        try {
            Path dir = Path.of("logs", "vitrail");
            Files.createDirectories(dir);
            String base = name.replace('/', '_');
            Files.writeString(dir.resolve(base + ".vsh"), vertex);
            Files.writeString(dir.resolve(base + ".fsh"), fragment);
        } catch (IOException exception) {
            Initializer.LOGGER.warn("Vitrail: could not write the translated '{}' beside the log", name, exception);
        }
    }

    /**
     * The four lines that place the quad a full screen pass draws onto, from the vertex index.
     * <p>
     * The corner list is the engine's own blit's, {@code (-1,-1) (3,-1) (-1,3)}. The texture
     * coordinate is that corner mapped with its vertical axis flipped: the engine's targets are
     * stored with the first row at the bottom of the picture (which is why its own blit carries
     * {@code INVERTED_UV}), so a stage that sampled them the other way up draws the frame upside
     * down — the world seen through a pack's last pass came out mirrored for exactly that reason.
     */
    private static final String CORNERS = """
            vec2 of_QuadCorner = vec2(gl_VertexIndex == 1 ? 3.0 : -1.0, gl_VertexIndex == 2 ? 3.0 : -1.0);
            vec3 Position = vec3(of_QuadCorner, 0.0);
            vec2 UV0 = vec2(of_QuadCorner.x * 0.5 + 0.5, 0.5 - of_QuadCorner.y * 0.5);""";

    /**
     * Gives a full screen stage its corners.
     * <p>
     * {@link dev.vitrail.glsl.Emitter} declares the quad a pack draws onto as two attributes,
     * {@code in vec3 Position} and {@code in vec2 UV0}, because that is what Iris hands a full
     * screen stage. This pipeline is built with {@link CustomVertexFormat#NONE} and drawn with
     * three bare vertices, so every input reads nought: the triangle collapses to a point, the
     * pass runs, writes nothing, and the screen stays black. Computing the corners from the vertex
     * index instead removes the attributes altogether, which is what a pipeline with no vertex
     * format can draw.
     * <p>
     * Only a stage carrying the full screen prologue is touched: the marker is the vertex macro
     * the same list writes, so a geometry stage that happens to take a {@code Position} of its own
     * is left alone.
     */
    static String fullscreenCorners(String text) {
        if (!text.contains("#define of_Vertex vec4(Position, 1.0)")
                || !text.contains("in vec3 Position;")) {
            return text;
        }

        String placed = text.replace("in vec3 Position;\n", CORNERS + "\n")
                .replace("in vec2 UV0;\n", "");

        // Where the stage puts the quad, too, not only where its corners are. The emitter closes
        // every full screen vertex stage with the pack's own projection - `ftransform()`, which is
        // the camera matrix of the uniform block - and that block is zeroed here until the game's
        // values are written into it. A zero matrix multiplies the corner to the origin: the
        // triangle has no area, the pass records, writes nothing, and the picture is black with no
        // error anywhere. A full screen stage only has to fill an image it already has the corners
        // of, so its clip position is the corner itself and no uniform is consulted.
        Matcher wrapper = WRAPPER.matcher(placed);

        return wrapper.find()
                ? placed.substring(0, wrapper.start()) + "void main() { ofPackMain(); "
                        + "gl_Position = vec4(of_QuadCorner, 0.0, 1.0); }"
                        + placed.substring(wrapper.end())
                : placed;
    }

    /** The emitter's full screen entry point: the pack's own body, then its projection. */
    private static final Pattern WRAPPER = Pattern.compile(
            "void main\\(\\) \\{ ofPackMain\\(\\); gl_Position\\.z = [^}]*\\}");

    /** One declaration of a program, in the order the stage mentions it. */
    private record Declaration(String name, boolean block) {}

    /** A sampler of a prepared program, with the binding the text now carries for it. */
    record SamplerDecl(String name, String type, int binding) {}

    /** A uniform block of a prepared program, with the stages that read it. */
    record BlockDecl(String name, int binding, int stages) {}

    /**
     * The samplers of two prepared stages, by the binding the preparation wrote into them.
     * <p>
     * A caller building a pipeline has to promise the same binding the text declares, and the
     * facade takes one number per descriptor: reading them back out of the text is what keeps the
     * two from being two guesses.
     */
    static List<SamplerDecl> samplerDecls(String vertex, String fragment) {
        Layout layout = new Layout();
        layout.scan(vertex, VK_SHADER_STAGE_VERTEX_BIT);
        layout.scan(fragment, VK_SHADER_STAGE_FRAGMENT_BIT);

        List<SamplerDecl> decls = new ArrayList<>();
        for (Map.Entry<Integer, String> entry : layout.samplerTypes.entrySet()) {
            String name = layout.samplerNamesByBinding.get(entry.getKey());
            decls.add(new SamplerDecl(name, entry.getValue(), entry.getKey()));
        }

        return decls;
    }

    /** The blocks of two prepared stages, with the stage bits each is read from. */
    static List<BlockDecl> blockDecls(String vertex, String fragment) {
        Layout layout = new Layout();
        layout.scan(vertex, VK_SHADER_STAGE_VERTEX_BIT);
        layout.scan(fragment, VK_SHADER_STAGE_FRAGMENT_BIT);

        List<BlockDecl> decls = new ArrayList<>();
        for (Map.Entry<Integer, Integer> entry : layout.blockStages.entrySet()) {
            decls.add(new BlockDecl(layout.blockNames.get(entry.getKey()), entry.getKey(), entry.getValue()));
        }

        return decls;
    }

    /** The two stages of a program, with a binding written into every declaration that lacked one. */
    record Bound(String vertex, String fragment) {}

    /**
     * A varying, as one stage declares it: qualifiers, direction, type and names.
     * <p>
     * GLSL lets one declaration name several varyings, and this pack does it: {@code out vec3 sunVec,
     * upVec;}. A single number written in front of that line would put both at one location, and
     * leaving the line alone leaves both unnumbered, which glslang then numbers itself - starting at
     * zero, on top of the varyings the pack numbered by hand. Both are the same bug seen from two
     * sides, so the names are read out one by one and each gets its own declaration.
     */
    private static final Pattern VARYING = Pattern.compile(
            "^(\\s*)((?:(?:flat|smooth|noperspective|centroid|invariant|sample)\\s+)*)(out|in)\\s+(\\w+)\\s+"
                    + "((?:\\w+\\s*,\\s*)*\\w+)\\s*;");

    /** A location a declaration already carries, or -1. */
    private static int writtenLocation(String line) {
        Matcher numbered = LOCATION.matcher(line);
        return numbered.find() ? Integer.parseInt(numbered.group(1)) : -1;
    }

    /**
     * The two stages, with a location written into every varying.
     * <p>
     * OpenGL links a vertex output to a fragment input by name, and every shader this pack was
     * written for relies on that. Vulkan links them by location, and a compiler numbers the varyings
     * a shader left unnumbered in the order it met them - which differs between the two stages the
     * moment they declare them in a different order. This pack's last stages do exactly that: the
     * vertex stage declares {@code texcoord} first, the fragment stage declares the flat
     * {@code exposure} first, so the fragment's {@code texcoord} arrived as the vertex stage's
     * {@code rodExposureDepth} - a constant. Every pixel then sampled one texel, which is what a
     * screen of a single flat colour is. Numbering both stages from one map, by name, is what OpenGL
     * was doing for them all along.
     */
    /** A location a declaration already carries. */
    private static final Pattern LOCATION = Pattern.compile("layout\\s*\\(\\s*location\\s*=\\s*(\\d+)");

    static Bound assignVaryings(String vertexText, String fragmentText) {
        Map<String, Integer> locations = new LinkedHashMap<>();
        int next = 0;

        // One number per name, from the vertex stage outwards, and the pack's own numbers kept as
        // they are. A stage that numbers a varying by hand and a stage that leaves it unnumbered
        // still meet at one location, because the map is keyed by name and both stages are read.
        List<String> lines = new ArrayList<>();
        List<Integer> written = new ArrayList<>();

        for (String[] stage : new String[][]{{vertexText, "out"}, {fragmentText, "in"}}) {
            for (String line : stage[0].split("\\R")) {
                Matcher varying = VARYING.matcher(line);

                if (!varying.matches() || !varying.group(3).equals(stage[1])) {
                    continue;
                }

                int location = writtenLocation(line);

                for (String name : varying.group(5).split(",")) {
                    lines.add(name.trim());
                    written.add(location);
                    next = Math.max(next, location + 1);
                }
            }
        }

        // This pack numbers most of its varyings itself, and the emitter numbers the fragment's
        // outputs. Starting at zero would hand an unnumbered varying a location one of those already
        // holds, and a stage cannot carry two of them at one location: that is what stopped the
        // world's geometry shader from building the first time this ran.
        for (int i = 0; i < lines.size(); i++) {
            if (written.get(i) >= 0) {
                locations.putIfAbsent(lines.get(i), written.get(i));
            }
        }

        // An input the vertex stage does not write still gets a number of its own: leaving it
        // unnumbered is what let the two stages disagree in the first place.
        for (String name : lines) {
            locations.putIfAbsent(name, next++);
        }

        return new Bound(numbered(vertexText, locations, "out"), numbered(fragmentText, locations, "in"));
    }

    static Bound assignVaryings(Bound bound) {
        return assignVaryings(bound.vertex(), bound.fragment());
    }

    private static String numbered(String text, Map<String, Integer> locations, String direction) {
        List<String> lines = new ArrayList<>();

        for (String line : text.split("\\R", -1)) {
            Matcher varying = VARYING.matcher(line);

            // A stage that numbered its own varying is taken at its word, as with the bindings.
            if (!varying.matches() || !varying.group(3).equals(direction) || line.contains("layout(")) {
                lines.add(line);
                continue;
            }

            for (String name : varying.group(5).split(",")) {
                String trimmed = name.trim();

                lines.add(varying.group(1) + "layout(location = " + locations.get(trimmed) + ") "
                        + varying.group(2) + varying.group(3) + " " + varying.group(4) + " "
                        + trimmed + ";");
            }
        }

        return String.join("\n", lines);
    }

    static Bound assignBindings(String vertexText, String fragmentText) {
        List<Declaration> vertex = declarations(vertexText);
        List<Declaration> fragment = declarations(fragmentText);
        Map<String, Integer> bindings = new LinkedHashMap<>();
        int next = 0;

        // A binding number carries one descriptor type, so the blocks take a run of their own and
        // the samplers start above it. Both stages share the map: a name one stage declares and
        // the other samples is one descriptor, and the two texts have to agree on its number.
        for (List<Declaration> stage : List.of(vertex, fragment)) {
            for (Declaration declaration : stage) {
                if (declaration.block() && !bindings.containsKey(declaration.name())) {
                    bindings.put(declaration.name(), next++);
                }
            }
        }

        for (List<Declaration> stage : List.of(vertex, fragment)) {
            for (Declaration declaration : stage) {
                if (!declaration.block() && !bindings.containsKey(declaration.name())) {
                    bindings.put(declaration.name(), next++);
                }
            }
        }

        return new Bound(withBindings(vertexText, bindings), withBindings(fragmentText, bindings));
    }

    private static List<Declaration> declarations(String text) {
        List<Declaration> declarations = new ArrayList<>();

        for (String line : text.split("\\R")) {
            Matcher block = BLOCK_DECL.matcher(line);
            if (block.find()) {
                if (!addressed(block.group(3))) {
                    declarations.add(new Declaration(block.group(6), true));
                }
                continue;
            }

            Matcher opaque = OPAQUE_DECL.matcher(line);
            if (opaque.find() && !addressed(opaque.group(3))) {
                declarations.add(new Declaration(opaque.group(6), false));
            }
        }

        return declarations;
    }

    /** Whether a declaration already carries a binding, or is a push constant that carries none. */
    private static boolean addressed(String layout) {
        return layout != null && (layout.contains("binding") || layout.contains("push_constant"));
    }

    private static String withBindings(String text, Map<String, Integer> bindings) {
        List<String> lines = new ArrayList<>();

        for (String line : text.split("\\R", -1)) {
            lines.add(bindLine(line, bindings));
        }

        return String.join("\n", lines);
    }

    private static String bindLine(String line, Map<String, Integer> bindings) {
        Matcher block = BLOCK_DECL.matcher(line);
        if (block.find()) {
            Integer binding = bindings.get(block.group(6));
            if (binding == null || addressed(block.group(3))) {
                return line;
            }

            return block.group(1) + qualified(block.group(3), "std140", binding)
                    + block.group(4) + block.group(5) + " " + block.group(6) + " {"
                    + line.substring(block.end());
        }

        Matcher opaque = OPAQUE_DECL.matcher(line);
        if (opaque.find()) {
            Integer binding = bindings.get(opaque.group(6));
            if (binding == null || addressed(opaque.group(3))) {
                return line;
            }

            return opaque.group(1) + qualified(opaque.group(3), "", binding)
                    + opaque.group(4) + "uniform " + opaque.group(5) + " " + opaque.group(6) + ";"
                    + line.substring(opaque.end());
        }

        return line;
    }

    /**
     * The layout qualifier to write, keeping whatever the declaration already said: an image
     * carries its format there, and dropping it would change how the pack's writes are read back.
     */
    private static String qualified(String layout, String implied, int binding) {
        List<String> keys = new ArrayList<>();

        if (layout != null && !layout.isBlank()) {
            keys.add(layout.strip());
        } else if (!implied.isEmpty()) {
            keys.add(implied);
        }

        keys.add("binding = " + binding);
        return "layout(" + String.join(", ", keys) + ") ";
    }

    /** Bindings of one program, collected across both stages. */
    private static final class Layout {
        private final Map<Integer, Integer> blockStages = new LinkedHashMap<>();
        private final Map<Integer, String> blockNames = new LinkedHashMap<>();
        private final Map<Integer, String> samplerTypes = new LinkedHashMap<>();
        private final Map<Integer, String> samplerNamesByBinding = new LinkedHashMap<>();

        final List<String> samplerNames = new ArrayList<>();
        final List<Integer> samplerSlots = new ArrayList<>();

        void scan(String text, int stage) {
            Matcher block = BLOCK.matcher(text);
            while (block.find()) {
                int binding = Integer.parseInt(block.group(2));
                this.blockStages.merge(binding, stage, (left, right) -> left | right);
                this.blockNames.putIfAbsent(binding, block.group(3));
            }

            Matcher sampler = SAMPLER.matcher(text);
            while (sampler.find()) {
                int binding = Integer.parseInt(sampler.group(2));
                this.samplerTypes.putIfAbsent(binding, sampler.group(3));
                this.samplerNamesByBinding.putIfAbsent(binding, sampler.group(4));
            }
        }

        void blocks(VitrailGlobals globals, GpuPipeline.Builder builder) {
            for (Map.Entry<Integer, Integer> entry : this.blockStages.entrySet()) {
                // The pack's own block is written from the game each frame, so the facade is handed
                // the memory the writer fills rather than a copy of it. A block nothing fills keeps
                // the facade's zeroed range instead of reading undefined memory.
                GpuBuffer block = VitrailGlobals.BLOCK.equals(this.blockNames.get(entry.getKey()))
                        ? GpuBuffer.wrap(globals.address(), BLOCK_FLOATS * 4)
                        : new GpuBuffer(BLOCK_FLOATS * 4);
                builder.block(entry.getKey(), entry.getValue(), block);
            }
        }

        /**
         * The selector slot a chunk mesh's textures are bound at, taken from the name the pack gave
         * the sampler rather than from where it sat among the declarations.
         * <p>
         * The engine binds a terrain draw from its own texture selector: slot 0 is the block atlas,
         * slot 2 is the light texture, slot 4 is the PBR normal atlas ({@code WorldRenderer} binds it
         * there itself). A pack names those three {@code gtexture}, {@code lightmap} and
         * {@code normals}/{@code specular}. Numbering them by declaration order instead hands the
         * light map to {@code specular} and the overlay to {@code normals}, and the draw comes out
         * grey with no light on it - so the names are read here, and a name nothing fills is sent to
         * the last slot, which no engine code writes and which therefore holds the white texture.
         */
        private static int engineSlot(String name) {
            return switch (name) {
                case "gtexture", "texture", "tex" -> 0;
                case "lightmap" -> 2;
                case "normals", "specular" -> 4;
                default -> VTextureSelector.SIZE - 1;
            };
        }

        /**
         * @param terrain whether the slots are the engine's terrain slots, by name, instead of the
         *                order the declarations were met in
         */
        void samplers(GpuPipeline.Builder builder, boolean terrain) {
            int imageIdx = 0;

            for (Map.Entry<Integer, String> entry : this.samplerTypes.entrySet()) {
                int binding = entry.getKey();
                String type = entry.getValue();
                String name = this.samplerNamesByBinding.get(binding);

                // The selector slot is the order the declarations were met in, and not the binding:
                // the bindings now start above the blocks and run past what the selector holds, so
                // what is bound at draw time is this index and what the layout promises is the
                // binding beside it. Both are written into one descriptor below.
                // One slot per sampler, in declaration order. Clamping the excess onto slot zero is
                // what collided every sampler past the twelfth: eight of the nineteen this pack's
                // composite4 declares were all reading whatever image was bound last.
                int slot = terrain ? engineSlot(name) : imageIdx;

                this.samplerNames.add(name);
                this.samplerSlots.add(slot);
                builder.sampler(binding, type, name, slot);
                imageIdx++;
            }
        }
    }
}
