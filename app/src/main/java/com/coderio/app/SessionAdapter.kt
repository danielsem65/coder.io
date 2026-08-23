package com.coderio.app

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import java.text.SimpleDateFormat
import java.util.*

class SessionAdapter(
    private val onItemClick: (ChatHistoryManager.ChatSession) -> Unit,
    private val onDeleteClick: (ChatHistoryManager.ChatSession) -> Unit
) : ListAdapter<ChatHistoryManager.ChatSession, SessionAdapter.ViewHolder>(DiffCallback) {

    object DiffCallback : DiffUtil.ItemCallback<ChatHistoryManager.ChatSession>() {
        override fun areItemsTheSame(a: ChatHistoryManager.ChatSession, b: ChatHistoryManager.ChatSession) = a.id == b.id
        override fun areContentsTheSame(a: ChatHistoryManager.ChatSession, b: ChatHistoryManager.ChatSession) = a == b
    }

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val tvTitle: TextView = view.findViewById(R.id.tv_session_title)
        val tvDate: TextView = view.findViewById(R.id.tv_session_date)
        val btnDelete: ImageButton = view.findViewById(R.id.btn_delete_session)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_chat_session, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val session = getItem(position)
        holder.tvTitle.text = session.title
        holder.tvDate.text = formatDate(session.updatedAt)

        holder.itemView.setOnClickListener { onItemClick(session) }
        holder.btnDelete.setOnClickListener { onDeleteClick(session) }
    }

    private fun formatDate(millis: Long): String {
        val now = System.currentTimeMillis()
        val diff = now - millis
        val cal = Calendar.getInstance().apply { timeInMillis = millis }
        val today = Calendar.getInstance()

        return when {
            diff < 60_000 -> "Just now"
            diff < 3_600_000 -> "${diff / 60_000}m ago"
            cal.get(Calendar.DAY_OF_YEAR) == today.get(Calendar.DAY_OF_YEAR) &&
                cal.get(Calendar.YEAR) == today.get(Calendar.YEAR) -> {
                SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date(millis))
            }
            cal.get(Calendar.YEAR) == today.get(Calendar.YEAR) -> {
                SimpleDateFormat("MMM d", Locale.getDefault()).format(Date(millis))
            }
            else -> SimpleDateFormat("MMM d, yyyy", Locale.getDefault()).format(Date(millis))
        }
    }
}
