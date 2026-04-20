package org.financial;

import javafx.fxml.FXML;
import javafx.fxml.Initializable;
import javafx.application.Platform;
import javafx.concurrent.Task;
import javafx.scene.control.*;
import javafx.scene.control.cell.CheckBoxTableCell;
import javafx.scene.control.cell.PropertyValueFactory;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.collections.transformation.FilteredList;
import javafx.geometry.Pos;
import javafx.scene.canvas.Canvas;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;

import java.io.BufferedWriter;
import java.io.FileWriter;
import java.nio.charset.StandardCharsets;
import javafx.scene.input.Dragboard;
import javafx.scene.input.TransferMode;
import javafx.stage.FileChooser;
import javafx.stage.Stage;

import java.io.File;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.ResourceBundle;
import java.util.prefs.Preferences;

public class PrimaryController implements Initializable {

    @FXML private Label statusLabel;
    @FXML private Label instructionLabel;
    @FXML private TextField contactNumber;
    @FXML private TextArea address;
    @FXML private TextField emergencyName;
    @FXML private TextField emergencyContact;
    @FXML private ImageView photoPreview;
    @FXML private TextField name;
    @FXML private TextField idNumber;
    @FXML private TextField position;
    @FXML private TextField department;
    @FXML private DatePicker dateOfBirth;
    @FXML private TableView<IdRow> idTable;
    @FXML private TableColumn<IdRow, Boolean> colSelect;
    @FXML private TableColumn<IdRow, String> colName;
    @FXML private TableColumn<IdRow, String> colIdNumber;
    @FXML private TableColumn<IdRow, Void> colActions;
    @FXML private CheckBox selectAllRecordsCheck;
    @FXML private ToggleButton recordsActionToggle;
    @FXML private ImageView referenceFrontPreview;
    @FXML private ImageView referenceBackPreview;
    @FXML private ScrollPane dataEntryPane;
    @FXML private VBox profilePane;
    @FXML private VBox templatePane;
    @FXML private Button navDataEntryBtn;
    @FXML private Button navProfileBtn;
    @FXML private Button navTemplateBtn;
    @FXML private Button navSettingsBtn;
    @FXML private VBox settingsPane;
    @FXML private Button exportDatabaseBtn;
    @FXML private Button importDatabaseBtn;
    @FXML private TextField recordSearchField;
    @FXML private ComboBox<String> recordFilterCombo;
    @FXML private TextField organizationName;
    @FXML private TextField organizationType;
    @FXML private TextField idPrefix;
    @FXML private ImageView excelPreviewFront;
    @FXML private ImageView excelPreviewBack;
    @FXML private TextArea excelImportLog;
    @FXML private Label excelPreviewGeneratedId;
    @FXML private VBox customFieldsFrontInputsBox;
    @FXML private VBox customFieldsBackInputsBox;
    @FXML private Label customBackHintLabel;
    @FXML private ListView<CustomFieldDef> customFieldList;
    @FXML private TextField customFieldNameInput;
    @FXML private CheckBox customFieldFrontCheck;
    @FXML private CheckBox customFieldBackCheck;
    private final ObservableList<IdRow> idRows = FXCollections.observableArrayList();
    private final FilteredList<IdRow> filteredRows = new FilteredList<>(idRows, row -> true);
    private IdRecord currentRecord;
    private String currentPhotoPath = "";
    private String referenceFrontPath = "";
    private String referenceBackPath = "";
    private java.util.List<ExcelIdReader.ParsedRow> excelParsedRows = List.of();
    private Path lastExcelPath;
    private boolean syncingSelectAll;
    private boolean recordsActionMode;
    private final Map<String, TextInputControl> customFieldInputs = new LinkedHashMap<>();

    private static final Path PHOTOS_DIR = Path.of(System.getProperty("user.home"), "IdGenerator", "photos");
    private static final Path REFERENCE_DIR = Path.of(System.getProperty("user.home"), "IdGenerator", "reference");
    private static final Preferences PREFS = Preferences.userNodeForPackage(PrimaryController.class);
    private static final String PREF_ORG_NAME = "org_name";
    private static final String PREF_ORG_TYPE = "org_type";
    private static final String PREF_ID_PREFIX = "id_prefix";
    private static final DateTimeFormatter DB_EXPORT_TS = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    @Override
    public void initialize(URL location, ResourceBundle resources) {
        colName.setCellValueFactory(new PropertyValueFactory<>("name"));
        colIdNumber.setCellValueFactory(new PropertyValueFactory<>("idNumber"));
        setupSelectColumn();
        setupActionColumn();
        idTable.setItems(filteredRows);
        idTable.getSelectionModel().setSelectionMode(SelectionMode.MULTIPLE);
        idTable.getSelectionModel().selectedItemProperty().addListener((o, oldVal, newVal) -> {
            if (newVal != null) loadRecord(newVal.getId());
        });
        if (selectAllRecordsCheck != null) {
            selectAllRecordsCheck.selectedProperty().addListener((o, wasSelected, isSelected) -> {
                if (syncingSelectAll) return;
                if (recordsActionMode) {
                    for (IdRow row : idTable.getItems()) {
                        row.setSelected(isSelected);
                    }
                } else {
                    if (isSelected) {
                        idTable.getSelectionModel().selectAll();
                    } else {
                        idTable.getSelectionModel().clearSelection();
                    }
                }
            });
        }
        idTable.getSelectionModel().getSelectedItems().addListener((javafx.collections.ListChangeListener<IdRow>) c -> syncSelectAllState());
        filteredRows.addListener((javafx.collections.ListChangeListener<IdRow>) c -> syncSelectAllState());
        if (recordsActionToggle != null) {
            recordsActionToggle.selectedProperty().addListener((o, wasOn, isOn) -> setRecordsActionMode(isOn));
        }
        if (recordSearchField != null) {
            recordSearchField.textProperty().addListener((o, oldVal, newVal) -> applyRecordFilter());
        }
        if (recordFilterCombo != null) {
            recordFilterCombo.getItems().setAll("All", "Name", "Identifier");
            recordFilterCombo.setValue("All");
            recordFilterCombo.valueProperty().addListener((o, oldVal, newVal) -> applyRecordFilter());
        }

        setupPhotoDragDrop();
        reloadIdTable();
        loadReferenceDesign();
        initCustomFieldManager();
        refreshCustomFieldInputs();
        loadOrganizationProfile();
        setupOrganizationListeners();
        refreshNavGating();
        onShowProfile();
        onNew();
    }

    private void setupSelectColumn() {
        if (colSelect == null) return;
        colSelect.setCellValueFactory(cellData -> cellData.getValue().selectedProperty());
        colSelect.setCellFactory(CheckBoxTableCell.forTableColumn(colSelect));
        colSelect.setEditable(true);
        idTable.setEditable(true);
        colSelect.setVisible(false);
    }

    private void setRecordsActionMode(boolean on) {
        recordsActionMode = on;
        if (colSelect != null) colSelect.setVisible(on);
        if (selectAllRecordsCheck != null) {
            selectAllRecordsCheck.setManaged(on);
            selectAllRecordsCheck.setVisible(on);
            if (!on) selectAllRecordsCheck.setSelected(false);
        }
        if (!on) {
            for (IdRow r : idRows) r.setSelected(false);
            idTable.getSelectionModel().clearSelection();
        }
        syncSelectAllState();
    }

    private void syncSelectAllState() {
        if (selectAllRecordsCheck == null) return;
        syncingSelectAll = true;
        try {
            int totalVisible = idTable.getItems() != null ? idTable.getItems().size() : 0;
            int selected = recordsActionMode
                ? (int) idTable.getItems().stream().filter(IdRow::isSelected).count()
                : idTable.getSelectionModel().getSelectedItems().size();
            selectAllRecordsCheck.setSelected(totalVisible > 0 && selected == totalVisible);
        } finally {
            syncingSelectAll = false;
        }
    }

    private List<IdRow> getActionRows() {
        if (recordsActionMode) {
            return idTable.getItems().stream().filter(IdRow::isSelected).toList();
        }
        return new java.util.ArrayList<>(idTable.getSelectionModel().getSelectedItems());
    }

    private void setupActionColumn() {
        if (colActions == null) return;
        colActions.setCellFactory(col -> new TableCell<>() {
            private final Button previewBtn = new Button("👁");
            private final Button deleteBtn = new Button("🗑");
            private final HBox box = new HBox(8, previewBtn, deleteBtn);
            {
                box.setAlignment(Pos.CENTER);
                previewBtn.getStyleClass().add("table-action-icon");
                deleteBtn.getStyleClass().addAll("table-action-icon", "danger-icon");
                previewBtn.setTooltip(new Tooltip("Preview record"));
                deleteBtn.setTooltip(new Tooltip("Delete record"));

                previewBtn.setOnAction(e -> {
                    IdRow row = getTableView().getItems().get(getIndex());
                    previewRecordRow(row);
                });
                deleteBtn.setOnAction(e -> {
                    IdRow row = getTableView().getItems().get(getIndex());
                    deleteRecordRow(row);
                });
            }
            @Override
            protected void updateItem(Void item, boolean empty) {
                super.updateItem(item, empty);
                setGraphic(empty ? null : box);
            }
        });
    }

    private void applyRecordFilter() {
        String q = recordSearchField == null || recordSearchField.getText() == null ? "" : recordSearchField.getText().trim().toLowerCase();
        String filter = (recordFilterCombo == null || recordFilterCombo.getValue() == null) ? "All" : recordFilterCombo.getValue();

        filteredRows.setPredicate(row -> {
            if (q.isEmpty()) return true;
            String name = row.getName() != null ? row.getName().toLowerCase() : "";
            String idn = row.getIdNumber() != null ? row.getIdNumber().toLowerCase() : "";
            return switch (filter) {
                case "Name" -> name.contains(q);
                case "Identifier" -> idn.contains(q);
                default -> name.contains(q) || idn.contains(q);
            };
        });
    }

    private void refreshNavGating() {
        boolean profileOk = isProfileComplete();
        if (navTemplateBtn != null) navTemplateBtn.setDisable(!profileOk);
        if (navDataEntryBtn != null) navDataEntryBtn.setDisable(!profileOk);
    }

    private boolean isProfileComplete() {
        String n = organizationName != null && organizationName.getText() != null ? organizationName.getText().trim() : "";
        String t = organizationType != null && organizationType.getText() != null ? organizationType.getText().trim() : "";
        String p = idPrefix != null && idPrefix.getText() != null ? idPrefix.getText().trim() : "";
        return !n.isEmpty() && !t.isEmpty() && !p.isEmpty();
    }

    private void loadOrganizationProfile() {
        organizationName.setText(PREFS.get(PREF_ORG_NAME, ""));
        organizationType.setText(PREFS.get(PREF_ORG_TYPE, ""));
        idPrefix.setText(PREFS.get(PREF_ID_PREFIX, ""));
    }

    private void saveOrganizationProfile() {
        PREFS.put(PREF_ORG_NAME, organizationName.getText().trim());
        PREFS.put(PREF_ORG_TYPE, organizationType.getText().trim());
        PREFS.put(PREF_ID_PREFIX, idPrefix.getText().trim().toUpperCase());
    }

    private void setupOrganizationListeners() {
        organizationName.focusedProperty().addListener((o, oldVal, newVal) -> {
            if (!newVal) {
                saveOrganizationProfile();
                refreshNavGating();
            }
        });
        organizationType.focusedProperty().addListener((o, oldVal, newVal) -> {
            if (!newVal) {
                saveOrganizationProfile();
                refreshNavGating();
            }
        });
        idPrefix.focusedProperty().addListener((o, oldVal, newVal) -> {
            if (!newVal) {
                saveOrganizationProfile();
                refreshNavGating();
            }
        });
    }

    private void setupPhotoDragDrop() {
        photoPreview.setOnDragOver(e -> {
            if (e.getDragboard().hasFiles()) {
                e.acceptTransferModes(TransferMode.COPY);
            }
            e.consume();
        });
        photoPreview.setOnDragDropped(e -> {
            Dragboard db = e.getDragboard();
            if (db.hasFiles() && !db.getFiles().isEmpty()) {
                File f = db.getFiles().get(0);
                String lower = f.getName().toLowerCase();
                if (lower.endsWith(".jpg") || lower.endsWith(".jpeg") || lower.endsWith(".png") || lower.endsWith(".gif")) {
                    setPhotoFromFile(f);
                } else {
                    showStatus("Please drop a valid image (jpg, png, gif).");
                }
            }
            e.setDropCompleted(true);
            e.consume();
        });
    }

    private void setPhotoFromFile(File file) {
        try {
            Files.createDirectories(PHOTOS_DIR);
            String name = System.currentTimeMillis() + "_" + file.getName();
            Path dest = PHOTOS_DIR.resolve(name);
            Files.copy(file.toPath(), dest, StandardCopyOption.REPLACE_EXISTING);
            currentPhotoPath = dest.toAbsolutePath().toString();
            photoPreview.setImage(new Image("file:" + currentPhotoPath));
            clearStatus();
        } catch (Exception ex) {
            showStatus("Failed to set photo: " + ex.getMessage());
        }
    }

    @FXML
    private void onBrowsePhoto() {
        FileChooser fc = new FileChooser();
        fc.setTitle("Select photo");
        fc.getExtensionFilters().add(new FileChooser.ExtensionFilter("Images", "*.jpg", "*.jpeg", "*.png", "*.gif"));
        File f = fc.showOpenDialog(photoPreview.getScene().getWindow());
        if (f != null) setPhotoFromFile(f);
    }

    @FXML
    private void onClearPhoto() {
        currentPhotoPath = "";
        photoPreview.setImage(null);
        clearStatus();
    }

    @FXML
    private void onSave() {
        if (!validateRequiredForSave()) return;
        clearStatus();
        saveOrganizationProfile();
        IdRecord r = formToRecord();
        try {
            if (currentRecord == null || currentRecord.id() <= 0) {
                IdRecord inserted = DbHelper.insertIdRecord(r);
                saveCustomFieldsForRecord(inserted.id());
                IdRow row = new IdRow(inserted.id(), inserted.name(), inserted.idNumber());
                row.selectedProperty().addListener((o, oldV, newV) -> syncSelectAllState());
                idRows.add(row);
                // If offline, this is queued and will upload automatically when online.
                SupabaseStorageService.enqueueIdPdfUpload(inserted);
                // Reset form so user can immediately add another ID
                onNew();
                showStatus("Saved. Form cleared for next ID.");
            } else {
                IdRecord updated = new IdRecord(currentRecord.id(), currentRecord.qrUid(), r.photoPath(), r.name(), r.idNumber(), r.position(), r.department(), r.dateOfBirth(),
                    r.contactNumber(), r.address(), r.emergencyName(), r.emergencyContact());
                DbHelper.updateIdRecord(updated);
                saveCustomFieldsForRecord(updated.id());
                currentRecord = updated;
                // Re-upload (or queue) PDF to Supabase Storage so the same QR URL serves updated content
                SupabaseStorageService.enqueueIdPdfUpload(updated);
                IdRow row = idTable.getSelectionModel().getSelectedItem();
                if (row != null) {
                    row.setName(updated.name());
                    row.setIdNumber(updated.idNumber());
                    idTable.refresh();
                }
            }
            showStatus("Saved.");
        } catch (Exception ex) {
            showStatus("Error: " + ex.getMessage());
        }
    }

    private boolean validateRequiredForSave() {
        if (name == null || name.getText() == null || name.getText().trim().isEmpty()) {
            showStatus("Full Name cannot be empty.", true);
            return false;
        }
        if (idNumber == null || idNumber.getText() == null || idNumber.getText().trim().isEmpty()) {
            showStatus("Unique Identifier cannot be empty. Enter a value or use Generate Identifier.", true);
            return false;
        }
        if (currentPhotoPath == null || currentPhotoPath.trim().isEmpty()) {
            showStatus("Profile Photo cannot be empty. Browse for an image.", true);
            return false;
        }
        for (Map.Entry<String, TextInputControl> e : customFieldInputs.entrySet()) {
            TextInputControl c = e.getValue();
            if (c == null) continue;
            String v = c.getText();
            if (v == null || v.trim().isEmpty()) {
                showStatus("Field \"" + c.getPromptText() + "\" cannot be empty.", true);
                return false;
            }
        }
        return true;
    }

    private void initCustomFieldManager() {
        if (customFieldList == null) return;
        customFieldList.getSelectionModel().selectedItemProperty().addListener((o, oldV, selected) -> {
            if (selected == null) return;
            if (customFieldNameInput != null) customFieldNameInput.setText(selected.displayName());
            if (customFieldFrontCheck != null) customFieldFrontCheck.setSelected(selected.showFront());
            if (customFieldBackCheck != null) customFieldBackCheck.setSelected(selected.showBack());
        });
        refreshCustomFieldManager();

        DynamicFieldSync.versionProperty().addListener((o, oldV, newV) -> {
            // If the user is currently on the Entry pane, update inputs immediately.
            if (dataEntryPane != null && dataEntryPane.isVisible()) {
                Platform.runLater(this::refreshCustomFieldInputs);
            }
        });
    }

    private void refreshCustomFieldManager() {
        if (customFieldList == null) return;
        List<CustomFieldDef> rows = new ArrayList<>();
        rows.add(new CustomFieldDef("default:name", "Full Name", true, false, true));
        rows.add(new CustomFieldDef("default:id_number", "Unique Identifier", true, false, true));
        rows.addAll(DbHelper.getActiveCustomFields());
        customFieldList.getItems().setAll(rows);
    }

    private void refreshCustomFieldInputs() {
        if (customFieldsFrontInputsBox == null || customFieldsBackInputsBox == null) return;
        List<CustomFieldDef> activeCustom = DbHelper.getActiveCustomFields();
        Map<String, String> existing = collectCustomFieldInputValues();
        Map<String, String> loaded = (currentRecord != null && currentRecord.id() > 0)
            ? DbHelper.getCustomValuesForRecord(currentRecord.id())
            : Map.of();
        customFieldInputs.clear();
        customFieldsFrontInputsBox.getChildren().clear();
        customFieldsBackInputsBox.getChildren().clear();
        boolean anyBack = false;
        for (CustomFieldDef f : activeCustom) {
            if (f == null || f.fieldKey() == null || f.fieldKey().isBlank()) continue;
            VBox wrap = new VBox(4);
            Label label = new Label(f.displayName());
            label.getStyleClass().add("field-label");
            TextField input = new TextField();
            input.setPromptText("Enter " + f.displayName());
            String key = f.fieldKey();
            String val = existing.getOrDefault(key, loaded.getOrDefault(key, ""));
            input.setText(val);
            customFieldInputs.put(key, input);
            wrap.getChildren().addAll(label, input);

            if (f.showFront()) {
                customFieldsFrontInputsBox.getChildren().add(wrap);
            } else if (f.showBack()) {
                anyBack = true;
                customFieldsBackInputsBox.getChildren().add(wrap);
            }
        }

        if (customBackHintLabel != null) {
            customBackHintLabel.setVisible(!anyBack);
            customBackHintLabel.setManaged(!anyBack);
        }
    }

    private Map<String, String> collectCustomFieldInputValues() {
        Map<String, String> map = new LinkedHashMap<>();
        for (Map.Entry<String, TextInputControl> e : customFieldInputs.entrySet()) {
            TextInputControl c = e.getValue();
            map.put(e.getKey(), c == null || c.getText() == null ? "" : c.getText().trim());
        }
        return map;
    }

    private void saveCustomFieldsForRecord(int recordId) {
        if (recordId <= 0) return;
        DbHelper.saveCustomValuesForRecord(recordId, collectCustomFieldInputValues());
    }

    private Map<String, String> blankCustomFieldValues() {
        Map<String, String> values = new LinkedHashMap<>();
        for (CustomFieldDef f : DbHelper.getActiveCustomFields()) {
            values.put(f.fieldKey(), "");
        }
        return values;
    }

    @FXML
    private void onNew() {
        currentRecord = null;
        currentPhotoPath = "";
        clearForm();
        idTable.getSelectionModel().clearSelection();
        clearStatus();
    }

    @FXML
    private void onGenerateIdentifier() {
        saveOrganizationProfile();
        String prefix = resolveIdPrefix();
        LocalDate today = LocalDate.now();
        String generated = new IdNumberGenerator(prefix, today).next();
        idNumber.setText(generated);
        showStatus("Identifier generated.");
    }

    @FXML
    private void onShowDataEntry() {
        if (!isProfileComplete()) {
            showStatus("Set up your Profile first.", true);
            onShowProfile();
            return;
        }
        if (profilePane != null) {
            profilePane.setVisible(false);
            profilePane.setManaged(false);
        }
        if (settingsPane != null) {
            settingsPane.setVisible(false);
            settingsPane.setManaged(false);
        }
        if (dataEntryPane != null) {
            dataEntryPane.setVisible(true);
            dataEntryPane.setManaged(true);
        }
        if (templatePane != null) {
            templatePane.setVisible(false);
            templatePane.setManaged(false);
        }
        refreshCustomFieldInputs();
        setInstruction("Step 3: Encode IDs in Entry");
        setNavActive(navDataEntryBtn);
    }

    @FXML
    private void onShowTemplate() {
        if (!isProfileComplete()) {
            showStatus("Set up your Profile first.", true);
            onShowProfile();
            return;
        }
        if (profilePane != null) {
            profilePane.setVisible(false);
            profilePane.setManaged(false);
        }
        if (settingsPane != null) {
            settingsPane.setVisible(false);
            settingsPane.setManaged(false);
        }
        if (dataEntryPane != null) {
            dataEntryPane.setVisible(false);
            dataEntryPane.setManaged(false);
        }
        if (templatePane != null) {
            templatePane.setVisible(true);
            templatePane.setManaged(true);
        }
        refreshCustomFieldManager();
        setInstruction("Step 2: Design your Template");
        setNavActive(navTemplateBtn);
    }

    @FXML
    private void onAddCustomField() {
        try {
            String name = customFieldNameInput != null ? customFieldNameInput.getText().trim() : "";
            boolean front = customFieldFrontCheck != null && customFieldFrontCheck.isSelected();
            boolean back = customFieldBackCheck != null && customFieldBackCheck.isSelected();
            DbHelper.addCustomField(name, front, back);
            refreshCustomFieldManager();
            refreshCustomFieldInputs();
            DynamicFieldSync.bump();
            showStatus("Custom field added.");
        } catch (Exception ex) {
            showStatus("Field add error: " + ex.getMessage(), true);
        }
    }

    @FXML
    private void onUpdateCustomField() {
        CustomFieldDef selected = customFieldList != null ? customFieldList.getSelectionModel().getSelectedItem() : null;
        if (selected == null || selected.defaultField()) {
            showStatus("Select a custom (non-default) field to update.", true);
            return;
        }
        try {
            String name = customFieldNameInput != null ? customFieldNameInput.getText().trim() : "";
            boolean front = customFieldFrontCheck != null && customFieldFrontCheck.isSelected();
            boolean back = customFieldBackCheck != null && customFieldBackCheck.isSelected();
            DbHelper.updateCustomField(selected.fieldKey(), name, front, back);
            refreshCustomFieldManager();
            refreshCustomFieldInputs();
            DynamicFieldSync.bump();
            showStatus("Custom field updated.");
        } catch (Exception ex) {
            showStatus("Field update error: " + ex.getMessage(), true);
        }
    }

    @FXML
    private void onDeleteCustomField() {
        CustomFieldDef selected = customFieldList != null ? customFieldList.getSelectionModel().getSelectedItem() : null;
        if (selected == null || selected.defaultField()) {
            showStatus("Default fields cannot be deleted.", true);
            return;
        }
        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION,
            "Delete custom field '" + selected.displayName() + "'?\nStored values will be kept as hidden data.",
            ButtonType.CANCEL, ButtonType.OK);
        if (confirm.showAndWait().orElse(ButtonType.CANCEL) != ButtonType.OK) return;
        try {
            DbHelper.deactivateCustomField(selected.fieldKey());
            refreshCustomFieldManager();
            refreshCustomFieldInputs();
            DynamicFieldSync.bump();
            showStatus("Custom field deleted from active layout.");
        } catch (Exception ex) {
            showStatus("Field delete error: " + ex.getMessage(), true);
        }
    }

    @FXML
    private void onShowProfile() {
        if (profilePane != null) {
            profilePane.setVisible(true);
            profilePane.setManaged(true);
        }
        if (dataEntryPane != null) {
            dataEntryPane.setVisible(false);
            dataEntryPane.setManaged(false);
        }
        if (templatePane != null) {
            templatePane.setVisible(false);
            templatePane.setManaged(false);
        }
        if (settingsPane != null) {
            settingsPane.setVisible(false);
            settingsPane.setManaged(false);
        }
        setInstruction("Step 1: Set up Profile (one-time)");
        setNavActive(navProfileBtn);
    }

    @FXML
    private void onShowSettings() {
        if (profilePane != null) {
            profilePane.setVisible(false);
            profilePane.setManaged(false);
        }
        if (dataEntryPane != null) {
            dataEntryPane.setVisible(false);
            dataEntryPane.setManaged(false);
        }
        if (templatePane != null) {
            templatePane.setVisible(false);
            templatePane.setManaged(false);
        }
        if (settingsPane != null) {
            settingsPane.setVisible(true);
            settingsPane.setManaged(true);
        }
        setInstruction("Settings: backup and restore");
        setNavActive(navSettingsBtn);
    }

    @FXML
    private void onExportDatabase() {
        Stage owner = getStage();
        FileChooser fc = new FileChooser();
        fc.setTitle("Export backup");
        fc.setInitialFileName("idgenerator-backup-" + DB_EXPORT_TS.format(LocalDateTime.now()) + ".idgbak");
        fc.getExtensionFilters().addAll(
            new FileChooser.ExtensionFilter("ID Generator Backup", "*.idgbak"),
            new FileChooser.ExtensionFilter("SQLite database (legacy)", "*.db")
        );
        File out = fc.showSaveDialog(owner);
        if (out == null) {
            return;
        }
        Path target = out.toPath();

        setDatabaseOperationInProgress(true);
        Task<Void> task = new Task<>() {
            @Override
            protected Void call() throws Exception {
                String name = target.getFileName() != null ? target.getFileName().toString().toLowerCase() : "";
                if (name.endsWith(".db")) {
                    DbHelper.exportDatabaseTo(target);
                } else {
                    BackupBundleService.exportBundle(target);
                }
                return null;
            }
        };
        task.setOnSucceeded(e -> {
            setDatabaseOperationInProgress(false);
            showStatus("Backup exported successfully.\n\n" + target.toAbsolutePath());
        });
        task.setOnFailed(e -> {
            setDatabaseOperationInProgress(false);
            Throwable ex = task.getException();
            String detail = ex != null && ex.getMessage() != null ? ex.getMessage() : "Unknown error";
            showStatus("Export failed: " + detail, true);
        });
        Thread t = new Thread(task, "idgenerator-db-export");
        t.setDaemon(true);
        t.start();
    }

    @FXML
    private void onImportDatabase() {
        Alert confirm = new Alert(Alert.AlertType.WARNING);
        confirm.setTitle("ID Generator");
        confirm.setHeaderText("Replace local database?");
        confirm.setContentText(
            "All local data may be replaced by the selected backup: ID records, profile setup, template layout/style, and related data.\n\n"
                + "A backup of your current database will be saved in the database folder before replacing.\n\n"
                + "Continue?");
        confirm.getButtonTypes().setAll(ButtonType.CANCEL, ButtonType.OK);
        confirm.initOwner(getStage());
        if (confirm.showAndWait().orElse(ButtonType.CANCEL) != ButtonType.OK) {
            return;
        }

        FileChooser fc = new FileChooser();
        fc.setTitle("Select backup to import");
        fc.getExtensionFilters().addAll(
            new FileChooser.ExtensionFilter("ID Generator Backup", "*.idgbak"),
            new FileChooser.ExtensionFilter("SQLite database (legacy)", "*.db"),
            new FileChooser.ExtensionFilter("All files", "*.*")
        );
        File in = fc.showOpenDialog(getStage());
        if (in == null) {
            return;
        }
        Path source = in.toPath();

        setDatabaseOperationInProgress(true);
        Task<Path> task = new Task<>() {
            @Override
            protected Path call() throws Exception {
                String name = source.getFileName() != null ? source.getFileName().toString().toLowerCase() : "";
                if (name.endsWith(".db")) {
                    return DbHelper.importDatabaseReplacing(source);
                }
                return BackupBundleService.importBundle(source);
            }
        };
        task.setOnSucceeded(e -> {
            reloadUiAfterDatabaseChange();
            Path autoBackup = task.getValue();
            String extra = autoBackup != null
                ? "\n\nPrevious database backed up to:\n" + autoBackup.toAbsolutePath()
                : "";
            showStatus("Database imported. The app has reloaded data from the new file." + extra);
            autoReuploadSupabasePdfsForImportedData();
        });
        task.setOnFailed(e -> {
            setDatabaseOperationInProgress(false);
            Throwable ex = task.getException();
            String detail = ex != null && ex.getMessage() != null ? ex.getMessage() : "Unknown error";
            showStatus("Import failed: " + detail + "\n\nYour previous database was restored if a backup was made.", true);
        });
        Thread t = new Thread(task, "idgenerator-db-import");
        t.setDaemon(true);
        t.start();
    }

    private void autoReuploadSupabasePdfsForImportedData() {
        // If Supabase isn't configured on this PC, we can't generate cloud-based QR/PDF.
        if (SupabaseConfig.load().isEmpty()) {
            setDatabaseOperationInProgress(false);
            return;
        }

        setDatabaseOperationInProgress(true);
        Task<int[]> task = new Task<>() {
            @Override
            protected int[] call() {
                int ok = 0;
                int failed = 0;
                List<IdRecord> records = DbHelper.getAllIdRecords();
                for (IdRecord r : records) {
                    if (r == null) continue;
                    try {
                        var url = SupabaseStorageService.uploadIdPdfAndGetPublicUrl(r);
                        if (url.isPresent()) {
                            ok++;
                            SupabaseStorageService.deleteLegacyIdPdfOnly(r);
                        } else {
                            failed++;
                        }
                    } catch (Exception ex) {
                        failed++;
                    }
                }
                return new int[]{ok, failed};
            }
        };

        task.setOnSucceeded(e -> {
            setDatabaseOperationInProgress(false);
            int[] r = task.getValue();
            int ok = r != null && r.length > 0 ? r[0] : 0;
            int failed = r != null && r.length > 1 ? r[1] : 0;
            showStatus("Supabase PDFs regenerated after import.\nUploaded: " + ok + "\nFailed: " + failed);
        });
        task.setOnFailed(e -> {
            setDatabaseOperationInProgress(false);
            Throwable ex = task.getException();
            String detail = ex != null && ex.getMessage() != null ? ex.getMessage() : "Unknown error";
            showStatus("Supabase regeneration failed: " + detail, true);
        });

        Thread t = new Thread(task, "idgenerator-supabase-regenerate-after-import");
        t.setDaemon(true);
        t.start();
    }

    private void setDatabaseOperationInProgress(boolean busy) {
        if (exportDatabaseBtn != null) {
            exportDatabaseBtn.setDisable(busy);
        }
        if (importDatabaseBtn != null) {
            importDatabaseBtn.setDisable(busy);
        }
        if (navProfileBtn != null) {
            navProfileBtn.setDisable(busy);
        }
        if (navTemplateBtn != null) {
            navTemplateBtn.setDisable(busy);
        }
        if (navDataEntryBtn != null) {
            navDataEntryBtn.setDisable(busy);
        }
        if (navSettingsBtn != null) {
            navSettingsBtn.setDisable(busy);
        }
    }

    private void reloadUiAfterDatabaseChange() {
        reloadIdTable();
        loadReferenceDesign();
        refreshCustomFieldManager();
        refreshCustomFieldInputs();
        onNew();
        refreshNavGating();
        clearStatus();
        DynamicFieldSync.bump();
    }

    private static final String NAV_ACTIVE_CLASS = "sidebar-link-active";

    private void setNavActive(Button activeBtn) {
        Button[] all = new Button[]{navProfileBtn, navTemplateBtn, navDataEntryBtn, navSettingsBtn};
        for (Button b : all) {
            if (b == null) continue;
            b.getStyleClass().remove(NAV_ACTIVE_CLASS);
        }
        if (activeBtn != null && !activeBtn.getStyleClass().contains(NAV_ACTIVE_CLASS)) {
            activeBtn.getStyleClass().add(NAV_ACTIVE_CLASS);
        }
    }

    private void setInstruction(String text) {
        if (instructionLabel != null) instructionLabel.setText(text);
    }

    private IdRecord formToRecord() {
        String dob = "";
        if (dateOfBirth != null && dateOfBirth.getValue() != null) {
            dob = dateOfBirth.getValue().format(DateTimeFormatter.ISO_LOCAL_DATE);
        }
        int id = currentRecord != null ? currentRecord.id() : 0;
        String qrUid = currentRecord != null ? currentRecord.qrUid() : "";
        String computedIdNumber;
        if (currentRecord != null && currentRecord.id() > 0) {
            String existing = idNumber.getText().trim();
            computedIdNumber = existing.isEmpty() ? currentRecord.idNumber() : existing;
        } else {
            String existing = idNumber.getText().trim();
            if (!existing.isEmpty()) {
                computedIdNumber = existing;
            } else {
                computedIdNumber = new IdNumberGenerator(resolveIdPrefix(), LocalDate.now()).next();
                idNumber.setText(computedIdNumber);
            }
        }
        // Some controls (contact/address/emergency) are not present in the simplified FXML.
        // Treat them as empty strings when missing.
        String cn = contactNumber != null ? contactNumber.getText().trim() : "";
        String adr = address != null ? address.getText().trim() : "";
        String en = emergencyName != null ? emergencyName.getText().trim() : "";
        String ec = emergencyContact != null ? emergencyContact.getText().trim() : "";

        String dept = department != null ? department.getText().trim() : "";

        String pos = position != null && position.getText() != null ? position.getText().trim() : "";

        return new IdRecord(id, qrUid, currentPhotoPath, name.getText().trim(), computedIdNumber, pos, dept, dob,
            cn, adr, en, ec);
    }

    private String resolveIdPrefix() {
        String prefix = idPrefix.getText().trim().toUpperCase();
        if (!prefix.isEmpty()) {
            return prefix;
        }
        String org = organizationName.getText().trim();
        if (!org.isEmpty()) {
            String[] parts = org.split("\\s+");
            StringBuilder initials = new StringBuilder();
            for (String p : parts) {
                if (!p.isBlank()) initials.append(Character.toUpperCase(p.charAt(0)));
                if (initials.length() == 4) break;
            }
            prefix = initials.isEmpty() ? "ORG" : initials.toString();
        } else {
            prefix = "ORG";
        }
        idPrefix.setText(prefix);
        return prefix;
    }

    private void clearForm() {
        if (contactNumber != null) contactNumber.clear();
        if (address != null) address.clear();
        if (emergencyName != null) emergencyName.clear();
        if (emergencyContact != null) emergencyContact.clear();
        photoPreview.setImage(null);
        name.clear();
        idNumber.clear();
        if (position != null) position.clear();
        if (department != null) department.clear();
        if (dateOfBirth != null) dateOfBirth.setValue(null);
        for (TextInputControl c : customFieldInputs.values()) {
            if (c != null) c.clear();
        }
    }

    private void loadRecord(int id) {
        IdRecord r = DbHelper.getIdRecord(id);
        if (r == null) return;
        currentRecord = r;
        currentPhotoPath = r.photoPath();
        if (contactNumber != null) contactNumber.setText(r.contactNumber());
        if (address != null) address.setText(r.address());
        if (emergencyName != null) emergencyName.setText(r.emergencyName());
        if (emergencyContact != null) emergencyContact.setText(r.emergencyContact());
        name.setText(r.name());
        idNumber.setText(r.idNumber());
        if (position != null) position.setText(r.position());
        if (department != null) {
            department.setText(r.department());
        }
        if (dateOfBirth != null) {
            if (r.dateOfBirth() != null && !r.dateOfBirth().isEmpty()) {
                try {
                    dateOfBirth.setValue(LocalDate.parse(r.dateOfBirth()));
                } catch (Exception ignored) {
                    dateOfBirth.setValue(null);
                }
            } else {
                dateOfBirth.setValue(null);
            }
        }
        if (r.photoPath() != null && !r.photoPath().isEmpty() && Files.exists(Path.of(r.photoPath()))) {
            photoPreview.setImage(new Image("file:" + r.photoPath()));
        } else {
            photoPreview.setImage(null);
        }
        Map<String, String> vals = DbHelper.getCustomValuesForRecord(r.id());
        for (Map.Entry<String, TextInputControl> e : customFieldInputs.entrySet()) {
            TextInputControl c = e.getValue();
            if (c != null) c.setText(vals.getOrDefault(e.getKey(), ""));
        }
        clearStatus();
    }

    private void reloadIdTable() {
        idRows.setAll(
            DbHelper.getAllIdRecords().stream()
                .map(r -> {
                    IdRow row = new IdRow(r.id(), r.name(), r.idNumber());
                    row.selectedProperty().addListener((o, oldV, newV) -> syncSelectAllState());
                    return row;
                })
                .toList()
        );
        syncSelectAllState();
    }

    @FXML
    private void onSetReferenceFront() {
        File f = chooseImageFile("Select front reference design");
        if (f != null) {
            try {
                Files.createDirectories(REFERENCE_DIR);
                Path dest = REFERENCE_DIR.resolve("front_" + f.getName());
                Files.copy(f.toPath(), dest, StandardCopyOption.REPLACE_EXISTING);
                referenceFrontPath = dest.toAbsolutePath().toString();
                referenceFrontPreview.setImage(new Image("file:" + referenceFrontPath));
                DbHelper.setReferenceDesign(referenceFrontPath, referenceBackPath);
                showStatus("Front reference set.");
            } catch (Exception ex) {
                showStatus("Failed: " + ex.getMessage());
            }
        }
    }

    @FXML
    private void onSetReferenceBack() {
        File f = chooseImageFile("Select back reference design");
        if (f != null) {
            try {
                Files.createDirectories(REFERENCE_DIR);
                Path dest = REFERENCE_DIR.resolve("back_" + f.getName());
                Files.copy(f.toPath(), dest, StandardCopyOption.REPLACE_EXISTING);
                referenceBackPath = dest.toAbsolutePath().toString();
                referenceBackPreview.setImage(new Image("file:" + referenceBackPath));
                DbHelper.setReferenceDesign(referenceFrontPath, referenceBackPath);
                showStatus("Back reference set.");
            } catch (Exception ex) {
                showStatus("Failed: " + ex.getMessage());
            }
        }
    }

    @FXML
    private void onDefineLayout() {
        IdRecord preview = currentRecord;
        if (preview == null) {
            IdRow selected = idTable.getSelectionModel().getSelectedItem();
            if (selected != null) {
                preview = DbHelper.getIdRecord(selected.getId());
            }
        }
        TemplateDesignerController.show(getStage(), referenceFrontPath, referenceBackPath, preview);
    }

    private void loadReferenceDesign() {
        String[] paths = DbHelper.getReferenceDesign();
        referenceFrontPath = paths[0];
        referenceBackPath = paths[1];
        if (!referenceFrontPath.isEmpty() && Files.exists(Path.of(referenceFrontPath))) {
            referenceFrontPreview.setImage(new Image("file:" + referenceFrontPath));
        }
        if (!referenceBackPath.isEmpty() && Files.exists(Path.of(referenceBackPath))) {
            referenceBackPreview.setImage(new Image("file:" + referenceBackPath));
        }
    }

    private File chooseImageFile(String title) {
        FileChooser fc = new FileChooser();
        fc.setTitle(title);
        fc.getExtensionFilters().add(new FileChooser.ExtensionFilter("Images", "*.jpg", "*.jpeg", "*.png", "*.gif", "*.bmp"));
        return fc.showOpenDialog(referenceFrontPreview.getScene().getWindow());
    }

    @FXML
    private void onPreviewGenerate() {
        List<IdRow> selectedRows = getActionRows();
        if (selectedRows.isEmpty()) {
            showStatus("Select at least one ID to preview or generate.");
            return;
        }
        try {
            int shown = 0;
            for (IdRow row : selectedRows) {
                IdRecord r = DbHelper.getIdRecord(row.getId());
                if (r == null) continue;
                String localBaseUrl = App.getServerBaseUrl();
                String cloudUrl = SupabaseStorageService.getPublicPdfUrlForRecord(r).orElse("");
                IdPreviewController.show(getStage(), r, referenceFrontPath, referenceBackPath, localBaseUrl, cloudUrl);
                shown++;
            }
            if (shown == 0) {
                showStatus("No valid selected IDs found for preview.", true);
            } else if (shown == 1) {
                showStatus("Preview opened.");
            } else {
                showStatus("Opened preview windows for " + shown + " selected IDs.");
            }
        } catch (Exception ex) {
            showStatus("Preview error: " + ex.getMessage(), true);
        }
    }

    private void previewRecordRow(IdRow selected) {
        IdRecord r = DbHelper.getIdRecord(selected.getId());
        if (r == null) return;
        try {
            String localBaseUrl = App.getServerBaseUrl();
            String cloudUrl = SupabaseStorageService.getPublicPdfUrlForRecord(r).orElse("");
            IdPreviewController.show(getStage(), r, referenceFrontPath, referenceBackPath, localBaseUrl, cloudUrl);
        } catch (Exception ex) {
            showStatus("Preview error: " + ex.getMessage());
        }
    }

    @FXML
    private void onBrowseExcel() {
        FileChooser fc = new FileChooser();
        fc.setTitle("Select Excel workbook");
        fc.getExtensionFilters().add(new FileChooser.ExtensionFilter("Excel", "*.xlsx"));
        File f = fc.showOpenDialog(getStage());
        if (f == null) return;
        if (excelImportLog != null) {
            excelImportLog.clear();
        }
        lastExcelPath = f.toPath();
        PhotoImageCache.clear();
        try {
            ExcelIdReader reader = new ExcelIdReader();
            ExcelIdReader.ExcelParseResult result = reader.read(lastExcelPath);
            if (!result.isOk()) {
                excelParsedRows = List.of();
                if (excelPreviewFront != null) excelPreviewFront.setImage(null);
                if (excelPreviewBack != null) excelPreviewBack.setImage(null);
                if (excelPreviewGeneratedId != null) {
                    excelPreviewGeneratedId.setText("");
                }
                appendExcelLog("Errors: " + String.join("; ", result.errors()));
                showStatus("Excel headers invalid. See log.", true);
                return;
            }
            excelParsedRows = result.rows();
            appendExcelLog("Loaded: " + lastExcelPath.getFileName() + " — " + excelParsedRows.size() + " data row(s). Columns: " + result.columnMap().keySet());
            refreshExcelPreviewFromExcel();
            showStatus("Excel loaded. Review preview and click Import valid rows.");
        } catch (Exception ex) {
            excelParsedRows = List.of();
            if (excelPreviewGeneratedId != null) {
                excelPreviewGeneratedId.setText("");
            }
            appendExcelLog("Read failed: " + ex.getMessage());
            showStatus("Excel error: " + ex.getMessage(), true);
        }
    }

    @FXML
    private void onImportExcel() {
        if (excelParsedRows == null || excelParsedRows.isEmpty()) {
            showStatus("Browse and load an Excel file first.", true);
            appendExcelLog("No rows loaded.");
            return;
        }
        saveOrganizationProfile();
        IdNumberGenerator idGen = new IdNumberGenerator(resolveIdPrefix(), LocalDate.now());
        int imported = 0;
        int skipped = 0;
        StringBuilder log = new StringBuilder();
        for (ExcelIdReader.ParsedRow row : excelParsedRows) {
            IdRecordValidator.ValidationResult vr = IdRecordValidator.validate(row);
            if (!vr.valid()) {
                skipped++;
                log.append("Row ").append(row.excelRowNumber()).append(" skipped: ").append(String.join(", ", vr.messages())).append("\n");
                continue;
            }
            for (String w : vr.messages()) {
                log.append("Row ").append(row.excelRowNumber()).append(" — ").append(w).append("\n");
            }
            String idNum = idGen.next();
            try {
                String importedPhotoPath = copyExcelPhotoToManagedStore(row.get("photo_path").trim(), row.excelRowNumber(), log);
                IdRecord r0 = new IdRecord(0, "", importedPhotoPath, row.get("name").trim(), idNum,
                    row.get("position").trim(), row.get("department").trim(), row.get("date_of_birth").trim(),
                    row.get("contact_number").trim(), row.get("address").trim(),
                    row.get("emergency_name").trim(), row.get("emergency_contact").trim());
                IdRecord inserted = DbHelper.insertIdRecord(r0);
                // Persist dynamic custom field values if the Excel template includes them.
                java.util.Map<String, String> customValues = new java.util.LinkedHashMap<>();
                for (CustomFieldDef cf : DbHelper.getActiveCustomFields()) {
                    if (cf == null || cf.fieldKey() == null || cf.fieldKey().isBlank()) continue;
                    customValues.put(cf.fieldKey(), row.get(cf.fieldKey()).trim());
                }
                DbHelper.saveCustomValuesForRecord(inserted.id(), customValues);
                IdRow addedRow = new IdRow(inserted.id(), inserted.name(), inserted.idNumber());
                addedRow.selectedProperty().addListener((o, oldV, newV) -> syncSelectAllState());
                idRows.add(addedRow);
                SupabaseStorageService.enqueueIdPdfUpload(inserted);
                imported++;
            } catch (Exception ex) {
                skipped++;
                log.append("Row ").append(row.excelRowNumber()).append(" DB error: ").append(ex.getMessage()).append("\n");
            }
        }
        reloadIdTable();
        appendExcelLog(log.toString());
        appendExcelLog("Imported " + imported + ", skipped " + skipped + ".");
        if (skipped > 0) {
            showStatus("Imported " + imported + " record(s). Skipped " + skipped + ". See Excel log.", true);
        } else {
            showStatus("Imported " + imported + " record(s).");
        }
    }

    /**
     * For portability, Excel-imported photo paths are copied into the app-managed photos folder.
     * If the file cannot be copied, keep original path so the row can still be imported.
     */
    private String copyExcelPhotoToManagedStore(String rawPath, int excelRowNumber, StringBuilder log) {
        String srcText = rawPath == null ? "" : rawPath.trim();
        if (srcText.isEmpty()) {
            return "";
        }
        Path source;
        try {
            source = Path.of(srcText);
        } catch (Exception ex) {
            if (log != null) {
                log.append("Row ").append(excelRowNumber)
                    .append(" photo warning: invalid path format, keeping original path.\n");
            }
            return srcText;
        }
        if (!source.isAbsolute() && lastExcelPath != null) {
            Path excelDir = lastExcelPath.toAbsolutePath().getParent();
            if (excelDir != null) {
                source = excelDir.resolve(source).normalize();
            }
        }
        if (!Files.isRegularFile(source)) {
            if (log != null) {
                log.append("Row ").append(excelRowNumber)
                    .append(" photo warning: file not found, keeping original path.\n");
            }
            return srcText;
        }
        try {
            Files.createDirectories(PHOTOS_DIR);
            String originalName = source.getFileName() != null ? source.getFileName().toString() : ("excel-photo-" + excelRowNumber + ".jpg");
            String managedName = System.currentTimeMillis() + "_" + excelRowNumber + "_" + originalName;
            Path dest = PHOTOS_DIR.resolve(managedName);
            Files.copy(source, dest, StandardCopyOption.REPLACE_EXISTING);
            return dest.toAbsolutePath().toString();
        } catch (Exception ex) {
            if (log != null) {
                log.append("Row ").append(excelRowNumber)
                    .append(" photo warning: copy failed (").append(ex.getMessage()).append("), keeping original path.\n");
            }
            return source.toAbsolutePath().toString();
        }
    }

    @FXML
    private void onDownloadExcelTemplate() {
        FileChooser fc = new FileChooser();
        fc.setTitle("Save Excel template");
        fc.setInitialFileName("id-import-template.xlsx");
        fc.getExtensionFilters().add(new FileChooser.ExtensionFilter("Excel", "*.xlsx"));
        File out = fc.showSaveDialog(getStage());
        if (out == null) return;
        try (java.io.FileOutputStream fos = new java.io.FileOutputStream(out)) {
            ExcelIdReader.writeTemplate(fos);
            showStatus("Excel template saved: " + out.getAbsolutePath());
        } catch (Exception ex) {
            showStatus("Template save error: " + ex.getMessage(), true);
        }
    }

    private void appendExcelLog(String text) {
        if (excelImportLog == null) return;
        if (text == null || text.isBlank()) return;
        String cur = excelImportLog.getText();
        excelImportLog.setText((cur.isBlank() ? "" : cur + "\n") + text);
    }

    private void refreshExcelPreviewFromExcel() {
        if (excelPreviewFront == null || excelPreviewBack == null) return;
        ExcelIdReader.ParsedRow previewRow = null;
        for (ExcelIdReader.ParsedRow row : excelParsedRows) {
            if (IdRecordValidator.validate(row).valid()) {
                previewRow = row;
                break;
            }
        }
        if (previewRow == null && !excelParsedRows.isEmpty()) {
            previewRow = excelParsedRows.get(0);
        }
        if (previewRow == null) {
            excelPreviewFront.setImage(null);
            excelPreviewBack.setImage(null);
            if (excelPreviewGeneratedId != null) {
                excelPreviewGeneratedId.setText("");
            }
            return;
        }
        saveOrganizationProfile();
        String nextIdPreview = IdNumberGenerator.previewNextId(resolveIdPrefix(), LocalDate.now());
        if (excelPreviewGeneratedId != null) {
            excelPreviewGeneratedId.setText("Next ID (system): " + nextIdPreview);
        }
        IdRecord sample = parsedRowToPreviewRecord(previewRow, nextIdPreview);
        String localBaseUrl = App.getServerBaseUrl();
        int[] fd = IdImageGenerator.getTemplateDimensions(referenceFrontPath);
        int[] bd = IdImageGenerator.getTemplateDimensions(referenceBackPath);
        int fw = fd != null ? fd[0] : IdImageGenerator.DEFAULT_CARD_WIDTH;
        int fh = fd != null ? fd[1] : IdImageGenerator.DEFAULT_CARD_HEIGHT;
        int bw = bd != null ? bd[0] : IdImageGenerator.DEFAULT_CARD_WIDTH;
        int bh = bd != null ? bd[1] : IdImageGenerator.DEFAULT_CARD_HEIGHT;
        Canvas cFront = new Canvas(fw, fh);
        Canvas cBack = new Canvas(bw, bh);
        String cloudUrl = SupabaseStorageService.getPublicPdfUrlForRecord(sample).orElse("");
        IdImageGenerator.drawFront(cFront.getGraphicsContext2D(), sample, referenceFrontPath, localBaseUrl, cloudUrl, fw, fh);
        IdImageGenerator.drawBack(cBack.getGraphicsContext2D(), sample, referenceBackPath, localBaseUrl, cloudUrl, bw, bh);
        excelPreviewFront.setImage(cFront.snapshot(null, null));
        excelPreviewBack.setImage(cBack.snapshot(null, null));
    }

    private IdRecord parsedRowToPreviewRecord(ExcelIdReader.ParsedRow row, String generatedIdNumber) {
        return new IdRecord(0, java.util.UUID.randomUUID().toString(), row.get("photo_path").trim(), row.get("name").trim(), generatedIdNumber,
            row.get("position").trim(), row.get("department").trim(), row.get("date_of_birth").trim(),
            row.get("contact_number").trim(), row.get("address").trim(),
            row.get("emergency_name").trim(), row.get("emergency_contact").trim());
    }

    @FXML
    private void onExportAllAsImages() {
        List<IdRow> selectedRows = getActionRows();
        if (selectedRows.isEmpty()) {
            showStatus("Select one or more IDs to export.");
            return;
        }
        List<IdRecord> records = selectedRows.stream()
            .map(r -> DbHelper.getIdRecord(r.getId()))
            .filter(java.util.Objects::nonNull)
            .toList();
        if (records.isEmpty()) {
            showStatus("No valid selected IDs to export.");
            return;
        }
        var dirChooser = new javafx.stage.DirectoryChooser();
        dirChooser.setTitle("Choose folder to save ID images");
        File dir = dirChooser.showDialog(getStage());
        if (dir == null) return;
        Path dirPath = dir.toPath();
        String localBaseUrl = App.getServerBaseUrl();
        saveOrganizationProfile();
        double scale = IdExportService.PRINT_SCALE;
        try {
            for (IdRecord r : records) {
                String cloudUrl = SupabaseStorageService.getPublicPdfUrlForRecord(r).orElse("");
                IdExportService.exportPngPair(r, referenceFrontPath, referenceBackPath, localBaseUrl, cloudUrl,
                    dirPath, "id_" + r.id(), scale);
            }
            showStatus("Exported " + records.size() + " ID(s) at " + (int) (scale * 100) + "% scale to " + dirPath);
        } catch (Exception ex) {
            showStatus("Export error: " + ex.getMessage(), true);
        }
    }

    @FXML
    private void onDeleteSelected() {
        List<IdRow> selectedRows = getActionRows();
        if (selectedRows.isEmpty()) {
            showStatus("Select one or more IDs to delete.");
            return;
        }
        deleteRecordRows(selectedRows);
    }

    private void deleteRecordRow(IdRow selected) {
        deleteRecordRows(java.util.List.of(selected));
    }

    private void deleteRecordRows(List<IdRow> selectedRows) {
        int count = selectedRows.size();
        String msg = count == 1 ? "Delete this ID record?" : "Delete " + count + " selected ID records?";
        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION, msg, ButtonType.CANCEL, ButtonType.OK);
        if (confirm.showAndWait().orElse(ButtonType.CANCEL) != ButtonType.OK) return;
        try {
            int deleted = 0;
            for (IdRow selected : selectedRows) {
                IdRecord r = DbHelper.getIdRecord(selected.getId());
                if (r != null) SupabaseStorageService.deleteIdPdf(r);
                DbHelper.deleteIdRecord(selected.getId());
                idRows.remove(selected);
                deleted++;
            }
            onNew();
            showStatus(deleted == 1 ? "Deleted." : ("Deleted " + deleted + " records."));
        } catch (Exception ex) {
            showStatus("Error: " + ex.getMessage());
        }
    }

    private Stage getStage() {
        return (Stage) statusLabel.getScene().getWindow();
    }

    private void showStatus(String msg) {
        showStatus(msg, false);
    }

    private void showStatus(String msg, boolean isError) {
        // Keep the inline label available, but primary notification is a popup.
        statusLabel.setText(msg);
        statusLabel.setManaged(false);
        statusLabel.setVisible(false);
        statusLabel.getStyleClass().remove("error");

        if (msg == null || msg.isBlank()) return;

        Platform.runLater(() -> {
            Alert.AlertType type = isError ? Alert.AlertType.ERROR : Alert.AlertType.INFORMATION;
            Alert a = new Alert(type);
            a.setTitle("ID Generator");
            a.setHeaderText(null);
            a.setContentText(msg);
            if (statusLabel != null && statusLabel.getScene() != null && statusLabel.getScene().getWindow() instanceof Stage s) {
                a.initOwner(s);
            }
            a.show();
        });
    }

    private void clearStatus() {
        statusLabel.setText("");
        statusLabel.setManaged(false);
        statusLabel.setVisible(false);
        statusLabel.getStyleClass().remove("error");
    }

    public static final class IdRow {
        private final javafx.beans.property.IntegerProperty id = new javafx.beans.property.SimpleIntegerProperty();
        private final javafx.beans.property.SimpleStringProperty name = new javafx.beans.property.SimpleStringProperty();
        private final javafx.beans.property.SimpleStringProperty idNumber = new javafx.beans.property.SimpleStringProperty();
        private final javafx.beans.property.BooleanProperty selected = new javafx.beans.property.SimpleBooleanProperty(false);

        public IdRow(int id, String name, String idNumber) {
            this.id.set(id);
            this.name.set(name != null ? name : "");
            this.idNumber.set(idNumber != null ? idNumber : "");
        }

        public int getId() { return id.get(); }
        public String getName() { return name.get(); }
        public void setName(String v) { name.set(v); }
        public String getIdNumber() { return idNumber.get(); }
        public void setIdNumber(String v) { idNumber.set(v); }
        public javafx.beans.property.BooleanProperty selectedProperty() { return selected; }
        public boolean isSelected() { return selected.get(); }
        public void setSelected(boolean v) { selected.set(v); }
    }
}
