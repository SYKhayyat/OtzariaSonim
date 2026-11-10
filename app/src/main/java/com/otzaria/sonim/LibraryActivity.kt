package com.otzaria.sonim

import android.Manifest
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import java.io.File

/** Browse the אוצריא folder tree. D-pad up/down, center = open, back = up a level. */
class LibraryActivity : OtzActivity() {

    private lateinit var header: TextView
    private lateinit var list: ListView
    private var currentDir: File = File("/")
    private var entries: List<Library.Entry> = emptyList()

    /**
     * Whether the library was readable when it was last listed.
     *
     * Back used to ask "is the library still there?" by listing it — 6,618 names off
     * the SD card, on the UI thread, on every Back press. The answer is known: it
     * was read when the folder was opened.
     */
    private var libraryReady = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Otzaria.loadRoot(this)

        val rootView = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        header = TextView(this).apply {
            textSize = 16f
            setPadding(24, 16, 24, 16)
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#33691E"))
            gravity = Gravity.RIGHT
            textDirection = View.TEXT_DIRECTION_RTL
        }
        list = Ui.list(this, "ספרייה")
        rootView.addView(
            header,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        )
        rootView.addView(
            list,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        )
        setContentView(rootView)

        list.setOnItemClickListener { _, _, pos, _ -> open(entries[pos].file) }

        if (checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE)
            != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE), 1)
        } else {
            start()
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        // grantResults was previously ignored and start() ran either way, so denying
        // the permission produced a blank screen with no message at all.
        val granted = grantResults.isNotEmpty() &&
            grantResults[0] == PackageManager.PERMISSION_GRANTED
        if (granted) start() else denied()
    }

    private fun denied() {
        header.text = "אין הרשאה"
        list.adapter = RowAdapter(
            this,
            listOf(
                "האפליקציה צריכה הרשאת קריאה כדי לפתוח את הספרייה.",
                "",
                "הרשאות ← אחסון ← אפשר,",
                "או לחצו על מקש התפריט כדי לנסות שוב."
            ),
            18f
        )
        list.requestFocus()
    }

    private fun start() {
        // The אוצריא folder holds every book on the phone — 6,618 of them on an SD
        // card — and reading its names is disk I/O. It is read in the background.
        showDir(Otzaria.textsDir) { listing ->
            when (listing.state) {
                // the folder is there but unlistable — that is a denied permission,
                // not a wrong path, and asking for a path again would never fix it
                Library.State.UNREADABLE -> denied()
                Library.State.MISSING -> promptForRoot()
                Library.State.READY -> {
                    libraryReady = true
                    showEntries(listing.entries)
                }
            }
        }
    }

    /**
     * List [dir] off the main thread and show it.
     *
     * `File.listFiles()` on the אוצריא folder reads 6,618 names off the SD card. It
     * used to happen on the UI thread, in `showDir`, and the folder was listed
     * *again* on every Back press, because "is the library still there?" was
     * answered by listing it.
     */
    private fun showDir(dir: File, deliver: (Library.Listing) -> Unit = {}) {
        currentDir = dir
        header.text = "${dir.name} — טוען…"
        Library.Loader(
            list = { Library.listing(it) },
            post = { action -> runOnUiThread { action() } }
        ).load(dir) { listing ->
            if (isFinishing) return@load
            deliver(listing)
        }
    }

    /** Draw the rows of a folder that was listed. */
    private fun showEntries(entries: List<Library.Entry>) {
        this.entries = entries
        header.text = "${currentDir.name}   MENU הערות · # עזרה"
        val labels: List<CharSequence> = entries.map { it.label }
        list.adapter = RowAdapter(
            this,
            labels,
            18f,
            describe = { i -> entries[i].spoken }
        )
        list.requestFocus()
        if (entries.isNotEmpty()) list.setSelection(0)
    }

    private fun open(f: File) {
        if (f.isDirectory) {
            currentDir = f
            showDir(f) { listing ->
                when (listing.state) {
                    Library.State.UNREADABLE -> denied()
                    Library.State.MISSING -> showEntries(emptyList())
                    Library.State.READY -> showEntries(listing.entries)
                }
            }
        } else {
            startActivity(
                Intent(this, ReaderActivity::class.java).putExtra("path", f.absolutePath)
            )
        }
    }

    override fun onBackPressed() {
        val parent = currentDir.parentFile
        if (libraryReady &&
            currentDir.absolutePath != Otzaria.textsDir.absolutePath &&
            parent != null
        ) {
            currentDir = parent
            showDir(parent) { listing ->
                if (listing.state == Library.State.READY) showEntries(listing.entries)
                else showEntries(emptyList())
            }
        } else {
            super.onBackPressed()
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_POUND -> {
                startActivity(Intent(this, HelpActivity::class.java)); return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    /** Held MENU: set the library folder — the one thing that used to live on MENU. */
    override fun onMenuHold() {
        promptForRoot()
    }

    private fun promptForRoot() {
        val input = EditText(this).apply { setText(Otzaria.root) }
        AlertDialog.Builder(this)
            .setTitle("תיקיית הספרייה")
            .setMessage("נתיב לתיקייה שמכילה את אוצריא ואת idx")
            .setView(input)
            .setPositiveButton("שמור") { _, _ ->
                Otzaria.saveRoot(this, input.text.toString())
                // Whether the new path holds a library is the same question, and the
                // same listing — so it is asked in the background too.
                showDir(Otzaria.textsDir) { listing ->
                    when (listing.state) {
                        Library.State.READY -> {
                            libraryReady = true
                            currentDir = Otzaria.textsDir
                            showEntries(listing.entries)
                        }
                        else -> Toast.makeText(
                            this, "לא נמצא: " + Otzaria.textsDir.path, Toast.LENGTH_LONG
                        ).show()
                    }
                }
            }
            .setNegativeButton("ביטול", null)
            .show()
    }
}
