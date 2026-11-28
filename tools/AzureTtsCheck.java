/* Checks AzureTts against a fake Azure server on this computer (no key, no Azure account needed).
   Run from the repo root (JDK 17):
     javac -d build/check app/src/main/java/fr/echo/app/AzureTts.java tools/AzureTtsCheck.java
     java -cp build/check fr.echo.app.AzureTtsCheck */
package fr.echo.app;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

public class AzureTtsCheck {
    static int fails = 0;
    static void check(boolean ok, String what) { System.out.println((ok ? "ok   " : "FAIL ") + what); if (!ok) fails++; }

    public static void main(String[] a) throws Exception {
        final List<String> seen = new ArrayList<>();
        HttpServer s = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        s.createContext("/cognitiveservices/v1", x -> {
            String key = x.getRequestHeaders().getFirst("Ocp-Apim-Subscription-Key");
            String body = new String(x.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            seen.add(x.getRequestMethod() + "|" + x.getRequestHeaders().getFirst("Content-Type") + "|" + x.getRequestHeaders().getFirst("X-Microsoft-OutputFormat") + "|" + body);
            int code = "good".equals(key) ? 200 : "busy".equals(key) ? 429 : 401;
            byte[] out = code == 200 ? new byte[4000] : new byte[0];
            x.sendResponseHeaders(code, out.length == 0 ? -1 : out.length);
            if (out.length > 0) x.getResponseBody().write(out);
            x.close();
        });
        s.createContext("/cognitiveservices/voices/list", x -> {
            byte[] out = "[{\"ShortName\":\"de-DE-ConradNeural\",\"Locale\":\"de-DE\",\"Gender\":\"Male\"}]".getBytes(StandardCharsets.UTF_8);
            x.sendResponseHeaders(200, out.length); x.getResponseBody().write(out); x.close();
        });
        s.start();
        AzureTts.base = "http://127.0.0.1:" + s.getAddress().getPort();

        byte[] mp3 = AzureTts.synth("westeurope", "good", "de-DE-ConradNeural", "Tom & Jerry's <plan>", 0.95f);
        check(mp3.length == 4000, "audio comes back");
        String req = seen.get(0);
        check(req.startsWith("POST|application/ssml+xml|audio-24khz-48kbitrate-mono-mp3|"), "method and headers: " + req.substring(0, Math.min(60, req.length())));
        check(req.contains("<voice name='de-DE-ConradNeural'>") && req.contains("xml:lang='de-DE'"), "voice and its language in the SSML");
        check(req.contains("<prosody rate='-5%'>"), "rate 0.95 becomes -5%");
        check(req.contains("Tom &amp; Jerry&apos;s &lt;plan&gt;"), "text is escaped");
        check(AzureTts.ssml("en-GB-SoniaNeural", "Hi", 1.05f).contains("rate='+5%'"), "rate 1.05 becomes +5%");
        check(code(() -> AzureTts.synth("westeurope", "wrong", "de-DE-ConradNeural", "Hi", 1f)).equals("bad_key"), "401 gives bad_key");
        check(code(() -> AzureTts.synth("westeurope", "busy", "de-DE-ConradNeural", "Hi", 1f)).equals("quota"), "429 gives quota");
        check(code(() -> AzureTts.synth("westeurope", "", "de-DE-ConradNeural", "Hi", 1f)).equals("no_key"), "no key gives no_key");
        check(code(() -> AzureTts.synth("westeurope", "good", "de-DE-Conrad'><x", "Hi", 1f)).equals("bad_voice"), "a strange voice name is refused");
        check(AzureTts.voices("westeurope", "good").contains("ConradNeural"), "voice list comes back as JSON text");
        s.stop(0);
        AzureTts.base = "http://127.0.0.1:1";
        check(code(() -> AzureTts.synth("westeurope", "good", "de-DE-ConradNeural", "Hi", 1f)).equals("network"), "no server gives network");
        AzureTts.base = "";
        check(code(() -> AzureTts.synth("west europe", "good", "de-DE-ConradNeural", "Hi", 1f)).equals("bad_region"), "a strange region is refused");
        System.out.println(fails == 0 ? "PASS" : "FAIL (" + fails + ")");
        System.exit(fails == 0 ? 0 : 1);
    }

    interface Call { Object run() throws Exception; }
    static String code(Call c) { try { c.run(); return "none"; } catch (AzureTts.Fail f) { return f.code; } catch (Exception e) { return "other:" + e; } }
}
