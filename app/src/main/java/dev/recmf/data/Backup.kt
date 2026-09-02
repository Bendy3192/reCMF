/*
 * reCMF — Copyright (C) 2026 reCMF contributors. AGPL-3.0-or-later.
 */
package dev.recmf.data

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/**
 * Everything reCMF holds, in a file somebody can carry to their next phone.
 *
 * ## What travels, and what deliberately does not
 *
 * Two things are left behind, and for different reasons.
 *
 * The **pairing key** is a secret, and this app already decided what to do with secrets:
 * [SecretVault] wraps it in a key held by the Android Keystore that never leaves the
 * device and is not itself backed up. Exporting the sealed blob would move a thing nothing
 * on the other phone could open. Exporting it unsealed would put the one value that
 * decrypts a watch's entire traffic into a file destined for somebody's cloud drive. So it
 * stays.
 *
 * The **watch's address and name** stay with it. Restoring those without the key would
 * leave the app showing a paired watch it has no way of talking to, which is a worse
 * starting position than an honest empty one. Re-pair on the new phone and everything
 * below — every setting, every alarm, the blocked-app list, the goals, the history — is
 * already where it was.
 *
 * Anything added to settings later travels automatically. The store is walked rather than
 * enumerated, because a list of keys written out by hand is a list that silently stops
 * being complete the first time somebody adds a setting and forgets this file.
 *
 * ## Why JSON, and why it is not compressed
 *
 * A backup nobody can open is a backup nobody can check. The retention rules cap what
 * there is to carry — a week of samples, a month of nights — so this stays comfortably
 * small enough that being readable is worth more than being tidy.
 *
 * Tables declare their columns and then give rows as bare arrays: self-describing without
 * repeating every field name ten thousand times.
 */
object Backup {

    /**
     * The format version, in the file and checked on the way back in.
     *
     * A restore that does not recognise a file refuses it rather than doing its best with
     * it. Half-restoring somebody's history is worse than telling them the file is wrong.
     */
    const val FORMAT = 1

    /**
     * Keys that never reach the file, whatever the caller passes.
     *
     * Enforced here as well as at the call site: this is the rule that matters most in the
     * whole class, and a rule applied in one place is a rule that has one place to go
     * wrong.
     */
    val NEVER_LEAVES: Set<String> = setOf(
        "watch_auth_key_sealed",
        "watch_address",
        "watch_name",
    )

    /** The kinds of value a preference store can hold. */
    enum class SettingType { BOOLEAN, INT, LONG, FLOAT, DOUBLE, STRING, STRING_SET }

    /** One setting as it travels: named, typed, and valued. */
    data class Setting(val key: String, val type: SettingType, val value: Any)

    /** Rows of one table, with the columns they are in. */
    data class Table(val columns: List<String>, val rows: List<List<Any?>>)

    /** A whole backup, before it is text or after it has stopped being text. */
    data class Contents(
        val settings: List<Setting> = emptyList(),
        val tables: Map<String, Table> = emptyMap(),
        /** The app that wrote it, for a person reading the file to make sense of it. */
        val versionCode: Int = 0,
        val writtenAtSeconds: Long = 0,
    )

    /** Renders a backup. Excluded keys are dropped here whether or not the caller did. */
    fun write(contents: Contents): String {
        val settings = JSONArray()
        contents.settings
            .filterNot { it.key in NEVER_LEAVES }
            .forEach { setting ->
                settings.put(
                    JSONObject()
                        .put("key", setting.key)
                        .put("type", setting.type.name)
                        .put("value", setting.value.toJson()),
                )
            }

        val tables = JSONObject()
        contents.tables.forEach { (name, table) ->
            tables.put(
                name,
                JSONObject()
                    .put("columns", JSONArray(table.columns))
                    .put(
                        "rows",
                        JSONArray().apply {
                            table.rows.forEach { row ->
                                put(JSONArray().apply { row.forEach { put(it ?: JSONObject.NULL) } })
                            }
                        },
                    ),
            )
        }

        return JSONObject()
            .put("format", FORMAT)
            .put("app", contents.versionCode)
            .put("writtenAt", contents.writtenAtSeconds)
            .put("settings", settings)
            .put("tables", tables)
            .toString(2)
    }

    /**
     * Reads a backup back.
     *
     * @return null when the text is not JSON, is not one of ours, or is a format this
     *   version does not know. A setting whose type is unreadable is dropped and the rest
     *   is kept — one unknown key should not cost somebody their history.
     */
    fun read(text: String): Contents? {
        val root = try {
            JSONObject(text)
        } catch (e: JSONException) {
            return null
        }

        if (root.optInt("format", -1) != FORMAT) return null

        val settings = root.optJSONArray("settings").orEmpty().mapNotNull { it.toSetting() }
            .filterNot { it.key in NEVER_LEAVES }

        val tablesJson = root.optJSONObject("tables") ?: JSONObject()
        val tables = tablesJson.keys().asSequence().mapNotNull { name ->
            val table = tablesJson.optJSONObject(name) ?: return@mapNotNull null
            val columns = table.optJSONArray("columns").orEmpty().map { it.toString() }
            if (columns.isEmpty()) return@mapNotNull null

            val rows = table.optJSONArray("rows").orEmpty().mapNotNull { row ->
                (row as? JSONArray)?.let { cells ->
                    (0 until cells.length()).map { cells.opt(it).takeIf { cell -> cell != JSONObject.NULL } }
                }
            }
            name to Table(columns, rows)
        }.toMap()

        return Contents(
            settings = settings,
            tables = tables,
            versionCode = root.optInt("app"),
            writtenAtSeconds = root.optLong("writtenAt"),
        )
    }

    private fun Any.toJson(): Any = when (this) {
        is Set<*> -> JSONArray(this.map { it.toString() })
        else -> this
    }

    private fun Any?.toSetting(): Setting? {
        val row = this as? JSONObject ?: return null
        val key = row.optString("key").takeIf { it.isNotEmpty() } ?: return null
        val type = SettingType.entries.firstOrNull { it.name == row.optString("type") } ?: return null

        val value: Any = when (type) {
            SettingType.BOOLEAN -> row.optBoolean("value")
            SettingType.INT -> row.optInt("value")
            SettingType.LONG -> row.optLong("value")
            SettingType.FLOAT -> row.optDouble("value").toFloat()
            SettingType.DOUBLE -> row.optDouble("value")
            SettingType.STRING -> row.optString("value")
            SettingType.STRING_SET ->
                row.optJSONArray("value").orEmpty().map { it.toString() }.toSet()
        }

        return Setting(key, type, value)
    }

    private fun JSONArray?.orEmpty(): List<Any?> {
        val array = this ?: return emptyList()
        return (0 until array.length()).map { array.opt(it) }
    }
}
