package com.anthroid.claude.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.anthroid.R
import com.anthroid.claude.QuickSendCandidate
import com.google.android.material.chip.Chip

/**
 * Adapter for quick send candidate chips.
 * Displays frequently used short messages as clickable chips.
 */
class QuickSendAdapter(
    private val onItemClick: (String) -> Unit
) : ListAdapter<QuickSendCandidate, QuickSendAdapter.ViewHolder>(DiffCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_quick_send, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val chip: Chip = itemView.findViewById(R.id.quick_send_chip)

        fun bind(candidate: QuickSendCandidate) {
            // Show text only (no count)
            chip.text = candidate.text
            chip.setOnClickListener {
                onItemClick(candidate.text)
            }
        }
    }

    private class DiffCallback : DiffUtil.ItemCallback<QuickSendCandidate>() {
        override fun areItemsTheSame(oldItem: QuickSendCandidate, newItem: QuickSendCandidate): Boolean {
            return oldItem.text == newItem.text
        }

        override fun areContentsTheSame(oldItem: QuickSendCandidate, newItem: QuickSendCandidate): Boolean {
            return oldItem == newItem
        }
    }
}
