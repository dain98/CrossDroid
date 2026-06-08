package de.radicalfishgames.crosscode

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.BitmapDrawable
import android.os.Bundle
import android.text.InputType
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.util.Log
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.imageview.ShapeableImageView
import com.google.android.material.textfield.TextInputEditText
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import de.radicalfishgames.crosscode.steam.SteamAuth
import de.radicalfishgames.crosscode.steam.SteamCloudSync
import de.radicalfishgames.crosscode.steam.SteamDepotDownloader
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.CountDownLatch

// The launcher home screen. Two states in one screen:
//   • signed out  -> username + password + "Sign in with Steam" + "Sign in with QR code"
//   • signed in   -> Steam avatar + "Signed in as <you>" + a big PLAY button
// PLAY pulls the latest Steam Cloud save and then launches the game; the game pushes back to the
// cloud on quit (see GameActivity.quitWithCloudSync). The whole screen is controller-navigable
// (D-pad to move, A to activate). The password only ever goes to JavaSteam locally on-device.
// NOTE: the refresh token is stored in plain prefs for this build; encrypt via Keystore before release.
class SteamLoginActivity : AppCompatActivity() {

    private lateinit var signedOutGroup: View
    private lateinit var signedInGroup: View
    private lateinit var username: TextInputEditText
    private lateinit var password: TextInputEditText
    private lateinit var accountLine: TextView
    private lateinit var status: TextView
    private lateinit var progress: ProgressBar
    private lateinit var downloadBar: ProgressBar
    private lateinit var signInButton: MaterialButton
    private lateinit var qrButton: MaterialButton
    private lateinit var playButton: MaterialButton
    private lateinit var offlineToggle: CheckBox
    private lateinit var avatar: ShapeableImageView

    @Volatile private var profileRefreshing = false

    private val prefs get() = getSharedPreferences("steam", MODE_PRIVATE)
    private val avatarFile get() = File(getExternalFilesDir(null), "profile/avatar.png")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_steam_login)

        // Blend the system bars into the gradient so the whole screen reads as one dark surface.
        window.statusBarColor = ContextCompat.getColor(this, R.color.launcher_bg_top)
        window.navigationBarColor = ContextCompat.getColor(this, R.color.launcher_bg_bottom)

        signedOutGroup = findViewById(R.id.signed_out_group)
        signedInGroup = findViewById(R.id.signed_in_group)
        username = findViewById(R.id.username)
        password = findViewById(R.id.password)
        accountLine = findViewById(R.id.account_line)
        status = findViewById(R.id.status)
        progress = findViewById(R.id.progress)
        downloadBar = findViewById(R.id.download_bar)
        signInButton = findViewById(R.id.sign_in_button)
        qrButton = findViewById(R.id.qr_button)
        playButton = findViewById(R.id.play_button)
        offlineToggle = findViewById(R.id.offline_toggle)
        avatar = findViewById(R.id.avatar)

        // Keep the pixel-art logo crisp instead of bilinear-smeared when scaled up.
        (findViewById<ImageView>(R.id.logo).drawable as? BitmapDrawable)?.isFilterBitmap = false

        signInButton.setOnClickListener { signIn() }
        qrButton.setOnClickListener { loginWithQr() }
        playButton.setOnClickListener { play() }
        offlineToggle.setOnCheckedChangeListener { _, checked ->
            prefs.edit().putBoolean("playOffline", checked).apply()
            updatePlayLabel()
        }
        findViewById<MaterialButton>(R.id.sign_out_button).setOnClickListener { signOut() }

        renderState()
        if (prefs.getString("token", null) == null) {
            setStatus("Sign in with your Steam account to play with your cloud save.")
        }
    }

    // Show the right half of the UI for the current login state, set initial controller focus.
    private fun renderState() {
        val account = prefs.getString("account", null)
        val token = prefs.getString("token", null)
        if (account != null && token != null) {
            signedOutGroup.visibility = View.GONE
            signedInGroup.visibility = View.VISIBLE
            offlineToggle.isChecked = playOffline
            updatePlayLabel()
            refreshAccountLine()
            loadAvatar()
            refreshProfileIfNeeded()
            playButton.requestFocus()
        } else {
            signedInGroup.visibility = View.GONE
            signedOutGroup.visibility = View.VISIBLE
            avatar.visibility = View.GONE
            if (username.text.isNullOrEmpty()) username.setText(account ?: "")
            qrButton.requestFocus()
        }
    }

    private fun refreshAccountLine() {
        val name = prefs.getString("persona", null) ?: prefs.getString("account", null) ?: return
        accountLine.text = signedInLabel(name)
    }

    // "● Signed in as <name>" — green dot, name in bold white.
    private fun signedInLabel(name: String): CharSequence {
        val s = SpannableStringBuilder()
        val dotEnd = s.append("● ").length
        s.setSpan(ForegroundColorSpan(ContextCompat.getColor(this, R.color.online_green)),
            0, dotEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        s.append("Signed in as ")
        val nameStart = s.length
        s.append(name)
        s.setSpan(StyleSpan(Typeface.BOLD), nameStart, s.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        s.setSpan(ForegroundColorSpan(ContextCompat.getColor(this, R.color.text_primary)),
            nameStart, s.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        return s
    }

    private fun loadAvatar() {
        val bmp = if (avatarFile.exists()) BitmapFactory.decodeFile(avatarFile.absolutePath) else null
        if (bmp != null) { avatar.setImageBitmap(bmp); avatar.visibility = View.VISIBLE }
        else avatar.visibility = View.GONE
    }

    // Manual "Play Offline" toggle: when on, PLAY skips the Steam round-trip and launches the local
    // save instantly. Saves still reconcile via lastSyncedSha the next time you PLAY online.
    private val playOffline: Boolean get() = prefs.getBoolean("playOffline", false)

    private fun updatePlayLabel() {
        if (playOffline) {
            playButton.text = "▶  PLAY OFFLINE"
            findViewById<TextView>(R.id.play_caption).text = "Plays your local save · no cloud sync"
        } else {
            playButton.text = "▶  PLAY"
            findViewById<TextView>(R.id.play_caption).text = "Pulls your latest save · uploads to Steam Cloud on quit"
        }
    }

    // For an already-signed-in user with no cached avatar, fetch it quietly in the background.
    private fun refreshProfileIfNeeded() {
        if (avatarFile.exists() || profileRefreshing) return
        val account = prefs.getString("account", null) ?: return
        val token = prefs.getString("token", null) ?: return
        profileRefreshing = true
        Thread {
            try {
                val sync = SteamCloudSync(account, token)
                try {
                    if (sync.connect() == SteamCloudSync.ConnectResult.OK) {
                        sync.fetchProfile()?.let { p ->
                            prefs.edit().putString("persona", p.personaName)
                                .putString("avatarHash", p.avatarHashHex).apply()
                            if (downloadAvatar(p.avatarHashHex)) runOnUiThread { loadAvatar(); refreshAccountLine() }
                        }
                    }
                } finally { sync.disconnect() }
            } catch (t: Throwable) {
                Log.e("CrossCode", "[Steam] profile refresh failed", t)
            } finally { profileRefreshing = false }
        }.apply { isDaemon = true; name = "SteamProfile"; start() }
    }

    private fun signIn() {
        val u = username.text.toString().trim()
        val p = password.text.toString()
        if (u.isEmpty() || p.isEmpty()) { setStatus("Enter your Steam username and password."); return }
        setBusy(true, "Connecting to Steam…")
        Thread {
            try {
                val auth = SteamAuth()
                val result = auth.login(u, p, prefs.getString("guard", null), status = { setStatus(it) }) { wrong ->
                    promptForGuardCode(wrong)
                }
                storeLogin(result.accountName, result.refreshToken, result.guardData)
                runOnUiThread { password.setText(""); renderState() }
                setStatus("Signed in as ${result.accountName}. Syncing your cloud save…")
                setStatus(pullCloudSave(result.accountName, result.refreshToken))
            } catch (t: Throwable) {
                Log.e("CrossCode", "[Steam] login/sync failed", t)
                setStatus("Sign-in failed: ${t.message ?: t.javaClass.simpleName}")
            } finally {
                setBusy(false)
            }
        }.apply { isDaemon = true; name = "SteamLogin"; start() }
    }

    // QR login: render Steam's challenge URL as a QR the user scans with the Steam Mobile app.
    private fun loginWithQr() {
        val d = resources.displayMetrics.density
        fun dp(v: Int) = (v * d).toInt()
        val sizePx = dp(168)

        // Landscape-friendly: instructions on the left, QR on the right.
        val heading = TextView(this).apply {
            text = "Sign in with QR"
            textSize = 18f
            setTypeface(typeface, Typeface.BOLD)
        }
        val instructions = TextView(this).apply {
            text = "In the Steam Mobile app, tap the QR-scan icon, scan this code, then tap Approve."
            setPadding(0, dp(10), 0, 0)
        }
        val qrStatus = TextView(this).apply {
            text = "Connecting to Steam…"
            setPadding(0, dp(14), 0, 0)
        }
        val left = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(dp(232), LinearLayout.LayoutParams.WRAP_CONTENT)
            gravity = Gravity.CENTER_VERTICAL
            addView(heading); addView(instructions); addView(qrStatus)
        }
        val qrView = ImageView(this).apply {
            setBackgroundColor(Color.WHITE)
            setPadding(dp(6), dp(6), dp(6), dp(6))
        }
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(24), dp(18), dp(24), dp(18))
            addView(left)
            addView(qrView, LinearLayout.LayoutParams(sizePx, sizePx).apply { leftMargin = dp(20) })
        }

        val auth = SteamAuth()
        val aborted = java.util.concurrent.atomic.AtomicBoolean(false)
        val dialog = AlertDialog.Builder(this)
            .setView(container)
            .setNegativeButton("Cancel") { _, _ -> aborted.set(true); auth.abort() }
            .setOnCancelListener { aborted.set(true); auth.abort() }
            .show()

        Thread {
            try {
                val result = auth.loginViaQr(
                    onChallengeUrl = { url ->
                        val bmp = runCatching { qrBitmap(url, sizePx) }.getOrNull()
                        runOnUiThread {
                            bmp?.let { qrView.setImageBitmap(it) }
                            qrStatus.text = "Scan with your Steam Mobile app, then tap Approve."
                        }
                    },
                    status = { s -> runOnUiThread { qrStatus.text = s } }
                )
                storeLogin(result.accountName, result.refreshToken, result.guardData)
                runOnUiThread {
                    runCatching { dialog.dismiss() }
                    renderState()
                    setStatus("Signed in as ${result.accountName}. Syncing your cloud save…")
                }
                setStatus(pullCloudSave(result.accountName, result.refreshToken))
            } catch (t: Throwable) {
                if (!aborted.get()) {
                    Log.e("CrossCode", "[Steam] QR login failed", t)
                    runOnUiThread {
                        runCatching { dialog.dismiss() }
                        setStatus("QR sign-in failed: ${t.message ?: t.javaClass.simpleName}")
                    }
                }
            }
        }.apply { isDaemon = true; name = "SteamQrLogin"; start() }
    }

    private fun qrBitmap(text: String, size: Int): Bitmap {
        val hints = hashMapOf(EncodeHintType.MARGIN to 1)
        val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size, hints)
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.RGB_565)
        for (x in 0 until size) for (y in 0 until size) {
            bmp.setPixel(x, y, if (matrix.get(x, y)) Color.BLACK else Color.WHITE)
        }
        return bmp
    }

    // Sync the save, then launch. Version-aware and non-destructive:
    //   • offline (can't reach Steam) -> play the LOCAL save, keep the saved login
    //   • only the cloud changed       -> pull it (prior local kept as .bak)
    //   • only this device changed      -> push it up, keep playing local
    //   • both changed since last sync  -> ASK which to use (the other is backed up)
    // Only an actual auth rejection clears the token.
    private fun play() {
        val account = prefs.getString("account", null)
        val token = prefs.getString("token", null)
        if (account == null || token == null) { renderState(); return }
        if (!gameFilesPresent()) {
            downloadCrossCode(account, token)
            return
        }
        if (playOffline) {
            // Deliberate offline: skip the cloud round-trip and launch the local save now.
            // lastSyncedSha reconciles everything the next time you PLAY with the toggle off.
            prefs.edit().putBoolean("sessionOffline", true).apply()   // exit won't try the cloud either
            setStatus("Playing offline — your progress syncs next time you play online.")
            launchGame()
            return
        }

        setBusy(true, "Syncing your save…")
        Thread {
            val dest = File(getExternalFilesDir(null), "cloudsave/cc.save")
            val localSha = SteamCloudSync.sha1Hex(dest)
            var launch = false
            var expired = false
            var conflict: ConflictInfo? = null
            var msg = ""
            val sync = SteamCloudSync(account, token)
            try {
                when (sync.connect()) {
                    SteamCloudSync.ConnectResult.NETWORK_FAILED -> {
                        prefs.edit().putBoolean("sessionOffline", true).apply()   // exit skips the cloud attempt
                        if (localSha != null) { msg = "Offline — playing your local save."; launch = true }
                        else msg = "Can't reach Steam and there's no save on this device yet. Connect to the internet and try again."
                    }
                    SteamCloudSync.ConnectResult.AUTH_FAILED -> {
                        expired = true; msg = "Your Steam login expired. Please sign in again."
                    }
                    SteamCloudSync.ConnectResult.OK -> {
                        prefs.edit().putBoolean("sessionOffline", false).apply()  // online this session
                        val cloud = sync.findSave()
                        when {
                            cloud == null -> {
                                msg = if (localSha != null) "No cloud save yet — playing your local save."
                                      else "No cloud save yet — starting fresh."
                                launch = true
                            }
                            localSha == cloud.shaHex -> {                       // already identical
                                lastSyncedSha = localSha; msg = "Save is up to date — launching…"; launch = true
                            }
                            localSha == null -> {                               // nothing local -> take cloud
                                if (sync.pull(cloud.name, dest)) {
                                    lastSyncedSha = SteamCloudSync.sha1Hex(dest); msg = "Cloud save downloaded — launching…"; launch = true
                                } else msg = "Couldn't download your cloud save. Try again."
                            }
                            else -> {
                                val base = lastSyncedSha
                                val localChanged = localSha != base
                                val cloudChanged = cloud.shaHex != base
                                when {
                                    !localChanged && cloudChanged -> {          // cloud moved on -> pull
                                        if (sync.pull(cloud.name, dest)) {
                                            lastSyncedSha = SteamCloudSync.sha1Hex(dest); msg = "Updated from Steam Cloud — launching…"; launch = true
                                        } else msg = "Couldn't download your cloud save. Try again."
                                    }
                                    localChanged && !cloudChanged -> {          // local moved on (e.g. offline progress) -> push
                                        msg = when (sync.push(cloud.name, dest)) {
                                            SteamCloudSync.PushResult.UPLOADED, SteamCloudSync.PushResult.UNCHANGED -> {
                                                lastSyncedSha = localSha; "Synced your progress to Steam Cloud — launching…"
                                            }
                                            SteamCloudSync.PushResult.REFUSED_SMALLER -> "Kept your cloud save (it looks more complete) — playing your local save."
                                            SteamCloudSync.PushResult.FAILED -> "Couldn't upload right now — playing local, will retry on quit."
                                        }
                                        launch = true
                                    }
                                    else -> {                                   // both changed (or no baseline) -> ask
                                        conflict = ConflictInfo(account, token, cloud.name, cloud.timestampMillis, dest.lastModified())
                                    }
                                }
                            }
                        }
                    }
                }
            } catch (t: Throwable) {
                Log.e("CrossCode", "[Steam] play-sync failed", t)
                if (localSha != null) { msg = "Couldn't sync — playing your local save."; launch = true }
                else msg = "Couldn't sync: ${t.message ?: t.javaClass.simpleName}"
            } finally {
                sync.disconnect()
            }
            runOnUiThread {
                if (conflict != null) { setBusy(false); showConflictDialog(conflict!!); return@runOnUiThread }
                setBusy(false, msg)
                if (expired) { prefs.edit().remove("token").apply(); renderState() }
                if (launch) launchGame()
            }
        }.apply { isDaemon = true; name = "SteamPlay"; start() }
    }

    // SHA-1 of the save the cloud and this device last agreed on — lets us tell who changed since.
    private var lastSyncedSha: String?
        get() = prefs.getString("lastSyncedSha", null)
        set(v) { prefs.edit().putString("lastSyncedSha", v).apply() }

    private data class ConflictInfo(val account: String, val token: String, val cloudName: String,
                                    val cloudTs: Long, val localTs: Long)

    // Both the cloud save and this device's save changed since they last matched. Never auto-pick —
    // ask, and keep a backup of whichever isn't chosen so nothing is lost.
    private fun showConflictDialog(c: ConflictInfo) {
        val newer = if (c.cloudTs >= c.localTs) "the cloud save looks newer" else "this device's save looks newer"
        AlertDialog.Builder(this)
            .setTitle("Which save do you want?")
            .setMessage("Your save here and your Steam Cloud save have both changed since they last matched " +
                "($newer). Pick one to play — the other is backed up, not deleted.")
            .setCancelable(false)
            .setPositiveButton("This device's save") { _, _ -> resolveConflict(c, useLocal = true) }
            .setNegativeButton("Cloud save") { _, _ -> resolveConflict(c, useLocal = false) }
            .show()
    }

    private fun resolveConflict(c: ConflictInfo, useLocal: Boolean) {
        setBusy(true, if (useLocal) "Uploading your save…" else "Downloading your cloud save…")
        Thread {
            val dest = File(getExternalFilesDir(null), "cloudsave/cc.save")
            var msg: String; var launch = false
            val sync = SteamCloudSync(c.account, c.token)
            try {
                if (sync.connect() == SteamCloudSync.ConnectResult.OK) {
                    if (useLocal) {
                        // stash the cloud save aside, then push the local one up
                        runCatching { sync.pull(c.cloudName, File(dest.parentFile, "cc.save.cloud.bak")) }
                        msg = when (sync.push(c.cloudName, dest)) {
                            SteamCloudSync.PushResult.UPLOADED, SteamCloudSync.PushResult.UNCHANGED -> {
                                lastSyncedSha = SteamCloudSync.sha1Hex(dest); "Using this device's save (cloud backed up)."
                            }
                            SteamCloudSync.PushResult.REFUSED_SMALLER -> "This device's save is much smaller — kept the cloud save to be safe. Playing local."
                            SteamCloudSync.PushResult.FAILED -> "Upload failed — playing local, will retry on quit."
                        }
                        launch = true
                    } else {
                        if (sync.pull(c.cloudName, dest)) {     // pull() keeps the local save as .bak first
                            lastSyncedSha = SteamCloudSync.sha1Hex(dest); msg = "Using your cloud save (local backed up)."; launch = true
                        } else msg = "Couldn't download the cloud save. Try again."
                    }
                } else {
                    msg = "Couldn't reach Steam — playing your local save."
                    launch = dest.exists() && dest.length() > 0
                }
            } catch (t: Throwable) {
                Log.e("CrossCode", "[Steam] conflict resolve failed", t)
                msg = "Sync error — playing your local save."
                launch = dest.exists() && dest.length() > 0
            } finally { sync.disconnect() }
            runOnUiThread { setBusy(false, msg); if (launch) launchGame() }
        }.apply { isDaemon = true; name = "SteamConflict"; start() }
    }

    // First run: download CrossCode's web game from the Steam CDN, extract the bundled mod loader,
    // then launch. Resumable + auto-retrying inside the downloader.
    private fun downloadCrossCode(account: String, token: String) {
        setBusy(true, "Connecting to Steam to download CrossCode…")
        runOnUiThread { downloadBar.progress = 0; downloadBar.visibility = View.VISIBLE }
        Thread {
            val ccDir = File(getExternalFilesDir(null), "CrossCode")
            val lastPct = java.util.concurrent.atomic.AtomicInteger(-1)
            var ok = false
            try {
                val dl = SteamDepotDownloader(account, token)
                try {
                    if (dl.connect()) {
                        ok = dl.download(
                            ccDir,
                            onProgress = { p ->
                                if (p.percent != lastPct.getAndSet(p.percent)) runOnUiThread {
                                    downloadBar.progress = p.percent
                                    status.text = "Downloading CrossCode… ${p.percent}%  (${p.completedFiles}/${p.totalFiles})"
                                }
                            },
                            log = { Log.i("CrossCode", "[Depot] $it") }
                        )
                    } else setStatus("Couldn't reach Steam to download CrossCode.")
                } finally {
                    dl.disconnect()
                }
            } catch (t: Throwable) {
                Log.e("CrossCode", "[Depot] download failed", t)
                setStatus("Download failed: ${t.message ?: t.javaClass.simpleName}")
            }

            if (ok) {
                setStatus("Installing mod loader…")
                ok = try {
                    provisionMods(ccDir); true
                } catch (t: Throwable) {
                    Log.e("CrossCode", "[Depot] mod provision failed", t)
                    setStatus("Mod setup failed: ${t.message ?: t.javaClass.simpleName}"); false
                }
            }

            runOnUiThread {
                downloadBar.visibility = View.GONE
                setBusy(false)
                if (ok) { setStatus("CrossCode installed!"); play() }
            }
        }.apply { isDaemon = true; name = "SteamDownload"; start() }
    }

    // Extract the bundled mod loader + cc-font-fix (assets/mods.zip) into the game dir.
    private fun provisionMods(ccDir: File) {
        val root = ccDir.canonicalPath
        assets.open("mods.zip").use { ins ->
            java.util.zip.ZipInputStream(ins).use { zis ->
                var entry = zis.nextEntry
                while (entry != null) {
                    val out = File(ccDir, entry.name)
                    if (out.canonicalPath.startsWith(root)) {          // zip-slip guard
                        if (entry.isDirectory) out.mkdirs()
                        else { out.parentFile?.mkdirs(); out.outputStream().use { zis.copyTo(it) } }
                    }
                    zis.closeEntry()
                    entry = zis.nextEntry
                }
            }
        }
        Log.i("CrossCode", "[Depot] mod loader provisioned")
    }

    private fun signOut() {
        prefs.edit().remove("account").remove("token").remove("guard")
            .remove("persona").remove("avatarHash").apply()
        runCatching { avatarFile.delete() }
        username.setText(""); password.setText("")
        renderState()
        setStatus("Signed out.")
    }

    private fun storeLogin(account: String, token: String, guard: String?) {
        prefs.edit().putString("account", account).putString("token", token).putString("guard", guard).apply()
    }

    private fun launchGame() {
        startActivity(Intent(this, GameActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
    }

    // "Installed" = game assets AND a mod-loader entry. A partial depot download has assets/ but no
    // ccloader (mods are extracted only after the full download), so this won't mistake it for ready.
    private fun gameFilesPresent(): Boolean {
        val dir = getExternalFilesDir(null) ?: return false
        val cc = File(dir, "CrossCode")
        val hasAssets = File(cc, "assets").exists()
        val hasLoader = File(cc, "ccloader/main.html").exists() ||
            File(cc, "ccloader/index.html").exists() ||
            File(cc, "ccloader3/main.html").exists()
        return hasAssets && hasLoader
    }

    // Connect with a refresh token: fetch the profile (name + avatar) and download the cloud cc.save.
    private fun pullCloudSave(account: String, token: String): String {
        val sync = SteamCloudSync(account, token)
        try {
            when (sync.connect()) {
                SteamCloudSync.ConnectResult.NETWORK_FAILED ->
                    return "Signed in. (Offline — your cloud save will sync once you're back online.)"
                SteamCloudSync.ConnectResult.AUTH_FAILED ->
                    return "Signed in, but Steam rejected the session. Try signing in again."
                SteamCloudSync.ConnectResult.OK -> { /* continue below */ }
            }
            runCatching {
                sync.fetchProfile()?.let { p ->
                    prefs.edit().putString("persona", p.personaName)
                        .putString("avatarHash", p.avatarHashHex).apply()
                    if (downloadAvatar(p.avatarHashHex)) runOnUiThread { loadAvatar(); refreshAccountLine() }
                }
            }
            val cloud = sync.findSave()
                ?: return "Signed in. No cloud save found yet — you're ready to play."
            val dest = File(getExternalFilesDir(null), "cloudsave/cc.save")
            return if (sync.pull(cloud.name, dest)) {
                lastSyncedSha = SteamCloudSync.sha1Hex(dest)
                "Cloud save downloaded. Ready to play!"
            } else "Signed in, but the save download failed. Tap PLAY to retry."
        } finally {
            sync.disconnect()
        }
    }

    // Download the Steam avatar JPG for this hash into profile/avatar.png. A blank/all-zero hash
    // means the user has Steam's default avatar.
    private fun downloadAvatar(hashHex: String?): Boolean {
        val hex = hashHex?.takeIf { it.length == 40 && it.any { c -> c != '0' } } ?: DEFAULT_AVATAR
        return try {
            val conn = URL("https://avatars.steamstatic.com/${hex}_full.jpg").openConnection() as HttpURLConnection
            conn.connectTimeout = 15_000; conn.readTimeout = 15_000
            val bytes = conn.inputStream.use { it.readBytes() }
            conn.disconnect()
            avatarFile.parentFile?.mkdirs()
            avatarFile.writeBytes(bytes)
            Log.i("CrossCode", "[Steam] avatar saved (${bytes.size} bytes) hash=$hex")
            true
        } catch (t: Throwable) {
            Log.e("CrossCode", "[Steam] avatar download failed", t); false
        }
    }

    private fun setStatus(text: String) = runOnUiThread { status.text = text }

    private fun setBusy(busy: Boolean, msg: String = "") = runOnUiThread {
        progress.visibility = if (busy) View.VISIBLE else View.GONE
        playButton.isEnabled = !busy
        signInButton.isEnabled = !busy
        qrButton.isEnabled = !busy
        if (msg.isNotEmpty()) status.text = msg
    }

    // Controller A button activates the focused control (D-pad/Center/Enter already do).
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BUTTON_A) {
            currentFocus?.let { it.performClick(); return true }
        }
        return super.onKeyDown(keyCode, event)
    }

    // Blocks the caller (Steam poll thread) until the user enters their Steam Guard code.
    private fun promptForGuardCode(previousWasWrong: Boolean): String {
        val latch = CountDownLatch(1)
        var code = ""
        runOnUiThread {
            val input = EditText(this).apply { inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_CLASS_TEXT }
            AlertDialog.Builder(this)
                .setTitle(if (previousWasWrong) "Code incorrect — try again" else "Steam Guard code")
                .setMessage("Enter the code from your Steam Mobile app or email (or approve in the app).")
                .setView(input)
                .setCancelable(false)
                .setPositiveButton("OK") { _, _ -> code = input.text.toString().trim(); latch.countDown() }
                .show()
        }
        latch.await()
        return code
    }

    companion object {
        // Steam's default (no custom avatar) image hash.
        private const val DEFAULT_AVATAR = "fef49e7fa7e1997310d705b2a6158ff8dc1cdfeb"
    }
}
