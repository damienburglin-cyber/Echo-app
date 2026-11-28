package fr.echo.app;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/* 0.7: Microsoft Azure Speech, the official way to the same natural voices as Edge's "Read aloud".
   Damien's own free Azure resource (F0: 0.5 million characters a month). The key is typed by Damien in the
   app and kept on the phone only: never in this public repo, never sent to the Echo page.
   Plain Java (no Android class) so tools/AzureTtsCheck.java can test it on a computer. */
final class AzureTts {
    /* Tests point this at a local server; empty means the real Azure endpoint of the region. */
    static String base = "";
    static final int CONNECT_MS = 5000, READ_MS = 10000;

    static final class Fail extends Exception {
        final String code;
        Fail(String code) { super(code); this.code = code; }
    }

    static boolean validRegion(String r) { return r != null && r.matches("[a-z0-9]{3,30}"); }
    static boolean validVoice(String v) { return v != null && v.matches("[A-Za-z]{2,3}-[A-Za-z0-9]{2,8}-[A-Za-z0-9]{2,60}"); }

    private static String host(String region) throws Fail {
        if (!base.isEmpty()) return base;
        if (!validRegion(region)) throw new Fail("bad_region");
        return "https://" + region + ".tts.speech.microsoft.com";
    }

    static String esc(String s) {
        StringBuilder b = new StringBuilder(s.length() + 16);
        for (char c : s.toCharArray()) {
            switch (c) {
                case '&': b.append("&amp;"); break;
                case '<': b.append("&lt;"); break;
                case '>': b.append("&gt;"); break;
                case '"': b.append("&quot;"); break;
                case '\'': b.append("&apos;"); break;
                default: if (c >= 0x20 || c == '\n' || c == '\t') b.append(c);
            }
        }
        return b.toString();
    }

    /* The text is English; the voice keeps its own language, which gives the accent (a German voice reads it
       the way a German colleague would). Rate 1.0 = the voice's normal pace. */
    static String ssml(String voice, String text, float rate) {
        int pct = Math.round((Math.max(0.5f, Math.min(1.5f, rate)) - 1f) * 100f);
        String lang = voice.substring(0, voice.indexOf('-', voice.indexOf('-') + 1));
        return "<speak version='1.0' xmlns='http://www.w3.org/2001/10/synthesis' xml:lang='" + lang + "'>"
            + "<voice name='" + voice + "'><prosody rate='" + (pct >= 0 ? "+" : "") + pct + "%'>" + esc(text) + "</prosody></voice></speak>";
    }

    private static Fail failFor(int code) {
        if (code == 401 || code == 403) return new Fail("bad_key");
        if (code == 429) return new Fail("quota");
        return new Fail("http_" + code);
    }

    /* MP3 audio of the text. Throws Fail with a short code: bad_key, quota, network, http_NNN, bad_voice, empty. */
    static byte[] synth(String region, String key, String voice, String text, float rate) throws Fail {
        if (key == null || key.isEmpty()) throw new Fail("no_key");
        if (!validVoice(voice)) throw new Fail("bad_voice");
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(host(region) + "/cognitiveservices/v1").openConnection();
            c.setConnectTimeout(CONNECT_MS);
            c.setReadTimeout(READ_MS);
            c.setRequestMethod("POST");
            c.setDoOutput(true);
            c.setRequestProperty("Ocp-Apim-Subscription-Key", key);
            c.setRequestProperty("Content-Type", "application/ssml+xml");
            c.setRequestProperty("X-Microsoft-OutputFormat", "audio-24khz-48kbitrate-mono-mp3");
            c.setRequestProperty("User-Agent", "EchoApp");
            byte[] body = ssml(voice, text, rate).getBytes(StandardCharsets.UTF_8);
            try (OutputStream o = c.getOutputStream()) { o.write(body); }
            int code = c.getResponseCode();
            if (code != 200) throw failFor(code);
            byte[] audio = readAll(c.getInputStream());
            if (audio.length < 100) throw new Fail("empty");
            return audio;
        } catch (Fail f) {
            throw f;
        } catch (Exception e) {
            throw new Fail("network");
        } finally {
            if (c != null) c.disconnect();
        }
    }

    /* The region's voice list, as Azure sends it (a JSON array). Echo reads it; listing does not use the quota. */
    static String voices(String region, String key) throws Fail {
        if (key == null || key.isEmpty()) throw new Fail("no_key");
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(host(region) + "/cognitiveservices/voices/list").openConnection();
            c.setConnectTimeout(CONNECT_MS);
            c.setReadTimeout(READ_MS);
            c.setRequestProperty("Ocp-Apim-Subscription-Key", key);
            c.setRequestProperty("User-Agent", "EchoApp");
            int code = c.getResponseCode();
            if (code != 200) throw failFor(code);
            return new String(readAll(c.getInputStream()), StandardCharsets.UTF_8);
        } catch (Fail f) {
            throw f;
        } catch (Exception e) {
            throw new Fail("network");
        } finally {
            if (c != null) c.disconnect();
        }
    }

    private static byte[] readAll(InputStream in) throws java.io.IOException {
        try (InputStream i = in) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[16384];
            int n;
            while ((n = i.read(buf)) > 0) out.write(buf, 0, n);
            return out.toByteArray();
        }
    }
}
