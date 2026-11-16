package org.carbridge.guard;

import static org.junit.Assert.assertEquals;

import org.carbridge.guard.ParkedState.Status;
import org.junit.Test;

public class ParkedStateTest {

    @Test
    public void startsLockedWithoutAFix() {
        assertEquals(Status.CHECKING, new ParkedState().status(0));
    }

    @Test
    public void unlocksOnlyAfterStayingStillForTheHold() {
        ParkedState s = new ParkedState();
        s.onFix(0, 0f);
        assertEquals(Status.MOVING, s.status(0));
        s.onFix(4_000, 0.3f);
        assertEquals(Status.MOVING, s.status(4_000));
        s.onFix(5_000, 0.2f);
        assertEquals(Status.PARKED, s.status(5_000));
    }

    @Test
    public void locksAtOnceWhenMoving() {
        ParkedState s = parked();
        s.onFix(20_000, 5f);
        assertEquals(Status.MOVING, s.status(20_000));
    }

    @Test
    public void gpsDriftBetweenThresholdsKeepsParked() {
        ParkedState s = parked();
        s.onFix(20_000, 2f);
        assertEquals(Status.PARKED, s.status(20_000));
    }

    @Test
    public void slowCrawlNeverUnlocks() {
        ParkedState s = new ParkedState();
        for (long t = 0; t <= 30_000; t += 1_000) s.onFix(t, 2f);
        assertEquals(Status.MOVING, s.status(30_000));
    }

    @Test
    public void crawlInterruptsAStop() {
        ParkedState s = new ParkedState();
        s.onFix(0, 0f);
        s.onFix(3_000, 2f);
        s.onFix(4_000, 0f);
        s.onFix(8_000, 0f);
        assertEquals(Status.MOVING, s.status(8_000));
        s.onFix(9_000, 0f);
        assertEquals(Status.PARKED, s.status(9_000));
    }

    @Test
    public void staleFixLocksAgain() {
        ParkedState s = parked();
        assertEquals(Status.CHECKING, s.status(10_000 + ParkedState.FIX_MAX_AGE_MS + 1));
    }

    @Test
    public void unknownSpeedCountsAsMoving() {
        ParkedState s = parked();
        s.onFix(11_000, Float.NaN);
        assertEquals(Status.MOVING, s.status(11_000));
    }

    @Test
    public void resetNeedsANewStop() {
        ParkedState s = parked();
        s.reset();
        assertEquals(Status.CHECKING, s.status(10_000));
    }

    @Test
    public void derivedSpeed() {
        assertEquals(10f, ParkedState.derivedSpeed(100, 10_000), 0.001f);
        assertEquals(Float.NaN, ParkedState.derivedSpeed(5, 0), 0f);
    }

    private static ParkedState parked() {
        ParkedState s = new ParkedState();
        for (long t = 0; t <= 10_000; t += 1_000) s.onFix(t, 0f);
        assertEquals(Status.PARKED, s.status(10_000));
        return s;
    }
}
