package www.cetool.com.manager

import android.content.Context
import com.google.gson.Gson
import www.cetool.com.model.WorldEntry
import www.cetool.com.model.WorldInfo
import java.io.File

/** B26：解析失败的条目用这个名字标记（判定艺术品：界面识别并展示，引擎跳过不注入） */
const val BROKEN_ENTRY_NAME = "__broken__"

/**
 * M11：显式 JSON null（如 `"name": null`）时 Gson 的 asString/asBoolean/asInt 会抛
 * UnsupportedOperationException，被上层 `catch (_: Exception) { null }` 吞掉后，
 * 整本世界书从列表「静默消失」、绑定失效（文件其实还在）。
 * 取值前统一排除 JsonNull；非 null 的类型错误（如把对象当字符串）仍照旧抛出，
 * 由 B26 的损坏哨兵条目机制呈现，不在这里兜底成默认值。
 */
private fun com.google.gson.JsonElement?.asStringOrNull(): String? =
    this?.takeIf { !it.isJsonNull }?.asString

private fun com.google.gson.JsonElement?.asBooleanOrNull(): Boolean? =
    this?.takeIf { !it.isJsonNull }?.asBoolean

private fun com.google.gson.JsonElement?.asIntOrNull(): Int? =
    this?.takeIf { !it.isJsonNull }?.asInt

private fun com.google.gson.JsonElement?.asJsonArrayOrNull(): com.google.gson.JsonArray? =
    this?.takeIf { !it.isJsonNull }?.asJsonArray

object WorldInfoManager {

    private const val DIR_NAME = "worldinfo"
    private val gson = Gson()

    private fun getDir(context: Context): File {
        return File(context.filesDir, DIR_NAME).also { it.mkdirs() }
    }

    /**
     * P1（路径穿越）：id 可能来自外部导入的卡 JSON（`sachat_worldbook_id` 等，不可信），
     * 直接拼 `File(dir, "$id.json")` 会让 `"../characters/xxxx"` 落到别的数据文件上删除/覆盖。
     * 只接受纯文件名形态的 id，其余一律拒绝。
     */
    private fun fileOf(context: Context, id: String): File? {
        if (id.isBlank() || id.contains('/') || id.contains('\\') || id.contains("..")) return null
        return File(getDir(context), "$id.json")
    }

    data class WorldInfoSummary(
        val id: String,
        val name: String,
        val description: String,
        val entryCount: Int,
        val enabled: Boolean,
        val importedAt: Long = System.currentTimeMillis(),
        /** B4：被停用的条目数（`disable: true` 的酒馆条目导入后在此可见） */
        val disabledEntryCount: Int = 0
    )

    fun list(context: Context): List<WorldInfoSummary> {
        val dir = getDir(context)
        return dir.listFiles()
            ?.filter { it.extension == "json" }
            ?.mapNotNull { file ->
                try {
                    val info = load(context, file.nameWithoutExtension) ?: return@mapNotNull null
                    WorldInfoSummary(
                        id = info.id,
                        name = info.name,
                        description = info.description,
                        entryCount = info.entries.size,
                        enabled = info.enabled,
                        importedAt = file.lastModified(),
                        disabledEntryCount = info.entries.count { !it.enabled }
                    )
                } catch (_: Exception) { null }
            }
            ?.sortedByDescending { it.importedAt }
            ?: emptyList()
    }

    fun load(context: Context, id: String): WorldInfo? {
        val file = fileOf(context, id) ?: return null
        if (!file.exists()) return null
        return try {
            val json = file.readText()
            val root = com.google.gson.JsonParser.parseString(json).asJsonObject
            // M11：`"data": null` 时 getAsJsonObject 抛异常 → 外层 catch 吞掉 → 书从
            // 列表消失。把 JsonNull 的 data 当作「没有 data」回退到根节点；
            // 真正的类型错误（如 data 是字符串）仍抛出，交由 P2 的加载失败保护兜底。
            val dataEl = root.get("data")
            val data = if (dataEl == null || dataEl.isJsonNull) root else dataEl.asJsonObject
            WorldInfo(
                id = id,
                name = data.get("name").asStringOrNull() ?: "",
                description = data.get("description").asStringOrNull() ?: "",
                enabled = data.get("enabled").asBooleanOrNull() ?: true,
                entries = parseEntries(data)
            )
        } catch (_: Exception) { null }
    }

    /** 读取世界书原始 JSON（**自有格式**，仅供内部搬运） */
    fun rawJson(context: Context, id: String): String? {
        val file = fileOf(context, id) ?: return null
        return if (file.exists()) www.cetool.com.util.SafeFiles.readText(file) else null
    }

    /**
     * B27：导出为**酒馆 SillyTavern 标准世界书**格式（委托给纯函数实现，
     * 便于脱离 Android 做往返一致性验证）。
     */
    fun exportSillyTavern(context: Context, id: String): String? {
        val info = load(context, id) ?: return null
        return SillyTavernExporter.toJson(info)
    }

    private fun parseEntries(data: com.google.gson.JsonObject): MutableList<WorldEntry> {
        val entries = mutableListOf<WorldEntry>()
        // M11：`"entries": null` 时 getAsJsonArray 的强转会抛 ClassCastException，
        // 同样被外层 catch 吞掉 → 书从列表消失。null 视为「没有条目」。
        val entriesArray = data.get("entries").asJsonArrayOrNull() ?: return entries
        for (element in entriesArray) {
            try {
                val obj = element.asJsonObject
                // M11：以下取值全部排除 JsonNull（见文件头 asStringOrNull 等小工具），
                // 否则 `"name": null` 这类条目会被整条丢进 B26 损坏哨兵。
                val entry = WorldEntry(
                    id = obj.get("id").asStringOrNull() ?: java.util.UUID.randomUUID().toString().take(8),
                    name = obj.get("name").asStringOrNull() ?: "",
                    enabled = obj.get("enabled").asBooleanOrNull() ?: true,
                    priority = obj.get("priority").asIntOrNull() ?: 1000,
                    position = obj.get("position").asStringOrNull() ?: "after_system_prompt",
                    content = obj.get("content").asStringOrNull() ?: "",
                    injectDepth = obj.get("injectDepth").asIntOrNull() ?: 4,
                    role = obj.get("role").asStringOrNull(),
                    keywords = obj.get("keywords").asJsonArrayOrNull()
                        ?.mapNotNull { it.asStringOrNull() }?.toMutableList() ?: mutableListOf(),
                    useRegex = obj.get("useRegex").asBooleanOrNull() ?: false,
                    caseSensitive = obj.get("caseSensitive").asBooleanOrNull() ?: false,
                    scanDepth = obj.get("scanDepth").asIntOrNull() ?: 4,
                    constantActive = obj.get("constantActive").asBooleanOrNull() ?: false,
                    stPosition = obj.get("stPosition").asIntOrNull() ?: -1,
                    wholeWords = obj.get("wholeWords").asBooleanOrNull() ?: true,
                    secondaryKeys = obj.get("secondaryKeys").asJsonArrayOrNull()
                        ?.mapNotNull { it.asStringOrNull() }?.toMutableList() ?: mutableListOf(),
                    selective = obj.get("selective").asBooleanOrNull() ?: false,
                    selectiveLogic = obj.get("selectiveLogic").asIntOrNull() ?: 0,
                    probability = obj.get("probability").asIntOrNull() ?: 100,
                    useProbability = obj.get("useProbability").asBooleanOrNull() ?: false,
                    groupName = obj.get("groupName").asStringOrNull() ?: "",
                    groupWeight = obj.get("groupWeight").asIntOrNull() ?: 100,
                    excludeRecursion = obj.get("excludeRecursion").asBooleanOrNull() ?: false,
                    preventRecursion = obj.get("preventRecursion").asBooleanOrNull() ?: false,
                    delayUntilRecursion = obj.get("delayUntilRecursion").asBooleanOrNull() ?: false,
                    automationId = obj.get("automationId").asStringOrNull() ?: "",
                    score = obj.get("score").asIntOrNull() ?: 0,
                    sticky = obj.get("sticky").asIntOrNull() ?: 0,
                    cooldown = obj.get("cooldown").asIntOrNull() ?: 0,
                    delay = obj.get("delay").asIntOrNull() ?: 0,
                    displayIndex = obj.get("displayIndex").asIntOrNull() ?: 0,
                    comment = obj.get("comment").asStringOrNull() ?: "",
                    vectorized = obj.get("vectorized").asBooleanOrNull() ?: false,
                    triggers = obj.get("triggers").asJsonArrayOrNull()
                        ?.mapNotNull { it.asStringOrNull() }?.toMutableList() ?: mutableListOf(),
                    injectRole = obj.get("injectRole").asStringOrNull()
                )
                entries.add(entry)
            } catch (e: Exception) {
                // B26：原实现在这里静默 `catch (_: Exception) {}` —— 任何解析失败的条目被
                // 悄悄丢弃，用户完全看不到；一旦在编辑页保存，丢失就被固化进文件。
                // 现在把失败原因挂到一个哨兵条目上，界面可以显示出来。
                val reason = e.message ?: e.javaClass.simpleName
                entries.add(
                    WorldEntry(
                        id = "broken_${entries.size}",
                        name = BROKEN_ENTRY_NAME,
                        content = reason,
                        comment = element.toString()
                    )
                )
            }
        }
        return entries
    }

    /** 计算安全的落盘 id：空 id 重新生成；非法形态（路径穿越）一律重新生成 */
    private fun safeId(context: Context, info: WorldInfo): String =
        // 防呆：id 为空时重新生成（旧实现曾把缺失 id 写成 "null.json" 互相覆盖）
        // P1：id 可能来自导入文件（不可信），非法形态一律重新生成，防止路径穿越
        info.id?.takeIf { fileOf(context, it) != null }
            ?: java.util.UUID.randomUUID().toString().take(8)

    /**
     * M7：保存新世界书，**写盘失败返回 null**（不再吞掉 SafeFiles.writeAtomic 的失败
     * 仍返回 id —— 那样调用方拿到「成功」但文件根本没写进去，磁盘满/IO 异常时数据全丢）。
     * 成功返回真正落盘的 id（可能是重新生成的）。
     */
    fun saveNewOrNull(context: Context, info: WorldInfo): String? {
        val id = safeId(context, info)
        val safe = info.copy(id = id)
        val json = buildJson(safe)
        val file = File(getDir(context), "$id.json")
        // C4：原子写入 + IOException 保护（裸 writeText 会让磁盘满/权限异常直接崩溃）
        val ok = www.cetool.com.util.SafeFiles.writeAtomic(file, json) { it.printStackTrace() }
        return if (ok) id else null
    }

    /**
     * 兼容入口：**不检查写盘结果**（失败仍返回 id，与旧版 saveNew 语义一致）。
     * ChatActivity / WorldInfoListActivity / CharacterManager 的旧调用方暂用它承接
     * （它们各自不在本次修改范围内）；新代码请用 [saveNewOrNull] 并处理 null。
     */
    fun saveNew(context: Context, info: WorldInfo): String {
        val id = safeId(context, info)
        saveNewOrNull(context, info.copy(id = id))
        return id
    }

    fun overwrite(context: Context, id: String, info: WorldInfo): Boolean {
        val file = fileOf(context, id) ?: return false
        // M7：目标文件必须始终对应参数 id —— 旧实现在「文件不存在」的回退路径上调
        // saveNew(context, info)，实际写到的是 info.id（或重新生成的 id）的文件上，
        // 参数 id 对应的文件根本没被写入却返回 true（目标文件错位 + 假成功）。
        // writeAtomic 本身能创建新文件，无需回退；info.id 与 id 不一致时统一改写成 id，
        // 保证文件名与内嵌 id 一致。
        val safe = if (info.id == id) info else info.copy(id = id)
        return www.cetool.com.util.SafeFiles.writeAtomic(file, buildJson(safe)) { it.printStackTrace() }
    }

    fun delete(context: Context, id: String) {
        // P1：拒绝非法 id（路径穿越），防止删到其它数据文件
        fileOf(context, id)?.delete()
    }

    private fun buildJson(info: WorldInfo): String {
        val root = com.google.gson.JsonObject()
        root.addProperty("version", 1)
        root.addProperty("type", "lorebook")
        val data = com.google.gson.JsonObject()
        data.addProperty("id", info.id)
        data.addProperty("name", info.name)
        data.addProperty("description", info.description)
        data.addProperty("enabled", info.enabled)
        val entries = com.google.gson.JsonArray()
        for (entry in info.entries) {
            // B26：不要把「损坏哨兵」写回文件（那会真的把原条目替换掉）。
            // 保留原始节点即可实现「保存后原始 JSON 字段仍在」。
            if (entry.name == BROKEN_ENTRY_NAME) {
                val raw = runCatching {
                    com.google.gson.JsonParser.parseString(entry.comment).takeIf { it.isJsonObject }
                }.getOrNull()
                if (raw != null) {
                    entries.add(raw)
                    continue
                }
            }
            val obj = com.google.gson.JsonObject()
            obj.addProperty("id", entry.id)
            obj.addProperty("name", entry.name)
            obj.addProperty("enabled", entry.enabled)
            obj.addProperty("priority", entry.priority)
            obj.addProperty("position", entry.position)
            obj.addProperty("content", entry.content)
            obj.addProperty("injectDepth", entry.injectDepth)
            if (entry.role != null) obj.addProperty("role", entry.role)
            obj.add("keywords", entry.keywords?.let { arr ->
                val ja = com.google.gson.JsonArray(); arr.forEach { ja.add(it) }; ja
            } ?: com.google.gson.JsonArray())
            obj.addProperty("useRegex", entry.useRegex)
            obj.addProperty("caseSensitive", entry.caseSensitive)
            obj.addProperty("scanDepth", entry.scanDepth)
            obj.addProperty("constantActive", entry.constantActive)
            // ─── 酒馆对标字段往返保存 ───
            obj.addProperty("stPosition", entry.stPosition)
            obj.addProperty("wholeWords", entry.wholeWords)
            obj.add("secondaryKeys", entry.secondaryKeys?.let { arr ->
                val ja = com.google.gson.JsonArray(); arr.forEach { ja.add(it) }; ja
            } ?: com.google.gson.JsonArray())
            obj.addProperty("selective", entry.selective)
            obj.addProperty("selectiveLogic", entry.selectiveLogic)
            obj.addProperty("probability", entry.probability)
            obj.addProperty("useProbability", entry.useProbability)
            obj.addProperty("groupName", entry.groupName)
            obj.addProperty("groupWeight", entry.groupWeight)
            obj.addProperty("excludeRecursion", entry.excludeRecursion)
            obj.addProperty("preventRecursion", entry.preventRecursion)
            obj.addProperty("delayUntilRecursion", entry.delayUntilRecursion)
            obj.addProperty("automationId", entry.automationId)
            obj.addProperty("score", entry.score)
            obj.addProperty("sticky", entry.sticky)
            obj.addProperty("cooldown", entry.cooldown)
            obj.addProperty("delay", entry.delay)
            obj.addProperty("displayIndex", entry.displayIndex)
            obj.addProperty("comment", entry.comment)
            obj.addProperty("vectorized", entry.vectorized)
            obj.add("triggers", entry.triggers?.let { arr ->
                val ja = com.google.gson.JsonArray(); arr.forEach { ja.add(it) }; ja
            } ?: com.google.gson.JsonArray())
            if (entry.injectRole != null) obj.addProperty("injectRole", entry.injectRole)
            entries.add(obj)
        }
        data.add("entries", entries)
        root.add("data", data)
        return gson.toJson(root)
    }
}