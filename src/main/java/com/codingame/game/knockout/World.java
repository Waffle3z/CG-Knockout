package com.codingame.game.knockout;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

/**
 * The whole game, with no SDK dependency. {@link #step} is the "Expert rules" pseudocode of
 * config/statement_en.html line for line: keep the two identical, including the order of
 * floating-point operations (players reproduce it bit for bit). Cross-checked against the
 * Python prototype by WorldTest.
 */
public final class World {
    public static final int UNITS_PER_PLAYER = 6;
    public static final int UNIT_R = 400;
    public static final int ARENA_R = 5000;
    public static final int MIN_R = 500;
    public static final int SHRINK_FROM_TURN = 101;   // 1-based: first turn with a smaller ring
    public static final int SHRINK = 40;
    public static final int MAX_TURNS = 225;
    public static final int THRUST = 100;
    public static final int DASH_MULT = 5;
    public static final int DASH_COST = 40;
    public static final int PLANT_COST = 25;
    public static final double GRIP = 150;
    public static final int REGEN = 6;
    public static final int STAMINA_MAX = 100;
    public static final int SUBSTEPS = 4;
    public static final double RESTITUTION = 0.5;
    /** Viewer only: a contact ONSET closing faster than this is drawn as an impact. */
    public static final double IMPACT_MIN_SPEED = 150;
    private static final double TOUCH_EPS = 10;

    public final List<Unit> units = new ArrayList<>();   // all units, by id
    public final int unitsPerPlayer;
    /** Turns already played; the next turn is number turn + 1. */
    public int turn = 0;
    /** Ring radius for the next turn. */
    public int radius = ARENA_R;
    private Set<Long> touching = new HashSet<>();

    public World(long seed) {
        this(seed, UNITS_PER_PLAYER);
    }

    public World(long seed, int unitsPerPlayer) {
        this.unitsPerPlayer = unitsPerPlayer;
        Random rng = new Random(seed);
        List<double[]> placed = new ArrayList<>();
        while (placed.size() < unitsPerPlayer) {
            double r = 1200 + rng.nextDouble() * 2400;
            double a = Math.PI / 2 + 0.25 + rng.nextDouble() * (Math.PI - 0.5);   // left half
            double x = Math.round(r * Math.cos(a)), y = Math.round(r * Math.sin(a));
            if (x > -UNIT_R * 1.5) continue;
            boolean ok = true;
            for (double[] p : placed) {
                if (Math.hypot(x - p[0], y - p[1]) <= 2 * UNIT_R + 200) ok = false;
            }
            if (ok) placed.add(new double[]{x, y});
        }
        for (int i = 0; i < unitsPerPlayer; i++) units.add(new Unit(i, 0, placed.get(i)[0], placed.get(i)[1]));
        // point-symmetric: the opponent starts at the mirror image of every unit
        for (int i = 0; i < unitsPerPlayer; i++)
            units.add(new Unit(unitsPerPlayer + i, 1, -placed.get(i)[0], -placed.get(i)[1]));
    }

    /** For tests: start from given units instead of a random placement. */
    public World(List<Unit> start, int unitsPerPlayer, int turn, int radius) {
        this.unitsPerPlayer = unitsPerPlayer;
        this.units.addAll(start);
        this.turn = turn;
        this.radius = radius;
    }

    public List<Unit> alive(int owner) {
        List<Unit> out = new ArrayList<>();
        for (Unit u : units) if (u.alive && u.owner == owner) out.add(u);
        return out;
    }

    public List<Unit> alive() {
        List<Unit> out = new ArrayList<>();
        for (Unit u : units) if (u.alive) out.add(u);
        return out;
    }

    /** What happened in one turn, for the referee and viewer. */
    public static final class StepResult {
        public final List<Unit> knockedOut = new ArrayList<>();
        /** Impacts: {fraction of the turn, x, y, nx, ny, idA, idB, closing speed}. Viewer only. */
        public final List<double[]> impacts = new ArrayList<>();
    }

    /** Plays one turn. commands[id] for every living unit (null = coast). */
    public StepResult step(Command[] commands) {
        StepResult res = new StepResult();
        List<Unit> w = alive();
        int n = w.size();
        for (Unit u : w) {
            Command c = commands[u.id];
            double tx = c == null ? u.x : c.x, ty = c == null ? u.y : c.y;
            double p = c == null ? 0 : Math.min(THRUST, Math.max(0, c.power));
            String mode = c == null ? "" : c.mode;
            u.mode = "";
            if ("DASH".equals(mode) && u.stamina >= DASH_COST) {
                u.stamina -= DASH_COST;
                p = p * DASH_MULT;
                u.mode = "DASH";
            }
            if ("PLANT".equals(mode) && u.stamina >= PLANT_COST) {
                u.stamina -= PLANT_COST;
                u.mode = "PLANT";
            }
            double dx = tx - u.x, dy = ty - u.y;
            double d = Math.sqrt(dx * dx + dy * dy);
            if (d > 0) {
                u.ax = dx / d * p;
                u.ay = dy / d * p;
            } else {
                u.ax = 0;
                u.ay = 0;
            }
        }

        double grip = GRIP / SUBSTEPS;
        double minD = 2 * UNIT_R;
        double impulse = (1 + RESTITUTION) / 2;   // 0.75: restitution 0.5, equal masses
        Set<Long> hitThisTurn = new HashSet<>();
        for (int sub = 0; sub < SUBSTEPS; sub++) {
            for (Unit u : w) {
                u.vx += u.ax / SUBSTEPS;
                u.vy += u.ay / SUBSTEPS;
                u.x += u.vx / SUBSTEPS;
                u.y += u.vy / SUBSTEPS;
            }
            detectImpacts(w, sub, hitThisTurn, res);
            for (int i = 0; i < n; i++) {
                Unit a = w.get(i);
                for (int j = i + 1; j < n; j++) {
                    Unit b = w.get(j);
                    double dx = b.x - a.x, dy = b.y - a.y;
                    double d = Math.sqrt(dx * dx + dy * dy);
                    if (d >= minD) continue;
                    double nx, ny;
                    if (d == 0) {
                        nx = 1;
                        ny = 0;
                    } else {
                        nx = dx / d;
                        ny = dy / d;
                    }
                    double closing = (b.vx - a.vx) * nx + (b.vy - a.vy) * ny;
                    if (closing < 0) {
                        double jj = -impulse * closing;
                        a.vx -= jj * nx;
                        a.vy -= jj * ny;
                        b.vx += jj * nx;
                        b.vy += jj * ny;
                    }
                    double push = (minD - d) / 2;
                    a.x -= push * nx;
                    a.y -= push * ny;
                    b.x += push * nx;
                    b.y += push * ny;
                }
            }
            for (Unit u : w) {
                if (!"PLANT".equals(u.mode)) continue;
                double s = Math.sqrt(u.vx * u.vx + u.vy * u.vy);
                if (s <= grip) {
                    u.vx = 0;
                    u.vy = 0;
                } else {
                    double f = (s - grip) / s;
                    u.vx = u.vx * f;
                    u.vy = u.vy * f;
                }
            }
        }

        for (Unit u : w) {
            u.x = roundHalfAway(u.x);
            u.y = roundHalfAway(u.y);
            u.vx = (double) (long) u.vx;
            u.vy = (double) (long) u.vy;
        }
        double r2 = (double) radius * radius;
        for (Unit u : w) {
            if (u.x * u.x + u.y * u.y > r2) {
                u.alive = false;
                res.knockedOut.add(u);
            } else {
                u.stamina = Math.min(STAMINA_MAX, u.stamina + REGEN);
            }
        }
        turn++;
        radius = radiusForTurn(turn + 1);
        return res;
    }

    /** Ring radius during 1-based turn t. */
    public static int radiusForTurn(int t) {
        if (t < SHRINK_FROM_TURN) return ARENA_R;
        return Math.max(MIN_R, ARENA_R - SHRINK * (t - SHRINK_FROM_TURN + 1));
    }

    static double roundHalfAway(double v) {
        return v >= 0 ? Math.floor(v + 0.5) : -Math.floor(-v + 0.5);
    }

    /** Viewer only: a pair that starts touching while closing fast. Sustained pushes never count. */
    private void detectImpacts(List<Unit> w, int sub, Set<Long> hitThisTurn, StepResult res) {
        Set<Long> now = new HashSet<>();
        double touch = 2 * UNIT_R + TOUCH_EPS;
        for (int i = 0; i < w.size(); i++) {
            Unit a = w.get(i);
            for (int j = i + 1; j < w.size(); j++) {
                Unit b = w.get(j);
                double dx = b.x - a.x, dy = b.y - a.y;
                double d = Math.sqrt(dx * dx + dy * dy);
                if (d >= touch) continue;
                long key = (long) a.id * 1000 + b.id;
                now.add(key);
                if (touching.contains(key) || hitThisTurn.contains(key) || d == 0) continue;
                double nx = dx / d, ny = dy / d;
                double closing = -((b.vx - a.vx) * nx + (b.vy - a.vy) * ny);
                if (closing >= IMPACT_MIN_SPEED) {
                    hitThisTurn.add(key);
                    res.impacts.add(new double[]{(sub + 1) / (double) SUBSTEPS, a.x + nx * UNIT_R, a.y + ny * UNIT_R,
                            nx, ny, a.id, b.id, closing});
                }
            }
        }
        touching = now;
    }

    /** -1 while the game runs; else 0 or 1 for a winner, 2 for a draw. */
    public int outcome() {
        int a = alive(0).size(), b = alive(1).size();
        if (a == 0 && b == 0) return 2;
        if (a == 0) return 1;
        if (b == 0) return 0;
        if (turn >= MAX_TURNS) return a == b ? 2 : (a > b ? 0 : 1);
        return -1;
    }
}
