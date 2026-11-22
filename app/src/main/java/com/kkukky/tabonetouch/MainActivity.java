package com.kkukky.tabonetouch;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.ThumbnailUtils;
import android.os.Bundle;
import android.util.Base64;
import android.view.WindowManager;
import android.view.View;
import android.widget.FrameLayout;
import android.webkit.JavascriptInterface;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import androidx.documentfile.provider.DocumentFile;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

public class MainActivity extends Activity {
    private static final int REQ_FOLDER = 1001;
    private static final String APP_DIR = "Tab_One_Touch";
    private static final String FILE_NAME = "오늘의메시지.txt";

    private WebView web;
    private WebView yt;
    private SharedPreferences prefs;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences("tab_one_touch", MODE_PRIVATE);

        web = new WebView(this);
        web.setBackgroundColor(0xFF000000);
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(0xFF000000);
        root.addView(web, new FrameLayout.LayoutParams(-1, -1));
        yt = new WebView(this);
        yt.setBackgroundColor(0xFF000000);
        yt.setVisibility(View.GONE);
        root.addView(yt, new FrameLayout.LayoutParams(-1, -1));
        setContentView(root);
        WebSettings ys = yt.getSettings();
        ys.setJavaScriptEnabled(true);
        ys.setDomStorageEnabled(true);
        ys.setMediaPlaybackRequiresUserGesture(false);
        ys.setSupportZoom(false);
        yt.setWebViewClient(new WebViewClient());
        yt.addJavascriptInterface(new YtBridge(), "Native2");

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
        s.setAllowFileAccess(true);

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


    /** 고른 영상을 앱 전용 폴더로 복사해 둡니다(같은 파일이면 다시 복사하지 않음). 끝나면 자바스크립트에 알립니다. */
    private void prepareVideoAsync(final String name) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                String err = null;
                String url = null;
                try {
                    DocumentFile dir = appDir();
                    DocumentFile f = (dir == null || name == null) ? null : dir.findFile(name);
                    if (f == null || !f.isFile()) throw new IOException("폴더에서 파일을 찾지 못했습니다.");
                    String ext = name.contains(".") ? name.substring(name.lastIndexOf('.')) : ".mp4";
                    java.io.File outDir = new java.io.File(getFilesDir(), "video");
                    if (!outDir.exists()) outDir.mkdirs();
                    java.io.File out = new java.io.File(outDir, "current" + ext);
                    String key = name + "|" + f.length() + "|" + f.lastModified();
                    if (!(out.exists() && out.length() == f.length() && key.equals(prefs.getString("video_key", "")))) {
                        java.io.File[] old = outDir.listFiles();
                        if (old != null) for (java.io.File o : old) o.delete();
                        java.io.File tmp = new java.io.File(outDir, "copy.tmp");
                        InputStream in = getContentResolver().openInputStream(f.getUri());
                        if (in == null) throw new IOException("파일을 열지 못했습니다.");
                        java.io.FileOutputStream os = new java.io.FileOutputStream(tmp);
                        byte[] buf = new byte[1 << 16];
                        int n;
                        while ((n = in.read(buf)) > 0) os.write(buf, 0, n);
                        os.close();
                        in.close();
                        if (!tmp.renameTo(out)) throw new IOException("복사한 파일을 저장하지 못했습니다.");
                        prefs.edit().putString("video_key", key).apply();
                    }
                    url = Uri.fromFile(out).toString();
                } catch (Exception e) {
                    err = e.getMessage() == null ? e.toString() : e.getMessage();
                }
                final String js = url != null
                        ? "window.__onVideo && window.__onVideo(true," + JSONObject.quote(url) + ")"
                        : "window.__onVideo && window.__onVideo(false," + JSONObject.quote(err) + ")";
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        web.evaluateJavascript(js, null);
                    }
                });
            }
        }).start();
    }

    private final java.util.Map<String, String> imgKeys = new java.util.HashMap<String, String>();
    private final java.util.Map<String, String> imgData = new java.util.HashMap<String, String>();
    private final java.util.concurrent.atomic.AtomicBoolean imgBusy = new java.util.concurrent.atomic.AtomicBoolean(false);

    /** 176x176(버튼 크기의 2배)으로 가운데 기준 잘라 data URL로 만듭니다. */
    private String thumbData(DocumentFile f) {
        try {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            InputStream a = getContentResolver().openInputStream(f.getUri());
            if (a == null) return null;
            BitmapFactory.decodeStream(a, null, o);
            a.close();
            int sample = 1;
            while (o.outWidth / (sample * 2) >= 352 && o.outHeight / (sample * 2) >= 352) sample *= 2;
            BitmapFactory.Options o2 = new BitmapFactory.Options();
            o2.inSampleSize = sample;
            InputStream b = getContentResolver().openInputStream(f.getUri());
            if (b == null) return null;
            Bitmap bmp = BitmapFactory.decodeStream(b, null, o2);
            b.close();
            if (bmp == null) return null;
            Bitmap th = ThumbnailUtils.extractThumbnail(bmp, 176, 176);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            th.compress(Bitmap.CompressFormat.JPEG, 90, out);
            return "data:image/jpeg;base64," + Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP);
        } catch (Exception e) {
            return null;
        }
    }

    private void loadImagesAsync() {
        if (!imgBusy.compareAndSet(false, true)) return;
        new Thread(new Runnable() {
            @Override
            public void run() {
                org.json.JSONArray arr = new org.json.JSONArray();
                try {
                    DocumentFile dir = appDir();
                    String[] exts = {".png", ".jpg", ".jpeg"};
                    for (int i = 1; i <= 3; i++) {
                        String data = null;
                        DocumentFile f = null;
                        if (dir != null) {
                            for (String ext : exts) {
                                DocumentFile c = dir.findFile(i + ext);
                                if (c != null && c.isFile()) { f = c; break; }
                            }
                        }
                        if (f != null) {
                            String key = f.getName() + "|" + f.length() + "|" + f.lastModified();
                            String slot = String.valueOf(i);
                            if (key.equals(imgKeys.get(slot)) && imgData.get(slot) != null) {
                                data = imgData.get(slot);
                            } else {
                                data = thumbData(f);
                                imgKeys.put(slot, key);
                                imgData.put(slot, data);
                            }
                        } else {
                            imgKeys.remove(String.valueOf(i));
                            imgData.remove(String.valueOf(i));
                        }
                        arr.put(data == null ? org.json.JSONObject.NULL : data);
                    }
                } catch (Exception e) {
                    arr = new org.json.JSONArray();
                    arr.put(org.json.JSONObject.NULL).put(org.json.JSONObject.NULL).put(org.json.JSONObject.NULL);
                }
                final String js = "window.__onImages && window.__onImages(" + arr.toString() + ")";
                imgBusy.set(false);
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        web.evaluateJavascript(js, null);
                    }
                });
            }
        }).start();
    }

    private void closeYoutube() {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                yt.loadUrl("about:blank");
                yt.setVisibility(View.GONE);
            }
        });
    }

    @Override
    public void onBackPressed() {
        if (yt != null && yt.getVisibility() == View.VISIBLE) closeYoutube();
        else super.onBackPressed();
    }

    private static String color(String c, String d) {
        return (c != null && c.matches("#[0-9A-Fa-f]{3,8}")) ? c : d;
    }

    private class YtBridge {
        @JavascriptInterface
        public void close() {
            closeYoutube();
        }

        @JavascriptInterface
        public void external(String id) {
            if (id == null || !id.matches("[A-Za-z0-9_-]{6,20}")) return;
            try {
                startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/shorts/" + id)));
            } catch (Exception e) {
                // 열 수 있는 앱이 없으면 아무것도 하지 않습니다.
            }
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
        public boolean hasVideo(String name) {
            try {
                DocumentFile dir = appDir();
                if (dir == null || name == null) return false;
                DocumentFile f = dir.findFile(name);
                return f != null && f.isFile();
            } catch (Exception e) {
                return false;
            }
        }

        /** 폴더 안의 영상 파일 이름 목록(JSON 배열). */
        @JavascriptInterface
        public void openYoutube(final String id, String bg, String fg, String ghost) {
            if (id == null || !id.matches("[A-Za-z0-9_-]{6,20}")) return;
            final String b = color(bg, "#000000"), f = color(fg, "#FFFFFF"), g = color(ghost, "#4A4A4A");
            final String html = "<!doctype html><html><head><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">"
                    + "<style>html,body{margin:0;height:100%;background:" + b + ";overflow:hidden;font-family:sans-serif}"
                    + "#f{position:absolute;left:50%;top:14vh;height:80vh;aspect-ratio:9/16;transform:translateX(-50%);border:0;border-radius:1.5vh;background:#000}"
                    + "#b{position:absolute;top:3.3vh;right:3.3vw;width:7.3vh;height:7.3vh;box-sizing:border-box;border-radius:50%;border:3px solid " + g + ";background:transparent;color:" + f + ";display:flex;align-items:center;justify-content:center;padding:0}"
                    + "#b svg{width:50%;height:50%}"
                    + "#o{position:absolute;left:0;right:0;bottom:1.6vh;text-align:center}"
                    + "#o button{background:transparent;border:0;color:" + f + ";opacity:.7;font-size:2.6vh;text-decoration:underline}"
                    + "</style></head><body>"
                    + "<iframe id=\"f\" src=\"https://www.youtube.com/embed/" + id + "?playsinline=1&rel=0&autoplay=1\" allow=\"autoplay; encrypted-media; picture-in-picture\" referrerpolicy=\"strict-origin-when-cross-origin\"></iframe>"
                    + "<button id=\"b\" aria-label=\"시작 화면으로 돌아가기\" onclick=\"Native2.close()\"><svg viewBox=\"0 0 24 24\" fill=\"none\" stroke=\"currentColor\" stroke-width=\"2\" stroke-linecap=\"round\" stroke-linejoin=\"round\"><path d=\"M3 11.5L12 4l9 7.5\"/><path d=\"M5.5 10v9.5h13V10\"/><path d=\"M10 19.5v-5h4v5\"/></svg></button>"
                    + "<div id=\"o\"><button onclick=\"Native2.external('" + id + "')\">재생되지 않으면 YouTube에서 열기</button></div>"
                    + "</body></html>";
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    yt.loadDataWithBaseURL("https://tabonetouch.local/", html, "text/html", "utf-8", null);
                    yt.setVisibility(View.VISIBLE);
                }
            });
        }

        /** 폴더의 1.png~3.png를 백그라운드에서 읽어 자바스크립트에 알립니다(바뀌지 않은 파일은 다시 읽지 않음). */
        @JavascriptInterface
        public void loadImages() {
            loadImagesAsync();
        }

        /** 폴더의 영상 파일 목록을 백그라운드에서 읽어 자바스크립트에 알립니다. */
        @JavascriptInterface
        public void loadVideoList() {
            new Thread(new Runnable() {
                @Override
                public void run() {
                    org.json.JSONArray a = new org.json.JSONArray();
                    try {
                        DocumentFile dir = appDir();
                        if (dir != null) {
                            for (DocumentFile f : dir.listFiles()) {
                                String n = f.getName();
                                if (n != null && n.toLowerCase(java.util.Locale.ROOT).matches(".*\\.(mp4|m4v|webm|mkv|mov)$")) a.put(n);
                            }
                        }
                    } catch (Exception e) {
                        // 읽지 못하면 빈 목록
                    }
                    final String js = "window.__onVideos && window.__onVideos(" + a.toString() + ")";
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            web.evaluateJavascript(js, null);
                        }
                    });
                }
            }).start();
        }

        /** 화면을 다시 그립니다. */
        @JavascriptInterface
        public void redraw() {
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    web.invalidate();
                }
            });
        }

        /** 화면 켜짐 유지. */
        @JavascriptInterface
        public void setKeepAwake(final boolean on) {
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    if (on) getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                    else getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                }
            });
        }

        @JavascriptInterface
        public void prepareVideo(String name) {
            prepareVideoAsync(name);
        }

        @JavascriptInterface
        public String listVideos() {
            try {
                DocumentFile dir = appDir();
                org.json.JSONArray a = new org.json.JSONArray();
                if (dir == null) return a.toString();
                for (DocumentFile f : dir.listFiles()) {
                    String n = f.getName();
                    if (f.isFile() && n != null && n.toLowerCase(java.util.Locale.ROOT).matches(".*\\.(mp4|m4v|webm|mkv|mov)$")) a.put(n);
                }
                return a.toString();
            } catch (Exception e) {
                return "[]";
            }
        }

        @JavascriptInterface
        public void clearFolder() {
            prefs.edit().remove("tree").apply();
        }
    }
}
