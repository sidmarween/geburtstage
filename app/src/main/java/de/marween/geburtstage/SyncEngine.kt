package de.marween.geburtstage

import android.content.Context
import android.util.Log
import java.util.concurrent.Executors

/** Alle Änderungen laufen nacheinander auf einem Hintergrund-Thread. */
object SyncEngine {
    private const val TAG = "Geburtstage"
    private val executor = Executors.newSingleThreadExecutor()

    fun submit(task: () -> Unit) {
        executor.execute {
            try {
                task()
            } catch (e: Exception) {
                Log.e(TAG, "Fehler", e)
            }
        }
    }

    /** Liste laden, ändern, speichern und Kalender nachziehen. Nur im Executor aufrufen. */
    fun mutate(ctx: Context, block: (MutableList<Birthday>, Store) -> Unit) {
        val store = Store(ctx)
        val list = store.load()
        block(list, store)
        pushCalendar(ctx, list, store)
        store.save(list)
    }

    fun setMuted(ctx: Context, id: String, muted: Boolean) {
        mutate(ctx) { list, _ -> list.firstOrNull { it.id == id }?.muted = muted }
    }

    fun delete(ctx: Context, id: String) {
        mutate(ctx) { list, store ->
            val b = list.firstOrNull { it.id == id } ?: return@mutate
            b.sourceKey?.let { store.ignored = store.ignored + it }
            list.remove(b)
            Photos.delete(ctx, b.id)
        }
    }

    private fun pushCalendar(ctx: Context, list: List<Birthday>, store: Store) {
        try {
            CalendarSync.writeAll(ctx, list, store)
        } catch (e: Exception) {
            Log.w(TAG, "Kalender-Sync fehlgeschlagen", e)
        }
    }

    /** Kontakte + Kalender einlesen und eigene Einträge in den Kalender schreiben. */
    fun syncNow(ctx: Context): String {
        val store = Store(ctx)
        val list = store.load()
        val ignored = store.ignored
        val notes = ArrayList<String>()

        val contacts = try { ContactsImport.read(ctx) } catch (e: Exception) { Log.w(TAG, "Kontakte", e); null }
        if (contacts != null) {
            val n = merge(list, contacts, SOURCE_CONTACT, ignored)
            notes.add(if (n > 0) "$n neu aus Kontakten" else "Kontakte aktuell")
        } else {
            notes.add("kein Kontaktzugriff")
        }

        val fromCal = try { CalendarSync.readBirthdays(ctx) } catch (e: Exception) { Log.w(TAG, "Kalender", e); null }
        if (fromCal != null) {
            list.forEach { it.inForeignCalendar = false }
            val n = merge(list, fromCal, SOURCE_CALENDAR, ignored)
            if (n > 0) notes.add("$n neu aus dem Kalender")
        } else {
            notes.add("kein Kalenderzugriff")
        }

        // Fotos: passenden Kontakt zuordnen (eigener Kontakt oder gleicher Name)
        if (contacts != null) {
            val withPhoto = try { ContactsImport.contactsWithPhoto(ctx) } catch (e: Exception) { emptyMap() }
            for (b in list) {
                b.contactLookup = if (b.source == SOURCE_CONTACT) {
                    b.sourceKey?.removePrefix("contact:")
                } else {
                    withPhoto[ContactsImport.normalize(b.name)]
                }
            }
        }

        // Beim ersten Mal automatisch den Google-Hauptkalender wählen
        if (!store.calendarChosen && CalendarSync.hasWrite(ctx)) {
            CalendarSync.defaultCalendar(ctx)?.let {
                store.calendarId = it.id
                store.calendarChosen = true
                notes.add("Kalender: ${it.label}")
            }
        }

        pushCalendar(ctx, list, store)
        store.save(list)
        return "Synchronisiert – " + notes.joinToString(", ")
    }

    private fun merge(list: MutableList<Birthday>, found: List<Found>, source: String, ignored: Set<String>): Int {
        val foundKeys = found.map { it.key }.toSet()
        // Einträge, die es in der Quelle nicht mehr gibt, entfernen
        list.removeAll { it.source == source && it.sourceKey != null && it.sourceKey !in foundKeys }

        var added = 0
        for (f in found) {
            if (f.key in ignored) continue
            val same = list.firstOrNull { it.source == source && it.sourceKey == f.key }
            if (same != null) {
                same.name = f.name
                same.month = f.month
                same.day = f.day
                if (f.year != null) same.year = f.year
                if (source == SOURCE_CALENDAR) same.inForeignCalendar = true
                continue
            }
            val key = Birthday.matchKey(f.name, f.month, f.day)
            val dup = list.firstOrNull { it.matchKey() == key }
            if (dup != null) {
                if (dup.year == null) dup.year = f.year
                if (source == SOURCE_CALENDAR) dup.inForeignCalendar = true
                continue
            }
            list.add(
                Birthday(
                    name = f.name, month = f.month, day = f.day, year = f.year,
                    source = source, sourceKey = f.key,
                    inForeignCalendar = source == SOURCE_CALENDAR
                )
            )
            added++
        }
        return added
    }
}
