package de.marween.geburtstage

import android.Manifest
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CalendarContract
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * Liest Geburtstage aus dem Kalender und schreibt eigene Einträge hinein.
 * Die App schreibt in den Kalender des Google-Kontos auf dem Handy –
 * Android synchronisiert das dann selbst mit dem Google-Kalender.
 */
object CalendarSync {
    const val MARKER = "[Geburtstage-App]"

    data class Cal(
        val id: Long,
        val name: String,
        val account: String,
        val owner: String,
        val type: String,
        val access: Int
    ) {
        val label: String get() = if (name == account || account.isEmpty()) name else "$name ($account)"
    }

    fun hasRead(ctx: Context): Boolean =
        ctx.checkSelfPermission(Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED

    fun hasWrite(ctx: Context): Boolean =
        hasRead(ctx) && ctx.checkSelfPermission(Manifest.permission.WRITE_CALENDAR) == PackageManager.PERMISSION_GRANTED

    fun calendars(ctx: Context): List<Cal> {
        if (!hasRead(ctx)) return emptyList()
        val out = ArrayList<Cal>()
        val projection = arrayOf(
            "_id", "calendar_displayName", "account_name", "ownerAccount", "account_type", "calendar_access_level"
        )
        ctx.contentResolver.query(CalendarContract.Calendars.CONTENT_URI, projection, null, null, null)?.use { c ->
            while (c.moveToNext()) {
                out.add(
                    Cal(
                        id = c.getLong(0),
                        name = c.getString(1) ?: "",
                        account = c.getString(2) ?: "",
                        owner = c.getString(3) ?: "",
                        type = c.getString(4) ?: "",
                        access = c.getInt(5)
                    )
                )
            }
        }
        return out
    }

    /** Kalender, in die man schreiben darf (Zugriffsstufe "Mitwirkender" oder höher). */
    fun writable(ctx: Context): List<Cal> = calendars(ctx).filter { it.access >= 500 }

    /** Hauptkalender des Google-Kontos, falls vorhanden. */
    fun defaultCalendar(ctx: Context): Cal? {
        val google = writable(ctx).filter { it.type == "com.google" }
        return google.firstOrNull { it.owner.equals(it.account, ignoreCase = true) } ?: google.firstOrNull()
    }

    /** Liest Geburtstage, die schon im Kalender stehen. null = keine Berechtigung. */
    fun readBirthdays(ctx: Context): List<Found>? {
        if (!hasRead(ctx)) return null
        val bdayCals = calendars(ctx).filter {
            it.owner.contains("#contacts@group.v.calendar.google.com") ||
                it.name.contains("geburtstag", ignoreCase = true) ||
                it.name.contains("birthday", ignoreCase = true)
        }.map { it.id }

        val calFilter = if (bdayCals.isEmpty()) "" else "calendar_id IN (${bdayCals.joinToString(",")}) OR "
        val selection = "deleted = 0 AND (" + calFilter +
            "((title LIKE '%geburtstag%' OR title LIKE '%birthday%') AND rrule IS NOT NULL))"
        val projection = arrayOf("_id", "title", "dtstart", "allDay", "description")

        val out = ArrayList<Found>()
        ctx.contentResolver.query(CalendarContract.Events.CONTENT_URI, projection, selection, null, null)?.use { c ->
            while (c.moveToNext()) {
                val title = c.getString(1) ?: continue
                val desc = c.getString(4) ?: ""
                if (desc.contains(MARKER)) continue // eigene Einträge überspringen
                val allDay = c.getInt(3) == 1
                val zone: ZoneId = if (allDay) ZoneOffset.UTC else ZoneId.systemDefault()
                val date = Instant.ofEpochMilli(c.getLong(2)).atZone(zone).toLocalDate()
                val name = nameFromTitle(title)
                if (name.isBlank()) continue
                out.add(Found("calendar:${c.getLong(0)}", name, date.monthValue, date.dayOfMonth, null))
            }
        }
        return out
    }

    /** Macht aus "Geburtstag von Max", "Max hat Geburtstag", "Max's birthday" usw. einfach "Max". */
    fun nameFromTitle(title: String): String {
        var s = title
        for (e in listOf("🎂", "🎉", "🎁", "🥳", "🎈")) s = s.replace(e, "")
        s = s.trim()
        val patterns = listOf(
            Regex("""(?i)^(?:happy birthday|alles gute)[,!:\s]*(.+?)!*$"""),
            Regex("""(?i)^(?:geburtstag|birthday|geb\.)\s*(?:von|of|:|-|–)?\s*(.+)$"""),
            Regex("""(?i)^(.+?)\s+(?:hat|feiert|has)\s+(?:geburtstag|birthday).*$"""),
            Regex("""(?i)^(.+?)['’]s?\s*(?:geburtstag|birthday)$"""),
            Regex("""(?i)^(.+?)\s*[-–:,]?\s*(?:geburtstag|birthday)$""")
        )
        for (p in patterns) {
            val m = p.find(s) ?: continue
            val n = m.groupValues[1].trim().trim(',', '-', ':', '–', '!').trim()
            if (n.isNotEmpty()) return n
        }
        return s
    }

    private fun shouldWrite(b: Birthday, store: Store): Boolean {
        if (b.inForeignCalendar) return false // steht schon im Kalender
        return when (b.source) {
            SOURCE_MANUAL -> true
            SOURCE_CONTACT -> store.contactsToCalendar
            else -> false
        }
    }

    private fun eventValues(b: Birthday, calId: Long): ContentValues {
        val startYear = b.year ?: 2000 // 2000 ist ein Schaltjahr, damit klappt auch der 29.2.
        val start = LocalDate.of(startYear, b.month, b.day)
            .atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        val rrule = if (b.month == 2 && b.day == 29) "FREQ=YEARLY;BYMONTH=2;BYMONTHDAY=-1" else "FREQ=YEARLY"
        val info = when {
            b.deceased && b.year != null -> "Gedenken an den Geburtstag (geboren ${b.year})"
            b.deceased -> "Gedenken an den Geburtstag"
            b.year != null -> "Geburtstag (geboren ${b.year})"
            else -> "Geburtstag"
        }
        return ContentValues().apply {
            put("calendar_id", calId)
            put("title", if (b.deceased) "🕊️ ${b.name}" else "🎂 ${b.name}")
            put("description", "$info\n$MARKER")
            put("dtstart", start)
            put("duration", "P1D")
            put("allDay", 1)
            put("eventTimezone", "UTC")
            put("rrule", rrule)
            put("availability", 1) // als "frei" anzeigen
            put("hasAlarm", 0)
        }
    }

    private fun signature(b: Birthday, calId: Long) = "$calId|${b.name}|${b.month}|${b.day}|${b.year}|${b.deceased}"

    /** Bringt die eigenen Kalendereinträge auf den Stand der Liste. */
    fun writeAll(ctx: Context, list: List<Birthday>, store: Store) {
        if (!hasWrite(ctx)) return
        val cr = ctx.contentResolver
        val calId = store.calendarId

        // Alle eigenen Einträge im Kalender: Event-ID -> Kalender-ID
        val ours = HashMap<Long, Long>()
        cr.query(
            CalendarContract.Events.CONTENT_URI, arrayOf("_id", "calendar_id"),
            "deleted = 0 AND description LIKE ?", arrayOf("%$MARKER%"), null
        )?.use { c ->
            while (c.moveToNext()) ours[c.getLong(0)] = c.getLong(1)
        }

        val keep = HashSet<Long>()
        for (b in list) {
            if (calId < 0 || !shouldWrite(b, store)) {
                b.calendarEventId = null
                b.calendarSig = null
                continue
            }
            val sig = signature(b, calId)
            val existing = b.calendarEventId
            if (existing != null && ours[existing] == calId) {
                if (b.calendarSig != sig) {
                    val uri = ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, existing)
                    cr.update(uri, eventValues(b, calId), null, null)
                    b.calendarSig = sig
                }
                keep.add(existing)
                continue
            }
            val newId = cr.insert(CalendarContract.Events.CONTENT_URI, eventValues(b, calId))
                ?.lastPathSegment?.toLongOrNull()
            b.calendarEventId = newId
            b.calendarSig = if (newId != null) sig else null
            if (newId != null) keep.add(newId)
        }

        // Eigene Einträge entfernen, die nicht mehr gebraucht werden (gelöscht, anderer Kalender …)
        for (id in ours.keys) {
            if (id !in keep) {
                cr.delete(ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, id), null, null)
            }
        }
    }
}
