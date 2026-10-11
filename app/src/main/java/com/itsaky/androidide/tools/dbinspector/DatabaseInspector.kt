/*
 *  This file is part of AndroidIDE.
 *
 *  AndroidIDE is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation, either version 3 of the License, or
 *  (at your option) any later version.
 *
 *  AndroidIDE is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *   along with AndroidIDE.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.itsaky.androidide.tools.dbinspector

import android.database.sqlite.SQLiteDatabase
import com.itsaky.androidide.tools.devicefiles.DeviceFileExplorer
import java.io.File

/**
 * On-device Database Inspector: extract .db via run-as, list tables, paginated rows, SQL editor.
 * Only works for debuggable apps.
 */
class DatabaseInspector(
  private val fileExplorer: DeviceFileExplorer = DeviceFileExplorer()
) {

  data class OpenResult(
    val ok: Boolean,
    val dbFile: File? = null,
    val error: String? = null,
    val notDebuggable: Boolean = false
  )

  data class QueryResult(
    val columns: List<String>,
    val rows: List<List<String?>>,
    val error: String? = null
  )

  fun openDatabase(packageName: String, dbName: String, cacheDir: File): OpenResult {
    val list = fileExplorer.list(packageName, "databases")
    if (list.notDebuggable) {
      return OpenResult(ok = false, notDebuggable = true, error = list.error)
    }
    if (!list.ok) {
      return OpenResult(ok = false, error = list.error ?: "Falha ao listar databases.")
    }
    val local = File(cacheDir, "db_inspector/$packageName/$dbName")
    local.parentFile?.mkdirs()
    val ok = fileExplorer.pull(packageName, "databases/$dbName", local)
    if (!ok || !local.exists()) {
      return OpenResult(ok = false, error = "Não foi possível extrair $dbName")
    }
    return OpenResult(ok = true, dbFile = local)
  }

  fun listTables(dbFile: File): List<String> {
    return SQLiteDatabase.openDatabase(dbFile.absolutePath, null, SQLiteDatabase.OPEN_READONLY).use { db ->
      val tables = mutableListOf<String>()
      db.rawQuery("SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%' ORDER BY name", null).use { c ->
        while (c.moveToNext()) tables.add(c.getString(0))
      }
      tables
    }
  }

  fun query(
    dbFile: File,
    sql: String,
    page: Int = 0,
    pageSize: Int = 50,
    allowWrite: Boolean = false
  ): QueryResult {
    val trimmed = sql.trim()
    val isSelect = trimmed.startsWith("SELECT", ignoreCase = true) ||
      trimmed.startsWith("PRAGMA", ignoreCase = true) ||
      trimmed.startsWith("EXPLAIN", ignoreCase = true)

    if (!isSelect && !allowWrite) {
      return QueryResult(emptyList(), emptyList(), "INSERT/UPDATE/DELETE exigem confirmação (allowWrite=true).")
    }

    val flags = if (isSelect) SQLiteDatabase.OPEN_READONLY else SQLiteDatabase.OPEN_READWRITE
    return try {
      SQLiteDatabase.openDatabase(dbFile.absolutePath, null, flags).use { db ->
        if (isSelect) {
          val limited = if (trimmed.contains("LIMIT", ignoreCase = true)) {
            trimmed
          } else {
            "$trimmed LIMIT $pageSize OFFSET ${page * pageSize}"
          }
          db.rawQuery(limited, null).use { c ->
            val cols = (0 until c.columnCount).map { c.getColumnName(it) }
            val rows = mutableListOf<List<String?>>()
            while (c.moveToNext()) {
              rows.add((0 until c.columnCount).map { idx ->
                if (c.isNull(idx)) null else c.getString(idx)
              })
            }
            QueryResult(cols, rows)
          }
        } else {
          db.execSQL(trimmed)
          QueryResult(listOf("result"), listOf(listOf("OK")))
        }
      }
    } catch (e: Exception) {
      QueryResult(emptyList(), emptyList(), e.message)
    }
  }
}
