package net.vulkanmod.vitrail;

import net.vulkanmod.Initializer;
import net.vulkanmod.render.pack.ShaderPacks;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The OptiFine-format packs Vitrail can run, read out of the same {@code shaderpacks/} folder the
 * engine already uses.
 * <p>
 * A pack here is a directory or a zip holding a {@code shaders/} directory, which is what every
 * pack written for OptiFine and Iris looks like. The engine's own packs live beside them and are
 * a different format: those are JSON manifests, they are skipped, and nothing about them changes.
 */
public final class VitrailPacks {

    /** Prefix that marks a selection as a Vitrail pack rather than an engine pipeline id. */
    public static final String PREFIX = "vitrail:";

    private VitrailPacks() {
    }

    public static Path directory() {
        return ShaderPacks.dir();
    }

    public static String selection(String id) {
        return PREFIX + id;
    }

    public static boolean isSelection(String selection) {
        return selection != null && selection.startsWith(PREFIX);
    }

    public static String idOf(String selection) {
        return selection.substring(PREFIX.length());
    }

    /** Pack ids on disk, sorted, both directories and zips. */
    public static List<String> available() {
        Path dir = directory();
        List<String> ids = new ArrayList<>();

        try (DirectoryStream<Path> entries = Files.newDirectoryStream(dir)) {
            for (Path entry : entries) {
                String name = entry.getFileName().toString();

                if (Files.isDirectory(entry)) {
                    if (Files.isDirectory(entry.resolve("shaders"))) {
                        ids.add(name);
                    }
                } else if (name.endsWith(".zip") && Files.isRegularFile(entry)) {
                    ids.add(name.substring(0, name.length() - 4));
                }
            }
        } catch (IOException exception) {
            Initializer.LOGGER.warn("Could not list Vitrail packs in {}", dir, exception);
        }

        Collections.sort(ids);
        return ids;
    }

    /** The path of a pack, its zip if there is one, or null when it is not there. */
    public static Path path(String id) {
        Path dir = directory();
        Path folder = dir.resolve(id);

        if (Files.isDirectory(folder.resolve("shaders"))) {
            return folder;
        }

        Path zip = dir.resolve(id + ".zip");
        return Files.isRegularFile(zip) ? zip : null;
    }

    public static void logAvailable() {
        List<String> ids = available();
        Initializer.LOGGER.info("Vitrail: {} OptiFine-format pack(s) in shaderpacks/{}",
                ids.size(), ids.isEmpty() ? "" : " " + ids);
    }
}
