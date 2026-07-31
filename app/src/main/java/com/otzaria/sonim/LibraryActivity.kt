package com.otzaria.sonim

import android.Manifest
import android.app.Activity
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
class LibraryActivity : Activity() {

    private lateinit var header: TextView
    private lateinit var list: ListView
    private var currentDir: File = File("/")
    private var entries: List<File> = emptyList()

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
        list = ListView(this)
        rootView.addView(
            header,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        )
        rootView.addView(
            list,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        )
        setContentView(rootView)

        list.setOnItemClickListener { _, _, pos, _ -> open(entries[pos]) }

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
        when {
            Otzaria.isReady() -> showDir(Otzaria.textsDir)
            // the folder is there but unlistable — that is a denied permission, not a
            // wrong path, and asking for a path again would never fix it
            Otzaria.isUnreadable() -> denied()
            else -> promptForRoot()
        }
    }

    private fun showDir(dir: File) {
        currentDir = dir
        entries = (dir.listFiles()?.toList() ?: emptyList())
            .filter { it.isDirectory || it.name.endsWith(".txt", ignoreCase = true) }
            .sortedWith(compareBy({ !it.isDirectory }, { it.name }))
        header.text = "${dir.name}   # עזרה"
        val labels: List<CharSequence> = entries.map { f ->
            if (f.isDirectory) "📁  " + f.name else f.name.removeSuffix(".txt")
        }
        list.adapter = RowAdapter(this, labels, 18f)
        list.requestFocus()
        if (entries.isNotEmpty()) list.setSelection(0)
    }

    private fun open(f: File) {
        if (f.isDirectory) {
            showDir(f)
        } else {
            startActivity(
                Intent(this, ReaderActivity::class.java).putExtra("path", f.absolutePath)
            )
        }
    }

    override fun onBackPressed() {
        val parent = currentDir.parentFile
        if (Otzaria.isReady() &&
            currentDir.absolutePath != Otzaria.textsDir.absolutePath &&
            parent != null
        ) {
            showDir(parent)
        } else {
            super.onBackPressed()
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_MENU -> { promptForRoot(); return true }
            KeyEvent.KEYCODE_POUND -> {
                startActivity(Intent(this, HelpActivity::class.java)); return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    private fun promptForRoot() {
        val input = EditText(this).apply { setText(Otzaria.root) }
        AlertDialog.Builder(this)
            .setTitle("תיקיית הספרייה")
            .setMessage("נתיב לתיקייה שמכילה את אוצריא ואת idx")
            .setView(input)
            .setPositiveButton("שמור") { _, _ ->
                Otzaria.saveRoot(this, input.text.toString())
                if (Otzaria.isReady()) {
                    showDir(Otzaria.textsDir)
                } else {
                    Toast.makeText(
                        this, "לא נמצא: " + Otzaria.textsDir.path, Toast.LENGTH_LONG
                    ).show()
                }
            }
            .setNegativeButton("ביטול", null)
            .show()
    }
}
