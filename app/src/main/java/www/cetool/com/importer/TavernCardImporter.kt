package www.cetool.com.importer

import com.google.gson.Gson
import com.google.gson.JsonParser
import www.cetool.com.model.TavernCard
import www.cetool.com.model.TavernCardData

object TavernCardImporter {

    // C2：外部卡文件可能含 "field": null，直接交给 Gson 会把非空 Kotlin 属性置为 null
    private val gson = www.cetool.com.util.NullSafeTypeAdapterFactory.buildGson()

    /**
     * 解析角色卡。
     *
     * B29：原实现要求 spec 必须是 `chara_card_v2`/`chara_card_v3` 且必须有 `data`，
     * 于是：
     * - 酒馆 **v1 卡**（扁平结构，无 spec/data）→ 直接报「不支持的格式」
     * - **PNG 卡**（生态里最主流的分发格式，JSON 藏在 PNG 的 `tEXt`/`iTXt` 的 `chara` 块）
     *   → 全项目无解析代码，按 UTF-8 读二进制必然失败，只得到「格式无效」
     *
     * 现在：先尝试 PNG 容器提取，再按 v3/v2 / v1 扁平结构分流。
     */
    fun parse(jsonOrPng: String): Result<TavernCard> = runCatching {
        val root = JsonParser.parseString(jsonOrPng).asJsonObject
        parseRoot(root)
    }

    /**
     * 从任意来源的原始字节解析角色卡（自动识别 PNG 容器与 JSON）。
     * 供 SAF 选择任意 MIME 后按二进制读取的调用方使用。
     */
    fun parseBytes(bytes: ByteArray): Result<TavernCard> {
        if (bytes.isEmpty()) return Result.failure(IllegalArgumentException("文件为空"))
        // PNG 魔数：89 50 4E 47 0D 0A 1A 0A
        if (bytes.size > 8 &&
            bytes[0] == 0x89.toByte() && bytes[1] == 0x50.toByte() &&
            bytes[2] == 0x4E.toByte() && bytes[3] == 0x47.toByte()
        ) {
            val embedded = extractJsonFromPng(bytes)
                ?: return Result.failure(IllegalArgumentException("这是 PNG 图片，但未找到角色卡数据（非 SillyTavern PNG 卡）"))
            return runCatching { parseRoot(JsonParser.parseString(embedded).asJsonObject) }
        }
        return runCatching { parseRoot(JsonParser.parseString(String(bytes, Charsets.UTF_8)).asJsonObject) }
    }

    private fun parseRoot(root: com.google.gson.JsonObject): TavernCard {
        val spec = root.get("spec")?.asString ?: ""

        return when {
            spec == "chara_card_v3" || spec == "chara_card_v2" -> {
                val dataObj = root.getAsJsonObject("data")
                    ?: throw IllegalArgumentException("缺少 data 字段")
                val card = gson.fromJson(dataObj, TavernCardData::class.java)
                TavernCard(
                    spec = spec,
                    spec_version = root.get("spec_version")?.asString ?: "3.0",
                    data = card,
                    avatarBase64 = topLevelAvatar(root)
                )
            }
            // B29①：酒馆 v1 卡是扁平结构（字段直接放在根节点，没有 spec / data）
            root.has("name") && (root.has("description") || root.has("first_mes") || root.has("personality")) -> {
                val card = gson.fromJson(root, TavernCardData::class.java)
                TavernCard(
                    spec = "chara_card_v1",
                    spec_version = "1.0",
                    data = card,
                    avatarBase64 = topLevelAvatar(root)
                )
            }
            else -> throw IllegalArgumentException("不支持的格式：$spec")
        }
    }

    private fun topLevelAvatar(root: com.google.gson.JsonObject): String? =
        root.get("avatarBase64")?.takeIf { it.isJsonPrimitive }?.asString

    /**
     * 从 PNG 的 `tEXt` / `iTXt` 块里取出 `chara` 字段（base64 编码的角色卡 JSON）。
     *
     * PNG 结构：8 字节签名 + 若干 chunk，每个 chunk = [4字节长度][4字节类型][数据][4字节CRC]。
     * - `tEXt`：keyword\0text，keyword 通常是 "chara"，text 是 base64
     * - `iTXt`：keyword\0compressionFlag\0compressionMethod\0languageTag\0translatedKeyword\0text
     */
    private fun extractJsonFromPng(bytes: ByteArray): String? {
        try {
            var offset = 8 // 跳过 PNG 签名
            while (offset + 8 <= bytes.size) {
                val length = readInt32BE(bytes, offset)
                if (length < 0 || offset + 12 + length > bytes.size) break
                val type = String(bytes, offset + 4, 4, Charsets.US_ASCII)
                if (type == "tEXt" || type == "iTXt") {
                    val payload = String(bytes, offset + 8, length, Charsets.UTF_8)
                    val parts = payload.split('\u0000', limit = 2)
                    val keyword = parts.getOrNull(0) ?: ""
                    val rest = parts.getOrNull(1) ?: ""
                    if (keyword.equals("chara", ignoreCase = true)) {
                        val text = if (type == "iTXt") {
                            // iTXt 头部有两个单字节标志、语言标签、翻译关键字，取最后一段
                            val segments = rest.split('\u0000')
                            segments.lastOrNull().orEmpty()
                        } else rest
                        if (text.isBlank()) return null
                        return try {
                            String(android.util.Base64.decode(text, android.util.Base64.DEFAULT), Charsets.UTF_8)
                        } catch (_: Exception) {
                            // 有些工具直接写明文 JSON
                            text
                        }
                    }
                }
                offset += 12 + length
            }
        } catch (_: Exception) {
            return null
        }
        return null
    }

    private fun readInt32BE(bytes: ByteArray, offset: Int): Int {
        return ((bytes[offset].toInt() and 0xFF) shl 24) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 16) or
            ((bytes[offset + 2].toInt() and 0xFF) shl 8) or
            (bytes[offset + 3].toInt() and 0xFF)
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
