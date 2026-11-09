package com.otzaria.sonim

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * MENU is two keys now — a tap opens הערות from anywhere, a hold runs the action the
 * screen used to put on MENU. That split is decided by how long the key is held, so
 * it can be pinned here, on a fake clock, instead of by watching a phone.
 */
class MenuKeyTest {

    private var clock = 1_000L
    private val log = ArrayList<String>()
    private val key = MenuKey(
        now = { clock },
        onTap = { log.add("tap") },
        onHold = { log.add("hold") }
    )

    private fun press(ms: Long) {
        assertTrue("MENU down must be consumed", key.down(MenuKey.KEYCODE))
        clock += ms
        assertTrue("MENU up must be consumed", key.up(MenuKey.KEYCODE))
    }

    @Test
    fun aQuickPressOpensTheStack() {
        press(80)
        assertEquals(listOf("tap"), log)
    }

    @Test
    fun aLongPressRunsTheScreensOwnActionAndNothingElse() {
        press(MenuKey.HOLD_MS + 250)
        assertEquals("a hold must not also open הערות", listOf("hold"), log)
    }

    @Test
    fun theThresholdIsInclusive() {
        press(MenuKey.HOLD_MS)
        assertEquals(listOf("hold"), log)
    }

    @Test
    fun otherKeysAreNotConsumed() {
        assertFalse(key.down(KeyEventCodes.DPAD_LEFT))
        assertFalse(key.up(KeyEventCodes.DPAD_LEFT))
        assertEquals(emptyList<String>(), log)
    }

    /**
     * Auto-repeat must not restart the clock (on the oldest phones it is 50 ms) — and
     * once the threshold passes it fires the hold, so the gesture does not depend on a
     * release event this keypad may not send.
     */
    @Test
    fun autoRepeatFiresTheHoldOnceWhenItFallsDue() {
        key.down(MenuKey.KEYCODE)
        for (i in 1..12) {
            clock += 50
            assertTrue(key.down(MenuKey.KEYCODE, repeatCount = i))
        }
        assertEquals("fired mid-press, at the threshold", listOf("hold"), log)
        key.up(MenuKey.KEYCODE)
        assertEquals("the release must not fire it again", listOf("hold"), log)
    }

    /** A tap, then a hold: each press is decided on its own. */
    @Test
    fun eachPressIsDecidedOnItsOwn() {
        press(50)
        press(MenuKey.HOLD_MS + 100)
        press(50)
        assertEquals(listOf("tap", "hold", "tap"), log)
    }

    /** A release with no press before it (the press belonged to another screen). */
    @Test
    fun aReleaseWithNoPressBeforeItDoesNothing() {
        key.up(MenuKey.KEYCODE)
        assertEquals(emptyList<String>(), log)
    }

    /** Leaving the screen mid-press must not leave a clock running. */
    @Test
    fun cancelForgetsThePressInFlight() {
        key.down(MenuKey.KEYCODE)
        clock += MenuKey.HOLD_MS * 4
        key.cancel()
        key.up(MenuKey.KEYCODE)
        assertEquals("a cancelled press must not fire anything on release", emptyList<String>(), log)
    }

    /**
     * If a release is ever lost, the next press must still work: a fresh down
     * (repeatCount 0) starts a new press rather than waiting for a release that is
     * never coming.
     */
    @Test
    fun aLostReleaseDoesNotLeaveTheKeyDead() {
        key.down(MenuKey.KEYCODE)                 // pressed, release lost
        clock += MenuKey.HOLD_MS * 3
        key.down(MenuKey.KEYCODE)                 // a new press, not a repeat
        clock += 40
        key.up(MenuKey.KEYCODE)
        assertEquals(listOf("tap"), log)
    }

    /** The key code is KeyEvent.KEYCODE_MENU; the splitter must not be re-pointed. */
    @Test
    fun theKeyIsMenu() {
        assertEquals(82, MenuKey.KEYCODE)
    }

    /** Only the numbers this test needs, so the test does not lean on android.jar. */
    private object KeyEventCodes {
        const val DPAD_LEFT = 21
    }
}
