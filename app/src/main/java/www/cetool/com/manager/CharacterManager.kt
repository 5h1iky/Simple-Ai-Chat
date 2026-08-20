package www.cetool.com.manager

import android.content.Context
import www.cetool.com.importer.TavernCardImporter
import www.cetool.com.importer.WorldInfoImporter
import www.cetool.com.model.CharacterInfo
import www.cetool.com.model.TavernCard
import java.io.File
import java.util.UUID

object CharacterManager {

    private const val DIR_NAME = "characters"

    private fun getDir(context: Context): File {
        return File(context.filesDir, DIR_NAME).also { it.mkdirs() }
    }

    fun list(context: Context): List<CharacterInfo> {
        val dir = getDir(context)
        return dir.listFiles()
            ?.filter { it.extension == "json" }
            ?.mapNotNull { file ->
                try {
                    val json = file.readText()
                    val card = TavernCardImporter.parse(json).getOrNull() ?: return@mapNotNull null
                    CharacterInfo(
                        id = file.nameWithoutExtension,
                        name = card.data.name,
                        avatarBase64 = card.avatarBase64,
                        description = card.data.description.take(100),
                        tags = card.data.getCharacterFields().identityTags,
                        importedAt = file.lastModified()
                    )
                } catch (_: Exception) { null }
            }
            ?.sortedByDescending { it.importedAt }
            ?: emptyList()
    }

    fun loadCard(context: Context, characterId: String): TavernCard? {
        val file = File(getDir(context), "$characterId.json")
        if (!file.exists()) return null
        return try {
            TavernCardImporter.parse(file.readText()).getOrNull()
        } catch (_: Exception) { null }
    }

    fun save(context: Context, json: String): String? {
        val card = TavernCardImporter.parse(json).getOrNull() ?: return null
        val id = UUID.randomUUID().toString().take(8)
        // 角色卡内嵌世界书（酒馆 extensions.world_book）：自动建书并绑定
        val finalCard = processEmbeddedWorldBook(context, card)
        val file = File(getDir(context), "$id.json")
        file.writeText(TavernCardImporter.export(finalCard))
        return id
    }

    /**
     * 检测角色卡内嵌世界书（SillyTavern 格式 extensions.world_book）：
     * 用 WorldInfoImporter 解析建书，并把新书 id 写入 extensions["sachat_worldbook_id"]。
     */
    private fun processEmbeddedWorldBook(context: Context, card: TavernCard): TavernCard {
        val wb = card.data.extensions["world_book"] ?: return card
        if (!wb.isJsonObject) return card
        val result = WorldInfoImporter.parse(wb.toString())
        if (result is WorldInfoImporter.ImportResult.Success && result.info.entries.isNotEmpty()) {
            val wid = WorldInfoManager.saveNew(context, result.info)
            val newExtensions = card.data.extensions.toMutableMap()
            newExtensions["sachat_worldbook_id"] = com.google.gson.JsonPrimitive(wid)
            return card.copy(data = card.data.copy(extensions = newExtensions))
        }
        return card
    }

    fun overwrite(context: Context, characterId: String, json: String): Boolean {
        val file = File(getDir(context), "$characterId.json")
        if (!file.exists()) return false
        file.writeText(json)
        return true
    }

    fun delete(context: Context, characterId: String) {
        val file = File(getDir(context), "$characterId.json")
        if (file.exists()) file.delete()
    }

    fun assembleSystemPrompt(card: TavernCard): String {
        return CharacterCompiler.assembleSystemPrompt(card)
    }

    /** 编译角色定义（含示例对话分段），供世界书按酒馆位置注入 */
    fun compileCharacter(card: TavernCard): CharacterCompiler.CompiledCharacter {
        return CharacterCompiler.compileCharacter(card)
    }
}
