package de.radicalfishgames.crosscode.steam

import android.util.Log
import `in`.dragonbra.javasteam.steam.authentication.AuthSessionDetails
import `in`.dragonbra.javasteam.steam.authentication.IAuthenticator
import `in`.dragonbra.javasteam.steam.authentication.IChallengeUrlChanged
import `in`.dragonbra.javasteam.steam.steamclient.SteamClient
import `in`.dragonbra.javasteam.steam.steamclient.callbackmgr.CallbackManager
import `in`.dragonbra.javasteam.steam.steamclient.callbacks.ConnectedCallback
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

data class SteamAuthResult(val accountName: String, val refreshToken: String, val guardData: String?)

// One-time interactive Steam login: username/password + Steam Guard 2FA -> a refresh token.
// The password is only ever passed to JavaSteam locally on-device; we keep the refresh token
// (callers persist it encrypted). The 2FA code is supplied by `codeProvider`, which the UI
// fulfills (it blocks JavaSteam's poll thread until the user types the code).
class SteamAuth {

    private val client = SteamClient()
    private val manager = CallbackManager(client)
    @Volatile private var pumping = false
    private var pumpThread: Thread? = null

    // status: surfaced to the UI so the user knows what to do (approve in app vs enter a code).
    // codeProvider: only called when Steam requires a typed code (not when mobile approval is used).
    fun login(
        username: String,
        password: String,
        guardData: String?,
        status: (String) -> Unit = {},
        codeProvider: (previousCodeWasIncorrect: Boolean) -> String
    ): SteamAuthResult {
        val connected = CountDownLatch(1)
        manager.subscribe(ConnectedCallback::class.java) { connected.countDown() }

        startPump()
        try {
            client.connect()
            if (!connected.await(15, TimeUnit.SECONDS)) {
                throw RuntimeException("Could not connect to Steam (check your internet connection).")
            }

            val details = AuthSessionDetails().apply {
                this.username = username
                this.password = password
                this.persistentSession = true
                this.guardData = guardData
                this.authenticator = object : IAuthenticator {
                    override fun getDeviceCode(previousCodeWasIncorrect: Boolean): CompletableFuture<String> {
                        Log.i("CrossCode", "[Steam] Steam Guard authenticator code required")
                        status(
                            if (previousCodeWasIncorrect) "That code was wrong — enter your Steam Guard code again."
                            else "Enter the Steam Guard code from your Steam Mobile app's authenticator."
                        )
                        return CompletableFuture.completedFuture(codeProvider(previousCodeWasIncorrect))
                    }

                    override fun getEmailCode(email: String?, previousCodeWasIncorrect: Boolean): CompletableFuture<String> {
                        Log.i("CrossCode", "[Steam] Steam Guard email code sent to $email")
                        status(
                            if (previousCodeWasIncorrect) "That code was wrong — enter the emailed code again."
                            else "Enter the Steam Guard code emailed to ${email ?: "your address"}."
                        )
                        return CompletableFuture.completedFuture(codeProvider(previousCodeWasIncorrect))
                    }

                    // Preferred path when the account has the Steam Mobile authenticator: Steam pushes
                    // an approval prompt to the app and we poll until the user taps Approve (no code needed).
                    override fun acceptDeviceConfirmation(): CompletableFuture<Boolean> {
                        Log.i("CrossCode", "[Steam] waiting for Steam mobile-app approval")
                        status("Open your Steam Mobile app and tap APPROVE for the login request…")
                        return CompletableFuture.completedFuture(true)
                    }
                }
            }

            val session = client.authentication.beginAuthSessionViaCredentials(details).get()
            val poll = session.pollingWaitForResult().get()

            Log.i("CrossCode", "[Steam] Authenticated as '${poll.accountName}'")
            return SteamAuthResult(poll.accountName, poll.refreshToken, poll.newGuardData ?: guardData)
        } finally {
            try { client.disconnect() } catch (_: Throwable) {}
            stopPump()
        }
    }

    // QR login: Steam returns a challenge URL we render as a QR code; the user scans it with the
    // Steam Mobile app and approves. `onChallengeUrl` is called with the initial URL and again each
    // time Steam rotates it. Blocks until the user approves (or abort() breaks the connection).
    fun loginViaQr(
        onChallengeUrl: (String) -> Unit,
        status: (String) -> Unit = {}
    ): SteamAuthResult {
        val connected = CountDownLatch(1)
        manager.subscribe(ConnectedCallback::class.java) { connected.countDown() }

        startPump()
        try {
            client.connect()
            if (!connected.await(15, TimeUnit.SECONDS)) {
                throw RuntimeException("Could not connect to Steam (check your internet connection).")
            }

            val details = AuthSessionDetails().apply {
                persistentSession = true
                deviceFriendlyName = "CrossCode (Android)"
                // QR is approved in the mobile app, so these are never prompted — provide a no-op.
                authenticator = object : IAuthenticator {
                    override fun getDeviceCode(p: Boolean) = CompletableFuture.completedFuture("")
                    override fun getEmailCode(e: String?, p: Boolean) = CompletableFuture.completedFuture("")
                    override fun acceptDeviceConfirmation() = CompletableFuture.completedFuture(true)
                }
            }

            val session = client.authentication.beginAuthSessionViaQR(details).get()
            onChallengeUrl(session.challengeUrl)
            status("Scan this code with your Steam Mobile app, then tap Approve.")
            session.challengeUrlChanged = IChallengeUrlChanged { s -> s?.let { onChallengeUrl(it.challengeUrl) } }

            val poll = session.pollingWaitForResult().get()
            Log.i("CrossCode", "[Steam] QR authenticated as '${poll.accountName}'")
            return SteamAuthResult(poll.accountName, poll.refreshToken, poll.newGuardData)
        } finally {
            try { client.disconnect() } catch (_: Throwable) {}
            stopPump()
        }
    }

    // Break a pending login (e.g. the user cancels the QR dialog): disconnecting makes the blocking
    // poll throw, so the caller's thread unwinds.
    fun abort() {
        try { client.disconnect() } catch (_: Throwable) {}
        stopPump()
    }

    private fun startPump() {
        pumping = true
        pumpThread = Thread {
            while (pumping) {
                manager.runWaitCallbacks(1000)
            }
        }.apply { isDaemon = true; name = "SteamAuthCallbacks"; start() }
    }

    private fun stopPump() {
        pumping = false
        pumpThread?.join(2000)
        pumpThread = null
    }
}
