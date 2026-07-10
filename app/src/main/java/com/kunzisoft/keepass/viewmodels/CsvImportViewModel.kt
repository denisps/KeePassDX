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

package com.kunzisoft.keepass.viewmodels

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.MutableLiveData
import com.kunzisoft.keepass.database.ContextualDatabase
import com.kunzisoft.keepass.database.DatabaseTaskProvider
import com.kunzisoft.keepass.database.element.Entry
import com.kunzisoft.keepass.database.element.Group
import com.kunzisoft.keepass.utils.CsvParser
import java.io.InputStream

class CsvImportViewModel(application: Application) : AndroidViewModel(application) {

    val headers = MutableLiveData<List<String>>()
    val mapping = MutableLiveData<MutableMap<Int, FieldType>>()
    val fileUri = MutableLiveData<Uri>()

    private val databaseTaskProvider = DatabaseTaskProvider(application)

    enum class FieldType {
        IGNORE, TITLE, USERNAME, PASSWORD, URL, NOTES
    }

    fun setFile(uri: Uri, inputStream: InputStream) {
        fileUri.value = uri
        val data = CsvParser.parse(inputStream)
        if (data.isNotEmpty()) {
            val headerList = data[0]
            headers.value = headerList
            
            // Auto-detection
            val newMapping = mutableMapOf<Int, FieldType>()
            headerList.forEachIndexed { index, s ->
                newMapping[index] = when (s.lowercase()) {
                    "name", "title" -> FieldType.TITLE
                    "url" -> FieldType.URL
                    "username", "login", "user" -> FieldType.USERNAME
                    "password" -> FieldType.PASSWORD
                    "notes", "note" -> FieldType.NOTES
                    else -> FieldType.IGNORE
                }
            }
            mapping.value = newMapping
        }
    }

    fun startImport(database: ContextualDatabase, parent: Group, inputStream: InputStream) {
        val data = CsvParser.parse(inputStream)
        if (data.size < 2) return
        
        val currentMapping = mapping.value ?: return
        
        val entries = mutableListOf<Entry>()
        for (i in 1 until data.size) {
            val row = data[i]
            val entry = database.createEntry() ?: continue
            row.forEachIndexed { index, value ->
                val type = currentMapping[index] ?: FieldType.IGNORE
                when (type) {
                    FieldType.TITLE -> entry.title = value
                    FieldType.USERNAME -> entry.username = value
                    FieldType.PASSWORD -> entry.password = value.toCharArray()
                    FieldType.URL -> entry.url = value
                    FieldType.NOTES -> entry.notes = value
                    FieldType.IGNORE -> {}
                }
            }
            if (entry.title.isEmpty() && entry.url.isNotEmpty()) {
                entry.title = entry.url
            }
            if (entry.title.isNotEmpty() || entry.username.isNotEmpty()) {
                entries.add(entry)
            }
        }
        
        if (entries.isNotEmpty()) {
            databaseTaskProvider.startDatabaseImportCsv(entries, parent.nodeId, true)
        }
    }
}
