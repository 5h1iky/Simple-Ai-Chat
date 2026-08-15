package www.cetool.com.model

import com.google.gson.JsonElement

data class TavernCard(
    val spec: String = "chara_card_v3",
    val spec_version: String = "3.0",
    val data: TavernCardData,
    val avatarBase64: String? = null
)

data class TavernCardData(
    val name: String = "",
    val description: String = "",
    val personality: String = "",
    val scenario: String = "",
    val first_mes: String = "",
    val mes_example: String = "",
    val system_prompt: String = "",
    val post_history_instructions: String = "",
    val creator_notes: String = "",
    val tags: List<String> = emptyList(),
    val creator: String = "",
    val character_version: String = "1.0",
    val alternate_greetings: List<String> = emptyList(),
    val extensions: Map<String, JsonElement> = emptyMap()
) {
    fun isVaild(): Boolean = name.isNotBlank()
}
