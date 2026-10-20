import com.codingame.gameengine.runner.MultiplayerGameRunner;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.lang.reflect.Method;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * Local viewer + game launcher. Run through run.ps1 (plain java -cp): exec:java breaks the SDK's
 * resource serving -- see sprouts/README.md.
 *
 *   viewer   http://localhost:PORT/test.html       (the SDK's own page, one game)
 *   launcher http://localhost:PORT+1/              (pick two bots and a seed, "New game")
 *
 * Arguments: [seed (0 = random)] [port] [botA] [botB]. Bots are files in target/bots: .exe runs
 * directly, .py through python -u, .jar through java -jar. They run as external processes:
 * in-process Java agents crash at shutdown on Java 21 (SDK 4.7.8 calls the removed Thread.stop()).
 *
 * The launcher plays a game with MultiplayerGameRunner.simulate() and writes its JSON over the
 * game.json the viewer serves (java.io.tmpdir/codingame), so reloading the player shows it.
 * The JSON comes from the runner's private getJSONResult() -- the same text start() serves.
 */
public class Main {
    static final File BOTS = new File("target/bots");

    public static void main(String[] args) throws IOException {
        long seed = args.length > 0 ? Long.parseLong(args[0]) : 0;
        int port = args.length > 1 ? Integer.parseInt(args[1]) : 8890;
        String a = args.length > 2 ? args[2] : "boss2";
        String b = args.length > 3 ? args[3] : "boss1";
        startLauncher(port);
        MultiplayerGameRunner gameRunner = new MultiplayerGameRunner();
        gameRunner.addAgent(command(a), a);
        gameRunner.addAgent(command(b), b);
        if (seed != 0) gameRunner.setSeed(seed);
        gameRunner.start(port);
    }

    static String[] command(String bot) {
        File f = resolve(bot);
        String path = f.getAbsolutePath();
        String name = f.getName().toLowerCase();
        if (name.endsWith(".py")) return new String[]{"python", "-u", path};
        if (name.endsWith(".jar")) return new String[]{"java", "-jar", path};
        return new String[]{path};
    }

    static File resolve(String bot) {
        File exact = new File(BOTS, bot);
        if (exact.isFile()) return exact;
        for (String ext : new String[]{".exe", ".py", ".jar"}) {
            File f = new File(BOTS, bot + ext);
            if (f.isFile()) return f;
        }
        throw new IllegalArgumentException("no bot \"" + bot + "\" in " + BOTS.getAbsolutePath());
    }

    static List<String> botNames() {
        List<String> out = new ArrayList<>();
        File[] files = BOTS.listFiles();
        if (files == null) return out;
        for (File f : files) {
            String n = f.getName();
            if (n.endsWith(".exe") || n.endsWith(".py") || n.endsWith(".jar")) out.add(n.replaceAll("\\.(exe|py|jar)$", ""));
        }
        out.sort(null);
        return out;
    }

    // ---- launcher ----------------------------------------------------------------------------

    static void startLauncher(int viewerPort) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("localhost", viewerPort + 1), 0);
        server.createContext("/new", ex -> {
            Map<String, String> q = query(ex);
            String reply;
            int code = 200;
            try {
                reply = play(q.getOrDefault("a", "boss2"), q.getOrDefault("b", "boss1"), q.getOrDefault("seed", ""));
            } catch (Exception e) {
                code = 500;
                reply = "{\"error\":\"" + e.toString().replace("\\", "/").replace("\"", "'") + "\"}";
            }
            send(ex, code, "application/json", reply);
        });
        server.createContext("/", ex -> send(ex, 200, "text/html; charset=utf-8", page(viewerPort)));
        server.start();
        System.out.println("Game launcher: http://localhost:" + (viewerPort + 1) + "/");
    }

    /** Plays one game, overwrites the viewer's game.json, returns a short JSON summary. */
    static synchronized String play(String a, String b, String seedText) throws Exception {
        long seed = seedText.isBlank() ? 1 + new Random().nextInt(1_000_000_000) : Long.parseLong(seedText.trim());
        MultiplayerGameRunner runner = new MultiplayerGameRunner();
        runner.addAgent(command(a), a);
        runner.addAgent(command(b), b);
        runner.setSeed(seed);
        var result = runner.simulate();
        Method m = Class.forName("com.codingame.gameengine.runner.GameRunner").getDeclaredMethod("getJSONResult");
        m.setAccessible(true);
        String json = (String) m.invoke(runner);
        Path file = Path.of(System.getProperty("java.io.tmpdir"), "codingame", "game.json");
        Files.writeString(file, json, StandardCharsets.UTF_8);
        return "{\"seed\":" + seed + ",\"a\":\"" + a + "\",\"b\":\"" + b + "\",\"scoreA\":" + result.scores.get(0)
                + ",\"scoreB\":" + result.scores.get(1) + "}";
    }

    static Map<String, String> query(HttpExchange ex) {
        Map<String, String> out = new HashMap<>();
        String q = ex.getRequestURI().getRawQuery();
        if (q == null) return out;
        for (String kv : q.split("&")) {
            int i = kv.indexOf('=');
            if (i > 0) out.put(kv.substring(0, i), URLDecoder.decode(kv.substring(i + 1), StandardCharsets.UTF_8));
        }
        return out;
    }

    static void send(HttpExchange ex, int code, String type, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", type);
        ex.getResponseHeaders().set("Cache-Control", "no-store");
        ex.sendResponseHeaders(code, bytes.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(bytes);
        }
    }

    static String page(int viewerPort) {
        StringBuilder opts = new StringBuilder();
        for (String n : botNames()) opts.append("<option>").append(n).append("</option>");
        return """
<!doctype html><html><head><meta charset="utf-8"><title>Knockout launcher</title>
<style>
 body{margin:0;background:#1b2128;color:#e8e4da;font:15px system-ui,sans-serif}
 header{display:flex;gap:12px;align-items:center;flex-wrap:wrap;padding:10px 16px;background:#252d36}
 select,input,button{font:inherit;padding:5px 10px;border-radius:6px;border:1px solid #4a5563;background:#1b2128;color:inherit}
 button{background:#ed855b;border-color:#ed855b;color:#1b2128;font-weight:700;cursor:pointer}
 button:disabled{opacity:.5;cursor:wait}
 .vs{color:#9aa3ad} #status{color:#9aa3ad} b.a{color:#ed855b} b.b{color:#378ed0}
 iframe{width:100%;height:calc(100vh - 56px);border:0;display:block}
</style></head><body>
<header>
 <b class="a">Player 1</b><select id="a">OPTS</select>
 <span class="vs">vs</span>
 <b class="b">Player 2</b><select id="b">OPTS</select>
 <label>seed <input id="seed" size="10" placeholder="random"></label>
 <button id="go">New game</button>
 <span id="status">bots are the files in target/bots (.exe, .py, .jar)</span>
</header>
<iframe id="view" src="http://localhost:VPORT/test.html"></iframe>
<script>
 const $ = id => document.getElementById(id);
 try { $('a').value = localStorage.getItem('knockout.a') || 'boss2'; $('b').value = localStorage.getItem('knockout.b') || 'boss1'; } catch (e) {}
 $('go').onclick = async () => {
   $('go').disabled = true; $('status').textContent = 'playing...';
   try { localStorage.setItem('knockout.a', $('a').value); localStorage.setItem('knockout.b', $('b').value); } catch (e) {}
   const q = new URLSearchParams({a: $('a').value, b: $('b').value, seed: $('seed').value});
   try {
     const r = await (await fetch('/new?' + q)).json();
     if (r.error) { $('status').textContent = 'error: ' + r.error; }
     else {
       const w = r.scoreA > r.scoreB ? r.a + ' wins' : r.scoreB > r.scoreA ? r.b + ' wins' : 'draw';
       $('status').textContent = 'seed ' + r.seed + ': ' + w + ' (' + r.scoreA + ' to ' + r.scoreB + ' units left)';
       $('view').src = 'http://localhost:VPORT/test.html?t=' + Date.now();
     }
   } catch (e) { $('status').textContent = 'error: ' + e; }
   $('go').disabled = false;
 };
</script></body></html>
""".replace("OPTS", opts.toString()).replace("VPORT", Integer.toString(viewerPort));
    }
}
