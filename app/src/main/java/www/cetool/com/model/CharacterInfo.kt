package www.cetool.com.model

data class CharacterInfo(
    val id: String,
    val name: String,
    val avatarBase64: String? = null,
    val description: String = "",
    val importedAt: Long = System.currentTimeMillis()
)
