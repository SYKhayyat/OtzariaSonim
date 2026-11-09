package com.otzaria.sonim

import android.view.KeyEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * What each key means while a number is typed ahead — the table that decides whether
 * a digit is erased, a count is walked, or the number is dropped.
 *
 * It exists as a table because of a bug it would otherwise take a handset to find:
 * Back used to clear the pending number and then be passed on to the framework, which
 * took the same press as "leave the sefer". A reader who typed 12, changed their mind
 * and pressed Back was thrown out of the book. [TypedAction.CANCEL] is what lets the
 * screens return on Back instead of forwarding it, and this test is what keeps Back
 * out of [TypedAction.PASS].
 */
class TypedKeysTest {

    @Test
    fun everyDigitIsAnotherDigit() {
        for (k in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9) {
            assertEquals("key $k", TypedAction.DIGIT, TypedKeys.of(k))
        }
    }

    @Test
    fun leftAndDeleteEraseOneDigit() {
        assertEquals(TypedAction.ERASE, TypedKeys.of(KeyEvent.KEYCODE_DPAD_LEFT))
        assertEquals(TypedAction.ERASE, TypedKeys.of(KeyEvent.KEYCODE_DEL))
    }

    @Test
    fun upAndDownWalkTheTypedCount() {
        assertEquals(TypedAction.UP, TypedKeys.of(KeyEvent.KEYCODE_DPAD_UP))
        assertEquals(TypedAction.DOWN, TypedKeys.of(KeyEvent.KEYCODE_DPAD_DOWN))
    }

    /** The regression: Back cancels the number and must not also leave the screen. */
    @Test
    fun backCancelsAndIsNotPassedOn() {
        assertEquals(TypedAction.CANCEL, TypedKeys.of(KeyEvent.KEYCODE_BACK))
        assertNotEquals(TypedAction.PASS, TypedKeys.of(KeyEvent.KEYCODE_BACK))
    }

    /**
     * OK must be forwarded with the number intact: the ListView's click handler is what
     * commits a percent, so cancelling it here would turn "typed 50, pressed OK" into
     * "opened the meforshim panel" — silently, and only when the count was not a
     * scroll.
     */
    @Test
    fun okIsForwardedWithTheNumberStillPending() {
        assertEquals(TypedAction.CONFIRM, TypedKeys.of(KeyEvent.KEYCODE_DPAD_CENTER))
        assertEquals(TypedAction.CONFIRM, TypedKeys.of(KeyEvent.KEYCODE_ENTER))
        assertNotEquals(TypedAction.PASS, TypedKeys.of(KeyEvent.KEYCODE_DPAD_CENTER))
    }

    /** MENU must reach the base class, which opens הערות from everywhere. */
    @Test
    fun menuIsPassedOnSoItStillOpensTheStack() {
        assertEquals(TypedAction.PASS, TypedKeys.of(MenuKey.KEYCODE))
        assertEquals(TypedAction.PASS, TypedKeys.of(KeyEvent.KEYCODE_POUND))
        assertEquals(TypedAction.PASS, TypedKeys.of(KeyEvent.KEYCODE_STAR))
        assertEquals(TypedAction.PASS, TypedKeys.of(KeyEvent.KEYCODE_VOLUME_UP))
        assertEquals(TypedAction.PASS, TypedKeys.of(KeyEvent.KEYCODE_DPAD_RIGHT))
    }

    /** One key, one meaning — a key in two rows would make the order of the table load-bearing. */
    @Test
    fun noTwoKeysShareAnActionTheyShouldNotShare() {
        val keys = listOf(
            KeyEvent.KEYCODE_0, KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_UP,
            KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_MENU
        )
        assertEquals(keys.size, keys.map { TypedKeys.of(it) }.toSet().size)
    }

    /** The numbers in the app are the framework's numbers. */
    @Test
    fun theKeyCodesAreTheFrameworks() {
        assertEquals(KeyEvent.KEYCODE_MENU, MenuKey.KEYCODE)
        assertEquals(4, KeyEvent.KEYCODE_BACK)
        assertEquals(21, KeyEvent.KEYCODE_DPAD_LEFT)
        assertEquals(19, KeyEvent.KEYCODE_DPAD_UP)
        assertEquals(20, KeyEvent.KEYCODE_DPAD_DOWN)
        assertEquals(67, KeyEvent.KEYCODE_DEL)
    }
}
