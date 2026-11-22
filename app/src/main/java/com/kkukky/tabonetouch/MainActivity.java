package com.kkukky.tabonetouch;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.webkit.JavascriptInterface;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import androidx.documentfile.provider.DocumentFile;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

public class MainActivity extends Activity {
    private static final int REQ_FOLDER = 1001;
    private static final String APP_DIR = "Tab_One_Touch";
    private static final String FILE_NAME = "오늘의메시지.txt";

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

        web.setWebViewClient(new WebViewClient());
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
        public void clearFolder() {
            prefs.edit().remove("tree").apply();
        }
    }
}
