package org.opencodemobile.shared.data.permission

import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.opencodemobile.shared.domain.cache.CachedPreference
import org.opencodemobile.shared.domain.cache.SessionCache
import org.opencodemobile.shared.domain.cache.SessionCacheWriter
import org.opencodemobile.shared.domain.permission.PendingPermissionStore
import org.opencodemobile.shared.domain.permission.PermissionCapabilities
import org.opencodemobile.shared.domain.permission.PermissionDecision
import org.opencodemobile.shared.domain.permission.PermissionRequest

/** Preference key under which the pending set is stored, per server. */
public const val PENDING_PERMISSION_PREFERENCE_KEY: String = "pending_permissions"

/**
 * The encrypted-cache implementation of [PendingPermissionStore] (V1-06).
 *
 * Pending requests are persisted through the existing non-secret preference table
 * of the disposable, encrypted cache (`docs/ARCHITECTURE.md` §3.4, T3), so a
 * pending permission survives an app kill and reappears on the next launch.
 *
 * Persisting a request is **not** approving it: this class can only store the
 * pending set; the only path to the server is [PermissionPort]
 * (`shared/networking`). A corrupt or absent value degrades to an empty list,
 * never to a fabricated request.
 */
public class CachePendingPermissionStore(
    private val cache: SessionCache,
    private val writer: SessionCacheWriter,
    private val serverId: String,
    private val json: Json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    },
) : PendingPermissionStore {

    override suspend fun load(): List<PermissionRequest> {
        val stored = cache.preference(serverId, PENDING_PERMISSION_PREFERENCE_KEY)
            ?: return emptyList()
        return runCatching { decode(stored) }.getOrDefault(emptyList())
    }

    override suspend fun save(requests: List<PermissionRequest>) {
        writer.putPreference(
            CachedPreference(
                serverId = serverId,
                key = PENDING_PERMISSION_PREFERENCE_KEY,
                value = encode(requests),
            ),
        )
    }

    internal fun encode(requests: List<PermissionRequest>): String =
        json.encodeToString(requests.map { it.toStored() })

    internal fun decode(stored: String): List<PermissionRequest> =
        json.decodeFromString<List<StoredPermission>>(stored).map { it.toDomain() }
}

/**
 * Serialization DTO. It lives in the data layer so the domain stays free of
 * serialization annotations; [PermissionDecision] is stored by name so the
 * exact server-exposed set round-trips.
 */
@Serializable
internal data class StoredPermission(
    val id: String,
    val sessionId: String? = null,
    val tool: String,
    val patterns: List<String> = emptyList(),
    val rawArguments: String = "",
    val rememberScopes: List<String> = emptyList(),
    val decisions: List<String> = emptyList(),
)

internal fun PermissionRequest.toStored(): StoredPermission = StoredPermission(
    id = id,
    sessionId = sessionId,
    tool = tool,
    patterns = patterns,
    rawArguments = rawArguments,
    rememberScopes = rememberScopes,
    decisions = capabilities.decisions.map { it.name },
)

internal fun StoredPermission.toDomain(): PermissionRequest = PermissionRequest(
    id = id,
    sessionId = sessionId,
    tool = tool,
    patterns = patterns,
    rawArguments = rawArguments,
    rememberScopes = rememberScopes,
    capabilities = decisions
        .mapNotNull { name -> runCatching { PermissionDecision.valueOf(name) }.getOrNull() }
        .toSet()
        .takeIf { it.isNotEmpty() }
        ?.let { PermissionCapabilities(it) }
        ?: PermissionCapabilities.fromServer(rememberScopes),
)
