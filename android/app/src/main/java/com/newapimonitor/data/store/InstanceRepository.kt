package com.newapimonitor.data.store

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.newapimonitor.domain.AppSettings
import com.newapimonitor.domain.AuthStyle
import com.newapimonitor.domain.Instance
import com.newapimonitor.domain.InstanceSanitizer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** 全 App 唯一 DataStore（同一檔案開兩個 DataStore 會炸）：HistoryStore / Alert*Store 共用 */
internal val Context.dataStore by preferencesDataStore(name = "newapi_monitor_store")

/** 持久化 DTO（kotlinx-serialization；token 只存本機 DataStore） */
@Serializable
data class InstanceDto(
    val id: String = "",
    val name: String = "",
    val baseUrl: String = "",
    val accessToken: String = "",
    val apiKey: String = "",
    val quotaPerUnit: Double? = null,
    val authStyle: String? = null,
    val authStyleKey: String? = null,
    val createdAt: Long = 0,
)

@Serializable
data class SettingsDto(
    val activeId: String? = null,
    val intervalSec: Int = 15,
    val demo: Boolean = false,
)

/**
 * 多實例管理＋設定（移植 js/store.js；localStorage → DataStore Preferences）。
 * 清洗邏輯統一交 InstanceSanitizer。
 */
class InstanceRepository(private val context: Context, private val cipher: SecretCipher = CredentialCipher()) {

    /** Application context（小工具同步等無 Activity 場景用；唔係 Activity context，唔會洩漏） */
    val appContext: Context get() = context

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val keyInstances = stringPreferencesKey("instances.v2")
    private val legacyInstances = stringPreferencesKey("instances.v1")
    private val unreadable = mutableMapOf<String, InstanceDto>()
    private var credentialWriteBlocked = false
    private val _credentialError = MutableStateFlow<String?>(null)
    val credentialError = _credentialError.asStateFlow()
    private val keySettings = stringPreferencesKey("settings.v1")
    private val mutex = Mutex()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _instances = MutableStateFlow<List<Instance>>(emptyList())
    val instances: StateFlow<List<Instance>> = _instances.asStateFlow()

    private val _settings = MutableStateFlow(AppSettings())
    val settings: StateFlow<AppSettings> = _settings.asStateFlow()

    private val _ready = MutableStateFlow(false)
    val ready: StateFlow<Boolean> = _ready.asStateFlow()

    /** 當前實例：activeId 指向嘅實例，其次第一個；點刪除／改動都會自動重算 */
    val activeInstance: StateFlow<Instance?> = combine(_instances, _settings) { list, s ->
        val byId = s.activeId?.let { id -> list.firstOrNull { it.id == id } }
        byId ?: list.firstOrNull()
    }.stateIn(scope, SharingStarted.Eagerly, null)

    /* ---------------- 載入 / 清洗 ---------------- */

    suspend fun load() {
        if (_ready.value) return
        val prefs = context.dataStore.data.first()
        val encrypted = prefs[keyInstances] != null
        val raw = prefs[keyInstances] ?: prefs[legacyInstances]
        val dtos = try { if (raw == null) emptyList() else json.decodeFromString<List<InstanceDto>>(raw) }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) {
                credentialWriteBlocked = true
                _credentialError.value = "本機實例資料無法讀取；原始資料已保留，請勿清除 App 資料。"
                _ready.value = true
                return
            }
        val instances = dtos.mapNotNull { dto ->
            val decoded = if (!encrypted) dto else try {
                dto.copy(accessToken = cipher.open(dto.accessToken, "${dto.id}:access"),
                    apiKey = cipher.open(dto.apiKey, "${dto.id}:api"))
            } catch (e: CancellationException) { throw e
            } catch (_: Exception) {
                unreadable[dto.id] = dto
                _credentialError.value = "部分本機憑證未能解密，請重新輸入該實例金鑰；原有加密資料仍然保留。"
                dto.copy(accessToken = "", apiKey = "")
            }
            dtoToInstance(decoded)
        }
        // Commit encryption before publishing ready; failed migration leaves the original data intact.
        if (!encrypted && dtos.isNotEmpty()) {
            try {
                val secured = instances.map(::encryptedDto)
                // Do not drop a malformed legacy row: keep the entire original until it can migrate safely.
                require(secured.size == dtos.size)
                context.dataStore.edit { it[keyInstances] = json.encodeToString(secured); it.remove(legacyInstances) }
            } catch (e: CancellationException) { throw e
            } catch (_: Exception) {
                credentialWriteBlocked = true
                _credentialError.value = "本機憑證加密暫時失敗；舊資料已保留，請稍後重新開啟 App。"
            }
        }
        val settings: AppSettings = try {
            val raw = prefs[keySettings]
            if (raw != null) {
                val s = json.decodeFromString<SettingsDto>(raw)
                AppSettings(
                    activeId = s.activeId,
                    intervalSec = if (AppSettings.VALID_INTERVALS.contains(s.intervalSec)) s.intervalSec else 15,
                    demo = s.demo,
                )
            } else {
                AppSettings()
            }
        } catch (e: Exception) {
            AppSettings()
        }

        var st = settings
        // activeId 指向已刪除實例 → 修正（js load 同款）
        if (st.activeId != null && instances.none { it.id == st.activeId }) {
            st = st.copy(activeId = instances.firstOrNull()?.id)
        }
        if (st.activeId == null && instances.isNotEmpty()) {
            st = st.copy(activeId = instances[0].id)
        }
        _instances.value = instances
        _settings.value = st
        _ready.value = true
    }

    private fun dtoToInstance(dto: InstanceDto): Instance? = InstanceSanitizer.sanitize(
        Instance(
            id = dto.id,
            name = dto.name,
            baseUrl = dto.baseUrl,
            accessToken = dto.accessToken,
            apiKey = dto.apiKey,
            quotaPerUnit = dto.quotaPerUnit,
            authStyle = if (dto.authStyle == "raw") AuthStyle.RAW else null,
            authStyleKey = if (dto.authStyleKey == "raw") AuthStyle.RAW else null,
            createdAt = dto.createdAt,
        )
    )

    private fun instanceToDto(i: Instance) = InstanceDto(
        id = i.id,
        name = i.name,
        baseUrl = i.baseUrl,
        accessToken = i.accessToken,
        apiKey = i.apiKey,
        quotaPerUnit = i.quotaPerUnit,
        // 只有 RAW 會持久化（js：o.authStyle === 'raw' ? 'raw' : null）
        authStyle = if (i.authStyle == AuthStyle.RAW) "raw" else null,
        authStyleKey = if (i.authStyleKey == AuthStyle.RAW) "raw" else null,
        createdAt = i.createdAt,
    )

    /* ---------------- 實例 CRUD ---------------- */

    /** Serialize local result writes with deletion/reset so cancelled requests cannot repopulate wiped data. */
    suspend fun <T> withCurrentSource(instance: Instance, block: suspend () -> T): T? = mutex.withLock {
        if (_instances.value.any { it == instance }) block() else null
    }

    suspend fun addInstance(data: Instance): Instance? = mutex.withLock {
        val inst = InstanceSanitizer.sanitize(
            data.copy(id = InstanceSanitizer.newInstanceSeed(), createdAt = System.currentTimeMillis())
        ) ?: return null
        persistInstancesLocked(_instances.value + inst)
        if (_settings.value.activeId == null) {
            persistSettingsLocked(_settings.value.copy(activeId = inst.id))
        }
        inst
    }

    suspend fun updateInstance(id: String, data: Instance): Instance? = mutex.withLock {
        val list = _instances.value
        val idx = list.indexOfFirst { it.id == id }
        if (idx < 0) return null
        val cur = list[idx]
        // js Object.assign({...cur, ...data, id})：表單欄位覆蓋，authStyle 未填就保留舊值
        val merged = InstanceSanitizer.sanitize(
            data.copy(
                id = id,
                authStyle = data.authStyle ?: cur.authStyle,
                authStyleKey = data.authStyleKey ?: cur.authStyleKey,
                createdAt = cur.createdAt,
            )
        ) ?: return null
        val retained = unreadable.remove(id)
        try { persistInstancesLocked(list.toMutableList().apply { set(idx, merged) }) }
        catch (e: Exception) { if (retained != null) unreadable[id] = retained; throw e }
        if (unreadable.isEmpty() && !credentialWriteBlocked) _credentialError.value = null
        merged
    }

    suspend fun removeInstance(id: String): Unit = mutex.withLock {
        val list = _instances.value.filter { it.id != id }
        persistInstancesLocked(list)
        unreadable.remove(id)
        if (unreadable.isEmpty() && !credentialWriteBlocked) _credentialError.value = null
        if (_settings.value.activeId == id) {
            persistSettingsLocked(_settings.value.copy(activeId = list.firstOrNull()?.id))
        }
        // 附屬數據一併清走（餘額歷史／警報狀態），避免殘留孤兒 key
        runCatching {
            HistoryStore(context).clearHistory(id)
            AlertStateStore(context).clear(id)
            context.dataStore.edit { prefs ->
                prefs.asMap().keys.filter { it.name == "speed_test_history.v1.$id" || it.name == "speed_test_history.v1.instance.$id" }
                    .forEach { prefs.remove(it) }
            }
            com.newapimonitor.data.monitor.MonitorStore(context).remove(id)
            com.newapimonitor.data.monitor.MonitorPreferencesStore(context).remove(id)
            com.newapimonitor.alert.AlertHistoryStore(context).removeSource(id)
        }
    }

    suspend fun setActive(id: String?): Unit = mutex.withLock {
        if (id == null || _instances.value.any { it.id == id }) {
            persistSettingsLocked(_settings.value.copy(activeId = id))
        }
    }

    /** 401 探測成功後記住 header 格式（js persistAuthStyle） */
    suspend fun updateAuthStyles(id: String, access: AuthStyle?, key: AuthStyle?): Unit = mutex.withLock {
        val list = _instances.value
        val idx = list.indexOfFirst { it.id == id }
        if (idx < 0) return
        val cur = list[idx]
        val newAccess = access?.takeIf { it == AuthStyle.RAW }
        val newKey = key?.takeIf { it == AuthStyle.RAW }
        if (newAccess == cur.authStyle && newKey == cur.authStyleKey) return
        persistInstancesLocked(
            list.toMutableList().apply { set(idx, cur.copy(authStyle = newAccess, authStyleKey = newKey)) }
        )
    }

    /* ---------------- 設定 ---------------- */

    suspend fun setIntervalSec(sec: Int): Unit = mutex.withLock {
        if (AppSettings.VALID_INTERVALS.contains(sec)) {
            persistSettingsLocked(_settings.value.copy(intervalSec = sec))
        }
    }

    suspend fun setDemo(v: Boolean): Unit = mutex.withLock {
        persistSettingsLocked(_settings.value.copy(demo = v))
    }

    /** 清除本 App 所有本機數據 */
    suspend fun wipeAll(): Unit = mutex.withLock {
        unreadable.clear()
        _credentialError.value = null
        _instances.value = emptyList()
        _settings.value = AppSettings()
        context.dataStore.edit {
            it.clear()
        }
        runCatching {
            HistoryStore(context).clearAll()
            AlertStateStore(context).clearAll()
        }
    }

    /* ---------------- 持久化 ---------------- */

    private suspend fun persistInstancesLocked(list: List<Instance>) {
        check(!credentialWriteBlocked) { "Credential migration has not completed" }
        val secured = withContext(Dispatchers.IO) { list.map { unreadable[it.id] ?: encryptedDto(it) } }
        context.dataStore.edit { p ->
            p[keyInstances] = json.encodeToString(secured)
            p.remove(legacyInstances)
        }
        _instances.value = list
    }

    private fun encryptedDto(i: Instance) = instanceToDto(i).copy(
        accessToken = cipher.seal(i.accessToken, "${i.id}:access"),
        apiKey = cipher.seal(i.apiKey, "${i.id}:api"),
    )

    private suspend fun persistSettingsLocked(s: AppSettings) {
        _settings.value = s
        context.dataStore.edit { p ->
            p[keySettings] = json.encodeToString(
                SettingsDto.serializer(),
                SettingsDto(activeId = s.activeId, intervalSec = s.intervalSec, demo = s.demo),
            )
        }
    }
}
