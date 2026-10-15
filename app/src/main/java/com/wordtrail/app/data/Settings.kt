package com.wordtrail.app.data

import android.content.Context
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.*
import java.io.IOException

private val Context.settingsDataStore by preferencesDataStore("settings")
data class Settings(val showMeaning: Boolean = false, val theme: String = "system")
class SettingsStore(private val context: Context) {
    private val meaning = booleanPreferencesKey("show_meaning")
    private val theme = stringPreferencesKey("theme")
    val flow = context.settingsDataStore.data.catch { if (it is IOException) emit(emptyPreferences()) else throw it }
        .map { Settings(it[meaning] ?: false, it[theme] ?: "system") }
    suspend fun showMeaning(value: Boolean) { context.settingsDataStore.edit { it[meaning] = value } }
    suspend fun theme(value: String) { context.settingsDataStore.edit { it[theme] = value } }
}
