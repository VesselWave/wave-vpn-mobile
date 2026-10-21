// SPDX-License-Identifier: GPL-3.0-or-later
package me.vwave.vpn.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.json.JSONObject

sealed interface Target {
    data object Best : Target
    data class Country(val code: String) : Target
    data class Server(val name: String) : Target
}
enum class SplitMode { Off, Exclude, Include }
data class Settings(val target: Target = Target.Best, val deviceKey: String = "", val port: Int = 1637,
    val ipv6Endpoint: Boolean = false, val splitMode: SplitMode = SplitMode.Off,
    val splitApps: Set<String> = emptySet(), val favorites: Set<String> = emptySet(),
    val unitBits: Boolean = false, val unitIec: Boolean = false)

class SettingsStore(context: Context) {
    private val prefs = context.getSharedPreferences("wave-settings-v2", Context.MODE_PRIVATE)
    private val state = MutableStateFlow(read())
    val settings = state.asStateFlow()
    val current get() = state.value
    @Synchronized fun update(change: (Settings) -> Settings) {
        val s = change(current)
        val target = when (val t = s.target) {
            Target.Best -> JSONObject().put("type", "best")
            is Target.Country -> JSONObject().put("type", "country").put("value", t.code)
            is Target.Server -> JSONObject().put("type", "server").put("value", t.name)
        }
        val j = JSONObject().put("target", target).put("device", s.deviceKey).put("port", s.port)
            .put("ipv6", s.ipv6Endpoint).put("splitMode", s.splitMode.name)
            .put("apps", org.json.JSONArray(s.splitApps.toList())).put("favorites", org.json.JSONArray(s.favorites.toList()))
            .put("bits", s.unitBits).put("iec", s.unitIec)
        check(prefs.edit().putString("settings", j.toString()).commit()) { "Unable to save settings" }
        state.value = s
    }
    fun clear() { prefs.edit().clear().commit(); state.value = Settings() }
    private fun read(): Settings = runCatching {
        val j = JSONObject(prefs.getString("settings", "{}")!!)
        val t = j.optJSONObject("target") ?: JSONObject()
        fun strings(name: String): Set<String> = j.optJSONArray(name)?.let { a ->
            (0 until a.length()).map { a.getString(it) }.toSet()
        }.orEmpty()
        Settings(when(t.optString("type")) {
            "country" -> Target.Country(t.getString("value"))
            "server" -> Target.Server(t.getString("value"))
            else -> Target.Best
        }, j.optString("device"), j.optInt("port", 1637), j.optBoolean("ipv6"),
            runCatching { SplitMode.valueOf(j.optString("splitMode")) }.getOrDefault(SplitMode.Off),
            strings("apps"), strings("favorites"), j.optBoolean("bits"), j.optBoolean("iec"))
    }.getOrDefault(Settings())
}

enum class LogLevel { Debug, Info, Warn, Error }
data class LogEntry(val timeMillis: Long, val level: LogLevel, val message: String)
class EventLog {
    private val state = MutableStateFlow<List<LogEntry>>(emptyList())
    val entries = state.asStateFlow()
    fun add(level: LogLevel, text: String) { state.update { (it + LogEntry(System.currentTimeMillis(), level, text)).takeLast(500) } }
    fun info(text: String) = add(LogLevel.Info, text)
    fun warn(text: String) = add(LogLevel.Warn, text)
    fun error(text: String) = add(LogLevel.Error, text)
    fun clear() { state.value = emptyList() }
}
