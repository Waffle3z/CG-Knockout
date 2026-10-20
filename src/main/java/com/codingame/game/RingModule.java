package com.codingame.game;

import com.codingame.game.knockout.Unit;
import com.codingame.game.knockout.World;
import com.codingame.gameengine.core.AbstractPlayer;
import com.codingame.gameengine.core.GameManager;
import com.codingame.gameengine.core.Module;
import com.google.inject.Inject;
import com.google.inject.Singleton;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Sends one snapshot per turn to the viewer's ring module (view/ring-module), which draws the
 * whole game itself on a canvas: positions, velocities, thrust, ability, stamina, impacts.
 * Frame format (JSON): {t, r, u: [[id, owner, x, y, alive, mode, stamina, vx, vy, ax, ay]],
 * i: [[frac, x, y, nx, ny, idA, idB, speed]], m: [[id, message]]}.
 */
@Singleton
public class RingModule implements Module {
    private final GameManager<AbstractPlayer> gameManager;
    private Map<String, Object> frame;

    @Inject
    RingModule(GameManager<AbstractPlayer> gameManager) {
        this.gameManager = gameManager;
        gameManager.registerModule(this);
    }

    void setGlobal(World w) {
        Map<String, Object> g = new HashMap<>();
        g.put("unit_r", World.UNIT_R);
        g.put("arena_r", World.ARENA_R);
        g.put("stamina_max", World.STAMINA_MAX);
        g.put("impact_min_speed", World.IMPACT_MIN_SPEED);
        g.put("units", w.unitsPerPlayer);
        g.put("max_turns", World.MAX_TURNS);
        gameManager.setViewGlobalData("ring", g);
    }

    void snapshot(World w, List<double[]> impacts, Map<Integer, String> messages) {
        Map<String, Object> f = new HashMap<>();
        f.put("t", w.turn);
        f.put("r", World.radiusForTurn(w.turn));
        List<Object[]> u = new ArrayList<>();
        for (Unit x : w.units) {
            u.add(new Object[]{x.id, x.owner, (int) x.x, (int) x.y, x.alive ? 1 : 0, x.mode, x.stamina,
                    (int) x.vx, (int) x.vy, Math.round(x.ax), Math.round(x.ay)});
        }
        f.put("u", u);
        List<Object[]> imp = new ArrayList<>();
        for (double[] h : impacts) {
            imp.add(new Object[]{h[0], Math.round(h[1]), Math.round(h[2]), Math.round(h[3] * 1000) / 1000.0,
                    Math.round(h[4] * 1000) / 1000.0, (int) h[5], (int) h[6], Math.round(h[7])});
        }
        f.put("i", imp);
        List<Object[]> msg = new ArrayList<>();
        for (Map.Entry<Integer, String> e : messages.entrySet()) msg.add(new Object[]{e.getKey(), e.getValue()});
        f.put("m", msg);
        frame = f;
    }

    @Override
    public void onGameInit() {
        gameManager.setViewData("ring", frame);
    }

    @Override
    public void onAfterGameTurn() {
        gameManager.setViewData("ring", frame);
    }

    @Override
    public void onAfterOnEnd() {
    }
}
