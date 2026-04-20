package org.financial;

/**
 * Defines a region on the template (0–1 normalized) for one dynamic field.
 * Used so the same template layout is applied when generating many IDs.
 */
public record TemplateRegion(String side, String fieldName, double x, double y, double width, double height) {
    public static final String SIDE_FRONT = "front";
    public static final String SIDE_BACK = "back";

    public static final String FIELD_PHOTO = "photo";
    public static final String FIELD_NAME = "name";
    public static final String FIELD_ID_NUMBER = "id_number";
    public static final String FIELD_POSITION = "position";
    public static final String FIELD_DEPARTMENT = "department";
    public static final String FIELD_QR = "qr_code";
    public static final String FIELD_DATE_OF_BIRTH = "date_of_birth";
    public static final String FIELD_CONTACT = "contact";
    public static final String FIELD_ADDRESS = "address";
    public static final String FIELD_EMERGENCY = "emergency";
    public static final String FIELD_RECTANGLE = "rectangle";
    public static final String FIELD_CIRCLE = "circle";
}
