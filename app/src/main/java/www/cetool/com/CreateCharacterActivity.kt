package www.cetool.com

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.util.Base64
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import com.google.gson.Gson
import www.cetool.com.manager.CharacterManager
import www.cetool.com.model.TavernCard
import www.cetool.com.model.TavernCardData
import java.io.ByteArrayOutputStream

class CreateCharacterActivity : AppCompatActivity() {

    private lateinit var etName: TextInputEditText
    private lateinit var etDescription: TextInputEditText
    private lateinit var etPersonality: TextInputEditText
    private lateinit var etScenario: TextInputEditText
    private lateinit var etFirstMes: TextInputEditText
    private lateinit var etMesExample: TextInputEditText
    private lateinit var etSystemPrompt: TextInputEditText
    private lateinit var etPostHistory: TextInputEditText
    private lateinit var etTags: TextInputEditText
    private lateinit var btnSave: MaterialButton
    private lateinit var btnPickAvatar: MaterialButton
    private lateinit var tvTitle: TextView

    private var editCharacterId: String? = null
    private var avatarBase64: String? = null

    private val imagePickerLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) handleAvatarPicked(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_create_character)

        etName = findViewById(R.id.etName)
        etDescription = findViewById(R.id.etDescription)
        etPersonality = findViewById(R.id.etPersonality)
        etScenario = findViewById(R.id.etScenario)
        etFirstMes = findViewById(R.id.etFirstMes)
        etMesExample = findViewById(R.id.etMesExample)
        etSystemPrompt = findViewById(R.id.etSystemPrompt)
        etPostHistory = findViewById(R.id.etPostHistory)
        etTags = findViewById(R.id.etTags)
        btnSave = findViewById(R.id.btnSave)
        btnPickAvatar = findViewById(R.id.btnPickAvatar)
        tvTitle = findViewById(R.id.tvTitle)

        editCharacterId = intent.getStringExtra("character_id")
        if (editCharacterId != null) {
            loadExistingCharacter(editCharacterId!!)
        }

        btnPickAvatar.setOnClickListener {
            imagePickerLauncher.launch(arrayOf("image/*"))
        }

        btnSave.setOnClickListener { saveCharacter() }
    }

    private fun loadExistingCharacter(characterId: String) {
        val card = CharacterManager.loadCard(this, characterId) ?: return
        tvTitle.text = "编辑角色卡"
        btnSave.text = "保存修改"
        etName.setText(card.data.name)
        etDescription.setText(card.data.description)
        etPersonality.setText(card.data.personality)
        etScenario.setText(card.data.scenario)
        etFirstMes.setText(card.data.first_mes)
        etMesExample.setText(card.data.mes_example)
        etSystemPrompt.setText(card.data.system_prompt)
        etPostHistory.setText(card.data.post_history_instructions)
        etTags.setText(card.data.tags.joinToString(", "))
        avatarBase64 = card.avatarBase64
        if (avatarBase64 != null) btnPickAvatar.text = "已选择头像"
    }

    private fun handleAvatarPicked(uri: Uri) {
        try {
            val inputStream = contentResolver.openInputStream(uri)
            val bitmap = BitmapFactory.decodeStream(inputStream)
            inputStream?.close()
            if (bitmap == null) return

            val maxSize = 512
            val scale = Math.min(maxSize.toFloat() / bitmap.width, maxSize.toFloat() / bitmap.height).coerceAtMost(1f)
            val scaled = if (scale < 1f) Bitmap.createScaledBitmap(bitmap, (bitmap.width * scale).toInt(), (bitmap.height * scale).toInt(), true) else bitmap

            val outputStream = ByteArrayOutputStream()
            scaled.compress(Bitmap.CompressFormat.JPEG, 80, outputStream)
            avatarBase64 = Base64.encodeToString(outputStream.toByteArray(), Base64.NO_WRAP)
            outputStream.close()

            btnPickAvatar.text = "已选择头像"
            Toast.makeText(this, "头像已选择", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, "头像加载失败", Toast.LENGTH_SHORT).show()
        }
    }

    private fun saveCharacter() {
        val name = etName.text?.toString()?.trim() ?: ""
        if (name.isBlank()) {
            etName.error = "角色名不能为空"
            return
        }

        val data = TavernCardData(
            name = name,
            description = etDescription.text?.toString()?.trim() ?: "",
            personality = etPersonality.text?.toString()?.trim() ?: "",
            scenario = etScenario.text?.toString()?.trim() ?: "",
            first_mes = etFirstMes.text?.toString()?.trim() ?: "",
            mes_example = etMesExample.text?.toString()?.trim() ?: "",
            system_prompt = etSystemPrompt.text?.toString()?.trim() ?: "",
            post_history_instructions = etPostHistory.text?.toString()?.trim() ?: "",
            tags = etTags.text?.toString()?.trim()?.split(Regex("[，,、\\s]+"))?.filter { it.isNotBlank() } ?: emptyList()
        )

        val card = TavernCard(data = data, avatarBase64 = avatarBase64)
        val json = Gson().toJson(card)

        val id = if (editCharacterId != null) {
            if (CharacterManager.overwrite(this, editCharacterId!!, json)) editCharacterId else null
        } else {
            CharacterManager.save(this, json)
        }

        if (id != null) {
            val msg = if (editCharacterId != null) "角色「$name」已更新" else "角色「$name」创建成功"
            Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
            val intent = Intent().apply {
                putExtra("character_id", id)
                putExtra("is_edit", editCharacterId != null)
            }
            setResult(RESULT_OK, intent)
            finish()
        } else {
            Toast.makeText(this, "保存失败", Toast.LENGTH_SHORT).show()
        }
    }
}
