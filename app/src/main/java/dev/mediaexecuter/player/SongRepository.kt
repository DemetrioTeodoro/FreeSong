package dev.mediaexecuter.player

import android.content.ContentUris
import android.content.Context
import android.os.Build
import android.provider.MediaStore
import java.io.File
import java.text.Collator
import java.util.Locale

object SongRepository {

    /** Lê as músicas do índice do Android (MediaStore). Chamar fora da main thread. */
    fun query(context: Context): List<Song> {
        val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        } else {
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        }
        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.ALBUM,
            MediaStore.Audio.Media.ALBUM_ID,
            MediaStore.Audio.Media.DURATION,
            MediaStore.Audio.Media.SIZE,
            @Suppress("DEPRECATION") MediaStore.Audio.Media.DATA, // caminho do arquivo, só para saber a pasta
        )
        val selection = "${MediaStore.Audio.Media.IS_MUSIC} != 0"
        val unknownArtist = context.getString(R.string.unknown_artist)

        val songs = ArrayList<Song>()
        context.contentResolver.query(collection, projection, selection, null, null)?.use { c ->
            val idCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
            val titleCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
            val artistCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
            val albumCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
            val albumIdCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM_ID)
            val durationCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
            val sizeCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.SIZE)
            @Suppress("DEPRECATION")
            val dataCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DATA)
            songs.ensureCapacity(c.count)
            while (c.moveToNext()) {
                val id = c.getLong(idCol)
                val artist = c.getString(artistCol)
                val file = c.getString(dataCol)?.let { File(it) }
                songs += Song(
                    id = id,
                    title = c.getString(titleCol) ?: "",
                    artist = if (artist.isNullOrBlank() || artist == MediaStore.UNKNOWN_STRING) unknownArtist else artist,
                    album = c.getString(albumCol) ?: "",
                    albumId = c.getLong(albumIdCol),
                    durationMs = c.getLong(durationCol),
                    uri = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id),
                    folder = file?.parent ?: "",
                    fileName = file?.name ?: "",
                    sizeBytes = c.getLong(sizeCol),
                )
            }
        }
        // Ordena como um humano espera: ignora acentos e maiúsculas ("Águas" junto de "Ainda").
        val collator = Collator.getInstance(Locale.getDefault()).apply { strength = Collator.PRIMARY }
        songs.sortWith { a, b -> collator.compare(a.title, b.title) }
        return songs
    }
}
