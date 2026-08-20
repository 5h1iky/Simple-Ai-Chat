package www.cetool.com.importer

import com.google.gson.Gson
import com.google.gson.JsonParser
import www.cetool.com.model.TavernCard

object TavernCardImporter {

    private val gson = Gson()

    fun parse(json: String): Result<TavernCard> = runCatching {
        val root = JsonParser.parseString(json).asJsonObject

        val spec = root.get("spec")?.asString ?: ""
        if (spec != "chara_card_v3" && spec != "chara_card_v2") {
            throw IllegalArgumentException("不支持的格式：$spec")
        }

        val dataObj = root.getAsJsonObject("data")
        if (dataObj == null) {
            throw IllegalArgumentException("缺少 data 字段")
        }

        val card = gson.fromJson(dataObj, www.cetool.com.model.TavernCardData::class.java)
        // 头像：兼容顶层 avatarBase64 字段（本项目旧文件格式）
        val avatar = root.get("avatarBase64")?.takeIf { it.isJsonPrimitive }?.asString
        TavernCard(
            spec = spec,
            spec_version = root.get("spec_version")?.asString ?: "3.0",
            data = card,
            avatarBase64 = avatar
        )
    }

    fun export(card: TavernCard): String {
        val root = com.google.gson.JsonObject()
        root.addProperty("spec", card.spec)
        root.addProperty("spec_version", card.spec_version)
        root.add("data", gson.toJsonTree(card.data))
        if (card.avatarBase64 != null) {
            root.addProperty("avatarBase64", card.avatarBase64)
        }
        return gson.toJson(root)
    }
}
