package dev.mediaexecuter.player

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import java.util.concurrent.Executors

/**
 * Base para telas que apagam arquivos de música do aparelho.
 *  - Android 11+: o sistema mostra a confirmação e apaga (MediaStore.createDeleteRequest).
 *  - Android 10 ou anterior: pede a permissão de escrita e apaga direto.
 */
abstract class DeletingActivity : Activity() {

    private var pendingDelete: List<Uri> = emptyList()
    private val deleteExecutor = Executors.newSingleThreadExecutor()

    /** Chamado na main thread com as músicas que foram de fato apagadas. */
    protected abstract fun onSongsDeleted(deleted: List<Uri>)

    protected fun deleteSongs(uris: List<Uri>) {
        if (uris.isEmpty()) return
        pendingDelete = uris
        when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.R -> {
                val request = MediaStore.createDeleteRequest(contentResolver, uris)
                startIntentSenderForResult(request.intentSender, REQ_DELETE, null, 0, 0, 0)
            }
            checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED ->
                requestPermissions(arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE), REQ_WRITE)
            else -> deleteDirectly()
        }
    }

    private fun deleteDirectly() {
        val uris = pendingDelete
        deleteExecutor.execute {
            val deleted = uris.filter {
                try {
                    contentResolver.delete(it, null, null) > 0
                } catch (_: Exception) {
                    false
                }
            }
            runOnUiThread { if (!isDestroyed) onSongsDeleted(deleted) }
        }
    }

    @Deprecated("API de Activity")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_DELETE && resultCode == RESULT_OK) onSongsDeleted(pendingDelete)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_WRITE && grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) deleteDirectly()
    }

    override fun onDestroy() {
        deleteExecutor.shutdown()
        super.onDestroy()
    }

    private companion object {
        const val REQ_DELETE = 100
        const val REQ_WRITE = 101
    }
}
