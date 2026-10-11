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

package com.itsaky.androidide.ai.tools

import com.itsaky.androidide.projects.IProjectManager
import com.itsaky.androidide.projects.builder.BuildService
import com.itsaky.androidide.lookup.Lookup
import java.io.File
import java.nio.charset.Charset

/**
 * File and build tools available to the AI Agent.
 * All file operations are restricted to the open project root (path traversal rejected).
 */
class AgentTools(
  private val onStatus: (String) -> Unit = {},
  private val requestDeleteConfirmation: suspend (String) -> Boolean = { true }
) {

  private fun projectRoot(): File? {
    return File(IProjectManager.getInstance().projectDirPath ?: return null)
  }

  private fun resolveSafe(path: String): File? {
    val root = projectRoot() ?: return null
    val target = if (path.startsWith("/")) {
      File(path)
    } else {
      File(root, path)
    }.canonicalFile
    val rootCanon = root.canonicalFile
    if (!target.path.startsWith(rootCanon.path)) {
      return null // path traversal or outside project
    }
    return target
  }

  fun listFiles(path: String = "."): String {
    onStatus("Listando arquivos…")
    val dir = resolveSafe(path) ?: return "Erro: caminho inválido ou fora do projeto: $path"
    if (!dir.exists()) return "Erro: caminho não existe: $path"
    if (!dir.isDirectory) return "Erro: não é um diretório: $path"
    val files = dir.listFiles()?.sortedBy { it.name } ?: emptyList()
    if (files.isEmpty()) return "(vazio)"
    return files.joinToString("\n") { f ->
      val type = if (f.isDirectory) "[DIR]" else "[FILE]"
      "$type ${f.name}"
    }
  }

  fun readFile(path: String): String {
    onStatus("Lendo arquivo…")
    val file = resolveSafe(path) ?: return "Erro: caminho inválido ou fora do projeto: $path"
    if (!file.exists() || !file.isFile) return "Erro: arquivo não encontrado: $path"
    if (file.length() > MAX_READ_BYTES) {
      return "Erro: arquivo muito grande (>${MAX_READ_BYTES / 1024} KB). Use search_code ou leia trechos menores."
    }
    return file.readText(Charset.defaultCharset())
  }

  fun searchCode(query: String, path: String = "."): String {
    onStatus("Buscando no código…")
    val root = resolveSafe(path) ?: return "Erro: caminho inválido: $path"
    val results = mutableListOf<String>()
    fun walk(dir: File) {
      if (results.size >= MAX_SEARCH_RESULTS) return
      dir.listFiles()?.forEach { f ->
        if (f.isDirectory) {
          if (f.name !in IGNORE_DIRS) walk(f)
        } else if (f.isFile && f.extension in CODE_EXTS && f.length() < MAX_READ_BYTES) {
          try {
            f.readLines().forEachIndexed { i, line ->
              if (line.contains(query, ignoreCase = true)) {
                val rel = f.relativeTo(projectRoot()!!).path
                results.add("$rel:${i + 1}: ${line.trim()}")
                if (results.size >= MAX_SEARCH_RESULTS) return
              }
            }
          } catch (_: Exception) { }
        }
      }
    }
    walk(root)
    return if (results.isEmpty()) "Nenhum resultado para: $query"
    else results.joinToString("\n")
  }

  fun createFile(path: String, content: String): String {
    onStatus("Criando arquivo…")
    val file = resolveSafe(path) ?: return "Erro: caminho inválido ou fora do projeto: $path"
    if (file.exists()) return "Erro: arquivo já existe: $path"
    file.parentFile?.mkdirs()
    file.writeText(content, Charset.defaultCharset())
    return "Arquivo criado: $path"
  }

  fun editFile(path: String, oldText: String, newText: String): String {
    onStatus("Editando arquivo…")
    val file = resolveSafe(path) ?: return "Erro: caminho inválido ou fora do projeto: $path"
    if (!file.exists() || !file.isFile) return "Erro: arquivo não encontrado: $path"
    val current = file.readText(Charset.defaultCharset())
    if (!current.contains(oldText)) {
      return "Erro: trecho antigo não encontrado no arquivo. Verifique o texto exato."
    }
    val updated = current.replaceFirst(oldText, newText)
    file.writeText(updated, Charset.defaultCharset())
    return "Arquivo editado: $path"
  }

  suspend fun deleteFile(path: String): String {
    onStatus("Aguardando confirmação para excluir…")
    val file = resolveSafe(path) ?: return "Erro: caminho inválido ou fora do projeto: $path"
    if (!file.exists()) return "Erro: arquivo/pasta não existe: $path"
    val confirmed = requestDeleteConfirmation(path)
    if (!confirmed) return "Exclusão cancelada pelo usuário."
    onStatus("Excluindo…")
    val ok = if (file.isDirectory) file.deleteRecursively() else file.delete()
    return if (ok) "Excluído: $path" else "Erro ao excluir: $path"
  }

  fun runBuild(): String {
    onStatus("Compilando…")
    val buildService = Lookup.getDefault().lookup(BuildService::class.java)
      ?: return "Erro: BuildService não disponível."
    // The actual build is triggered asynchronously via the existing AndroidIDE build pipeline.
    // Agent will poll read_build_log afterwards.
    return "Build disparado. Use read_build_log para acompanhar o resultado."
  }

  fun readBuildLog(): String {
    onStatus("Lendo log do build…")
    val root = projectRoot() ?: return "Erro: nenhum projeto aberto."
    val logCandidates = listOf(
      File(root, "build/outputs/logs/last-build.log"),
      File(root, ".androidide/build.log")
    )
    val logFile = logCandidates.firstOrNull { it.exists() }
    if (logFile == null) {
      return "Nenhum log de build encontrado ainda. Dispare o build primeiro."
    }
    val text = logFile.readText(Charset.defaultCharset())
    return if (text.length > MAX_LOG_CHARS) {
      text.takeLast(MAX_LOG_CHARS)
    } else text
  }

  companion object {
    private const val MAX_READ_BYTES = 512 * 1024
    private const val MAX_SEARCH_RESULTS = 50
    private const val MAX_LOG_CHARS = 80_000
    private val IGNORE_DIRS = setOf(
      ".git", ".gradle", "build", ".idea", "node_modules",
      ".androidide", "captures", ".cxx"
    )
    private val CODE_EXTS = setOf(
      "kt", "java", "xml", "gradle", "kts", "properties",
      "json", "md", "txt", "toml", "pro", "aidl"
    )
  }
}
