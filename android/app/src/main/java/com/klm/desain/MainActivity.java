package com.klm.desain;

import android.Manifest;
import android.app.Activity;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.print.PrintAttributes;
import android.print.PrintDocumentAdapter;
import android.print.PrintManager;
import android.provider.MediaStore;
import android.util.Base64;
import android.view.View;
import android.webkit.JavascriptInterface;
import android.webkit.PermissionRequest;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** KLM Desain untuk Android: aplikasi web tertanam di APK (offline), simpan hasil ke galeri/Download, cetak lewat Android. */
public class MainActivity extends Activity {
    static final String APP = "KLM Desain";
    static final String HOST = "appassets.androidplatform.net";
    static final String ORIGIN = "https://" + HOST + "/";
    static final int REQ_FILE = 11, REQ_PERM = 12, REQ_STORAGE = 13;

    // pengait unduhan (a[download] blob:/data:) + window.print → jembatan Android
    static final String HOOK = "(function(){if(window.__klmHook)return;window.__klmHook=1;try{window.showSaveFilePicker=undefined}catch(e){}"
        + "async function save(h,n){try{var r=await fetch(h);var b=await r.blob();var st=KLMAndroid.saveBegin(n||'berkas',b.type||'application/octet-stream');if(st!=='ok'){KLMAndroid.toast(st);return}"
        + "var C=393216;for(var i=0;i<b.size;i+=C){var u=new Uint8Array(await b.slice(i,i+C).arrayBuffer());var s='';for(var j=0;j<u.length;j+=32768)s+=String.fromCharCode.apply(null,u.subarray(j,j+32768));KLMAndroid.saveChunk(btoa(s))}"
        + "var l=KLMAndroid.saveEnd();KLMAndroid.toast(l?'Tersimpan: '+l:'Gagal menyimpan')}catch(e){KLMAndroid.toast('Gagal menyimpan: '+e.message)}}"
        + "var isF=function(a){return a&&a.hasAttribute&&a.hasAttribute('download')&&/^(blob:|data:)/.test(a.href)};"
        + "var oc=HTMLAnchorElement.prototype.click;HTMLAnchorElement.prototype.click=function(){if(isF(this)){save(this.href,this.getAttribute('download'));return}return oc.call(this)};"
        + "document.addEventListener('click',function(e){var a=e.target&&e.target.closest&&e.target.closest('a[download]');if(isF(a)){e.preventDefault();e.stopPropagation();save(a.href,a.getAttribute('download'))}},true);"
        + "window.print=function(){KLMAndroid.print()};})();";

    WebView web;
    ValueCallback<Uri[]> fileCb;
    PermissionRequest pendingPerm;
    OutputStream saveOut;
    Uri saveUri;
    File saveFile;
    String saveMime = "", saveName = "", saveLoc = "";

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        int bg = Color.parseColor("#E9542F");
        getWindow().setStatusBarColor(bg);
        getWindow().setNavigationBarColor(bg);
        web = new WebView(this);
        web.setBackgroundColor(bg);
        setContentView(web);

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(false);
        s.setSupportZoom(false);
        s.setBuiltInZoomControls(false);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        s.setUserAgentString(s.getUserAgentString() + " KLMAndroid/1.0");
        web.setOverScrollMode(View.OVER_SCROLL_NEVER);
        web.addJavascriptInterface(new Bridge(), "KLMAndroid");

        web.setWebViewClient(new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView v, WebResourceRequest r) {
                Uri u = r.getUrl();
                if (HOST.equals(u.getHost())) return serve(u);
                return null;
            }
            @Override
            public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) {
                Uri u = r.getUrl();
                if (HOST.equals(u.getHost())) return false;
                try { startActivity(new Intent(Intent.ACTION_VIEW, u)); } catch (Exception e) { /* abaikan */ }
                return true;
            }
            @Override
            public void onPageFinished(WebView v, String url) { v.evaluateJavascript(HOOK, null); }
        });

        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onProgressChanged(WebView v, int p) { if (p >= 30) v.evaluateJavascript(HOOK, null); }
            @Override
            public boolean onShowFileChooser(WebView v, ValueCallback<Uri[]> cb, FileChooserParams p) {
                if (fileCb != null) fileCb.onReceiveValue(null);
                fileCb = cb;
                try { startActivityForResult(p.createIntent(), REQ_FILE); } catch (Exception e) { fileCb = null; return false; }
                return true;
            }
            @Override
            public void onPermissionRequest(final PermissionRequest req) {
                runOnUiThread(new Runnable() { public void run() {
                    List<String> perms = new ArrayList<>(), grant = new ArrayList<>();
                    for (String r : req.getResources()) {
                        if (PermissionRequest.RESOURCE_VIDEO_CAPTURE.equals(r)) { grant.add(r); perms.add(Manifest.permission.CAMERA); }
                        if (PermissionRequest.RESOURCE_AUDIO_CAPTURE.equals(r)) { grant.add(r); perms.add(Manifest.permission.RECORD_AUDIO); }
                    }
                    if (grant.isEmpty()) { req.deny(); return; }
                    boolean all = true;
                    for (String p : perms) if (checkSelfPermission(p) != PackageManager.PERMISSION_GRANTED) all = false;
                    if (all) req.grant(grant.toArray(new String[0]));
                    else { pendingPerm = req; requestPermissions(perms.toArray(new String[0]), REQ_PERM); }
                }});
            }
        });

        if (Build.VERSION.SDK_INT < 29 && checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, REQ_STORAGE);
        }
        web.loadUrl(ORIGIN + "index.html");
    }

    WebResourceResponse serve(Uri u) {
        String path = u.getPath();
        if (path == null || path.equals("/") || path.isEmpty()) path = "/index.html";
        if (path.endsWith("/")) path += "index.html";
        try {
            String rel = path.substring(1);
            if (rel.contains("..")) return notFound();
            InputStream in = getAssets().open("web/" + rel);
            String mime = mimeOf(rel);
            WebResourceResponse r = new WebResourceResponse(mime, mime.startsWith("text/") || mime.contains("json") || mime.contains("javascript") || mime.contains("xml") ? "utf-8" : null, in);
            Map<String, String> h = new HashMap<>();
            h.put("Cache-Control", "no-cache");
            h.put("Access-Control-Allow-Origin", "*");
            r.setResponseHeaders(h);
            return r;
        } catch (Exception e) {
            return notFound();
        }
    }

    WebResourceResponse notFound() {
        WebResourceResponse r = new WebResourceResponse("text/plain", "utf-8", new java.io.ByteArrayInputStream(new byte[0]));
        r.setStatusCodeAndReasonPhrase(404, "Not Found");
        return r;
    }

    static String mimeOf(String f) {
        String e = f.substring(f.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT);
        switch (e) {
            case "html": case "htm": return "text/html";
            case "js": case "mjs": return "text/javascript";
            case "css": return "text/css";
            case "json": case "map": return "application/json";
            case "webmanifest": return "application/manifest+json";
            case "png": return "image/png";
            case "jpg": case "jpeg": return "image/jpeg";
            case "gif": return "image/gif";
            case "svg": return "image/svg+xml";
            case "ico": return "image/x-icon";
            case "webp": return "image/webp";
            case "mp3": return "audio/mpeg";
            case "wav": return "audio/wav";
            case "mp4": return "video/mp4";
            case "webm": return "video/webm";
            case "woff2": return "font/woff2";
            case "woff": return "font/woff";
            case "ttf": return "font/ttf";
            case "otf": return "font/otf";
            case "wasm": return "application/wasm";
            case "pdf": return "application/pdf";
            case "bcmap": case "pfb": case "icc": case "onnx": return "application/octet-stream";
            case "txt": return "text/plain";
            default: return "application/octet-stream";
        }
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        if (req == REQ_FILE) {
            if (fileCb == null) return;
            Uri[] r = null;
            if (res == RESULT_OK && data != null) {
                r = WebChromeClient.FileChooserParams.parseResult(res, data);
                if (r == null && data.getData() != null) r = new Uri[]{data.getData()};
            }
            fileCb.onReceiveValue(r);
            fileCb = null;
            return;
        }
        super.onActivityResult(req, res, data);
    }

    @Override
    public void onRequestPermissionsResult(int req, String[] perms, int[] results) {
        if (req == REQ_PERM && pendingPerm != null) {
            boolean ok = results.length > 0;
            for (int r : results) if (r != PackageManager.PERMISSION_GRANTED) ok = false;
            if (ok) pendingPerm.grant(pendingPerm.getResources()); else pendingPerm.deny();
            pendingPerm = null;
        }
    }

    @Override
    public void onBackPressed() {
        if (web.canGoBack()) web.goBack(); else super.onBackPressed();
    }

    @Override protected void onPause() { super.onPause(); web.onPause(); }
    @Override protected void onResume() { super.onResume(); web.onResume(); }
    @Override protected void onDestroy() { closeSave(false); if (web != null) web.destroy(); super.onDestroy(); }

    void closeSave(boolean ok) {
        try { if (saveOut != null) saveOut.close(); } catch (IOException e) { /* abaikan */ }
        saveOut = null;
        if (!ok && saveUri != null) { try { getContentResolver().delete(saveUri, null, null); } catch (Exception e) { /* abaikan */ } }
        if (!ok && saveFile != null) saveFile.delete();
        saveUri = null;
        saveFile = null;
    }

    static String safeName(String n) {
        n = n == null ? "" : n.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", "_").trim();
        return n.isEmpty() ? "berkas" : n;
    }

    class Bridge {
        @JavascriptInterface
        public synchronized String saveBegin(String name, String mime) {
            try {
                closeSave(false);
                saveName = safeName(name);
                saveMime = (mime == null || mime.isEmpty()) ? "application/octet-stream" : mime;
                String folder = saveMime.startsWith("image/") ? Environment.DIRECTORY_PICTURES
                    : saveMime.startsWith("video/") ? Environment.DIRECTORY_MOVIES
                    : saveMime.startsWith("audio/") ? Environment.DIRECTORY_MUSIC : Environment.DIRECTORY_DOWNLOADS;
                String sub = folder + "/" + APP;
                if (Build.VERSION.SDK_INT >= 29) {
                    ContentValues cv = new ContentValues();
                    cv.put(MediaStore.MediaColumns.DISPLAY_NAME, saveName);
                    cv.put(MediaStore.MediaColumns.MIME_TYPE, saveMime);
                    cv.put(MediaStore.MediaColumns.RELATIVE_PATH, sub);
                    cv.put(MediaStore.MediaColumns.IS_PENDING, 1);
                    Uri coll = saveMime.startsWith("image/") ? MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                        : saveMime.startsWith("video/") ? MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                        : saveMime.startsWith("audio/") ? MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                        : MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY);
                    saveUri = getContentResolver().insert(coll, cv);
                    if (saveUri == null) return "Gagal membuat berkas";
                    saveOut = getContentResolver().openOutputStream(saveUri);
                    saveLoc = sub + "/" + saveName;
                } else {
                    if (checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                        runOnUiThread(new Runnable() { public void run() { requestPermissions(new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, REQ_STORAGE); } });
                        return "Izinkan akses penyimpanan lalu coba simpan lagi";
                    }
                    File dir = new File(Environment.getExternalStoragePublicDirectory(folder), APP);
                    if (!dir.exists() && !dir.mkdirs()) return "Gagal membuat folder";
                    String base = saveName, ext = "";
                    int dot = base.lastIndexOf('.');
                    if (dot > 0) { ext = base.substring(dot); base = base.substring(0, dot); }
                    File f = new File(dir, saveName);
                    int k = 1;
                    while (f.exists()) f = new File(dir, base + " (" + (k++) + ")" + ext);
                    saveFile = f;
                    saveOut = new FileOutputStream(f);
                    saveLoc = folder + "/" + APP + "/" + f.getName();
                }
                return "ok";
            } catch (Exception e) {
                closeSave(false);
                return "Gagal menyimpan: " + e.getMessage();
            }
        }

        @JavascriptInterface
        public synchronized void saveChunk(String b64) throws IOException {
            if (saveOut == null) throw new IOException("tidak ada berkas terbuka");
            saveOut.write(Base64.decode(b64, Base64.DEFAULT));
        }

        @JavascriptInterface
        public synchronized String saveEnd() {
            try {
                if (saveOut != null) saveOut.close();
                saveOut = null;
                if (saveUri != null) {
                    if (Build.VERSION.SDK_INT >= 29) {
                        ContentValues cv = new ContentValues();
                        cv.put(MediaStore.MediaColumns.IS_PENDING, 0);
                        getContentResolver().update(saveUri, cv, null, null);
                    }
                    saveUri = null;
                } else if (saveFile != null) {
                    MediaScannerConnection.scanFile(MainActivity.this, new String[]{saveFile.getAbsolutePath()}, new String[]{saveMime}, null);
                    saveFile = null;
                }
                return saveLoc;
            } catch (Exception e) {
                closeSave(false);
                return "";
            }
        }

        @JavascriptInterface
        public void toast(final String msg) {
            runOnUiThread(new Runnable() { public void run() { Toast.makeText(MainActivity.this, msg, Toast.LENGTH_LONG).show(); } });
        }

        @JavascriptInterface
        public void print() {
            runOnUiThread(new Runnable() { public void run() {
                try {
                    PrintManager pm = (PrintManager) getSystemService(Context.PRINT_SERVICE);
                    PrintDocumentAdapter ad = web.createPrintDocumentAdapter(APP);
                    pm.print(APP, ad, new PrintAttributes.Builder().build());
                } catch (Exception e) {
                    Toast.makeText(MainActivity.this, "Cetak tidak tersedia: " + e.getMessage(), Toast.LENGTH_LONG).show();
                }
            }});
        }
    }
}
