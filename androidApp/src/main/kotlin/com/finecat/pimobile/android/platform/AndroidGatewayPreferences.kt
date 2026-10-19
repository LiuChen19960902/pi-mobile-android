package com.finecat.pimobile.android.platform

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStoreFile
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.finecat.pimobile.shared.platform.GatewayPreferences
import com.finecat.pimobile.shared.platform.GatewayPreferencesSnapshot
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import java.io.IOException

class AndroidGatewayPreferences(context: Context) : GatewayPreferences {
    private val dataStore: DataStore<Preferences> = PreferenceDataStoreFactory.create(
        produceFile = { context.preferencesDataStoreFile(FILE_NAME) }
    )

    private val mutableReadFailed = MutableStateFlow(false)
    val readFailed: StateFlow<Boolean> = mutableReadFailed

    override val snapshots: Flow<GatewayPreferencesSnapshot> = dataStore.data
        .onEach { mutableReadFailed.value = false }
        .catch { error ->
            if (error is IOException) {
                mutableReadFailed.value = true
                emit(androidx.datastore.preferences.core.emptyPreferences())
            }
            else throw error
        }
        .map(::toSnapshot)

    override suspend fun load(): GatewayPreferencesSnapshot = snapshots.first()

    override suspend fun update(snapshot: GatewayPreferencesSnapshot) {
        dataStore.edit { preferences -> writeSnapshot(preferences, snapshot) }
    }

    /** DataStore 事务内读改写：外观/标题/端点并发改不丢更新。 */
    override suspend fun updateFields(transform: (GatewayPreferencesSnapshot) -> GatewayPreferencesSnapshot) {
        dataStore.edit { preferences -> writeSnapshot(preferences, transform(toSnapshot(preferences))) }
    }

    private fun writeSnapshot(
        preferences: androidx.datastore.preferences.core.MutablePreferences,
        snapshot: GatewayPreferencesSnapshot
    ) {
        preferences[ENDPOINT] = snapshot.endpoint
        val candidates = snapshot.endpointCandidates.filter(String::isNotBlank)
        if (candidates.isEmpty()) preferences.remove(ENDPOINT_CANDIDATES)
        // DataStore 没有有序列表键，按行拼成单字符串保存（顺序即尝试优先级）。
        else preferences[ENDPOINT_CANDIDATES] = candidates.joinToString("\n")
        snapshot.sessionsJson.setOrRemove(preferences, SESSIONS_JSON)
        snapshot.worktreePendingJson.setOrRemove(preferences, WORKTREE_PENDING)
        snapshot.heroTitle.setOrRemove(preferences, HERO_TITLE)
        snapshot.heroTagline.setOrRemove(preferences, HERO_TAGLINE)
        preferences[APPEARANCE] = snapshot.appearanceMode.ifBlank { "system" }
        val hidden = snapshot.hiddenSessionIds.filter(String::isNotBlank).distinct()
        // 与候选地址同口径：DataStore 没有集合键，按行拼成单字符串保存。
        if (hidden.isEmpty()) preferences.remove(HIDDEN_SESSIONS)
        else preferences[HIDDEN_SESSIONS] = hidden.joinToString("\n")
        val hiddenDirs = snapshot.hiddenDirectoryPaths.filter(String::isNotBlank).distinct()
        if (hiddenDirs.isEmpty()) preferences.remove(HIDDEN_DIRECTORIES)
        else preferences[HIDDEN_DIRECTORIES] = hiddenDirs.joinToString("\n")
        val pinned = snapshot.pinnedSessionIds.filter(String::isNotBlank).distinct()
        if (pinned.isEmpty()) preferences.remove(PINNED_SESSIONS)
        else preferences[PINNED_SESSIONS] = pinned.joinToString("\n")
    }

    private fun toSnapshot(preferences: Preferences): GatewayPreferencesSnapshot =
        GatewayPreferencesSnapshot(
            endpoint = preferences[ENDPOINT] ?: DEFAULT_ENDPOINT,
            endpointCandidates = preferences[ENDPOINT_CANDIDATES]
                ?.lineSequence()?.map(String::trim)?.filter(String::isNotEmpty)?.distinct()?.toList()
                ?: emptyList(),
            sessionsJson = preferences[SESSIONS_JSON],
            worktreePendingJson = preferences[WORKTREE_PENDING],
            heroTitle = preferences[HERO_TITLE],
            heroTagline = preferences[HERO_TAGLINE],
            appearanceMode = preferences[APPEARANCE] ?: "system",
            hiddenSessionIds = preferences[HIDDEN_SESSIONS]
                ?.lineSequence()?.map(String::trim)?.filter(String::isNotEmpty)?.distinct()?.toList()
                ?: emptyList(),
            hiddenDirectoryPaths = preferences[HIDDEN_DIRECTORIES]
                ?.lineSequence()?.map(String::trim)?.filter(String::isNotEmpty)?.distinct()?.toList()
                ?: emptyList(),
            pinnedSessionIds = preferences[PINNED_SESSIONS]
                ?.lineSequence()?.map(String::trim)?.filter(String::isNotEmpty)?.distinct()?.toList()
                ?: emptyList()
        )

    private fun String?.setOrRemove(
        preferences: androidx.datastore.preferences.core.MutablePreferences,
        key: Preferences.Key<String>
    ) {
        if (this == null) preferences.remove(key) else preferences[key] = this
    }

    companion object {
        const val DEFAULT_ENDPOINT = "ws://127.0.0.1:3080/ws/mobile"

        private const val FILE_NAME = "gateway.preferences_pb"
        private val ENDPOINT = stringPreferencesKey("gateway.endpoint")
        private val ENDPOINT_CANDIDATES = stringPreferencesKey("gateway.endpoint_candidates")
        private val WORKTREE_PENDING = stringPreferencesKey("gateway.worktree_pending")
        private val SESSIONS_JSON = stringPreferencesKey("gateway.sessions_json")
        private val HERO_TITLE = stringPreferencesKey("gateway.hero_title")
        private val HERO_TAGLINE = stringPreferencesKey("gateway.hero_tagline")
        private val HIDDEN_SESSIONS = stringPreferencesKey("gateway.hidden_sessions")
        private val HIDDEN_DIRECTORIES = stringPreferencesKey("gateway.hidden_directories")
        private val PINNED_SESSIONS = stringPreferencesKey("gateway.pinned_sessions")
        private val APPEARANCE = stringPreferencesKey("gateway.appearance")
    }
}
