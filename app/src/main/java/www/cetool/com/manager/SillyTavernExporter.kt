package www.cetool.com.manager

import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import www.cetool.com.model.WorldEntry
import www.cetool.com.model.WorldInfo

/**
 * B27：把世界书序列化成**酒馆 SillyTavern 标准格式**。
 *
 * 为什么需要它：本项目 `WorldInfoManager.buildJson` 写出的是
 * `{version, type:"lorebook", data:{..., entries:[...]}}`（**数组** + camelCase），
 * 而酒馆世界书是 `{entries:{uid:{keys:[...], insertion_order:...}}}`（**对象** + snake_case）。
 * 编辑一次后导出再用酒馆导入会失败/丢键。
 *
 * 本对象刻意**不依赖任何 Android API**（只用 Gson + 模型），
 * 这样往返一致性可以脱离设备直接做单元验证 —— 审计 §7.4 把
 * 「WorldEntry 保存/加载往返完整」列为硬性要求。
 */
object SillyTavernExporter {

    /**
     * 酒馆 position 编号 → 本项目位置槽位号。
     *
     * 与 [www.cetool.com.importer.WorldInfoImporter] 的 ST_POSITION_MAP 同义，
     * 只是用槽位号表达以便比较：
     * 0 before / 1 after / 2 ANTop / 3 ANBottom / 4 atDepth / 5 EMTop / 6 EMBottom / 7 outlet
     */
    private val ST_SLOT_OF = mapOf(
        0 to 0, 1 to 1, 2 to 2, 3 to 3, 4 to 4, 5 to 2, 6 to 3, 7 to 7
    )

    fun toJson(info: WorldInfo): String {
        val root = JsonObject()
        root.addProperty("name", info.name)
        if (info.description.isNotBlank()) root.addProperty("description", info.description)

        val entries = JsonObject()
        info.entries.forEachIndexed { index, e ->
            entries.add(index.toString(), entryToJson(e))
        }
        root.add("entries", entries)
        return GsonBuilder().setPrettyPrinting().create().toJson(root)
    }

    private fun entryToJson(e: WorldEntry): JsonObject {
        val obj = JsonObject()
        val pos = stPositionFor(e)

        obj.add("keys", JsonArray().apply { e.keywords.forEach { add(it) } })

        if (e.secondaryKeys.isNotEmpty()) {
            obj.add("secondary_keys", JsonArray().apply { e.secondaryKeys.forEach { add(it) } })
        }

        obj.addProperty("content", e.content)
        // 往返完整性：WorldEntry 有 name 与 comment 两个独立字段，而酒馆格式只有 `comment`。
        // 若只导出 comment，则「name=A, comment=B」往返后会变成 name=B（A 丢失）。
        // 因此两个都写：comment 回落 name 保证显示名不丢，另写 name 供本项目导入器读取。
        val displayName = e.comment.ifBlank { e.name }
        if (displayName.isNotBlank()) obj.addProperty("comment", displayName)
        if (e.name.isNotBlank()) obj.addProperty("name", e.name)

        obj.addProperty("constant", e.constantActive)
        obj.addProperty("selective", e.selective)
        obj.addProperty("selectiveLogic", e.selectiveLogic)
        // `order` 与 `insertion_order` 都必须写 priority。
        // 早先 order 写的是数组下标，而酒馆读 order → 条目顺序变成文件里的位置，priority 失效。
        obj.addProperty("order", e.priority)
        obj.addProperty("insertion_order", e.priority)
        obj.addProperty("enabled", e.enabled)
        obj.addProperty("disable", !e.enabled)
        obj.addProperty("position", pos)
        obj.addProperty("probability", e.probability)
        obj.addProperty("useProbability", e.useProbability)
        obj.addProperty("score", e.score)
        // 正则标志必须写在**顶层**：本项目导入器只读顶层 useRegex
        //（酒馆自身放在 extensions.use_regex，两处都写以同时满足）
        obj.addProperty("useRegex", e.useRegex)
        if (e.groupName.isNotBlank()) obj.addProperty("group", e.groupName)
        if (e.triggers.isNotEmpty()) {
            obj.add("triggers", JsonArray().apply { e.triggers.forEach { add(it) } })
        }

        val ext = JsonObject()
        ext.addProperty("position", pos)
        ext.addProperty("depth", e.injectDepth)
        ext.addProperty("scan_depth", e.scanDepth)
        ext.addProperty("probability", e.probability)
        ext.addProperty("useProbability", e.useProbability)
        ext.addProperty("use_regex", e.useRegex)
        ext.addProperty("match_whole_words", e.wholeWords)
        ext.addProperty("case_sensitive", e.caseSensitive)
        ext.addProperty("group_weight", e.groupWeight)
        ext.addProperty("exclude_recursion", e.excludeRecursion)
        ext.addProperty("prevent_recursion", e.preventRecursion)
        ext.addProperty("delay_until_recursion", e.delayUntilRecursion)
        ext.addProperty("automation_id", e.automationId)
        ext.addProperty("sticky", e.sticky)
        ext.addProperty("cooldown", e.cooldown)
        ext.addProperty("delay", e.delay)
        ext.addProperty("display_index", e.displayIndex)
        ext.addProperty("vectorized", e.vectorized)
        e.injectRole?.let { ext.addProperty("role", it) }
        obj.add("extensions", ext)

        return obj
    }

    /**
     * 本项目位置字符串 → 酒馆 position 编号。
     *
     * 优先按**当前** `position` 映射 —— `stPosition` 是导入时的原始编号，
     * 而条目编辑器只更新 `position`、从不改 `stPosition`，
     * 因此若无条件优先 stPosition，「界面上改过的注入位置」会在导出时被静默回退。
     *
     * 例外：当原始编号映射到的槽位与当前 position 一致时保留原始编号
     *（例如酒馆的 5=EMTop 与 2=ANTop 在本项目里是同一个槽位，
     * 用户没动过位置就应原样导出，而不是被压成 2）。
     */
    fun stPositionFor(e: WorldEntry): Int {
        val mapped = when (e.position) {
            "before_char_defs" -> 0
            "before_example_messages" -> 2
            "after_example_messages" -> 3
            "at_depth" -> 4
            "outlet" -> 7
            else -> 1 // after_char_defs / after_system_prompt
        }
        if (e.stPosition >= 0 && ST_SLOT_OF[e.stPosition] == mapped) return e.stPosition
        return mapped
    }
}
