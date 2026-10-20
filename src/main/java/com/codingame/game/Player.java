package com.codingame.game;

import com.codingame.gameengine.core.AbstractMultiplayerPlayer;

public class Player extends AbstractMultiplayerPlayer {
    /** One line per unit still in the ring. */
    private int expectedOutputLines = 1;

    void setExpectedOutputLines(int n) {
        expectedOutputLines = n;
    }

    @Override
    public int getExpectedOutputLines() {
        return expectedOutputLines;
    }
}
