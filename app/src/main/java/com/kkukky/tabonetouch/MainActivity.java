package com.kkukky.tabonetouch;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.view.View;
import android.webkit.JavascriptInterface;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import androidx.documentfile.provider.DocumentFile;

import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.FileInputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

public class MainActivity extends Activity {
    private static final int REQ_FOLDER = 1001;
    private static final String APP_DIR = "Tab_One_Touch";
    private static final String FILE_NAME = "오늘의메시지.txt";
    private static final String VIDEO_NAME = "Go_Work.mp4";
    private static final String VIDEO_HOST = "tabonetouch.local";

    private WebView web;
    private SharedPreferences prefs;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences("tab_one_touch", MODE_PRIVATE);

        web = new WebView(this);
        web.setBackgroundColor(0xFF000000);
        setContentView(web);

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setLoadWithOverviewMode(false);
        s.setUseWideViewPort(true);
        // 돋보기(핀치 확대/축소)를 끄고, 시스템 글꼴 크기 설정과 상관없이 같은 크기로 보이게 합니다.
        s.setSupportZoom(false);
        s.setBuiltInZoomControls(false);
        s.setDisplayZoomControls(false);
        s.setTextZoom(100);
        s.setMediaPlaybackRequiresUserGesture(false);

        web.setWebViewClient(new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                Uri u = request.getUrl();
                if (u != null && VIDEO_HOST.equals(u.getHost())) return serveVideo(request);
                return super.shouldInterceptRequest(view, request);
            }
        });
        web.addJavascriptInterface(new Bridge(), "Native");
        web.loadUrl("file:///android_asset/index.html");
        hideSystemUi();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) hideSystemUi();
    }

    private void hideSystemUi() {
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
    }

    /** 사용자가 고른 위치 안에 Tab_One_Touch 폴더를 찾거나 만듭니다. 고른 폴더 이름이 이미 같으면 그대로 씁니다. */
    private DocumentFile ensureAppDir(DocumentFile root) {
        if (root == null) return null;
        if (APP_DIR.equals(root.getName())) return root;
        DocumentFile d = root.findFile(APP_DIR);
        if (d != null && d.isDirectory()) return d;
        return root.createDirectory(APP_DIR);
    }

    private DocumentFile appDir() {
        String t = prefs.getString("tree", null);
        if (t == null) return null;
        try {
            return ensureAppDir(DocumentFile.fromTreeUri(this, Uri.parse(t)));
        } catch (Exception e) {
            return null;
        }
    }


    /** 폴더 안의 Go_Work.mp4를 WebView에 줍니다. 되감기를 위해 Range 요청을 처리합니다. */
    private WebResourceResponse serveVideo(WebResourceRequest req) {
        Map<String, String> h = new HashMap<String, String>();
        h.put("Access-Control-Allow-Origin", "*");
        h.put("Accept-Ranges", "bytes");
        try {
            DocumentFile dir = appDir();
            DocumentFile f = dir == null ? null : dir.findFile(VIDEO_NAME);
            if (f == null || !f.isFile()) throw new IOException("none");
            ParcelFileDescriptor pfd = getContentResolver().openFileDescriptor(f.getUri(), "r");
            if (pfd == null) throw new IOException("open");
            long len = pfd.getStatSize();
            if (len <= 0) len = f.length();
            long start = 0, end = len - 1;
            boolean partial = false;
            String range = null;
            for (Map.Entry<String, String> e : req.getRequestHeaders().entrySet()) {
                if ("Range".equalsIgnoreCase(e.getKey())) range = e.getValue();
            }
            if (range != null && range.startsWith("bytes=")) {
                String[] p = range.substring(6).split("-", 2);
                try {
                    if (p[0].length() > 0) start = Long.parseLong(p[0].trim());
                    if (p.length > 1 && p[1].trim().length() > 0) end = Long.parseLong(p[1].trim());
                    partial = true;
                } catch (NumberFormatException ex) {
                    start = 0; end = len - 1; partial = false;
                }
            }
            if (end > len - 1) end = len - 1;
            if (start > end) start = end;
            final long count = end - start + 1;
            FileInputStream fis = new ParcelFileDescriptor.AutoCloseInputStream(pfd);
            fis.getChannel().position(start);
            InputStream body = new FilterInputStream(fis) {
                long left = count;
                @Override
                public int read() throws IOException {
                    if (left <= 0) return -1;
                    int r = super.read();
                    if (r >= 0) left--;
                    return r;
                }
                @Override
                public int read(byte[] b, int off, int n) throws IOException {
                    if (left <= 0) return -1;
                    int r = super.read(b, off, (int) Math.min(n, left));
                    if (r > 0) left -= r;
                    return r;
                }
            };
            h.put("Content-Length", String.valueOf(count));
            if (partial) {
                h.put("Content-Range", "bytes " + start + "-" + end + "/" + len);
                return new WebResourceResponse("video/mp4", null, 206, "Partial Content", h, body);
            }
            return new WebResourceResponse("video/mp4", null, 200, "OK", h, body);
        } catch (Exception e) {
            return new WebResourceResponse("text/plain", "utf-8", 404, "Not Found", h, new ByteArrayInputStream(new byte[0]));
        }
    }

    private void callJs(final String name) {
        final String js = "window.__onFolder && window.__onFolder(" + JSONObject.quote(name) + ")";
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                web.evaluateJavascript(js, null);
            }
        });
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_FOLDER) return;
        if (resultCode != RESULT_OK || data == null || data.getData() == null) {
            return;
        }
        Uri tree = data.getData();
        try {
            getContentResolver().takePersistableUriPermission(tree,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
            DocumentFile dir = ensureAppDir(DocumentFile.fromTreeUri(this, tree));
            if (dir == null) {
                callJs("");
                return;
            }
            prefs.edit().putString("tree", tree.toString()).apply();
            callJs(APP_DIR);
        } catch (Exception e) {
            callJs("");
        }
    }

    private class Bridge {
        @JavascriptInterface
        public String folderName() {
            String t = prefs.getString("tree", null);
            if (t == null) return "";
            for (android.content.UriPermission p : getContentResolver().getPersistedUriPermissions()) {
                if (p.getUri().toString().equals(t) && p.isWritePermission()) return APP_DIR;
            }
            return "";
        }

        @JavascriptInterface
        public void pickFolder() {
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
                    i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                            | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                            | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
                            | Intent.FLAG_GRANT_PREFIX_URI_PERMISSION);
                    startActivityForResult(i, REQ_FOLDER);
                }
            });
        }

        /** 파일 내용을 돌려줍니다. 파일이 없거나 읽을 수 없으면 null. */
        @JavascriptInterface
        public String read() {
            try {
                DocumentFile dir = appDir();
                if (dir == null) return null;
                DocumentFile f = dir.findFile(FILE_NAME);
                if (f == null || !f.isFile()) return null;
                InputStream in = getContentResolver().openInputStream(f.getUri());
                if (in == null) return null;
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buf = new byte[4096];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                in.close();
                return new String(out.toByteArray(), StandardCharsets.UTF_8);
            } catch (Exception e) {
                return null;
            }
        }

        @JavascriptInterface
        public boolean write(String text) {
            try {
                DocumentFile dir = appDir();
                if (dir == null) return false;
                DocumentFile f = dir.findFile(FILE_NAME);
                if (f == null) f = dir.createFile("application/octet-stream", FILE_NAME);
                if (f == null) return false;
                OutputStream os = getContentResolver().openOutputStream(f.getUri(), "wt");
                if (os == null) return false;
                os.write(text.getBytes(StandardCharsets.UTF_8));
                os.close();
                return true;
            } catch (Exception e) {
                return false;
            }
        }

        @JavascriptInterface
        public boolean hasVideo() {
            try {
                DocumentFile dir = appDir();
                if (dir == null) return false;
                DocumentFile f = dir.findFile(VIDEO_NAME);
                return f != null && f.isFile();
            } catch (Exception e) {
                return false;
            }
        }

        @JavascriptInterface
        public void clearFolder() {
            prefs.edit().remove("tree").apply();
        }
    }
}
