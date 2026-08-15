package www.cetool.com

import android.content.Intent
import android.os.Bundle
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.switchmaterial.SwitchMaterial
import com.google.android.material.textfield.TextInputEditText
import www.cetool.com.manager.WorldInfoManager
import www.cetool.com.model.WorldEntry
import www.cetool.com.model.WorldInfo

class CreateWorldInfoActivity : AppCompatActivity() {

    private lateinit var etName: TextInputEditText
    private lateinit var etDescription: TextInputEditText
    private lateinit var switchEnabled: SwitchMaterial
    private lateinit var containerEntries: LinearLayout
    private lateinit var btnAddEntry: MaterialButton
    private lateinit var btnSave: MaterialButton
    private lateinit var tvTitle: TextView

    private var editId: String? = null
    private val entries = mutableListOf<WorldEntry>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_create_worldinfo)

        tvTitle = findViewById(R.id.tvTitle)
        etName = findViewById(R.id.etName)
        etDescription = findViewById(R.id.etDescription)
        switchEnabled = findViewById(R.id.switchEnabled)
        containerEntries = findViewById(R.id.containerEntries)
        btnAddEntry = findViewById(R.id.btnAddEntry)
        btnSave = findViewById(R.id.btnSave)

        editId = intent.getStringExtra("world_info_id")
        if (editId != null) {
            loadExisting(editId!!)
        }

        btnAddEntry.setOnClickListener { showEntryDialog(null) }
        btnSave.setOnClickListener { saveWorldInfo() }
    }

    private fun loadExisting(id: String) {
        val info = WorldInfoManager.load(this, id) ?: return
        tvTitle.text = "编辑世界书"
        etName.setText(info.name)
        etDescription.setText(info.description)
        switchEnabled.isChecked = info.enabled
        entries.clear()
        entries.addAll(info.entries)
        renderEntries()
    }

    private fun renderEntries() {
        containerEntries.removeAllViews()
        for ((index, entry) in entries.withIndex()) {
            val card = layoutInflater.inflate(R.layout.item_world_entry_card, containerEntries, false) as MaterialCardView
            card.findViewById<TextView>(R.id.tvEntryName).text = entry.name
            val summary = entry.keywords.take(3).joinToString(", ")
            card.findViewById<TextView>(R.id.tvEntryKeywords).text = if (summary.isNotBlank()) "关键词: $summary" else "无关键词"
            card.findViewById<TextView>(R.id.tvEntryPriority).text = "优先级: ${entry.priority}"
            card.setOnClickListener { showEntryDialog(index) }
            card.setOnLongClickListener {
                MaterialAlertDialogBuilder(this)
                    .setTitle("删除条目")
                    .setMessage("确定要删除「${entry.name}」吗？")
                    .setPositiveButton("删除") { _, _ -> entries.removeAt(index); renderEntries() }
                    .setNegativeButton("取消", null)
                    .show()
                true
            }
            containerEntries.addView(card)
        }
    }

    private fun showEntryDialog(index: Int?) {
        val entry = if (index != null) entries[index] else WorldEntry()
        val view = layoutInflater.inflate(R.layout.dialog_world_entry, null)
        val etName = view.findViewById<TextInputEditText>(R.id.etEntryName)
        val etKeywords = view.findViewById<TextInputEditText>(R.id.etEntryKeywords)
        val etContent = view.findViewById<TextInputEditText>(R.id.etEntryContent)
        val etPriority = view.findViewById<TextInputEditText>(R.id.etEntryPriority)
        val etScanDepth = view.findViewById<TextInputEditText>(R.id.etEntryScanDepth)
        val etInjectDepth = view.findViewById<TextInputEditText>(R.id.etEntryInjectDepth)
        val switchUseRegex = view.findViewById<SwitchMaterial>(R.id.switchUseRegex)
        val switchCaseSensitive = view.findViewById<SwitchMaterial>(R.id.switchCaseSensitive)
        val switchConstantActive = view.findViewById<SwitchMaterial>(R.id.switchConstantActive)
        val etEntryRole = view.findViewById<TextInputEditText>(R.id.etEntryRole)

        etName.setText(entry.name)
        etKeywords.setText(entry.keywords.joinToString(", "))
        etContent.setText(entry.content)
        etPriority.setText(entry.priority.toString())
        etScanDepth.setText(entry.scanDepth.toString())
        etInjectDepth.setText(entry.injectDepth.toString())
        switchUseRegex.isChecked = entry.useRegex
        switchCaseSensitive.isChecked = entry.caseSensitive
        switchConstantActive.isChecked = entry.constantActive
        etEntryRole.setText(entry.role ?: "")

        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(if (index != null) "编辑条目" else "新建条目")
            .setView(view)
            .setPositiveButton("保存", null)
            .setNegativeButton("取消", null)
            .show()
        dialog.getButton(android.content.DialogInterface.BUTTON_POSITIVE).setOnClickListener {
            val name = etName.text?.toString()?.trim() ?: ""
            if (name.isBlank()) {
                Toast.makeText(this, "条目名称不能为空", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val kw = etKeywords.text?.toString()?.trim() ?: ""
            val role = etEntryRole.text?.toString()?.trim() ?: ""
            val updated = WorldEntry(
                id = entry.id,
                name = name,
                enabled = true,
                priority = etPriority.text?.toString()?.toIntOrNull() ?: 1000,
                content = etContent.text?.toString()?.trim() ?: "",
                injectDepth = etInjectDepth.text?.toString()?.toIntOrNull() ?: 4,
                keywords = if (kw.isNotBlank()) kw.split(Regex("[，,、\\s]+")).toMutableList() else mutableListOf(),
                scanDepth = etScanDepth.text?.toString()?.toIntOrNull() ?: 4,
                useRegex = switchUseRegex.isChecked,
                caseSensitive = switchCaseSensitive.isChecked,
                constantActive = switchConstantActive.isChecked,
                role = role.ifBlank { null }
            )
            if (index != null) entries[index] = updated else entries.add(updated)
            renderEntries()
            dialog.dismiss()
        }
    }

    private fun saveWorldInfo() {
        val name = etName.text?.toString()?.trim() ?: ""
        if (name.isBlank()) { etName.error = "名称不能为空"; return }
        if (entries.isEmpty()) { Toast.makeText(this, "请至少添加一个条目", Toast.LENGTH_SHORT).show(); return }

        val info = WorldInfo(
            id = editId ?: java.util.UUID.randomUUID().toString().take(8),
            name = name,
            description = etDescription.text?.toString()?.trim() ?: "",
            enabled = switchEnabled.isChecked,
            entries = entries.toMutableList()
        )

        if (editId != null) {
            WorldInfoManager.overwrite(this, editId!!, info)
        } else {
            WorldInfoManager.saveNew(this, info)
        }
        Toast.makeText(this, "世界书「$name」已保存", Toast.LENGTH_SHORT).show()
        val intent = Intent().apply { putExtra("world_info_id", info.id) }
        setResult(RESULT_OK, intent)
        finish()
    }
}
