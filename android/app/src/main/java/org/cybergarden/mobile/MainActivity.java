package org.cybergarden.mobile;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import android.os.Build;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.widget.FrameLayout;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import org.json.JSONObject;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.util.Collections;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

public class MainActivity extends Activity {
    private static final String ORIGIN = "https://garden.local";
    private static final String KEY = "cybergarden.session.v1";
    private WebView web;
    private SharedPreferences preferences;
    private final ExecutorService executor = Executors.newFixedThreadPool(3);
    private ValueCallback<Uri[]> fileCallback;
    private String pdfRequest, pdfPath;
    private String defaultBase = "http://10.0.2.2:8787";

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        preferences = getSharedPreferences("garden", MODE_PRIVATE);
        try (InputStream source = getAssets().open("connection.json")) {
            ByteArrayOutputStream data = new ByteArrayOutputStream(); byte[] buffer = new byte[1024]; int count;
            while ((count = source.read(buffer)) != -1) data.write(buffer, 0, count);
            defaultBase = validateBase(new JSONObject(data.toString("UTF-8")).getString("server"));
        } catch (Exception ignored) { }
        web = new WebView(this);
        web.setBackgroundColor(0xff26282c);
        WebSettings settings = web.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(false);
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(true);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        settings.setJavaScriptCanOpenWindowsAutomatically(false);
        web.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) { return true; }
            @Override public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                Uri uri = request.getUrl();
                if (!"https".equals(uri.getScheme()) || !"garden.local".equals(uri.getHost())) return emptyResponse();
                String name = uri.getPath();
                if ("/".equals(name)) name = "/index.html";
                if (!"/index.html".equals(name) && !"/app.js".equals(name) && !"/app.css".equals(name) && !"/JetBrainsMono-Regular.woff2".equals(name)) return emptyResponse();
                try {
                    String type = name.endsWith(".js") ? "text/javascript" : name.endsWith(".css") ? "text/css" : name.endsWith(".woff2") ? "font/woff2" : "text/html";
                    WebResourceResponse response = new WebResourceResponse(type, "UTF-8", getAssets().open(name.substring(1)));
                    response.setResponseHeaders(Collections.singletonMap("Content-Security-Policy", "default-src 'self'; script-src 'self'; style-src 'self' 'unsafe-inline'; connect-src 'none'; img-src 'self' data:; frame-src 'none'; object-src 'none'; base-uri 'none'"));
                    return response;
                } catch (Exception error) { return emptyResponse(); }
            }
        });
        web.setWebChromeClient(new WebChromeClient() {
            @Override public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback, FileChooserParams params) {
                if (fileCallback != null) fileCallback.onReceiveValue(null);
                fileCallback = callback;
                Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*").putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
                startActivityForResult(intent, 10);
                return true;
            }
        });
        web.addJavascriptInterface(new Bridge(), "GardenNative");
        FrameLayout content = new FrameLayout(this);
        content.setBackgroundColor(0xff26282c);
        content.addView(web, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        content.setOnApplyWindowInsetsListener((view, insets) -> {
            // Resize the container for the keyboard; WebView's own padding does
            // not reliably inset its HTML content.
            if (Build.VERSION.SDK_INT >= 30) {
                view.setPadding(0, 0, 0, insets.getInsets(WindowInsets.Type.ime()).bottom);
            }
            return insets;
        });
        setContentView(content);
        hideSystemBars();
        web.loadUrl(ORIGIN + "/index.html");
    }

    private void hideSystemBars() {
        if (Build.VERSION.SDK_INT >= 30) {
            getWindow().setDecorFitsSystemWindows(false);
            WindowInsetsController controller = getWindow().getInsetsController();
            if (controller != null) {
                controller.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
                controller.hide(WindowInsets.Type.systemBars());
            }
        } else {
            getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_FULLSCREEN |
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_LAYOUT_STABLE |
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
        }
    }
    @Override protected void onResume() { super.onResume(); hideSystemBars(); }
    @Override public void onWindowFocusChanged(boolean hasFocus) { super.onWindowFocusChanged(hasFocus); if (hasFocus) hideSystemBars(); }

    private WebResourceResponse emptyResponse() { return new WebResourceResponse("text/plain", "UTF-8", new ByteArrayInputStream(new byte[0])); }
    private synchronized SecretKey encryptionKey() throws Exception {
        KeyStore store = KeyStore.getInstance("AndroidKeyStore"); store.load(null);
        if (store.containsAlias(KEY)) return (SecretKey) store.getKey(KEY, null);
        KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        generator.init(new KeyGenParameterSpec.Builder(KEY, KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT).setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build());
        return generator.generateKey();
    }
    private synchronized void saveToken(String value) throws Exception {
        if (value.isEmpty()) { preferences.edit().remove("session").commit(); return; }
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding"); cipher.init(Cipher.ENCRYPT_MODE, encryptionKey());
        String encrypted = Base64.encodeToString(cipher.getIV(), Base64.NO_WRAP) + ":" + Base64.encodeToString(cipher.doFinal(value.getBytes(StandardCharsets.UTF_8)), Base64.NO_WRAP);
        if (!preferences.edit().putString("session", encrypted).commit()) throw new Exception("Не удалось сохранить сессию");
    }
    private synchronized String token() throws Exception {
        String stored = preferences.getString("session", ""); if (stored.isEmpty()) return "";
        try {
            String[] parts = stored.split(":"); Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, encryptionKey(), new GCMParameterSpec(128, Base64.decode(parts[0], Base64.NO_WRAP)));
            return new String(cipher.doFinal(Base64.decode(parts[1], Base64.NO_WRAP)), StandardCharsets.UTF_8);
        } catch (Exception error) { saveToken(""); return ""; }
    }
    private String validateBase(String raw) throws Exception {
        URI uri = new URI(raw.trim()); String host = uri.getHost();
        boolean privateHost = host != null && (host.equals("localhost") || host.equals("10.0.2.2") || host.matches("127\\.\\d+\\.\\d+\\.\\d+") || host.matches("10\\.\\d+\\.\\d+\\.\\d+") || host.matches("192\\.168\\.\\d+\\.\\d+") || host.matches("172\\.(1[6-9]|2\\d|3[01])\\.\\d+\\.\\d+"));
        if (!("https".equals(uri.getScheme()) || "http".equals(uri.getScheme()) && privateHost) || host == null || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null || !(uri.getPath().isEmpty() || uri.getPath().equals("/"))) throw new Exception("Укажите HTTPS-сервер или локальный IPv4 компьютера, например http://192.168.1.10:8787");
        return uri.getScheme() + "://" + uri.getRawAuthority();
    }
    private void answer(String id, JSONObject data, String error, int status) {
        runOnUiThread(() -> {
            if (web == null) return;
            try {
                JSONObject result = new JSONObject().put("data", data == null ? JSONObject.NULL : data).put("error", error == null ? JSONObject.NULL : error).put("status", status);
                web.evaluateJavascript("window.gardenReply(" + JSONObject.quote(id) + "," + result.toString() + ")", null);
            } catch (Exception ignored) { }
        });
    }
    private byte[] request(String method, String route, String body) throws Exception {
        if (!route.matches("/api/[A-Za-z0-9_/-]+") || !(method.equals("GET") || method.equals("POST") || method.equals("PUT") || method.equals("DELETE"))) throw new Exception("Недопустимый запрос");
        String base = validateBase(preferences.getString("base", defaultBase));
        HttpURLConnection connection = (HttpURLConnection) new URL(base + route).openConnection();
        connection.setInstanceFollowRedirects(false); connection.setConnectTimeout(15000); connection.setReadTimeout(150000); connection.setRequestMethod(method);
        connection.setRequestProperty("Content-Type", "application/json");
        String session = token();
        if (!session.isEmpty() && !route.equals("/api/login") && !route.equals("/api/register")) connection.setRequestProperty("Authorization", "Bearer " + session);
        try {
            if (!body.isEmpty() && !method.equals("GET")) {
                byte[] bytes = body.getBytes(StandardCharsets.UTF_8); if (bytes.length > 2 * 1024 * 1024) throw new Exception("Максимум 2 МБ");
                connection.setDoOutput(true); connection.setFixedLengthStreamingMode(bytes.length);
                try (OutputStream output = connection.getOutputStream()) { output.write(bytes); }
            }
            int status = connection.getResponseCode();
            InputStream input = status >= 200 && status < 300 ? connection.getInputStream() : connection.getErrorStream();
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            if (input != null) try (InputStream source = input) {
                byte[] buffer = new byte[8192]; int count;
                while ((count = source.read(buffer)) != -1) { output.write(buffer, 0, count); if (output.size() > 16 * 1024 * 1024) throw new Exception("Ответ сервера слишком большой"); }
            }
            if (status < 200 || status >= 300) {
                if (status == 401) saveToken("");
                String message = "Ошибка сервера: " + status;
                try { message = new JSONObject(output.toString("UTF-8")).optString("error", message); } catch (Exception ignored) { }
                throw new ApiException(status, message);
            }
            return output.toByteArray();
        } finally { connection.disconnect(); }
    }
    private static class ApiException extends Exception { final int status; ApiException(int code, String text) { super(text); status = code; } }
    public class Bridge {
        @JavascriptInterface public void invoke(String id, String operation, String payload) {
            executor.execute(() -> {
                try {
                    JSONObject input = new JSONObject(payload), result = new JSONObject();
                    if (operation.equals("state")) {
                        result.put("base", preferences.getString("base", defaultBase)).put("login", preferences.getString("login", "")).put("loggedIn", !token().isEmpty());
                    } else if (operation.equals("configure")) {
                        String base = validateBase(input.getString("base"));
                        if (!base.equals(preferences.getString("base", ""))) saveToken("");
                        preferences.edit().putString("base", base).commit(); result.put("ok", true);
                    } else if (operation.equals("api")) {
                        String route = input.getString("path");
                        byte[] data = request(input.optString("method", "GET"), route, input.has("body") ? input.getJSONObject("body").toString() : "");
                        String json = new String(data, StandardCharsets.UTF_8);
                        // Wrap arrays so the native callback always transports one JSON object.
                        if (json.startsWith("[")) result.put("value", new org.json.JSONArray(json));
                        else result.put("value", new JSONObject(json));
                        if (route.equals("/api/login") || route.equals("/api/register")) {
                            JSONObject auth = result.getJSONObject("value"); saveToken(auth.getString("token")); auth.remove("token");
                            preferences.edit().putString("login", auth.getJSONObject("user").getString("login")).commit();
                        } else if (route.equals("/api/logout")) saveToken("");
                    } else if (operation.equals("pdf")) {
                        String report = input.getString("id"); if (!report.matches("[a-f0-9-]{36}")) throw new Exception("Недопустимый отчёт");
                        runOnUiThread(() -> {
                            if (pdfRequest != null) { answer(id, null, "Сохранение PDF уже открыто", 409); return; }
                            pdfRequest = id; pdfPath = "/api/reports/" + report + "/pdf";
                            startActivityForResult(new Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("application/pdf").putExtra(Intent.EXTRA_TITLE, "CyberGarden-" + report + ".pdf"), 11);
                        }); return;
                    } else throw new Exception("Неизвестная операция");
                    answer(id, result, null, 200);
                } catch (ApiException error) { answer(id, null, error.getMessage(), error.status); }
                catch (Exception error) { answer(id, null, error instanceof java.io.IOException ? "Нет соединения с сервером. Проверьте адрес, Wi-Fi и запуск сервера." : error.getMessage(), 0); }
            });
        }
    }
    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == 10 && fileCallback != null) {
            fileCallback.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(resultCode, data)); fileCallback = null;
        } else if (requestCode == 11 && pdfRequest != null) {
            String id = pdfRequest, route = pdfPath; pdfRequest = null; pdfPath = null;
            if (resultCode != RESULT_OK || data == null || data.getData() == null) { answer(id, null, "Сохранение отменено", 0); return; }
            Uri destination = data.getData();
            executor.execute(() -> {
                try {
                    byte[] pdf = request("GET", route, "");
                    try (OutputStream output = getContentResolver().openOutputStream(destination)) { output.write(pdf); }
                    answer(id, new JSONObject().put("saved", true), null, 200);
                } catch (Exception error) { answer(id, null, "Не удалось сохранить PDF. Проверьте сессию и соединение.", 0); }
            });
        }
    }
    @Override public void onBackPressed() { web.evaluateJavascript("window.gardenBack ? window.gardenBack() : false", result -> { if (!"true".equals(result)) finish(); }); }
    @Override protected void onDestroy() { if (fileCallback != null) fileCallback.onReceiveValue(null); web.removeJavascriptInterface("GardenNative"); web.destroy(); web = null; executor.shutdownNow(); super.onDestroy(); }
}
