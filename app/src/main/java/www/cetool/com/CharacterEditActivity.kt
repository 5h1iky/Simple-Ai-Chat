package www.cetool.com

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.util.Base64
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AssistChip
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.google.gson.Gson
import io.noties.markwon.Markwon
import www.cetool.com.manager.CharacterCompiler
import www.cetool.com.manager.CharacterManager
import www.cetool.com.model.CharacterFields
import www.cetool.com.model.TavernCard
import www.cetool.com.model.TavernCardData
import www.cetool.com.ui.components.SectionCard
import www.cetool.com.ui.components.TagRow
import www.cetool.com.ui.theme.SAChatTheme
import java.io.ByteArrayOutputStream

/**
 * 角色卡编辑（Compose）：分组表单 + 标签 chip 输入 + 编辑/预览双模式。
 * 结构化字段存 extensions（D1 决策），旧字段同步兜底（description/personality）。
 */
class CharacterEditActivity : ComponentActivity() {

    private var editCharacterId: String? = null
    private var avatarState: androidx.compose.runtime.MutableState<String?>? = null

    private val imagePicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) handleAvatarPicked(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        editCharacterId = intent.getStringExtra("character_id")

        // 头像状态：在组合外持有，传给组合内读取（赋值即触发重组）
        val avatarStateLocal = mutableStateOf<String?>(null)
        avatarState = avatarStateLocal
        val state = CharacterEditState()

        editCharacterId?.let { id ->
            CharacterManager.loadCard(this, id)?.let { card ->
                val fields = card.data.getCharacterFields()
                state.name = card.data.name
                state.description = card.data.description
                state.firstMes = card.data.first_mes
                state.mesExample = card.data.mes_example
                state.systemPrompt = card.data.system_prompt
                state.postHistory = card.data.post_history_instructions
                state.legacyTags = card.data.tags
                fields.run {
                    state.age = age; state.gender = gender; state.race = race
                    state.birthplace = birthplace; state.occupation = occupation; state.socialClass = socialClass
                    state.identityTags = identityTags.toMutableList()
                    state.heightBuild = heightBuild; state.iconicFeatures = iconicFeatures
                    state.clothingStyle = clothingStyle; state.overallVibe = overallVibe
                    state.externalPersonality = externalPersonality; state.internalPersonality = internalPersonality
                    state.coreDesire = coreDesire; state.fearWeakness = fearWeakness
                    state.moralValues = moralValues; state.quirk = quirk
                    state.skills = skills; state.backgroundStory = backgroundStory; state.relationships = relationships
                    state.speakingStyle = speakingStyle; state.typicalReactions = typicalReactions
                    state.userRelationType = userRelationType; state.userInteractionModel = userInteractionModel
                    state.userRelationBottomLine = userRelationBottomLine; state.keyEvents = keyEvents
                }
                avatarStateLocal.value = card.avatarBase64
            }
        }

        setContent {
            SAChatTheme {
                CharacterEditScreen(
                    state = state,
                    avatar = avatarStateLocal.value,
                    onAvatarPicked = { imagePicker.launch(arrayOf("image/*")) },
                    onBack = { finish() },
                    onSave = { saveCharacter(state, avatarStateLocal.value) }
                )
            }
        }
    }

    private fun handleAvatarPicked(uri: Uri) {
        try {
            val inputStream = contentResolver.openInputStream(uri) ?: return
            val bitmap = BitmapFactory.decodeStream(inputStream)
            inputStream?.close()
            if (bitmap == null) return

            val maxSize = 512
            val scale = Math.min(maxSize.toFloat() / bitmap.width, maxSize.toFloat() / bitmap.height).coerceAtMost(1f)
            val scaled = if (scale < 1f) {
                Bitmap.createScaledBitmap(bitmap, (bitmap.width * scale).toInt(), (bitmap.height * scale).toInt(), true)
            } else bitmap

            val outputStream = ByteArrayOutputStream()
            scaled.compress(Bitmap.CompressFormat.JPEG, 80, outputStream)
            val base64 = Base64.encodeToString(outputStream.toByteArray(), Base64.NO_WRAP)
            outputStream.close()

            val state = avatarState
            state?.value = base64
        } catch (e: Exception) {
            Toast.makeText(this, "头像加载失败", Toast.LENGTH_SHORT).show()
        }
    }

    private fun saveCharacter(state: CharacterEditState, avatar: String?) {
        val name = state.name.trim()
        if (name.isBlank()) {
            Toast.makeText(this, "角色名不能为空", Toast.LENGTH_SHORT).show()
            return
        }

        val fields = CharacterFields(
            age = state.age.trim(), gender = state.gender.trim(), race = state.race.trim(),
            birthplace = state.birthplace.trim(), occupation = state.occupation.trim(),
            socialClass = state.socialClass.trim(),
            identityTags = state.identityTags.toMutableList(),
            heightBuild = state.heightBuild.trim(), iconicFeatures = state.iconicFeatures.trim(),
            clothingStyle = state.clothingStyle.trim(), overallVibe = state.overallVibe.trim(),
            externalPersonality = state.externalPersonality.trim(),
            internalPersonality = state.internalPersonality.trim(),
            coreDesire = state.coreDesire.trim(), fearWeakness = state.fearWeakness.trim(),
            moralValues = state.moralValues.trim(), quirk = state.quirk.trim(),
            skills = state.skills.trim(), backgroundStory = state.backgroundStory.trim(),
            relationships = state.relationships.trim(), speakingStyle = state.speakingStyle.trim(),
            typicalReactions = state.typicalReactions.trim(),
            userRelationType = state.userRelationType.trim(),
            userInteractionModel = state.userInteractionModel.trim(),
            userRelationBottomLine = state.userRelationBottomLine.trim(),
            keyEvents = state.keyEvents.trim()
        )

        val description = state.description.trim().ifBlank {
            fields.backgroundStory.ifBlank { fields.overallVibe }
        }
        val personality = listOf(fields.externalPersonality, fields.internalPersonality)
            .filter { it.isNotBlank() }.joinToString("；")

        val data = TavernCardData(
            name = name,
            description = description,
            personality = personality,
            scenario = "",
            first_mes = state.firstMes.trim(),
            mes_example = state.mesExample.trim(),
            system_prompt = state.systemPrompt.trim(),
            post_history_instructions = state.postHistory.trim(),
            tags = state.legacyTags,
            extensions = emptyMap()
        ).withCharacterFields(fields)

        val card = TavernCard(data = data, avatarBase64 = avatar)
        val json = Gson().toJson(card)

        val id = editCharacterId?.let {
            if (CharacterManager.overwrite(this, it, json)) it else null
        } ?: CharacterManager.save(this, json)

        if (id != null) {
            Toast.makeText(this, "角色「$name」已保存", Toast.LENGTH_SHORT).show()
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

    companion object {
        const val EXTRA_CHARACTER_ID = "character_id"
        const val EXTRA_IS_EDIT = "is_edit"
    }
}

/** 编辑表单状态（全部为 Compose state，便于双向绑定） */
class CharacterEditState {
    var name by mutableStateOf("")
    var description by mutableStateOf("")
    var firstMes by mutableStateOf("")
    var mesExample by mutableStateOf("")
    var systemPrompt by mutableStateOf("")
    var postHistory by mutableStateOf("")
    var legacyTags: List<String> = emptyList()

    var age by mutableStateOf("")
    var gender by mutableStateOf("")
    var race by mutableStateOf("")
    var birthplace by mutableStateOf("")
    var occupation by mutableStateOf("")
    var socialClass by mutableStateOf("")
    var identityTags by mutableStateOf<MutableList<String>>(mutableListOf())
    var heightBuild by mutableStateOf("")
    var iconicFeatures by mutableStateOf("")
    var clothingStyle by mutableStateOf("")
    var overallVibe by mutableStateOf("")
    var externalPersonality by mutableStateOf("")
    var internalPersonality by mutableStateOf("")
    var coreDesire by mutableStateOf("")
    var fearWeakness by mutableStateOf("")
    var moralValues by mutableStateOf("")
    var quirk by mutableStateOf("")
    var skills by mutableStateOf("")
    var backgroundStory by mutableStateOf("")
    var relationships by mutableStateOf("")
    var speakingStyle by mutableStateOf("")
    var typicalReactions by mutableStateOf("")
    var userRelationType by mutableStateOf("")
    var userInteractionModel by mutableStateOf("")
    var userRelationBottomLine by mutableStateOf("")
    var keyEvents by mutableStateOf("")
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CharacterEditScreen(
    state: CharacterEditState,
    avatar: String?,
    onAvatarPicked: () -> Unit,
    onBack: () -> Unit,
    onSave: () -> Unit
) {
    var previewMode by remember { mutableStateOf(false) }
    var tagInput by remember { mutableStateOf("") }
    val context = LocalContext.current

    // 编译预览用
    val compiled = remember(state) {
        val fields = CharacterFields(
            age = state.age, gender = state.gender, race = state.race,
            birthplace = state.birthplace, occupation = state.occupation, socialClass = state.socialClass,
            identityTags = state.identityTags.toMutableList(),
            heightBuild = state.heightBuild, iconicFeatures = state.iconicFeatures,
            clothingStyle = state.clothingStyle, overallVibe = state.overallVibe,
            externalPersonality = state.externalPersonality, internalPersonality = state.internalPersonality,
            coreDesire = state.coreDesire, fearWeakness = state.fearWeakness,
            moralValues = state.moralValues, quirk = state.quirk,
            skills = state.skills, backgroundStory = state.backgroundStory, relationships = state.relationships,
            speakingStyle = state.speakingStyle, typicalReactions = state.typicalReactions,
            userRelationType = state.userRelationType, userInteractionModel = state.userInteractionModel,
            userRelationBottomLine = state.userRelationBottomLine, keyEvents = state.keyEvents
        )
        CharacterCompiler.compileMarkdown(state.name.ifBlank { "未命名角色" }, fields)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (previewMode) "效果预览" else "编辑角色卡") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    TextButton(onClick = { previewMode = !previewMode }) {
                        Text(if (previewMode) "编辑" else "预览")
                    }
                    TextButton(onClick = onSave) { Text("保存") }
                }
            )
        }
    ) { padding ->
        if (previewMode) {
            // 预览：复用现有 Markwon 渲染（AndroidView 包装，零新依赖）
            val markwon = remember { Markwon.create(context) }
            AndroidView(
                factory = { ctx -> android.widget.TextView(ctx) },
                update = { tv -> markwon.setMarkdown(tv, compiled) },
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(16.dp)
            )
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // 头像
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CharacterAvatar(avatar, Modifier.size(64.dp))
                    Spacer(Modifier.width(12.dp))
                    TextButton(onClick = onAvatarPicked) {
                        Text(if (avatar != null) "更换头像" else "选择头像")
                    }
                }

                SectionCard("基础信息") {
                    FormField("姓名", state.name) { state.name = it }
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        FormField("年龄", state.age, Modifier.weight(1f)) { state.age = it }
                        FormField("性别", state.gender, Modifier.weight(1f)) { state.gender = it }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        FormField("种族", state.race, Modifier.weight(1f)) { state.race = it }
                        FormField("出生地", state.birthplace, Modifier.weight(1f)) { state.birthplace = it }
                    }
                    FormField("职业", state.occupation) { state.occupation = it }
                    FormField("社会阶层", state.socialClass) { state.socialClass = it }
                }

                SectionCard("身份标签") {
                    if (state.identityTags.isNotEmpty()) {
                        TagRow(tags = state.identityTags)
                        Spacer(Modifier.height(8.dp))
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(
                            value = tagInput,
                            onValueChange = { tagInput = it },
                            label = { Text("输入标签，回车添加") },
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                        IconButton(onClick = {
                            val t = tagInput.trim()
                            if (t.isNotEmpty() && t !in state.identityTags) {
                                state.identityTags = (state.identityTags + t).toMutableList()
                            }
                            tagInput = ""
                        }) {
                            Icon(Icons.Filled.Close, contentDescription = "添加")
                        }
                    }
                }

                SectionCard("外貌气质") {
                    FormField("身高体型", state.heightBuild) { state.heightBuild = it }
                    FormField("标志性特征", state.iconicFeatures) { state.iconicFeatures = it }
                    FormField("衣着风格", state.clothingStyle) { state.clothingStyle = it }
                    FormField("整体气质", state.overallVibe) { state.overallVibe = it }
                }

                SectionCard("性格特征") {
                    FormField("外在性格", state.externalPersonality) { state.externalPersonality = it }
                    FormField("内在性格", state.internalPersonality) { state.internalPersonality = it }
                    FormField("核心欲望", state.coreDesire) { state.coreDesire = it }
                    FormField("恐惧与弱点", state.fearWeakness) { state.fearWeakness = it }
                    FormField("道德观念", state.moralValues) { state.moralValues = it }
                    FormField("怪癖", state.quirk) { state.quirk = it }
                }

                SectionCard("技能与经历") {
                    FormField("技能专长", state.skills) { state.skills = it }
                    FormField("背景故事", state.backgroundStory) { state.backgroundStory = it }
                    FormField("人际关系", state.relationships) { state.relationships = it }
                    FormField("说话方式", state.speakingStyle) { state.speakingStyle = it }
                    FormField("典型反应", state.typicalReactions) { state.typicalReactions = it }
                }

                SectionCard("角色记忆（记忆封存写回目标）") {
                    FormField("与用户关系类型", state.userRelationType) { state.userRelationType = it }
                    FormField("与用户相处模式", state.userInteractionModel) { state.userInteractionModel = it }
                    FormField("与用户关系底线", state.userRelationBottomLine) { state.userRelationBottomLine = it }
                    FormField("关键事件", state.keyEvents) { state.keyEvents = it }
                }

                SectionCard("对话设置（Tavern 兼容字段）") {
                    FormField("开场白 first_mes", state.firstMes) { state.firstMes = it }
                    FormField("示例对话 mes_example", state.mesExample) { state.mesExample = it }
                    FormField("系统提示词 system_prompt", state.systemPrompt) { state.systemPrompt = it }
                    FormField("历史后指令 post_history", state.postHistory) { state.postHistory = it }
                    FormField("描述 description", state.description) { state.description = it }
                }

                Spacer(Modifier.height(24.dp))
            }
        }
    }
}

@Composable
private fun FormField(label: String, value: String, modifier: Modifier = Modifier, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        modifier = modifier.fillMaxWidth()
    )
}
