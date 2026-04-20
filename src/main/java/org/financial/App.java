package org.financial;

import javafx.application.Application;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.stage.Stage;

import java.io.IOException;

/**
 * JavaFX App
 */
public class App extends Application {

    private static Scene scene;
    private static IdServer idServer;

    @Override
    public void start(Stage stage) throws IOException {
        DbHelper.initDatabase();
        idServer = new IdServer();
        idServer.start();
        SupabaseStorageService.startBackgroundUploader();
        scene = new Scene(loadFXML("primary"), 720, 620);
        scene.getStylesheets().add(App.class.getResource("styles.css").toExternalForm());
        stage.setTitle("ID Generator");
        stage.setMinWidth(720);
        stage.setMinHeight(560);
        stage.setScene(scene);
        stage.show();
    }

    @Override
    public void stop() {
        if (idServer != null) {
            idServer.stop();
        }
    }

    public static String getServerBaseUrl() {
        return idServer != null ? idServer.getBaseUrl() : "";
    }

    static void setRoot(String fxml) throws IOException {
        scene.setRoot(loadFXML(fxml));
    }

    private static Parent loadFXML(String fxml) throws IOException {
        FXMLLoader fxmlLoader = new FXMLLoader(App.class.getResource(fxml + ".fxml"));
        return fxmlLoader.load();
    }

    public static void main(String[] args) {
        launch();
    }

}
