package de.radicalfishgames.crosscode.steam

import android.util.Log
import `in`.dragonbra.javasteam.steam.steamclient.SteamClient

// One-off runtime check that JavaSteam (+ its Ktor/OkHttp/coroutines/protobuf stack) actually
// loads and initializes on the Android runtime — not just that it dexed. Constructing a
// SteamClient builds the whole handler/CM-client object graph without connecting to the network.
object SteamSmokeTest {
    fun run(): String {
        return try {
            val client = SteamClient()
            "JavaSteam runtime OK: " + client.javaClass.name
        } catch (t: Throwable) {
            Log.e("CrossCode", "[Steam] smoke test threw", t)
            "JavaSteam runtime FAIL: " + t.javaClass.name + ": " + t.message
        }
    }
}
