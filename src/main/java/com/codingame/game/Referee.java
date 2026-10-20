package com.codingame.game;

import com.codingame.game.knockout.Command;
import com.codingame.game.knockout.Unit;
import com.codingame.game.knockout.World;
import com.codingame.gameengine.core.AbstractPlayer;
import com.codingame.gameengine.core.AbstractReferee;
import com.codingame.gameengine.core.GameManager;
import com.codingame.gameengine.core.MultiplayerGameManager;
import com.codingame.gameengine.module.endscreen.EndScreenModule;
import com.codingame.gameengine.module.entities.GraphicEntityModule;
import com.google.inject.Inject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Simultaneous turns: both players receive the same state, both answer, then the world steps
 * once. Protocol in config/stub.txt; physics in {@link World} (= the statement's Expert rules).
 * Score = units left, so elimination, the turn limit and draws all rank correctly.
 */
public class Referee extends AbstractReferee {
    @Inject
    private MultiplayerGameManager<Player> gameManager;
    @Inject
    private EndScreenModule endScreenModule;
    @Inject
    private GraphicEntityModule graphics;   // unused by the referee; the end screen draws with it
    @Inject
    private RingModule ringModule;

    private World world;

    @Override
    public void init() {
        world = new World(gameManager.getSeed());
        ringModule.setGlobal(world);
        ringModule.snapshot(world, List.of(), Map.of());
        // 225 turns: 2 x 1000 ms + 224 x 2 x 50 ms = 24.4 s, under the SDK's 25 s warning (30 s cap);
        // 50 ms is the SDK's minimum turn time
        gameManager.setMaxTurns(World.MAX_TURNS);
        gameManager.setFirstTurnMaxTime(1000);
        gameManager.setTurnMaxTime(50);
        gameManager.setFrameDuration(125);
    }

    @Override
    public void gameTurn(int turn) {
        for (Player p : gameManager.getActivePlayers()) {
            sendInputs(p, turn);
            p.setExpectedOutputLines(world.alive(p.getIndex()).size());
            p.execute();
        }

        Command[] commands = new Command[world.units.size()];
        Map<Integer, String> messages = new HashMap<>();
        String[] failure = new String[2];
        for (int i = 0; i < 2; i++) {
            Player p = gameManager.getPlayer(i);
            List<Unit> mine = world.alive(i);
            try {
                List<Command> cmds = parse(p.getOutputs(), mine.size());
                for (int k = 0; k < mine.size(); k++) {
                    commands[mine.get(k).id] = cmds.get(k);
                    if (!cmds.get(k).message.isEmpty()) messages.put(mine.get(k).id, cmds.get(k).message);
                }
            } catch (AbstractPlayer.TimeoutException e) {
                failure[i] = "timeout";
            } catch (InvalidAction e) {
                failure[i] = "invalid action: " + e.getMessage();
            }
        }
        if (failure[0] != null || failure[1] != null) {
            endOnFailure(failure);
            return;
        }

        World.StepResult result = world.step(commands);
        ringModule.snapshot(world, result.impacts, messages);
        for (Unit u : result.knockedOut) {
            Player owner = gameManager.getPlayer(u.owner);
            gameManager.addToGameSummary(owner.getNicknameToken()
                    + "'s unit " + u.id + " was knocked out on turn " + world.turn);
            // a marker on the replay timeline for every knockout
            gameManager.addTooltip(owner, owner.getNicknameToken() + " loses unit " + u.id
                    + " (" + world.alive(u.owner).size() + " left)");
        }
        setScores(world.alive(0).size(), world.alive(1).size());

        int outcome = world.outcome();
        if (outcome >= 0) {
            announce(outcome);
            gameManager.endGame();
        }
    }

    @Override
    public void onEnd() {
        int[] scores = new int[2];
        String[] texts = new String[2];
        for (int i = 0; i < 2; i++) {
            Player p = gameManager.getPlayer(i);
            scores[i] = p.getScore();
            texts[i] = !p.isActive() ? "disqualified" : scores[i] == 1 ? "1 unit left" : scores[i] + " units left";
        }
        endScreenModule.setScores(scores, texts);
        endScreenModule.setTitleRankingsSprite("logo.png");
    }

    private void sendInputs(Player p, int turn) {
        int me = p.getIndex();
        if (turn == 1) p.sendInputLine(Integer.toString(world.unitsPerPlayer));
        p.sendInputLine(turn + " " + world.radius);
        sendUnits(p, world.alive(me));
        sendUnits(p, world.alive(1 - me));
    }

    private static void sendUnits(Player p, List<Unit> units) {
        p.sendInputLine(Integer.toString(units.size()));
        for (Unit u : units) {
            p.sendInputLine(u.id + " " + (int) u.x + " " + (int) u.y + " " + (int) u.vx + " " + (int) u.vy + " " + u.stamina);
        }
    }

    /**
     * "x y power [DASH|PLANT] [message]", one line per unit. A fourth token that is not an
     * ability starts the message: bots append debug text, and this tree has been bitten by
     * consuming a whole line before.
     */
    static List<Command> parse(List<String> lines, int expected) throws InvalidAction {
        if (lines.size() != expected) {
            throw new InvalidAction("expected " + expected + " lines, got " + lines.size());
        }
        List<Command> out = new ArrayList<>();
        for (String line : lines) {
            String[] tok = line.trim().split("\\s+", 5);
            if (tok.length < 3) throw new InvalidAction("expected \"x y power\", got: " + line);
            int x = parseInt(tok[0], line), y = parseInt(tok[1], line), power = parseInt(tok[2], line);
            if (Math.abs(x) > 100000 || Math.abs(y) > 100000) {
                throw new InvalidAction("target must be within -100000..100000, got: " + line);
            }
            if (power < 0 || power > 100) throw new InvalidAction("power must be 0 to 100, got " + power);
            String mode = "";
            String message = "";
            if (tok.length > 3) {
                String t3 = tok[3].toUpperCase();
                if (t3.equals("DASH") || t3.equals("PLANT")) {
                    mode = t3;
                    message = tok.length > 4 ? tok[4] : "";
                } else {
                    message = line.trim().split("\\s+", 4)[3];
                }
            }
            out.add(new Command(x, y, power, mode, message.length() > 40 ? message.substring(0, 40) : message));
        }
        return out;
    }

    private static int parseInt(String token, String line) throws InvalidAction {
        try {
            return Integer.parseInt(token);
        } catch (NumberFormatException e) {
            throw new InvalidAction("expected an integer, got \"" + token + "\" in: " + line);
        }
    }

    private void endOnFailure(String[] failure) {
        for (int i = 0; i < 2; i++) {
            if (failure[i] != null) {
                Player p = gameManager.getPlayer(i);
                gameManager.addToGameSummary(GameManager.formatErrorMessage(p.getNicknameToken() + ": " + failure[i]));
                p.deactivate(p.getNicknameToken() + ": " + failure[i]);
                p.setScore(-1);
            } else {
                gameManager.getPlayer(i).setScore(world.alive(i).size());
            }
        }
        gameManager.endGame();
    }

    private void announce(int outcome) {
        int a = world.alive(0).size(), b = world.alive(1).size();
        if (outcome == 2) {
            gameManager.addToGameSummary(GameManager.formatSuccessMessage(
                    a == 0 ? "Both sides were knocked out on the same turn: draw" : "Turn limit reached with " + a + " units each: draw"));
            return;
        }
        Player w = gameManager.getPlayer(outcome);
        String why = world.alive(1 - outcome).isEmpty() ? "knocked out every enemy unit"
                : "has more units left at the turn limit (" + Math.max(a, b) + " to " + Math.min(a, b) + ")";
        gameManager.addToGameSummary(GameManager.formatSuccessMessage(w.getNicknameToken() + " " + why));
        gameManager.addTooltip(w, w.getNicknameToken() + " wins!");
    }

    private void setScores(int s0, int s1) {
        gameManager.getPlayer(0).setScore(s0);
        gameManager.getPlayer(1).setScore(s1);
    }
}
