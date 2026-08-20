package www.cetool.com.manager

import android.content.Context
import com.google.gson.Gson
import www.cetool.com.model.WorldEntry
import www.cetool.com.model.WorldInfo
import java.io.File

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
        val importedAt: Long = System.currentTimeMillis()
    )

    fun list(context: Context): List<WorldInfoSummary> {
        val dir = getDir(context)
        return dir.listFiles()
            ?.filter { it.extension == "json" }
            ?.mapNotNull { file ->
                try {
                    val info = load(context, file.nameWithoutExtension) ?: return@mapNotNull null
                    WorldInfoSummary(info.id, info.name, info.description, info.entries.size, info.enabled, file.lastModified())
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
            } catch (_: Exception) {}
        }
        return entries
    }

    fun saveNew(context: Context, info: WorldInfo): String {
        // 防呆：id 为空时重新生成（旧实现曾把缺失 id 写成 "null.json" 互相覆盖）
        val id = if (info.id.isNullOrBlank()) java.util.UUID.randomUUID().toString().take(8) else info.id
        val safe = info.copy(id = id)
        val json = buildJson(safe)
        val file = File(getDir(context), "$id.json")
        file.writeText(json)
        return id
    }

    fun overwrite(context: Context, id: String, info: WorldInfo): Boolean {
        val file = File(getDir(context), "$id.json")
        if (!file.exists()) return saveNew(context, info).let { true }
        file.writeText(buildJson(info))
        return true
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
