package de.marween.geburtstage

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.media.ExifInterface
import android.net.Uri
import android.provider.ContactsContract
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * Fotos der Personen: ein selbst gewähltes Foto hat Vorrang,
 * sonst wird das Bild aus dem passenden Kontakt genommen.
 */
object Photos {
    private val NONE = Any()
    private val cache = ConcurrentHashMap<String, Any>()
    private val loader = Executors.newFixedThreadPool(2)

    private fun dir(ctx: Context): File = File(ctx.filesDir, "photos").apply { mkdirs() }

    fun file(ctx: Context, id: String): File = File(dir(ctx), "$id.jpg")

    fun hasOwnPhoto(ctx: Context, id: String): Boolean = file(ctx, id).exists()

    private fun key(ctx: Context, b: Birthday): String =
        b.id + "|" + (b.contactLookup ?: "") + "|" + file(ctx, b.id).lastModified()

    private fun invalidate(id: String) {
        cache.keys.removeIf { it.startsWith("$id|") }
    }

    /** Lädt das Foto rund zugeschnitten. Blockierend – nicht im UI-Thread aufrufen. */
    fun load(ctx: Context, b: Birthday, sizePx: Int): Bitmap? {
        val f = file(ctx, b.id)
        val raw: Bitmap? = if (f.exists()) {
            BitmapFactory.decodeFile(f.absolutePath)
        } else {
            b.contactLookup?.let { contactPhoto(ctx, it) }
        }
        return raw?.let { circle(it, sizePx) }
    }

    /** Für die Liste: sofort aus dem Speicher oder im Hintergrund laden. */
    fun loadAsync(ctx: Context, b: Birthday, sizePx: Int, onResult: (String, Bitmap?) -> Unit): String {
        val k = key(ctx, b)
        val hit = cache[k]
        if (hit != null) {
            onResult(k, hit as? Bitmap)
            return k
        }
        val app = ctx.applicationContext
        loader.execute {
            val bmp = try { load(app, b, sizePx) } catch (e: Exception) { null }
            cache[k] = bmp ?: NONE
            onResult(k, bmp)
        }
        return k
    }

    private fun contactPhoto(ctx: Context, lookup: String): Bitmap? = try {
        val uri = Uri.withAppendedPath(ContactsContract.Contacts.CONTENT_LOOKUP_URI, Uri.encode(lookup))
        ContactsContract.Contacts.openContactPhotoInputStream(ctx.contentResolver, uri, true)
            ?.use { BitmapFactory.decodeStream(it) }
    } catch (e: Exception) {
        null
    }

    fun circle(src: Bitmap, size: Int): Bitmap {
        val side = minOf(src.width, src.height)
        val x = (src.width - side) / 2
        val y = (src.height - side) / 2
        val out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        canvas.drawCircle(size / 2f, size / 2f, size / 2f, paint)
        paint.xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_IN)
        canvas.drawBitmap(src, Rect(x, y, x + side, y + side), Rect(0, 0, size, size), paint)
        return out
    }

    /** Foto aus der Galerie übernehmen: drehen, quadratisch zuschneiden, verkleinern, speichern. */
    fun saveFromUri(ctx: Context, id: String, uri: Uri): Boolean {
        val cr = ctx.contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        val first = cr.openInputStream(uri) ?: return false
        first.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return false

        var sample = 1
        while (bounds.outWidth / (sample * 2) >= 512 && bounds.outHeight / (sample * 2) >= 512) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        val second = cr.openInputStream(uri) ?: return false
        var bmp: Bitmap = second.use { BitmapFactory.decodeStream(it, null, opts) } ?: return false

        val degrees = try {
            val third = cr.openInputStream(uri)
            val orientation = third?.use {
                ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
            } ?: ExifInterface.ORIENTATION_NORMAL
            when (orientation) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90
                ExifInterface.ORIENTATION_ROTATE_180 -> 180
                ExifInterface.ORIENTATION_ROTATE_270 -> 270
                else -> 0
            }
        } catch (e: Exception) {
            0
        }
        if (degrees != 0) {
            val m = Matrix().apply { postRotate(degrees.toFloat()) }
            bmp = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
        }

        val side = minOf(bmp.width, bmp.height)
        val square = Bitmap.createBitmap(bmp, (bmp.width - side) / 2, (bmp.height - side) / 2, side, side)
        val target = if (side > 512) Bitmap.createScaledBitmap(square, 512, 512, true) else square
        file(ctx, id).outputStream().use { target.compress(Bitmap.CompressFormat.JPEG, 88, it) }
        invalidate(id)
        return true
    }

    fun delete(ctx: Context, id: String) {
        file(ctx, id).delete()
        invalidate(id)
    }
}
