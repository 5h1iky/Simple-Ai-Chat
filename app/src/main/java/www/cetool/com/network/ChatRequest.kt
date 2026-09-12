package www.cetool.com.network

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject

data class ChatRequest(
    val model: String,
    val messages: List<MessageItem>,
    val temperature: Double? = 0.7,
    val stream: Boolean = true,
    val reasoning_effort: String? = null,
    val max_tokens: Int? = null
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

        /**
         * 文本附件：文件作为**独立内容块**发送，不再拼进用户消息正文。
         * 采用 OpenAI chat/completions 的 file 内容块（与 image_url 同属多模态机制）。
         *
         * @param text     用户实际输入的文字（为空时不发送 text 块）
         * @param fileName 文件名（作为元数据，不混入正文）
         * @param fileData 文件全文
         */
        fun withFile(
            role: String,
            text: String,
            fileName: String,
            fileData: String
        ): MessageItem {
            val contentArray = JsonArray()

            if (text.isNotBlank()) {
                val textPart = JsonObject()
                textPart.addProperty("type", "text")
                textPart.addProperty("text", text)
                contentArray.add(textPart)
            }

            val fileObj = JsonObject()
            fileObj.addProperty("filename", fileName)
            fileObj.addProperty("file_data", fileData)

            val filePart = JsonObject()
            filePart.addProperty("type", "file")
            filePart.add("file", fileObj)
            contentArray.add(filePart)

            return MessageItem(role, contentArray)
        }
    }
}
