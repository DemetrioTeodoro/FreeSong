package dev.mediaexecuter.player

import android.app.PendingIntent
import android.content.Intent
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.RenderersFactory
import androidx.media3.exoplayer.audio.MediaCodecAudioRenderer
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.session.CacheBitmapLoader
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture

/**
 * Serviço que toca a música em segundo plano e mostra os controles
 * na notificação, tela de bloqueio e fones Bluetooth.
 */
class PlaybackService : MediaSessionService() {

    private var session: MediaSession? = null

    override fun onCreate() {
        super.onCreate()

        // Só o renderizador de áudio: sem vídeo/legenda, app mais leve.
        val audioOnly = RenderersFactory { handler, _, audioListener, _, _ ->
            arrayOf(MediaCodecAudioRenderer(this, MediaCodecSelector.DEFAULT, handler, audioListener))
        }

        val player = ExoPlayer.Builder(this, audioOnly)
            .setMediaSourceFactory(ProgressiveMediaSource.Factory(DefaultDataSource.Factory(this)))
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .build(),
                /* handleAudioFocus = */ true,
            )
            .setHandleAudioBecomingNoisy(true) // pausa ao desconectar o fone
            .setWakeMode(C.WAKE_MODE_LOCAL)
            .build()

        val openApp = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        session = MediaSession.Builder(this, player)
            .setSessionActivity(openApp)
            .setBitmapLoader(CacheBitmapLoader(EmbeddedArtBitmapLoader(this)))
            .setCallback(object : MediaSession.Callback {
                // Reconstrói a URI a partir do mediaId caso ela não venha do controlador.
                override fun onAddMediaItems(
                    mediaSession: MediaSession,
                    controller: MediaSession.ControllerInfo,
                    mediaItems: MutableList<MediaItem>,
                ): ListenableFuture<MutableList<MediaItem>> = Futures.immediateFuture(
                    mediaItems.mapTo(ArrayList()) {
                        if (it.localConfiguration != null) it else it.buildUpon().setUri(it.mediaId).build()
                    }
                )
            })
            .build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo) = session

    override fun onTaskRemoved(rootIntent: Intent?) {
        val player = session?.player
        if (player == null || !player.playWhenReady || player.mediaItemCount == 0) {
            stopSelf()
        }
    }

    override fun onDestroy() {
        session?.run {
            player.release()
            release()
        }
        session = null
        super.onDestroy()
    }
}
