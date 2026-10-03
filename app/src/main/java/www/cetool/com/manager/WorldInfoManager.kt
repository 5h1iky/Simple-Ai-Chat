package www.cetool.com.manager

import android.content.Context
import com.google.gson.Gson
import www.cetool.com.model.WorldEntry
import www.cetool.com.model.WorldInfo
import java.io.File

/** B26：解析失败的条目用这个名字标记（判定艺术品：界面识别并展示，引擎跳过不注入） */
const val BROKEN_ENTRY_NAME = "__broken__"

object WorldInfoManager {

    private const val DIR_NAME = "worldinfo"
    private val gson = Gson()

    private fun getDir(context: Context): File {
        return File(context.filesDir, DIR_NAME).also { it.mkdirs() }
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
        val file = File(getDir(context), "$id.json")
        if (!file.exists()) return null
        return try {
            val json = file.readText()
            val root = com.google.gson.JsonParser.parseString(json).asJsonObject
            val data = root.getAsJsonObject("data") ?: root
            WorldInfo(
                id = id,
                name = data.get("name")?.asString ?: "",
                description = data.get("description")?.asString ?: "",
                enabled = data.get("enabled")?.asBoolean ?: true,
                entries = parseEntries(data)
            )
        } catch (_: Exception) { null }
    }

    /** 读取世界书原始 JSON（导出用） */
    fun rawJson(context: Context, id: String): String? {
        val file = File(getDir(context), "$id.json")
        return if (file.exists()) try { file.readText() } catch (_: Exception) { null } else null
    }

    private fun parseEntries(data: com.google.gson.JsonObject): MutableList<WorldEntry> {
        val entries = mutableListOf<WorldEntry>()
        val entriesArray = data.getAsJsonArray("entries") ?: return entries
        for (element in entriesArray) {
            try {
                val obj = element.asJsonObject
                val entry = WorldEntry(
                    id = obj.get("id")?.asString ?: java.util.UUID.randomUUID().toString().take(8),
                    name = obj.get("name")?.asString ?: "",
                    enabled = obj.get("enabled")?.asBoolean ?: true,
                    priority = obj.get("priority")?.asInt ?: 1000,
                    position = obj.get("position")?.asString ?: "after_system_prompt",
                    content = obj.get("content")?.asString ?: "",
                    injectDepth = obj.get("injectDepth")?.asInt ?: 4,
                    role = obj.get("role")?.asString,
                    keywords = obj.getAsJsonArray("keywords")?.map { it.asString }?.toMutableList() ?: mutableListOf(),
                    useRegex = obj.get("useRegex")?.asBoolean ?: false,
                    caseSensitive = obj.get("caseSensitive")?.asBoolean ?: false,
                    scanDepth = obj.get("scanDepth")?.asInt ?: 4,
                    constantActive = obj.get("constantActive")?.asBoolean ?: false,
                    stPosition = obj.get("stPosition")?.asInt ?: -1,
                    wholeWords = obj.get("wholeWords")?.asBoolean ?: true,
                    secondaryKeys = obj.getAsJsonArray("secondaryKeys")?.map { it.asString }?.toMutableList() ?: mutableListOf(),
                    selective = obj.get("selective")?.asBoolean ?: false,
                    selectiveLogic = obj.get("selectiveLogic")?.asInt ?: 0,
                    probability = obj.get("probability")?.asInt ?: 100,
                    useProbability = obj.get("useProbability")?.asBoolean ?: false,
                    groupName = obj.get("groupName")?.asString ?: "",
                    groupWeight = obj.get("groupWeight")?.asInt ?: 100,
                    excludeRecursion = obj.get("excludeRecursion")?.asBoolean ?: false,
                    preventRecursion = obj.get("preventRecursion")?.asBoolean ?: false,
                    delayUntilRecursion = obj.get("delayUntilRecursion")?.asBoolean ?: false,
                    automationId = obj.get("automationId")?.asString ?: "",
                    score = obj.get("score")?.asInt ?: 0,
                    sticky = obj.get("sticky")?.asInt ?: 0,
                    cooldown = obj.get("cooldown")?.asInt ?: 0,
                    delay = obj.get("delay")?.asInt ?: 0,
                    displayIndex = obj.get("displayIndex")?.asInt ?: 0,
                    comment = obj.get("comment")?.asString ?: "",
                    vectorized = obj.get("vectorized")?.asBoolean ?: false,
                    triggers = obj.getAsJsonArray("triggers")?.map { it.asString }?.toMutableList() ?: mutableListOf(),
                    injectRole = obj.get("injectRole")?.asString
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

    fun saveNew(context: Context, info: WorldInfo): String {
        // 防呆：id 为空时重新生成（旧实现曾把缺失 id 写成 "null.json" 互相覆盖）
        val id = if (info.id.isNullOrBlank()) java.util.UUID.randomUUID().toString().take(8) else info.id
        val safe = info.copy(id = id)
        val json = buildJson(safe)
        val file = File(getDir(context), "$id.json")
        // C4：原子写入 + IOException 保护（裸 writeText 会让磁盘满/权限异常直接崩溃）
        www.cetool.com.util.SafeFiles.writeAtomic(file, json) { it.printStackTrace() }
        return id
    }

    fun overwrite(context: Context, id: String, info: WorldInfo): Boolean {
        val file = File(getDir(context), "$id.json")
        if (!file.exists()) return saveNew(context, info).let { true }
        return www.cetool.com.util.SafeFiles.writeAtomic(file, buildJson(info)) { it.printStackTrace() }
    }

    fun delete(context: Context, id: String) {
        File(getDir(context), "$id.json").delete()
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
