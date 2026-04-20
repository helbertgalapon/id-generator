package org.financial;

/**
 * Represents one ID card's data (front + back).
 * qrUid is a stable unique identifier for the QR code; never changes even when other fields are updated.
 */
public record IdRecord(
    int id,
    String qrUid,
    // FRONT
    String photoPath,
    String name,
    String idNumber,
    String position,
    String department,
    String dateOfBirth,
    // BACK
    String contactNumber,
    String address,
    String emergencyName,
    String emergencyContact
) {
    public static IdRecord empty(int id) {
        return new IdRecord(id, "", "", "", "", "", "", "", "", "", "", "");
    }
}
