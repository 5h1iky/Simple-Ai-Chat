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
        TavernCard(spec = spec, spec_version = root.get("spec_version")?.asString ?: "3.0", data = card)
    }

    fun export(card: TavernCard): String {
        val root = com.google.gson.JsonObject()
        root.addProperty("spec", card.spec)
        root.addProperty("spec_version", card.spec_version)
        root.add("data", gson.toJsonTree(card.data))
        return gson.toJson(root)
    }
}
