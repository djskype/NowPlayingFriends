package com.example.nowplayingfriends

import android.app.Notification
import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSession
import android.os.Build
import android.os.Bundle
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Base64
import android.util.Log
import java.io.ByteArrayOutputStream

class NowPlayingListener : NotificationListenerService() {
    private val ALLOWED_MEDIA_PACKAGES = setOf(
        "com.google.android.apps.youtube.music", // YouTube Music
        "com.spotify.music",                     // Spotify
        "com.apple.android.music"                // Apple Music (Android)
    )

    private val TAG = "NPF/Listener"

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        if (sbn == null) return
        val n = sbn.notification ?: return
        val extras = n.extras ?: Bundle()

        val pkg = sbn.packageName ?: return

        // ✅ Ignore anything not from YouTube Music / Spotify / Apple Music
        val allowedPkgs = setOf(
            "com.google.android.apps.youtube.music",
            "com.spotify.music",
            "com.apple.android.music"
        )
        if (pkg !in allowedPkgs) return

        // Only media-style notifications tend to have sessions/metadata
        if (!isMediaStyle(n)) {
            // You can uncomment this line to debug non-media notifs:
            // Log.d(TAG, "Non-media notification: $pkg")
            return
        }

        // 1) Try to get metadata from MediaSession token (most reliable)
        val infoFromSession = extractFromMediaSession(this, extras, pkg)

        // 2) Fallback: parse from notification extras (title/artist + large icon)
        val info = infoFromSession ?: extractFromNotificationExtras(extras, pkg) ?: return

        // 3) Broadcast to UI and persist (if sharing is on)
        NowPlayingBus.post(info)
        NowPlayingUploader.upsertPresenceAndHistory(applicationContext, info)

        // Debug (optional)
        // Log.d(TAG, "Posted: ${info.service} • ${info.track} — ${info.artist}")
    }


    private fun isMediaStyle(n: Notification): Boolean {
        // MediaStyle notifications usually set a media session and category
        if (n.category == Notification.CATEGORY_TRANSPORT) return true
        val hasToken = n.extras?.getParcelable<MediaSession.Token>("android.mediaSession") != null
        return hasToken
    }

    private fun extractFromMediaSession(ctx: Context, extras: Bundle, pkg: String): NowPlayingInfo? {
        val token = extras.getParcelable<MediaSession.Token>("android.mediaSession") ?: return null
        return try {
            val controller = MediaController(ctx, token)
            val md = controller.metadata ?: return null

            val track = md.getString(MediaMetadata.METADATA_KEY_TITLE).orEmpty()
            val artist = (md.getString(MediaMetadata.METADATA_KEY_ARTIST)
                ?: md.getString(MediaMetadata.METADATA_KEY_AUTHOR)
                ?: md.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST)).orEmpty()

            if (track.isBlank() && artist.isBlank()) return null

            val ts = System.currentTimeMillis()

            // Prefer URL-like artwork if provided
            val artUrl = md.getString(MediaMetadata.METADATA_KEY_ALBUM_ART_URI)
                ?: md.getString(MediaMetadata.METADATA_KEY_ART_URI)
                ?: md.getString(MediaMetadata.METADATA_KEY_DISPLAY_ICON_URI)

            // Fallback bitmap(s)
            val artBitmap = md.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
                ?: md.getBitmap(MediaMetadata.METADATA_KEY_ART)
                ?: md.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON)

            val artB64 = artBitmap?.let { encodeToBase64(it) }

            NowPlayingInfo(
                track = track,
                artist = artist,
                service = guessService(pkg),
                timestamp = ts,
                artB64 = artB64,
                artUrl = artUrl
            )
        } catch (e: Exception) {
            Log.w(TAG, "extractFromMediaSession failed: ${e.message}")
            null
        }
    }

    private fun extractFromNotificationExtras(extras: Bundle, pkg: String): NowPlayingInfo? {
        // Common notification extras: title = track, text/subtext = artist/album
        val title = extras.getString(Notification.EXTRA_TITLE).orEmpty()
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty()
        val sub = extras.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString().orEmpty()

        val track = title
        val artist = when {
            text.isNotBlank() -> text
            sub.isNotBlank() -> sub
            else -> ""
        }

        if (track.isBlank() && artist.isBlank()) return null

        val ts = System.currentTimeMillis()

        // Large icon sometimes has album art
        val largeIcon = extras.getParcelable<Bitmap>(Notification.EXTRA_LARGE_ICON)
            ?: extras.getParcelable("android.largeIcon") as? Bitmap

        val artB64 = largeIcon?.let { encodeToBase64(it) }

        return NowPlayingInfo(
            track = track,
            artist = artist,
            service = guessService(pkg),
            timestamp = ts,
            artB64 = artB64,
            artUrl = null // no URL from plain extras
        )
    }

    private fun encodeToBase64(bitmap: Bitmap): String {
        val baos = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 80, baos)
        return Base64.encodeToString(baos.toByteArray(), Base64.NO_WRAP)
    }

    private fun guessService(pkg: String): String = when {
        pkg.contains("youtube", true) -> "YouTube Music"
        pkg.contains("spotify", true) -> "Spotify"
        pkg.contains("apple", true) -> "Apple Music"
        else -> pkg
    }
}

// Keep the shared data class here (same package as Bus + Activity)
data class NowPlayingInfo(
    val track: String,
    val artist: String,
    val service: String,
    val timestamp: Long,
    val artB64: String?,
    val artUrl: String? = null
)
