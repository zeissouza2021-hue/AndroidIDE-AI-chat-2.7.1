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

package com.itsaky.androidide.activities.tools

import android.app.Activity
import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.lifecycle.lifecycleScope
import com.itsaky.androidide.resources.R
import com.itsaky.androidide.tools.apkanalyzer.ApkAnalyzer
import com.itsaky.androidide.tools.assetstudio.AssetStudio
import com.itsaky.androidide.tools.dbinspector.DatabaseInspector
import com.itsaky.androidide.tools.deps.DependencyUpdateChecker
import com.itsaky.androidide.tools.devicefiles.DeviceFileExplorer
import com.itsaky.androidide.tools.lint.LintRunner
import com.itsaky.androidide.tools.manifestmerger.ManifestMergerViewer
import com.itsaky.androidide.tools.signing.SignatureVerifier
import com.itsaky.androidide.tools.signing.SigningConfigStore
import com.itsaky.androidide.tools.themebuilder.MaterialThemeBuilder
import com.itsaky.androidide.tools.translations.TranslationsEditor
import com.itsaky.androidide.projects.IProjectManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Hub screen for all additional IDE tools (Part 5 + 6).
 * Each tool runs real local logic — no mock UIs.
 */
class IdeToolsActivity : AppCompatActivity() {

  private lateinit var output: TextView

  private val pickApk = registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
    if (uri == null) return@registerForActivityResult
    val tmp = File(cacheDir, "picked.apk")
    contentResolver.openInputStream(uri)?.use { input ->
      tmp.outputStream().use { input.copyTo(it) }
    }
    runApkAnalyzer(tmp)
  }

  private val pickApkCompareA = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
    pendingCompareA = copyUri(uri) ?: return@registerForActivityResult
    pickApkCompareB.launch("application/*")
  }
  private val pickApkCompareB = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
    val b = copyUri(uri) ?: return@registerForActivityResult
    val a = pendingCompareA ?: return@registerForActivityResult
    lifecycleScope.launch {
      val diff = withContext(Dispatchers.IO) { ApkAnalyzer().compare(a, b) }
      output.text = diff.take(200).joinToString("\n") {
        val sign = if (it.delta > 0) "+" else ""
        "${it.path}: ${it.sizeA} → ${it.sizeB} ($sign${it.delta})"
      }
    }
  }
  private var pendingCompareA: File? = null

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)

    val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    val toolbar = Toolbar(this).apply { title = "IDE Tools" }
    setSupportActionBar(toolbar)
    supportActionBar?.setDisplayHomeAsUpEnabled(true)
    toolbar.setNavigationOnClickListener { finish() }
    root.addView(toolbar)

    val scroll = ScrollView(this)
    val content = LinearLayout(this).apply {
      orientation = LinearLayout.VERTICAL
      setPadding(32, 16, 32, 32)
    }

    fun tool(label: String, onClick: () -> Unit) {
      content.addView(Button(this).apply {
        text = label
        setOnClickListener { onClick() }
      })
    }

    tool(getString(R.string.title_apk_analyzer)) { pickApk.launch("application/*") }
    tool("Comparar dois APKs") { pickApkCompareA.launch("application/*") }
    tool(getString(R.string.title_asset_studio)) { openAssetStudio() }
    tool(getString(R.string.title_database_inspector)) { openDbInspector() }
    tool(getString(R.string.title_signing_config)) { openSigningConfig() }
    tool(getString(R.string.title_material_theme_builder)) { openThemeBuilder() }
    tool(getString(R.string.title_translations_editor)) { openTranslations() }
    tool(getString(R.string.action_analyze_code)) { runLint() }
    tool(getString(R.string.title_manifest_merger)) { runManifestMerger() }
    tool(getString(R.string.title_device_file_explorer)) { openDeviceFiles() }
    tool("Verificar assinatura (APK/AAB)") { pickForSignature() }
    tool(getString(R.string.action_check_dependency_updates)) { checkDeps() }

    output = TextView(this).apply {
      typeface = Typeface.MONOSPACE
      textSize = 12f
      setPadding(0, 24, 0, 0)
      setTextIsSelectable(true)
    }
    content.addView(output)
    scroll.addView(content)
    root.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
    setContentView(root)
  }

  private fun runApkAnalyzer(file: File) {
    lifecycleScope.launch {
      val report = withContext(Dispatchers.IO) { ApkAnalyzer().analyze(file) }
      val sb = StringBuilder()
      sb.appendLine("Arquivo: ${report.file.name} (${report.totalSize} bytes)")
      sb.appendLine("DEX: ${report.dex.dexCount} arquivos, ${report.dex.totalUncompressed} bytes")
      report.manifest?.let { m ->
        sb.appendLine("Package: ${m.packageName}")
        sb.appendLine("Version: ${m.versionName}")
        sb.appendLine("Permissions (${m.permissions.size}): ${m.permissions.take(20).joinToString()}")
        sb.appendLine("Activities: ${m.activities.take(15).joinToString()}")
        sb.appendLine("Services: ${m.services.take(15).joinToString()}")
      }
      sb.appendLine("Resources: ${report.resourceTypes}")
      sb.appendLine("--- Entradas (top 80) ---")
      report.entries.take(80).forEach {
        sb.appendLine("${it.path}  ${it.size}")
      }
      output.text = sb.toString()
    }
  }

  private fun openAssetStudio() {
    val input = EditText(this).apply { hint = "Texto do ícone (1-2 letras)" }
    AlertDialog.Builder(this)
      .setTitle(R.string.title_asset_studio)
      .setView(input)
      .setPositiveButton("Gerar") { _, _ ->
        val text = input.text.toString().ifBlank { "AI" }
        val result = AssetStudio().generateFromText(text)
        output.text = result.message + "\n" + result.filesWritten.joinToString("\n") { it.absolutePath }
        Toast.makeText(this, result.message, Toast.LENGTH_LONG).show()
      }
      .setNegativeButton(android.R.string.cancel, null)
      .show()
  }

  private fun openDbInspector() {
    val appId = try {
      // best-effort from project
      IProjectManager.getInstance().rootProject?.subProjects
        ?.firstOrNull()?.name ?: ""
    } catch (_: Exception) { "" }

    val pkgInput = EditText(this).apply {
      setText(appId)
      hint = "package name"
    }
    val dbInput = EditText(this).apply { hint = "nome do .db (ex: app.db)" }
    val box = LinearLayout(this).apply {
      orientation = LinearLayout.VERTICAL
      setPadding(32, 16, 32, 16)
      addView(pkgInput)
      addView(dbInput)
    }
    AlertDialog.Builder(this)
      .setTitle(R.string.title_database_inspector)
      .setView(box)
      .setPositiveButton("Abrir") { _, _ ->
        lifecycleScope.launch {
          val result = withContext(Dispatchers.IO) {
            DatabaseInspector().openDatabase(
              pkgInput.text.toString().trim(),
              dbInput.text.toString().trim().ifBlank { "app.db" },
              cacheDir
            )
          }
          if (result.notDebuggable) {
            output.text = result.error
            return@launch
          }
          if (!result.ok || result.dbFile == null) {
            output.text = result.error ?: "Falha"
            return@launch
          }
          val tables = withContext(Dispatchers.IO) {
            DatabaseInspector().listTables(result.dbFile)
          }
          output.text = "Tabelas:\n" + tables.joinToString("\n")
          if (tables.isNotEmpty()) {
            val q = withContext(Dispatchers.IO) {
              DatabaseInspector().query(result.dbFile, "SELECT * FROM ${tables.first()}")
            }
            output.append("\n\n${tables.first()}:\n")
            output.append(q.columns.joinToString(" | ") + "\n")
            q.rows.forEach { output.append(it.joinToString(" | ") + "\n") }
            q.error?.let { output.append("\nErro: $it") }
          }
        }
      }
      .setNegativeButton(android.R.string.cancel, null)
      .show()
  }

  private fun openSigningConfig() {
    val store = SigningConfigStore.getInstance(this)
    val debug = store.ensureDebugKeystore()
    val all = store.getAll()
    output.text = "Configurações de assinatura:\n" +
      all.joinToString("\n") { "• ${it.name}: ${it.keystorePath} (alias=${it.keyAlias})" } +
      "\n\nSelecionada: ${store.getSelected()?.name}" +
      "\n\nAviso: com Play App Signing, a chave do build é a upload key."
  }

  private fun openThemeBuilder() {
    val input = EditText(this).apply {
      hint = "#RRGGBB"
      setText("#6750A4")
    }
    AlertDialog.Builder(this)
      .setTitle(R.string.title_material_theme_builder)
      .setView(input)
      .setPositiveButton("Gerar e exportar") { _, _ ->
        val hex = input.text.toString().removePrefix("#")
        val color = try {
          android.graphics.Color.parseColor("#$hex")
        } catch (_: Exception) {
          Toast.makeText(this, "Cor inválida", Toast.LENGTH_SHORT).show()
          return@setPositiveButton
        }
        val builder = MaterialThemeBuilder()
        val palette = builder.generate(color)
        val msg = builder.exportToProject(palette)
        output.text = msg + "\n\nLight:\n" +
          palette.light.entries.joinToString("\n") { "${it.key}=#${"%08X".format(it.value)}" } +
          "\n\nDark:\n" +
          palette.dark.entries.joinToString("\n") { "${it.key}=#${"%08X".format(it.value)}" }
      }
      .setNegativeButton(android.R.string.cancel, null)
      .show()
  }

  private fun openTranslations() {
    val table = TranslationsEditor().load()
    val sb = StringBuilder()
    sb.appendLine("Idiomas: ${table.languages}")
    sb.appendLine("Chaves: ${table.keys.size}")
    if (table.missing.isNotEmpty()) {
      sb.appendLine("\n⚠ Faltando:")
      table.missing.forEach { (lang, keys) ->
        sb.appendLine("  $lang: ${keys.joinToString()}")
      }
    }
    sb.appendLine("\n--- Tabela ---")
    for (key in table.keys.take(100)) {
      val row = table.languages.joinToString(" | ") { lang ->
        table.values[key]?.get(lang)?.take(20) ?: "—"
      }
      sb.appendLine("$key :: $row")
    }
    output.text = sb.toString()
  }

  private fun runLint() {
    output.text = "Executando Lint…"
    lifecycleScope.launch {
      val report = withContext(Dispatchers.IO) { LintRunner().run() }
      output.text = "Fonte: ${report.source}\n\n" +
        report.issues.joinToString("\n") { i ->
          "[${i.severity}] ${i.id}: ${i.message}" +
            (if (i.file != null) "\n  ${i.file}:${i.line ?: "?"}" else "")
        }.ifEmpty { "Nenhum problema encontrado." }
    }
  }

  private fun runManifestMerger() {
    lifecycleScope.launch {
      val report = withContext(Dispatchers.IO) { ManifestMergerViewer().analyze() }
      val sb = StringBuilder()
      sb.appendLine("Elementos com origem:")
      report.nodes.forEach {
        val badge = if (it.origin == "project") "" else " [lib]"
        sb.appendLine("${it.tag} ${it.name ?: ""} ← ${it.origin}$badge")
      }
      sb.appendLine("\n--- Preview ---\n${report.mergedXmlPreview.take(4000)}")
      output.text = sb.toString()
    }
  }

  private fun openDeviceFiles() {
    val input = EditText(this).apply { hint = "package name" }
    AlertDialog.Builder(this)
      .setTitle(R.string.title_device_file_explorer)
      .setView(input)
      .setPositiveButton("Listar") { _, _ ->
        lifecycleScope.launch {
          val pkg = input.text.toString().trim()
          val result = withContext(Dispatchers.IO) {
            DeviceFileExplorer().list(pkg)
          }
          if (result.notDebuggable) {
            output.text = result.error
          } else if (!result.ok) {
            output.text = result.error ?: "Erro"
          } else {
            output.text = result.entries.joinToString("\n") {
              val t = if (it.isDir) "[DIR]" else "[FILE]"
              "$t ${it.name} ${if (it.size >= 0) it.size else ""}"
            }
          }
        }
      }
      .setNegativeButton(android.R.string.cancel, null)
      .show()
  }

  private val pickSignature = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
    val f = copyUri(uri) ?: return@registerForActivityResult
    val result = SignatureVerifier().verify(f)
    output.text = buildString {
      appendLine(if (result.valid) "VÁLIDO" else "INVÁLIDO")
      appendLine("Esquemas: ${result.schemes}")
      appendLine(result.message)
      result.sha256?.let { appendLine("SHA-256: $it") }
      result.sha1?.let { appendLine("SHA-1: $it") }
      result.issuer?.let { appendLine("Issuer: $it") }
      result.subject?.let { appendLine("Subject: $it") }
    }
  }

  private fun pickForSignature() {
    pickSignature.launch("application/*")
  }

  private fun checkDeps() {
    output.text = "Consultando Maven Central / Google Maven…"
    lifecycleScope.launch {
      val checker = DependencyUpdateChecker()
      val updates = withContext(Dispatchers.IO) { checker.checkUpdates() }
      if (updates.isEmpty()) {
        output.text = "Nenhuma atualização encontrada (ou sem rede / deps)."
        return@launch
      }
      output.text = updates.joinToString("\n") {
        "${it.dependency.group}:${it.dependency.name}: ${it.dependency.currentVersion} → ${it.latestVersion}"
      }
      AlertDialog.Builder(this@IdeToolsActivity)
        .setTitle("Atualizar dependências?")
        .setMessage("${updates.size} atualizações disponíveis. Aplicar a primeira?")
        .setPositiveButton("Atualizar 1ª") { _, _ ->
          val ok = checker.applyUpdate(updates.first())
          Toast.makeText(
            this@IdeToolsActivity,
            if (ok) "Atualizado: ${updates.first().dependency.name}" else "Falha ao editar arquivo",
            Toast.LENGTH_LONG
          ).show()
        }
        .setNegativeButton(android.R.string.cancel, null)
        .show()
    }
  }

  private fun copyUri(uri: Uri?): File? {
    if (uri == null) return null
    val tmp = File(cacheDir, "picked_${System.currentTimeMillis()}")
    contentResolver.openInputStream(uri)?.use { input ->
      tmp.outputStream().use { input.copyTo(it) }
    } ?: return null
    return tmp
  }
}
