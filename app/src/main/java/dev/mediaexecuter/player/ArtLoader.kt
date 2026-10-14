package dev.mediaexecuter.player

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import android.widget.ImageView
import java.util.Collections
import java.util.concurrent.Executors

/**
 * Carrega a capa embutida em cada arquivo de música, em segundo plano, com cache em memória.
 *
 * O cache é por música (não por álbum): músicas sem tag de álbum ganham do Android o nome
 * da pasta como álbum, e aí várias músicas diferentes dividiriam a mesma capa.
 */
object ArtLoader {
    private const val SIZE_PX = 192

    private val cache = object : LruCache<Long, Bitmap>((Runtime.getRuntime().maxMemory() / 1024 / 8).toInt()) {
        override fun sizeOf(key: Long, value: Bitmap) = value.byteCount / 1024
    }
    private val missing: MutableSet<Long> = Collections.synchronizedSet(HashSet())
    private val executor = Executors.newFixedThreadPool(2)
    private val main = Handler(Looper.getMainLooper())

    fun load(view: ImageView, song: Song?) {
        val key = song?.id
        view.tag = key
        if (song == null || key == null) return showPlaceholder(view)

        cache.get(key)?.let { return showBitmap(view, it) }
        showPlaceholder(view)
        if (key in missing) return

        val context = view.context.applicationContext
        executor.execute {
            val bitmap = decodeEmbedded(context, song.uri, SIZE_PX)
            if (bitmap != null) cache.put(key, bitmap) else missing += key
            main.post { if (view.tag == key && bitmap != null) showBitmap(view, bitmap) }
        }
    }

    private fun showBitmap(view: ImageView, bitmap: Bitmap) {
        view.scaleType = ImageView.ScaleType.CENTER_CROP
        view.setImageBitmap(bitmap)
    }

    private fun showPlaceholder(view: ImageView) {
        view.scaleType = ImageView.ScaleType.CENTER
        view.setImageResource(R.drawable.ic_note)
    }

    /**
     * Lê só a imagem embutida no próprio arquivo (sem usar capas de outras músicas da pasta),
     * reduzida para ~[sizePx]. Retorna null se a música não tem capa. Chamar fora da main thread.
     */
    fun decodeEmbedded(context: Context, uri: Uri, sizePx: Int): Bitmap? {
        val data = try {
            MediaMetadataRetriever().run {
                try {
                    setDataSource(context, uri)
                    embeddedPicture
                } finally {
                    release()
                }
            }
        } catch (_: Exception) {
            null
        } ?: return null

        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(data, 0, data.size, bounds)
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= sizePx && bounds.outHeight / (sample * 2) >= sizePx) sample *= 2
        return BitmapFactory.decodeByteArray(data, 0, data.size, BitmapFactory.Options().apply { inSampleSize = sample })
    }
}
