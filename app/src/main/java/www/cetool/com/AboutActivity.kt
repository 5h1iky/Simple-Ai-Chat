package www.cetool.com

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.text.Html
import android.view.ViewGroup
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.dialog.MaterialAlertDialogBuilder

class AboutActivity : AppCompatActivity() {

    private val BILIBILI_URL = "https://space.bilibili.com/432122433"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_about)

        val tvAppName: TextView = findViewById(R.id.tvAppName)
        val tvVersion: TextView = findViewById(R.id.tvVersion)
        val cardAuthor: MaterialCardView = findViewById(R.id.cardAuthor)
        val cardFeedback: MaterialCardView = findViewById(R.id.cardFeedback)
        val cardDisclaimer: MaterialCardView = findViewById(R.id.cardDisclaimer)

        tvAppName.text = "SAChat"
        val version = try {
            packageManager.getPackageInfo(packageName, 0).versionName
        } catch (_: Exception) { "1.0" }
        tvVersion.text = "v$version"

        cardFeedback.setOnClickListener {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(BILIBILI_URL))
            startActivity(intent)
        }

        cardDisclaimer.setOnClickListener { showDisclaimerDialog() }
    }

    private fun showDisclaimerDialog() {
        val density = resources.displayMetrics.density
        val contentText = TextView(this).apply {
            text = Html.fromHtml(getString(R.string.disclaimer_content), Html.FROM_HTML_MODE_LEGACY)
            textSize = 14f
            setLineSpacing(4f, 1.1f)
            setPadding((density * 4).toInt(), 0, (density * 4).toInt(), 0)
            setTextColor(0xFF212121.toInt())
        }

        val scrollView = ScrollView(this).apply {
            addView(contentText, ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ))
            // 限制对话框内容高度为屏幕 60%，超长可滚动
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                (resources.displayMetrics.heightPixels * 0.6).toInt()
            )
        }

        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.disclaimer_title)
            .setView(scrollView)
            .setPositiveButton(R.string.got_it, null)
            .show()
    }
}
