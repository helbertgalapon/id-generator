package org.financial;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Validates parsed Excel rows before database insert or batch export.
 */
public final class IdRecordValidator {

    private IdRecordValidator() {}

    /** Validates Excel row; display ID is always assigned by the application, not read from Excel. */
    public static ValidationResult validate(ExcelIdReader.ParsedRow row) {
        List<String> messages = new ArrayList<>();
        String name = row.get("name").trim();
        String photoPath = row.get("photo_path").trim();

        if (name.isEmpty()) {
            messages.add("name is empty");
            return new ValidationResult(false, messages);
        }
        if (photoPath.isEmpty()) {
            messages.add("photo_path is empty");
            return new ValidationResult(false, messages);
        }
        try {
            if (!Files.isRegularFile(Path.of(photoPath))) {
                messages.add("photo file not found; placeholder will be used");
            }
        } catch (Exception e) {
            messages.add("photo path invalid; placeholder will be used");
        }
        return new ValidationResult(true, messages);
    }

    public record ValidationResult(boolean valid, List<String> messages) {}
}
