package app.tennyo.mobile.link.store

import com.russhwolf.settings.Settings
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json

/**
 * One persisted setting: its storage name and how its value is read and written. Each module declares the keys it
 * owns, so no caller spells a storage name or a raw type at the read site.
 */
class SettingKey<T> private constructor(
    val name: String,
    private val read: Settings.(String) -> T,
    private val write: Settings.(String, T) -> Unit,
) {
    internal fun readFrom(settings: Settings): T = settings.read(name)

    internal fun writeTo(settings: Settings, value: T) = settings.write(name, value)

    companion object {
        fun boolean(name: String, default: Boolean): SettingKey<Boolean> =
            SettingKey(name, { getBoolean(it, default) }, { key, value -> putBoolean(key, value) })

        /** An enum stored by constant name; an unknown or missing name reads as [default]. */
        inline fun <reified E : Enum<E>> enum(name: String, default: E): SettingKey<E> =
            enum(name, default, enumValues<E>().toList())

        fun <E : Enum<E>> enum(name: String, default: E, values: List<E>): SettingKey<E> =
            SettingKey(
                name,
                { key -> getStringOrNull(key)?.let { stored -> values.firstOrNull { it.name == stored } } ?: default },
                { key, value -> putString(key, value.name) },
            )

        /** A structured value stored as JSON; a missing or undecodable entry reads as [default]. */
        fun <T> json(name: String, serializer: KSerializer<T>, default: T, json: Json = SettingsJson): SettingKey<T> =
            SettingKey(
                name,
                { key ->
                    getStringOrNull(key)
                        ?.let { stored -> runCatching { json.decodeFromString(serializer, stored) }.getOrNull() }
                        ?: default
                },
                { key, value -> putString(key, json.encodeToString(serializer, value)) },
            )

        private val SettingsJson = Json { ignoreUnknownKeys = true }
    }
}

operator fun <T> Settings.get(key: SettingKey<T>): T = key.readFrom(this)

operator fun <T> Settings.set(key: SettingKey<T>, value: T) = key.writeTo(this, value)
