package fr.echo.app;

import android.Manifest;
import android.app.Activity;
import android.content.pm.PackageManager;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.speech.tts.Voice;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
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
   to GlobalExam yourself. App 0.4: the phone's voice (European accents) and speech recognition for Echo. Through the bridge, Echo can open, read and tap that browser, the same tools
   as the Claude desktop app's browser. The bridge only answers pages served as claude.ai artifacts. */
public class MainActivity extends Activity {
    /* The real installed version (0.3.2 said 0.3.1 here, so it kept offering itself). */
    static String VERSION = "0";
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
        try { VERSION = getPackageManager().getPackageInfo(getPackageName(), 0).versionName; } catch (Exception ignored) { }
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
        lastCheck = SystemClock.elapsedRealtime();
        checkUpdate();
    }

    /* ---------- Voice (0.4): the phone's own speech synthesis and recognition ----------
       Echo asks the phone to say a line in a European voice reading English (the accents claude.ai has not),
       and to listen to Damien's answer. While listening, the app also sends {id, event, text}:
       event "state" (text "listening") and event "partial" (the words so far). */
    private static final String[] ACCENTS = { "de-DE", "nl-NL", "it-IT", "es-ES", "fr-FR", "sv-SE", "pl-PL", "pt-PT", "en-GB", "en-US" };
    private TextToSpeech speech;
    private boolean speechReady = false;
    private final ArrayList<Runnable> speechWaiting = new ArrayList<>();
    private final Map<String, JavaScriptReplyProxy> sayReplies = new HashMap<>();
    private SpeechRecognizer recognizer;
    private JavaScriptReplyProxy listenReply;
    private String listenId, listenLang;
    private static final int MIC = 9;

    private void withSpeech(Runnable then) {
        if (speechReady) { then.run(); return; }
        speechWaiting.add(then);
        if (speech != null) return;
        speech = new TextToSpeech(this, status -> ui.post(() -> {
            speechReady = status == TextToSpeech.SUCCESS;
            if (speechReady) speech.setOnUtteranceProgressListener(new UtteranceProgressListener() {
                @Override public void onStart(String u) { }
                @Override public void onDone(String u) { ui.post(() -> sayDone(u, true, null)); }
                @Override public void onError(String u) { ui.post(() -> sayDone(u, false, "speak_failed")); }
                @Override public void onStop(String u, boolean interrupted) { ui.post(() -> sayDone(u, true, "stopped")); }
            });
            ArrayList<Runnable> w = new ArrayList<>(speechWaiting);
            speechWaiting.clear();
            for (Runnable x : w) x.run();
        }));
    }

    private void sayDone(String u, boolean ok, String err) {
        JavaScriptReplyProxy r = sayReplies.remove(u);
        if (r == null) return;
        JSONObject o = res(u, ok);
        if (err != null) put(o, ok ? "note" : "err", err);
        reply(r, o);
    }

    private static int voiceScore(Voice v, Locale want) {
        return (v.getLocale().getCountry().equals(want.getCountry()) ? 2 : 0) + (v.isNetworkConnectionRequired() ? 0 : 1);
    }

    /* The best installed voice for a language: same country and offline first. */
    private Voice voiceFor(String tag) {
        Locale want = Locale.forLanguageTag(tag);
        Voice best = null;
        try {
            for (Voice v : speech.getVoices()) {
                if (!v.getLocale().getLanguage().equals(want.getLanguage())) continue;
                if (v.getFeatures() != null && v.getFeatures().contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED)) continue;
                if (best == null || voiceScore(v, want) > voiceScore(best, want)) best = v;
            }
        } catch (Exception ignored) { }
        return best;
    }

    private void voiceList(String id, JavaScriptReplyProxy r) {
        withSpeech(() -> {
            JSONArray list = new JSONArray();
            if (speechReady) for (String a : ACCENTS) if (voiceFor(a) != null) list.put(a);
            JSONObject o = res(id, speechReady);
            put(o, "voices", list);
            put(o, "listen", SpeechRecognizer.isRecognitionAvailable(this));
            reply(r, o);
        });
    }

    private void say(String id, JSONObject in, JavaScriptReplyProxy r) {
        final String text = in.optString("text");
        final String lang = in.optString("lang", "en-GB");
        final float rate = (float) Math.max(0.6, Math.min(1.4, in.optDouble("rate", 1.0)));
        withSpeech(() -> {
            if (!speechReady) { reply(r, put(res(id, false), "err", "no_speech_engine")); return; }
            Voice v = voiceFor(lang);
            if (v == null) v = voiceFor("en-GB");
            if (v != null) speech.setVoice(v); else speech.setLanguage(Locale.UK);
            speech.setSpeechRate(rate);
            for (String u : new ArrayList<>(sayReplies.keySet())) sayDone(u, true, "replaced");
            sayReplies.put(id, r);
            if (speech.speak(text, TextToSpeech.QUEUE_FLUSH, new Bundle(), id) != TextToSpeech.SUCCESS) sayDone(id, false, "speak_failed");
        });
    }

    private void listen(String id, JSONObject in, JavaScriptReplyProxy r) {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) { reply(r, put(res(id, false), "err", "unavailable")); return; }
        finishListen(false, "replaced", null);
        listenReply = r; listenId = id; listenLang = in.optString("lang", "en-US");
        if (speech != null) speech.stop();
        if (Build.VERSION.SDK_INT >= 23 && checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[] { Manifest.permission.RECORD_AUDIO }, MIC);
            return;
        }
        startListening();
    }

    @Override
    public void onRequestPermissionsResult(int req, String[] perms, int[] results) {
        super.onRequestPermissionsResult(req, perms, results);
        if (req != MIC) return;
        if (results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) startListening();
        else finishListen(false, "not_granted", null);
    }

    private void startListening() {
        if (listenReply == null) return;
        if (recognizer == null) {
            recognizer = SpeechRecognizer.createSpeechRecognizer(this);
            recognizer.setRecognitionListener(new RecognitionListener() {
                @Override public void onReadyForSpeech(Bundle p) { event("state", "listening"); }
                @Override public void onBeginningOfSpeech() { }
                @Override public void onRmsChanged(float v) { }
                @Override public void onBufferReceived(byte[] b) { }
                @Override public void onEndOfSpeech() { }
                @Override public void onError(int e) {
                    String err = e == SpeechRecognizer.ERROR_NO_MATCH || e == SpeechRecognizer.ERROR_SPEECH_TIMEOUT ? "no_speech"
                        : e == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS ? "not_granted"
                        : e == SpeechRecognizer.ERROR_RECOGNIZER_BUSY ? "busy" : "failed";
                    finishListen(false, err, null);
                }
                @Override public void onResults(Bundle b) { finishListen(true, null, first(b)); }
                @Override public void onPartialResults(Bundle b) { String t = first(b); if (t != null && !t.isEmpty()) event("partial", t); }
                @Override public void onEvent(int t, Bundle b) { }
            });
        }
        Intent i = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE, listenLang);
        i.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true);
        /* A learner pauses to find words: wait longer before deciding he has finished (some phones ignore it). */
        i.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 2500L);
        i.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 2000L);
        try { recognizer.startListening(i); } catch (Exception e) { finishListen(false, "failed", null); }
    }

    private static String first(Bundle b) {
        ArrayList<String> l = b == null ? null : b.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
        return l == null || l.isEmpty() ? null : l.get(0);
    }

    private void event(String name, String text) {
        if (listenReply == null) return;
        JSONObject o = new JSONObject();
        put(o, "id", listenId); put(o, "event", name); put(o, "text", text);
        reply(listenReply, o);
    }

    private void finishListen(boolean ok, String err, String text) {
        if (listenReply == null) return;
        JSONObject o = res(listenId, ok);
        if (err != null) put(o, "err", err);
        put(o, "text", text == null ? "" : text);
        JavaScriptReplyProxy r = listenReply;
        listenReply = null; listenId = null;
        reply(r, o);
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
        if (isFinishing() || (updateDialog != null && updateDialog.isShowing())) return;
        updateDialog = new AlertDialog.Builder(this)
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

    private long lastCheck = 0;
    private AlertDialog updateDialog;

    @Override
    protected void onResume() {
        super.onResume();
        /* 0.4: a refused update is offered again when you come back to Echo, at most every 30 minutes. */
        if (SystemClock.elapsedRealtime() - lastCheck > 30 * 60 * 1000L && (updateDialog == null || !updateDialog.isShowing())) {
            lastCheck = SystemClock.elapsedRealtime();
            checkUpdate();
        }
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
                    for (String c : new String[] { "hello", "openApp", "openUrl", "copy", "web.open", "web.text", "web.tree", "web.run", "web.click", "web.select", "web.type", "web.show", "web.hide", "voice.voices", "voice.say", "voice.stop", "voice.listen", "voice.stopListen" }) cmds.put(c);
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
                case "voice.voices": voiceList(id, r); return;
                case "voice.say": say(id, in, r); return;
                case "voice.stop": if (speech != null) speech.stop(); reply(r, out); return;
                case "voice.listen": listen(id, in, r); return;
                case "voice.stopListen": if (recognizer != null) recognizer.stopListening(); reply(r, out); return;
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
        if (speech != null) speech.stop();
        if (recognizer != null) recognizer.cancel();
        finishListen(false, "paused", null);
    }

    @Override
    protected void onDestroy() {
        if (speech != null) speech.shutdown();
        if (recognizer != null) recognizer.destroy();
        super.onDestroy();
    }
}
