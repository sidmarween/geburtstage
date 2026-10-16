package de.marween.geburtstage

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.ContactsContract

/** Liest die Geburtstage aus den Handy-Kontakten. */
object ContactsImport {

    fun hasPermission(ctx: Context): Boolean =
        ctx.checkSelfPermission(Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED

    /** null = keine Berechtigung */
    fun read(ctx: Context): List<Found>? {
        if (!hasPermission(ctx)) return null
        val out = ArrayList<Found>()
        // Spalten: lookup = Kontakt-Schlüssel, display_name, data1 = Datum, data2 = Ereignistyp (3 = Geburtstag)
        val projection = arrayOf("lookup", "display_name", "data1")
        val selection = "mimetype = ? AND data2 = ?"
        val args = arrayOf("vnd.android.cursor.item/contact_event", "3")
        ctx.contentResolver.query(ContactsContract.Data.CONTENT_URI, projection, selection, args, null)?.use { c ->
            while (c.moveToNext()) {
                val key = c.getString(0) ?: continue
                val name = c.getString(1)?.trim().orEmpty()
                if (name.isEmpty()) continue
                val raw = c.getString(2) ?: continue
                val date = DateParse.parse(raw) ?: continue
                out.add(Found("contact:$key", name, date.month, date.day, date.year))
            }
        }
        return out.distinctBy { it.key }
    }

    /** Kontakte mit Foto: Name (klein geschrieben) -> Kontakt-Schlüssel. */
    fun contactsWithPhoto(ctx: Context): Map<String, String> {
        if (!hasPermission(ctx)) return emptyMap()
        val out = HashMap<String, String>()
        ctx.contentResolver.query(
            ContactsContract.Contacts.CONTENT_URI, arrayOf("lookup", "display_name"),
            "photo_id IS NOT NULL", null, null
        )?.use { c ->
            while (c.moveToNext()) {
                val key = c.getString(0) ?: continue
                val name = c.getString(1) ?: continue
                out[normalize(name)] = key
            }
        }
        return out
    }

    fun normalize(name: String): String =
        name.trim().lowercase(java.util.Locale.GERMAN).replace(Regex("\\s+"), " ")
}
