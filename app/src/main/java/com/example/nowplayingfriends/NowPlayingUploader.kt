package com.example.nowplayingfriends

import android.content.Context
import android.util.Log
import com.google.firebase.Firebase
import com.google.firebase.auth.auth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import java.util.concurrent.ConcurrentHashMap

object NowPlayingUploader {

    private const val TAG = "NowPlayingUploader"

    // How long a song must be “seen” to count as a history entry
    private const val MIN_PLAY_MS = 15_000L

    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // Pending-in-memory play per user. We finalize the *previous* song when it changes.
    private data class PendingPlay(
        val key: String,            // track|artist|service
        var firstTs: Long,
        var lastTs: Long,
        var info: NowPlayingInfo    // snapshot (art url/b64 etc.)
    )
    private val pendingByUid = ConcurrentHashMap<String, PendingPlay>()

    /**
     * Update presence immediately.
     * Buffer history writes and only commit when a song changes and the previous one
     * met the MIN_PLAY_MS threshold.
     */
    fun upsertPresenceAndHistory(context: Context, info: NowPlayingInfo) {
        val uid = Firebase.auth.currentUser?.uid ?: return
        val db = FirebaseFirestore.getInstance()
        val prefs = Prefs(context)

        // --- 1) Presence: always update immediately (non-blocking)
        val presenceDoc = db.collection("presence").document(uid)
        val presenceData = mapOf(
            "displayName" to (prefs.displayName ?: uid.take(6)),
            "track" to info.track,
            "artist" to info.artist,
            "service" to info.service,
            "timestamp" to info.timestamp,
            "art" to info.artB64,
            "artUrl" to info.artUrl
        )
        presenceDoc.set(presenceData)
            .addOnFailureListener { e -> Log.w(TAG, "Presence set failed", e) }

        // --- 2) History buffering / thresholding (off main thread)
        ioScope.launch {
            try {
                val key = "${info.track}|${info.artist}|${info.service}"
                val pending = pendingByUid[uid]

                if (pending == null) {
                    // First seen song in this process
                    pendingByUid[uid] = PendingPlay(
                        key = key,
                        firstTs = info.timestamp,
                        lastTs = info.timestamp,
                        info = info
                    )
                    return@launch
                }

                if (pending.key == key) {
                    // Same song continuing: just extend the window and refresh snapshot
                    pending.lastTs = info.timestamp
                    pending.info = info
                    return@launch
                }

                // Song changed: finalize previous if it met the threshold
                val playedMs = pending.lastTs - pending.firstTs
                if (playedMs >= MIN_PLAY_MS) {
                    val prev = pending.info
                    val historyData = mapOf(
                        "track" to prev.track,
                        "artist" to prev.artist,
                        "service" to prev.service,
                        "ts" to pending.lastTs,     // write the last time we saw it playing
                        "art" to prev.artB64,
                        "artUrl" to prev.artUrl
                    )
                    presenceDoc.collection("history").add(historyData).await()
                } else {
                    Log.d(TAG, "Skip history (played ${playedMs}ms < $MIN_PLAY_MS)")
                }

                // Start new pending for current song
                pendingByUid[uid] = PendingPlay(
                    key = key,
                    firstTs = info.timestamp,
                    lastTs = info.timestamp,
                    info = info
                )
            } catch (e: Exception) {
                Log.w(TAG, "Buffered history write failed", e)
            }
        }
    }

    /**
     * Optional helper to flush the current pending song if it already met the threshold.
     * You could call this when the app goes background, etc.
     */
    fun flushIfEligible(context: Context) {
        val uid = Firebase.auth.currentUser?.uid ?: return
        val pend = pendingByUid[uid] ?: return
        val db = FirebaseFirestore.getInstance()
        val prefs = Prefs(context)
        ioScope.launch {
            try {
                val playedMs = pend.lastTs - pend.firstTs
                if (playedMs >= MIN_PLAY_MS) {
                    val presenceDoc = db.collection("presence").document(uid)
                    val prev = pend.info
                    val historyData = mapOf(
                        "track" to prev.track,
                        "artist" to prev.artist,
                        "service" to prev.service,
                        "ts" to pend.lastTs,
                        "art" to prev.artB64,
                        "artUrl" to prev.artUrl
                    )
                    presenceDoc.collection("history").add(historyData).await()
                }
                // Clear pending after flush attempt
                pendingByUid.remove(uid)
            } catch (e: Exception) {
                Log.w(TAG, "Flush failed", e)
            }
        }
    }
}
