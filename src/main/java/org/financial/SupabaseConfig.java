package org.financial;

import com.google.gson.Gson;
import com.google.gson.annotations.SerializedName;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Optional;

/**
 * Loads Supabase configuration from the user's IdGenerator directory.
 * Optional: if not present or invalid, Supabase sync is disabled.
 */
public class SupabaseConfig {

    private static final Path CONFIG_PATH = Path.of(
            System.getProperty("user.home"),
            "IdGenerator",
            "supabase-config.json"
    );
    private static final Path LOCAL_CONFIG_PATH = Path.of("supabase-config.json");
    private static final String TEMPLATE_URL = "https://wzibmekhpptsrzxwlbyr.supabase.co";
    private static final String TEMPLATE_ANON = "sb_publishable_xP56ZgsKjnIluMVLPK5gfw_27oTCx6h";
    private static final String CONFIG_TEMPLATE_JSON = """
            {
              "url": "https://your-project-ref.supabase.co",
              "anonKey": "YOUR_SUPABASE_ANON_KEY",
              "storageBucket": "idgenerator-files"
            }
            """;

    @SerializedName("url")
    private String url;

    @SerializedName("anonKey")
    private String anonKey;

    /**
     * Optional storage bucket for uploaded files (PDFs/images).
     * Defaults to "idgenerator-files" when not set.
     */
    @SerializedName("storageBucket")
    private String storageBucket;

    public String getUrl() {
        return url == null ? "" : url.trim();
    }

    public String getAnonKey() {
        return anonKey == null ? "" : anonKey.trim();
    }

    public String getStorageBucket() {
        if (storageBucket == null || storageBucket.isBlank()) {
            return "idgenerator-files";
        }
        return storageBucket.trim();
    }

    public boolean isValid() {
        String u = getUrl();
        String k = getAnonKey();
        if (u.isEmpty() || k.isEmpty()) return false;
        if (TEMPLATE_URL.equalsIgnoreCase(u)) return false;
        return !TEMPLATE_ANON.equalsIgnoreCase(k);
    }

    private static Optional<Path> resolveConfigPath() {
        // 1) Primary location: per-user IdGenerator folder
        if (Files.isRegularFile(CONFIG_PATH)) {
            if (isConfigUsable(CONFIG_PATH)) {
                SyncLogger.log("SupabaseConfig: using CONFIG_PATH " + CONFIG_PATH);
                return Optional.of(CONFIG_PATH);
            }
            Optional<Path> replacement = firstUsableExternalConfig();
            if (replacement.isPresent()) {
                Path src = replacement.get();
                try {
                    Files.createDirectories(CONFIG_PATH.getParent());
                    Files.copy(src, CONFIG_PATH, StandardCopyOption.REPLACE_EXISTING);
                    SyncLogger.log("SupabaseConfig: replaced placeholder/invalid CONFIG_PATH using " + src.toAbsolutePath().normalize());
                    return Optional.of(CONFIG_PATH);
                } catch (IOException e) {
                    SyncLogger.log("SupabaseConfig: failed replacing CONFIG_PATH; using source " + src.toAbsolutePath().normalize());
                    return Optional.of(src.toAbsolutePath().normalize());
                }
            }
            SyncLogger.log("SupabaseConfig: using CONFIG_PATH " + CONFIG_PATH);
            return Optional.of(CONFIG_PATH);
        }

        // 2) Dev convenience: allow config next to the app working directory
        if (Files.isRegularFile(LOCAL_CONFIG_PATH)) {
            // Prefer migrating it into the per-user folder so backups/imports remain portable.
            try {
                Files.createDirectories(CONFIG_PATH.getParent());
                Files.copy(LOCAL_CONFIG_PATH, CONFIG_PATH, StandardCopyOption.REPLACE_EXISTING);
                SyncLogger.log("SupabaseConfig: migrated LOCAL_CONFIG_PATH to CONFIG_PATH " + CONFIG_PATH);
                return Optional.of(CONFIG_PATH);
            } catch (IOException e) {
                SyncLogger.log("SupabaseConfig: using LOCAL_CONFIG_PATH " + LOCAL_CONFIG_PATH.toAbsolutePath().normalize());
                return Optional.of(LOCAL_CONFIG_PATH.toAbsolutePath().normalize());
            }
        }

        // 3) Installed-app convenience: allow config next to the running jar/exe.
        Optional<Path> appLocal = findConfigNearInstalledApp();
        if (appLocal.isPresent()) {
            Path appCfg = appLocal.get();
            try {
                Files.createDirectories(CONFIG_PATH.getParent());
                Files.copy(appCfg, CONFIG_PATH, StandardCopyOption.REPLACE_EXISTING);
                SyncLogger.log("SupabaseConfig: copied app-local config to CONFIG_PATH " + CONFIG_PATH);
                return Optional.of(CONFIG_PATH);
            } catch (IOException e) {
                SyncLogger.log("SupabaseConfig: using app-local config " + appCfg.toAbsolutePath().normalize());
                return Optional.of(appCfg.toAbsolutePath().normalize());
            }
        }

        // 4) Optional override via env var
        String env = System.getenv("IDGEN_SUPABASE_CONFIG");
        if (env != null && !env.isBlank()) {
            Path p = Path.of(env.trim());
            if (Files.isRegularFile(p)) {
                SyncLogger.log("SupabaseConfig: using IDGEN_SUPABASE_CONFIG=" + p.toAbsolutePath().normalize());
                return Optional.of(p.toAbsolutePath().normalize());
            }
        }

        if (ensureTemplateConfigExists() && isConfigUsable(CONFIG_PATH)) {
            SyncLogger.log("SupabaseConfig: using auto-created CONFIG_PATH " + CONFIG_PATH);
            return Optional.of(CONFIG_PATH);
        }
        SyncLogger.log("SupabaseConfig: no config file found");
        return Optional.empty();
    }

    private static Optional<Path> firstUsableExternalConfig() {
        if (Files.isRegularFile(LOCAL_CONFIG_PATH) && isConfigUsable(LOCAL_CONFIG_PATH)) {
            return Optional.of(LOCAL_CONFIG_PATH.toAbsolutePath().normalize());
        }
        Optional<Path> appLocal = findConfigNearInstalledApp();
        if (appLocal.isPresent() && isConfigUsable(appLocal.get())) {
            return Optional.of(appLocal.get().toAbsolutePath().normalize());
        }
        String env = System.getenv("IDGEN_SUPABASE_CONFIG");
        if (env != null && !env.isBlank()) {
            Path p = Path.of(env.trim()).toAbsolutePath().normalize();
            if (Files.isRegularFile(p) && isConfigUsable(p)) {
                return Optional.of(p);
            }
        }
        return Optional.empty();
    }

    private static boolean isConfigUsable(Path p) {
        try {
            String json = Files.readString(p);
            SupabaseConfig cfg = new Gson().fromJson(json, SupabaseConfig.class);
            return cfg != null && cfg.isValid();
        } catch (Exception e) {
            return false;
        }
    }

    private static Optional<Path> findConfigNearInstalledApp() {
        try {
            Path codePath = Path.of(SupabaseConfig.class.getProtectionDomain().getCodeSource().getLocation().toURI())
                    .toAbsolutePath()
                    .normalize();

            Path baseDir = Files.isDirectory(codePath) ? codePath : codePath.getParent();
            if (baseDir == null) return Optional.empty();

            Path direct = baseDir.resolve("supabase-config.json");
            if (Files.isRegularFile(direct)) return Optional.of(direct);

            // Common packaged layout: app/bin + config at parent folder.
            Path parent = baseDir.getParent();
            if (parent != null) {
                Path parentCfg = parent.resolve("supabase-config.json");
                if (Files.isRegularFile(parentCfg)) return Optional.of(parentCfg);
            }
        } catch (URISyntaxException | NullPointerException ignored) {
            // no-op; fallback paths below will still be checked
        }
        return Optional.empty();
    }

    private static boolean ensureTemplateConfigExists() {
        try {
            Files.createDirectories(CONFIG_PATH.getParent());
            if (Files.isRegularFile(CONFIG_PATH)) return true;
            Files.writeString(CONFIG_PATH, CONFIG_TEMPLATE_JSON);
            SyncLogger.log("SupabaseConfig: created template config at " + CONFIG_PATH.toAbsolutePath().normalize());
            return true;
        } catch (Exception e) {
            SyncLogger.log("SupabaseConfig: failed to create template config file");
            return false;
        }
    }

    public static Optional<SupabaseConfig> load() {
        try {
            Optional<Path> cfgPath = resolveConfigPath();
            if (cfgPath.isEmpty()) {
                return Optional.empty();
            }
            Path p = cfgPath.get();
            String json = Files.readString(p);

            SupabaseConfig cfg = new Gson().fromJson(json, SupabaseConfig.class);
            if (cfg == null || !cfg.isValid()) {
                SyncLogger.log("SupabaseConfig: invalid config in " + p.toAbsolutePath().normalize());
                return Optional.empty();
            }

            return Optional.of(cfg);
        } catch (Exception e) {
            SyncLogger.log("SupabaseConfig: exception while loading config: " + e.getClass().getSimpleName());
            return Optional.empty();
        }
    }

    public static Path getConfigPath() {
        return CONFIG_PATH;
    }
}

