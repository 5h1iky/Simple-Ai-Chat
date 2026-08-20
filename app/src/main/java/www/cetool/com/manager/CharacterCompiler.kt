package www.cetool.com.manager

import www.cetool.com.model.CharacterFields
import www.cetool.com.model.TavernCard
import www.cetool.com.model.TavernCardData

/**
 * 角色卡编译层：结构化字段 ↔ Markdown（借鉴 MuseAI 的 compileItemToMarkdown 设计）。
 * 一份数据多处复用：表单编辑 / 效果预览 / 系统提示词注入 / 记忆封存上下文。
 */
object CharacterCompiler {

    /** 编译结果分段：角色定义与示例对话分离，便于世界书按酒馆位置注入 */
    data class CompiledCharacter(
        /** 角色定义主体（基础信息/标签/外貌/性格/技能/背景/关系/说话/反应/记忆） */
        val definition: String,
        /** 示例对话块（可为空） */
        val examples: String
    )

    /** 结构化字段 → Markdown（预览/提示词共用） */
    fun compileMarkdown(name: String, fields: CharacterFields): String {
        val sb = StringBuilder()

        // 基础信息
        val basic = buildListSection(
            "基础信息",
            listOf(
                "姓名" to name,
                "年龄" to fields.age,
                "性别" to fields.gender,
                "种族" to fields.race,
                "出生地" to fields.birthplace,
                "职业" to fields.occupation,
                "社会阶层" to fields.socialClass
            )
        )
        if (basic.isNotBlank()) sb.append(basic).append("\n\n")

        // 身份标签
        if (fields.identityTags.isNotEmpty()) {
            sb.append("## 身份标签\n")
            sb.append(fields.identityTags.joinToString(" ") { "`$it`" }).append("\n\n")
        }

        // 外貌气质
        val appearance = buildListSection(
            "外貌气质",
            listOf(
                "身高体型" to fields.heightBuild,
                "标志性特征" to fields.iconicFeatures,
                "衣着风格" to fields.clothingStyle,
                "整体气质" to fields.overallVibe
            )
        )
        if (appearance.isNotBlank()) sb.append(appearance).append("\n\n")

        // 性格特征
        val personality = buildListSection(
            "性格特征",
            listOf(
                "外在性格" to fields.externalPersonality,
                "内在性格" to fields.internalPersonality,
                "核心欲望" to fields.coreDesire,
                "恐惧与弱点" to fields.fearWeakness,
                "道德观念" to fields.moralValues,
                "怪癖" to fields.quirk
            )
        )
        if (personality.isNotBlank()) sb.append(personality).append("\n\n")

        // 技能与经历
        if (fields.skills.isNotBlank()) sb.append("## 技能专长\n${fields.skills}\n\n")
        if (fields.backgroundStory.isNotBlank()) sb.append("## 背景故事\n${fields.backgroundStory}\n\n")
        if (fields.relationships.isNotBlank()) sb.append("## 人际关系\n${fields.relationships}\n\n")

        // 说话方式
        if (fields.speakingStyle.isNotBlank()) sb.append("## 说话方式\n${fields.speakingStyle}\n\n")
        if (fields.typicalReactions.isNotBlank()) sb.append("## 典型反应\n${fields.typicalReactions}\n\n")

        // 角色记忆
        val memory = buildListSection(
            "角色记忆",
            listOf(
                "与用户关系类型" to fields.userRelationType,
                "与用户相处模式" to fields.userInteractionModel,
                "与用户关系底线" to fields.userRelationBottomLine
            )
        )
        if (memory.isNotBlank()) sb.append(memory).append("\n\n")
        if (fields.keyEvents.isNotBlank()) sb.append("## 关键事件\n${fields.keyEvents}\n\n")

        val result = sb.toString().trim()
        return if (result.isEmpty()) "" else "# 角色卡：$name\n\n$result"
    }

    /** 编译完整角色定义（结构化字段优先，旧字段兜底，保持向后兼容） */
    fun compileCharacter(card: TavernCard): CompiledCharacter {
        val data = card.data
        val fields = data.getCharacterFields()

        val definition = if (!fields.isEmpty()) {
            compileMarkdown(data.name, fields)
        } else {
            // 旧字段兜底（无结构化字段的卡）
            val parts = mutableListOf<String>()
            if (data.description.isNotBlank()) parts.add("[角色设定]\n${data.description}")
            if (data.personality.isNotBlank()) parts.add("[性格]\n${data.personality}")
            if (data.scenario.isNotBlank()) parts.add("[场景]\n${data.scenario}")
            parts.joinToString("\n\n")
        }

        val examples = if (data.mes_example.isNotBlank()) {
            "[示例对话]\n${data.mes_example}"
        } else ""

        return CompiledCharacter(definition, examples)
    }

    /** 系统提示词组装（兼容旧 assembleSystemPrompt 语义，输出为单一文本） */
    fun assembleSystemPrompt(card: TavernCard): String {
        val compiled = compileCharacter(card)
        val parts = mutableListOf<String>()
        if (compiled.definition.isNotBlank()) parts.add(compiled.definition)
        if (compiled.examples.isNotBlank()) parts.add(compiled.examples)
        if (card.data.system_prompt.isNotBlank()) parts.add(card.data.system_prompt)
        return parts.joinToString("\n\n")
    }

    private fun buildListSection(title: String, items: List<Pair<String, String>>): String {
        val lines = items.mapNotNull { (label, value) ->
            val v = value.trim()
            if (v.isNotEmpty()) "- **$label**：$v" else null
        }
        return if (lines.isEmpty()) "" else "## $title\n${lines.joinToString("\n")}"
    }
}
