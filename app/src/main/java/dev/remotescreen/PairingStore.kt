package dev.remotescreen

import android.content.Context
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject

data class PairedReceiver(
    val fingerprint: String,
    val name: String,
    val streamKey: ByteArray,
    val pairedAt: Long
)

/** Receivers that survived the pairing handshake, plus the unattended toggle. */
object PairingStore {

    private const val PREFS = "remote_screen"
    private const val KEY_PEERS = "peers"
    private const val KEY_UNATTENDED = "unattended"

    fun peers(ctx: Context): MutableList<PairedReceiver> {
        val blob = prefs(ctx).getString(KEY_PEERS, null) ?: return mutableListOf()
        val json = SecretStore.open(blob)?.toString(Charsets.UTF_8) ?: return mutableListOf()
        return try {
            val arr = JSONArray(json)
            MutableList(arr.length()) { i ->
                val o = arr.getJSONObject(i)
                PairedReceiver(
                    fingerprint = o.getString("fp"),
                    name = o.optString("name", "receiver"),
                    streamKey = Base64.decode(o.getString("key"), Base64.NO_WRAP),
                    pairedAt = o.optLong("at", 0L)
                )
            }
        } catch (e: Exception) {
            mutableListOf()
        }
    }

    fun find(ctx: Context, fingerprint: String): PairedReceiver? =
        peers(ctx).firstOrNull { it.fingerprint == fingerprint }

    fun add(ctx: Context, peer: PairedReceiver) {
        val all = peers(ctx).filterNot { it.fingerprint == peer.fingerprint }.toMutableList()
        all.add(peer)
        write(ctx, all)
    }

    fun remove(ctx: Context, fingerprint: String) {
        write(ctx, peers(ctx).filterNot { it.fingerprint == fingerprint }.toMutableList())
    }

    fun unattended(ctx: Context): Boolean = prefs(ctx).getBoolean(KEY_UNATTENDED, false)

    fun setUnattended(ctx: Context, enabled: Boolean) {
        prefs(ctx).edit().putBoolean(KEY_UNATTENDED, enabled).apply()
    }

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun write(ctx: Context, list: List<PairedReceiver>) {
        val arr = JSONArray()
        for (p in list) {
            arr.put(
                JSONObject()
                    .put("fp", p.fingerprint)
                    .put("name", p.name)
                    .put("key", Base64.encodeToString(p.streamKey, Base64.NO_WRAP))
                    .put("at", p.pairedAt)
            )
        }
        prefs(ctx).edit()
            .putString(KEY_PEERS, SecretStore.seal(arr.toString().toByteArray(Charsets.UTF_8)))
            .apply()
    }
}
