package com.anthroid.claude.ui

import android.text.format.DateUtils
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.anthroid.R
import com.anthroid.claude.ConversationManager

/**
 * Adapter for displaying conversation list in dialog.
 */
class ConversationAdapter(
    private val onConversationClick: (ConversationManager.Conversation) -> Unit,
    private val onDeleteClick: (ConversationManager.Conversation) -> Unit,
    private val onTitleEdit: ((ConversationManager.Conversation) -> Unit)? = null
) : ListAdapter<ConversationManager.Conversation, ConversationAdapter.ViewHolder>(DiffCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_conversation, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val titleText: TextView = itemView.findViewById(R.id.conversation_title)
        private val previewText: TextView = itemView.findViewById(R.id.conversation_preview)
        private val timeText: TextView = itemView.findViewById(R.id.conversation_time)
        private val statsText: TextView = itemView.findViewById(R.id.conversation_stats)
        private val deleteButton: ImageButton = itemView.findViewById(R.id.btn_delete)

        fun bind(conversation: ConversationManager.Conversation) {
            titleText.text = conversation.title
            previewText.text = conversation.lastMessage.ifEmpty { "(No preview)" }

            // Format timestamp
            if (conversation.timestamp > 0) {
                timeText.text = DateUtils.getRelativeTimeSpanString(
                    conversation.timestamp,
                    System.currentTimeMillis(),
                    DateUtils.MINUTE_IN_MILLIS,
                    DateUtils.FORMAT_ABBREV_RELATIVE
                )
            } else {
                timeText.text = "Unknown time"
            }

            // Format stats
            val sizeKb = conversation.fileSize / 1024
            statsText.text = "${conversation.messageCount} msgs · ${sizeKb}KB"

            itemView.setOnClickListener {
                onConversationClick(conversation)
            }

            deleteButton.setOnClickListener {
                onDeleteClick(conversation)
            }

            // Long-press on title to edit
            titleText.setOnLongClickListener {
                onTitleEdit?.invoke(conversation)
                true
            }
        }
    }

    private class DiffCallback : DiffUtil.ItemCallback<ConversationManager.Conversation>() {
        override fun areItemsTheSame(
            oldItem: ConversationManager.Conversation,
            newItem: ConversationManager.Conversation
        ): Boolean = oldItem.sessionId == newItem.sessionId

        override fun areContentsTheSame(
            oldItem: ConversationManager.Conversation,
            newItem: ConversationManager.Conversation
        ): Boolean = oldItem == newItem
    }
}
