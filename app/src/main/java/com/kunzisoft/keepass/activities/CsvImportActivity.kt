/*
 * Copyright 2024 Jeremy Jamet / Kunzisoft.
 *
 * This file is part of KeePassDX.
 *
 *  KeePassDX is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation, either version 3 of the License, or
 *  (at your option) any later version.
 *
 *  KeePassDX is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *  along with KeePassDX.  If not, see <http://www.gnu.org/licenses/>.
 */

package com.kunzisoft.keepass.activities

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.Spinner
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.widget.Toolbar
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.kunzisoft.keepass.R
import com.kunzisoft.keepass.activities.legacy.DatabaseLockActivity
import com.kunzisoft.keepass.database.ContextualDatabase
import com.kunzisoft.keepass.database.element.Group
import com.kunzisoft.keepass.database.element.node.NodeId
import com.kunzisoft.keepass.viewmodels.CsvImportViewModel
import com.kunzisoft.keepass.utils.getParcelableExtraCompat
import java.util.UUID

class CsvImportActivity : DatabaseLockActivity() {

    private val viewModel: CsvImportViewModel by viewModels()

    private lateinit var coordinatorLayout: androidx.coordinatorlayout.widget.CoordinatorLayout
    private lateinit var buttonPickFile: Button
    private lateinit var textFilePath: TextView
    private lateinit var recyclerFieldMapping: RecyclerView
    private lateinit var buttonConfirmImport: Button

    private var parentGroup: Group? = null

    override fun viewToInvalidateTimeout(): View? = coordinatorLayout

    private val pickFileLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        uri?.let {
            contentResolver.openInputStream(it)?.use { inputStream ->
                viewModel.setFile(it, inputStream)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_csv_import)

        coordinatorLayout = findViewById(R.id.coordinator_layout)
        val toolbar = findViewById<Toolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        buttonPickFile = findViewById(R.id.button_pick_file)
        textFilePath = findViewById(R.id.text_file_path)
        recyclerFieldMapping = findViewById(R.id.recycler_field_mapping)
        buttonConfirmImport = findViewById(R.id.button_confirm_import)

        buttonPickFile.setOnClickListener {
            pickFileLauncher.launch("text/*")
        }

        viewModel.fileUri.observe(this) {
            textFilePath.text = it.toString()
            buttonConfirmImport.isEnabled = true
        }

        recyclerFieldMapping.layoutManager = LinearLayoutManager(this)
        viewModel.headers.observe(this) { headers ->
            recyclerFieldMapping.adapter = MappingAdapter(headers)
        }

        buttonConfirmImport.setOnClickListener {
            val uri = viewModel.fileUri.value ?: return@setOnClickListener
            val database = mDatabase ?: return@setOnClickListener
            val parent = parentGroup ?: return@setOnClickListener
            contentResolver.openInputStream(uri)?.use { inputStream ->
                viewModel.startImport(database, parent, inputStream)
            }
            finish()
        }
    }

    override fun onDatabaseRetrieved(database: ContextualDatabase) {
        super.onDatabaseRetrieved(database)
        val parentId: NodeId<*>? = intent.getParcelableExtraCompat(EXTRA_PARENT_ID)
        parentId?.let {
            parentGroup = database.getGroupById(it)
        }
    }

    private inner class MappingAdapter(private val headers: List<String>) :
        RecyclerView.Adapter<MappingAdapter.ViewHolder>() {

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_csv_field_mapping, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            holder.bind(headers[position], position)
        }

        override fun getItemCount() = headers.size

        inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            private val textHeader: TextView = view.findViewById(R.id.text_csv_header)
            private val spinnerFieldType: Spinner = view.findViewById(R.id.spinner_field_type)

            fun bind(header: String, position: Int) {
                textHeader.text = header
                val adapter = ArrayAdapter(
                    itemView.context,
                    android.R.layout.simple_spinner_item,
                    CsvImportViewModel.FieldType.values().map { it.name }
                )
                adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
                spinnerFieldType.adapter = adapter
                
                val currentMapping = viewModel.mapping.value ?: mutableMapOf()
                spinnerFieldType.setSelection(currentMapping[position]?.ordinal ?: 0)

                spinnerFieldType.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                    override fun onItemSelected(parent: AdapterView<*>?, view: View?, pos: Int, id: Long) {
                        val newMapping = viewModel.mapping.value ?: mutableMapOf()
                        newMapping[position] = CsvImportViewModel.FieldType.values()[pos]
                        viewModel.mapping.value = newMapping
                    }

                    override fun onNothingSelected(parent: AdapterView<*>?) {}
                }
            }
        }
    }

    companion object {
        private const val EXTRA_PARENT_ID = "EXTRA_PARENT_ID"

        fun launch(activity: Activity, parentId: NodeId<*>) {
            val intent = Intent(activity, CsvImportActivity::class.java)
            intent.putExtra(EXTRA_PARENT_ID, parentId)
            activity.startActivity(intent)
        }
    }
}
