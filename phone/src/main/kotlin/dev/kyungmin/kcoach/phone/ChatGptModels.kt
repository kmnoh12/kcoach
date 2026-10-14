package dev.kyungmin.kcoach.phone

import org.json.JSONObject

data class ChatGptReasoningOption(val effort: String, val description: String = "")

data class ChatGptModelOption(
    val slug: String,
    val displayName: String,
    val reasoningOptions: List<ChatGptReasoningOption>,
    val defaultReasoning: String?,
    val reasoningMetadataAvailable: Boolean,
)

data class ChatGptModelSettingsUi(
    val loading: Boolean = false,
    val loaded: Boolean = false,
    val models: List<ChatGptModelOption> = emptyList(),
    val selectedSlug: String = "",
    val selectedEffort: String? = null,
    val message: String = "",
) {
    val selectedModel get() = models.firstOrNull { it.slug == selectedSlug }
    val selectionValid get() = loaded && selectedModel?.let { model ->
        selectedEffort == null || model.reasoningOptions.any { it.effort == selectedEffort }
    } == true
}

/** Parse account-visible models in server order. Never infer capabilities from a model's name. */
internal fun parseChatGptModels(body: JSONObject): List<ChatGptModelOption> {
    val models = body.optJSONArray("models") ?: authFail("지원 모델 목록을 확인할 수 없어요. 다시 불러와 주세요")
    val result = mutableListOf<ChatGptModelOption>()
    val seen = mutableSetOf<String>()
    for (index in 0 until models.length()) {
        val item = models.optJSONObject(index) ?: continue
        if (item.optString("visibility") != "list") continue
        val slug = (item.opt("slug") as? String).orEmpty().trim()
        val name = (item.opt("display_name") as? String).orEmpty().trim()
        if (slug.isBlank() || slug.length > 200 || name.isBlank() || name.length > 200)
            authFail("지원 모델 정보가 불완전해요. 다시 불러와 주세요")
        if (!seen.add(slug)) authFail("지원 모델 목록이 중복돼요. 다시 불러와 주세요")
        val efforts = item.optJSONArray("supported_reasoning_levels") ?: item.optJSONArray("supportedReasoningEfforts")
        val options = mutableListOf<ChatGptReasoningOption>()
        if (efforts != null) for (level in 0 until efforts.length()) {
            val option = efforts.optJSONObject(level) ?: authFail("추론 강도 정보를 확인할 수 없어요")
            val effort = (option.opt("effort") as? String) ?: (option.opt("reasoningEffort") as? String).orEmpty()
            if (!effort.matches(Regex("[A-Za-z0-9_-]{1,64}"))) authFail("추론 강도 정보를 확인할 수 없어요")
            if (options.none { it.effort == effort }) options += ChatGptReasoningOption(effort, (option.opt("description") as? String).orEmpty())
        }
        val default = ((item.opt("default_reasoning_level") as? String) ?: (item.opt("defaultReasoningEffort") as? String)).orEmpty()
            .takeIf { candidate -> options.any { it.effort == candidate } }
        result += ChatGptModelOption(slug, name, options, default, efforts != null)
    }
    return result
}

internal fun workRequestOptions(model: ChatGptModelOption, effort: String?): JSONObject {
    require(effort == null || model.reasoningOptions.any { it.effort == effort })
    return JSONObject().put("model", model.slug).put("store", false).put("stream", true).also { request ->
        if (effort != null) request.put("reasoning", JSONObject().put("effort", effort))
    }
}
