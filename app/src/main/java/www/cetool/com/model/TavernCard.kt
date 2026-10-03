package www.cetool.com.model

import com.google.gson.Gson
import com.google.gson.JsonElement
import com.google.gson.JsonObject

data class TavernCard(
    val spec: String = "chara_card_v3",
    val spec_version: String = "3.0",
    val data: TavernCardData,
    val avatarBase64: String? = null
)

/**
 * 角色卡内嵌世界书（酒馆 `data.character_book`）。
 *
 * 酒馆把角色卡自带的世界书放在这里（**不是** `extensions.world_book`），
 * 且 `entries` 是**数组**、字段用 `keys`/`secondary_keys`/`constant`/`insertion_order`
 * 与蛇形命名（角色书的规定写法见 SillyTavern Character Book 规范）。
 */
data class CharacterBook(
    val name: String? = null,
    val description: String? = null,
    val scan_depth: Int? = null,
    val token_budget: Int? = null,
    val recursive_scanning: Boolean? = null,
    val extensions: Map<String, JsonElement> = emptyMap(),
    val entries: List<CharacterBookEntry> = emptyList()
)

data class CharacterBookEntry(
    val keys: List<String> = emptyList(),
    val secondary_keys: List<String> = emptyList(),
    val content: String = "",
    val comment: String = "",
    val name: String? = null,
    val constant: Boolean = false,
    val selective: Boolean = false,
    val selectiveLogic: Int = 0,
    val insertion_order: Int = 100,
    val enabled: Boolean = true,
    val case_sensitive: Boolean? = null,
    val match_whole_words: Boolean? = null,
    val use_regex: Boolean = false,
    val scan_depth: Int? = null,
    val priority: Int? = null,
    /** 角色卡条目的位置是字符串：`"before_char"` / `"after_char"` */
    val position: String? = null,
    val extensions: Map<String, JsonElement> = emptyMap()
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
    val extensions: Map<String, JsonElement> = emptyMap(),

    // ─── v3 附加字段（B28：原本缺模型 → 导入即被 Gson 丢弃） ───
    val nickname: String = "",
    val source: List<String> = emptyList(),
    val group_only_greetings: List<String> = emptyList(),
    val creation_date: Long = 0L,
    val modification_date: Long = 0L,
    val creator_notes_multilingual: Map<String, String> = emptyMap(),
    val assets: List<CharacterAsset> = emptyList(),

    // ─── 角色卡内嵌世界书（B1：酒馆规范字段是 data.character_book） ───
    val character_book: CharacterBook? = null
) {
    fun isVaild(): Boolean = name.isNotBlank()

    // ─── 结构化字段（D1 决策：存 Tavern 规范预留的 extensions，旧卡零迁移、生态兼容） ───

    private companion object {
        const val FIELDS_KEY = "sachat_fields"
        val gson = Gson()
    }

    /** 读取结构化字段（无则返回全空对象） */
    fun getCharacterFields(): CharacterFields {
        val el = extensions[FIELDS_KEY] ?: return CharacterFields()
        return try {
            gson.fromJson(el, CharacterFields::class.java) ?: CharacterFields()
        } catch (_: Exception) {
            CharacterFields()
        }
    }

    /** 写入结构化字段，返回新副本（保持原字段不变） */
    fun withCharacterFields(fields: CharacterFields): TavernCardData {
        val newExtensions = extensions.toMutableMap()
        newExtensions[FIELDS_KEY] = gson.toJsonTree(fields)
        return copy(extensions = newExtensions)
    }
}

/** v3 资源文件描述（`data.assets`，如内置头像/表情包） */
data class CharacterAsset(
    val type: String = "",
    val name: String = "",
    val ext: String = "",
    val uri: String = "",
    val data: String? = null
)

/**
 * 角色卡结构化字段（借鉴 MuseAI 的 PartnerItemFields，按模块组织）
 */
data class CharacterFields(
    // 基础信息
    var age: String = "",
    var gender: String = "",
    var race: String = "",
    var birthplace: String = "",
    var occupation: String = "",
    var socialClass: String = "",
    // 身份标签
    var identityTags: MutableList<String> = mutableListOf(),
    // 外貌气质
    var heightBuild: String = "",
    var iconicFeatures: String = "",
    var clothingStyle: String = "",
    var overallVibe: String = "",
    // 性格特征
    var externalPersonality: String = "",
    var internalPersonality: String = "",
    var coreDesire: String = "",
    var fearWeakness: String = "",
    var moralValues: String = "",
    var quirk: String = "",
    // 技能与经历
    var skills: String = "",
    var backgroundStory: String = "",
    var relationships: String = "",
    // 说话方式
    var speakingStyle: String = "",
    var typicalReactions: String = "",
    // 角色记忆（记忆封存写回目标）
    var userRelationType: String = "",
    var userInteractionModel: String = "",
    var userRelationBottomLine: String = "",
    var keyEvents: String = ""
) {
    /** 是否有任何结构化内容 */
    fun isEmpty(): Boolean {
        return age.isBlank() && gender.isBlank() && race.isBlank() && birthplace.isBlank() &&
            occupation.isBlank() && socialClass.isBlank() && identityTags.isEmpty() &&
            heightBuild.isBlank() && iconicFeatures.isBlank() && clothingStyle.isBlank() && overallVibe.isBlank() &&
            externalPersonality.isBlank() && internalPersonality.isBlank() && coreDesire.isBlank() &&
            fearWeakness.isBlank() && moralValues.isBlank() && quirk.isBlank() &&
            skills.isBlank() && backgroundStory.isBlank() && relationships.isBlank() &&
            speakingStyle.isBlank() && typicalReactions.isBlank() &&
            userRelationType.isBlank() && userInteractionModel.isBlank() &&
            userRelationBottomLine.isBlank() && keyEvents.isBlank()
    }
}
