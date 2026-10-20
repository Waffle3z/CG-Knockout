package tools;

import com.codingame.gameengine.runner.MultiplayerGameRunner;
import com.codingame.gameengine.runner.simulate.GameResult;

import java.io.File;

/**
 * End to end through the real SDK, no browser: plays bot A against bot B on several seeds, both
 * seats, and reports A's record, agent errors and replay size.
 *   java -cp ... tools.SimulateCheck [games] [botA] [botB]   (names in target/bots, no .exe)
 */
public class SimulateCheck {
    public static void main(String[] args) {
        int games = args.length > 0 ? Integer.parseInt(args[0]) : 6;
        String a = args.length > 1 ? args[1] : "boss2";
        String b = args.length > 2 ? args[2] : "boss1";
        int winsA = 0, winsB = 0, draws = 0, errors = 0;
        long bytes = 0;
        for (int g = 1; g <= games; g++) {
            boolean swap = g % 2 == 0;   // alternate seats on consecutive seeds
            MultiplayerGameRunner runner = new MultiplayerGameRunner();
            runner.addAgent(new String[]{exe(swap ? b : a)}, swap ? b : a);
            runner.addAgent(new String[]{exe(swap ? a : b)}, swap ? a : b);
            runner.setSeed((long) ((g + 1) / 2) * 7919);
            GameResult r = runner.simulate();
            int sA = r.scores.get(swap ? 1 : 0), sB = r.scores.get(swap ? 0 : 1);
            if (sA > sB) winsA++; else if (sB > sA) winsB++; else draws++;
            errors += r.errors.values().stream().mapToLong(l -> l.stream().filter(e -> e != null && !e.isEmpty()).count()).sum();
            bytes += String.join("", r.views).length();
            if (r.failCause != null) System.out.println("seed " + g + " failCause: " + r.failCause);
        }
        System.out.printf("%s vs %s: %d-%d-%d (A wins-B wins-draws) over %d games, agent error lines %d, mean replay %d KB%n",
                a, b, winsA, winsB, draws, games, errors, bytes / games / 1024);
    }

    static String exe(String name) {
        return new File("target/bots/" + name + ".exe").getAbsolutePath();
    }
}
