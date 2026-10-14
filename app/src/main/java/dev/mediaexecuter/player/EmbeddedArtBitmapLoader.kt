package dev.mediaexecuter.player

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import androidx.media3.common.util.BitmapLoader
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSourceBitmapLoader
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
import java.io.IOException
import java.util.concurrent.Executors

/**
 * Capa da notificação / tela de bloqueio: o "artworkUri" de cada música é o próprio arquivo,
 * e daqui lemos a imagem embutida nele (igual à lista). Assim cada música mostra a sua capa.
 */
@UnstableApi
class EmbeddedArtBitmapLoader(context: Context) : BitmapLoader {
    private val app = context.applicationContext
    private val executor = MoreExecutors.listeningDecorator(Executors.newSingleThreadExecutor())
    private val fallback = DataSourceBitmapLoader(app)

    override fun supportsMimeType(mimeType: String) = fallback.supportsMimeType(mimeType)

    override fun decodeBitmap(data: ByteArray): ListenableFuture<Bitmap> = fallback.decodeBitmap(data)

    override fun loadBitmap(uri: Uri): ListenableFuture<Bitmap> = executor.submit<Bitmap> {
        ArtLoader.decodeEmbedded(app, uri, SIZE_PX) ?: throw IOException("Música sem capa")
    }

    private companion object {
        const val SIZE_PX = 512
    }
}
