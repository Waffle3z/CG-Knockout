import com.codingame.gameengine.runner.MultiplayerGameRunner;

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Records the pre-game replay (src/main/resources/view/demo.js), the game CodinGame shows before a
 * test is played. Bots are names in target/bots, as in the launcher.
 *   java -cp ... MakeDemo botA botB seed1 [seed2 ...]   plays each seed, prints the score and length
 *   java -cp ... MakeDemo botA botB seed --write        records that one seed into demo.js
 */
public class MakeDemo {
    static final String[] AVATARS = {
        "https://static.codingame.com/servlet/fileservlet?id=16085734516701&format=viewer_avatar",
        "https://static.codingame.com/servlet/fileservlet?id=16085846089817&format=viewer_avatar",
    };

    public static void main(String[] args) throws Exception {
        String a = args[0], b = args[1];
        boolean write = args[args.length - 1].equals("--write");
        int last = write ? args.length - 1 : args.length;
        for (int i = 2; i < last; i++) {
            long seed = Long.parseLong(args[i]);
            MultiplayerGameRunner runner = new MultiplayerGameRunner();
            runner.addAgent(Main.command(a), a);
            runner.addAgent(Main.command(b), b);
            runner.setSeed(seed);
            var result = runner.simulate();
            System.out.printf("seed %d: %s %d - %d %s, %d frames%n", seed, a, result.scores.get(0),
                    result.scores.get(1), b, result.views.size());
            if (!write) continue;
            Method m = Class.forName("com.codingame.gameengine.runner.GameRunner").getDeclaredMethod("getJSONResult");
            m.setAccessible(true);
            String json = (String) m.invoke(runner);
            // CodinGame's demo shows neutral players: stock names and avatars instead of local paths
            int agents = json.lastIndexOf("\"agents\":");
            json = json.substring(0, agents) + "\"agents\":[" + agent(0) + "," + agent(1) + "]}";
            String js = "// Pre-game replay shown before a test is played: " + a + " vs " + b + ", seed " + seed + ".\n"
                    + "// Regenerate after viewer or referee-output changes (see README.md, Demo).\n"
                    + "export const demo = " + json + ";\n";
            Path out = Path.of("src/main/resources/view/demo.js");
            Files.writeString(out, js, StandardCharsets.UTF_8);
            System.out.println("wrote " + out + " (" + js.length() / 1024 + " KB)");
        }
    }

    static String agent(int i) {
        return "{\"index\":" + i + ",\"name\":\"Player " + (i + 1) + "\",\"avatar\":\"" + AVATARS[i] + "\",\"agentId\":" + i + "}";
    }
}
