package org.financial;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.sql.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

public class DbHelper {

    private static final String APP_DIR_NAME = "IdGenerator";
    private static final Path DB_PATH = Path.of(
            System.getProperty("user.home"),
            APP_DIR_NAME,
            "database",
            "idgenerator.db"
    );
    private static final String DB_URL = "jdbc:sqlite:" + DB_PATH.toString().replace("\\", "/");
    private static final DateTimeFormatter BACKUP_TS = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    static {
        try {
            Class.forName("org.sqlite.JDBC");
        } catch (ClassNotFoundException e) {
            throw new RuntimeException("SQLite JDBC driver not found", e);
        }
    }

    public static void initDatabase() {
        try {
            Files.createDirectories(DB_PATH.getParent());

            // Migration/dev convenience:
            // If you previously ran the app using a relative path, copy that DB into the new
            // per-user location on first run so you don't lose existing data.
            if (Files.notExists(DB_PATH)) {
                // Migration: copy any seed DB from project into per-user location
                Path legacyProjectDb = Path.of("database", "idgenerator.db");
                Path legacyRootDb = Path.of("idgenerator.db");
                if (Files.exists(legacyProjectDb)) {
                    Files.copy(legacyProjectDb, DB_PATH);
                } else if (Files.exists(legacyRootDb)) {
                    Files.copy(legacyRootDb, DB_PATH);
                }
            }
        } catch (Exception e) {
            throw new RuntimeException("Failed to create database directory: " + DB_PATH, e);
        }

        try (Connection conn = DriverManager.getConnection(DB_URL);
             Statement stmt = conn.createStatement()) {

            stmt.execute("""
                CREATE TABLE IF NOT EXISTS id_cards (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    qr_uid TEXT UNIQUE,
                    photo_path TEXT,
                    name TEXT, id_number TEXT, position TEXT, department TEXT, date_of_birth TEXT,
                    contact_number TEXT, address TEXT,
                    emergency_name TEXT, emergency_contact TEXT
                )
                """);
            // Migration: add qr_uid if table existed without it
            try {
                stmt.execute("ALTER TABLE id_cards ADD COLUMN qr_uid TEXT");
            } catch (SQLException e) { /* column already exists */ }
            // Migration: add position, department if missing
            try {
                stmt.execute("ALTER TABLE id_cards ADD COLUMN position TEXT");
            } catch (SQLException e) { /* column already exists */ }
            try {
                stmt.execute("ALTER TABLE id_cards ADD COLUMN department TEXT");
            } catch (SQLException e) { /* column already exists */ }
            // Backfill: assign UUIDs to rows missing qr_uid
            try (PreparedStatement sel = conn.prepareStatement("SELECT id FROM id_cards WHERE qr_uid IS NULL OR qr_uid = ''");
                 ResultSet rs = sel.executeQuery()) {
                PreparedStatement upd = conn.prepareStatement("UPDATE id_cards SET qr_uid = ? WHERE id = ?");
                while (rs.next()) {
                    upd.setString(1, UUID.randomUUID().toString());
                    upd.setInt(2, rs.getInt("id"));
                    upd.executeUpdate();
                }
            }
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS design_template (
                    id INTEGER PRIMARY KEY CHECK (id = 1),
                    reference_front_path TEXT,
                    reference_back_path TEXT
                )
                """);
            stmt.execute("INSERT OR IGNORE INTO design_template (id, reference_front_path, reference_back_path) VALUES (1, '', '')");
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS template_layout (
                    side TEXT NOT NULL,
                    field_name TEXT NOT NULL,
                    x REAL NOT NULL, y REAL NOT NULL, w REAL NOT NULL, h REAL NOT NULL,
                    PRIMARY KEY (side, field_name)
                )
                """);
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS template_style (
                    side TEXT NOT NULL,
                    field_name TEXT NOT NULL,
                    font_size INTEGER NOT NULL DEFAULT 12,
                    bold INTEGER NOT NULL DEFAULT 0,
                    align TEXT NOT NULL DEFAULT 'Center',
                    locked INTEGER NOT NULL DEFAULT 0,
                    visible INTEGER NOT NULL DEFAULT 1,
                    PRIMARY KEY (side, field_name)
                )
                """);
            try {
                stmt.execute("ALTER TABLE template_style ADD COLUMN photo_circular INTEGER NOT NULL DEFAULT 0");
            } catch (SQLException e) { /* column already exists */ }

            stmt.execute("""
                CREATE TABLE IF NOT EXISTS custom_fields (
                    field_key TEXT PRIMARY KEY,
                    display_name TEXT NOT NULL,
                    show_front INTEGER NOT NULL DEFAULT 1,
                    show_back INTEGER NOT NULL DEFAULT 1,
                    active INTEGER NOT NULL DEFAULT 1
                )
                """);
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS id_card_custom_values (
                    record_id INTEGER NOT NULL,
                    field_key TEXT NOT NULL,
                    field_value TEXT,
                    PRIMARY KEY (record_id, field_key)
                )
                """);

            // Clean up legacy seeded "custom" fields so new installs start with only defaults,
            // but do not delete fields that already have stored values.
            cleanupLegacySeedCustomFields(conn);

            // Ensure AUTOINCREMENT continues from imported/max id (portable backups).
            // If sqlite_sequence is missing for any reason, this is best-effort.
            try (Statement s2 = conn.createStatement()) {
                s2.executeUpdate("INSERT INTO sqlite_sequence(name, seq) SELECT 'id_cards', COALESCE(MAX(id),0) FROM id_cards " +
                        "WHERE NOT EXISTS (SELECT 1 FROM sqlite_sequence WHERE name='id_cards')");
                s2.executeUpdate("UPDATE sqlite_sequence SET seq = (SELECT COALESCE(MAX(id),0) FROM id_cards) WHERE name='id_cards'");
            } catch (SQLException ignored) {
                // best-effort; some SQLite configs may not expose sqlite_sequence yet
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to init database", e);
        }
    }

    private static void cleanupLegacySeedCustomFields(Connection conn) {
        try {
            // Names that were previously used as "custom" seed fields.
            String[] legacyNames = new String[] {
                "Position/ Role",
                "Department / Unit",
                "Validity / Expiry Date",
                "Contact Info",
                "Address or Notes"
            };

            for (String name : legacyNames) {
                if (name == null || name.isBlank()) continue;
                // Find active field keys with this display name.
                try (PreparedStatement sel = conn.prepareStatement(
                    "SELECT field_key FROM custom_fields WHERE active = 1 AND display_name = ?"
                )) {
                    sel.setString(1, name);
                    try (ResultSet rs = sel.executeQuery()) {
                        while (rs.next()) {
                            String key = rs.getString(1);
                            if (key == null || key.isBlank()) continue;
                            // Only deactivate if it has no stored values anywhere.
                            boolean hasValues = false;
                            try (PreparedStatement chk = conn.prepareStatement(
                                "SELECT 1 FROM id_card_custom_values WHERE field_key = ? LIMIT 1"
                            )) {
                                chk.setString(1, key);
                                try (ResultSet r2 = chk.executeQuery()) {
                                    hasValues = r2.next();
                                }
                            }
                            if (!hasValues) {
                                try (PreparedStatement upd = conn.prepareStatement(
                                    "UPDATE custom_fields SET active = 0 WHERE field_key = ?"
                                )) {
                                    upd.setString(1, key);
                                    upd.executeUpdate();
                                }
                                // Also remove any template placement/styles for this field.
                                String marker = customFieldMarker(key);
                                try (PreparedStatement del = conn.prepareStatement("DELETE FROM template_layout WHERE field_name = ?")) {
                                    del.setString(1, marker);
                                    del.executeUpdate();
                                }
                                try (PreparedStatement del = conn.prepareStatement("DELETE FROM template_style WHERE field_name = ?")) {
                                    del.setString(1, marker);
                                    del.executeUpdate();
                                }
                            }
                        }
                    }
                }
            }
        } catch (SQLException ignored) {
            // best-effort cleanup
        }
    }

    // --- ID Cards ---

    /**
     * Returns the highest trailing sequence number (NNNN) for IDs matching {@code PREFIX-YYYYMMDD-NNNN}.
     * Used so new IDs never duplicate existing ones for the same prefix and date.
     */
    public static int maxSequenceForPrefixAndDate(String prefix, String yyyymmdd) {
        if (prefix == null || prefix.isBlank() || yyyymmdd == null || yyyymmdd.length() != 8) {
            return 0;
        }
        String p = prefix.trim().toUpperCase();
        String expectedPrefix = p + "-" + yyyymmdd + "-";
        String sql = "SELECT id_number FROM id_cards WHERE id_number LIKE ?";
        String like = p + "-" + yyyymmdd + "-%";
        int max = 0;
        try (Connection conn = DriverManager.getConnection(DB_URL);
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, like);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    int n = parseTrailingSequence(rs.getString(1), expectedPrefix);
                    if (n > max) {
                        max = n;
                    }
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to query max ID sequence", e);
        }
        return max;
    }

    private static int parseTrailingSequence(String idNumber, String expectedPrefix) {
        if (idNumber == null || !idNumber.startsWith(expectedPrefix)) {
            return 0;
        }
        String tail = idNumber.substring(expectedPrefix.length());
        try {
            return Integer.parseInt(tail);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /**
     * Legacy support: IDs matching {@code PREFIX-YYYYNN} (e.g. {@code DAR-202601}).
     * Used when an existing database already contains legacy IDs so Excel imports don't restart at 01.
     */
    public static boolean hasLegacyYearIds(String prefix, int year) {
        if (prefix == null || prefix.isBlank() || year < 1000) return false;
        String p = prefix.trim().toUpperCase();
        String yearPrefix = p + "-" + year;
        int expectedLen = yearPrefix.length() + 2; // NN
        String sql = "SELECT 1 FROM id_cards WHERE id_number LIKE ? AND length(id_number) = ? LIMIT 1";
        try (Connection conn = DriverManager.getConnection(DB_URL);
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, yearPrefix + "%");
            ps.setInt(2, expectedLen);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        } catch (SQLException e) {
            return false;
        }
    }

    /** Returns max NN for legacy {@code PREFIX-YYYYNN}. */
    public static int maxSequenceForPrefixAndYear(String prefix, int year) {
        if (prefix == null || prefix.isBlank() || year < 1000) return 0;
        String p = prefix.trim().toUpperCase();
        String yearPrefix = p + "-" + year;
        int expectedLen = yearPrefix.length() + 2;
        String sql = "SELECT id_number FROM id_cards WHERE id_number LIKE ? AND length(id_number) = ?";
        int max = 0;
        try (Connection conn = DriverManager.getConnection(DB_URL);
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, yearPrefix + "%");
            ps.setInt(2, expectedLen);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String idNumber = rs.getString(1);
                    if (idNumber == null || !idNumber.startsWith(yearPrefix)) continue;
                    String tail = idNumber.substring(yearPrefix.length());
                    try {
                        int n = Integer.parseInt(tail);
                        if (n > max) max = n;
                    } catch (NumberFormatException ignored) { }
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to query max legacy ID sequence", e);
        }
        return max;
    }

    public static List<IdRecord> getAllIdRecords() {
        List<IdRecord> list = new ArrayList<>();
        String sql = "SELECT id, qr_uid, photo_path, name, id_number, position, department, date_of_birth, contact_number, address, emergency_name, emergency_contact FROM id_cards ORDER BY id";
        try (Connection conn = DriverManager.getConnection(DB_URL);
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            while (rs.next()) {
                list.add(rowToRecord(rs));
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to load ID cards", e);
        }
        return list;
    }

    public static IdRecord getIdRecord(int id) {
        String sql = "SELECT id, qr_uid, photo_path, name, id_number, position, department, date_of_birth, contact_number, address, emergency_name, emergency_contact FROM id_cards WHERE id = ?";
        try (Connection conn = DriverManager.getConnection(DB_URL);
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return rowToRecord(rs);
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to load ID card", e);
        }
        return null;
    }

    public static IdRecord getIdRecordByQrUid(String qrUid) {
        if (qrUid == null || qrUid.isBlank()) return null;
        String sql = "SELECT id, qr_uid, photo_path, name, id_number, position, department, date_of_birth, contact_number, address, emergency_name, emergency_contact FROM id_cards WHERE qr_uid = ?";
        try (Connection conn = DriverManager.getConnection(DB_URL);
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, qrUid.trim());
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return rowToRecord(rs);
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to load ID card by qr_uid", e);
        }
        return null;
    }

    private static IdRecord rowToRecord(ResultSet rs) throws SQLException {
        return new IdRecord(
            rs.getInt("id"),
            nullToEmpty(rs.getString("qr_uid")),
            nullToEmpty(rs.getString("photo_path")),
            nullToEmpty(rs.getString("name")),
            nullToEmpty(rs.getString("id_number")),
            nullToEmpty(rs.getString("position")),
            nullToEmpty(rs.getString("department")),
            nullToEmpty(rs.getString("date_of_birth")),
            nullToEmpty(rs.getString("contact_number")),
            nullToEmpty(rs.getString("address")),
            nullToEmpty(rs.getString("emergency_name")),
            nullToEmpty(rs.getString("emergency_contact"))
        );
    }

    public static IdRecord insertIdRecord(IdRecord r) {
        String qrUid = UUID.randomUUID().toString();
        String sql = "INSERT INTO id_cards (qr_uid, photo_path, name, id_number, position, department, date_of_birth, contact_number, address, emergency_name, emergency_contact) VALUES (?,?,?,?,?,?,?,?,?,?,?)";
        try (Connection conn = DriverManager.getConnection(DB_URL);
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, qrUid);
            setRecordParams(ps, r, 2);
            ps.executeUpdate();
            try (Statement stmt = conn.createStatement();
                 ResultSet rs = stmt.executeQuery("SELECT last_insert_rowid() AS id")) {
                if (rs.next()) {
                    int newId = rs.getInt("id");
                    return new IdRecord(newId, qrUid, r.photoPath(), r.name(), r.idNumber(), r.position(), r.department(), r.dateOfBirth(),
                        r.contactNumber(), r.address(), r.emergencyName(), r.emergencyContact());
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to insert ID card", e);
        }
        throw new RuntimeException("Insert succeeded but could not read new id");
    }

    public static void updateIdRecord(IdRecord r) {
        String sql = "UPDATE id_cards SET photo_path=?, name=?, id_number=?, position=?, department=?, date_of_birth=?, contact_number=?, address=?, emergency_name=?, emergency_contact=? WHERE id=?";
        try (Connection conn = DriverManager.getConnection(DB_URL);
             PreparedStatement ps = conn.prepareStatement(sql)) {
            setRecordParams(ps, r, 1);
            ps.setInt(11, r.id());
            if (ps.executeUpdate() != 1) {
                throw new RuntimeException("No row updated for id=" + r.id());
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to update ID card", e);
        }
    }

    public static void deleteIdRecord(int id) {
        try (Connection conn = DriverManager.getConnection(DB_URL);
             PreparedStatement ps = conn.prepareStatement("DELETE FROM id_cards WHERE id = ?")) {
            ps.setInt(1, id);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to delete ID card", e);
        }
    }

    /** Replaces all id_cards with the given list (for sync restore). Preserves ids and qr_uids. */
    public static void replaceAllIdCards(List<IdRecord> records) {
        if (records == null || records.isEmpty()) return;
        String delSql = "DELETE FROM id_cards";
        String insSql = "INSERT INTO id_cards (id, qr_uid, photo_path, name, id_number, position, department, date_of_birth, contact_number, address, emergency_name, emergency_contact) VALUES (?,?,?,?,?,?,?,?,?,?,?,?)";
        try (Connection conn = DriverManager.getConnection(DB_URL);
             PreparedStatement del = conn.prepareStatement(delSql);
             PreparedStatement ins = conn.prepareStatement(insSql)) {
            del.executeUpdate();
            for (IdRecord r : records) {
                String qrUid = (r.qrUid() != null && !r.qrUid().isBlank()) ? r.qrUid() : UUID.randomUUID().toString();
                ins.setInt(1, r.id());
                ins.setString(2, qrUid);
                ins.setString(3, r.photoPath());
                ins.setString(4, r.name());
                ins.setString(5, r.idNumber());
                ins.setString(6, r.position());
                ins.setString(7, r.department());
                ins.setString(8, r.dateOfBirth());
                ins.setString(9, r.contactNumber());
                ins.setString(10, r.address());
                ins.setString(11, r.emergencyName());
                ins.setString(12, r.emergencyContact());
                ins.executeUpdate();
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to replace ID cards", e);
        }
    }

    private static void setRecordParams(PreparedStatement ps, IdRecord r, int startIndex) throws SQLException {
        ps.setString(startIndex, r.photoPath());
        ps.setString(startIndex + 1, r.name());
        ps.setString(startIndex + 2, r.idNumber());
        ps.setString(startIndex + 3, r.position());
        ps.setString(startIndex + 4, r.department());
        ps.setString(startIndex + 5, r.dateOfBirth());
        ps.setString(startIndex + 6, r.contactNumber());
        ps.setString(startIndex + 7, r.address());
        ps.setString(startIndex + 8, r.emergencyName());
        ps.setString(startIndex + 9, r.emergencyContact());
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }

    // --- Reference design (template) ---

    public static void setReferenceDesign(String frontPath, String backPath) {
        String sql = "UPDATE design_template SET reference_front_path = ?, reference_back_path = ? WHERE id = 1";
        try (Connection conn = DriverManager.getConnection(DB_URL);
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, frontPath != null ? frontPath : "");
            ps.setString(2, backPath != null ? backPath : "");
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to save reference design", e);
        }
    }

    public static String[] getReferenceDesign() {
        String sql = "SELECT reference_front_path, reference_back_path FROM design_template WHERE id = 1";
        try (Connection conn = DriverManager.getConnection(DB_URL);
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            if (rs.next()) {
                return new String[]{ nullToEmpty(rs.getString(1)), nullToEmpty(rs.getString(2)) };
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to load reference design", e);
        }
        return new String[]{ "", "" };
    }

    // --- Template layout (field positions on template) ---

    public static java.util.List<TemplateRegion> getTemplateLayout() {
        java.util.List<TemplateRegion> list = new ArrayList<>();
        // Preserve insertion order so z-index ("To Front/To Back") matches designer.
        String sql = "SELECT side, field_name, x, y, w, h FROM template_layout ORDER BY rowid";
        try (Connection conn = DriverManager.getConnection(DB_URL);
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            while (rs.next()) {
                list.add(new TemplateRegion(
                    rs.getString("side"),
                    rs.getString("field_name"),
                    rs.getDouble("x"),
                    rs.getDouble("y"),
                    rs.getDouble("w"),
                    rs.getDouble("h")
                ));
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to load template layout", e);
        }
        return list;
    }

    public static void saveTemplateLayout(java.util.List<TemplateRegion> regions) {
        String delSql = "DELETE FROM template_layout";
        String insSql = "INSERT OR REPLACE INTO template_layout (side, field_name, x, y, w, h) VALUES (?,?,?,?,?,?)";
        try (Connection conn = DriverManager.getConnection(DB_URL);
             PreparedStatement del = conn.prepareStatement(delSql);
             PreparedStatement ins = conn.prepareStatement(insSql)) {
            del.executeUpdate();
            for (TemplateRegion r : regions) {
                ins.setString(1, r.side());
                ins.setString(2, r.fieldName());
                ins.setDouble(3, r.x());
                ins.setDouble(4, r.y());
                ins.setDouble(5, r.width());
                ins.setDouble(6, r.height());
                ins.executeUpdate();
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to save template layout", e);
        }
    }

    public static java.util.List<TemplateFieldStyle> getTemplateStyles() {
        java.util.List<TemplateFieldStyle> list = new ArrayList<>();
        // Keep deterministic ordering for debug/consistency.
        String sql = "SELECT side, field_name, font_size, bold, align, locked, visible, photo_circular FROM template_style ORDER BY rowid";
        try (Connection conn = DriverManager.getConnection(DB_URL);
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            while (rs.next()) {
                int photoCirc;
                try {
                    photoCirc = rs.getInt("photo_circular");
                } catch (SQLException ex) {
                    photoCirc = 0;
                }
                list.add(new TemplateFieldStyle(
                    rs.getString("side"),
                    rs.getString("field_name"),
                    rs.getInt("font_size"),
                    rs.getInt("bold") != 0,
                    nullToEmpty(rs.getString("align")).isBlank() ? "Center" : rs.getString("align"),
                    rs.getInt("locked") != 0,
                    rs.getInt("visible") != 0,
                    photoCirc != 0
                ));
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to load template styles", e);
        }
        return list;
    }

    public static void saveTemplateStyles(java.util.List<TemplateFieldStyle> styles) {
        String delSql = "DELETE FROM template_style";
        String insSql = "INSERT OR REPLACE INTO template_style (side, field_name, font_size, bold, align, locked, visible, photo_circular) VALUES (?,?,?,?,?,?,?,?)";
        try (Connection conn = DriverManager.getConnection(DB_URL);
             PreparedStatement del = conn.prepareStatement(delSql);
             PreparedStatement ins = conn.prepareStatement(insSql)) {
            del.executeUpdate();
            for (TemplateFieldStyle s : styles) {
                ins.setString(1, s.side());
                ins.setString(2, s.fieldName());
                ins.setInt(3, s.fontSize());
                ins.setInt(4, s.bold() ? 1 : 0);
                ins.setString(5, s.align());
                ins.setInt(6, s.locked() ? 1 : 0);
                ins.setInt(7, s.visible() ? 1 : 0);
                ins.setInt(8, s.photoCircular() ? 1 : 0);
                ins.executeUpdate();
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to save template styles", e);
        }
    }

    // --- Dynamic custom fields ---

    public static List<CustomFieldDef> getActiveCustomFields() {
        List<CustomFieldDef> list = new ArrayList<>();
        String sql = "SELECT field_key, display_name, show_front, show_back FROM custom_fields WHERE active = 1 ORDER BY rowid";
        try (Connection conn = DriverManager.getConnection(DB_URL);
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            while (rs.next()) {
                list.add(new CustomFieldDef(
                    nullToEmpty(rs.getString("field_key")),
                    nullToEmpty(rs.getString("display_name")),
                    rs.getInt("show_front") != 0,
                    rs.getInt("show_back") != 0,
                    false
                ));
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to load custom fields", e);
        }
        return list;
    }

    public static CustomFieldDef addCustomField(String displayName, boolean showFront, boolean showBack) {
        String clean = displayName == null ? "" : displayName.trim();
        if (clean.isEmpty()) throw new RuntimeException("Field name cannot be empty");
        if (!showFront && !showBack) throw new RuntimeException("Select Front and/or Back for the field");
        String key = "cf_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        String sql = "INSERT INTO custom_fields (field_key, display_name, show_front, show_back, active) VALUES (?,?,?,?,1)";
        try (Connection conn = DriverManager.getConnection(DB_URL);
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, key);
            ps.setString(2, clean);
            ps.setInt(3, showFront ? 1 : 0);
            ps.setInt(4, showBack ? 1 : 0);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to add custom field", e);
        }
        return new CustomFieldDef(key, clean, showFront, showBack, false);
    }

    public static void updateCustomField(String fieldKey, String displayName, boolean showFront, boolean showBack) {
        if (fieldKey == null || fieldKey.isBlank()) throw new RuntimeException("Missing field key");
        String clean = displayName == null ? "" : displayName.trim();
        if (clean.isEmpty()) throw new RuntimeException("Field name cannot be empty");
        if (!showFront && !showBack) throw new RuntimeException("Select Front and/or Back for the field");
        String sql = "UPDATE custom_fields SET display_name = ?, show_front = ?, show_back = ? WHERE field_key = ? AND active = 1";
        try (Connection conn = DriverManager.getConnection(DB_URL);
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, clean);
            ps.setInt(2, showFront ? 1 : 0);
            ps.setInt(3, showBack ? 1 : 0);
            ps.setString(4, fieldKey);
            ps.executeUpdate();
            String marker = customFieldMarker(fieldKey);
            if (!showFront) {
                try (PreparedStatement del = conn.prepareStatement("DELETE FROM template_layout WHERE side = ? AND field_name = ?")) {
                    del.setString(1, TemplateRegion.SIDE_FRONT);
                    del.setString(2, marker);
                    del.executeUpdate();
                }
                try (PreparedStatement del = conn.prepareStatement("DELETE FROM template_style WHERE side = ? AND field_name = ?")) {
                    del.setString(1, TemplateRegion.SIDE_FRONT);
                    del.setString(2, marker);
                    del.executeUpdate();
                }
            }
            if (!showBack) {
                try (PreparedStatement del = conn.prepareStatement("DELETE FROM template_layout WHERE side = ? AND field_name = ?")) {
                    del.setString(1, TemplateRegion.SIDE_BACK);
                    del.setString(2, marker);
                    del.executeUpdate();
                }
                try (PreparedStatement del = conn.prepareStatement("DELETE FROM template_style WHERE side = ? AND field_name = ?")) {
                    del.setString(1, TemplateRegion.SIDE_BACK);
                    del.setString(2, marker);
                    del.executeUpdate();
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to update custom field", e);
        }
    }

    public static void deactivateCustomField(String fieldKey) {
        if (fieldKey == null || fieldKey.isBlank()) return;
        String marker = customFieldMarker(fieldKey);
        try (Connection conn = DriverManager.getConnection(DB_URL)) {
            try (PreparedStatement ps = conn.prepareStatement("UPDATE custom_fields SET active = 0 WHERE field_key = ?")) {
                ps.setString(1, fieldKey);
                ps.executeUpdate();
            }
            try (PreparedStatement ps = conn.prepareStatement("DELETE FROM template_layout WHERE field_name = ?")) {
                ps.setString(1, marker);
                ps.executeUpdate();
            }
            try (PreparedStatement ps = conn.prepareStatement("DELETE FROM template_style WHERE field_name = ?")) {
                ps.setString(1, marker);
                ps.executeUpdate();
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to deactivate custom field", e);
        }
    }

    public static String customFieldMarker(String fieldKey) {
        return "custom:" + (fieldKey == null ? "" : fieldKey.trim());
    }

    public static boolean isCustomFieldMarker(String fieldName) {
        return fieldName != null && fieldName.startsWith("custom:");
    }

    public static String fieldKeyFromMarker(String fieldName) {
        if (!isCustomFieldMarker(fieldName)) return "";
        return fieldName.substring("custom:".length());
    }

    public static Map<String, String> getCustomFieldLabelsByKey() {
        Map<String, String> map = new LinkedHashMap<>();
        String sql = "SELECT field_key, display_name FROM custom_fields WHERE active = 1";
        try (Connection conn = DriverManager.getConnection(DB_URL);
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            while (rs.next()) {
                map.put(nullToEmpty(rs.getString("field_key")), nullToEmpty(rs.getString("display_name")));
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to load custom field labels", e);
        }
        return map;
    }

    public static Map<String, String> getCustomValuesForRecord(int recordId) {
        Map<String, String> map = new LinkedHashMap<>();
        if (recordId <= 0) return map;
        String sql = "SELECT field_key, field_value FROM id_card_custom_values WHERE record_id = ?";
        try (Connection conn = DriverManager.getConnection(DB_URL);
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, recordId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    map.put(nullToEmpty(rs.getString("field_key")), nullToEmpty(rs.getString("field_value")));
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to load custom field values", e);
        }
        return map;
    }

    public static void saveCustomValuesForRecord(int recordId, Map<String, String> valuesByKey) {
        if (recordId <= 0) return;
        if (valuesByKey == null || valuesByKey.isEmpty()) return;
        String sql = "INSERT OR REPLACE INTO id_card_custom_values (record_id, field_key, field_value) VALUES (?,?,?)";
        try (Connection conn = DriverManager.getConnection(DB_URL);
             PreparedStatement ps = conn.prepareStatement(sql)) {
            for (Map.Entry<String, String> e : valuesByKey.entrySet()) {
                String key = e.getKey() == null ? "" : e.getKey().trim();
                if (key.isEmpty()) continue;
                ps.setInt(1, recordId);
                ps.setString(2, key);
                ps.setString(3, e.getValue() == null ? "" : e.getValue());
                ps.executeUpdate();
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to save custom field values", e);
        }
    }

    // --- Backup / restore (offline) ---

    public static Path getDatabasePath() {
        return DB_PATH;
    }

    private static String toSqlitePath(Path p) {
        return p.toAbsolutePath().normalize().toString().replace("\\", "/").replace("'", "''");
    }

    /**
     * Writes a consistent copy of the open database using SQLite {@code VACUUM INTO}
     * (fallback: checkpoint + file copy).
     */
    public static void exportDatabaseTo(Path destination) throws SQLException, IOException {
        Objects.requireNonNull(destination, "destination");
        Path abs = destination.toAbsolutePath().normalize();
        Path live = DB_PATH.toAbsolutePath().normalize();
        if (abs.equals(live)) {
            throw new IOException("Cannot export over the active database file.");
        }
        Files.createDirectories(abs.getParent());
        if (Files.exists(abs)) {
            Files.delete(abs);
        }
        try (Connection conn = DriverManager.getConnection(DB_URL);
             Statement stmt = conn.createStatement()) {
            stmt.execute("VACUUM INTO '" + toSqlitePath(abs) + "'");
        } catch (SQLException ignored) {
            try (Connection conn = DriverManager.getConnection(DB_URL);
                 Statement stmt = conn.createStatement()) {
                stmt.execute("PRAGMA wal_checkpoint(TRUNCATE)");
            }
            Files.copy(live, abs, StandardCopyOption.REPLACE_EXISTING);
        }
        if (!Files.exists(abs) || Files.size(abs) < 1) {
            throw new IOException("Export produced an empty or missing file.");
        }
    }

    /**
     * Verifies that {@code file} is a readable SQLite DB with required tables.
     */
    public static void validateImportFile(Path file) throws SQLException, IOException {
        Objects.requireNonNull(file, "file");
        if (!Files.isRegularFile(file)) {
            throw new IOException("Select a valid database file.");
        }
        if (Files.size(file) < 100) {
            throw new IOException("File is too small to be a SQLite database.");
        }
        String url = "jdbc:sqlite:" + toSqlitePath(file);
        try (Connection conn = DriverManager.getConnection(url);
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("PRAGMA quick_check")) {
            if (!rs.next()) {
                throw new IOException("Could not verify database integrity.");
            }
            String result = rs.getString(1);
            if (result == null || !result.equalsIgnoreCase("ok")) {
                throw new IOException("Database appears corrupted or invalid"
                    + (result != null && !result.isBlank() ? (": " + result) : "") + ".");
            }
        }
        try (Connection conn = DriverManager.getConnection(url);
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(
                 "SELECT COUNT(*) FROM sqlite_master WHERE type='table' AND name IN ('id_cards','design_template')")) {
            if (!rs.next() || rs.getInt(1) < 2) {
                throw new IOException("This file is not an ID Generator backup (missing id_cards or design_template).");
            }
        }
    }

    private static Path timestampedCopy(Path source, String filenamePrefix) throws IOException {
        Files.createDirectories(source.getParent());
        Path dest = source.getParent().resolve(filenamePrefix + BACKUP_TS.format(LocalDateTime.now()) + ".db");
        Files.copy(source, dest);
        return dest;
    }

    private static void checkpointLiveDatabase() throws SQLException {
        try (Connection conn = DriverManager.getConnection(DB_URL);
             Statement stmt = conn.createStatement()) {
            stmt.execute("PRAGMA wal_checkpoint(TRUNCATE)");
        }
    }

    /**
     * Replaces the live database with {@code source}. Creates a timestamped backup of the
     * current file when present. Runs {@link #initDatabase()} on success. Rolls back from
     * backup if the replace or init fails.
     */
    public static Path importDatabaseReplacing(Path source) throws SQLException, IOException {
        Objects.requireNonNull(source, "source");
        validateImportFile(source);
        Path normalizedSource = source.toAbsolutePath().normalize();
        Path live = DB_PATH.toAbsolutePath().normalize();
        if (normalizedSource.equals(live)) {
            throw new IOException("Cannot import from the active database file. Choose a different copy.");
        }

        Files.createDirectories(DB_PATH.getParent());
        Path backupOfLive = null;
        if (Files.exists(DB_PATH)) {
            backupOfLive = timestampedCopy(DB_PATH, "idgenerator-preimport-");
        }

        try {
            checkpointLiveDatabase();
            Files.copy(normalizedSource, DB_PATH, StandardCopyOption.REPLACE_EXISTING);
            initDatabase();
            return backupOfLive;
        } catch (Exception e) {
            if (backupOfLive != null && Files.exists(backupOfLive)) {
                try {
                    Files.copy(backupOfLive, DB_PATH, StandardCopyOption.REPLACE_EXISTING);
                    initDatabase();
                } catch (Exception restoreEx) {
                    e.addSuppressed(restoreEx);
                }
            }
            if (e instanceof IOException ioe) {
                throw ioe;
            }
            if (e instanceof SQLException sqle) {
                throw sqle;
            }
            throw new IOException(e.getMessage() != null ? e.getMessage() : "Import failed", e);
        }
    }

    public static void relinkImportedReferencePaths(Path referenceDir) {
        if (referenceDir == null) return;
        try {
            String[] cur = getReferenceDesign();
            String front = relinkPathByFilename(cur[0], referenceDir);
            String back = relinkPathByFilename(cur[1], referenceDir);
            setReferenceDesign(front, back);
        } catch (Exception ignored) {
            // best effort relink
        }
    }

    public static void relinkImportedPhotoPaths(Path photosDir) {
        if (photosDir == null || !Files.isDirectory(photosDir)) return;
        String sql = "SELECT id, photo_path FROM id_cards";
        String upd = "UPDATE id_cards SET photo_path = ? WHERE id = ?";
        try (Connection conn = DriverManager.getConnection(DB_URL);
             Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(sql);
             PreparedStatement ps = conn.prepareStatement(upd)) {
            while (rs.next()) {
                int id = rs.getInt("id");
                String current = nullToEmpty(rs.getString("photo_path"));
                String relinked = relinkPathByFilename(current, photosDir);
                if (!relinked.equals(current)) {
                    ps.setString(1, relinked);
                    ps.setInt(2, id);
                    ps.executeUpdate();
                }
            }
        } catch (SQLException ignored) {
            // best effort relink
        }
    }

    private static String relinkPathByFilename(String rawPath, Path newBaseDir) {
        String p = nullToEmpty(rawPath).trim();
        if (p.isEmpty()) return "";
        try {
            Path old = Path.of(p);
            if (Files.isRegularFile(old)) {
                return old.toAbsolutePath().toString();
            }
            String fileName = old.getFileName() != null ? old.getFileName().toString() : "";
            if (fileName.isBlank()) return p;
            Path candidate = newBaseDir.resolve(fileName);
            if (Files.isRegularFile(candidate)) {
                return candidate.toAbsolutePath().toString();
            }
        } catch (Exception ignored) {
            // keep old path
        }
        return p;
    }
}
