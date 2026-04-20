package org.financial;

import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.control.Alert;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.SelectionMode;
import javafx.scene.control.TextField;
import javafx.scene.image.Image;
import javafx.scene.input.Dragboard;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseButton;
import javafx.scene.input.TransferMode;
import javafx.scene.layout.AnchorPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.BorderPane;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import javafx.scene.text.TextAlignment;
import javafx.scene.layout.VBox;
import javafx.geometry.Insets;
import javafx.stage.FileChooser;
import javafx.stage.Stage;

import java.io.IOException;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Objects;
import java.util.HashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

public class TemplateDesignerController {
    private static final String PREVIEW_FONT = "Segoe UI";
    private static final double BASE_W = 760;
    private static final double BASE_H = 520;
    private static final double HANDLE = 8;
    private static final double SAFE_MARGIN = 0.04;
    private static final double SNAP_PX = 6;

    /** Enable FINE on {@code org.financial.template.typography} to log fontSize / zoom / region size on each change. */
    private static final Logger TYPOLOGY_LOG = Logger.getLogger("org.financial.template.typography");

    private enum DragMode { NONE, MOVE, CREATE, MARQUEE, RESIZE_NW, RESIZE_N, RESIZE_NE, RESIZE_E, RESIZE_SE, RESIZE_S, RESIZE_SW, RESIZE_W }

    @FXML private ComboBox<String> sideCombo;
    @FXML private ListView<String> fieldList;
    @FXML private CheckBox snapGridCheck;
    @FXML private CheckBox showLabelsCheck;
    @FXML private CheckBox bgLockCheck;
    @FXML private ScrollPane canvasScroll;
    @FXML private AnchorPane canvasHost;
    @FXML private Canvas templateCanvas;
    @FXML private HBox miniToolbar;
    @FXML private TextField miniFontSize;
    @FXML private CheckBox miniBold;
    @FXML private ComboBox<String> miniAlign;
    @FXML private TextField propX;
    @FXML private TextField propY;
    @FXML private TextField propW;
    @FXML private TextField propH;
    @FXML private TextField propFontSize;
    @FXML private ComboBox<String> propFontWeight;
    @FXML private ComboBox<String> propTextAlign;
    @FXML private CheckBox propLocked;
    @FXML private CheckBox propVisible;
    @FXML private VBox propsPhotoShapeSection;
    @FXML private ComboBox<String> propPhotoShape;
    @FXML private ListView<String> layerList;
    @FXML private Label hintLabel;
    @FXML private Label canvasLabel;
    @FXML private VBox centerPanel;
    @FXML private VBox propsPanel;

    private String referenceFrontPath = "";
    private String referenceBackPath = "";
    private IdRecord previewRecord;
    private final Map<String, String> displayToFieldName = new LinkedHashMap<>();
    private final Map<String, String> fieldNameToDisplay = new LinkedHashMap<>();

    private final LinkedHashMap<String, TemplateRegion> regions = new LinkedHashMap<>();
    private final LinkedHashMap<String, FieldStyle> styles = new LinkedHashMap<>();
    private final Set<String> selectedKeys = new LinkedHashSet<>();
    private final ArrayDeque<List<TemplateRegion>> undoStack = new ArrayDeque<>();
    private final ArrayDeque<List<TemplateRegion>> redoStack = new ArrayDeque<>();

    private double zoom = 1.0;
    private DragMode dragMode = DragMode.NONE;
    private String activeKey;
    private double dragStartX;
    private double dragStartY;
    private double marqueeX;
    private double marqueeY;
    private Map<String, TemplateRegion> dragStartSelection = new LinkedHashMap<>();
    private boolean spacePanning = false;
    private double panStartSceneX;
    private double panStartSceneY;
    private double panStartH;
    private double panStartV;
    private double guideX = -1;
    private double guideY = -1;

    private String hoverKey;
    private boolean suppressPropertySync = false;

    public static void show(Stage owner, String referenceFrontPath, String referenceBackPath) {
        show(owner, referenceFrontPath, referenceBackPath, null);
    }

    public static void show(Stage owner, String referenceFrontPath, String referenceBackPath, IdRecord previewRecord) {
        try {
            FXMLLoader loader = new FXMLLoader(TemplateDesignerController.class.getResource("template-designer.fxml"));
            Parent root = loader.load();
            TemplateDesignerController ctrl = loader.getController();
            ctrl.referenceFrontPath = referenceFrontPath != null ? referenceFrontPath : "";
            ctrl.referenceBackPath = referenceBackPath != null ? referenceBackPath : "";
            // Blueprint mode: the layout editor must never bind to real saved data.
            // Keep the parameter for compatibility with callers, but ignore it here.
            ctrl.previewRecord = null;
            ctrl.init();
            ctrl.applyDesignerSpacing();

            Stage stage = new Stage();
            stage.setTitle("Define template layout");
            stage.initOwner(owner);
            stage.setMinWidth(1280);
            stage.setMinHeight(760);
            stage.setWidth(1600);
            stage.setHeight(880);
            Scene scene = new Scene(root);
            scene.getStylesheets().add(TemplateDesignerController.class.getResource("styles.css").toExternalForm());

            ctrl.attachKeyboard(scene);
            stage.setScene(scene);
            stage.setMaximized(true);
            stage.show();
        } catch (IOException e) {
            new Alert(Alert.AlertType.ERROR, "Could not open template designer: " + e.getMessage()).showAndWait();
        }
    }

    private void applyDesignerSpacing() {
        // Create visual breathing room between the canvas and the properties panel.
        // BorderPane has no built-in "gap", so we rely on margins.
        if (centerPanel != null) {
            BorderPane.setMargin(centerPanel, new Insets(0, 18, 0, 0));
        }
        if (propsPanel != null) {
            BorderPane.setMargin(propsPanel, new Insets(0, 0, 0, 18));
        }
    }

    private void init() {
        sideCombo.getItems().setAll("Front", "Back");
        sideCombo.getSelectionModel().select(0);
        sideCombo.valueProperty().addListener((o, a, b) -> {
            updateFieldList();
            selectedKeys.clear();
            strictFieldSync();
            redraw();
        });

        updateFieldList();
        fieldList.getSelectionModel().setSelectionMode(SelectionMode.SINGLE);
        fieldList.setOnDragDetected(e -> {
            String item = fieldList.getSelectionModel().getSelectedItem();
            if (item == null) return;
            Dragboard db = fieldList.startDragAndDrop(TransferMode.COPY);
            javafx.scene.input.ClipboardContent c = new javafx.scene.input.ClipboardContent();
            c.putString(item);
            db.setContent(c);
            e.consume();
        });
        DynamicFieldSync.versionProperty().addListener((o, oldV, newV) -> {
            updateFieldList();
            strictFieldSync();
            redraw();
        });

        propFontWeight.getItems().setAll("Normal", "Bold");
        propFontWeight.getSelectionModel().select(0);
        propTextAlign.getItems().setAll("Left", "Center", "Right");
        propTextAlign.getSelectionModel().select(1);
        propVisible.setSelected(true);
        snapGridCheck.setSelected(true);
        showLabelsCheck.setSelected(false); // default: clean editor canvas
        propFontSize.setOnAction(e -> onApplyProperties());
        propFontWeight.setOnAction(e -> onApplyProperties());
        propTextAlign.setOnAction(e -> onApplyProperties());
        propVisible.setOnAction(e -> onApplyProperties());

        // Floating quick toolbar (direct typography editing)
        miniAlign.getItems().setAll("Left", "Center", "Right");
        miniAlign.getSelectionModel().select(1);
        miniBold.setSelected(false);

        // Font-size fields must not auto-apply on every keystroke:
        // redraw() -> updateMiniToolbar()/refreshPropertyPanel() sets the text again,
        // which makes the field effectively uneditable.
        miniFontSize.setOnAction(e -> applyStyleFromMiniToolbar());
        miniFontSize.focusedProperty().addListener((o, was, focused) -> {
            if (focused) return;
            if (suppressPropertySync) return;
            applyStyleFromMiniToolbar();
        });
        miniBold.selectedProperty().addListener((o, oldV, newV) -> {
            if (suppressPropertySync) return;
            applyStyleFromMiniToolbar();
        });
        miniAlign.valueProperty().addListener((o, oldV, newV) -> {
            if (suppressPropertySync) return;
            applyStyleFromMiniToolbar();
        });

        // Background lock toggles whether clicking empty canvas clears selection.
        if (bgLockCheck != null) bgLockCheck.setSelected(false);

        // Real-time property syncing (keep it lightweight: only apply when selection is exactly one).
        propX.textProperty().addListener((o, oldV, newV) -> { if (suppressPropertySync) return; applyGeometryFromProperties(); });
        propY.textProperty().addListener((o, oldV, newV) -> { if (suppressPropertySync) return; applyGeometryFromProperties(); });
        propW.textProperty().addListener((o, oldV, newV) -> { if (suppressPropertySync) return; applyGeometryFromProperties(); });
        propH.textProperty().addListener((o, oldV, newV) -> { if (suppressPropertySync) return; applyGeometryFromProperties(); });

        propFontSize.setOnAction(e -> applyStyleFromProperties());
        propFontSize.focusedProperty().addListener((o, was, focused) -> {
            if (focused) return;
            if (suppressPropertySync) return;
            applyStyleFromProperties();
        });
        propFontWeight.valueProperty().addListener((o, oldV, newV) -> { if (suppressPropertySync) return; applyStyleFromProperties(); });
        propTextAlign.valueProperty().addListener((o, oldV, newV) -> { if (suppressPropertySync) return; applyStyleFromProperties(); });
        propVisible.setOnAction(e -> {
            if (!canEditSelectedSingle()) return;
            FieldStyle s = style(selectedKeys.iterator().next());
            s.visible = propVisible.isSelected();
            redraw();
            refreshPropertyPanel();
        });

        if (propPhotoShape != null) {
            propPhotoShape.getItems().setAll("Square", "Circle");
            propPhotoShape.getSelectionModel().select(0);
            propPhotoShape.valueProperty().addListener((o, oldV, newV) -> {
                if (suppressPropertySync) return;
                applyPhotoShapeFromProperties();
            });
        }

        canvasScroll.setPannable(false);
        templateCanvas.setLayoutX(48);
        templateCanvas.setLayoutY(48);
        canvasHost.setOnDragOver(e -> {
            if (e.getDragboard().hasString()) e.acceptTransferModes(TransferMode.COPY);
            e.consume();
        });
        canvasHost.setOnDragDropped(e -> {
            String text = e.getDragboard().getString();
            if (text != null && !text.isBlank()) {
                addFieldAtCanvasCenter(text);
                e.setDropCompleted(true);
            } else {
                e.setDropCompleted(false);
            }
            e.consume();
        });
        canvasHost.setOnMousePressed(e -> {
            if (spacePanning) {
                panStartSceneX = e.getSceneX();
                panStartSceneY = e.getSceneY();
                panStartH = canvasScroll.getHvalue();
                panStartV = canvasScroll.getVvalue();
            }
        });
        canvasHost.setOnMouseDragged(e -> {
            if (!spacePanning) return;
            double dx = e.getSceneX() - panStartSceneX;
            double dy = e.getSceneY() - panStartSceneY;
            double hw = Math.max(1, canvasHost.getWidth() - canvasScroll.getViewportBounds().getWidth());
            double hh = Math.max(1, canvasHost.getHeight() - canvasScroll.getViewportBounds().getHeight());
            canvasScroll.setHvalue(clamp(panStartH - dx / hw, 0, 1));
            canvasScroll.setVvalue(clamp(panStartV - dy / hh, 0, 1));
        });

        templateCanvas.setOnScroll(e -> {
            if (e.getDeltaY() > 0) onZoomIn();
            else onZoomOut();
            e.consume();
        });
        templateCanvas.setOnMousePressed(e -> onCanvasPressed(e.getX(), e.getY(), e.isShiftDown(), e.getButton()));
        templateCanvas.setOnMouseDragged(e -> onCanvasDragged(e.getX(), e.getY(), e.isShiftDown()));
        templateCanvas.setOnMouseReleased(e -> onCanvasReleased());
        templateCanvas.setOnMouseMoved(e -> {
            if (spacePanning || dragMode != DragMode.NONE) return;
            String hit = findTopRegionAt(e.getX(), e.getY());
            if (Objects.equals(hit, hoverKey)) return;
            hoverKey = hit;
            redraw(false);
        });
        templateCanvas.setOnMouseExited(e -> {
            if (hoverKey == null) return;
            hoverKey = null;
            redraw(false);
        });
        templateCanvas.setOnMouseClicked(e -> {
            if (e.getButton() != MouseButton.PRIMARY) return;
            if (spacePanning) return;
            if (e.getClickCount() != 2) return;
            if (selectedKeys.size() == 1) {
                miniFontSize.requestFocus();
                miniFontSize.selectAll();
            }
        });

        layerList.getSelectionModel().selectedItemProperty().addListener((o, a, b) -> {
            if (b == null) return;
            String key = b.split(" ", 2)[0];
            if (regions.containsKey(key)) {
                selectedKeys.clear();
                selectedKeys.add(key);
                refreshPropertyPanel();
                redraw();
            }
        });

        updateFieldList();
        resetLayoutEditorState();
        redraw();
    }

    private void resetLayoutEditorState() {
        selectedKeys.clear();
        hoverKey = null;
        activeKey = null;
        undoStack.clear();
        redoStack.clear();

        loadExistingLayout();
        strictFieldSync();
    }

    /**
     * Strict Field Sync:
     * Keep only regions/styles that are valid for the current field configuration and side assignment.
     * This prevents orphan/stale fields from lingering in memory (and therefore in preview/layers).
     */
    private void strictFieldSync() {
        pruneInactiveOrDisallowedRegions();
        refreshLayerList();
        refreshPropertyPanel();
    }

    private void pruneInactiveOrDisallowedRegions() {
        Map<String, CustomFieldDef> customByKey = new HashMap<>();
        for (CustomFieldDef cf : DbHelper.getActiveCustomFields()) {
            if (cf == null || cf.fieldKey() == null || cf.fieldKey().isBlank()) continue;
            customByKey.put(cf.fieldKey(), cf);
        }

        Set<String> drop = new LinkedHashSet<>();
        for (Map.Entry<String, TemplateRegion> e : regions.entrySet()) {
            String key = e.getKey();
            TemplateRegion r = e.getValue();
            if (key == null || key.isBlank() || r == null || r.fieldName() == null || r.side() == null) {
                drop.add(key);
                continue;
            }

            if (!isAllowedFieldForSide(r.side(), r.fieldName(), customByKey)) {
                drop.add(key);
            }
        }

        for (String k : drop) {
            if (k == null) continue;
            regions.remove(k);
            styles.remove(k);
            selectedKeys.remove(k);
            if (Objects.equals(activeKey, k)) activeKey = null;
            if (Objects.equals(hoverKey, k)) hoverKey = null;
        }
    }

    private boolean isAllowedFieldForSide(String side, String fieldName, Map<String, CustomFieldDef> customByKey) {
        if (side == null || fieldName == null) return false;

        // Shapes are always allowed on both sides.
        if (fieldName.startsWith(TemplateRegion.FIELD_RECTANGLE) || fieldName.startsWith(TemplateRegion.FIELD_CIRCLE)) {
            return TemplateRegion.SIDE_FRONT.equals(side) || TemplateRegion.SIDE_BACK.equals(side);
        }

        if (TemplateRegion.SIDE_FRONT.equals(side)) {
            if (TemplateRegion.FIELD_PHOTO.equals(fieldName)) return true;
            if (TemplateRegion.FIELD_NAME.equals(fieldName)) return true;
            if (TemplateRegion.FIELD_ID_NUMBER.equals(fieldName)) return true;
        }

        if (TemplateRegion.SIDE_BACK.equals(side)) {
            if (TemplateRegion.FIELD_QR.equals(fieldName)) return true;
        }

        if (DbHelper.isCustomFieldMarker(fieldName)) {
            String cfKey = DbHelper.fieldKeyFromMarker(fieldName);
            CustomFieldDef def = customByKey.get(cfKey);
            if (def == null) return false;
            return (TemplateRegion.SIDE_FRONT.equals(side) && def.showFront())
                || (TemplateRegion.SIDE_BACK.equals(side) && def.showBack());
        }

        // Legacy/static fields were removed from the system; never allow them to linger in the designer.
        return false;
    }

    private boolean canEditSelectedSingle() {
        return selectedKeys.size() == 1;
    }

    private String selectedSingleKey() {
        return selectedKeys.size() == 1 ? selectedKeys.iterator().next() : null;
    }

    private void applyGeometryFromProperties() {
        if (!canEditSelectedSingle()) return;
        String key = selectedSingleKey();
        if (key == null) return;
        if (style(key).locked) return;

        try {
            TemplateRegion r = regions.get(key);
            if (r == null) return;
            double x = Double.parseDouble(propX.getText()) / templateCanvas.getWidth();
            double y = Double.parseDouble(propY.getText()) / templateCanvas.getHeight();
            double w = Double.parseDouble(propW.getText()) / templateCanvas.getWidth();
            double h = Double.parseDouble(propH.getText()) / templateCanvas.getHeight();
            regions.put(key, new TemplateRegion(r.side(), r.fieldName(),
                clamp(x, 0, 1 - w),
                clamp(y, 0, 1 - h),
                clamp(w, 0.02, 1),
                clamp(h, 0.02, 1)));
            redraw();
        } catch (Exception ignored) { }
    }

    private void applyStyleFromProperties() {
        if (!canEditSelectedSingle()) return;
        String key = selectedSingleKey();
        if (key == null) return;
        if (style(key).locked) return;

        try {
            FieldStyle s = style(key);
            if (propFontSize.getText() != null && !propFontSize.getText().isBlank()) {
                s.fontSize = clampInt(parseInt(propFontSize.getText(), s.fontSize), 8, 144);
            }
            s.bold = "Bold".equals(propFontWeight.getValue());
            if (propTextAlign.getValue() != null) s.align = propTextAlign.getValue();
            logTypographyDebug("applyStyleFromProperties", key, s.fontSize);
            redraw();
        } catch (Exception ignored) { }
    }

    private void applyPhotoShapeFromProperties() {
        if (!canEditSelectedSingle() || propPhotoShape == null) return;
        String key = selectedSingleKey();
        if (key == null) return;
        TemplateRegion r = regions.get(key);
        if (r == null || r.fieldName() == null || !r.fieldName().startsWith(TemplateRegion.FIELD_PHOTO)) return;
        if (style(key).locked) return;
        if (propPhotoShape.getValue() == null) return;
        style(key).photoCircular = "Circle".equals(propPhotoShape.getValue());
        redraw();
    }

    private void updateFieldList() {
        displayToFieldName.clear();
        fieldNameToDisplay.clear();
        if ("Front".equals(sideCombo.getValue())) {
            putFieldDisplay("Photo", TemplateRegion.FIELD_PHOTO);
            putFieldDisplay("Full Name", TemplateRegion.FIELD_NAME);
            putFieldDisplay("Unique Identifier", TemplateRegion.FIELD_ID_NUMBER);
            for (CustomFieldDef cf : DbHelper.getActiveCustomFields()) {
                if (cf.showFront()) {
                    String marker = DbHelper.customFieldMarker(cf.fieldKey());
                    putFieldDisplay(cf.displayName(), marker);
                }
            }
            putFieldDisplay("Rectangle", TemplateRegion.FIELD_RECTANGLE);
            putFieldDisplay("Circle", TemplateRegion.FIELD_CIRCLE);
        } else {
            putFieldDisplay("QR Code", TemplateRegion.FIELD_QR);
            for (CustomFieldDef cf : DbHelper.getActiveCustomFields()) {
                if (cf.showBack()) {
                    String marker = DbHelper.customFieldMarker(cf.fieldKey());
                    putFieldDisplay(cf.displayName(), marker);
                }
            }
            putFieldDisplay("Rectangle", TemplateRegion.FIELD_RECTANGLE);
            putFieldDisplay("Circle", TemplateRegion.FIELD_CIRCLE);
        }
        fieldList.getItems().setAll(displayToFieldName.keySet());
    }

    private void putFieldDisplay(String display, String fieldName) {
        displayToFieldName.put(display, fieldName);
        fieldNameToDisplay.put(fieldName, display);
    }

    private void pruneInactiveCustomRegions() {
        Map<String, String> active = DbHelper.getCustomFieldLabelsByKey();
        List<String> drop = new ArrayList<>();
        for (Map.Entry<String, TemplateRegion> e : regions.entrySet()) {
            String field = e.getValue().fieldName();
            // Remove deprecated legacy static fields that are no longer part of the system field set.
            if (TemplateRegion.FIELD_POSITION.equals(field)
                || TemplateRegion.FIELD_DEPARTMENT.equals(field)
                || TemplateRegion.FIELD_DATE_OF_BIRTH.equals(field)
                || TemplateRegion.FIELD_CONTACT.equals(field)
                || TemplateRegion.FIELD_ADDRESS.equals(field)
                || TemplateRegion.FIELD_EMERGENCY.equals(field)) {
                drop.add(e.getKey());
                continue;
            }

            if (DbHelper.isCustomFieldMarker(field)) {
                String key = DbHelper.fieldKeyFromMarker(field);
                if (!active.containsKey(key)) {
                    drop.add(e.getKey());
                }
            }
        }
        for (String k : drop) {
            regions.remove(k);
            styles.remove(k);
        }
    }

    private void applyStyleFromMiniToolbar() {
        if (!canEditSelectedSingle()) return;
        String key = selectedSingleKey();
        if (key == null) return;
        if (style(key).locked) return;

        try {
            FieldStyle s = style(key);
            String fs = miniFontSize.getText();
            if (fs != null && !fs.isBlank()) {
                s.fontSize = clampInt(parseInt(fs, s.fontSize), 8, 144);
            }
            s.bold = miniBold.isSelected();
            if (miniAlign.getValue() != null) s.align = miniAlign.getValue();
            logTypographyDebug("miniToolbar", key, s.fontSize);
            redraw();
            refreshPropertyPanel();
        } catch (Exception ignored) { }
    }

    private void attachKeyboard(Scene scene) {
        scene.addEventFilter(KeyEvent.KEY_PRESSED, e -> {
            if (e.getCode() == KeyCode.SPACE) {
                spacePanning = true;
                return;
            }
            if (e.isControlDown() && e.getCode() == KeyCode.Z) { onUndo(); e.consume(); return; }
            if (e.isControlDown() && e.getCode() == KeyCode.Y) { onRedo(); e.consume(); return; }
            if (e.isControlDown() && e.getCode() == KeyCode.D) { onDuplicateSelected(); e.consume(); return; }

            int step = e.isShiftDown() ? 10 : 1;
            switch (e.getCode()) {
                case DELETE, BACK_SPACE -> { onDeleteSelected(); e.consume(); }
                case LEFT -> { nudge(-step, 0); e.consume(); }
                case RIGHT -> { nudge(step, 0); e.consume(); }
                case UP -> { nudge(0, -step); e.consume(); }
                case DOWN -> { nudge(0, step); e.consume(); }
                default -> { }
            }
        });
        scene.addEventFilter(KeyEvent.KEY_RELEASED, e -> {
            if (e.getCode() == KeyCode.SPACE) spacePanning = false;
        });
    }

    private void onCanvasPressed(double x, double y, boolean shift, MouseButton button) {
        if (button != MouseButton.PRIMARY || spacePanning) return;
        String hit = findTopRegionAt(x, y);
        if (hit != null) {
            if (shift) {
                if (selectedKeys.contains(hit)) selectedKeys.remove(hit);
                else selectedKeys.add(hit);
            } else if (!selectedKeys.contains(hit)) {
                selectedKeys.clear();
                selectedKeys.add(hit);
            }
            activeKey = hit;
            DragMode handle = detectHandle(hit, x, y);
            dragMode = handle != DragMode.NONE ? handle : DragMode.MOVE;
            dragStartX = x;
            dragStartY = y;
            dragStartSelection = captureSelection();
            snapshotForUndo();
        } else {
            if (!shift && (bgLockCheck == null || !bgLockCheck.isSelected())) selectedKeys.clear();
            dragMode = DragMode.MARQUEE;
            marqueeX = x;
            marqueeY = y;
            dragStartX = x;
            dragStartY = y;
        }
        refreshPropertyPanel();
        redraw();
    }

    private void onCanvasDragged(double x, double y, boolean shift) {
        if (dragMode == DragMode.NONE) return;
        if (dragMode == DragMode.MARQUEE) {
            selectByMarquee(marqueeX, marqueeY, x, y, shift);
            redraw();
            return;
        }
        if (selectedKeys.isEmpty()) return;
        if (dragMode == DragMode.MOVE) {
            moveSelection(x - dragStartX, y - dragStartY);
        } else {
            resizePrimary(activeKey, x, y, shift);
        }
        redraw();
        refreshPropertyPanel();
    }

    private void onCanvasReleased() {
        dragMode = DragMode.NONE;
        guideX = -1;
        guideY = -1;
        refreshLayerList();
        updateMiniToolbar();
        redraw();
    }

    private void moveSelection(double dx, double dy) {
        if (dragStartSelection.isEmpty()) return;
        double cw = templateCanvas.getWidth();
        double ch = templateCanvas.getHeight();
        double ndx = dx / cw;
        double ndy = dy / ch;
        if (snapGridCheck.isSelected()) {
            ndx = snapNorm(ndx, cw);
            ndy = snapNorm(ndy, ch);
        }
        for (String key : selectedKeys) {
            TemplateRegion s = dragStartSelection.get(key);
            if (s == null || style(key).locked) continue;
            double nx = clamp(s.x() + ndx, 0, 1 - s.width());
            double ny = clamp(s.y() + ndy, 0, 1 - s.height());
            regions.put(key, new TemplateRegion(s.side(), s.fieldName(), nx, ny, s.width(), s.height()));
        }
        applyElementSnapping();
    }

    private void resizePrimary(String key, double mx, double my, boolean shift) {
        if (key == null) return;
        TemplateRegion s = dragStartSelection.get(key);
        if (s == null || style(key).locked) return;
        double cw = templateCanvas.getWidth();
        double ch = templateCanvas.getHeight();
        double x = s.x() * cw, y = s.y() * ch, w = s.width() * cw, h = s.height() * ch;
        double dx = mx - dragStartX;
        double dy = my - dragStartY;
        switch (dragMode) {
            case RESIZE_NW -> { x += dx; y += dy; w -= dx; h -= dy; }
            case RESIZE_N -> { y += dy; h -= dy; }
            case RESIZE_NE -> { y += dy; w += dx; h -= dy; }
            case RESIZE_E -> w += dx;
            case RESIZE_SE -> { w += dx; h += dy; }
            case RESIZE_S -> h += dy;
            case RESIZE_SW -> { x += dx; w -= dx; h += dy; }
            case RESIZE_W -> { x += dx; w -= dx; }
            default -> { return; }
        }
        if (shift && (s.fieldName().startsWith(TemplateRegion.FIELD_PHOTO) || s.fieldName().startsWith(TemplateRegion.FIELD_CIRCLE))) {
            double ratio = (s.height() <= 0) ? 1 : s.width() / s.height();
            h = Math.max(12, w / Math.max(0.1, ratio));
        }
        w = Math.max(12, w);
        h = Math.max(12, h);
        x = clamp(x, 0, cw - w);
        y = clamp(y, 0, ch - h);
        double nx = x / cw, ny = y / ch, nw = w / cw, nh = h / ch;
        if (snapGridCheck.isSelected()) {
            nx = snapNorm(nx, cw); ny = snapNorm(ny, ch); nw = snapNorm(nw, cw); nh = snapNorm(nh, ch);
        }
        regions.put(key, new TemplateRegion(s.side(), s.fieldName(), nx, ny, nw, nh));
        applyElementSnapping();
    }

    private void applyElementSnapping() {
        if (selectedKeys.isEmpty()) return;
        String key = selectedKeys.iterator().next();
        TemplateRegion r = regions.get(key);
        if (r == null) return;
        double cw = templateCanvas.getWidth();
        double ch = templateCanvas.getHeight();
        double left = r.x() * cw, right = (r.x() + r.width()) * cw, cx = left + (right - left) / 2;
        double top = r.y() * ch, bottom = (r.y() + r.height()) * ch, cy = top + (bottom - top) / 2;
        double bestDx = 0, bestDy = 0;
        guideX = -1; guideY = -1;
        for (Map.Entry<String, TemplateRegion> e : regions.entrySet()) {
            if (selectedKeys.contains(e.getKey()) || !e.getValue().side().equals(currentSide())) continue;
            TemplateRegion o = e.getValue();
            double ol = o.x() * cw, or = (o.x() + o.width()) * cw, oc = ol + (or - ol) / 2;
            double ot = o.y() * ch, ob = (o.y() + o.height()) * ch, om = ot + (ob - ot) / 2;
            bestDx = nearestSnap(new double[]{left, cx, right}, new double[]{ol, oc, or}, bestDx);
            bestDy = nearestSnap(new double[]{top, cy, bottom}, new double[]{ot, om, ob}, bestDy);
            if (Math.abs(bestDx) > 0) guideX = oc;
            if (Math.abs(bestDy) > 0) guideY = om;
        }
        if (Math.abs(bestDx) > 0 || Math.abs(bestDy) > 0) {
            for (String k : selectedKeys) {
                TemplateRegion tr = regions.get(k);
                if (tr == null || style(k).locked) continue;
                regions.put(k, new TemplateRegion(tr.side(), tr.fieldName(),
                    clamp(tr.x() + bestDx / cw, 0, 1 - tr.width()),
                    clamp(tr.y() + bestDy / ch, 0, 1 - tr.height()),
                    tr.width(), tr.height()));
            }
        }
    }

    private double nearestSnap(double[] from, double[] to, double current) {
        for (double f : from) {
            for (double t : to) {
                double d = t - f;
                if (Math.abs(d) <= SNAP_PX) {
                    if (Math.abs(current) == 0 || Math.abs(d) < Math.abs(current)) current = d;
                }
            }
        }
        return current;
    }

    private void selectByMarquee(double x1, double y1, double x2, double y2, boolean shift) {
        double l = Math.min(x1, x2), t = Math.min(y1, y2), r = Math.max(x1, x2), b = Math.max(y1, y2);
        if (!shift) selectedKeys.clear();
        for (Map.Entry<String, TemplateRegion> e : regions.entrySet()) {
            TemplateRegion tr = e.getValue();
            if (!tr.side().equals(currentSide())) continue;
            double tl = tr.x() * templateCanvas.getWidth();
            double tt = tr.y() * templateCanvas.getHeight();
            double trr = (tr.x() + tr.width()) * templateCanvas.getWidth();
            double tbb = (tr.y() + tr.height()) * templateCanvas.getHeight();
            boolean intersects = !(trr < l || tl > r || tbb < t || tt > b);
            if (intersects) selectedKeys.add(e.getKey());
        }
    }

    private Map<String, TemplateRegion> captureSelection() {
        Map<String, TemplateRegion> out = new LinkedHashMap<>();
        for (String k : selectedKeys) {
            TemplateRegion r = regions.get(k);
            if (r != null) out.put(k, r);
        }
        return out;
    }

    private String findTopRegionAt(double x, double y) {
        List<Map.Entry<String, TemplateRegion>> list = new ArrayList<>(regions.entrySet());
        for (int i = list.size() - 1; i >= 0; i--) {
            Map.Entry<String, TemplateRegion> e = list.get(i);
            TemplateRegion r = e.getValue();
            if (!r.side().equals(currentSide())) continue;
            double l = r.x() * templateCanvas.getWidth();
            double t = r.y() * templateCanvas.getHeight();
            double rr = (r.x() + r.width()) * templateCanvas.getWidth();
            double bb = (r.y() + r.height()) * templateCanvas.getHeight();
            if (x >= l && x <= rr && y >= t && y <= bb) return e.getKey();
        }
        return null;
    }

    private DragMode detectHandle(String key, double x, double y) {
        TemplateRegion r = regions.get(key);
        if (r == null) return DragMode.NONE;
        double l = r.x() * templateCanvas.getWidth();
        double t = r.y() * templateCanvas.getHeight();
        double rr = (r.x() + r.width()) * templateCanvas.getWidth();
        double bb = (r.y() + r.height()) * templateCanvas.getHeight();
        double cx = (l + rr) / 2;
        double cy = (t + bb) / 2;
        if (near(x, y, l, t)) return DragMode.RESIZE_NW;
        if (near(x, y, cx, t)) return DragMode.RESIZE_N;
        if (near(x, y, rr, t)) return DragMode.RESIZE_NE;
        if (near(x, y, rr, cy)) return DragMode.RESIZE_E;
        if (near(x, y, rr, bb)) return DragMode.RESIZE_SE;
        if (near(x, y, cx, bb)) return DragMode.RESIZE_S;
        if (near(x, y, l, bb)) return DragMode.RESIZE_SW;
        if (near(x, y, l, cy)) return DragMode.RESIZE_W;
        return DragMode.NONE;
    }

    private boolean near(double x, double y, double hx, double hy) {
        // Make handles easier to grab, especially when zoomed out.
        double tol = handleHitTolerancePx();
        return Math.abs(x - hx) <= tol && Math.abs(y - hy) <= tol;
    }

    private double handleHitTolerancePx() {
        // At low zoom, the canvas is smaller and precision gets harder — increase hit area.
        // At high zoom, keep it modest so you can still click near edges precisely.
        return clamp(HANDLE * (zoom < 1.0 ? (1.0 / zoom) : 1.0), 8, 22);
    }

    private double handleSizePx() {
        // Visual handle size: scale slightly with zoom, but keep within a reasonable range.
        return clamp(HANDLE * (0.9 + 0.35 * zoom), 7, 16);
    }

    private void redraw() {
        redraw(true);
    }

    private void redraw(boolean refreshLayers) {
        strictFieldSync();
        boolean isFront = TemplateRegion.SIDE_FRONT.equals(currentSide());
        String referencePath = isFront ? referenceFrontPath : referenceBackPath;
        Image bg = loadImage(referencePath);

        // Native template resolution must match preview/export so typography clamps and style multipliers match.
        // Zoom is display-only: scale the graphics context after drawing at native size.
        double nativeWd = bg != null ? bg.getWidth() : BASE_W;
        double nativeHd = bg != null ? bg.getHeight() : BASE_H;
        int nativeW = Math.max(1, (int) Math.round(nativeWd));
        int nativeH = Math.max(1, (int) Math.round(nativeHd));
        int canvasW = Math.max(1, (int) Math.round(nativeWd * zoom));
        int canvasH = Math.max(1, (int) Math.round(nativeHd * zoom));

        templateCanvas.setWidth(canvasW);
        templateCanvas.setHeight(canvasH);
        canvasHost.setMinWidth(Math.max(980, canvasW + 120));
        canvasHost.setMinHeight(Math.max(700, canvasH + 120));

        GraphicsContext gc = templateCanvas.getGraphicsContext2D();
        gc.clearRect(0, 0, canvasW, canvasH);

        // Blueprint mode (preview only):
        // - Render ONLY the current side.
        // - Use placeholder sample data only (never bind to a saved record, server URL, or cloud asset).
        List<TemplateRegion> layoutList = new ArrayList<>();
        for (TemplateRegion r : regions.values()) {
            if (r != null && currentSide().equals(r.side())) layoutList.add(r);
        }
        List<TemplateFieldStyle> stylesList = buildTemplateStylesFromRegions(layoutList);
        IdRecord record = samplePreviewRecord();
        String baseUrl = "";
        String cloudUrl = "";

        gc.save();
        gc.scale(zoom, zoom);
        if (isFront) {
            IdImageGenerator.drawFrontBlueprint(gc, record, referencePath, nativeW, nativeH, layoutList, stylesList);
        } else {
            IdImageGenerator.drawBackBlueprint(gc, record, referencePath, nativeW, nativeH, layoutList, stylesList);
        }
        gc.restore();

        // Optional editor overlays (debug/snap):
        if (showLabelsCheck.isSelected()) {
            drawSafeMargin(gc, canvasW, canvasH);
            drawDebugBoundingBoxes(gc, canvasW, canvasH);
        }
        if (snapGridCheck.isSelected()) {
            drawGrid(gc, canvasW, canvasH);
        }

        // Hover outline (helps selection without changing rendered pixels).
        if (hoverKey != null && !selectedKeys.contains(hoverKey)) {
            FieldStyle st = styles.get(hoverKey);
            if (st != null && st.visible) {
                TemplateRegion r = regions.get(hoverKey);
                if (r != null && r.side().equals(currentSide())) {
                    double x = r.x() * canvasW;
                    double y = r.y() * canvasH;
                    double rw = r.width() * canvasW;
                    double rh = r.height() * canvasH;
                    drawHoverOutline(gc, x, y, rw, rh);
                }
            }
        }

        drawSelection(gc, canvasW, canvasH);
        drawMarquee(gc);
        drawGuides(gc, canvasW, canvasH);
        updateMiniToolbar();
        if (refreshLayers) refreshLayerList();
    }

    private void drawDebugBoundingBoxes(GraphicsContext gc, int canvasW, int canvasH) {
        gc.setStroke(Color.rgb(34, 197, 94, 0.75));
        gc.setLineWidth(1.2);
        gc.setFill(Color.rgb(15, 23, 42, 0.95));

        double fontSize = 11;
        gc.setFont(javafx.scene.text.Font.font("Segoe UI", fontSize));

        for (TemplateRegion r : regions.values()) {
            if (r == null) continue;
            if (!r.side().equals(currentSide())) continue;
            if (r.fieldName() == null) continue;
            FieldStyle st = styles.get(r.side() + ":" + r.fieldName());
            if (st != null && !st.visible) continue;

            double x = r.x() * canvasW;
            double y = r.y() * canvasH;
            double w = r.width() * canvasW;
            double h = r.height() * canvasH;

            gc.strokeRect(x, y, w, h);
            String txt = String.format("%s\nx=%.0f y=%.0f w=%.0f h=%.0f", r.fieldName(), x, y, w, h);
            gc.fillText(txt, x + 2, y + 12);
        }
        gc.setLineWidth(1);
    }

    private List<TemplateFieldStyle> buildTemplateStylesFromRegions(List<TemplateRegion> layoutList) {
        List<TemplateFieldStyle> out = new ArrayList<>();
        for (TemplateRegion r : layoutList) {
            if (r == null || r.fieldName() == null) continue;
            FieldStyle st = style(r.side() + ":" + r.fieldName());
            out.add(new TemplateFieldStyle(r.side(), r.fieldName(), st.fontSize, st.bold, st.align, st.locked, st.visible, st.photoCircular));
        }
        return out;
    }

    private IdRecord samplePreviewRecord() {
        // Ensure the editor has stable content even when no record is selected.
        return new IdRecord(
            1,
            "preview_qr_sample",
            "",
            "Juan Dela Cruz",
            "EMP-0001",
            "Senior Analyst",
            "Operations",
            "1990-01-15",
            "+63 917 000 0000",
            "123 Main Street",
            "Jamie Lee",
            "+63 917 000 0001"
        );
    }

    private void drawGrid(GraphicsContext gc, double w, double h) {
        gc.setStroke(Color.rgb(100, 120, 160, 0.12));
        int step = Math.max(8, (int) (10 * zoom));
        for (int x = 0; x < w; x += step) gc.strokeLine(x, 0, x, h);
        for (int y = 0; y < h; y += step) gc.strokeLine(0, y, w, y);
    }

    private void drawSafeMargin(GraphicsContext gc, double w, double h) {
        gc.setStroke(Color.rgb(255, 120, 120, 0.35));
        gc.setLineDashes(6, 4);
        gc.strokeRect(w * SAFE_MARGIN, h * SAFE_MARGIN, w * (1 - SAFE_MARGIN * 2), h * (1 - SAFE_MARGIN * 2));
        gc.setLineDashes(null);
    }

    private void drawFields(GraphicsContext gc, double w, double h) {
        for (Map.Entry<String, TemplateRegion> e : regions.entrySet()) {
            TemplateRegion r = e.getValue();
            if (!r.side().equals(currentSide())) continue;
            FieldStyle st = style(e.getKey());
            if (!st.visible) continue;
            String key = e.getKey();
            double x = r.x() * w, y = r.y() * h, rw = r.width() * w, rh = r.height() * h;
            boolean selected = selectedKeys.contains(key);
            boolean hovered = key.equals(hoverKey) && !selected;
            String fn = r.fieldName();
            boolean photoCirc = fn != null && fn.startsWith(TemplateRegion.FIELD_PHOTO) && st.photoCircular;

            // Keep the canvas clean: only show strong bounds when selected.
            if (selected) {
                gc.setFill(Color.rgb(47, 111, 239, 0.06));
                if (photoCirc) gc.fillOval(x, y, rw, rh);
                else gc.fillRect(x, y, rw, rh);
            } else if (hovered) {
                if (photoCirc) {
                    gc.setStroke(Color.rgb(47, 111, 239, 0.6));
                    gc.setLineDashes(5, 4);
                    gc.strokeOval(x, y, rw, rh);
                    gc.setLineDashes(null);
                } else {
                    drawHoverOutline(gc, x, y, rw, rh);
                }
            }

            drawPreview(gc, r.fieldName(), x, y, rw, rh);
        }
    }

    private void drawHoverOutline(GraphicsContext gc, double x, double y, double w, double h) {
        gc.setStroke(Color.rgb(47, 111, 239, 0.6));
        gc.setLineDashes(5, 4);
        gc.strokeRect(x, y, w, h);
        gc.setLineDashes(null);
    }

    private void drawPreview(GraphicsContext gc, String field, double x, double y, double w, double h) {
        String key = currentSide() + ":" + field;
        FieldStyle st = style(key);

        if (field != null && field.startsWith(TemplateRegion.FIELD_PHOTO)) {
            gc.setFill(Color.rgb(47, 111, 239, 0.08));
            if (st.photoCircular) gc.fillOval(x, y, w, h);
            else gc.fillRect(x, y, w, h);
            gc.setStroke(Color.rgb(47, 111, 239, 0.55));
            gc.setLineWidth(Math.max(1, zoom));
            if (st.photoCircular) gc.strokeOval(x, y, w, h);
            else gc.strokeRect(x, y, w, h);
            if (showLabelsCheck.isSelected()) {
                gc.setFill(Color.rgb(90, 100, 115));
                double fs = Math.max(10, st.fontSize * zoom * 0.65);
                FontWeight weight = st.bold ? FontWeight.BOLD : FontWeight.NORMAL;
                gc.setFont(Font.font(PREVIEW_FONT, weight, fs));
                gc.setTextAlign(TextAlignment.CENTER);
                gc.fillText("PHOTO", x + w / 2, y + Math.max(16, h / 2 - 4));
                gc.setTextAlign(TextAlignment.LEFT);
            }
            return;
        }

        // Design shapes (persisted in the same layout/style tables).
        if (field != null && field.startsWith(TemplateRegion.FIELD_RECTANGLE)) {
            double arc = Math.max(4, Math.min(w, h) * 0.15);
            gc.setFill(Color.rgb(47, 111, 239, 0.10));
            gc.fillRoundRect(x, y, w, h, arc, arc);
            gc.setStroke(Color.rgb(47, 111, 239, 0.70));
            gc.strokeRoundRect(x, y, w, h, arc, arc);
            if (showLabelsCheck.isSelected()) {
                gc.setFill(Color.rgb(90, 100, 115));
                double fs = Math.max(10, st.fontSize * zoom * 0.65);
                FontWeight weight = st.bold ? FontWeight.BOLD : FontWeight.NORMAL;
                gc.setFont(Font.font(PREVIEW_FONT, weight, fs));
                gc.setTextAlign(TextAlignment.CENTER);
                gc.fillText("RECT", x + w / 2, y + Math.max(16, h / 2 - 4));
                gc.setTextAlign(TextAlignment.LEFT);
            }
            return;
        }
        if (field != null && field.startsWith(TemplateRegion.FIELD_CIRCLE)) {
            gc.setFill(Color.rgb(47, 111, 239, 0.10));
            gc.fillOval(x, y, w, h);
            gc.setStroke(Color.rgb(47, 111, 239, 0.70));
            gc.strokeOval(x, y, w, h);
            if (showLabelsCheck.isSelected()) {
                gc.setFill(Color.rgb(90, 100, 115));
                double fs = Math.max(10, st.fontSize * zoom * 0.65);
                FontWeight weight = st.bold ? FontWeight.BOLD : FontWeight.NORMAL;
                gc.setFont(Font.font(PREVIEW_FONT, weight, fs));
                gc.setTextAlign(TextAlignment.CENTER);
                gc.fillText("CIRCLE", x + w / 2, y + Math.max(16, h / 2 - 4));
                gc.setTextAlign(TextAlignment.LEFT);
            }
            return;
        }

        String v = sampleValue(field);
        gc.setFill(Color.rgb(24, 30, 40));
        FontWeight weight = st.bold ? FontWeight.BOLD : FontWeight.NORMAL;
        double fontSize = Math.max(8, st.fontSize * zoom);
        gc.setFont(Font.font(PREVIEW_FONT, weight, fontSize));
        gc.setTextAlign(switch (st.align == null ? "Center" : st.align) {
            case "Left" -> TextAlignment.LEFT;
            case "Right" -> TextAlignment.RIGHT;
            default -> TextAlignment.CENTER;
        });
        double textX = switch (st.align == null ? "Center" : st.align) {
            case "Left" -> x + 6 * zoom;
            case "Right" -> x + w - 6 * zoom;
            default -> x + w / 2;
        };
        boolean showFieldLabels = showLabelsCheck.isSelected() && !TemplateRegion.FIELD_ID_NUMBER.equals(field);
        if (showFieldLabels) {
            gc.setFill(Color.rgb(90, 100, 115));
            gc.fillText(field.replace('_', ' ').toUpperCase(), textX, y + 14 * zoom);
            gc.setFill(Color.rgb(24, 30, 40));
            gc.fillText(v, textX, y + Math.max(30, h * 0.58));
        } else {
            gc.fillText(v, textX, y + Math.max(22, h * 0.54));
        }
        gc.setTextAlign(TextAlignment.LEFT);
    }

    private String sampleValue(String field) {
        return switch (field) {
            case "photo" -> "Photo";
            case "name" -> "Juan Dela Cruz";
            case "id_number" -> "EMP-0001";
            case "position" -> "Senior Analyst";
            case "department" -> "Operations";
            case "date_of_birth" -> "Jan 15, 1990";
            case "contact" -> "+63 917 000 0000";
            case "address" -> "123 Main Street";
            case "emergency" -> "Jamie Lee";
            case "qr_code" -> "QR";
            default -> {
                // Custom fields: give a non-empty placeholder so new fields are never invisible.
                yield "Sample";
            }
        };
    }

    private void drawSelection(GraphicsContext gc, double w, double h) {
        for (String key : selectedKeys) {
            TemplateRegion r = regions.get(key);
            if (r == null || !r.side().equals(currentSide())) continue;
            double x = r.x() * w, y = r.y() * h, rw = r.width() * w, rh = r.height() * h;
            String fn = r.fieldName();
            boolean photoCirc = fn != null && fn.startsWith(TemplateRegion.FIELD_PHOTO) && style(key).photoCircular;
            gc.setStroke(Color.rgb(47, 111, 239, 0.85));
            gc.setLineWidth(2.5);
            if (photoCirc) gc.strokeOval(x, y, rw, rh);
            else gc.strokeRect(x, y, rw, rh);

            if (!photoCirc && fn != null && isTextLayoutField(fn)) {
                drawTextContentMarginGuide(gc, x, y, rw, rh, w);
            }

            // Printable area warning (safe margin inset)
            double safeL = w * SAFE_MARGIN;
            double safeT = h * SAFE_MARGIN;
            double safeR = w * (1 - SAFE_MARGIN);
            double safeB = h * (1 - SAFE_MARGIN);
            boolean outside = x < safeL || y < safeT || (x + rw) > safeR || (y + rh) > safeB;
            if (outside) {
                gc.setStroke(Color.rgb(225, 29, 72, 0.95));
                gc.setLineDashes(7, 4);
                gc.setLineWidth(2);
                if (photoCirc) gc.strokeOval(x, y, rw, rh);
                else gc.strokeRect(x, y, rw, rh);
                gc.setLineDashes(null);
            }
        }
        if (!selectedKeys.isEmpty()) {
            String key = selectedKeys.iterator().next();
            TemplateRegion r = regions.get(key);
            if (r != null) drawHandles(gc, r.x() * w, r.y() * h, r.width() * w, r.height() * h);
        }
    }

    /** Fields that use {@link IdImageGenerator} symmetric horizontal margin for text (matches dashed guide). */
    private boolean isTextLayoutField(String fieldName) {
        if (fieldName == null) return false;
        return !fieldName.startsWith(TemplateRegion.FIELD_PHOTO)
            && !fieldName.startsWith(TemplateRegion.FIELD_RECTANGLE)
            && !fieldName.startsWith(TemplateRegion.FIELD_CIRCLE)
            && !fieldName.startsWith(TemplateRegion.FIELD_QR);
    }

    private void drawTextContentMarginGuide(GraphicsContext gc, double x, double y, double rw, double rh, double cardWidthPx) {
        double m = IdImageGenerator.horizontalMarginPx(cardWidthPx);
        if (rw <= 2 * m || rh <= 2 * m) return;
        gc.save();
        gc.setStroke(Color.rgb(47, 111, 239, 0.38));
        gc.setLineDashes(5, 4);
        gc.setLineWidth(1);
        gc.strokeRect(x + m, y + m, rw - 2 * m, rh - 2 * m);
        gc.setLineDashes(null);
        gc.restore();
    }

    private void drawHandles(GraphicsContext gc, double x, double y, double w, double h) {
        double[][] pts = {{x,y},{x+w/2,y},{x+w,y},{x+w,y+h/2},{x+w,y+h},{x+w/2,y+h},{x,y+h},{x,y+h/2}};
        gc.setFill(Color.WHITE);
        gc.setStroke(Color.rgb(47, 111, 239));
        double hs = handleSizePx();
        for (double[] p : pts) {
            gc.fillRect(p[0]-hs/2, p[1]-hs/2, hs, hs);
            gc.strokeRect(p[0]-hs/2, p[1]-hs/2, hs, hs);
        }
    }

    private void drawMarquee(GraphicsContext gc) {
        if (dragMode != DragMode.MARQUEE) return;
        double l = Math.min(marqueeX, dragStartX), t = Math.min(marqueeY, dragStartY);
        double w = Math.abs(dragStartX - marqueeX), h = Math.abs(dragStartY - marqueeY);
        gc.setStroke(Color.rgb(47, 111, 239, 0.8));
        gc.setLineDashes(6, 4);
        gc.strokeRect(l, t, w, h);
        gc.setLineDashes(null);
    }

    private void drawGuides(GraphicsContext gc, double w, double h) {
        gc.setStroke(Color.rgb(47, 111, 239, 0.55));
        if (guideX >= 0) gc.strokeLine(guideX, 0, guideX, h);
        if (guideY >= 0) gc.strokeLine(0, guideY, w, guideY);
    }

    private void updateMiniToolbar() {
        if (selectedKeys.isEmpty()) {
            miniToolbar.setVisible(false);
            miniToolbar.setManaged(false);
            return;
        }
        String key = selectedKeys.iterator().next();
        TemplateRegion r = regions.get(key);
        if (r == null) return;
        double x = templateCanvas.getLayoutX() + r.x() * templateCanvas.getWidth();
        double y = templateCanvas.getLayoutY() + r.y() * templateCanvas.getHeight();
        miniToolbar.setLayoutX(x);
        miniToolbar.setLayoutY(Math.max(2, y - 34));
        miniToolbar.setVisible(true);
        miniToolbar.setManaged(true);

        boolean single = selectedKeys.size() == 1;
        FieldStyle s = style(key);
        suppressPropertySync = true;
        try {
            miniFontSize.setText(String.valueOf(s.fontSize));
            miniBold.setSelected(s.bold);
            miniAlign.getSelectionModel().select(s.align);
            miniFontSize.setDisable(!single);
            miniBold.setDisable(!single);
            miniAlign.setDisable(!single);
        } finally {
            suppressPropertySync = false;
        }
    }

    private void refreshPropertyPanel() {
        boolean single = selectedKeys.size() == 1;
        propX.setDisable(!single);
        propY.setDisable(!single);
        propW.setDisable(!single);
        propH.setDisable(!single);
        propFontSize.setDisable(!single);
        propFontWeight.setDisable(!single);
        propTextAlign.setDisable(!single);
        propLocked.setDisable(!single);
        propVisible.setDisable(!single);

        if (!single) {
            if (propsPhotoShapeSection != null) {
                propsPhotoShapeSection.setVisible(false);
                propsPhotoShapeSection.setManaged(false);
            }
            return;
        }
        String key = selectedKeys.iterator().next();
        TemplateRegion r0 = regions.get(key);
        boolean showPhotoShape = propsPhotoShapeSection != null && propPhotoShape != null
            && r0 != null && r0.fieldName() != null && r0.fieldName().startsWith(TemplateRegion.FIELD_PHOTO);
        if (propsPhotoShapeSection != null) {
            propsPhotoShapeSection.setVisible(showPhotoShape);
            propsPhotoShapeSection.setManaged(showPhotoShape);
        }
        if (propPhotoShape != null) {
            propPhotoShape.setDisable(!showPhotoShape || style(key).locked);
        }
        TemplateRegion r = regions.get(key);
        if (r == null) return;
        suppressPropertySync = true;
        try {
            propX.setText(String.format("%.1f", r.x() * templateCanvas.getWidth()));
            propY.setText(String.format("%.1f", r.y() * templateCanvas.getHeight()));
            propW.setText(String.format("%.1f", r.width() * templateCanvas.getWidth()));
            propH.setText(String.format("%.1f", r.height() * templateCanvas.getHeight()));
            FieldStyle s = style(key);
            propFontSize.setText(String.valueOf(s.fontSize));
            propFontWeight.getSelectionModel().select(s.bold ? 1 : 0);
            propTextAlign.getSelectionModel().select(s.align);
            propLocked.setSelected(s.locked);
            propVisible.setSelected(s.visible);
            if (propPhotoShape != null && showPhotoShape) {
                propPhotoShape.getSelectionModel().select(s.photoCircular ? 1 : 0);
            }
        } finally {
            suppressPropertySync = false;
        }
    }

    private void refreshLayerList() {
        List<String> items = new ArrayList<>();
        for (Map.Entry<String, TemplateRegion> e : regions.entrySet()) {
            if (e.getValue().side().equals(currentSide())) items.add(e.getKey() + "  " + e.getValue().fieldName());
        }
        layerList.getItems().setAll(items);
    }

    private String displayToField(String d) {
        String mapped = displayToFieldName.get(d);
        return mapped != null ? mapped : d.toLowerCase().replace(" ", "_");
    }

    private void addFieldAtCanvasCenter(String display) {
        snapshotForUndo();
        String field = displayToField(display);
        String key = currentSide() + ":" + field;
        double cw = templateCanvas.getWidth(), ch = templateCanvas.getHeight();
        double w;
        double h;
        if (TemplateRegion.FIELD_PHOTO.equals(field)) {
            w = 0.18;
            h = 0.24;
        } else if (TemplateRegion.FIELD_CIRCLE.equals(field)) {
            w = 0.18;
            h = 0.18;
        } else if (TemplateRegion.FIELD_RECTANGLE.equals(field)) {
            w = 0.24;
            h = 0.14;
        } else {
            w = 0.24;
            h = 0.10;
        }
        regions.put(key, new TemplateRegion(currentSide(), field, 0.5 - w / 2, 0.5 - h / 2, w, h));
        applyDefaultTypographyForField(field, style(key));
        selectedKeys.clear();
        selectedKeys.add(key);
        redraw();
        refreshPropertyPanel();
    }

    private FieldStyle style(String key) {
        return styles.computeIfAbsent(key, k -> new FieldStyle());
    }

    private void logTypographyDebug(String source, String regionKey, int fontSize) {
        if (!TYPOLOGY_LOG.isLoggable(Level.FINE)) return;
        double cw = templateCanvas != null ? templateCanvas.getWidth() : 0;
        double ch = templateCanvas != null ? templateCanvas.getHeight() : 0;
        TemplateRegion r = regions.get(regionKey);
        double rw = r != null && cw > 0 ? r.width() * cw : -1;
        double rh = r != null && ch > 0 ? r.height() * ch : -1;
        TYPOLOGY_LOG.fine(String.format(
            "%s key=%s fontSize=%d editorZoom=%.3f canvasPx=%.0fx%.0f regionPx=%.1fx%.1f | text: TemplateTypography+effectiveFontPx only; box resize does not change font; IdImageGenerator gc transform identity",
            source, regionKey, fontSize, zoom, cw, ch, rw, rh));
    }

    /** Baseline scale = {@link TemplateTypography#STYLE_BASELINE}; bold name reads clearly without oversizing. */
    private void applyDefaultTypographyForField(String field, FieldStyle s) {
        if (field == null) return;
        if (field.startsWith(TemplateRegion.FIELD_RECTANGLE) || field.startsWith(TemplateRegion.FIELD_CIRCLE)
            || field.startsWith(TemplateRegion.FIELD_PHOTO) || field.startsWith(TemplateRegion.FIELD_QR)) {
            s.fontSize = (int) Math.round(TemplateTypography.STYLE_BASELINE);
            return;
        }
        s.align = "Center";
        s.fontSize = (int) Math.round(TemplateTypography.STYLE_BASELINE);
        s.bold = TemplateRegion.FIELD_NAME.equals(field) || TemplateRegion.FIELD_ID_NUMBER.equals(field);
    }

    private void snapshotForUndo() {
        undoStack.push(new ArrayList<>(regions.values()));
        if (undoStack.size() > 80) undoStack.removeLast();
        redoStack.clear();
    }

    private void restoreSnapshot(List<TemplateRegion> snap) {
        regions.clear();
        for (TemplateRegion r : snap) regions.put(r.side() + ":" + r.fieldName(), r);
    }

    private void loadExistingLayout() {
        regions.clear();
        styles.clear();
        for (TemplateRegion r : DbHelper.getTemplateLayout()) {
            if (TemplateRegion.SIDE_FRONT.equals(r.side()) && TemplateRegion.FIELD_QR.equals(r.fieldName())) continue;
            regions.put(r.side() + ":" + r.fieldName(), r);
        }
        for (TemplateFieldStyle s : DbHelper.getTemplateStyles()) {
            String key = s.side() + ":" + s.fieldName();
            FieldStyle fs = style(key);
            fs.fontSize = s.fontSize();
            fs.bold = s.bold();
            fs.align = s.align();
            fs.locked = s.locked();
            fs.visible = s.visible();
            fs.photoCircular = s.photoCircular();
        }
    }

    @FXML
    private void onRefreshLayout() {
        resetLayoutEditorState();
        redraw();
    }

    private static Image loadImage(String path) {
        if (path == null || path.isBlank()) return null;
        try {
            if (Files.exists(Path.of(path))) {
                Image img = new Image("file:" + path);
                return img.isError() ? null : img;
            }
        } catch (Exception ignored) { }
        return null;
    }

    private String currentSide() {
        return "Front".equals(sideCombo.getValue()) ? TemplateRegion.SIDE_FRONT : TemplateRegion.SIDE_BACK;
    }

    private void nudge(int dx, int dy) {
        if (selectedKeys.isEmpty()) return;
        snapshotForUndo();
        double ndx = dx / templateCanvas.getWidth();
        double ndy = dy / templateCanvas.getHeight();
        for (String key : selectedKeys) {
            TemplateRegion r = regions.get(key);
            if (r == null || style(key).locked) continue;
            regions.put(key, new TemplateRegion(r.side(), r.fieldName(),
                clamp(r.x() + ndx, 0, 1 - r.width()),
                clamp(r.y() + ndy, 0, 1 - r.height()),
                r.width(), r.height()));
        }
        redraw();
    }

    private double snapNorm(double n, double dimensionPx) {
        int step = Math.max(8, (int) (10 * zoom));
        return (Math.round((n * dimensionPx) / step) * step) / dimensionPx;
    }

    private static double clamp(double v, double lo, double hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    @FXML private void onSaveLayout() {
        DbHelper.saveTemplateLayout(new ArrayList<>(regions.values()));
        List<TemplateFieldStyle> out = new ArrayList<>();
        for (Map.Entry<String, TemplateRegion> e : regions.entrySet()) {
            FieldStyle s = style(e.getKey());
            TemplateRegion r = e.getValue();
            out.add(new TemplateFieldStyle(r.side(), r.fieldName(), s.fontSize, s.bold, s.align, s.locked, s.visible, s.photoCircular));
        }
        DbHelper.saveTemplateStyles(out);
        DbHelper.setReferenceDesign(referenceFrontPath, referenceBackPath);
        new Alert(Alert.AlertType.INFORMATION, "Layout saved.").showAndWait();
    }

    @FXML
    private void onChangeBackground() {
        Stage w = (Stage) templateCanvas.getScene().getWindow();
        FileChooser fc = new FileChooser();
        fc.setTitle("Choose background image (" + sideCombo.getValue() + ")");
        fc.getExtensionFilters().addAll(
            new FileChooser.ExtensionFilter("Images", "*.png", "*.jpg", "*.jpeg", "*.bmp", "*.webp", "*.gif")
        );
        File file = fc.showOpenDialog(w);
        if (file == null) return;

        if ("Front".equals(sideCombo.getValue())) referenceFrontPath = file.getAbsolutePath();
        else referenceBackPath = file.getAbsolutePath();

        redraw();
    }
    @FXML private void onClose() { ((Stage) templateCanvas.getScene().getWindow()).close(); }
    @FXML private void onUndo() {
        if (undoStack.isEmpty()) return;
        redoStack.push(new ArrayList<>(regions.values()));
        restoreSnapshot(undoStack.pop());
        redraw();
    }
    @FXML private void onRedo() {
        if (redoStack.isEmpty()) return;
        undoStack.push(new ArrayList<>(regions.values()));
        restoreSnapshot(redoStack.pop());
        redraw();
    }
    @FXML private void onDuplicateSelected() {
        if (selectedKeys.isEmpty()) return;
        snapshotForUndo();
        Set<String> newSel = new LinkedHashSet<>();
        for (String key : selectedKeys) {
            TemplateRegion r = regions.get(key);
            if (r == null) continue;
            String newKey = r.side() + ":" + r.fieldName() + "_copy_" + System.nanoTime();
            regions.put(newKey, new TemplateRegion(r.side(), r.fieldName(),
                clamp(r.x() + 0.01, 0, 1 - r.width()), clamp(r.y() + 0.01, 0, 1 - r.height()), r.width(), r.height()));
            styles.put(newKey, style(key).copy());
            newSel.add(newKey);
        }
        selectedKeys.clear();
        selectedKeys.addAll(newSel);
        redraw();
    }
    @FXML private void onDeleteSelected() {
        if (selectedKeys.isEmpty()) return;
        snapshotForUndo();
        for (String key : new ArrayList<>(selectedKeys)) {
            if (!style(key).locked) {
                regions.remove(key);
                styles.remove(key);
            }
        }
        selectedKeys.clear();
        redraw();
    }
    @FXML private void onZoomOut() { zoom = clamp(zoom - 0.1, 0.4, 3.0); redraw(); }
    @FXML private void onZoomIn() { zoom = clamp(zoom + 0.1, 0.4, 3.0); redraw(); }
    @FXML private void onZoomFit() { zoom = 1.0; redraw(); }
    @FXML private void onAlignLeft() { alignBy("left"); }
    @FXML private void onAlignCenter() { alignBy("center"); }
    @FXML private void onAlignRight() { alignBy("right"); }
    @FXML private void onDistributeHorizontal() { distribute(true); }
    @FXML private void onDistributeVertical() { distribute(false); }
    @FXML private void onToggleLock() {
        for (String key : selectedKeys) style(key).locked = !style(key).locked;
        refreshPropertyPanel();
        redraw();
    }
    @FXML private void onSendBackward() { reorder(-1); }
    @FXML private void onBringForward() { reorder(1); }
    @FXML private void onSendToBack() { sendExtreme(false); }
    @FXML private void onBringToFront() { sendExtreme(true); }
    @FXML private void onPropertyLockChanged() {
        if (selectedKeys.size() != 1) return;
        style(selectedKeys.iterator().next()).locked = propLocked.isSelected();
        redraw();
    }
    @FXML private void onApplyProperties() {
        if (selectedKeys.size() != 1) return;
        String key = selectedKeys.iterator().next();
        TemplateRegion r = regions.get(key);
        if (r == null) return;
        try {
            double x = Double.parseDouble(propX.getText()) / templateCanvas.getWidth();
            double y = Double.parseDouble(propY.getText()) / templateCanvas.getHeight();
            double w = Double.parseDouble(propW.getText()) / templateCanvas.getWidth();
            double h = Double.parseDouble(propH.getText()) / templateCanvas.getHeight();
            regions.put(key, new TemplateRegion(r.side(), r.fieldName(), clamp(x, 0, 1 - w), clamp(y, 0, 1 - h), clamp(w, 0.02, 1), clamp(h, 0.02, 1)));
            FieldStyle s = style(key);
            s.fontSize = clampInt(parseInt(propFontSize.getText(), s.fontSize), 8, 144);
            s.bold = "Bold".equals(propFontWeight.getValue());
            s.align = propTextAlign.getValue();
            s.locked = propLocked.isSelected();
            s.visible = propVisible.isSelected();
            if (r.fieldName() != null && r.fieldName().startsWith(TemplateRegion.FIELD_PHOTO)
                && propPhotoShape != null && propPhotoShape.getValue() != null) {
                s.photoCircular = "Circle".equals(propPhotoShape.getValue());
            }
            logTypographyDebug("apply", key, s.fontSize);
            redraw();
        } catch (Exception ignored) { }
    }

    private void alignBy(String mode) {
        if (selectedKeys.size() < 2) return;
        snapshotForUndo();
        String baseKey = selectedKeys.iterator().next();
        TemplateRegion base = regions.get(baseKey);
        if (base == null) return;
        for (String key : selectedKeys) {
            TemplateRegion r = regions.get(key);
            if (r == null || style(key).locked) continue;
            double nx = r.x();
            if ("left".equals(mode)) nx = base.x();
            else if ("center".equals(mode)) nx = (base.x() + base.width() / 2) - r.width() / 2;
            else if ("right".equals(mode)) nx = (base.x() + base.width()) - r.width();
            regions.put(key, new TemplateRegion(r.side(), r.fieldName(), clamp(nx, 0, 1 - r.width()), r.y(), r.width(), r.height()));
        }
        redraw();
    }

    private void distribute(boolean horizontal) {
        if (selectedKeys.size() < 3) return;
        snapshotForUndo();
        List<String> keys = new ArrayList<>(selectedKeys);
        keys.sort(horizontal
            ? Comparator.comparingDouble(k -> regions.get(k).x())
            : Comparator.comparingDouble(k -> regions.get(k).y()));
        TemplateRegion first = regions.get(keys.get(0));
        TemplateRegion last = regions.get(keys.get(keys.size() - 1));
        if (first == null || last == null) return;
        double start = horizontal ? first.x() : first.y();
        double end = horizontal ? last.x() : last.y();
        double step = (end - start) / (keys.size() - 1);
        for (int i = 1; i < keys.size() - 1; i++) {
            String k = keys.get(i);
            TemplateRegion r = regions.get(k);
            if (r == null || style(k).locked) continue;
            if (horizontal) regions.put(k, new TemplateRegion(r.side(), r.fieldName(), clamp(start + step * i, 0, 1 - r.width()), r.y(), r.width(), r.height()));
            else regions.put(k, new TemplateRegion(r.side(), r.fieldName(), r.x(), clamp(start + step * i, 0, 1 - r.height()), r.width(), r.height()));
        }
        redraw();
    }

    private void reorder(int delta) {
        if (selectedKeys.size() != 1) return;
        String key = selectedKeys.iterator().next();
        List<Map.Entry<String, TemplateRegion>> entries = new ArrayList<>(regions.entrySet());
        int i = -1;
        for (int idx = 0; idx < entries.size(); idx++) if (entries.get(idx).getKey().equals(key)) { i = idx; break; }
        if (i < 0) return;
        int j = clampInt(i + delta, 0, entries.size() - 1);
        if (i == j) return;
        snapshotForUndo();
        var item = entries.remove(i);
        entries.add(j, item);
        regions.clear();
        for (var e : entries) regions.put(e.getKey(), e.getValue());
        redraw();
    }

    private void sendExtreme(boolean toFront) {
        if (selectedKeys.size() != 1) return;
        String key = selectedKeys.iterator().next();
        List<Map.Entry<String, TemplateRegion>> entries = new ArrayList<>(regions.entrySet());
        int idx = -1;
        for (int i = 0; i < entries.size(); i++) if (entries.get(i).getKey().equals(key)) { idx = i; break; }
        if (idx < 0) return;
        snapshotForUndo();
        var item = entries.remove(idx);
        if (toFront) entries.add(item); else entries.add(0, item);
        regions.clear();
        for (var e : entries) regions.put(e.getKey(), e.getValue());
        redraw();
    }

    private int parseInt(String s, int d) {
        try { return Integer.parseInt(s.trim()); } catch (Exception e) { return d; }
    }
    private int clampInt(int v, int lo, int hi) { return Math.max(lo, Math.min(hi, v)); }

    private static final class FieldStyle {
        int fontSize = 12;
        boolean bold = false;
        String align = "Center";
        boolean locked = false;
        boolean visible = true;
        boolean photoCircular = false;

        FieldStyle copy() {
            FieldStyle c = new FieldStyle();
            c.fontSize = fontSize;
            c.bold = bold;
            c.align = align;
            c.locked = locked;
            c.visible = visible;
            c.photoCircular = photoCircular;
            return c;
        }
    }
}
