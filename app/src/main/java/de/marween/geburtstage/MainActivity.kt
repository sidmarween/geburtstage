package de.marween.geburtstage

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.app.DatePickerDialog
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.NumberPicker
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.time.LocalDate

fun Context.dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

class MainActivity : Activity() {

    private lateinit var header: TextView
    private lateinit var listView: ListView
    private lateinit var emptyView: TextView
    private val adapter = BirthdayAdapter()
    private var items: List<Birthday> = emptyList()
    private var daysBefore = 7

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "Geburtstage"

        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        header = TextView(this).apply {
            setPadding(dp(16), dp(12), dp(16), dp(4))
            textSize = 14f
            alpha = 0.75f
        }
        emptyView = TextView(this).apply {
            text = "Noch keine Geburtstage.\n\nTippe unten auf „Geburtstag hinzufügen“ " +
                "oder erlaube den Zugriff auf Kontakte und Kalender – dann werden " +
                "die Geburtstage automatisch übernommen."
            gravity = Gravity.CENTER
            textSize = 15f
            alpha = 0.7f
            setPadding(dp(32), dp(32), dp(32), dp(32))
            visibility = View.GONE
        }
        listView = ListView(this).apply {
            divider = null
            setAdapter(this@MainActivity.adapter)
        }
        val addButton = Button(this).apply {
            text = "+  Geburtstag hinzufügen"
            setOnClickListener { showEditDialog(null) }
        }
        val footer = TextView(this).apply {
            text = "© marween"
            textSize = 11f
            alpha = 0.5f
            gravity = Gravity.CENTER
            setPadding(0, dp(2), 0, dp(8))
        }

        val logo = android.widget.ImageView(this).apply {
            setImageResource(R.drawable.logo)
            adjustViewBounds = true
            scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
            contentDescription = "Wer hat hier Geburtstag???"
            setPadding(dp(24), dp(12), dp(24), 0)
        }
        root.addView(logo, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(110)))
        root.addView(header, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        root.addView(emptyView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        root.addView(listView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        root.addView(
            addButton,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                setMargins(dp(16), dp(4), dp(16), dp(4))
            }
        )
        root.addView(footer, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        setContentView(root)

        listView.setOnItemClickListener { _, _, pos, _ -> showActions(items[pos]) }
        listView.setOnItemLongClickListener { _, _, pos, _ ->
            toggleMute(items[pos])
            true
        }

        Reminders.ensureChannel(this)
        Reminders.schedule(this)
        requestNeededPermissions()
        runSync(false)
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    // ---------- Berechtigungen ----------

    private fun requestNeededPermissions() {
        val perms = mutableListOf(
            Manifest.permission.READ_CONTACTS,
            Manifest.permission.READ_CALENDAR,
            Manifest.permission.WRITE_CALENDAR
        )
        if (Build.VERSION.SDK_INT >= 33) perms.add(Manifest.permission.POST_NOTIFICATIONS)
        val missing = perms.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) requestPermissions(missing.toTypedArray(), 42)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 42) runSync(true)
    }

    // ---------- Daten ----------

    private fun refresh() {
        val app = applicationContext
        SyncEngine.submit {
            val store = Store(app)
            val today = LocalDate.now()
            val sorted = store.load().sortedWith(
                compareBy<Birthday> { it.daysUntil(today) }.thenBy { it.name.lowercase() }
            )
            val before = store.daysBefore
            runOnUiThread {
                items = sorted
                daysBefore = before
                adapter.notifyDataSetChanged()
                updateHeader()
            }
        }
    }

    private fun runSync(showToast: Boolean) {
        val app = applicationContext
        SyncEngine.submit {
            val msg = SyncEngine.syncNow(app)
            Reminders.check(app)
            runOnUiThread {
                if (showToast) toast(msg)
                refresh()
            }
        }
    }

    private fun updateHeader() {
        val empty = items.isEmpty()
        emptyView.visibility = if (empty) View.VISIBLE else View.GONE
        listView.visibility = if (empty) View.GONE else View.VISIBLE
        if (empty) {
            header.text = ""
            return
        }
        val next = items.firstOrNull { !it.muted } ?: items.first()
        val d = next.daysUntil()
        header.text = "${items.size} Geburtstage · als Nächstes: ${next.name} ${Fmt.inDays(d)}"
    }

    // ---------- Aktionen ----------

    private fun showActions(b: Birthday) {
        val muteLabel = if (b.muted) "🔔  Erinnerungen wieder an" else "🔕  Stummschalten"
        val options = arrayOf<CharSequence>(muteLabel, "✏️  Bearbeiten", "🗑️  Löschen")
        AlertDialog.Builder(this)
            .setTitle(b.name)
            .setItems(options) { _, which ->
                when (which) {
                    0 -> toggleMute(b)
                    1 -> showEditDialog(b)
                    2 -> confirmDelete(b)
                }
            }
            .show()
    }

    private fun toggleMute(b: Birthday) {
        val app = applicationContext
        val newState = !b.muted
        SyncEngine.submit {
            SyncEngine.setMuted(app, b.id, newState)
            runOnUiThread {
                toast(if (newState) "${b.name}: stummgeschaltet 🔕" else "${b.name}: Erinnerungen an 🔔")
                refresh()
            }
        }
    }

    private fun confirmDelete(b: Birthday) {
        val extra = if (b.source != SOURCE_MANUAL) "\n\nDer Kontakt bzw. Kalendereintrag selbst bleibt unverändert." else ""
        AlertDialog.Builder(this)
            .setTitle("Löschen?")
            .setMessage("${b.name} wirklich aus der Liste löschen?$extra")
            .setPositiveButton("Löschen") { _, _ ->
                val app = applicationContext
                SyncEngine.submit {
                    SyncEngine.delete(app, b.id)
                    runOnUiThread { refresh() }
                }
            }
            .setNegativeButton("Abbrechen", null)
            .show()
    }

    private fun showEditDialog(existing: Birthday?) {
        val today = LocalDate.now()
        var month = existing?.month ?: today.monthValue
        var day = existing?.day ?: today.dayOfMonth
        var year: Int? = existing?.year
        var picked = existing != null

        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(8), dp(24), 0)
        }
        val nameField = EditText(this).apply {
            hint = "Name"
            setText(existing?.name ?: "")
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS
            isSingleLine = true
        }
        val dateButton = Button(this)
        val noYear = CheckBox(this).apply {
            text = "Geburtsjahr unbekannt"
            isChecked = existing != null && existing.year == null
        }

        fun updateDateText() {
            dateButton.text = if (!picked) "📅  Datum wählen"
            else "📅  " + Fmt.date(month, day, if (noYear.isChecked) null else year)
        }
        updateDateText()

        dateButton.setOnClickListener {
            DatePickerDialog(
                this,
                { _, y, m0, d ->
                    year = y
                    month = m0 + 1
                    day = d
                    picked = true
                    updateDateText()
                },
                year ?: 1990, month - 1, day
            ).show()
        }
        noYear.setOnCheckedChangeListener { _, _ -> updateDateText() }

        box.addView(nameField)
        box.addView(dateButton)
        box.addView(noYear)

        val dialog = AlertDialog.Builder(this)
            .setTitle(if (existing == null) "Neuer Geburtstag" else "Geburtstag bearbeiten")
            .setView(box)
            .setPositiveButton("Speichern", null)
            .setNegativeButton("Abbrechen", null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val name = nameField.text.toString().trim()
                if (name.isEmpty()) {
                    toast("Bitte einen Namen eingeben")
                    return@setOnClickListener
                }
                if (!picked) {
                    toast("Bitte ein Datum wählen")
                    return@setOnClickListener
                }
                val finalYear = if (noYear.isChecked) null else year
                val m = month
                val d = day
                val app = applicationContext
                SyncEngine.submit {
                    SyncEngine.mutate(app) { list, store ->
                        if (existing == null) {
                            list.add(Birthday(name = name, month = m, day = d, year = finalYear))
                        } else {
                            val e = list.firstOrNull { it.id == existing.id }
                            if (e != null) {
                                // Bearbeitete Kontakt-/Kalender-Einträge werden zu eigenen Einträgen
                                if (e.source != SOURCE_MANUAL) {
                                    e.sourceKey?.let { key -> store.ignored = store.ignored + key }
                                    e.source = SOURCE_MANUAL
                                    e.sourceKey = null
                                    e.inForeignCalendar = false
                                }
                                e.name = name
                                e.month = m
                                e.day = d
                                e.year = finalYear
                            }
                        }
                    }
                    Reminders.check(app)
                    runOnUiThread { refresh() }
                }
                dialog.dismiss()
            }
        }
        dialog.show()
    }

    // ---------- Menü ----------

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menu.add(0, 1, 0, "Jetzt synchronisieren")
        menu.add(0, 2, 0, "Kalender wählen")
        menu.add(0, 3, 0, "Erinnerungen einstellen")
        menu.add(0, 4, 0, "Über")
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            1 -> {
                requestNeededPermissions()
                runSync(true)
            }
            2 -> chooseCalendar()
            3 -> showSettings()
            4 -> showAbout()
            else -> return super.onOptionsItemSelected(item)
        }
        return true
    }

    private fun chooseCalendar() {
        if (!CalendarSync.hasWrite(this)) {
            toast("Bitte erst den Zugriff auf den Kalender erlauben")
            requestNeededPermissions()
            return
        }
        val app = applicationContext
        SyncEngine.submit {
            val cals = CalendarSync.writable(app)
            val current = Store(app).calendarId
            runOnUiThread {
                val labels = ArrayList<CharSequence>()
                labels.add("Nicht in den Kalender eintragen")
                cals.forEach { labels.add(it.label) }
                val idx = cals.indexOfFirst { it.id == current }
                val checked = if (idx < 0) 0 else idx + 1
                AlertDialog.Builder(this)
                    .setTitle("In welchen Kalender eintragen?")
                    .setSingleChoiceItems(labels.toTypedArray(), checked) { d, which ->
                        d.dismiss()
                        val newId = if (which == 0) -1L else cals[which - 1].id
                        SyncEngine.submit {
                            val s = Store(app)
                            s.calendarId = newId
                            s.calendarChosen = true
                            SyncEngine.mutate(app) { _, _ -> }
                            runOnUiThread {
                                toast(if (newId < 0) "Kalender-Sync aus" else "Kalender gespeichert ✓")
                                refresh()
                            }
                        }
                    }
                    .setNegativeButton("Abbrechen", null)
                    .show()
            }
        }
    }

    private fun showSettings() {
        val s = Store(this)
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(8), dp(24), 0)
        }
        fun label(t: String) = TextView(this).apply {
            text = t
            textSize = 15f
            setPadding(0, dp(12), 0, dp(4))
        }

        box.addView(label("Wie viele Tage vorher erinnern?"))
        val daysPicker = NumberPicker(this).apply {
            minValue = 1
            maxValue = 30
            value = s.daysBefore.coerceIn(1, 30)
        }
        box.addView(daysPicker)

        box.addView(label("Um welche Uhrzeit?"))
        val hourPicker = NumberPicker(this).apply {
            minValue = 0
            maxValue = 23
            displayedValues = Array(24) { "%02d:00".format(it) }
            value = s.hour.coerceIn(0, 23)
        }
        box.addView(hourPicker)

        val onDay = CheckBox(this).apply {
            text = "Auch am Geburtstag selbst erinnern"
            isChecked = s.notifyOnDay
        }
        box.addView(onDay)

        val contactsToCal = CheckBox(this).apply {
            text = "Geburtstage aus Kontakten auch in den Kalender eintragen"
            isChecked = s.contactsToCalendar
        }
        box.addView(contactsToCal)
        box.addView(TextView(this).apply {
            text = "Tipp: Google zeigt Geburtstage aus deinen Google-Kontakten oft schon selbst im Kalender an. " +
                "Dann lass das aus, sonst stehen sie doppelt drin. Selbst eingetragene Geburtstage kommen immer in den Kalender."
            textSize = 12f
            alpha = 0.65f
            setPadding(0, dp(4), 0, dp(8))
        })

        val scroll = ScrollView(this).apply { addView(box) }
        AlertDialog.Builder(this)
            .setTitle("Erinnerungen")
            .setView(scroll)
            .setPositiveButton("Speichern") { _, _ ->
                s.daysBefore = daysPicker.value
                s.hour = hourPicker.value
                s.notifyOnDay = onDay.isChecked
                s.contactsToCalendar = contactsToCal.isChecked
                Reminders.schedule(this)
                runSync(false)
                toast("Gespeichert ✓")
            }
            .setNegativeButton("Abbrechen", null)
            .show()
    }

    private fun showAbout() {
        val version = try {
            packageManager.getPackageInfo(packageName, 0).versionName
        } catch (e: Exception) {
            "?"
        }
        AlertDialog.Builder(this)
            .setTitle("Geburtstage")
            .setMessage(
                "Version $version\n\n" +
                    "• Geburtstage werden aus Kontakten und Kalender übernommen\n" +
                    "• Eigene Einträge landen in deinem Google-Kalender\n" +
                    "• Tippen = Optionen, lange drücken = stummschalten\n\n" +
                    "© marween"
            )
            .setPositiveButton("OK", null)
            .show()
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()

    // ---------- Liste ----------

    private inner class BirthdayAdapter : BaseAdapter() {
        override fun getCount(): Int = items.size
        override fun getItem(position: Int): Any = items[position]
        override fun getItemId(position: Int): Long = position.toLong()
        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val row = convertView as? BirthdayRow ?: BirthdayRow(this@MainActivity)
            row.bind(items[position], LocalDate.now(), daysBefore)
            return row
        }
    }
}

/** Eine Zeile in der Liste. */
class BirthdayRow(ctx: Context) : LinearLayout(ctx) {
    private val badge = TextView(ctx)
    private val nameView = TextView(ctx)
    private val subView = TextView(ctx)
    private val iconView = TextView(ctx)
    private val badgeBg = GradientDrawable().apply { shape = GradientDrawable.OVAL }

    init {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(ctx.dp(16), ctx.dp(10), ctx.dp(16), ctx.dp(10))

        badge.gravity = Gravity.CENTER
        badge.textSize = 12f
        badge.setTypeface(badge.typeface, Typeface.BOLD)
        badge.background = badgeBg
        addView(badge, LinearLayout.LayoutParams(ctx.dp(52), ctx.dp(52)))

        val mid = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(ctx.dp(14), 0, ctx.dp(8), 0)
        }
        nameView.textSize = 17f
        nameView.setTypeface(nameView.typeface, Typeface.BOLD)
        subView.textSize = 13f
        subView.alpha = 0.7f
        mid.addView(nameView)
        mid.addView(subView)
        addView(mid, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        iconView.textSize = 18f
        addView(iconView)
    }

    fun bind(b: Birthday, today: LocalDate, daysBefore: Int) {
        val d = b.daysUntil(today)
        when {
            d == 0 -> {
                badge.text = "🎂\nHeute"
                badgeBg.setColor(Color.parseColor("#E91E63"))
                badge.setTextColor(Color.WHITE)
            }
            d <= daysBefore -> {
                badge.text = if (d == 1) "1\nTag" else "$d\nTage"
                badgeBg.setColor(Color.parseColor("#FF9800"))
                badge.setTextColor(Color.WHITE)
            }
            else -> {
                badge.text = "$d\nTage"
                badgeBg.setColor(Color.parseColor("#33888888"))
                badge.setTextColor(nameView.currentTextColor)
            }
        }
        if (b.muted) badgeBg.setColor(Color.parseColor("#22888888"))

        nameView.text = b.name
        nameView.alpha = if (b.muted) 0.5f else 1f

        val age = b.turningAge(today)
        val parts = mutableListOf(Fmt.date(b.month, b.day, null))
        if (age != null) parts.add("wird $age")
        if (b.muted) parts.add("stumm")
        subView.text = parts.joinToString(" · ")

        val source = when (b.source) {
            SOURCE_CONTACT -> "📇"
            SOURCE_CALENDAR -> "📅"
            else -> ""
        }
        iconView.text = (if (b.muted) "🔕 " else "") + source
    }
}
