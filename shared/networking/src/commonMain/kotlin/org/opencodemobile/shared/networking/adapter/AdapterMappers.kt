package org.opencodemobile.shared.networking.adapter

import kotlinx.serialization.json.*
import org.opencode.mobile.networking.client.generated.models.*
import org.opencodemobile.shared.domain.chat.*
import org.opencodemobile.shared.domain.interaction.*
import org.opencodemobile.shared.domain.session.SessionSummary

internal fun ApiSession.toDomain(): SessionSummary = SessionSummary(
    id = id, title = title?.takeIf { it.isNotBlank() } ?: id,
    directory = location.directory, projectId = projectID, parentSessionId = parentID,
    createdAt = time.created.toLong(), updatedAt = time.updated.toLong(),
)

internal fun ApiModelInfo.toDomain(): ModelDescriptor = ModelDescriptor(
    id = id, name = name, providerId = providerID,
)

internal fun ApiAgentInfo.toDomain(): AgentDescriptor = AgentDescriptor(
    name = id, description = description, mode = mode, hidden = hidden,
)

/** Native messages carry their role in `type`; content block identity is its ordinal. */
internal fun ApiMessage.toDomain(sessionId: String): TranscriptMessage? {
    // These records describe session state, not conversation content.
    if (type in setOf("idle", "agent-switched", "model-switched", "location-switched", "compaction")) return null
    return TranscriptMessage(
        id = id, sessionId = sessionId,
        role = when (type) {
            "shell" -> TranscriptRole.Tool
            // Skill records contain injected instructions (text), so retain them as system context.
            "synthetic", "skill" -> TranscriptRole.System
            else -> TranscriptRole.fromWire(type)
        },
        parts = if (type in setOf("user", "system", "synthetic", "skill")) {
            listOf(TranscriptPart(id = "$id:0", type = "text", text = (text ?: description).orEmpty()))
        } else if (type == "shell") {
            listOf(
                TranscriptPart(id = "$id:0", type = "code", text = command.orEmpty(), language = "shell"),
                TranscriptPart(id = "$id:1", type = "text", text = output?.output.orEmpty()),
            )
        } else {
            content.mapIndexed { ordinal, part ->
                TranscriptPart(id = "$id:$ordinal", type = part.type, text = part.text.orEmpty())
            }
        },
    )
}

internal fun ApiForm.toDomain(): PendingQuestion = PendingQuestion(
    id = id, sessionId = sessionID, title = title,
    fields = fields.map { element ->
        val field = element.jsonObject
        FormField(
            key = requireNotNull(field.string("key")),
            type = when (field.string("type")) {
                "string" -> FormFieldType.Text
                "number" -> FormFieldType.Number
                "integer" -> FormFieldType.Integer
                "boolean" -> FormFieldType.Toggle
                "multiselect" -> FormFieldType.Multiselect
                "external" -> FormFieldType.External
                else -> error("Unsupported form field type")
            },
            title = field.string("title"), description = field.string("description"),
            required = (field["required"] as? JsonPrimitive)?.booleanOrNull ?: false,
            custom = (field["custom"] as? JsonPrimitive)?.booleanOrNull ?: false,
            url = field.string("url"),
            options = (field["options"] as? JsonArray).orEmpty().map { option ->
                val entry = option.jsonObject
                FormOption(requireNotNull(entry.string("value")), requireNotNull(entry.string("label")), entry.string("description"))
            },
        )
    },
)

internal fun FormAnswer.toApi(): JsonObject = JsonObject(mapValues { (_, value) ->
    when (value) {
        is FormValue.Text -> JsonPrimitive(value.value)
        is FormValue.Number -> JsonPrimitive(value.value)
        is FormValue.Toggle -> JsonPrimitive(value.value)
        is FormValue.Choices -> JsonArray(value.values.map { JsonPrimitive(it) })
    }
})

private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
