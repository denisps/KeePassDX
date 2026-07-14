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
import com.kunzisoft.keepass.utils.CsvReader

class CsvImportViewModel(application: Application) : AndroidViewModel(application) {

    val headers = MutableLiveData<List<String>>()
    val mapping = MutableLiveData<MutableMap<Int, FieldType>>()
    val fileUri = MutableLiveData<Uri>()

    private val databaseTaskProvider = DatabaseTaskProvider(application)

    /** Holds the open [CsvReader] between [setFile] and [startImport]; closed in [onCleared]. */
    private var csvReader: CsvReader? = null

    enum class FieldType {
        IGNORE, TITLE, USERNAME, PASSWORD, URL, NOTES
    }

    /**
     * Opens [uri] via a [CsvReader], reads the first (header) record to populate [headers]
     * and auto-detect [mapping], then retains the reader for [startImport].
     * The header field names are non-sensitive, so they are converted to [String] for display.
     */
    fun setFile(uri: Uri) {
        fileUri.value = uri
        csvReader?.close()
        val reader = CsvReader(getApplication<Application>().contentResolver, uri)
        csvReader = reader
        if (!reader.hasNext()) return
        // The first record is the header row.
        val headerRecord = reader.next()
        val headerList = headerRecord.map { String(it) }
        headers.value = headerList
        // Auto-detect field mapping from header names.
        val newMapping = mutableMapOf<Int, FieldType>()
        headerList.forEachIndexed { index, s ->
            newMapping[index] = when (s.trim().lowercase()) {
                "name", "title" -> FieldType.TITLE
                "url"           -> FieldType.URL
                "username", "login", "user" -> FieldType.USERNAME
                "password"      -> FieldType.PASSWORD
                "notes", "note" -> FieldType.NOTES
                else            -> FieldType.IGNORE
            }
        }
        mapping.value = newMapping
        // headerRecord CharArrays will be zeroed on the first reader.next() call in startImport.
    }

    /**
     * Iterates the remaining records of the [CsvReader] opened by [setFile], creates [Entry]
     * objects, then launches the database import task.
     *
     * Password fields are copied as [CharArray] (never converted to [String]) so that secret
     * data does not linger on the heap as an immutable [String] object.
     * Each record's [CharArray]s are zeroed by the reader as the iterator advances.
     * The reader is closed and nulled out after all records have been consumed.
     */
    fun startImport(database: ContextualDatabase, parent: Group) {
        val reader = csvReader ?: return
        val currentMapping = mapping.value ?: return

        val entries = mutableListOf<Entry>()
        while (reader.hasNext()) {
            val record = reader.next()
            val entry = database.createEntry() ?: continue
            record.forEachIndexed { index, field ->
                when (currentMapping[index] ?: FieldType.IGNORE) {
                    FieldType.TITLE    -> entry.title    = String(field)
                    FieldType.USERNAME -> entry.username = String(field)
                    // Copy the CharArray so the entry owns its password independently of the
                    // reader's scratch memory, which will be zeroed on the next iteration.
                    FieldType.PASSWORD -> entry.password = field.copyOf()
                    FieldType.URL      -> entry.url      = String(field)
                    FieldType.NOTES    -> entry.notes    = String(field)
                    FieldType.IGNORE   -> {}
                }
            }
            if (entry.title.isEmpty() && entry.url.isNotEmpty()) {
                entry.title = entry.url
            }
            if (entry.title.isNotEmpty() || entry.username.isNotEmpty()) {
                entries.add(entry)
            }
        }

        // All records consumed — zero and release the reader.
        reader.close()
        csvReader = null

        if (entries.isNotEmpty()) {
            databaseTaskProvider.startDatabaseImportCsv(entries, parent.nodeId, true)
        }
    }

    override fun onCleared() {
        super.onCleared()
        csvReader?.close()
        csvReader = null
    }
}
