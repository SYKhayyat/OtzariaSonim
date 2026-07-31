package com.otzaria.sonim

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * The .idx contract, checked two ways.
 *
 * The sidecar is written by Python (tools/pack_library.py) and read by Kotlin. That
 * is exactly the seam where an endianness or offset mistake survives review and then
 * shows the wrong commentary on the wrong pasuk — quietly, because a wrong ref reads
 * as plausibly as a right one.
 *
 * So: [readsAnIndependentlyEncodedSidecar] builds a file from the format spec here in
 * Kotlin, with no help from the packer, and reads it back. If the reader and the
 * writer ever drift apart in the same direction, this still catches it.
 * [readsTheRealPackedSidecar] then runs against a fixture the Python packer actually
 * produced, which is what pins the two languages to one format.
 */
class BookIndexTest {

    // ---------------------------------------------------------------- helpers

    /** A minimal encoder written from the format spec, not from the packer. */
    private class Builder(val lines: Int, val commentators: List<Pair<String, String>>) {
        // line -> list of (commentator index, target line, ref)
        val entries = LinkedHashMap<Int, MutableList<Triple<Int, Int, String>>>()

        fun add(line: Int, comm: Int, targetLine: Int, ref: String) = apply {
            entries.getOrPut(line) { mutableListOf() }.add(Triple(comm, targetLine, ref))
        }

        fun build(): ByteArray {
            fun be16(v: Int) = byteArrayOf((v ushr 8).toByte(), v.toByte())
            fun be32(v: Int) = byteArrayOf(
                (v ushr 24).toByte(), (v ushr 16).toByte(), (v ushr 8).toByte(), v.toByte()
            )

            val ctable = ByteArrayOutputStream()
            for ((name, path) in commentators) {
                val n = name.toByteArray(Charsets.UTF_8)
                val p = path.toByteArray(Charsets.UTF_8)
                ctable.write(be16(n.size)); ctable.write(n)
                ctable.write(be16(p.size)); ctable.write(p)
            }

            val stride = (lines + 7) / 8
            val marks = ByteArray(commentators.size * stride)
            val refs = ByteArrayOutputStream()
            val refAt = HashMap<String, Int>()
            val ents = ByteArrayOutputStream()
            val dir = ByteArrayOutputStream()

            for (line in 1..lines) {
                val rows = entries[line]
                if (rows == null) { dir.write(be32(0)); dir.write(be16(0)); continue }
                dir.write(be32(ents.size() / 10)); dir.write(be16(rows.size))
                for ((ci, tl, ref) in rows) {
                    marks[ci * stride + (line - 1) / 8] =
                        (marks[ci * stride + (line - 1) / 8].toInt() or (1 shl ((line - 1) % 8))).toByte()
                    val off = refAt.getOrPut(ref) {
                        val at = refs.size()
                        val rb = ref.toByteArray(Charsets.UTF_8)
                        refs.write(be16(rb.size)); refs.write(rb)
                        at
                    }
                    ents.write(be16(ci)); ents.write(be32(tl)); ents.write(be32(off))
                }
            }

            // 4 magic + 1 ver + 4 lines + 2 nComm + 5×4 offsets + 4 nEntries
            val headerSize = 35
            val offC = headerSize
            val offM = offC + ctable.size()
            val offD = offM + marks.size
            val offE = offD + dir.size()
            val offR = offE + ents.size()

            val out = ByteArrayOutputStream()
            out.write("OZSI".toByteArray(Charsets.US_ASCII))
            out.write(byteArrayOf(1))
            out.write(be32(lines)); out.write(be16(commentators.size))
            out.write(be32(offC)); out.write(be32(offM)); out.write(be32(offD))
            out.write(be32(offE)); out.write(be32(offR))
            out.write(be32(ents.size() / 10))
            out.write(ctable.toByteArray()); out.write(marks)
            out.write(dir.toByteArray()); out.write(ents.toByteArray()); out.write(refs.toByteArray())
            return out.toByteArray()
        }
    }

    private fun tempIdx(bytes: ByteArray): File =
        File.createTempFile("test", ".idx").apply { deleteOnExit(); writeBytes(bytes) }

    private fun fixture(): File {
        val url = javaClass.getResource("/mishnah-berakhot.idx")
            ?: error("fixture missing — regenerate with tools/pack_library.py")
        return File(url.toURI())
    }

    // ---------------------------------------------------------------- tests

    @Test
    fun readsAnIndependentlyEncodedSidecar() {
        val bytes = Builder(
            lines = 20,
            commentators = listOf("רש\"י" to "אוצריא/א/רשי.txt", "תוספות" to "אוצריא/א/תוספות.txt")
        )
            .add(3, 0, 5, "רש\"י על ג")
            .add(3, 1, 9, "תוספות על ג")
            .add(17, 1, 42, "תוספות על יז")
            .build()

        val ix = Otzaria.BookIndex.open(tempIdx(bytes))!!
        assertEquals(20, ix.lineCount)
        assertEquals(listOf("רש\"י", "תוספות"), ix.names)

        // the diamond query, unfiltered and filtered
        assertEquals(setOf(3, 17), ix.markedLines(null))
        assertEquals(setOf(3), ix.markedLines(setOf("רש\"י")))
        assertEquals(setOf(3, 17), ix.markedLines(setOf("רש\"י", "תוספות")))
        assertEquals(emptySet<Int>(), ix.markedLines(setOf("מהרש\"א")))

        // the seek query
        val onThree = ix.on(3)
        assertEquals(2, onThree.size)
        assertEquals("רש\"י", onThree[0].commentator)
        assertEquals(5, onThree[0].targetLine)
        assertEquals("רש\"י על ג", onThree[0].ref)
        assertEquals("אוצריא/א/תוספות.txt", onThree[1].relPath)
        assertEquals(42, ix.on(17).single().targetLine)

        // lines with nothing on them, and both sides of the range
        assertEquals(emptyList<Otzaria.LinkRef>(), ix.on(4))
        assertEquals(emptyList<Otzaria.LinkRef>(), ix.on(0))
        assertEquals(emptyList<Otzaria.LinkRef>(), ix.on(21))
    }

    /** Marks and the line directory must never disagree — one draws the ◆, the
     *  other answers OK, and a ◆ that opens to nothing is the defect this replaced. */
    @Test
    fun marksAndDirectoryAgree() {
        val ix = Otzaria.BookIndex.open(fixture())!!
        val marked = ix.markedLines(null)
        val viaSeek = (1..ix.lineCount).filter { ix.on(it).isNotEmpty() }.toSet()
        assertEquals(viaSeek, marked)
    }

    /** SPEC.md's verified lookup, through the packed format: Mishnah Berakhot
     *  line 3 carries the Rambam, pointing at line 5 of the Rambam's own file. */
    @Test
    fun readsTheRealPackedSidecar() {
        val ix = Otzaria.BookIndex.open(fixture())!!
        assertEquals(67, ix.lineCount)
        assertTrue("expected the Rambam in the commentator table", ix.names.any { it.contains("רמבם") })

        // the Rambam comments several times on this mishnah, so this is `first`,
        // not `single` -- an earlier version asserted `single` and failed, which is
        // the format telling the truth about the data
        val onThree = ix.on(3).filter { it.commentator.contains("רמבם") }
        assertTrue("expected at least one Rambam entry on line 3", onThree.isNotEmpty())
        val rambam = onThree.first()
        assertEquals(5, rambam.targetLine)
        assertTrue(rambam.relPath.endsWith("רמבם על משנה ברכות.txt"))
        assertTrue(rambam.ref.contains("משנה ברכות"))

        // headings carry no commentary; line 3 is the first mishnah
        assertTrue(ix.markedLines(null).contains(3))
        assertTrue(!ix.markedLines(null).contains(1))
    }

    /** A missing or corrupt sidecar means "no meforshim", never a crash. The old
     *  loadLinks caught Exception, which does not catch OutOfMemoryError — that is
     *  how a 72 MB links file became a crash dialog instead of a book. */
    @Test
    fun refusesGarbageQuietly() {
        assertNull(Otzaria.BookIndex.open(File("no-such-file.idx")))
        assertNull(Otzaria.BookIndex.open(tempIdx(ByteArray(4))))
        assertNull(Otzaria.BookIndex.open(tempIdx("NOPE".toByteArray() + ByteArray(60))))
        // right magic, wrong version
        val bad = tempIdx("OZSI".toByteArray() + byteArrayOf(99) + ByteArray(60))
        assertNull(Otzaria.BookIndex.open(bad))
    }
}
