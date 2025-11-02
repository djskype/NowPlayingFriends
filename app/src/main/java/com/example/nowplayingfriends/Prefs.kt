package com.example.nowplayingfriends

import android.content.Context

class Prefs(ctx: Context) {
    private val sp = ctx.getSharedPreferences("npf_prefs", Context.MODE_PRIVATE)

    var preferredPkg: String?
        get() = sp.getString("preferred_pkg", null)
        set(v) { sp.edit().putString("preferred_pkg", v).apply() }

    var lastMusicLaunch: Long
        get() = sp.getLong("last_music_launch", 0L)
        set(v) { sp.edit().putLong("last_music_launch", v).apply() }

    var displayName: String?
        get() = sp.getString("display_name", null)
        set(v) { sp.edit().putString("display_name", v).apply() }

    var shareEnabled: Boolean
        get() = sp.getBoolean("share_enabled", true)
        set(v) { sp.edit().putBoolean("share_enabled", v).apply() }

    // --- De-dup memory for history (used by NowPlayingListener) ---
    var lastTrack: String?
        get() = sp.getString("last_track", null)
        set(v) { sp.edit().putString("last_track", v).apply() }

    var lastArtist: String?
        get() = sp.getString("last_artist", null)
        set(v) { sp.edit().putString("last_artist", v).apply() }

    var lastService: String?
        get() = sp.getString("last_service", null)
        set(v) { sp.edit().putString("last_service", v).apply() }

    var lastTs: Long
        get() = sp.getLong("last_ts", 0L)
        set(v) { sp.edit().putLong("last_ts", v).apply() }

    var themeMode: Int
        get() = sp.getInt("theme_mode", 0) // 0 = System, 1 = Light, 2 = Dark
        set(v) { sp.edit().putInt("theme_mode", v).apply() }

    // In Prefs.kt
    var autoOpenOnStart: Boolean
        get() = sp.getBoolean("autoOpenOnStart", false)
        set(value) { sp.edit().putBoolean("autoOpenOnStart", value).apply() }

}
