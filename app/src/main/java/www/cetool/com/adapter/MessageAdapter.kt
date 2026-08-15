package www.cetool.com.adapter

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.util.Base64
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.bumptech.glide.load.resource.bitmap.CircleCrop
import com.google.android.material.card.MaterialCardView
import com.google.android.material.color.MaterialColors
import com.google.android.material.shape.CornerFamily
import com.google.android.material.shape.ShapeAppearanceModel
import io.noties.markwon.Markwon
import www.cetool.com.R
import www.cetool.com.SettingsKeys
import www.cetool.com.model.Message
import www.cetool.com.model.Message.Companion.ROLE_USER
import www.cetool.com.model.Message.Companion.ATTACH_TYPE_IMAGE
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MessageAdapter(
    private val messages: MutableList<Message>,
    private val markwon: Markwon
) : RecyclerView.Adapter<MessageAdapter.MessageViewHolder>() {

    var characterAvatarBase64: String? = null
    var userAvatarBase64: String? = null

    companion object {
        private const val THINKING_PLACEHOLDER = "思考中…"
    }

    private val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())
    private val reasoningExpandedPositions = mutableSetOf<Int>()

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): MessageViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_message, parent, false)
        return MessageViewHolder(view)
    }

    override fun onBindViewHolder(holder: MessageViewHolder, position: Int) {
        val message = messages[position]
        holder.bind(message, position)
    }

    override fun getItemCount(): Int = messages.size

    fun updateMessages(newMessages: List<Message>) {
        messages.clear()
        messages.addAll(newMessages)
        notifyDataSetChanged()
    }

    fun syncMessageAt(index: Int, message: Message) {
        if (index in messages.indices) {
            messages[index] = message
            notifyItemChanged(index)
        }
    }

    inner class MessageViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val rootMessage: ConstraintLayout = itemView.findViewById(R.id.rootMessage)
        private val cardMessage: MaterialCardView = itemView.findViewById(R.id.cardMessage)
        private val tvMessage: TextView = itemView.findViewById(R.id.tvMessage)
        private val ivAvatar: ImageView = itemView.findViewById(R.id.ivAvatar)
        private val tvNickname: TextView = itemView.findViewById(R.id.tvNickname)
        private val tvTimestamp: TextView = itemView.findViewById(R.id.tvTimestamp)
        private val ivAvatarUser: ImageView = itemView.findViewById(R.id.ivAvatarUser)
        private val layoutReasoning: View = itemView.findViewById(R.id.layoutReasoning)
        private val tvReasoningContent: TextView = itemView.findViewById(R.id.tvReasoningContent)
        private val tvReasoningArrow: TextView = itemView.findViewById(R.id.tvReasoningArrow)

        init {
            tvMessage.setOnLongClickListener {
                val clipboard = itemView.context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                val clip = ClipData.newPlainText("message", tvMessage.text)
                clipboard.setPrimaryClip(clip)
                Toast.makeText(itemView.context, "已复制", Toast.LENGTH_SHORT).show()
                true
            }
        }

        fun bind(message: Message, position: Int) {
            val ctx = itemView.context
            val isUser = message.role == ROLE_USER
            val density = ctx.resources.displayMetrics.density
            val screenWidth = ctx.resources.displayMetrics.widthPixels
            cardMessage.layoutParams.width = (screenWidth * 0.75).toInt()

            val sp = ctx.getSharedPreferences(SettingsKeys.PREFS_NAME, Context.MODE_PRIVATE)
            val aiName = sp.getString(SettingsKeys.KEY_AI_NAME, "AI") ?: "AI"
            val fontSize = sp.getInt(SettingsKeys.KEY_FONT_SIZE, 15)

            tvMessage.textSize = fontSize.toFloat()

            val cornerSmall = 8f * density
            val cornerLarge = 24f * density
            val shapeModel = ShapeAppearanceModel.builder()
                .setTopLeftCorner(CornerFamily.ROUNDED, if (isUser) cornerLarge else cornerSmall)
                .setTopRightCorner(CornerFamily.ROUNDED, if (isUser) cornerSmall else cornerLarge)
                .setBottomLeftCorner(CornerFamily.ROUNDED, if (isUser) cornerLarge else cornerSmall)
                .setBottomRightCorner(CornerFamily.ROUNDED, if (isUser) cornerSmall else cornerLarge)
                .build()
            cardMessage.shapeAppearanceModel = shapeModel

            if (isUser) {
                cardMessage.setCardBackgroundColor(
                    MaterialColors.getColor(ctx, com.google.android.material.R.attr.colorPrimaryContainer, 0xFF006487.toInt())
                )
                tvMessage.setTextColor(
                    MaterialColors.getColor(ctx, com.google.android.material.R.attr.colorOnPrimaryContainer, 0xFFFFFFFF.toInt())
                )
                tvMessage.text = message.content
            } else {
                cardMessage.setCardBackgroundColor(
                    MaterialColors.getColor(ctx, com.google.android.material.R.attr.colorSecondaryContainer, 0xFFE0E0E0.toInt())
                )
                tvMessage.setTextColor(
                    MaterialColors.getColor(ctx, com.google.android.material.R.attr.colorOnSecondaryContainer, 0xFF212121.toInt())
                )
                if (message.content.isNotEmpty()) {
                    markwon.setMarkdown(tvMessage, message.content)
                } else {
                    tvMessage.text = THINKING_PLACEHOLDER
                }
            }

            val hasReasoning = !isUser && message.reasoningContent.isNotBlank()
            val isThinking = !isUser && !hasReasoning && message.content.isEmpty()
            val showReasoningSection = hasReasoning || isThinking
            layoutReasoning.visibility = if (showReasoningSection) View.VISIBLE else View.GONE
            if (showReasoningSection) {
                val expanded = reasoningExpandedPositions.contains(position)
                tvReasoningArrow.visibility = View.VISIBLE
                if (expanded) {
                    tvReasoningContent.visibility = View.VISIBLE
                    tvReasoningContent.text = if (hasReasoning) message.reasoningContent else THINKING_PLACEHOLDER
                    tvReasoningArrow.text = "▾"
                } else {
                    tvReasoningContent.visibility = View.GONE
                    tvReasoningArrow.text = "▸"
                }
                layoutReasoning.setOnClickListener {
                    if (reasoningExpandedPositions.contains(position)) {
                        reasoningExpandedPositions.remove(position)
                    } else {
                        reasoningExpandedPositions.add(position)
                    }
                    notifyItemChanged(position)
                }
            }

            val cardParams = cardMessage.layoutParams as ConstraintLayout.LayoutParams
            val avatarParams = ivAvatar.layoutParams as ConstraintLayout.LayoutParams
            val nickParams = tvNickname.layoutParams as ConstraintLayout.LayoutParams
            val userAvatarParams = ivAvatarUser.layoutParams as ConstraintLayout.LayoutParams

            if (isUser) {
                ivAvatar.visibility = View.GONE
                tvNickname.visibility = View.GONE
                ivAvatarUser.visibility = View.VISIBLE
                tvTimestamp.visibility = View.VISIBLE

                cardParams.startToStart = ConstraintLayout.LayoutParams.PARENT_ID
                cardParams.endToStart = ivAvatarUser.id
                cardParams.startToEnd = ConstraintLayout.LayoutParams.UNSET
                cardParams.endToEnd = ConstraintLayout.LayoutParams.UNSET
                cardParams.horizontalBias = 1.0f

                avatarParams.startToStart = ConstraintLayout.LayoutParams.UNSET

                userAvatarParams.endToEnd = ConstraintLayout.LayoutParams.PARENT_ID

                tvTimestamp.text = timeFormat.format(Date(message.timestamp))

                if (userAvatarBase64 != null) {
                    try {
                        val bytes = Base64.decode(userAvatarBase64, Base64.DEFAULT)
                        Glide.with(ivAvatarUser.context)
                            .load(bytes)
                            .transform(CircleCrop())
                            .override(80, 80)
                            .into(ivAvatarUser)
                        ivAvatarUser.visibility = View.VISIBLE
                    } catch (_: Exception) {
                        ivAvatarUser.visibility = View.GONE
                    }
                } else {
                    ivAvatarUser.visibility = View.GONE
                }
            } else {
                ivAvatar.visibility = View.VISIBLE
                tvNickname.visibility = View.VISIBLE
                ivAvatarUser.visibility = View.GONE
                tvTimestamp.visibility = View.VISIBLE

                avatarParams.startToStart = ConstraintLayout.LayoutParams.PARENT_ID

                nickParams.startToEnd = R.id.ivAvatar
                nickParams.startToStart = ConstraintLayout.LayoutParams.UNSET

                cardParams.startToEnd = R.id.ivAvatar
                cardParams.endToEnd = ConstraintLayout.LayoutParams.PARENT_ID
                cardParams.startToStart = ConstraintLayout.LayoutParams.UNSET
                cardParams.endToStart = ConstraintLayout.LayoutParams.UNSET
                cardParams.horizontalBias = 0.0f

                tvNickname.text = aiName
                tvTimestamp.text = timeFormat.format(Date(message.timestamp))

                if (characterAvatarBase64 != null) {
                    try {
                        val bytes = Base64.decode(characterAvatarBase64, Base64.DEFAULT)
                        Glide.with(ivAvatar.context)
                            .load(bytes)
                            .transform(CircleCrop())
                            .override(80, 80)
                            .into(ivAvatar)
                        ivAvatar.visibility = View.VISIBLE
                    } catch (_: Exception) {
                        ivAvatar.visibility = View.GONE
                    }
                } else {
                    ivAvatar.visibility = View.GONE
                }
            }

            cardMessage.layoutParams = cardParams
            ivAvatar.layoutParams = avatarParams
            tvNickname.layoutParams = nickParams
            ivAvatarUser.layoutParams = userAvatarParams

            if (!isUser && message.timestamp > 0) {
                tvTimestamp.text = timeFormat.format(Date(message.timestamp))
            }
        }
    }
}
