package org.financial;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Objects;

/**
 * Generates unique display IDs.
 *
 * Supported formats:
 * - {@code PREFIX-YYYYMMDD-NNNN} (e.g. {@code DAR-20260323-0001})
 * - legacy {@code PREFIX-YYYYNN} (e.g. {@code DAR-202601})
 *
 * Sequence is allocated from the database so manual IDs and batch imports do not collide.
 * Excel and other parsers must not supply IDs; only this class (and manual edits in the form) set {@code id_number}.
 */
public final class IdNumberGenerator {

    private static final DateTimeFormatter DATE_COMPACT = DateTimeFormatter.BASIC_ISO_DATE;

    private final String prefix;
    private final LocalDate date;
    private int nextSeq;
    private final Style style;

    private enum Style {
        DATE_DAILY_4,  // PREFIX-YYYYMMDD-NNNN
        YEAR_2         // PREFIX-YYYYNN
    }

    /**
     * @param prefix uppercase letters/digits (e.g. DAR); normalized to uppercase
     * @param date   date embedded in the ID (typically today)
     */
    public IdNumberGenerator(String prefix, LocalDate date) {
        this.prefix = normalizePrefix(prefix);
        this.date = Objects.requireNonNull(date, "date");
        String ymd = date.format(DATE_COMPACT);
        int year = date.getYear();

        // Backward compatibility: if the database already contains legacy IDs for this prefix+year,
        // keep issuing legacy IDs so Excel import starts after the user's manual IDs.
        if (DbHelper.hasLegacyYearIds(this.prefix, year)) {
            this.style = Style.YEAR_2;
            this.nextSeq = DbHelper.maxSequenceForPrefixAndYear(this.prefix, year) + 1;
        } else {
            this.style = Style.DATE_DAILY_4;
            this.nextSeq = DbHelper.maxSequenceForPrefixAndDate(this.prefix, ymd) + 1;
        }
    }

    /** Next ID and advance in-memory counter (for consecutive rows in one batch before DB reflects inserts). */
    public String next() {
        String id = format(prefix, date, nextSeq, style);
        nextSeq++;
        return id;
    }

    /** Next ID that would be issued for this prefix/date, without consuming a sequence (preview only). */
    public static String previewNextId(String prefix, LocalDate date) {
        String p = normalizePrefix(prefix);
        String ymd = date.format(DATE_COMPACT);
        int year = date.getYear();
        if (DbHelper.hasLegacyYearIds(p, year)) {
            int seq = DbHelper.maxSequenceForPrefixAndYear(p, year) + 1;
            return format(p, date, seq, Style.YEAR_2);
        }
        int seq = DbHelper.maxSequenceForPrefixAndDate(p, ymd) + 1;
        return format(p, date, seq, Style.DATE_DAILY_4);
    }

    public static String format(String prefix, LocalDate date, int sequence) {
        return format(prefix, date, sequence, Style.DATE_DAILY_4);
    }

    private static String format(String prefix, LocalDate date, int sequence, Style style) {
        String p = normalizePrefix(prefix);
        if (style == Style.YEAR_2) {
            int year = date.getYear();
            return p + "-" + year + String.format("%02d", sequence);
        }
        return p + "-" + date.format(DATE_COMPACT) + "-" + String.format("%04d", sequence);
    }

    private static String normalizePrefix(String prefix) {
        if (prefix == null || prefix.isBlank()) {
            return "ORG";
        }
        return prefix.trim().toUpperCase();
    }
}
