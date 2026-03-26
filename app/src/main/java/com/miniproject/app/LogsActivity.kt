package com.miniproject.app

import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.MaterialToolbar
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class LogsActivity : AppCompatActivity() {

    private lateinit var dbHelper: DatabaseHelper
    private lateinit var adapter: LogsAdapter
    
    private lateinit var recyclerView: RecyclerView
    private lateinit var textEmptyLogs: TextView
    private lateinit var textLogCount: TextView
    private lateinit var btnClearLogs: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_logs)

        val toolbar = findViewById<MaterialToolbar>(R.id.toolbarLogs)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        toolbar.setNavigationOnClickListener { finish() }

        recyclerView = findViewById(R.id.recyclerViewLogs)
        textEmptyLogs = findViewById(R.id.textEmptyLogs)
        textLogCount = findViewById(R.id.textLogCount)
        btnClearLogs = findViewById(R.id.btnClearLogs)

        dbHelper = DatabaseHelper(this)
        adapter = LogsAdapter(emptyList())

        recyclerView.layoutManager = LinearLayoutManager(this)
        recyclerView.adapter = adapter

        btnClearLogs.setOnClickListener {
            CoroutineScope(Dispatchers.IO).launch {
                dbHelper.clearLogs()
                loadLogs()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        loadLogs()
    }

    private fun loadLogs() {
        CoroutineScope(Dispatchers.IO).launch {
            val logs = dbHelper.getAllLogs()
            withContext(Dispatchers.Main) {
                adapter.updateData(logs)
                textLogCount.text = "${logs.size} events recorded"
                
                if (logs.isEmpty()) {
                    textEmptyLogs.visibility = View.VISIBLE
                    recyclerView.visibility = View.GONE
                } else {
                    textEmptyLogs.visibility = View.GONE
                    recyclerView.visibility = View.VISIBLE
                }
            }
        }
    }
}
