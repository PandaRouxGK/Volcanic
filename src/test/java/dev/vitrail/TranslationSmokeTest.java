package dev.vitrail;

import dev.vitrail.glsl.PackProgram;
import dev.vitrail.glsl.TranslatedUnit;
import dev.vitrail.pack.model.ProgramStage;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

/**
 * Proves the ported pack front end still reads and translates a pack, without a game and without
 * a GPU: it writes one small OptiFine-format pack to a temporary directory, loads two programs out
 * of it, and prints what the translator produced.
 */
public final class TranslationSmokeTest {

	public static void main(String[] args) throws Exception {
		Path pack = Files.createTempDirectory("vitrail-smoke");
		Path shaders = Files.createDirectories(pack.resolve("shaders"));

		Files.writeString(shaders.resolve("composite.vsh"), """
				#version 120
				varying vec2 texcoord;
				void main() {
				    gl_Position = ftransform();
				    texcoord = (gl_TextureMatrix[0] * gl_MultiTexCoord0).xy;
				}
				""", StandardCharsets.UTF_8);

		Files.writeString(shaders.resolve("composite.fsh"), """
				#version 120
				uniform sampler2D colortex0;
				uniform sampler2D depthtex0;
				varying vec2 texcoord;
				/* DRAWBUFFERS:0 */
				void main() {
				    vec4 color = texture2D(colortex0, texcoord);
				    float depth = texture2D(depthtex0, texcoord).r;
				    gl_FragData[0] = vec4(color.rgb * (1.0 - depth * 0.5), 1.0);
				}
				""", StandardCharsets.UTF_8);

		Files.writeString(shaders.resolve("final.vsh"), """
				#version 120
				varying vec2 texcoord;
				void main() {
				    gl_Position = ftransform();
				    texcoord = (gl_TextureMatrix[0] * gl_MultiTexCoord0).xy;
				}
				""", StandardCharsets.UTF_8);

		Files.writeString(shaders.resolve("final.fsh"), """
				#version 120
				uniform sampler2D colortex0;
				varying vec2 texcoord;
				/* DRAWBUFFERS:0 */
				void main() {
				    vec3 color = texture2D(colortex0, texcoord).rgb;
				    color = color / (color + vec3(1.0));
				    gl_FragData[0] = vec4(pow(color, vec3(1.0 / 2.2)), 1.0);
				}
				""", StandardCharsets.UTF_8);

		System.out.println("pack: " + pack);
		boolean ok = true;

		for (String program : new String[]{"composite", "final"}) {
			Optional<PackProgram.Loaded> loaded = PackProgram.load(pack, program, true);

			if (loaded.isEmpty()) {
				System.out.println("[FAIL] " + program + ": nothing loaded");
				ok = false;
				continue;
			}

			PackProgram.Loaded unit = loaded.get();
			System.out.println("[OK] " + program + " -> " + unit.program().stages().keySet()
					
					+ ", samplers " + unit.program().samplers().size());

			for (ProgramStage stage : new ProgramStage[]{ProgramStage.VERTEX, ProgramStage.FRAGMENT}) {
				TranslatedUnit translated = unit.program().stages().get(stage);
				if (translated == null) {
					System.out.println("    [FAIL] missing " + stage + " stage");
					ok = false;
					continue;
				}

				String text = translated.text();
				System.out.println("    " + stage + ": " + text.length() + " chars, "
						+ translated.blockMembers().size() + " block members");

				for (String line : text.split("\n")) {
					if (line.contains("gl_FragData") || line.contains("gl_Position")
							|| line.contains("SkyColor") || line.contains("core/")) {
						System.out.println("      | " + line.trim());
					}
				}
			}
		}

		System.out.println(ok ? "RESULT: PASS" : "RESULT: FAIL");
		if (!ok) {
			System.exit(1);
		}
	}
}
