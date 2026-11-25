package fr.echo.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.os.Build;
import android.provider.Settings;
import androidx.core.content.FileProvider;
import java.io.File;
import java.io.FileOutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.webkit.WebResourceRequest;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.webkit.CookieManager;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.Toast;
import androidx.webkit.JavaScriptReplyProxy;
import androidx.webkit.WebViewCompat;
import androidx.webkit.WebViewFeature;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.Collections;
import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONTokener;

/* Echo app (code: github.com/damienburglin-cyber/Echo-app, releases there too).
   Since 0.3 it checks that repo for a newer release at start and offers to install it.
   Echo app 0.2: the Echo artifact (the brain) full screen; behind it, a second browser where you sign in
   to GlobalExam yourself. Through the bridge, Echo can open, read and tap that browser, the same tools
   as the Claude desktop app's browser. The bridge only answers pages served as claude.ai artifacts. */
public class MainActivity extends Activity {
    static final String VERSION = "0.3.1";
    private static final String ECHO = "https://claude.ai/artifact/6jNZwCbZuZ8bae7NKNu4NX";
    private static final String ARTIFACT_ORIGINS = "https://*.frame.claudeusercontent.com";
    private static final int PICK_FILE = 7;
    private static final String[][] APPS = {
        { "elsa", "us.nobarriers.elsa", "ELSA Speak" },
        { "netflix", "com.netflix.mediaclient", "Netflix" },
        { "prime", "com.amazon.avod.thirdpartyclient", "Prime Video" }
    };
    private WebView echo, web;
    private Button backToEcho;
    private boolean webShown = false;
    private ValueCallback<Uri[]> pendingFiles;
    private final Handler ui = new Handler(Looper.getMainLooper());
    /* The web.open waiting for its page to finish loading. */
    private JavaScriptReplyProxy openReply;
    private String openId;
    private Runnable openTimeout;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        FrameLayout root = new FrameLayout(this);
        root.setFitsSystemWindows(true);
        web = newWeb(false);
        echo = newWeb(true);
        root.addView(web);
        root.addView(echo);
        backToEcho = new Button(this);
        backToEcho.setText("← Back to Echo");
        backToEcho.setAllCaps(false);
        backToEcho.setOnClickListener(v -> showWeb(false));
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.END);
        lp.setMargins(24, 24, 24, 24);
        root.addView(backToEcho, lp);
        backToEcho.setVisibility(View.GONE);
        setContentView(root);

        web.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) { ui.postDelayed(() -> finishOpen(true, null), 800); }
        });
        if (WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
            WebViewCompat.addWebMessageListener(echo, "EchoAppMsg", Collections.singleton(ARTIFACT_ORIGINS),
                (view, message, origin, isMainFrame, reply) -> ui.post(() -> handle(message.getData(), reply)));
        }
        if (state != null) { echo.restoreState(state); } else { echo.loadUrl(ECHO); }
        checkUpdate();
    }

    /* ---------- Updates: the latest release of the public Echo-app repo ---------- */
    private static final String RELEASES = "https://api.github.com/repos/damienburglin-cyber/Echo-app/releases/latest";
    private String pendingApk, pendingVersion;

    static boolean newer(String a, String b) {
        String[] x = a.split("\\."), y = b.split("\\.");
        for (int i = 0; i < Math.max(x.length, y.length); i++) {
            int p = i < x.length ? parse(x[i]) : 0, q = i < y.length ? parse(y[i]) : 0;
            if (p != q) return p > q;
        }
        return false;
    }
    private static int parse(String s) { try { return Integer.parseInt(s.replaceAll("[^0-9]", "")); } catch (Exception e) { return 0; } }

    private void checkUpdate() {
        new Thread(() -> {
            try {
                HttpURLConnection c = (HttpURLConnection) new URL(RELEASES).openConnection();
                c.setRequestProperty("Accept", "application/vnd.github+json");
                c.setConnectTimeout(10000);
                c.setReadTimeout(10000);
                if (c.getResponseCode() != 200) return;
                JSONObject rel = new JSONObject(readAll(c.getInputStream()));
                String tag = rel.optString("tag_name").replaceFirst("^v", "");
                if (!newer(tag, VERSION)) return;
                JSONArray assets = rel.optJSONArray("assets");
                String apk = null;
                for (int i = 0; assets != null && i < assets.length(); i++) {
                    JSONObject a = assets.getJSONObject(i);
                    if (a.optString("name").endsWith(".apk")) apk = a.optString("browser_download_url");
                }
                if (apk == null) return;
                final String url = apk, notes = rel.optString("body");
                ui.post(() -> offerUpdate(tag, url, notes));
            } catch (Exception ignored) { }
        }).start();
    }

    private void offerUpdate(String version, String url, String notes) {
        new AlertDialog.Builder(this)
            .setTitle("Echo " + version + " is available")
            .setMessage((notes == null || notes.isEmpty() ? "" : notes + "\n\n") + "You have " + VERSION + ". Your sign-ins are kept.")
            .setPositiveButton("Install", (d, w) -> install(version, url))
            .setNegativeButton("Later", null)
            .show();
    }

    private void install(String version, String url) {
        if (Build.VERSION.SDK_INT >= 26 && !getPackageManager().canRequestPackageInstalls()) {
            pendingApk = url; pendingVersion = version;
            Toast.makeText(this, "Allow Echo to install updates, then come back", Toast.LENGTH_LONG).show();
            startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + getPackageName())));
            return;
        }
        Toast.makeText(this, "Downloading Echo " + version + "…", Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            try {
                File dir = new File(getCacheDir(), "updates");
                dir.mkdirs();
                File f = new File(dir, "echo.apk");
                HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
                c.setInstanceFollowRedirects(true);
                try (InputStream in = c.getInputStream(); FileOutputStream out = new FileOutputStream(f)) {
                    byte[] buf = new byte[16384];
                    int n;
                    while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                }
                Uri u = FileProvider.getUriForFile(this, "fr.echo.app.files", f);
                Intent i = new Intent(Intent.ACTION_VIEW).setDataAndType(u, "application/vnd.android.package-archive")
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
                ui.post(() -> startActivity(i));
            } catch (Exception e) {
                ui.post(() -> Toast.makeText(this, "Download failed. Try again later.", Toast.LENGTH_LONG).show());
            }
        }).start();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (pendingApk != null && (Build.VERSION.SDK_INT < 26 || getPackageManager().canRequestPackageInstalls())) {
            String u = pendingApk, v = pendingVersion;
            pendingApk = null;
            install(v, u);
        }
    }

    private static String readAll(InputStream in) throws java.io.IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        return out.toString("UTF-8");
    }

    /* isEcho: the Echo page gets the EchoApp marker; the GlobalExam browser looks like a plain browser
       (since 0.3.1: GlobalExam's sign-in page may refuse an unknown browser) and may open sign-in pop-ups. */
    private WebView newWeb(boolean isEcho) {
        WebView w = new WebView(this);
        WebSettings s = w.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        if (isEcho) s.setUserAgentString(s.getUserAgentString() + " EchoApp/" + VERSION);
        else { s.setJavaScriptCanOpenWindowsAutomatically(true); s.setSupportMultipleWindows(true); }
        CookieManager.getInstance().setAcceptCookie(true);
        CookieManager.getInstance().setAcceptThirdPartyCookies(w, true);
        w.setWebViewClient(new WebViewClient());
        w.setWebChromeClient(new WebChromeClient() {
            /* A pop-up (for example a sign-in step) opens in the same GlobalExam browser. */
            @Override
            public boolean onCreateWindow(WebView view, boolean isDialog, boolean userGesture, Message msg) {
                if (isEcho) return false;
                WebView tmp = new WebView(MainActivity.this);
                tmp.setWebViewClient(new WebViewClient() {
                    @Override
                    public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest req) { view.loadUrl(req.getUrl().toString()); return true; }
                });
                ((WebView.WebViewTransport) msg.obj).setWebView(tmp);
                msg.sendToTarget();
                return true;
            }

            @Override
            public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> cb, FileChooserParams params) {
                if (pendingFiles != null) pendingFiles.onReceiveValue(null);
                pendingFiles = cb;
                try { startActivityForResult(params.createIntent(), PICK_FILE); }
                catch (Exception e) { pendingFiles = null; return false; }
                return true;
            }
        });
        return w;
    }

    private void showWeb(boolean show) {
        webShown = show;
        echo.setVisibility(show ? View.INVISIBLE : View.VISIBLE);
        backToEcho.setVisibility(show ? View.VISIBLE : View.GONE);
        /* The keyboard must type into the page you see. */
        if (show) { echo.clearFocus(); web.requestFocus(); } else { web.clearFocus(); echo.requestFocus(); }
    }

    private void reply(JavaScriptReplyProxy r, JSONObject o) { try { r.postMessage(o.toString()); } catch (Exception ignored) {} }

    private JSONObject res(String id, boolean ok) {
        JSONObject o = new JSONObject();
        try { o.put("id", id).put("ok", ok); } catch (Exception ignored) {}
        return o;
    }

    /* Bridge protocol: the page posts {"id", "cmd", ...}; the app answers {"id", "ok", ...}, sometimes later. */
    private void handle(String json, JavaScriptReplyProxy r) {
        String id = "";
        try {
            JSONObject in = new JSONObject(json);
            id = in.optString("id");
            String cmd = in.optString("cmd");
            JSONObject out = res(id, true);
            switch (cmd) {
                case "hello": {
                    JSONArray apps = new JSONArray();
                    for (String[] a : APPS) if (getPackageManager().getLaunchIntentForPackage(a[1]) != null) apps.put(new JSONObject().put("id", a[0]).put("name", a[2]));
                    JSONArray cmds = new JSONArray();
                    for (String c : new String[] { "hello", "openApp", "openUrl", "copy", "web.open", "web.text", "web.tree", "web.run", "web.click", "web.select", "web.type", "web.show", "web.hide" }) cmds.put(c);
                    reply(r, out.put("app", VERSION).put("apps", apps).put("cmds", cmds));
                    return;
                }
                case "openApp": {
                    String pkg = null;
                    for (String[] a : APPS) if (a[0].equals(in.optString("app"))) pkg = a[1];
                    Intent i = pkg == null ? null : getPackageManager().getLaunchIntentForPackage(pkg);
                    if (i == null) { reply(r, res(id, false).put("err", "not_installed")); return; }
                    startActivity(i); reply(r, out); return;
                }
                case "openUrl": {
                    String url = in.optString("url");
                    if (!url.startsWith("https://")) { reply(r, res(id, false).put("err", "https_only")); return; }
                    try { startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url))); reply(r, out); }
                    catch (Exception e) { reply(r, res(id, false).put("err", "no_app")); }
                    return;
                }
                case "copy": {
                    ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                    cm.setPrimaryClip(ClipData.newPlainText("Echo", in.optString("text")));
                    Toast.makeText(this, "Copied", Toast.LENGTH_SHORT).show();
                    reply(r, out); return;
                }
                case "web.open": {
                    String url = in.optString("url");
                    if (!url.startsWith("https://")) { reply(r, res(id, false).put("err", "https_only")); return; }
                    finishOpen(false, "replaced");
                    openReply = r; openId = id;
                    openTimeout = () -> finishOpen(true, null);
                    ui.postDelayed(openTimeout, 20000);
                    web.loadUrl(url);
                    return;
                }
                case "web.text": {
                    int max = Math.max(200, Math.min(in.optInt("max", 20000), 60000));
                    final String fid = id;
                    web.evaluateJavascript("(() => 'URL: ' + location.href + '\\nTitle: ' + document.title + '\\n---\\n' + ((document.body && document.body.innerText) || ''))()",
                        v -> reply(r, put(res(fid, true), "text", cut(unquote(v), max))));
                    return;
                }
                case "web.tree": {
                    int max = Math.max(200, Math.min(in.optInt("max", 40000), 80000));
                    final String fid = id;
                    web.evaluateJavascript(asset("tree.js"), v -> reply(r, put(res(fid, true), "text", cut(unquote(v), max))));
                    return;
                }
                case "web.run": {
                    /* Runs a script from Echo in the GlobalExam browser (only artifact pages can ask).
                       Lets Echo carry its own page reader, so most fixes don't need a new app. */
                    int max = Math.max(200, Math.min(in.optInt("max", 40000), 80000));
                    final String fid = id;
                    web.evaluateJavascript(in.optString("js"), v -> {
                        String t;
                        try { Object o = new JSONTokener(v).nextValue(); t = o instanceof String ? (String) o : String.valueOf(o); } catch (Exception e) { t = String.valueOf(v); }
                        reply(r, put(res(fid, true), "text", cut(t, max)));
                    });
                    return;
                }
                case "web.click":
                case "web.select": {
                    String ref = in.optString("ref").replaceAll("[^a-z0-9_]", "");
                    final String fid = id;
                    final boolean select = "web.select".equals(cmd);
                    web.evaluateJavascript("(() => { const el = window.__echoRefs && window.__echoRefs['" + ref + "']; if (!el || !el.isConnected) return null; el.scrollIntoView({ block: 'center' }); const b = el.getBoundingClientRect(); return [b.left + b.width / 2, b.top + b.height / 2, window.devicePixelRatio || 1]; })()",
                        v -> {
                            try {
                                Object o = new JSONTokener(v).nextValue();
                                if (!(o instanceof JSONArray)) { reply(r, put(res(fid, false), "err", "ref_not_found")); return; }
                                JSONArray p = (JSONArray) o;
                                double d = p.getDouble(2);
                                tap((float) (p.getDouble(0) * d), (float) (p.getDouble(1) * d));
                                if (select) ui.postDelayed(() -> web.evaluateJavascript("(() => { const el = window.__echoRefs['" + ref + "']; if (el && el.select) el.select(); return true; })()", x -> reply(r, res(fid, true))), 250);
                                else reply(r, res(fid, true));
                            } catch (Exception e) { reply(r, put(res(fid, false), "err", "tap_failed")); }
                        });
                    return;
                }
                case "web.type": {
                    String text = JSONObject.quote(in.optString("text"));
                    final String fid = id;
                    web.evaluateJavascript("(() => { const el = document.activeElement; if (!el || el === document.body) return false; return document.execCommand('insertText', false, " + text + "); })()",
                        v -> reply(r, "true".equals(v) ? res(fid, true) : put(res(fid, false), "err", "no_field_focused")));
                    return;
                }
                case "web.show": showWeb(true); reply(r, out); return;
                case "web.hide": showWeb(false); reply(r, out); return;
                default: reply(r, res(id, false).put("err", "unknown_cmd"));
            }
        } catch (Exception e) {
            reply(r, put(res(id, false), "err", "bad_request"));
        }
    }

    private void finishOpen(boolean ok, String err) {
        if (openReply == null) return;
        if (openTimeout != null) ui.removeCallbacks(openTimeout);
        JSONObject o = res(openId, ok);
        try { if (err != null) o.put("err", err); o.put("url", web.getUrl()); } catch (Exception ignored) {}
        reply(openReply, o);
        openReply = null; openId = null; openTimeout = null;
    }

    private static JSONObject put(JSONObject o, String k, Object v) { try { o.put(k, v); } catch (Exception ignored) {} return o; }
    private static String cut(String s, int max) { return s.length() > max ? s.substring(0, max) : s; }
    private static String unquote(String v) {
        try { Object o = new JSONTokener(v).nextValue(); return o instanceof String ? (String) o : ""; } catch (Exception e) { return ""; }
    }

    /* A real tap on the GlobalExam browser, like a finger: the page cannot tell it from yours. */
    private void tap(float x, float y) {
        long t = SystemClock.uptimeMillis();
        MotionEvent down = MotionEvent.obtain(t, t, MotionEvent.ACTION_DOWN, x, y, 0);
        MotionEvent up = MotionEvent.obtain(t, t + 60, MotionEvent.ACTION_UP, x, y, 0);
        web.dispatchTouchEvent(down);
        web.dispatchTouchEvent(up);
        down.recycle();
        up.recycle();
    }

    private String asset(String name) {
        try (InputStream in = getAssets().open(name)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            return out.toString("UTF-8");
        } catch (Exception e) { return "''"; }
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req == PICK_FILE && pendingFiles != null) {
            pendingFiles.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(res, data));
            pendingFiles = null;
        }
    }

    @Override
    protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        echo.saveState(out);
    }

    @Override
    public void onBackPressed() {
        if (webShown) { if (web.canGoBack()) web.goBack(); else showWeb(false); return; }
        if (echo.canGoBack()) echo.goBack(); else super.onBackPressed();
    }

    @Override
    protected void onPause() {
        super.onPause();
        CookieManager.getInstance().flush();
    }
}
