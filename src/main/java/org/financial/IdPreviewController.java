package org.financial;

import javafx.embed.swing.SwingFXUtils;
import javafx.geometry.Insets;
import javafx.geometry.Rectangle2D;
import javafx.scene.Scene;
import javafx.scene.canvas.Canvas;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.image.ImageView;
import javafx.scene.image.WritableImage;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;
import javafx.stage.Screen;
import javafx.stage.Stage;

import javax.imageio.ImageIO;
import java.io.File;

/**
 * Shows ID preview/generate window. Renders the template with user data at saved layout positions.
 * Preview is scaled to fit the screen (no scrolling).
 */
public final class IdPreviewController {

    private static final double CARD_GAP = 16;
    /** Space reserved for title row, button row, window chrome, and padding */
    private static final double RESERVED_VERTICAL = 200;
    private static final double RESERVED_HORIZONTAL = 56;

    public static void show(Stage owner, IdRecord record, String referenceFrontPath, String referenceBackPath, String baseUrl) {
        String cloud = SupabaseStorageService.getPublicPdfUrlForRecord(record).orElse("");
        show(owner, record, referenceFrontPath, referenceBackPath, baseUrl, cloud);
    }

    /**
     * @param localBaseUrl local server base URL (used when cloudUrl is blank)
     * @param cloudUrl     full public URL to the uploaded ID PDF (preferred for QR)
     */
    public static void show(Stage owner, IdRecord record, String referenceFrontPath, String referenceBackPath, String localBaseUrl, String cloudUrl) {
        Stage stage = new Stage();
        stage.setTitle("ID Preview — " + record.name());
        stage.initOwner(owner);

        int[] frontDim = IdImageGenerator.getTemplateDimensions(referenceFrontPath);
        int[] backDim = IdImageGenerator.getTemplateDimensions(referenceBackPath);
        int nativeFrontW = frontDim != null ? frontDim[0] : IdImageGenerator.DEFAULT_CARD_WIDTH;
        int nativeFrontH = frontDim != null ? frontDim[1] : IdImageGenerator.DEFAULT_CARD_HEIGHT;
        int nativeBackW = backDim != null ? backDim[0] : IdImageGenerator.DEFAULT_CARD_WIDTH;
        int nativeBackH = backDim != null ? backDim[1] : IdImageGenerator.DEFAULT_CARD_HEIGHT;

        Rectangle2D vb = Screen.getPrimary().getVisualBounds();
        double availW = Math.max(320, vb.getWidth() - RESERVED_HORIZONTAL);
        double availH = Math.max(240, vb.getHeight() - RESERVED_VERTICAL);

        double contentW = nativeFrontW + CARD_GAP + nativeBackW;
        double contentH = Math.max(nativeFrontH, nativeBackH);
        double scale = Math.min(1.0, Math.min(availW / contentW, availH / contentH));

        int previewFrontW = Math.max(1, (int) Math.round(nativeFrontW * scale));
        int previewFrontH = Math.max(1, (int) Math.round(nativeFrontH * scale));
        int previewBackW = Math.max(1, (int) Math.round(nativeBackW * scale));
        int previewBackH = Math.max(1, (int) Math.round(nativeBackH * scale));

        Canvas frontCanvas = new Canvas(nativeFrontW, nativeFrontH);
        Canvas backCanvas = new Canvas(nativeBackW, nativeBackH);
        IdImageGenerator.drawFront(frontCanvas.getGraphicsContext2D(), record, referenceFrontPath, localBaseUrl, cloudUrl, nativeFrontW, nativeFrontH);
        IdImageGenerator.drawBack(backCanvas.getGraphicsContext2D(), record, referenceBackPath, localBaseUrl, cloudUrl, nativeBackW, nativeBackH);

        ImageView frontView = new ImageView(frontCanvas.snapshot(null, null));
        frontView.setPreserveRatio(true);
        frontView.setSmooth(true);
        frontView.setFitWidth(previewFrontW);
        frontView.setFitHeight(previewFrontH);

        ImageView backView = new ImageView(backCanvas.snapshot(null, null));
        backView.setPreserveRatio(true);
        backView.setSmooth(true);
        backView.setFitWidth(previewBackW);
        backView.setFitHeight(previewBackH);

        HBox cards = new HBox(CARD_GAP, frontView, backView);
        StackPane previewArea = new StackPane(cards);
        previewArea.setPadding(new Insets(8));
        VBox.setVgrow(previewArea, Priority.ALWAYS);

        Button saveFront = new Button("Save front as image...");
        Button saveBack = new Button("Save back as image...");
        Button closeBtn = new Button("Close");
        closeBtn.setDefaultButton(true);
        closeBtn.setCancelButton(true);
        closeBtn.setOnAction(e -> stage.close());
        saveFront.setOnAction(e -> saveSideAsImage(record, referenceFrontPath, true, localBaseUrl, cloudUrl, stage));
        saveBack.setOnAction(e -> saveSideAsImage(record, referenceBackPath, false, localBaseUrl, cloudUrl, stage));

        HBox buttons = new HBox(10, saveFront, saveBack, closeBtn);
        Label hint = new Label("Preview — same render as export, scaled to fit the window. Saved PNG/PDF uses full template resolution.");
        hint.setWrapText(true);
        VBox root = new VBox(12, hint, previewArea, buttons);
        root.setStyle("-fx-padding: 12; -fx-font-size: 13px;");
        Scene scene = new Scene(root);

        double scaledContentW = previewFrontW + CARD_GAP + previewBackW + 32;
        double scaledContentH = Math.max(previewFrontH, previewBackH) + 32;
        double windowW = Math.min(vb.getWidth() - 16, Math.max(480, scaledContentW));
        double windowH = Math.min(vb.getHeight() - 16, Math.max(320, scaledContentH + RESERVED_VERTICAL));

        stage.setScene(scene);
        stage.setWidth(windowW);
        stage.setHeight(windowH);
        stage.setMaxWidth(vb.getWidth());
        stage.setMaxHeight(vb.getHeight());
        stage.setResizable(true);
        stage.setOnCloseRequest(e -> stage.close());
        stage.show();
    }

    /** Export at native template pixel size (not the on-screen preview scale). */
    private static void saveSideAsImage(IdRecord record, String referencePath, boolean front, String localBaseUrl, String cloudUrl, Stage stage) {
        int[] dim = IdImageGenerator.getTemplateDimensions(referencePath);
        int w = dim != null ? dim[0] : IdImageGenerator.DEFAULT_CARD_WIDTH;
        int h = dim != null ? dim[1] : IdImageGenerator.DEFAULT_CARD_HEIGHT;
        Canvas c = new Canvas(w, h);
        if (front) {
            IdImageGenerator.drawFront(c.getGraphicsContext2D(), record, referencePath, localBaseUrl, cloudUrl, w, h);
        } else {
            IdImageGenerator.drawBack(c.getGraphicsContext2D(), record, referencePath, localBaseUrl, cloudUrl, w, h);
        }
        saveCanvasAsImage(c, stage, front ? "id_front" : "id_back");
    }

    private static void saveCanvasAsImage(Canvas canvas, Stage stage, String defaultName) {
        FileChooser fc = new FileChooser();
        fc.setTitle("Save as image");
        fc.setInitialFileName(defaultName + ".png");
        fc.getExtensionFilters().add(new FileChooser.ExtensionFilter("PNG", "*.png"));
        File file = fc.showSaveDialog(stage);
        if (file == null) return;
        try {
            WritableImage wi = new WritableImage((int) canvas.getWidth(), (int) canvas.getHeight());
            canvas.snapshot(null, wi);
            ImageIO.write(SwingFXUtils.fromFXImage(wi, null), "png", file);
        } catch (Exception ex) {
            throw new RuntimeException("Failed to save image", ex);
        }
    }
}
