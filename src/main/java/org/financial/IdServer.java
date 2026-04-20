package org.financial;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.nio.charset.StandardCharsets;
import java.util.Enumeration;

/**
 * Local HTTP server so the QR code can be scanned on the same WiFi.
 */
public final class IdServer {
    private static final int PREFERRED_PORT = 8085;
    private HttpServer server;
    private String baseUrl = "";

    public void start() {
        if (server != null) return;
        try {
            server = HttpServer.create(new InetSocketAddress(PREFERRED_PORT), 0);
        } catch (IOException ex) {
            try {
                server = HttpServer.create(new InetSocketAddress(0), 0);
            } catch (IOException e) {
                throw new RuntimeException("Failed to start server", e);
            }
        }

        server.createContext("/id", this::handleIdRequest);
        server.createContext("/qr", this::handleQrRequest);
        server.createContext("/", this::handleRoot);
        server.setExecutor(null);
        server.start();

        String ip = findLocalIp();
        int port = server.getAddress().getPort();
        baseUrl = "http://" + ip + ":" + port;
    }

    public void stop() {
        if (server != null) {
            server.stop(0);
            server = null;
        }
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    private void handleRoot(HttpExchange ex) throws IOException {
        String body = "<html><body><h3>ID Generator</h3><p>Use /id/&lt;id&gt; or /qr/&lt;qr_uid&gt; to view ID PDF.</p></body></html>";
        sendHtml(ex, 200, body);
    }

    private void handleQrRequest(HttpExchange ex) throws IOException {
        String path = ex.getRequestURI().getPath(); // /qr/{uid}
        String[] parts = path.split("/");
        if (parts.length < 3) {
            sendHtml(ex, 400, "<html><body>Missing qr_uid.</body></html>");
            return;
        }
        String qrUid = parts[2];
        if (qrUid == null || qrUid.isBlank()) {
            sendHtml(ex, 400, "<html><body>Invalid qr_uid.</body></html>");
            return;
        }
        IdRecord r = DbHelper.getIdRecordByQrUid(qrUid);
        if (r == null) {
            sendHtml(ex, 404, "<html><body>ID not found.</body></html>");
            return;
        }
        servePdf(ex, r);
    }

    private void handleIdRequest(HttpExchange ex) throws IOException {
        String path = ex.getRequestURI().getPath(); // /id/123
        String[] parts = path.split("/");
        if (parts.length < 3) {
            sendHtml(ex, 400, "<html><body>Missing id.</body></html>");
            return;
        }
        int id;
        try {
            id = Integer.parseInt(parts[2]);
        } catch (NumberFormatException e) {
            sendHtml(ex, 400, "<html><body>Invalid id.</body></html>");
            return;
        }

        IdRecord r = DbHelper.getIdRecord(id);
        if (r == null) {
            sendHtml(ex, 404, "<html><body>ID not found.</body></html>");
            return;
        }
        servePdf(ex, r);
    }

    private void servePdf(HttpExchange ex, IdRecord r) throws IOException {
        byte[] pdfBytes = IdPdfGenerator.toPdfFromBackground(r);
        if (pdfBytes == null || pdfBytes.length == 0) {
            sendHtml(ex, 500, "<html><body>Failed to generate PDF.</body></html>");
            return;
        }
        ex.getResponseHeaders().add("Content-Type", "application/pdf");
        ex.getResponseHeaders().add("Content-Disposition", "inline; filename=\"id-" + r.id() + ".pdf\"");
        ex.sendResponseHeaders(200, pdfBytes.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(pdfBytes);
        }
    }

    private static void sendHtml(HttpExchange ex, int code, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "text/html; charset=UTF-8");
        ex.sendResponseHeaders(code, bytes.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(bytes);
        }
    }

    private static String findLocalIp() {
        try {
            Enumeration<NetworkInterface> nets = NetworkInterface.getNetworkInterfaces();
            while (nets.hasMoreElements()) {
                NetworkInterface ni = nets.nextElement();
                if (!ni.isUp() || ni.isLoopback() || ni.isVirtual()) continue;
                Enumeration<InetAddress> addrs = ni.getInetAddresses();
                while (addrs.hasMoreElements()) {
                    InetAddress addr = addrs.nextElement();
                    if (addr instanceof Inet4Address && addr.isSiteLocalAddress()) {
                        return addr.getHostAddress();
                    }
                }
            }
            nets = NetworkInterface.getNetworkInterfaces();
            while (nets.hasMoreElements()) {
                NetworkInterface ni = nets.nextElement();
                if (!ni.isUp() || ni.isLoopback() || ni.isVirtual()) continue;
                Enumeration<InetAddress> addrs = ni.getInetAddresses();
                while (addrs.hasMoreElements()) {
                    InetAddress addr = addrs.nextElement();
                    if (addr instanceof Inet4Address) {
                        return addr.getHostAddress();
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return "127.0.0.1";
    }
}