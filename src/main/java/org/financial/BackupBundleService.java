package org.financial;

import com.google.gson.Gson;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.prefs.Preferences;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * Portable backup bundle containing DB + important local files.
 */
public final class BackupBundleService {
    private static final Gson GSON = new Gson();
    private static final String PROFILE_JSON = "profile.json";
    private static final String DATABASE_DB = "database.db";
    private static final String SUPABASE_CONFIG_JSON = "supabase-config.json";
    private static final String PENDING_QUEUE_JSON = "pending-supabase-uploads.json";
    private static final String REFERENCE_DIR = "reference/";
    private static final String PHOTOS_DIR = "photos/";

    private static final Path APP_HOME = Path.of(System.getProperty("user.home"), "IdGenerator");
    private static final Path REFERENCE_HOME = APP_HOME.resolve("reference");
    private static final Path PHOTOS_HOME = APP_HOME.resolve("photos");
    private static final Path SUPABASE_CONFIG_PATH = APP_HOME.resolve("supabase-config.json");
    private static final Path PENDING_QUEUE_PATH = APP_HOME.resolve("pending-supabase-uploads.json");
    private static final Path LOCAL_SUPABASE_CONFIG_PATH = Path.of("supabase-config.json");

    private static final Preferences PREFS = Preferences.userNodeForPackage(PrimaryController.class);
    private static final String PREF_ORG_NAME = "org_name";
    private static final String PREF_ORG_TYPE = "org_type";
    private static final String PREF_ID_PREFIX = "id_prefix";

    private BackupBundleService() {}

    public static void exportBundle(Path destinationZip) throws Exception {
        Path tempDir = Files.createTempDirectory("idgenerator-export-");
        try {
            Path tempDb = tempDir.resolve(DATABASE_DB);
            DbHelper.exportDatabaseTo(tempDb);

            Files.createDirectories(destinationZip.toAbsolutePath().getParent());
            try (OutputStream fos = Files.newOutputStream(destinationZip);
                 ZipOutputStream zos = new ZipOutputStream(fos)) {
                addFile(zos, tempDb, DATABASE_DB);
                addText(zos, PROFILE_JSON, GSON.toJson(readProfileMap()));

                // Include whichever Supabase config is actually available/used so QR stays cloud-based after import.
                if (Files.isRegularFile(SUPABASE_CONFIG_PATH)) {
                    addFile(zos, SUPABASE_CONFIG_PATH, SUPABASE_CONFIG_JSON);
                } else if (Files.isRegularFile(LOCAL_SUPABASE_CONFIG_PATH)) {
                    addFile(zos, LOCAL_SUPABASE_CONFIG_PATH.toAbsolutePath().normalize(), SUPABASE_CONFIG_JSON);
                }
                if (Files.isRegularFile(PENDING_QUEUE_PATH)) {
                    addFile(zos, PENDING_QUEUE_PATH, PENDING_QUEUE_JSON);
                }
                addDirectoryTree(zos, REFERENCE_HOME, REFERENCE_DIR);
                addDirectoryTree(zos, PHOTOS_HOME, PHOTOS_DIR);
            }
        } finally {
            deleteTreeQuietly(tempDir);
        }
    }

    public static Path importBundle(Path sourceZip) throws Exception {
        Path tempDir = Files.createTempDirectory("idgenerator-import-");
        try {
            unzip(sourceZip, tempDir);
            Path bundledDb = tempDir.resolve(DATABASE_DB);
            if (!Files.isRegularFile(bundledDb)) {
                throw new IOException("Backup bundle is missing database.db");
            }

            Path autoBackup = DbHelper.importDatabaseReplacing(bundledDb);

            restoreProfileFrom(tempDir.resolve(PROFILE_JSON));
            restoreOptionalFile(tempDir.resolve(SUPABASE_CONFIG_JSON), SUPABASE_CONFIG_PATH);
            restoreOptionalFile(tempDir.resolve(PENDING_QUEUE_JSON), PENDING_QUEUE_PATH);
            copyDirMerge(tempDir.resolve("reference"), REFERENCE_HOME);
            copyDirMerge(tempDir.resolve("photos"), PHOTOS_HOME);

            DbHelper.relinkImportedReferencePaths(REFERENCE_HOME);
            DbHelper.relinkImportedPhotoPaths(PHOTOS_HOME);
            return autoBackup;
        } finally {
            deleteTreeQuietly(tempDir);
        }
    }

    private static Map<String, String> readProfileMap() {
        Map<String, String> data = new LinkedHashMap<>();
        data.put(PREF_ORG_NAME, PREFS.get(PREF_ORG_NAME, ""));
        data.put(PREF_ORG_TYPE, PREFS.get(PREF_ORG_TYPE, ""));
        data.put(PREF_ID_PREFIX, PREFS.get(PREF_ID_PREFIX, ""));
        return data;
    }

    private static void restoreProfileFrom(Path profileJson) throws IOException {
        if (!Files.isRegularFile(profileJson)) return;
        String json = Files.readString(profileJson);
        @SuppressWarnings("unchecked")
        Map<String, String> data = GSON.fromJson(json, Map.class);
        if (data == null) return;
        putIfPresent(data, PREF_ORG_NAME);
        putIfPresent(data, PREF_ORG_TYPE);
        putIfPresent(data, PREF_ID_PREFIX);
    }

    private static void putIfPresent(Map<String, String> data, String key) {
        Object v = data.get(key);
        if (v != null) {
            PREFS.put(key, String.valueOf(v).trim());
        }
    }

    private static void restoreOptionalFile(Path from, Path to) throws IOException {
        if (!Files.isRegularFile(from)) return;
        Files.createDirectories(to.getParent());
        Files.copy(from, to, StandardCopyOption.REPLACE_EXISTING);
    }

    private static void addText(ZipOutputStream zos, String entryName, String text) throws IOException {
        ZipEntry entry = new ZipEntry(entryName);
        zos.putNextEntry(entry);
        zos.write((text == null ? "" : text).getBytes());
        zos.closeEntry();
    }

    private static void addFile(ZipOutputStream zos, Path file, String entryName) throws IOException {
        ZipEntry entry = new ZipEntry(entryName.replace("\\", "/"));
        zos.putNextEntry(entry);
        try (InputStream in = Files.newInputStream(file)) {
            in.transferTo(zos);
        }
        zos.closeEntry();
    }

    private static void addDirectoryTree(ZipOutputStream zos, Path dir, String baseEntryPrefix) throws IOException {
        if (!Files.isDirectory(dir)) return;
        try (var walk = Files.walk(dir)) {
            walk.filter(Files::isRegularFile).forEach(p -> {
                String rel = dir.relativize(p).toString().replace("\\", "/");
                try {
                    addFile(zos, p, baseEntryPrefix + rel);
                } catch (IOException ignored) {
                    // best effort per-file; continue bundle export
                }
            });
        }
    }

    private static void unzip(Path zipFile, Path targetDir) throws IOException {
        try (InputStream fis = Files.newInputStream(zipFile);
             ZipInputStream zis = new ZipInputStream(fis)) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                Path out = targetDir.resolve(entry.getName()).normalize();
                if (!out.startsWith(targetDir)) {
                    throw new IOException("Invalid zip entry path");
                }
                if (entry.isDirectory()) {
                    Files.createDirectories(out);
                } else {
                    Files.createDirectories(out.getParent());
                    try (OutputStream os = Files.newOutputStream(out)) {
                        zis.transferTo(os);
                    }
                }
                zis.closeEntry();
            }
        }
    }

    private static void copyDirMerge(Path source, Path target) throws IOException {
        if (!Files.isDirectory(source)) return;
        Files.createDirectories(target);
        try (var walk = Files.walk(source)) {
            walk.filter(Files::isRegularFile).forEach(p -> {
                Path dest = target.resolve(source.relativize(p).toString());
                try {
                    Files.createDirectories(dest.getParent());
                    Files.copy(p, dest, StandardCopyOption.REPLACE_EXISTING);
                } catch (IOException ignored) {
                    // best effort
                }
            });
        }
    }

    private static void deleteTreeQuietly(Path dir) {
        if (dir == null || !Files.exists(dir)) return;
        try (var walk = Files.walk(dir)) {
            walk.sorted((a, b) -> b.getNameCount() - a.getNameCount())
                .forEach(p -> {
                    try {
                        Files.deleteIfExists(p);
                    } catch (IOException ignored) {}
                });
        } catch (IOException ignored) {}
    }
}
