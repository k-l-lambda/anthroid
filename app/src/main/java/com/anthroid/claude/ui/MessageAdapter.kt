package com.anthroid.claude.ui

import android.util.Log
import android.content.Context
import android.graphics.Color
import android.text.method.LinkMovementMethod
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.anthroid.R
import com.anthroid.claude.Message
import com.anthroid.claude.MessageImage
import com.anthroid.claude.MessageRole
import io.noties.markwon.Markwon
import io.noties.markwon.ext.strikethrough.StrikethroughPlugin
import io.noties.markwon.ext.tables.TablePlugin
import io.noties.markwon.linkify.LinkifyPlugin
import java.text.SimpleDateFormat
import java.util.*

/**
 * Adapter for displaying chat messages in RecyclerView.
 */
class MessageAdapter(
    private val onImageClick: ((MessageImage) -> Unit)? = null,
    private val onToolClick: ((Message) -> Unit)? = null
) : ListAdapter<Message, RecyclerView.ViewHolder>(MessageDiffCallback()) {

    companion object {
        private const val TAG = "MessageAdapter"
        private const val VIEW_TYPE_USER = 0
        private const val VIEW_TYPE_ASSISTANT = 1
        private const val VIEW_TYPE_TOOL = 2
        private const val PAYLOAD_CONTENT_CHANGED = "content_changed"

        @Volatile
        private var markwonInstance: Markwon? = null

        fun getMarkwon(context: Context): Markwon {
            return markwonInstance ?: synchronized(this) {
                markwonInstance ?: Markwon.builder(context.applicationContext)
                    .usePlugin(StrikethroughPlugin.create())
                    .usePlugin(TablePlugin.create(context.applicationContext))
                    .usePlugin(LinkifyPlugin.create())
                    .build()
                    .also { markwonInstance = it }
            }
        }
    }

    override fun getItemViewType(position: Int): Int {
        return when (getItem(position).role) {
            MessageRole.USER -> VIEW_TYPE_USER
            MessageRole.ASSISTANT -> VIEW_TYPE_ASSISTANT
            MessageRole.SYSTEM -> VIEW_TYPE_ASSISTANT
            MessageRole.TOOL -> VIEW_TYPE_TOOL
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        return when (viewType) {
            VIEW_TYPE_USER -> {
                val view = LayoutInflater.from(parent.context).inflate(R.layout.item_message_user, parent, false)
                MessageViewHolder(view, isAssistant = false, onImageClick = onImageClick)
            }
            VIEW_TYPE_TOOL -> {
                val view = LayoutInflater.from(parent.context).inflate(R.layout.item_message_tool, parent, false)
                ToolViewHolder(view, onToolClick = onToolClick)
            }
            else -> {
                val view = LayoutInflater.from(parent.context).inflate(R.layout.item_message_assistant, parent, false)
                MessageViewHolder(view, isAssistant = true, onImageClick = onImageClick)
            }
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (holder) {
            is MessageViewHolder -> holder.bind(getItem(position))
            is ToolViewHolder -> holder.bind(getItem(position))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int, payloads: MutableList<Any>) {
        if (payloads.isEmpty()) {
            when (holder) {
                is MessageViewHolder -> holder.bind(getItem(position))
                is ToolViewHolder -> holder.bind(getItem(position))
            }
        } else {
            // Partial bind - just update content
            when (holder) {
                is MessageViewHolder -> holder.updateContent(getItem(position))
                is ToolViewHolder -> holder.updateContent(getItem(position))
            }
        }
    }

    class MessageViewHolder(
        itemView: View,
        private val isAssistant: Boolean = false,
        private val onImageClick: ((MessageImage) -> Unit)? = null
    ) : RecyclerView.ViewHolder(itemView) {
        private val contentText: TextView = itemView.findViewById(R.id.message_content)
        private val timestampText: TextView = itemView.findViewById(R.id.message_timestamp)
        private val streamingIndicator: ProgressBar? = itemView.findViewById(R.id.streaming_indicator)
        private val interruptedIndicator: TextView? = itemView.findViewById(R.id.interrupted_indicator)
        private val imagesContainer: LinearLayout? = itemView.findViewById(R.id.images_container)

        private val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())
        private val markwon: Markwon by lazy { getMarkwon(itemView.context) }

        init {
            if (isAssistant) {
                contentText.movementMethod = LinkMovementMethod.getInstance()
            }
        }

        fun bind(message: Message) {
            Log.d(TAG, "bind: id=${message.id.take(8)}, len=${message.content.length}, streaming=${message.isStreaming}, interrupted=${message.isInterrupted}, images=${message.images.size}")
            updateContent(message)
            updateImages(message)
            timestampText.text = timeFormat.format(Date(message.timestamp))
        }

        fun updateContent(message: Message) {
            if (message.isStreaming && message.content.isEmpty()) {
                contentText.text = "..."
                streamingIndicator?.visibility = View.VISIBLE
                interruptedIndicator?.visibility = View.GONE
                if (isAssistant) contentText.setTextColor(Color.parseColor("#333333"))
            } else {
                // Use Markwon for assistant messages, plain text for user messages
                if (isAssistant && !message.isError && message.content.isNotEmpty()) {
                    markwon.setMarkdown(contentText, message.content)
                } else {
                    contentText.text = message.content
                }
                streamingIndicator?.visibility = if (message.isStreaming) View.VISIBLE else View.GONE
                interruptedIndicator?.visibility = if (message.isInterrupted) View.VISIBLE else View.GONE
                // Show error messages in red
                if (message.isError) {
                    contentText.setTextColor(Color.parseColor("#D32F2F"))
                } else if (message.isInterrupted) {
                    contentText.setTextColor(Color.parseColor("#757575"))
                } else {
                    if (isAssistant) contentText.setTextColor(Color.parseColor("#333333"))
                }
            }
            // Hide content text if empty and has images
            if (message.content.isEmpty() && message.images.isNotEmpty()) {
                contentText.visibility = View.GONE
            } else {
                contentText.visibility = View.VISIBLE
            }
        }

        private fun updateImages(message: Message) {
            imagesContainer?.let { container ->
                container.removeAllViews()
                if (message.images.isEmpty()) {
                    container.visibility = View.GONE
                    return
                }

                container.visibility = View.VISIBLE
                val context = itemView.context
                val imageSize = (60 * context.resources.displayMetrics.density).toInt()
                val imageMargin = (4 * context.resources.displayMetrics.density).toInt()

                message.images.forEach { messageImage ->
                    val imageView = ImageView(context).apply {
                        layoutParams = LinearLayout.LayoutParams(imageSize, imageSize).apply {
                            marginEnd = imageMargin
                        }
                        scaleType = ImageView.ScaleType.CENTER_CROP
                        setBackgroundColor(Color.parseColor("#E0E0E0"))
                        try {
                            setImageURI(messageImage.uri)
                        } catch (e: Exception) {
                            Log.e(TAG, "Failed to load image: ${messageImage.uri}", e)
                        }
                        // Add click listener for image preview
                        setOnClickListener {
                            onImageClick?.invoke(messageImage)
                        }
                    }
                    container.addView(imageView)
                }
            }
        }
    }

    class ToolViewHolder(
        itemView: View,
        private val onToolClick: ((Message) -> Unit)? = null
    ) : RecyclerView.ViewHolder(itemView) {
        private val toolBubble: View = itemView.findViewById(R.id.tool_bubble)
        private val toolIcon: TextView = itemView.findViewById(R.id.tool_icon)
        private val toolName: TextView = itemView.findViewById(R.id.tool_name)
        private val toolInput: TextView = itemView.findViewById(R.id.tool_input)
        private val streamingIndicator: ProgressBar? = itemView.findViewById(R.id.streaming_indicator)
        private var currentMessage: Message? = null

        fun bind(message: Message) {
            Log.d(TAG, "bind tool: id=${message.id.take(8)}, name=${message.toolName}, streaming=${message.isStreaming}, error=${message.isError}")
            currentMessage = message
            updateContent(message)
            // Add click listener for tool detail dialog
            toolBubble.setOnClickListener {
                currentMessage?.let { msg ->
                    onToolClick?.invoke(msg)
                }
            }
        }

        fun updateContent(message: Message) {
            // Strip MCP server prefixes from tool name for cleaner display
            val displayName = (message.toolName ?: "Tool")
                .removePrefix("mcp__anthroid__")
                .removePrefix("mcp__")
            toolName.text = displayName
            toolInput.text = message.toolInput ?: message.content
            streamingIndicator?.visibility = if (message.isStreaming) View.VISIBLE else View.GONE

            // Update colors based on state
            val context = itemView.context
            when {
                message.isStreaming -> {
                    // Running: light blue
                    toolBubble.setBackgroundResource(R.drawable.bg_message_tool_running)
                    toolName.setTextColor(context.getColor(R.color.tool_running_name))
                }
                message.isError -> {
                    // Error: red
                    toolBubble.setBackgroundResource(R.drawable.bg_message_tool_error)
                    toolName.setTextColor(context.getColor(R.color.tool_error_name))
                }
                else -> {
                    // Success: green
                    toolBubble.setBackgroundResource(R.drawable.bg_message_tool)
                    toolName.setTextColor(context.getColor(R.color.tool_name_color))
                }
            }
        }
    }

    class MessageDiffCallback : DiffUtil.ItemCallback<Message>() {
        override fun areItemsTheSame(oldItem: Message, newItem: Message): Boolean {
            return oldItem.id == newItem.id
        }

        override fun areContentsTheSame(oldItem: Message, newItem: Message): Boolean {
            return oldItem.content == newItem.content &&
                   oldItem.isStreaming == newItem.isStreaming &&
                   oldItem.isError == newItem.isError &&
                   oldItem.isInterrupted == newItem.isInterrupted &&
                   oldItem.role == newItem.role &&
                   oldItem.toolName == newItem.toolName &&
                   oldItem.toolInput == newItem.toolInput &&
                   oldItem.toolOutput == newItem.toolOutput &&
                   oldItem.images == newItem.images
        }

        override fun getChangePayload(oldItem: Message, newItem: Message): Any? {
            if (oldItem.content != newItem.content || oldItem.isStreaming != newItem.isStreaming || oldItem.isInterrupted != newItem.isInterrupted) {
                return PAYLOAD_CONTENT_CHANGED
            }
            return null
        }
    }
}
