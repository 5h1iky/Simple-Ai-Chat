package www.cetool.com

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.util.Base64
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import www.cetool.com.manager.WorldInfoEngine
import www.cetool.com.model.ApiConfig
import java.io.ByteArrayOutputStream

class SettingsActivity : AppCompatActivity() {

    private val gson = Gson()
    private val apiEntries = mutableListOf<SettingsKeys.ApiEntry>()
    private var activeApiIndex = 0

    // Free Gateway Integration: 预设服务商相关状态
    private lateinit var spinnerProvider: Spinner
    private lateinit var tvProviderHint: TextView
    private lateinit var btnSelectModel: MaterialButton
    private var providerMode = SettingsKeys.PROVIDER_CUSTOM
    private var kiloModelOption = SettingsKeys.KILO_MODEL
    private var zenModelOption = SettingsKeys.ZEN_MODEL_DEFAULT
    private var manualEntriesSnapshot: List<SettingsKeys.ApiEntry>? = null
    private var manualActiveIndexSnapshot = 0
    private var autoCreatedByPreset = false

    private lateinit var containerApiList: LinearLayout
    private lateinit var btnAddApi: MaterialButton
    private lateinit var etFontSize: TextInputEditText
    private lateinit var tvFontPreview: TextView
    private lateinit var etSystemPrompt: TextInputEditText
    private lateinit var etMaxHistory: TextInputEditText
    private lateinit var etWorldBudget: TextInputEditText
    private lateinit var etWebSearchUrl: TextInputEditText
    private lateinit var rgBgMode: RadioGroup
    private lateinit var rbBgColor: RadioButton
    private lateinit var rbBgImage: RadioButton
    private lateinit var layoutBgImage: View
    private lateinit var btnPickBgImage: MaterialButton
    private lateinit var tvBgImageName: TextView
    private lateinit var rgBgScale: RadioGroup
    private lateinit var rbBgFit: RadioButton
    private lateinit var rbBgFill: RadioButton
    private lateinit var btnSave: MaterialButton
    private lateinit var etUserName: TextInputEditText
    private lateinit var etAiName: TextInputEditText
    private lateinit var btnPickUserAvatar: MaterialButton
    private lateinit var btnPickAiAvatar: MaterialButton
    private var userAvatarBase64: String? = null
    private var aiAvatarBase64: String? = null

    private var bgImageBase64: String? = null
    private var bgImageName: String? = null

    private val imagePickerLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) handleBgImagePicked(uri)
    }

    private val userAvatarPickerLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) handleUserAvatarPicked(uri, isAi = false)
    }

    private val aiAvatarPickerLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) handleUserAvatarPicked(uri, isAi = true)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        containerApiList = findViewById(R.id.containerApiList)
        btnAddApi = findViewById(R.id.btnAddApi)
        // Free Gateway Integration
        spinnerProvider = findViewById(R.id.spinnerProvider)
        tvProviderHint = findViewById(R.id.tvProviderHint)
        btnSelectModel = findViewById(R.id.btnSelectModel)
        etFontSize = findViewById(R.id.etFontSize)
        tvFontPreview = findViewById(R.id.tvFontPreview)
        etSystemPrompt = findViewById(R.id.etSystemPrompt)
        etMaxHistory = findViewById(R.id.etMaxHistory)
        etWorldBudget = findViewById(R.id.etWorldBudget)
        etWebSearchUrl = findViewById(R.id.etWebSearchUrl)
        rgBgMode = findViewById(R.id.rgBgMode)
        rbBgColor = findViewById(R.id.rbBgColor)
        rbBgImage = findViewById(R.id.rbBgImage)
        layoutBgImage = findViewById(R.id.layoutBgImage)
        btnPickBgImage = findViewById(R.id.btnPickBgImage)
        tvBgImageName = findViewById(R.id.tvBgImageName)
        rgBgScale = findViewById(R.id.rgBgScale)
        rbBgFit = findViewById(R.id.rbBgFit)
        rbBgFill = findViewById(R.id.rbBgFill)
        btnSave = findViewById(R.id.btnSave)
        etUserName = findViewById(R.id.etUserName)
        etAiName = findViewById(R.id.etAiName)
        btnPickUserAvatar = findViewById(R.id.btnPickUserAvatar)
        btnPickAiAvatar = findViewById(R.id.btnPickAiAvatar)

        setupBgModeToggle()
        ConversationManager.init(this)
        loadSettings()
        setupProviderSpinner()
        renderApiList()

        etFontSize.setOnKeyListener { _, _, _ -> updateFontPreview(); false }
        btnAddApi.setOnClickListener { showApiEntryDialog(null) }
        btnSelectModel.setOnClickListener { showModelSelectDialog() }
        btnPickBgImage.setOnClickListener { imagePickerLauncher.launch(arrayOf("image/png")) }
        btnPickUserAvatar.setOnClickListener { userAvatarPickerLauncher.launch(arrayOf("image/*")) }
        btnPickAiAvatar.setOnClickListener { aiAvatarPickerLauncher.launch(arrayOf("image/*")) }
        btnSave.setOnClickListener { saveSettings() }
    }

    private fun loadSettings() {
        val sp = getSharedPreferences(SettingsKeys.PREFS_NAME, Context.MODE_PRIVATE)

        val apiJson = sp.getString(SettingsKeys.KEY_API_CONFIGS, "[]") ?: "[]"
        val listType = object : TypeToken<List<SettingsKeys.ApiEntry>>() {}.type
        val loaded: List<SettingsKeys.ApiEntry> = gson.fromJson(apiJson, listType)
        apiEntries.clear()
        apiEntries.addAll(loaded)
        activeApiIndex = sp.getInt(SettingsKeys.KEY_ACTIVE_API, 0).coerceIn(0, (apiEntries.size - 1).coerceAtLeast(0))

        val fontSize = sp.getInt(SettingsKeys.KEY_FONT_SIZE, 15)
        etFontSize.setText(fontSize.toString())
        updateFontPreview()

        bgImageBase64 = sp.getString(SettingsKeys.KEY_BG_IMAGE, null)
        bgImageName = sp.getString("bg_image_name", null)

        val bgMode = sp.getString(SettingsKeys.KEY_BG_MODE, "color") ?: "color"
        if (bgMode == "image") rbBgImage.isChecked = true else rbBgColor.isChecked = true

        val bgScale = sp.getString(SettingsKeys.KEY_BG_SCALE, "fit") ?: "fit"
        if (bgScale == "fill") rbBgFill.isChecked = true else rbBgFit.isChecked = true

        etSystemPrompt.setText(ConversationManager.getDefaultSystemPrompt())
        etMaxHistory.setText(sp.getInt(SettingsKeys.KEY_MAX_HISTORY, www.cetool.com.model.Conversation.MAX_HISTORY).toString())
        etWorldBudget.setText(sp.getInt(SettingsKeys.KEY_WORLDINFO_BUDGET, WorldInfoEngine.DEFAULT_BUDGET).toString())
        etWebSearchUrl.setText(sp.getString(SettingsKeys.KEY_WEB_SEARCH_URL, ""))

        if (bgImageName != null) tvBgImageName.text = bgImageName

        etUserName.setText(sp.getString(SettingsKeys.KEY_USER_NAME, "用户"))
        etAiName.setText(sp.getString(SettingsKeys.KEY_AI_NAME, "AI"))
        userAvatarBase64 = sp.getString(SettingsKeys.KEY_USER_AVATAR, null)
        aiAvatarBase64 = sp.getString(SettingsKeys.KEY_AI_AVATAR, null)
    }

    // ─── Free Gateway Integration: 预设服务商 ──────────────────────

    private val providerModes = arrayOf(
        SettingsKeys.PROVIDER_CUSTOM,
        SettingsKeys.PROVIDER_OPEN_CODE_ZEN,
        SettingsKeys.PROVIDER_OPEN_KILO
    )

    private val providerModeLabels = arrayOf(
        R.string.provider_custom,
        R.string.provider_open_code_zen,
        R.string.provider_open_kilo
    )

    private fun setupProviderSpinner() {
        val labels = providerModeLabels.map { getString(it) }.toTypedArray()
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, labels)
        adapter.setDropDownViewResource(R.layout.spinner_dropdown_item)
        spinnerProvider.adapter = adapter

        loadProviderMode()

        spinnerProvider.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val mode = providerModes[position]
                if (mode != providerMode) {
                    providerMode = mode
                    applyProviderMode(mode)
                }
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }

    /** 启动时从 SharedPreferences 恢复预设模式与手动输入快照 */
    private fun loadProviderMode() {
        val sp = getSharedPreferences(SettingsKeys.PREFS_NAME, Context.MODE_PRIVATE)
        providerMode = sp.getString(SettingsKeys.KEY_PROVIDER_MODE, SettingsKeys.PROVIDER_CUSTOM)
            ?: SettingsKeys.PROVIDER_CUSTOM

        // Free Gateway Integration: 已不存在的预设模式重置为自定义
        if (providerMode !in providerModes) {
            providerMode = SettingsKeys.PROVIDER_CUSTOM
            sp.edit()
                .remove(SettingsKeys.KEY_PROVIDER_MANUAL_SNAPSHOT)
                .remove(SettingsKeys.KEY_PROVIDER_MANUAL_ACTIVE_INDEX)
                .remove(SettingsKeys.KEY_PROVIDER_AUTO_CREATED)
                .apply()
        }

        spinnerProvider.setSelection(providerModes.indexOf(providerMode).coerceAtLeast(0))

        if (providerMode == SettingsKeys.PROVIDER_CUSTOM) {
            tvProviderHint.visibility = View.GONE
            return
        }

        // Free Gateway: OpenKilo / OpenCode Zen 免费模式下恢复模型选择与按钮显隐
        if (providerMode == SettingsKeys.PROVIDER_OPEN_KILO) {
            kiloModelOption = sp.getString(SettingsKeys.KEY_KILO_MODEL, SettingsKeys.KILO_MODEL)
                ?: SettingsKeys.KILO_MODEL
            // 兼容旧版保存的 "auto" 值
            if (kiloModelOption == SettingsKeys.KILO_MODEL_AUTO) kiloModelOption = SettingsKeys.KILO_MODEL
        } else if (providerMode == SettingsKeys.PROVIDER_OPEN_CODE_ZEN) {
            zenModelOption = sp.getString(SettingsKeys.KEY_ZEN_MODEL, SettingsKeys.ZEN_MODEL_DEFAULT)
                ?: SettingsKeys.ZEN_MODEL_DEFAULT
        }
        tvProviderHint.visibility = View.VISIBLE
        containerApiList.visibility = View.GONE
        btnAddApi.visibility = View.GONE
        btnSelectModel.visibility = View.VISIBLE
        updateModelButtonText()
        val snapshotJson = sp.getString(SettingsKeys.KEY_PROVIDER_MANUAL_SNAPSHOT, null)
        if (!snapshotJson.isNullOrBlank()) {
            val type = object : TypeToken<List<SettingsKeys.ApiEntry>>() {}.type
            manualEntriesSnapshot = gson.fromJson(snapshotJson, type)
            manualActiveIndexSnapshot = sp.getInt(SettingsKeys.KEY_PROVIDER_MANUAL_ACTIVE_INDEX, 0)
            autoCreatedByPreset = sp.getBoolean(SettingsKeys.KEY_PROVIDER_AUTO_CREATED, false)
        }
    }

    /** 切换预设模式：自动填充 API 地址 / 模型 / Key，或恢复手动输入 */
    private fun applyProviderMode(mode: String) {
        when (mode) {
            SettingsKeys.PROVIDER_OPEN_KILO -> {
                tvProviderHint.visibility = View.VISIBLE
                // Free Gateway: OpenKilo 模式下隐藏 API 列表与添加按钮，只留模型选择
                containerApiList.visibility = View.GONE
                btnAddApi.visibility = View.GONE
                btnSelectModel.visibility = View.VISIBLE
                updateModelButtonText()
                fillPresetIntoActiveEntry(
                    label = getString(R.string.provider_open_kilo),
                    url = SettingsKeys.KILO_BASE_URL,
                    model = kiloModelOption,
                    key = ""
                )
            }
            SettingsKeys.PROVIDER_OPEN_CODE_ZEN -> {
                tvProviderHint.visibility = View.VISIBLE
                // Free Gateway: OpenCode Zen 模式下隐藏 API 列表与添加按钮，只留模型选择
                containerApiList.visibility = View.GONE
                btnAddApi.visibility = View.GONE
                btnSelectModel.visibility = View.VISIBLE
                updateModelButtonText()
                fillPresetIntoActiveEntry(
                    label = getString(R.string.provider_open_code_zen),
                    url = SettingsKeys.ZEN_BASE_URL,
                    model = zenModelOption,
                    key = SettingsKeys.ZEN_PUBLIC_KEY
                )
            }
            else -> {
                tvProviderHint.visibility = View.GONE
                // Free Gateway Integration: 恢复自定义模式下的 API 列表与添加按钮
                containerApiList.visibility = View.VISIBLE
                btnAddApi.visibility = View.VISIBLE
                btnSelectModel.visibility = View.GONE
                restoreManualState()
            }
        }
        persistProviderState()
        renderApiList()
    }

    // ─── Free Gateway: OpenKilo / OpenCode Zen 模型选择 ────────────

    /** OpenKilo 模型选项（values 直接存实际模型名，后续在此追加新选项） */
    private val kiloModelOptions = arrayOf(
        SettingsKeys.KILO_MODEL,
        SettingsKeys.KILO_MODEL_NEMOTRON_ULTRA,
        SettingsKeys.KILO_MODEL_STEPFUN,
        SettingsKeys.KILO_MODEL_LING,
        SettingsKeys.KILO_MODEL_NEMOTRON_SUPER,
        SettingsKeys.KILO_MODEL_LAGUNA
    )

    private val kiloModelOptionLabels = arrayOf(
        R.string.model_auto,
        R.string.model_nemotron_ultra,
        R.string.model_stepfun,
        R.string.model_ling,
        R.string.model_nemotron_super,
        R.string.model_laguna
    )

    /** OpenCode Zen 模型选项（模型 ID 为纯名称，调用时不加 opencode/ 前缀） */
    private val zenModelOptions = arrayOf(
        SettingsKeys.ZEN_MODEL_MIMO,
        SettingsKeys.ZEN_MODEL_LING,
        SettingsKeys.ZEN_MODEL_DEEPSEEK,
        SettingsKeys.ZEN_MODEL_NEMOTRON,
        SettingsKeys.ZEN_MODEL_NORTH,
        SettingsKeys.ZEN_MODEL_LAGUNA
    )

    private val zenModelOptionLabels = arrayOf(
        R.string.model_zen_mimo,
        R.string.model_zen_ling,
        R.string.model_zen_deepseek,
        R.string.model_zen_nemotron,
        R.string.model_zen_north,
        R.string.model_zen_laguna
    )

    /** 当前模式对应的模型选项列表 */
    private fun currentModelOptions(): Pair<Array<String>, Array<Int>> {
        return if (providerMode == SettingsKeys.PROVIDER_OPEN_CODE_ZEN) {
            zenModelOptions to zenModelOptionLabels
        } else {
            kiloModelOptions to kiloModelOptionLabels
        }
    }

    /** 当前模式选中的模型值 */
    private fun currentModelValue(): String {
        return if (providerMode == SettingsKeys.PROVIDER_OPEN_CODE_ZEN) zenModelOption else kiloModelOption
    }

    private fun updateModelButtonText() {
        val (options, labels) = currentModelOptions()
        val idx = options.indexOf(currentModelValue())
        val label = if (idx >= 0) getString(labels[idx]) else currentModelValue()
        btnSelectModel.text = "${getString(R.string.select_model)}：$label"
    }

    private fun showModelSelectDialog() {
        val sp = getSharedPreferences(SettingsKeys.PREFS_NAME, Context.MODE_PRIVATE)
        // Free Gateway Integration: 每次启动首次点击时弹提示，之后本次启动内不再弹
        if (!sp.getBoolean(SettingsKeys.KEY_KILO_MODEL_TIP_SHOWN, false)) {
            sp.edit().putBoolean(SettingsKeys.KEY_KILO_MODEL_TIP_SHOWN, true).apply()
            MaterialAlertDialogBuilder(this)
                .setTitle(R.string.kilo_model_tip_title)
                .setMessage(R.string.kilo_model_tip_message)
                .setPositiveButton(R.string.got_it) { _, _ ->
                    showModelOptionsDialog()
                }
                .setCancelable(false)
                .show()
            return
        }
        showModelOptionsDialog()
    }

    private fun showModelOptionsDialog() {
        val (options, labels) = currentModelOptions()
        val labelStrings = labels.map { getString(it) }.toTypedArray()
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.select_model)
            .setSingleChoiceItems(labelStrings, options.indexOf(currentModelValue()).coerceAtLeast(0)) { dialog, which ->
                val value = options[which]
                // Free Gateway: 按模式保存各自的模型选择
                if (providerMode == SettingsKeys.PROVIDER_OPEN_CODE_ZEN) {
                    zenModelOption = value
                } else {
                    kiloModelOption = value
                }
                updateModelButtonText()
                persistProviderState()
                dialog.dismiss()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /** 将预设的 Base URL / 模型 / Key 填入当前激活的 API 条目 */
    private fun fillPresetIntoActiveEntry(label: String, url: String, model: String, key: String) {
        if (manualEntriesSnapshot == null) {
            manualEntriesSnapshot = apiEntries.toList()
            manualActiveIndexSnapshot = activeApiIndex
        }

        if (apiEntries.isEmpty()) {
            apiEntries.add(SettingsKeys.ApiEntry(label = label, url = url, key = key, model = model))
            activeApiIndex = 0
            autoCreatedByPreset = true
        } else {
            val entry = apiEntries[activeApiIndex]
            apiEntries[activeApiIndex] = entry.copy(url = url, key = key, model = model)
        }
    }

    /** 切回自定义：恢复手动输入的内容；若此前无手动输入则恢复为空白 */
    private fun restoreManualState() {
        val snapshot = manualEntriesSnapshot
        when {
            snapshot != null && snapshot.isNotEmpty() -> {
                apiEntries.clear()
                apiEntries.addAll(snapshot)
                activeApiIndex = manualActiveIndexSnapshot.coerceIn(0, (apiEntries.size - 1).coerceAtLeast(0))
            }
            autoCreatedByPreset && apiEntries.isNotEmpty() -> {
                apiEntries.removeAt(activeApiIndex)
                activeApiIndex = (apiEntries.size - 1).coerceAtLeast(0)
            }
            snapshot != null -> {
                apiEntries.clear()
                activeApiIndex = 0
            }
        }
        autoCreatedByPreset = false
        manualEntriesSnapshot = null
    }

    /** 持久化预设模式、API 列表及手动输入快照 */
    private fun persistProviderState() {
        val sp = getSharedPreferences(SettingsKeys.PREFS_NAME, Context.MODE_PRIVATE)
        sp.edit {
            putString(SettingsKeys.KEY_PROVIDER_MODE, providerMode)
            putString(SettingsKeys.KEY_API_CONFIGS, gson.toJson(apiEntries))
            putInt(SettingsKeys.KEY_ACTIVE_API, activeApiIndex)
            if (providerMode != SettingsKeys.PROVIDER_CUSTOM) {
                putString(SettingsKeys.KEY_KILO_MODEL, kiloModelOption)
                putString(SettingsKeys.KEY_ZEN_MODEL, zenModelOption)
                putString(
                    SettingsKeys.KEY_PROVIDER_MANUAL_SNAPSHOT,
                    gson.toJson(manualEntriesSnapshot ?: emptyList<SettingsKeys.ApiEntry>())
                )
                putInt(SettingsKeys.KEY_PROVIDER_MANUAL_ACTIVE_INDEX, manualActiveIndexSnapshot)
                putBoolean(SettingsKeys.KEY_PROVIDER_AUTO_CREATED, autoCreatedByPreset)
            } else {
                remove(SettingsKeys.KEY_PROVIDER_MANUAL_SNAPSHOT)
                remove(SettingsKeys.KEY_PROVIDER_MANUAL_ACTIVE_INDEX)
                remove(SettingsKeys.KEY_PROVIDER_AUTO_CREATED)
            }
        }
    }

    private fun updateFontPreview() {
        val size = etFontSize.text?.toString()?.toIntOrNull() ?: 15
        tvFontPreview.textSize = size.toFloat()
    }

    private fun setupBgModeToggle() {
        rgBgMode.setOnCheckedChangeListener { _, id ->
            layoutBgImage.visibility = if (id == R.id.rbBgImage) View.VISIBLE else View.GONE
        }
    }

    private fun handleBgImagePicked(uri: Uri) {
        try {
            val inputStream = contentResolver.openInputStream(uri) ?: return
            val bitmap = BitmapFactory.decodeStream(inputStream)
            inputStream?.close()
            if (bitmap == null) { Toast.makeText(this, "图片无效", Toast.LENGTH_SHORT).show(); return }

            val maxSize = 1024
            val scale = Math.min(maxSize.toFloat() / bitmap.width, maxSize.toFloat() / bitmap.height).coerceAtMost(1f)
            val scaled = if (scale < 1f) Bitmap.createScaledBitmap(bitmap, (bitmap.width * scale).toInt(), (bitmap.height * scale).toInt(), true) else bitmap

            val outputStream = ByteArrayOutputStream()
            scaled.compress(Bitmap.CompressFormat.PNG, 100, outputStream)
            bgImageBase64 = Base64.encodeToString(outputStream.toByteArray(), Base64.NO_WRAP)
            outputStream.close()

            val cursor = contentResolver.query(uri, null, null, null, null)
            cursor?.use {
                if (it.moveToFirst()) {
                    val idx = it.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                    if (idx >= 0) bgImageName = it.getString(idx)
                }
            }
            tvBgImageName.text = bgImageName ?: "已选择图片"
            Toast.makeText(this, "背景图片已选择", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, "图片加载失败", Toast.LENGTH_SHORT).show()
        }
    }

    private fun handleUserAvatarPicked(uri: Uri, isAi: Boolean) {
        try {
            val inputStream = contentResolver.openInputStream(uri) ?: return
            val bitmap = BitmapFactory.decodeStream(inputStream)
            inputStream?.close()
            if (bitmap == null) { Toast.makeText(this, "图片无效", Toast.LENGTH_SHORT).show(); return }

            val maxSize = 256
            val scale = Math.min(maxSize.toFloat() / bitmap.width, maxSize.toFloat() / bitmap.height).coerceAtMost(1f)
            val scaled = if (scale < 1f) Bitmap.createScaledBitmap(bitmap, (bitmap.width * scale).toInt(), (bitmap.height * scale).toInt(), true) else bitmap

            val outputStream = ByteArrayOutputStream()
            scaled.compress(Bitmap.CompressFormat.JPEG, 80, outputStream)
            val base64 = Base64.encodeToString(outputStream.toByteArray(), Base64.NO_WRAP)
            outputStream.close()

            if (isAi) {
                aiAvatarBase64 = base64
                btnPickAiAvatar.text = "AI 头像已选择"
            } else {
                userAvatarBase64 = base64
                btnPickUserAvatar.text = "头像已选择"
            }
            Toast.makeText(this, "头像已选择", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, "头像加载失败", Toast.LENGTH_SHORT).show()
        }
    }

    private fun showApiEntryDialog(index: Int?) {
        val entry = if (index != null && index < apiEntries.size) apiEntries[index] else SettingsKeys.ApiEntry()
        val fields = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 16, 48, 16)
        }

        val etLabel = TextInputEditText(this).apply { setText(entry.label); hint = "标签（如：主力GPT）" }
        val etUrl = TextInputEditText(this).apply { setText(entry.url); hint = "API 地址" }
        val etKey = TextInputEditText(this).apply { setText(entry.key); hint = "API Key" }
        val etModel = TextInputEditText(this).apply { setText(entry.model); hint = "模型名称" }

        // Free Gateway Integration: 预设模式下禁用 API Key 输入框
        if (providerMode != SettingsKeys.PROVIDER_CUSTOM) {
            etKey.isEnabled = false
        }

        for (editText in listOf(etLabel, etUrl, etKey, etModel)) {
            val inputLayout = TextInputLayout(this, null, com.google.android.material.R.style.Widget_Material3_TextInputLayout_OutlinedBox).apply {
                addView(editText)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).also { it.bottomMargin = 12 }
            }
            fields.addView(inputLayout)

            // Free Gateway Integration: 在 API Key 输入框下方显示免费服务提示
            if (editText === etKey && providerMode != SettingsKeys.PROVIDER_CUSTOM) {
                fields.addView(TextView(this).apply {
                    text = getString(R.string.provider_free_hint)
                    textSize = 12f
                    setTextColor(0xFF757575.toInt())
                    setPadding(0, 0, 0, (resources.displayMetrics.density * 12).toInt())
                })
            }
        }

        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(if (index != null) "编辑 API" else "添加 API")
            .setView(fields)
            .setPositiveButton("确定", null)
            .setNegativeButton("取消", null)
            .show()
        dialog.getButton(android.content.DialogInterface.BUTTON_POSITIVE)
            .setOnClickListener {
                val label = etLabel.text?.toString()?.trim() ?: ""
                if (label.isBlank()) { Toast.makeText(this, "标签不能为空", Toast.LENGTH_SHORT).show(); return@setOnClickListener }
                val newEntry = SettingsKeys.ApiEntry(
                    label = label,
                    url = etUrl.text?.toString()?.trim() ?: "",
                    key = etKey.text?.toString()?.trim() ?: "",
                    model = etModel.text?.toString()?.trim() ?: ""
                )
                if (index != null && index < apiEntries.size) apiEntries[index] = newEntry else apiEntries.add(newEntry)
                renderApiList()
                dialog.dismiss()
            }
    }

    private fun renderApiList() {
        containerApiList.removeAllViews()
        val density = resources.displayMetrics.density
        for ((i, entry) in apiEntries.withIndex()) {
            val isActive = i == activeApiIndex
            val card = MaterialCardView(this).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).also { it.bottomMargin = (density * 6).toInt() }
                radius = density * 8
                cardElevation = 0f
                strokeWidth = 0
                setCardBackgroundColor(
                    if (isActive) ContextCompat.getColor(this@SettingsActivity, R.color.md_theme_primaryContainer)
                    else ContextCompat.getColor(this@SettingsActivity, R.color.md_theme_surface)
                )
                setContentPadding((density * 12).toInt(), (density * 8).toInt(), (density * 12).toInt(), (density * 8).toInt())
            }

            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            }

            val textCol = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            textCol.addView(TextView(this).apply { text = entry.label; textSize = 15f; setTextColor(0xFF212121.toInt()) })
            textCol.addView(TextView(this).apply {
                text = if (isActive) "当前使用" else entry.model.ifBlank { "未配置" }
                textSize = 12f; setTextColor(if (isActive) 0xFF1976D2.toInt() else 0xFF757575.toInt())
            })

            val btnDelete = MaterialButton(this, null, com.google.android.material.R.style.Widget_Material3_Button_TextButton).apply {
                text = "删除"
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).also { gravity = android.view.Gravity.CENTER_VERTICAL }
                setTextColor(0xFFC62828.toInt())
                setOnClickListener {
                    apiEntries.removeAt(i)
                    if (activeApiIndex >= apiEntries.size) activeApiIndex = (apiEntries.size - 1).coerceAtLeast(0)
                    renderApiList()
                }
            }

            row.addView(textCol)
            row.addView(btnDelete)
            card.addView(row)
            card.setOnClickListener { activeApiIndex = i; renderApiList() }
            containerApiList.addView(card)
        }
    }

    private fun saveSettings() {
        val sp = getSharedPreferences(SettingsKeys.PREFS_NAME, Context.MODE_PRIVATE)
        sp.edit {
            putString(SettingsKeys.KEY_API_CONFIGS, gson.toJson(apiEntries))
            putInt(SettingsKeys.KEY_ACTIVE_API, activeApiIndex)
            // Free Gateway Integration: 保存预设服务商模式
            putString(SettingsKeys.KEY_PROVIDER_MODE, providerMode)
            putString(SettingsKeys.KEY_KILO_MODEL, kiloModelOption)
            putString(SettingsKeys.KEY_ZEN_MODEL, zenModelOption)
            putInt(SettingsKeys.KEY_FONT_SIZE, etFontSize.text?.toString()?.toIntOrNull() ?: 15)
            // 记忆历史消息轮数（全局生效），钳制在 5~100 范围内
            putInt(
                SettingsKeys.KEY_MAX_HISTORY,
                (etMaxHistory.text?.toString()?.toIntOrNull() ?: www.cetool.com.model.Conversation.MAX_HISTORY)
                    .coerceIn(SettingsKeys.MAX_HISTORY_MIN, SettingsKeys.MAX_HISTORY_MAX)
            )
            putInt(
                SettingsKeys.KEY_WORLDINFO_BUDGET,
                (etWorldBudget.text?.toString()?.toIntOrNull() ?: WorldInfoEngine.DEFAULT_BUDGET)
                    .coerceIn(200, 20000)
            )
            putString(SettingsKeys.KEY_BG_MODE, if (rbBgImage.isChecked) "image" else "color")
            putString(SettingsKeys.KEY_BG_IMAGE, bgImageBase64)
            putString("bg_image_name", bgImageName)
            putString(SettingsKeys.KEY_BG_SCALE, if (rbBgFill.isChecked) "fill" else "fit")
            putString(SettingsKeys.KEY_WEB_SEARCH_URL, etWebSearchUrl.text?.toString()?.trim() ?: "")
            putString(SettingsKeys.KEY_USER_NAME, etUserName.text?.toString()?.trim()?.ifBlank { "用户" })
            putString(SettingsKeys.KEY_AI_NAME, etAiName.text?.toString()?.trim()?.ifBlank { "AI" })
            putString(SettingsKeys.KEY_USER_AVATAR, userAvatarBase64)
            putString(SettingsKeys.KEY_AI_AVATAR, aiAvatarBase64)
        }

        ConversationManager.init(this)
        ConversationManager.setDefaultSystemPrompt(etSystemPrompt.text?.toString()?.trim() ?: "")

        if (apiEntries.isNotEmpty() && activeApiIndex < apiEntries.size) {
            val active = apiEntries[activeApiIndex]
            getSharedPreferences(ApiConfig.PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putString(ApiConfig.KEY_URL, active.url)
                .putString(ApiConfig.KEY_KEY, active.key)
                .putString(ApiConfig.KEY_MODEL, active.model)
                .apply()
        }

        Toast.makeText(this, "设置已保存", Toast.LENGTH_SHORT).show()
        startActivity(Intent(this, ChatActivity::class.java))
        finish()
    }
}
