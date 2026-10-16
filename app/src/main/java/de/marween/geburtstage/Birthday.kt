package de.marween.geburtstage

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.time.Month
import java.time.Year
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale
import java.util.UUID

const val SOURCE_MANUAL = "manual"
const val SOURCE_CONTACT = "contact"
const val SOURCE_CALENDAR = "calendar"

/** Ein Geburtstag. Jahr ist optional (null = unbekannt). */
data class Birthday(
    val id: String = UUID.randomUUID().toString(),
    var name: String,
    var month: Int,
    var day: Int,
    var year: Int? = null,
    var source: String = SOURCE_MANUAL,
    var sourceKey: String? = null,
    var muted: Boolean = false,
    var calendarEventId: Long? = null,
    var calendarSig: String? = null,
    var inForeignCalendar: Boolean = false
) {
    /** Datum in einem bestimmten Jahr (29. Februar wird in Nicht-Schaltjahren zum 28.). */
    fun dateIn(y: Int): LocalDate =
        if (month == 2 && day == 29 && !Year.isLeap(y.toLong())) LocalDate.of(y, 2, 28)
        else LocalDate.of(y, month, day)

    fun nextDate(today: LocalDate = LocalDate.now()): LocalDate {
        val d = dateIn(today.year)
        return if (d.isBefore(today)) dateIn(today.year + 1) else d
    }

    fun daysUntil(today: LocalDate = LocalDate.now()): Int =
        ChronoUnit.DAYS.between(today, nextDate(today)).toInt()

    fun turningAge(today: LocalDate = LocalDate.now()): Int? =
        year?.let { nextDate(today).year - it }

    fun matchKey(): String = matchKey(name, month, day)

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("name", name)
        put("month", month)
        put("day", day)
        put("year", year ?: JSONObject.NULL)
        put("source", source)
        put("sourceKey", sourceKey ?: JSONObject.NULL)
        put("muted", muted)
        put("calendarEventId", calendarEventId ?: JSONObject.NULL)
        put("calendarSig", calendarSig ?: JSONObject.NULL)
        put("inForeignCalendar", inForeignCalendar)
    }

    companion object {
        fun matchKey(name: String, month: Int, day: Int): String =
            name.trim().lowercase(Locale.GERMAN).replace(Regex("\\s+"), " ") + "|" + month + "|" + day

        fun isValid(month: Int, day: Int): Boolean =
            month in 1..12 && day >= 1 && day <= Month.of(month).maxLength()

        fun fromJson(o: JSONObject): Birthday = Birthday(
            id = o.getString("id"),
            name = o.getString("name"),
            month = o.getInt("month"),
            day = o.getInt("day"),
            year = if (o.isNull("year")) null else o.getInt("year"),
            source = o.optString("source", SOURCE_MANUAL),
            sourceKey = if (o.isNull("sourceKey")) null else o.getString("sourceKey"),
            muted = o.optBoolean("muted", false),
            calendarEventId = if (o.isNull("calendarEventId")) null else o.getLong("calendarEventId"),
            calendarSig = if (o.isNull("calendarSig")) null else o.getString("calendarSig"),
            inForeignCalendar = o.optBoolean("inForeignCalendar", false)
        )
    }
}

/** Ein gefundener Geburtstag aus Kontakten oder Kalender. */
data class Found(val key: String, val name: String, val month: Int, val day: Int, val year: Int?)

data class ParsedDate(val year: Int?, val month: Int, val day: Int)

object DateParse {
    private val iso = Regex("""^(\d{4})-(\d{1,2})-(\d{1,2})""")
    private val noYear = Regex("""^--(\d{1,2})-?(\d{1,2})""")
    private val compact = Regex("""^(\d{4})(\d{2})(\d{2})$""")
    private val german = Regex("""^(\d{1,2})\.(\d{1,2})\.(\d{4})?""")

    fun parse(raw: String): ParsedDate? {
        val s = raw.trim()
        iso.find(s)?.let {
            return make(it.groupValues[1].toInt(), it.groupValues[2].toInt(), it.groupValues[3].toInt())
        }
        noYear.find(s)?.let {
            return make(null, it.groupValues[1].toInt(), it.groupValues[2].toInt())
        }
        compact.find(s)?.let {
            return make(it.groupValues[1].toInt(), it.groupValues[2].toInt(), it.groupValues[3].toInt())
        }
        german.find(s)?.let {
            return make(it.groupValues[3].toIntOrNull(), it.groupValues[2].toInt(), it.groupValues[1].toInt())
        }
        return null
    }

    private fun make(y: Int?, m: Int, d: Int): ParsedDate? {
        if (!Birthday.isValid(m, d)) return null
        // Manche Kontakte speichern 1604 o. ä. für "Jahr unbekannt"
        val year = if (y != null && y in 1850..LocalDate.now().year) y else null
        return ParsedDate(year, m, d)
    }
}

object Fmt {
    private val dayMonth = DateTimeFormatter.ofPattern("d. MMMM", Locale.GERMAN)

    fun date(month: Int, day: Int, year: Int?): String {
        val base = dayMonth.format(LocalDate.of(2000, month, day))
        return if (year != null) "$base $year" else base
    }

    fun inDays(d: Int): String = when (d) {
        0 -> "heute"
        1 -> "morgen"
        else -> "in $d Tagen"
    }
}

/** Speichert alles lokal in den SharedPreferences. */
class Store(ctx: Context) {
    private val prefs = ctx.applicationContext.getSharedPreferences("geburtstage", Context.MODE_PRIVATE)

    fun load(): MutableList<Birthday> {
        val s = prefs.getString("birthdays", null) ?: return mutableListOf()
        return try {
            val a = JSONArray(s)
            MutableList(a.length()) { Birthday.fromJson(a.getJSONObject(it)) }
        } catch (e: Exception) {
            mutableListOf()
        }
    }

    fun save(list: List<Birthday>) {
        val a = JSONArray()
        list.forEach { a.put(it.toJson()) }
        prefs.edit().putString("birthdays", a.toString()).apply()
    }

    var daysBefore: Int
        get() = prefs.getInt("daysBefore", 7)
        set(v) { prefs.edit().putInt("daysBefore", v).apply() }

    var notifyOnDay: Boolean
        get() = prefs.getBoolean("notifyOnDay", true)
        set(v) { prefs.edit().putBoolean("notifyOnDay", v).apply() }

    var hour: Int
        get() = prefs.getInt("hour", 9)
        set(v) { prefs.edit().putInt("hour", v).apply() }

    /** Kalender, in den geschrieben wird. -1 = aus. */
    var calendarId: Long
        get() = prefs.getLong("calendarId", -1L)
        set(v) { prefs.edit().putLong("calendarId", v).apply() }

    var calendarChosen: Boolean
        get() = prefs.getBoolean("calendarChosen", false)
        set(v) { prefs.edit().putBoolean("calendarChosen", v).apply() }

    var contactsToCalendar: Boolean
        get() = prefs.getBoolean("contactsToCalendar", false)
        set(v) { prefs.edit().putBoolean("contactsToCalendar", v).apply() }

    /** Gelöschte Kontakt-/Kalender-Einträge, die nicht wieder importiert werden sollen. */
    var ignored: Set<String>
        get() = prefs.getStringSet("ignored", null)?.toSet() ?: emptySet()
        set(v) { prefs.edit().putStringSet("ignored", HashSet(v)).apply() }

    /** Bereits verschickte Erinnerungen ("id|typ|datum"). */
    var notified: Set<String>
        get() = prefs.getStringSet("notified", null)?.toSet() ?: emptySet()
        set(v) { prefs.edit().putStringSet("notified", HashSet(v)).apply() }
}
