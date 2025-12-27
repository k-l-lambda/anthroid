package com.anthroid.claude.ui

import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ProgressBar
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.anthroid.R
import com.anthroid.claude.Message
import com.anthroid.claude.MessageRole
import java.text.SimpleDateFormat
import java.util.*

/**
 * Adapter for displaying chat messages in RecyclerView.
 */
class MessageAdapter : ListAdapter<Message, MessageAdapter.MessageViewHolder>(MessageDiffCallback()) {

    companion object {
        private const val TAG = "MessageAdapter"
        private const val VIEW_TYPE_USER = 0
        private const val VIEW_TYPE_ASSISTANT = 1
        private const val PAYLOAD_CONTENT_CHANGED = "content_changed"
    }

    override fun getItemViewType(position: Int): Int {
        return when (getItem(position).role) {
            MessageRole.USER -> VIEW_TYPE_USER
            MessageRole.ASSISTANT -> VIEW_TYPE_ASSISTANT
            MessageRole.SYSTEM -> VIEW_TYPE_ASSISTANT
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): MessageViewHolder {
        val layoutId = when (viewType) {
            VIEW_TYPE_USER -> R.layout.item_message_user
            else -> R.layout.item_message_assistant
        }
        val view = LayoutInflater.from(parent.context).inflate(layoutId, parent, false)
        return MessageViewHolder(view)
    }

    override fun onBindViewHolder(holder: MessageViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    override fun onBindViewHolder(holder: MessageViewHolder, position: Int, payloads: MutableList<Any>) {
        if (payloads.isEmpty()) {
            holder.bind(getItem(position))
        } else {
            // Partial bind - just update content
            holder.updateContent(getItem(position))
        }
    }

    class MessageViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val contentText: TextView = itemView.findViewById(R.id.message_content)
        private val timestampText: TextView = itemView.findViewById(R.id.message_timestamp)
        private val streamingIndicator: ProgressBar? = itemView.findViewById(R.id.streaming_indicator)

        private val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())

        fun bind(message: Message) {
            Log.d(TAG, "bind: id=${message.id.take(8)}, len=${message.content.length}, streaming=${message.isStreaming}")
            updateContent(message)
            timestampText.text = timeFormat.format(Date(message.timestamp))
        }

        fun updateContent(message: Message) {
            if (message.isStreaming && message.content.isEmpty()) {
                contentText.text = "..."
                streamingIndicator?.visibility = View.VISIBLE
            } else {
                contentText.text = message.content
                streamingIndicator?.visibility = if (message.isStreaming) View.VISIBLE else View.GONE
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
                   oldItem.role == newItem.role
        }

        override fun getChangePayload(oldItem: Message, newItem: Message): Any? {
            if (oldItem.content != newItem.content || oldItem.isStreaming != newItem.isStreaming) {
                return PAYLOAD_CONTENT_CHANGED
            }
            return null
        }
    }
}
