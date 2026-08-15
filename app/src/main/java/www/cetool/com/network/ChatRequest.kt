package www.cetool.com.network

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject

data class ChatRequest(
    val model: String,
    val messages: List<MessageItem>,
    val temperature: Double? = 0.7,
    val stream: Boolean = true,
    val reasoning_effort: String? = null
)

data class MessageItem(
    val role: String,
    val content: JsonElement,
    val name: String? = null
) {
    companion object {
        fun text(role: String, text: String): MessageItem {
            return MessageItem(role, com.google.gson.JsonPrimitive(text))
        }

        fun multimodal(
            role: String,
            text: String,
            imageBase64: String,
            imageMimeType: String
        ): MessageItem {
            val contentArray = JsonArray()

            val textPart = JsonObject()
            textPart.addProperty("type", "text")
            textPart.addProperty("text", text)
            contentArray.add(textPart)

            val imagePart = JsonObject()
            imagePart.addProperty("type", "image_url")
            val imageUrl = JsonObject()
            imageUrl.addProperty("url", "data:$imageMimeType;base64,$imageBase64")
            imagePart.add("image_url", imageUrl)
            contentArray.add(imagePart)

            return MessageItem(role, contentArray)
        }
    }
}
