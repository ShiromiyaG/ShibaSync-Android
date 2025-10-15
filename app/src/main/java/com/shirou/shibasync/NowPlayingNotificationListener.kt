package com.shirou.shibasync

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.media.session.MediaController
import android.media.MediaMetadata
import android.content.Intent
import android.util.Base64
import android.graphics.Bitmap
import java.io.ByteArrayOutputStream

class NowPlayingNotificationListener : NotificationListenerService() {
    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        emitNowPlaying(sbn)
    }
    override fun onNotificationRemoved(sbn: StatusBarNotification?) { /* ignore */ }

    private fun emitNowPlaying(sbn: StatusBarNotification?) {
        if (sbn == null) return
        val extras = sbn.notification.extras ?: return
        val token = extras.getParcelable<android.media.session.MediaSession.Token>("android.media.session") ?: return

        val controller = MediaController(this, token)
        val meta = controller.metadata ?: return

        val title  = meta.getString(MediaMetadata.METADATA_KEY_TITLE) ?: ""
        val artist = meta.getString(MediaMetadata.METADATA_KEY_ARTIST) ?: ""
        val album  = meta.getString(MediaMetadata.METADATA_KEY_ALBUM) ?: ""
        val art: Bitmap? = meta.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
            ?: meta.getBitmap(MediaMetadata.METADATA_KEY_ART)

        var coverBase64: String? = null
        art?.let {
            val baos = ByteArrayOutputStream()
            it.compress(Bitmap.CompressFormat.JPEG, 85, baos)
            coverBase64 = Base64.encodeToString(baos.toByteArray(), Base64.NO_WRAP)
        }

        val intent = Intent("NOW_PLAYING_BROADCAST").apply {
            putExtra("title", title)
            putExtra("artist", artist)
            putExtra("album", album)
            putExtra("coverBase64", coverBase64 ?: "")
        }
        sendBroadcast(intent)
    }
}
