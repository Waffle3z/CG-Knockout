package com.codingame.game.knockout;

/** "x y power [DASH|PLANT]" for one unit. */
public final class Command {
    public final int x, y, power;
    public final String mode;
    public final String message;

    public Command(int x, int y, int power, String mode, String message) {
        this.x = x;
        this.y = y;
        this.power = power;
        this.mode = mode;
        this.message = message;
    }

    public Command(int x, int y, int power, String mode) {
        this(x, y, power, mode, "");
    }
}
