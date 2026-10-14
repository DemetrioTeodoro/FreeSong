package dev.mediaexecuter.player

import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import java.text.Normalizer

data class Song(
    val id: Long,
    val title: String,
    val artist: String,
    val album: String,
    val albumId: Long,
    val durationMs: Long,
    val uri: Uri,
    /** Pasta onde o arquivo está (ex.: /storage/emulated/0/Music). */
    val folder: String,
    val fileName: String,
    val sizeBytes: Long,
) {
    /** Texto pré-normalizado usado na busca (sem acentos, minúsculo). */
    val searchKey: String = normalize("$title $artist $album")

    fun toMediaItem(): MediaItem = MediaItem.Builder()
        .setMediaId(uri.toString())
        .setUri(uri)
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(title)
                .setArtist(artist)
                .setAlbumTitle(album)
                .setArtworkUri(uri) // capa embutida no próprio arquivo (ver EmbeddedArtBitmapLoader)
                .setIsPlayable(true)
                .setIsBrowsable(false)
                .build()
        )
        .build()
}

private val DIACRITICS = Regex("\\p{Mn}+")

fun normalize(text: CharSequence): String =
    DIACRITICS.replace(Normalizer.normalize(text, Normalizer.Form.NFD), "").lowercase()

fun formatTime(ms: Long): String {
    val total = (ms.coerceAtLeast(0) / 1000).toInt()
    val h = total / 3600
    val m = total % 3600 / 60
    val s = total % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}
