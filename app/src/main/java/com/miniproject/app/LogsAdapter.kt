package com.miniproject.app

import android.graphics.Color
import android.text.format.DateFormat
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import java.util.Date

class LogsAdapter(private var logsList: List<LogEntry>) : RecyclerView.Adapter<LogsAdapter.LogViewHolder>() {

    class LogViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val textLogIcon: TextView = itemView.findViewById(R.id.textLogIcon)
        val textLogClass: TextView = itemView.findViewById(R.id.textLogClass)
        val textLogTime: TextView = itemView.findViewById(R.id.textLogTime)
        val textLogDb: TextView = itemView.findViewById(R.id.textLogDb)
        val textLogConfidence: TextView = itemView.findViewById(R.id.textLogConfidence)
        val cardLogRoot: com.google.android.material.card.MaterialCardView = itemView.findViewById(R.id.cardLogRoot)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): LogViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_log, parent, false)
        return LogViewHolder(view)
    }

    override fun onBindViewHolder(holder: LogViewHolder, position: Int) {
        val log = logsList[position]
        
        holder.textLogClass.text = log.soundClass.replaceFirstChar { it.uppercase() }
        holder.textLogDb.text = "${String.format("%.1f", log.dbLevel)} dB"
        holder.textLogConfidence.text = "${String.format("%.0f", log.confidence * 100)}% match"
        
        val dateString = DateFormat.format("MMM dd - hh:mm:ss a", Date(log.timestamp)).toString()
        holder.textLogTime.text = dateString

        // Emergency styling
        if (log.isEmergency) {
            holder.textLogIcon.text = "🚨"
            holder.cardLogRoot.setCardBackgroundColor(Color.parseColor("#FFCDD2"))
            holder.textLogClass.setTextColor(Color.parseColor("#B71C1C"))
        } else {
            holder.textLogIcon.text = "🔊"
            holder.cardLogRoot.setCardBackgroundColor(Color.WHITE)
            holder.textLogClass.setTextColor(Color.parseColor("#333333"))
        }
    }

    override fun getItemCount(): Int = logsList.size

    fun updateData(newLogs: List<LogEntry>) {
        logsList = newLogs
        notifyDataSetChanged()
    }
}
