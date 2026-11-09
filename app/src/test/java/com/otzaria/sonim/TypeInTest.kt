package com.otzaria.sonim

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The digits typed ahead of a jump or a counted move, and the maths that turns them
 * into a row.
 *
 * Two things are being pinned. First, that a mistyped digit is *erasable* — that is
 * the difference between "type 20, notice it said 2, start over" and a reader who
 * stops trusting the number entry. Second, that a count can never scroll off either
 * end of the sefer, on any list size including an empty one.
 */
class TypeInTest {

    // ------------------------------------------------------------ what was typed

    @Test
    fun digitsAccumulateInOrder() {
        val t = TypeIn()
        assertEquals(2, t.append(2))
        assertEquals(20, t.append(0))
        assertEquals(205, t.append(5))
        assertEquals(205, t.value)
        assertFalse(t.isEmpty)
    }

    @Test
    fun theCapHoldsAboveAndTheCapIsWhatIsShown() {
        val t = TypeIn(cap = 100)
        assertEquals(9, t.append(9))
        assertEquals(99, t.append(9))
        assertEquals("200 in a percent field means as far as it goes", 100, t.append(2))
        assertEquals(100, t.append(0))
    }

    // ------------------------------------------------------------ erasing it

    @Test
    fun backspaceErasesOneDigit() {
        val t = TypeIn()
        t.append(1)
        t.append(2)
        assertTrue(t.backspace())
        assertEquals(1, t.value)
    }

    @Test
    fun erasingTheLastDigitEndsTheEntry() {
        val t = TypeIn()
        t.append(7)
        assertTrue(t.backspace())
        assertTrue(t.isEmpty)
        assertEquals(0, t.value)
    }

    @Test
    fun backspaceOnNothingChangesNothing() {
        val t = TypeIn()
        assertFalse("nothing to erase", t.backspace())
        assertTrue(t.isEmpty)
    }

    /** The point of the key: a typo is fixed without starting over. */
    @Test
    fun aMistypedDigitIsFixableInPlace() {
        val t = TypeIn()
        t.append(1); t.append(2)   // meant 15
        t.backspace()
        assertEquals(15, t.append(5))
    }

    @Test
    fun clearDropsEverything() {
        val t = TypeIn()
        t.append(4); t.append(2)
        t.clear()
        assertTrue(t.isEmpty)
        assertEquals(0, t.value)
    }

    @Test
    fun leadingZeroIsJustZero() {
        val t = TypeIn()
        assertEquals(0, t.append(0))
        assertEquals(5, t.append(5))
    }

    // ------------------------------------------------------------ where it lands

    @Test
    fun percentZeroIsTheStartAndAHundredIsTheEnd() {
        val size = 1_000
        assertEquals(0, Jump.percent(0, size))
        assertEquals(size - 1, Jump.percent(100, size))
        assertEquals(size / 2 - 1, Jump.percent(50, size))
    }

    @Test
    fun percentIsClampedAndSafeOnTinyAndEmptyLists() {
        assertEquals(0, Jump.percent(0, 1))
        assertEquals(0, Jump.percent(100, 1))
        assertEquals(0, Jump.percent(50, 0))
        // Over 100 (the reader now accepts three digits) still means the end.
        assertEquals(9, Jump.percent(250, 10))
    }

    @Test
    fun aCountMovesBothWays() {
        assertEquals(30, Jump.by(10, +20, 100))
        assertEquals(0, Jump.by(10, -20, 100))
    }

    @Test
    fun aCountStopsAtBothEndsInsteadOfWrapping() {
        assertEquals(99, Jump.by(90, +500, 100))
        assertEquals(0, Jump.by(5, -500, 100))
    }

    @Test
    fun zeroCountStaysPut() {
        assertEquals(42, Jump.by(42, 0, 100))
    }

    @Test
    fun anEmptyListHasNowhereToGo() {
        assertEquals(0, Jump.by(0, 10, 0))
        assertEquals(0, Jump.by(-3, -10, 0))
    }
}
