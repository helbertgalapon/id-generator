module org.financial {
    requires java.sql;
    requires java.desktop;
    requires java.net.http;
    requires java.prefs;
    requires jdk.httpserver;
    requires org.xerial.sqlitejdbc;
    requires javafx.controls;
    requires javafx.fxml;
    requires javafx.swing;
    requires com.google.zxing;
    requires org.apache.pdfbox;
    requires com.google.gson;
    requires org.apache.poi.poi;
    requires org.apache.poi.ooxml;
    requires org.slf4j;

    // Gson uses reflection to set private fields (Java modules require explicit opens)
    opens org.financial to javafx.fxml, com.google.gson;
    exports org.financial;
}
