package com.jianqiaofan.subtitleplayer.domain.cloud

import com.jianqiaofan.subtitleplayer.domain.json.JsonValue
import com.jianqiaofan.subtitleplayer.domain.json.parseJson

data class PendingChanges(
    val subtitles: Boolean = false,
    val tags: Boolean = false,
) {
    val any: Boolean get() = subtitles || tags

    fun merge(other: PendingChanges) = PendingChanges(
        subtitles = subtitles || other.subtitles,
        tags = tags || other.tags,
    )
}

data class LeavePolicy(
    val syncSubtitles: Boolean,
    val syncTags: Boolean,
)

data class StudySyncMemory(
    val policies: Map<String, LeavePolicy> = emptyMap(),
    val pending: Map<String, PendingChanges> = emptyMap(),
    val sessionOwners: Map<String, String> = emptyMap(),
)

enum class LeaveAction { Ask, Auto, Skip }

fun leaveAction(loggedIn: Boolean, online: Boolean, pending: PendingChanges, policy: LeavePolicy?): LeaveAction {
    if (!pending.any || !loggedIn || !online) return LeaveAction.Skip
    return if (policy == null) LeaveAction.Ask else LeaveAction.Auto
}

fun itemsToUpload(policy: LeavePolicy, pending: PendingChanges): PendingChanges =
    PendingChanges(
        subtitles = policy.syncSubtitles && pending.subtitles,
        tags = policy.syncTags && pending.tags,
    )

fun encodeStudySyncMemory(memory: StudySyncMemory): String = buildString {
    append("{\"policies\":{")
    memory.policies.entries.forEachIndexed { index, (key, policy) ->
        if (index > 0) append(',')
        append("${com.jianqiaofan.subtitleplayer.domain.json.jsonString(key)}:")
        append("{\"syncSubtitles\":${policy.syncSubtitles},\"syncTags\":${policy.syncTags}}")
    }
    append("},\"pending\":{")
    memory.pending.entries.forEachIndexed { index, (key, pending) ->
        if (index > 0) append(',')
        append("${com.jianqiaofan.subtitleplayer.domain.json.jsonString(key)}:")
        append("{\"subtitles\":${pending.subtitles},\"tags\":${pending.tags}}")
    }
    append("},\"owners\":{")
    memory.sessionOwners.entries.forEachIndexed { index, (key, owner) ->
        if (index > 0) append(',')
        append("${com.jianqiaofan.subtitleplayer.domain.json.jsonString(key)}:")
        append(com.jianqiaofan.subtitleplayer.domain.json.jsonString(owner))
    }
    append("}}")
}

fun parseStudySyncMemory(raw: String): StudySyncMemory {
    val root = try {
        parseJson(raw) as? JsonValue.Obj
    } catch (_: Exception) {
        null
    } ?: return StudySyncMemory()
    val policies = (root.map["policies"] as? JsonValue.Obj)?.map.orEmpty().mapNotNull { (key, node) ->
        val item = (node as? JsonValue.Obj)?.map ?: return@mapNotNull null
        key to LeavePolicy(
            syncSubtitles = (item["syncSubtitles"] as? JsonValue.Bool)?.value == true,
            syncTags = (item["syncTags"] as? JsonValue.Bool)?.value == true,
        )
    }.toMap()
    val pending = (root.map["pending"] as? JsonValue.Obj)?.map.orEmpty().mapNotNull { (key, node) ->
        val item = (node as? JsonValue.Obj)?.map ?: return@mapNotNull null
        key to PendingChanges(
            subtitles = (item["subtitles"] as? JsonValue.Bool)?.value == true,
            tags = (item["tags"] as? JsonValue.Bool)?.value == true,
        )
    }.toMap()
    val owners = (root.map["owners"] as? JsonValue.Obj)?.map.orEmpty().mapNotNull { (key, node) ->
        val owner = (node as? JsonValue.Str)?.value ?: return@mapNotNull null
        key to owner
    }.toMap()
    return StudySyncMemory(policies, pending, owners)
}
