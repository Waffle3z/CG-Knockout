package com.codingame.game.knockout;

import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class WorldTest {

    /**
     * Replays games recorded by the Python prototype (knockout/dump_traces.py) and demands the
     * same integer state after every turn: the Java referee, the Python engine and the
     * statement's pseudocode are one simulation.
     */
    @Test
    void matchesPythonEngineBitForBit() throws Exception {
        var in = getClass().getResourceAsStream("/traces.txt");
        assertNotNull(in, "regenerate with: python dump_traces.py > sdk/src/test/resources/traces.txt");
        BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
        int games = 0, turns = 0;
        World w = null;
        List<Unit> start = new ArrayList<>();
        int perPlayer = 0;
        Command[] cmds = null;
        String line;
        String game = "";
        while ((line = r.readLine()) != null) {
            String[] t = line.split(" ");
            switch (t[0]) {
                case "GAME" -> { game = line; start = new ArrayList<>(); w = null; }
                case "UNITS" -> perPlayer = Integer.parseInt(t[1]);
                case "U" -> {
                    Unit u = new Unit(i(t[1]), i(t[2]), i(t[3]), i(t[4]));
                    u.vx = i(t[5]);
                    u.vy = i(t[6]);
                    u.stamina = i(t[7]);
                    start.add(u);
                }
                case "TURN" -> {
                    if (w == null) w = new World(start, perPlayer, 0, World.ARENA_R);
                    assertEquals(i(t[1]), w.radius, game + " radius at turn " + (w.turn + 1));
                    cmds = new Command[2 * perPlayer];
                }
                case "A" -> cmds[i(t[1])] = new Command(i(t[2]), i(t[3]), i(t[4]), "-".equals(t[5]) ? "" : t[5]);
                case "S" -> {
                    if (i(t[1]) == 0) {
                        w.step(cmds);
                        turns++;
                    }
                    Unit u = w.units.get(i(t[1]));
                    String where = game + " turn " + w.turn + " unit " + u.id;
                    assertEquals(i(t[7]) == 1, u.alive, where + " alive");
                    if (u.alive) {
                        assertEquals(i(t[2]), (int) u.x, where + " x");
                        assertEquals(i(t[3]), (int) u.y, where + " y");
                        assertEquals(i(t[4]), (int) u.vx, where + " vx");
                        assertEquals(i(t[5]), (int) u.vy, where + " vy");
                        assertEquals(i(t[6]), u.stamina, where + " stamina");
                    }
                }
                case "END" -> games++;
                default -> fail("bad trace line: " + line);
            }
        }
        System.out.println("cross-checked " + games + " games, " + turns + " turns");
        assertTrue(games > 0 && turns > 1000);
    }

    @Test
    void radiusSchedule() {
        assertEquals(5000, World.radiusForTurn(1));
        assertEquals(5000, World.radiusForTurn(100));
        assertEquals(4960, World.radiusForTurn(101));
        assertEquals(500, World.radiusForTurn(225));
    }

    @Test
    void startIsPointSymmetricAndIntegral() {
        for (long seed = 1; seed < 50; seed++) {
            World w = new World(seed);
            for (int k = 0; k < w.unitsPerPlayer; k++) {
                Unit a = w.units.get(k), b = w.units.get(k + w.unitsPerPlayer);
                assertEquals(a.x, -b.x);
                assertEquals(a.y, -b.y);
                assertEquals(Math.rint(a.x), a.x);
            }
        }
    }

    private static int i(String s) {
        return Integer.parseInt(s);
    }
}
