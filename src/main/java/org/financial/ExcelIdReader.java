package org.financial;

import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.HorizontalAlignment;
import org.apache.poi.ss.usermodel.IndexedColors;
import org.apache.poi.ss.usermodel.VerticalAlignment;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.BorderStyle;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.DataFormat;
import org.apache.poi.ss.usermodel.ConditionalFormattingRule;
import org.apache.poi.ss.usermodel.PatternFormatting;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.ss.util.CellRangeAddress;

import java.io.BufferedInputStream;
import java.io.OutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Reads .xlsx spreadsheets: first row = headers, mapped dynamically by normalized name.
 * Display IDs are never read from Excel; only {@code name}, {@code photo_path}, and optional fields below.
 * Unknown columns are ignored.
 */
public final class ExcelIdReader {

    public static final Set<String> KNOWN_FIELDS = Set.of(
        "name",
        "photo_path"
    );

    private final DataFormatter formatter = new DataFormatter();

    public ExcelParseResult read(Path xlsxFile) throws IOException {
        List<ParsedRow> rows = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        Map<String, Integer> colByField = Map.of();

        try (BufferedInputStream in = new BufferedInputStream(Files.newInputStream(xlsxFile));
             Workbook wb = WorkbookFactory.create(in)) {
            Sheet sheet = wb.getSheetAt(0);
            if (sheet == null) {
                errors.add("Workbook has no sheets.");
                return new ExcelParseResult(List.of(), Map.of(), errors);
            }

            Row headerRow = sheet.getRow(sheet.getFirstRowNum());
            if (headerRow == null) {
                errors.add("First row (headers) is missing.");
                return new ExcelParseResult(List.of(), Map.of(), errors);
            }

            colByField = mapHeaders(headerRow);
            if (!colByField.containsKey("name")) {
                errors.add("Required header 'name' not found.");
            }
            if (!colByField.containsKey("photo_path")) {
                errors.add("Required header 'photo_path' not found.");
            }

            if (!errors.isEmpty()) {
                return new ExcelParseResult(List.of(), colByField, errors);
            }

            int last = sheet.getLastRowNum();
            for (int r = sheet.getFirstRowNum() + 1; r <= last; r++) {
                Row row = sheet.getRow(r);
                if (row == null) {
                    continue;
                }
                if (isRowEmpty(row, colByField.values())) {
                    continue;
                }
                Map<String, String> values = new LinkedHashMap<>();
                for (Map.Entry<String, Integer> e : colByField.entrySet()) {
                    Cell c = row.getCell(e.getValue());
                    values.put(e.getKey(), cellString(c));
                }
                rows.add(new ParsedRow(r + 1, values));
            }
        }

        return new ExcelParseResult(rows, colByField, errors);
    }

    private Map<String, Integer> mapHeaders(Row headerRow) {
        Map<String, Integer> map = new HashMap<>();
        short lastCell = headerRow.getLastCellNum();
        java.util.List<CustomFieldDef> activeCustom = DbHelper.getActiveCustomFields();
        Map<String, String> normalizedCustomDisplayToKey = new HashMap<>();
        for (CustomFieldDef cf : activeCustom) {
            if (cf == null || cf.displayName() == null || cf.displayName().isBlank()) continue;
            normalizedCustomDisplayToKey.put(normalizeHeader(cf.displayName()), cf.fieldKey());
        }

        for (int c = 0; c < lastCell; c++) {
            Cell cell = headerRow.getCell(c);
            String raw = cellString(cell);
            if (raw.isEmpty()) continue;

            String rawTrim = raw.trim();
            String rawLower = rawTrim.toLowerCase(Locale.ROOT);

            // Default fields
            String key = normalizeHeader(rawTrim);
            if ("full_name".equals(key)) {
                map.putIfAbsent("name", c);
                continue;
            }
            if (KNOWN_FIELDS.contains(key) || "photo".equals(key)) {
                String field = "photo".equals(key) ? "photo_path" : key;
                map.putIfAbsent(field, c);
                continue;
            }

            // Dynamic fields: match by display name (no fieldKey shown in template)
            String fieldKey = normalizedCustomDisplayToKey.getOrDefault(key, "");
            if (!fieldKey.isEmpty()) {
                map.putIfAbsent(fieldKey, c);
            }
        }
        return map;
    }

    static String normalizeHeader(String s) {
        if (s == null) {
            return "";
        }
        return s.trim().toLowerCase(Locale.ROOT).replace(' ', '_');
    }

    private boolean isRowEmpty(Row row, Iterable<Integer> cols) {
        for (int c : cols) {
            Cell cell = row.getCell(c);
            if (!cellString(cell).isEmpty()) {
                return false;
            }
        }
        return true;
    }

    private String cellString(Cell cell) {
        if (cell == null) {
            return "";
        }
        CellType type = cell.getCellType();
        if (type == CellType.FORMULA) {
            try {
                return formatter.formatCellValue(cell).trim();
            } catch (Exception e) {
                return "";
            }
        }
        return formatter.formatCellValue(cell).trim();
    }

    public record ParsedRow(int excelRowNumber, Map<String, String> values) {
        public String get(String field) {
            String v = values.get(field);
            return v != null ? v : "";
        }
    }

    public record ExcelParseResult(
        List<ParsedRow> rows,
        Map<String, Integer> columnMap,
        List<String> errors
    ) {
        public boolean isOk() {
            return errors.isEmpty();
        }
    }

    /**
     * Writes a dynamic .xlsx template with columns for:
     * - default/existing known fields
     * - active custom fields (custom headers include the fieldKey for stable import)
     */
    public static void writeTemplate(OutputStream out) throws IOException {
        try (XSSFWorkbook wb = new XSSFWorkbook()) {
            var sheet = wb.createSheet("IDs");

            // Printing margins (Excel uses inches)
            sheet.setMargin(Sheet.LeftMargin, 0.3);
            sheet.setMargin(Sheet.RightMargin, 0.3);
            sheet.setMargin(Sheet.TopMargin, 0.3);
            sheet.setMargin(Sheet.BottomMargin, 0.3);

            // Columns in template (order matters only for display; parsing is header-based)
            java.util.List<String> baseHeaders = java.util.List.of(
                "Full Name",
                "photo_path"
            );
            java.util.List<CustomFieldDef> customFields = DbHelper.getActiveCustomFields();
            java.util.List<String> headersList = new java.util.ArrayList<>(baseHeaders);
            for (CustomFieldDef cf : customFields) {
                // Header uses display name only (no \"Custom\" prefix, no field key).
                headersList.add(cf.displayName());
            }
            String[] headers = headersList.toArray(new String[0]);

            // Styling helpers
            DataFormat dataFormat = wb.createDataFormat();

            Font headerFont = wb.createFont();
            headerFont.setFontName("Calibri");
            headerFont.setFontHeightInPoints((short) 12);
            headerFont.setBold(true);

            CellStyle headerStyle = wb.createCellStyle();
            headerStyle.setFont(headerFont);
            headerStyle.setAlignment(HorizontalAlignment.CENTER);
            headerStyle.setVerticalAlignment(VerticalAlignment.CENTER);
            headerStyle.setFillForegroundColor(IndexedColors.GREY_25_PERCENT.getIndex());
            headerStyle.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            headerStyle.setBorderTop(BorderStyle.THIN);
            headerStyle.setBorderBottom(BorderStyle.MEDIUM);
            headerStyle.setBorderLeft(BorderStyle.THIN);
            headerStyle.setBorderRight(BorderStyle.THIN);

            Font dataFont = wb.createFont();
            dataFont.setFontName("Calibri");
            dataFont.setFontHeightInPoints((short) 11);

            // Data rows: keep clean white background (no zebra gray) and no horizontal row borders.
            // Keep only thin left/right borders so columns remain visually separated.
            CellStyle zebraOdd = wb.createCellStyle();
            zebraOdd.setFont(dataFont);
            zebraOdd.setFillPattern(FillPatternType.NO_FILL);
            zebraOdd.setBorderTop(BorderStyle.NONE);
            zebraOdd.setBorderBottom(BorderStyle.NONE);
            zebraOdd.setBorderLeft(BorderStyle.THIN);
            zebraOdd.setBorderRight(BorderStyle.THIN);

            CellStyle zebraEven = wb.createCellStyle();
            zebraEven.setFont(dataFont);
            zebraEven.setFillPattern(FillPatternType.NO_FILL);
            zebraEven.setBorderTop(BorderStyle.NONE);
            zebraEven.setBorderBottom(BorderStyle.NONE);
            zebraEven.setBorderLeft(BorderStyle.THIN);
            zebraEven.setBorderRight(BorderStyle.THIN);

            // Text styles per column type
            CellStyle textLeftEven = wb.createCellStyle();
            textLeftEven.cloneStyleFrom(zebraEven);
            textLeftEven.setAlignment(HorizontalAlignment.LEFT);
            textLeftEven.setVerticalAlignment(VerticalAlignment.CENTER);

            CellStyle textLeftWrapEven = wb.createCellStyle();
            textLeftWrapEven.cloneStyleFrom(textLeftEven);
            textLeftWrapEven.setWrapText(true);

            CellStyle textLeftWrapOdd = wb.createCellStyle();
            textLeftWrapOdd.cloneStyleFrom(zebraOdd);
            textLeftWrapOdd.setAlignment(HorizontalAlignment.LEFT);
            textLeftWrapOdd.setVerticalAlignment(VerticalAlignment.CENTER);
            textLeftWrapOdd.setWrapText(true);

            CellStyle textLeftOdd = wb.createCellStyle();
            textLeftOdd.cloneStyleFrom(zebraOdd);
            textLeftOdd.setAlignment(HorizontalAlignment.LEFT);
            textLeftOdd.setVerticalAlignment(VerticalAlignment.CENTER);

            CellStyle phoneRightEven = wb.createCellStyle();
            phoneRightEven.cloneStyleFrom(zebraEven);
            phoneRightEven.setAlignment(HorizontalAlignment.RIGHT);
            phoneRightEven.setVerticalAlignment(VerticalAlignment.CENTER);
            phoneRightEven.setDataFormat(dataFormat.getFormat("@")); // keep leading zeros as text

            CellStyle phoneRightOdd = wb.createCellStyle();
            phoneRightOdd.cloneStyleFrom(zebraOdd);
            phoneRightOdd.setAlignment(HorizontalAlignment.RIGHT);
            phoneRightOdd.setVerticalAlignment(VerticalAlignment.CENTER);
            phoneRightOdd.setDataFormat(dataFormat.getFormat("@"));

            CellStyle dateCenterEven = wb.createCellStyle();
            dateCenterEven.cloneStyleFrom(zebraEven);
            dateCenterEven.setAlignment(HorizontalAlignment.CENTER);
            dateCenterEven.setVerticalAlignment(VerticalAlignment.CENTER);
            dateCenterEven.setDataFormat(dataFormat.getFormat("yyyy-MM-dd"));

            CellStyle dateCenterOdd = wb.createCellStyle();
            dateCenterOdd.cloneStyleFrom(zebraOdd);
            dateCenterOdd.setAlignment(HorizontalAlignment.CENTER);
            dateCenterOdd.setVerticalAlignment(VerticalAlignment.CENTER);
            dateCenterOdd.setDataFormat(dataFormat.getFormat("yyyy-MM-dd"));

            // Header row
            Row h = sheet.createRow(0);
            for (int i = 0; i < headers.length; i++) {
                var cell = h.createCell(i);
                cell.setCellValue(headers[i].toUpperCase(Locale.ROOT));
                cell.setCellStyle(headerStyle);
            }

            // Sample row (first data row = row index 1)
            Row sample = sheet.createRow(1);
            int rowIndex0Based = 1;
            boolean odd = (rowIndex0Based % 2) == 1; // zebra based on template rows

            CellStyle sName = odd ? textLeftOdd : textLeftEven;
            CellStyle sPosition = odd ? textLeftOdd : textLeftEven;
            CellStyle sDept = odd ? textLeftOdd : textLeftEven;
            CellStyle sPhoto = odd ? textLeftWrapOdd : textLeftWrapEven;
            CellStyle sDate = odd ? dateCenterOdd : dateCenterEven;
            CellStyle sPhone = odd ? phoneRightOdd : phoneRightEven;
            CellStyle sAddress = odd ? textLeftWrapOdd : textLeftWrapEven;
            CellStyle sEmergencyName = odd ? textLeftOdd : textLeftEven;
            CellStyle sEmergencyPhone = odd ? phoneRightOdd : phoneRightEven;

            LocalDate ld = LocalDate.of(1995, 4, 22);
            java.util.Date date = java.sql.Date.valueOf(ld);

            // Fill sample cells based on header name (custom columns left blank intentionally).
            for (int c = 0; c < headers.length; c++) {
                String header = headers[c];
                String norm = normalizeHeader(header);
                var cell = sample.createCell(c);
                if ("full_name".equals(norm) || "name".equals(norm)) {
                    cell.setCellValue("Juan Dela Cruz");
                    cell.setCellStyle(sName);
                } else if ("photo_path".equals(norm) || "photo".equals(norm)) {
                    cell.setCellValue("C:\\\\Users\\\\Public\\\\Pictures\\\\sample.jpg");
                    cell.setCellStyle(sPhoto);
                } else {
                    // Text inputs: wrap so longer values show nicely.
                    cell.setCellValue("");
                    cell.setCellStyle(odd ? textLeftWrapOdd : textLeftWrapEven);
                }
            }

            // Create a modest number of styled empty rows (so borders/wrap are visible in the template)
            // Users will typically overwrite these rows for bulk import.
            int extraRows = 100;
            for (int r = 2; r < 2 + extraRows; r++) {
                Row row = sheet.createRow(r);
                boolean rOdd = (r % 2) == 1;
                CellStyle csText = rOdd ? textLeftOdd : textLeftEven;
                CellStyle csTextWrap = rOdd ? textLeftWrapOdd : textLeftWrapEven;
                CellStyle csPhone = rOdd ? phoneRightOdd : phoneRightEven;
                CellStyle csDate = rOdd ? dateCenterOdd : dateCenterEven;

                for (int c = 0; c < headers.length; c++) {
                    String header = headers[c];
                    String norm = normalizeHeader(header);
                    Cell cell = row.createCell(c);
                    cell.setCellValue("");
                    if ("photo_path".equals(norm) || "photo".equals(norm)) {
                        cell.setCellStyle(csTextWrap);
                    } else if (!"full_name".equals(norm) && !"name".equals(norm)) {
                        // dynamic fields
                        cell.setCellStyle(csTextWrap);
                    } else {
                        cell.setCellStyle(csText);
                    }
                }
            }

            // Zebra/conditional formatting intentionally removed:
            // the goal is a clean print-friendly sheet with only header styling + column separators.

            // Auto-adjust column widths after writing sample values.
            for (int c = 0; c < headers.length; c++) {
                sheet.autoSizeColumn(c);
                int w = sheet.getColumnWidth(c);
                // Add some padding so text doesn't touch borders
                sheet.setColumnWidth(c, Math.min(255 * 256, w + 700));
            }

            wb.write(out);
        }
    }
}
