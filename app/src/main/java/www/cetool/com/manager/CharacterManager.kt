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

    /** 读取角色卡原始 JSON（导出用） */
    fun rawJson(context: Context, characterId: String): String? {
        val file = File(getDir(context), "$characterId.json")
        return if (file.exists()) try { file.readText() } catch (_: Exception) { null } else null
    }

    fun save(context: Context, json: String): String? {
        val card = TavernCardImporter.parse(json).getOrNull() ?: return null
        // E14：空名卡此前可以导入成功（isVaild 从未被调用），
        // 列表里会出现一张无法辨认的卡。这里统一拦住。
        if (!card.data.isVaild()) return null
        val id = UUID.randomUUID().toString().take(8)
        // 角色卡内嵌世界书（酒馆 extensions.world_book）：自动建书并绑定
        val finalCard = processEmbeddedWorldBook(context, card)
        val file = File(getDir(context), "$id.json")
        // C4：原实现裸 writeText —— 磁盘满/权限异常会抛 IOException 且调用方无保护，
        // 用户内容全丢；写入也不是原子的。这里统一用 try/catch + 临时文件 rename。
        val ok = www.cetool.com.util.SafeFiles.writeAtomic(file, TavernCardImporter.export(finalCard)) { e ->
            e.printStackTrace()
        }
        return if (ok) id else null
    }

    /**
     * 处理角色卡内嵌世界书（酒馆 `data.character_book`）。
     *
     * B1：原实现读的是 `extensions["world_book"]` —— 那不是酒馆的键，
     * 合规的 v2/v3 卡全都写成 `data.character_book`，所以此功能从未触发过。
     * 现在优先处理 `character_book`，保留对 `world_book` 的兼容（少数生成器仍用它）。
     */
    private fun processEmbeddedWorldBook(context: Context, card: TavernCard): TavernCard {
        val book = card.data.character_book
        val result = if (book != null && book.entries.isNotEmpty()) {
            WorldInfoImporter.parseCharacterBook(book, card.data.name.ifBlank { "角色" })
        } else {
            val legacy = card.data.extensions["world_book"]
            if (legacy != null && legacy.isJsonObject) {
                WorldInfoImporter.parse(legacy.toString())
            } else null
        }

        if (result is WorldInfoImporter.ImportResult.Success && result.info.entries.isNotEmpty()) {
            // C3：重复导入同一张卡原本会重复建一本一模一样的书
            val existingId = card.data.character_book?.let { dedupeEmbeddedBook(context, it) }
            val wid = existingId ?: WorldInfoManager.saveNew(context, result.info)
            val newExtensions = card.data.extensions.toMutableMap()
            newExtensions[SACHAT_WORLDBOOK_ID] = com.google.gson.JsonPrimitive(wid)
            return card.copy(data = card.data.copy(extensions = newExtensions))
        }
        return card
    }

    private const val SACHAT_WORLDBOOK_ID = "sachat_worldbook_id"

    fun overwrite(context: Context, characterId: String, json: String): Boolean {
        val file = File(getDir(context), "$characterId.json")
        if (!file.exists()) return false
        return www.cetool.com.util.SafeFiles.writeAtomic(file, json) { it.printStackTrace() }
    }

    fun delete(context: Context, characterId: String) {
        // C3：必须在删掉卡片文件之前读出其关联的内嵌书 id
        val embeddedBookId = loadCard(context, characterId)?.data
            ?.extensions?.get(SACHAT_WORLDBOOK_ID)
            ?.takeIf { it.isJsonPrimitive }?.asString

        File(getDir(context), "$characterId.json").delete()

        // C3：删卡不删它创建的内嵌世界书 → 该书永远留在世界书列表里成为孤儿。
        //
        // M7：但 dedupeEmbeddedBook 会让「两张内嵌同一本书的卡」共用同一个书 id
        //（同卡重复导入、或两张卡带同一份 character_book）。若无脑删除，
        // 删掉其中一张卡就会把另一张卡仍在用的书删掉，那张卡的 lore 静默失效。
        // 因此先确认没有**其他**角色卡仍引用这本书，再删。
        if (!embeddedBookId.isNullOrBlank() && !isBookReferencedByOtherCard(context, embeddedBookId, characterId)) {
            WorldInfoManager.delete(context, embeddedBookId)
        }
    }

    /** M7：所有角色卡当前引用的内嵌世界书 id 集合 */
    private fun allCardBookIds(context: Context): Set<String> {
        val dir = getDir(context)
        return dir.listFiles()
            ?.filter { it.extension == "json" }
            ?.mapNotNull { file ->
                try {
                    TavernCardImporter.parse(www.cetool.com.util.SafeFiles.readText(file) ?: return@mapNotNull null)
                        .getOrNull()?.data?.extensions?.get(SACHAT_WORLDBOOK_ID)
                        ?.takeIf { it.isJsonPrimitive }?.asString
                } catch (_: Exception) {
                    null
                }
            }
            ?.toSet()
            ?: emptySet()
    }

    /** M7：除 excludeCharacterId 外，是否还有角色卡引用该世界书 */
    private fun isBookReferencedByOtherCard(
        context: Context,
        bookId: String,
        excludeCharacterId: String
    ): Boolean {
        val dir = getDir(context)
        return dir.listFiles()
            ?.filter { it.extension == "json" && it.nameWithoutExtension != excludeCharacterId }
            ?.any { file ->
                val card = try {
                    TavernCardImporter.parse(www.cetool.com.util.SafeFiles.readText(file) ?: return@any false)
                        .getOrNull()
                } catch (_: Exception) {
                    null
                }
                card?.data?.extensions?.get(SACHAT_WORLDBOOK_ID)
                    ?.takeIf { it.isJsonPrimitive }?.asString == bookId
            } ?: false
    }

    /**
     * C3：重复导入同一张卡会重复建书。这里在 `save` 前做去重：
     * 若已存在「同源且内容相同」的内嵌书，直接复用它的 id 而不是新建一本。
     *
     * M7：候选只接受**已被某张角色卡引用**的书。否则用户手工建的一本同内容世界书
     * 会被"认领"为这张卡的内嵌书，随后删卡时把用户的书一起删掉。
     */
    private fun dedupeEmbeddedBook(context: Context, book: www.cetool.com.model.CharacterBook): String? {
        val pending = WorldInfoImporter.parseCharacterBook(book, "").let { result ->
            if (result is WorldInfoImporter.ImportResult.Success) result.info else null
        } ?: return null
        val signature = pending.entries.sortedBy { it.name }
            .joinToString("|") { "${it.name}::${it.content}" }
        if (signature.isBlank()) return null

        val cardOwnedBookIds = allCardBookIds(context)

        return WorldInfoManager.list(context).firstOrNull { summary ->
            summary.id in cardOwnedBookIds &&
                summary.entryCount == pending.entries.size &&
                run {
                    val existing = WorldInfoManager.load(context, summary.id) ?: return@run false
                    existing.entries.sortedBy { it.name }
                        .joinToString("|") { "${it.name}::${it.content}" } == signature
                }
        }?.id
    }

    fun assembleSystemPrompt(card: TavernCard): String {
        return CharacterCompiler.assembleSystemPrompt(card)
    }

    /** 编译角色定义（含示例对话分段），供世界书按酒馆位置注入 */
    fun compileCharacter(card: TavernCard): CharacterCompiler.CompiledCharacter {
        return CharacterCompiler.compileCharacter(card)
    }
}
