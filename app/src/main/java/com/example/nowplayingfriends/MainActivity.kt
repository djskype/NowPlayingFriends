package com.example.nowplayingfriends


import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import coil.compose.AsyncImage


import kotlinx.coroutines.launch
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
// (optional alternative) import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.Settings
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.format.DateUtils
import android.util.Base64
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import coil.compose.AsyncImage
import coil.request.ImageRequest
import androidx.compose.ui.layout.ContentScale
import com.example.nowplayingfriends.ui.theme.NpfTheme
import com.google.firebase.Firebase
import com.google.firebase.auth.auth
import com.google.firebase.firestore.FieldPath
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.Query
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (Firebase.auth.currentUser == null) Firebase.auth.signInAnonymously()

        setContent {
            // 0=System, 1=Light, 2=Dark
            var themeMode by rememberSaveable { mutableIntStateOf(0) }

            NpfTheme(darkTheme = when (themeMode) {
                1 -> false
                2 -> true
                else -> isSystemInDarkTheme()
            }) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    Scaffold(
                        containerColor = MaterialTheme.colorScheme.background,
                        contentWindowInsets = WindowInsets.safeDrawing
                    ) { innerPadding ->
                        Box(
                            modifier = Modifier
                                .padding(innerPadding)
                                .fillMaxSize()
                                .background(MaterialTheme.colorScheme.background)
                        ) {
                            AppScreen(
                                themeMode = themeMode,
                                onChangeTheme = { themeMode = it }
                            )
                        }
                    }
                }
            }
        }
    }
}

/** Rows & models used in UI */
data class HistoryRow(
    val id: String,
    val track: String,
    val artist: String,
    val service: String,
    val ts: Long,
    val artB64: String? = null,
    val artUrl: String? = null
)

data class SavedFriend(
    val uid: String,
    val nickname: String
)

data class PresenceRow(
    val uid: String,
    val name: String,
    val track: String,
    val artist: String,
    val service: String,
    val ts: Long,
    val artB64: String?,
    val artUrl: String? = null
)

@Composable
fun AppScreen(
    themeMode: Int,
    onChangeTheme: (Int) -> Unit
) {
    val ctx = LocalContext.current
    val prefs = remember { Prefs(ctx) }
    val db = remember { FirebaseFirestore.getInstance() }
    val myUid = Firebase.auth.currentUser?.uid.orEmpty()


    // Tabs
    var tabIndex by remember { mutableIntStateOf(0) }
    val tabs = listOf("Now Playing", "Friends", "Settings")

    // Preferred app picker dialog
    var showPicker by remember { mutableStateOf(false) }
    var autoOpenOnStart by remember { mutableStateOf(prefs.autoOpenOnStart) }
    val knownPackages = listOf(
        "com.google.android.apps.youtube.music",
        "com.spotify.music",
        "com.apple.android.music"
    )

    // Live "now playing"
    var nowTrack by remember { mutableStateOf("") }
    var nowArtist by remember { mutableStateOf("") }
    var nowService by remember { mutableStateOf("") }
    var nowTs by remember { mutableStateOf(0L) }
    var nowArtB64 by remember { mutableStateOf<String?>(null) }
    var nowArtUrl by remember { mutableStateOf<String?>(null) }

    // Saved friends + presence-name cache
    var savedFriends by remember { mutableStateOf(listOf<SavedFriend>()) }
    var savedFriendsListener by remember { mutableStateOf<ListenerRegistration?>(null) }
    val friendPresenceNames = remember { mutableStateMapOf<String, String>() }
    var presenceNamesListener by remember { mutableStateOf<ListenerRegistration?>(null) }

    // 🔥 Live presence per friend (new)
    val presenceByUid = remember { mutableStateMapOf<String, PresenceRow>() }
    var feedListeners by remember { mutableStateOf(listOf<ListenerRegistration>()) }
    val friendAvatars = remember { mutableStateMapOf<String, String?>() }
    var avatarListeners by remember { mutableStateOf(listOf<ListenerRegistration>()) }


    // Your history (live) + listener handle
    var myHistory by remember { mutableStateOf(listOf<HistoryRow>()) }
    var myHistoryListener by remember { mutableStateOf<ListenerRegistration?>(null) }

    // Per-friend history + expansion flags
    val friendHistories = remember { mutableStateMapOf<String, List<HistoryRow>>() }
    val friendExpanded = remember { mutableStateMapOf<String, Boolean>() }

    // Profile/settings live state
    var displayName by remember { mutableStateOf(prefs.displayName.orEmpty()) }
    var shareOn by remember { mutableStateOf(prefs.shareEnabled) }

    // Helpers
    fun labelFor(pkg: String) = when (pkg) {
        "com.google.android.apps.youtube.music" -> "YouTube Music"
        "com.spotify.music" -> "Spotify"
        "com.apple.android.music" -> "Apple Music"
        else -> pkg
    }

    fun isInstalled(pkg: String): Boolean {
        return try {
            if (ctx.packageManager.getLaunchIntentForPackage(pkg) != null) return true
            if (Build.VERSION.SDK_INT >= 33) {
                ctx.packageManager.getPackageInfo(pkg, PackageManager.PackageInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                ctx.packageManager.getPackageInfo(pkg, 0)
            }
            true
        } catch (_: Exception) {
            false
        }
    }

    fun openSongSearch(track: String, artist: String) {
        val query = "$track $artist".trim()
        val encoded = URLEncoder.encode(query, StandardCharsets.UTF_8.toString())
        val preferred = prefs.preferredPkg

        fun spotifyIntents() = listOf(
            Intent(Intent.ACTION_VIEW, "spotify:search:$query".toUri()).apply {
                `package` = "com.spotify.music"; addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            },
            Intent(Intent.ACTION_VIEW, "https://open.spotify.com/search/$encoded".toUri()).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        )

        fun ytmIntents() = listOf(
            Intent(
                Intent.ACTION_VIEW,
                "https://music.youtube.com/search?q=$encoded".toUri()
            ).apply {
                `package` =
                    "com.google.android.apps.youtube.music"; addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            },
            Intent(
                Intent.ACTION_VIEW,
                "https://music.youtube.com/search?q=$encoded".toUri()
            ).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        )

        val tries = when (preferred) {
            "com.spotify.music" -> spotifyIntents() + ytmIntents()
            "com.google.android.apps.youtube.music" -> ytmIntents() + spotifyIntents()
            "com.apple.android.music" -> listOf(
                Intent(Intent.ACTION_VIEW, "https://music.apple.com/search?term=$encoded".toUri())
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ) + ytmIntents() + spotifyIntents()

            else -> ytmIntents() + spotifyIntents()
        }
        for (i in tries) try {
            ctx.startActivity(i); return
        } catch (_: Exception) {
        }
        Toast.makeText(
            ctx,
            "No app could open the song. Install Spotify or YouTube Music.",
            Toast.LENGTH_LONG
        ).show()
    }

    fun decodeArt(b64: String?): ImageBitmap? {
        if (b64.isNullOrEmpty()) return null
        return try {
            val bytes = Base64.decode(b64, Base64.DEFAULT)
            val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null
            bmp.asImageBitmap()
        } catch (_: Exception) {
            null
        }
    }

    // Observe live now-playing (via bus)
    LaunchedEffect(Unit) {
        NowPlayingBus.flow.collect { info ->
            nowTrack = info.track
            nowArtist = info.artist
            nowService = info.service
            nowTs = info.timestamp
            nowArtB64 = info.artB64
            nowArtUrl = info.artUrl
        }
    }

    // Live saved friends list
    LaunchedEffect(myUid) {
        savedFriendsListener?.remove()
        if (myUid.isNotBlank()) {
            savedFriendsListener = db.collection("users").document(myUid)
                .collection("friends")
                .orderBy("nickname", Query.Direction.ASCENDING)
                .addSnapshotListener { snap, err ->
                    if (err != null) {
                        Toast.makeText(ctx, "Friends error: ${err.message}", Toast.LENGTH_SHORT)
                            .show()
                        return@addSnapshotListener
                    }
                    savedFriends = snap?.documents?.map { d ->
                        SavedFriend(
                            uid = d.getString("uid").orEmpty(),
                            nickname = d.getString("nickname").orEmpty()
                        )
                    }.orEmpty()
                }
        }
    }
    // Live "My History" listener (auto-load on start and keep updated)
    LaunchedEffect(Unit) {
        myHistoryListener?.remove()
        val uid = Firebase.auth.currentUser?.uid
        if (uid != null) {
            myHistoryListener = FirebaseFirestore.getInstance()
                .collection("presence").document(uid)
                .collection("history")
                .orderBy("ts", Query.Direction.DESCENDING)
                .limit(100) // read a bit more so dedupe still yields a solid list
                .addSnapshotListener { snap, _ ->
                    val mapped = snap?.documents?.map { d ->
                        HistoryRow(
                            id     = d.id, // ← use doc id
                            track  = d.getString("track").orEmpty(),
                            artist = d.getString("artist").orEmpty(),
                            service= d.getString("service").orEmpty(),
                            ts     = d.getLong("ts") ?: 0L,
                            artB64 = d.getString("art"),
                            artUrl = d.getString("artUrl")
                        )
                    }.orEmpty()

                    // 1) Deduplicate by document id (strong guarantee)
                    val byId = mapped.distinctBy { it.id }

                    // 2) Also coalesce "same song" flaps within a short time window (e.g., 2 min)
                    val seen = HashSet<String>()
                    val windowMs = 2 * 60 * 1000L
                    val coalesced = byId.filter { row ->
                        val key = "${row.track}|${row.artist}|${row.service}"
                        val already = seen.contains(key)
                        if (!already) {
                            seen.add(key)
                            true
                        } else {
                            // If we've already seen this song, only keep another if it's far apart in time
                            // Find first kept ts for this key and compare
                            // (We’re iterating in DESC order, so the first kept is the latest)
                            val firstTs = byId.firstOrNull {
                                it.track == row.track && it.artist == row.artist && it.service == row.service
                            }?.ts ?: row.ts
                            (firstTs - row.ts) > windowMs
                        }
                    }

                    myHistory = coalesced
                }
        }
    }



    // Listen to presence docs for saved friends to get their self-set displayName
    LaunchedEffect(savedFriends) {
        presenceNamesListener?.remove()
        val ids = savedFriends.map { it.uid }.take(10)
        if (ids.isEmpty()) {
            friendPresenceNames.clear()
            return@LaunchedEffect
        }
        presenceNamesListener = db.collection("presence")
            .whereIn(FieldPath.documentId(), ids)
            .addSnapshotListener { snap, _ ->
                snap?.documents?.forEach { d ->
                    val uid = d.id
                    val name = d.getString("displayName")
                    if (!name.isNullOrBlank()) friendPresenceNames[uid] = name
                }
            }
    }
    LaunchedEffect(savedFriends) {
        // remove old
        avatarListeners.forEach { it.remove() }
        avatarListeners = emptyList()
        friendAvatars.clear()

        val ids = savedFriends.map { it.uid }.filter { it.isNotBlank() }
        if (ids.isEmpty()) return@LaunchedEffect

        val db = FirebaseFirestore.getInstance()
        // One listener per friend, keeps it simple (avatars rarely change)
        val newLs = ids.map { uid ->
            // We try the subcollection path: users/{uid}/profile/meta
            db.collection("users").document(uid)
                .collection("profile").document("meta")
                .addSnapshotListener { snap, _ ->
                    val url = snap?.getString("photoUrl")
                    val b64 = snap?.getString("photoB64")
                    friendAvatars[uid] = url ?: b64
                }
        }
        avatarListeners = newLs
    }

    // 🔥 Auto-load live presence for all saved friends (chunked by 10)
    LaunchedEffect(savedFriends) {
        feedListeners.forEach { it.remove() }
        feedListeners = emptyList()
        presenceByUid.clear()

        val ids = savedFriends.map { it.uid }.filter { it.isNotBlank() }
        if (ids.isEmpty()) return@LaunchedEffect

        val chunks = ids.chunked(10)
        val newLs = chunks.map { chunk ->
            db.collection("presence")
                .whereIn(FieldPath.documentId(), chunk)
                .addSnapshotListener { snap, err ->
                    if (err != null) {
                        Toast.makeText(ctx, "Feed error: ${err.message}", Toast.LENGTH_SHORT).show()
                        return@addSnapshotListener
                    }
                    snap?.documents?.forEach { d ->
                        val row = PresenceRow(
                            uid = d.id,
                            name = d.getString("displayName") ?: d.id.take(6),
                            track = d.getString("track").orEmpty(),
                            artist = d.getString("artist").orEmpty(),
                            service = d.getString("service").orEmpty(),
                            ts = d.getLong("timestamp") ?: d.getLong("ts") ?: 0L,
                            artB64 = d.getString("art"),
                            artUrl = d.getString("artUrl")
                        )
                        presenceByUid[d.id] = row
                    }
                }
        }
        feedListeners = newLs
    }

    // --- UI ---
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    selected = tabIndex == 0,
                    onClick = { tabIndex = 0 },
                    icon = { Icon(Icons.Default.PlayArrow, contentDescription = "Now Playing") },
                    label = { Text("Now Playing") }
                )
                NavigationBarItem(
                    selected = tabIndex == 1,
                    onClick = { tabIndex = 1 },
                    icon = { Icon(Icons.Default.Group, contentDescription = "Friends") },
                    label = { Text("Friends") }
                )
                NavigationBarItem(
                    selected = tabIndex == 2,
                    onClick = { tabIndex = 2 },
                    icon = { Icon(Icons.Default.Settings, contentDescription = "Settings") },
                    label = { Text("Settings") }
                )
            }
        }
    ) { innerPadding ->
        Box(
            Modifier
                .padding(innerPadding)
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
        ) {
            when (tabIndex) {
                // ============== NOW PLAYING TAB ==============
                0 -> {
                    val snackbarHostState = remember { SnackbarHostState() }
                    val scope = rememberCoroutineScope()
                    val uid = Firebase.auth.currentUser?.uid.orEmpty()
                    val db = FirebaseFirestore.getInstance()

                    val scroll = rememberScrollState()
                    Column(
                        Modifier
                            .fillMaxSize()
                            .verticalScroll(scroll)
                            .padding(16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            "Your Playing",
                            style = MaterialTheme.typography.titleLarge,
                            textAlign = TextAlign.Center
                        )
                        Spacer(Modifier.height(12.dp))

                        val url = nowArtUrl
                        val img = decodeArt(nowArtB64)
                        if (!url.isNullOrBlank()) {
                            AsyncImage(
                                model = ImageRequest.Builder(ctx).data(url).crossfade(true)
                                    .size(512).build(),
                                contentDescription = null,
                                modifier = Modifier.size(220.dp).clip(RoundedCornerShape(12.dp)),
                                contentScale = ContentScale.Crop
                            )
                        } else if (img != null) {
                            Image(
                                bitmap = img,
                                contentDescription = null,
                                modifier = Modifier.size(220.dp).clip(RoundedCornerShape(12.dp))
                            )
                        } else {
                            Box(
                                Modifier.size(220.dp).clip(RoundedCornerShape(12.dp))
                                    .background(Color(0x11000000))
                            )
                        }

                        Spacer(Modifier.height(12.dp))
                        Text(
                            if (nowTrack.isNotBlank()) nowTrack else "—",
                            style = MaterialTheme.typography.titleMedium,
                            textAlign = TextAlign.Center
                        )
                        Text(
                            if (nowArtist.isNotBlank()) nowArtist else "",
                            style = MaterialTheme.typography.bodyLarge,
                            textAlign = TextAlign.Center
                        )
                        Spacer(Modifier.height(6.dp))
                        if (nowService.isNotBlank()) {
                            Text(
                                "$nowService · ${
                                    if (nowTs > 0) DateUtils.getRelativeTimeSpanString(
                                        nowTs
                                    ) else "waiting…"
                                }",
                                color = Color.Gray, textAlign = TextAlign.Center
                            )
                        }

                        Spacer(Modifier.height(20.dp))
                        Button(
                            onClick = {
                                val pkg = prefs.preferredPkg
                                if (pkg == null) {
                                    Toast.makeText(
                                        ctx,
                                        "Choose your music app first",
                                        Toast.LENGTH_SHORT
                                    ).show()
                                } else {
                                    val intent = ctx.packageManager.getLaunchIntentForPackage(pkg)
                                    if (intent == null) {
                                        val url2 =
                                            "https://play.google.com/store/apps/details?id=$pkg"
                                        ctx.startActivity(Intent(Intent.ACTION_VIEW, url2.toUri()))
                                    } else {
                                        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                        ctx.startActivity(intent)
                                        prefs.lastMusicLaunch = System.currentTimeMillis()
                                    }
                                }
                            }
                        ) { Text("Open Music App") }
                        Spacer(Modifier.height(16.dp))
                        Divider()
                        Spacer(Modifier.height(12.dp))

                        Text("Recent Plays (You)", style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.height(6.dp))

                        // Hide only the first/top item if it matches the currently-playing song.
// Older repeats of the same song are still shown.
                        val displayHistory = myHistory.let { list ->
                            if (list.isNotEmpty()
                                && list.first().track.equals(nowTrack, ignoreCase = true)
                                && list.first().artist.equals(nowArtist, ignoreCase = true)
                                && list.first().service == nowService
                            ) list.drop(1) else list
                        }
                        if (displayHistory.isEmpty()) {
                            Text("No recent plays yet.", color = Color.Gray)
                        } else {
                            displayHistory.forEach { row ->
                                Row(
                                    Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    // Prefer URL art (sharper), fallback to Base64
                                    val ru = row.artUrl
                                    val rb = decodeArt(row.artB64)

                                    Box(
                                        Modifier
                                            .size(40.dp)
                                            .clip(RoundedCornerShape(6.dp))
                                            .background(Color(0x11000000))
                                            .clickable { openSongSearch(row.track, row.artist) },
                                        contentAlignment = Alignment.Center
                                    ) {
                                        if (!ru.isNullOrBlank()) {
                                            AsyncImage(
                                                model = ImageRequest.Builder(ctx).data(ru).crossfade(true).size(128).build(),
                                                contentDescription = null,
                                                modifier = Modifier.matchParentSize(),
                                                contentScale = ContentScale.Crop
                                            )
                                        } else if (rb != null) {
                                            Image(
                                                bitmap = rb,
                                                contentDescription = null,
                                                modifier = Modifier.matchParentSize(),
                                                contentScale = ContentScale.Crop
                                            )
                                        }
                                    }

                                    Spacer(Modifier.width(8.dp))

                                    Column(
                                        Modifier
                                            .weight(1f)
                                            .clickable { openSongSearch(row.track, row.artist) }
                                    ) {
                                        Text("• ${row.track} — ${row.artist}", fontWeight = FontWeight.SemiBold)
                                        Text("(${row.service}) " + DateUtils.getRelativeTimeSpanString(row.ts))
                                    }

                                    Spacer(Modifier.width(8.dp))

                                    // ❌ Delete with UNDO
                                    IconButton(
                                        onClick = {
                                            // Optimistic remove locally
                                            val before = myHistory
                                            myHistory = myHistory.filterNot { it.id == row.id }

                                            scope.launch {
                                                val result = snackbarHostState.showSnackbar(
                                                    message = "Removed “${row.track}”",
                                                    actionLabel = "Undo",
                                                    withDismissAction = true,
                                                    duration = SnackbarDuration.Short
                                                )

                                                if (result == SnackbarResult.ActionPerformed) {
                                                    // Undo: restore list
                                                    myHistory = before
                                                } else {
                                                    // Commit delete in Firestore
                                                    if (uid.isNotBlank()) {
                                                        db.collection("presence").document(uid)
                                                            .collection("history").document(row.id)
                                                            .delete()
                                                            .addOnFailureListener {
                                                                myHistory = before
                                                                Toast.makeText(ctx, "Delete failed. Try again.", Toast.LENGTH_SHORT).show()
                                                            }
                                                    } else {
                                                        myHistory = before
                                                        Toast.makeText(ctx, "Not signed in.", Toast.LENGTH_SHORT).show()

                                                    }
                                                }
                                            }
                                        }
                                    ) {
                                        Icon(
                                            imageVector = Icons.Outlined.Close, // or Icons.Outlined.Delete
                                            contentDescription = "Delete"
                                        )
                                        Spacer(Modifier.height(8.dp))
                                        SnackbarHost(hostState = snackbarHostState)

                                    }
                                }
                            }
                        }


                    }
                }

                // ============== FRIENDS TAB ==============
                1 -> {
                    val scroll = rememberScrollState()
                    Column(
                        Modifier
                            .fillMaxSize()
                            .verticalScroll(scroll)
                            .padding(16.dp)
                    ) {
                        Text("Saved Friends", style = MaterialTheme.typography.titleMedium)
                        val liveCount = presenceByUid.size
                        Text("Live presence entries: $liveCount", color = Color.Gray)
                        Spacer(Modifier.height(8.dp))

                        var addFriendUid by remember { mutableStateOf("") }
                        var addFriendNick by remember { mutableStateOf("") }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            OutlinedTextField(
                                value = addFriendUid,
                                onValueChange = { addFriendUid = it },
                                label = { Text("Friend UID") },
                                modifier = Modifier.weight(1f)
                            )
                            Spacer(Modifier.width(8.dp))
                            OutlinedTextField(
                                value = addFriendNick,
                                onValueChange = { addFriendNick = it },
                                label = { Text("Nickname (optional)") },
                                modifier = Modifier.weight(1f)
                            )
                            Spacer(Modifier.width(8.dp))
                            Button(onClick = {
                                val uid = addFriendUid.trim()
                                val nick = addFriendNick.trim().ifBlank { uid.take(6) }
                                if (uid.isEmpty()) {
                                    Toast.makeText(ctx, "Enter a friend UID", Toast.LENGTH_SHORT)
                                        .show(); return@Button
                                }
                                if (myUid.isBlank()) {
                                    Toast.makeText(ctx, "Not signed in yet", Toast.LENGTH_SHORT)
                                        .show(); return@Button
                                }
                                db.collection("users").document(myUid)
                                    .collection("friends").document(uid)
                                    .set(mapOf("uid" to uid, "nickname" to nick))
                                    .addOnSuccessListener {
                                        addFriendUid = ""; addFriendNick = ""; Toast.makeText(
                                        ctx,
                                        "Friend saved",
                                        Toast.LENGTH_SHORT
                                    ).show()
                                    }
                                    .addOnFailureListener { e ->
                                        Toast.makeText(
                                            ctx,
                                            "Save error: ${e.message}",
                                            Toast.LENGTH_SHORT
                                        ).show()
                                    }
                            }) { Text("Save") }
                        }

                        Spacer(Modifier.height(8.dp))

                        savedFriends.forEach { f ->
                            Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    // ✅ avatar (from friendAvatars map)
                                    val avatar = friendAvatars[f.uid]
                                    if (!avatar.isNullOrBlank()) {
                                        if (avatar.startsWith("http")) {
                                            AsyncImage(
                                                model = ImageRequest.Builder(ctx).data(avatar)
                                                    .crossfade(true).size(96).build(),
                                                contentDescription = null,
                                                modifier = Modifier.size(28.dp).clip(CircleShape),
                                                contentScale = ContentScale.Crop
                                            )
                                        } else {
                                            val bmp =
                                                decodeArt(avatar) // base64 decode using same helper
                                            if (bmp != null) {
                                                Image(
                                                    bitmap = bmp,
                                                    contentDescription = null,
                                                    modifier = Modifier.size(28.dp)
                                                        .clip(CircleShape)
                                                )
                                            } else {
                                                Box(
                                                    Modifier.size(28.dp).clip(CircleShape)
                                                        .background(Color(0x22000000))
                                                )
                                            }
                                        }
                                        Spacer(Modifier.width(8.dp))
                                    } else {
                                        // placeholder
                                        Box(
                                            Modifier.size(28.dp).clip(CircleShape)
                                                .background(Color(0x22000000))
                                        )
                                        Spacer(Modifier.width(8.dp))
                                    }

                                    val display = friendPresenceNames[f.uid]
                                        ?: f.nickname.ifBlank { f.uid.take(6) }
                                    Text(
                                        "• $display (${f.uid.take(6)})",
                                        modifier = Modifier.weight(1f)
                                    )

                                    TextButton(onClick = {
                                        db.collection("presence").document(f.uid)
                                            .collection("history")
                                            .orderBy("ts", Query.Direction.DESCENDING)
                                            .limit(20)
                                            .get()
                                            .addOnSuccessListener { snap ->
                                                val rows = snap.documents.map { d ->
                                                    HistoryRow(
                                                        id     = d.id,
                                                        track = d.getString("track").orEmpty(),
                                                        artist = d.getString("artist").orEmpty(),
                                                        service = d.getString("service").orEmpty(),
                                                        ts = d.getLong("ts") ?: 0L,
                                                        artB64 = d.getString("art"),
                                                        artUrl = d.getString("artUrl")
                                                    )
                                                }
                                                friendHistories[f.uid] = rows
                                                friendExpanded[f.uid] = true
                                            }
                                            .addOnFailureListener { e ->
                                                Toast.makeText(
                                                    ctx,
                                                    "Friend history error: ${e.message}",
                                                    Toast.LENGTH_SHORT
                                                ).show()
                                            }
                                    }) { Text("View History") }

                                    TextButton(onClick = {
                                        friendExpanded[f.uid] = !(friendExpanded[f.uid] ?: false)
                                    }) {
                                        Text(if (friendExpanded[f.uid] == true) "Hide" else "Show")
                                    }
                                    TextButton(onClick = {
                                        if (myUid.isBlank()) return@TextButton
                                        db.collection("users").document(myUid).collection("friends")
                                            .document(f.uid).delete()
                                    }) { Text("Remove") }
                                }

                                // Inline "Currently Listening"
                                val current = presenceByUid[f.uid]
                                if (current != null) {
                                    Spacer(Modifier.height(6.dp))
                                    Row(
                                        Modifier
                                            .fillMaxWidth()
                                            .clickable {
                                                openSongSearch(
                                                    current.track,
                                                    current.artist
                                                )
                                            }
                                            .padding(start = 12.dp, top = 6.dp, bottom = 6.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        val cu = current.artUrl
                                        val cb = decodeArt(current.artB64)
                                        if (!cu.isNullOrBlank()) {
                                            AsyncImage(
                                                model = ImageRequest.Builder(ctx).data(cu)
                                                    .crossfade(true).size(128).build(),
                                                contentDescription = null,
                                                modifier = Modifier.size(40.dp)
                                                    .clip(RoundedCornerShape(6.dp)),
                                                contentScale = ContentScale.Crop
                                            )
                                        } else if (cb != null) {
                                            Image(
                                                bitmap = cb,
                                                contentDescription = null,
                                                modifier = Modifier.size(40.dp)
                                                    .clip(RoundedCornerShape(6.dp))
                                            )
                                        } else {
                                            Box(
                                                Modifier.size(40.dp).clip(RoundedCornerShape(6.dp))
                                                    .background(Color(0x11000000))
                                            )
                                        }

                                        Spacer(Modifier.width(8.dp))
                                        Column(Modifier.weight(1f)) {
                                            Text(
                                                text = "${current.track.ifBlank { "—" }} — ${current.artist}",
                                                fontWeight = FontWeight.SemiBold
                                            )
                                            Text(
                                                "(${current.service}) " + DateUtils.getRelativeTimeSpanString(
                                                    current.ts
                                                )
                                            )
                                        }
                                        Spacer(Modifier.width(8.dp))
                                        val ageMs = System.currentTimeMillis() - current.ts
                                        val isHot = ageMs < 90_000L
                                        Box(
                                            Modifier.size(12.dp).clip(CircleShape).background(
                                                if (isHot) Color(0xFF22C55E) else Color(0xFFE11D48)
                                            )
                                        )
                                    }
                                }

                                if (friendExpanded[f.uid] == true) {
                                    val rows = friendHistories[f.uid].orEmpty()
                                    if (rows.isEmpty()) {
                                        Text("No history yet.", modifier = Modifier.padding(start = 24.dp))
                                    } else {
                                        Column(
                                            Modifier
                                                .fillMaxWidth()
                                                .padding(start = 64.dp) // ← Indent the entire history block
                                        ) {
                                            rows.forEach { row ->
                                                Row(
                                                    Modifier
                                                        .fillMaxWidth()
                                                        .clickable { openSongSearch(row.track, row.artist) }
                                                        .padding(vertical = 6.dp),
                                                    verticalAlignment = Alignment.CenterVertically
                                                ) {
                                                    val img = decodeArt(row.artB64)
                                                    if (img != null) {
                                                        Image(
                                                            bitmap = img,
                                                            contentDescription = null,
                                                            modifier = Modifier
                                                                .size(40.dp)
                                                                .clip(RoundedCornerShape(6.dp))
                                                        )
                                                    } else {
                                                        Box(
                                                            Modifier
                                                                .size(40.dp)
                                                                .clip(RoundedCornerShape(6.dp))
                                                                .background(Color(0x11000000))
                                                        )
                                                    }
                                                    Spacer(Modifier.width(8.dp))
                                                    Column(Modifier.weight(1f)) {
                                                        Text("• ${row.track} — ${row.artist}", fontWeight = FontWeight.SemiBold)
                                                        Text("(${row.service}) " + DateUtils.getRelativeTimeSpanString(row.ts))
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                // ============== SETTINGS TAB ==============
                // ============== SETTINGS TAB ==============
                2 -> {
                    val scroll = rememberScrollState()
                    Column(
                        Modifier
                            .fillMaxSize()
                            .verticalScroll(scroll)
                            .padding(16.dp)
                    ) {
                        // THEME
                        Text("Theme", style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.height(6.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            @Composable
                            fun radioOption(label: String, value: Int) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.padding(end = 12.dp)
                                ) {
                                    RadioButton(
                                        selected = themeMode == value,
                                        onClick = { onChangeTheme(value) }
                                    )
                                    Text(label)
                                }
                            }
                            radioOption("System", 0)
                            radioOption("Light", 1)
                            radioOption("Dark", 2)
                        }

                        Divider(Modifier.padding(vertical = 16.dp))

                        // PERMISSIONS
                        Button(
                            onClick = { ctx.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) },
                            modifier = Modifier.fillMaxWidth()
                        ) { Text("Grant Notification Access") }

                        Spacer(Modifier.height(12.dp))

                        // DISPLAY NAME
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            OutlinedTextField(
                                value = displayName,
                                onValueChange = { displayName = it },
                                label = { Text("Display name") },
                                modifier = Modifier.weight(1f)
                            )
                            Spacer(Modifier.width(8.dp))
                            Button(onClick = {
                                prefs.displayName = displayName.ifBlank { null }
                                Toast.makeText(ctx, "Saved name", Toast.LENGTH_SHORT).show()
                            }) { Text("Save") }
                        }

                        Spacer(Modifier.height(8.dp))

                        // SHARE + COPY UID
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Share my listening")
                            Spacer(Modifier.width(8.dp))
                            Switch(
                                checked = shareOn,
                                onCheckedChange = {
                                    shareOn = it
                                    prefs.shareEnabled = it
                                }
                            )
                            Spacer(Modifier.width(12.dp))
                            Button(onClick = {
                                val uid = Firebase.auth.currentUser?.uid.orEmpty()
                                val cm = ctx.getSystemService(ClipboardManager::class.java)
                                cm.setPrimaryClip(ClipData.newPlainText("UID", uid))
                                Toast.makeText(ctx, "Copied UID: $uid", Toast.LENGTH_SHORT).show()
                            }) { Text("Copy my UID") }
                        }

                        Spacer(Modifier.height(12.dp))

                        // AUTO-OPEN ON START
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                "Auto-open preferred music app on start",
                                modifier = Modifier.weight(1f)
                            )
                            Switch(
                                checked = autoOpenOnStart,
                                onCheckedChange = { checked ->
                                    autoOpenOnStart = checked
                                    prefs.autoOpenOnStart = checked
                                    Toast.makeText(
                                        ctx,
                                        if (checked) "Will open your music app on launch" else "Auto-open disabled",
                                        Toast.LENGTH_SHORT
                                    ).show()
                                }
                            )
                        }

                        Spacer(Modifier.height(12.dp))

                        // PREFERRED APP PICKER
                        Button(
                            onClick = { showPicker = true },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Choose Preferred Music App")
                        }

                        Spacer(Modifier.height(8.dp))

                        // OPEN PREFERRED APP
                        Button(
                            onClick = {
                                val pkg = prefs.preferredPkg
                                if (pkg == null) {
                                    Toast.makeText(
                                        ctx,
                                        "Choose your music app first",
                                        Toast.LENGTH_SHORT
                                    ).show()
                                } else {
                                    val intent = ctx.packageManager.getLaunchIntentForPackage(pkg)
                                    if (intent == null) {
                                        val url =
                                            "https://play.google.com/store/apps/details?id=$pkg"
                                        ctx.startActivity(Intent(Intent.ACTION_VIEW, url.toUri()))
                                    } else {
                                        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                        ctx.startActivity(intent)
                                        prefs.lastMusicLaunch = System.currentTimeMillis()
                                    }
                                }
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) { Text("Open Preferred App") }
                    }
                }
            }
        }
    }
}