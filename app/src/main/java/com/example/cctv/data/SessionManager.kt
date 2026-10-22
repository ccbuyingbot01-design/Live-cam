package com.example.cctv.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "user_session")

class SessionManager(private val context: Context) {

    companion object {
        private const val PREFS_NAME = "cctv_prefs"
        private const val KEY_SAVED_MODE = "saved_device_mode"
        private const val KEY_REMEMBER_CHOICE = "remember_device_mode"

        fun getSavedModeSync(context: Context): String? {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val remember = prefs.getBoolean(KEY_REMEMBER_CHOICE, true)
            return if (remember) prefs.getString(KEY_SAVED_MODE, null) else null
        }

        fun getRememberChoiceSync(context: Context): Boolean {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            return prefs.getBoolean(KEY_REMEMBER_CHOICE, true)
        }

        fun saveModeSync(context: Context, mode: String, remember: Boolean) {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
                .putString(KEY_SAVED_MODE, mode)
                .putBoolean(KEY_REMEMBER_CHOICE, remember)
                .apply()
        }

        fun clearModeSync(context: Context) {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
                .remove(KEY_SAVED_MODE)
                .remove(KEY_REMEMBER_CHOICE)
                .apply()
        }
    }

    private val USERNAME_KEY = stringPreferencesKey("username")
    private val SAVED_MODE_KEY = stringPreferencesKey("saved_device_mode")
    private val REMEMBER_CHOICE_KEY = booleanPreferencesKey("remember_device_mode")

    val username: Flow<String?> = context.dataStore.data
        .map { preferences -> preferences[USERNAME_KEY] }

    val savedDeviceMode: Flow<String?> = context.dataStore.data
        .map { preferences ->
            val remember = preferences[REMEMBER_CHOICE_KEY] ?: true
            if (remember) preferences[SAVED_MODE_KEY] else null
        }

    suspend fun saveUsername(username: String) {
        context.dataStore.edit { preferences ->
            preferences[USERNAME_KEY] = username
        }
    }

    suspend fun saveDeviceMode(mode: String, remember: Boolean) {
        saveModeSync(context, mode, remember)
        context.dataStore.edit { preferences ->
            preferences[SAVED_MODE_KEY] = mode
            preferences[REMEMBER_CHOICE_KEY] = remember
        }
    }

    suspend fun clearSession() {
        clearModeSync(context)
        context.dataStore.edit { preferences ->
            preferences.remove(USERNAME_KEY)
            preferences.remove(SAVED_MODE_KEY)
            preferences.remove(REMEMBER_CHOICE_KEY)
        }
    }
}
