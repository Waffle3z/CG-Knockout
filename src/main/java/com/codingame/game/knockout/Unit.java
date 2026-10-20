package com.codingame.game.knockout;

/** One unit. Between turns x, y, vx, vy hold whole numbers (see World.step). */
public final class Unit {
    public final int id;
    public final int owner;
    public double x, y, vx, vy;
    public int stamina = World.STAMINA_MAX;
    public boolean alive = true;
    /** Ability actually used this turn: "", "DASH" or "PLANT". */
    public String mode = "";
    /** Thrust applied this turn (dash included), for the viewer. */
    public double ax, ay;

    public Unit(int id, int owner, double x, double y) {
        this.id = id;
        this.owner = owner;
        this.x = x;
        this.y = y;
    }
}
