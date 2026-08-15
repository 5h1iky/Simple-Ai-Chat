package www.cetool.com.manager

import android.content.Context
import www.cetool.com.importer.TavernCardImporter
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
                        description = card.data.description.take(100),
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
        val file = File(getDir(context), "$id.json")
        file.writeText(json)
        return id
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
        val parts = mutableListOf<String>()

        if (card.data.description.isNotBlank()) {
            parts.add("[角色设定]\n${card.data.description}")
        }
        if (card.data.personality.isNotBlank()) {
            parts.add("[性格]\n${card.data.personality}")
        }
        if (card.data.scenario.isNotBlank()) {
            parts.add("[场景]\n${card.data.scenario}")
        }
        if (card.data.system_prompt.isNotBlank()) {
            parts.add(card.data.system_prompt)
        }
        if (card.data.mes_example.isNotBlank()) {
            parts.add("[示例对话]\n${card.data.mes_example}")
        }

        return parts.joinToString("\n\n")
    }
}
