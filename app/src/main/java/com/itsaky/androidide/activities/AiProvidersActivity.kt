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

package com.itsaky.androidide.activities

import android.os.Bundle
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.lifecycle.lifecycleScope
import com.itsaky.androidide.ai.data.AiProvider
import com.itsaky.androidide.ai.data.AiProviderRepository
import com.itsaky.androidide.ai.provider.AiApiClient
import com.itsaky.androidide.resources.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Settings screen: manage AI providers (add / edit / remove / test connection).
 * List starts empty — no hardcoded providers.
 */
class AiProvidersActivity : AppCompatActivity() {

  private lateinit var repo: AiProviderRepository
  private lateinit var listView: ListView
  private lateinit var emptyView: TextView
  private var providers: List<AiProvider> = emptyList()

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)

    repo = AiProviderRepository.getInstance(this)

    val root = LinearLayout(this).apply {
      orientation = LinearLayout.VERTICAL
    }

    val toolbar = Toolbar(this).apply {
      title = getString(R.string.title_ai_providers)
      setNavigationOnClickListener { finish() }
    }
    setSupportActionBar(toolbar)
    supportActionBar?.setDisplayHomeAsUpEnabled(true)
    root.addView(
      toolbar,
      LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT
      )
    )

    emptyView = TextView(this).apply {
      text = getString(R.string.ai_provider_empty)
      textAlignment = View.TEXT_ALIGNMENT_CENTER
      setPadding(48, 48, 48, 48)
      visibility = View.GONE
    }

    listView = ListView(this)
    root.addView(
      listView,
      LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        0,
        1f
      )
    )
    root.addView(emptyView)

    val addBtn = Button(this).apply {
      text = getString(R.string.ai_provider_add)
      setOnClickListener { showEditor(null) }
    }
    root.addView(
      addBtn,
      LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT
      ).apply {
        setMargins(32, 16, 32, 32)
      }
    )

    setContentView(root)
    reload()
  }

  override fun onOptionsItemSelected(item: MenuItem): Boolean {
    if (item.itemId == android.R.id.home) {
      finish()
      return true
    }
    return super.onOptionsItemSelected(item)
  }

  private fun reload() {
    providers = repo.getAll()
    if (providers.isEmpty()) {
      listView.visibility = View.GONE
      emptyView.visibility = View.VISIBLE
    } else {
      listView.visibility = View.VISIBLE
      emptyView.visibility = View.GONE
      listView.adapter = ArrayAdapter(
        this,
        android.R.layout.simple_list_item_2,
        android.R.id.text1,
        providers.map { "${it.name}  ·  ${it.modelName}" }
      )
      listView.setOnItemClickListener { _, _, position, _ ->
        showEditor(providers[position])
      }
      listView.setOnItemLongClickListener { _, _, position, _ ->
        val p = providers[position]
        AlertDialog.Builder(this)
          .setTitle(R.string.ai_provider_delete)
          .setMessage(p.name)
          .setPositiveButton(android.R.string.ok) { _, _ ->
            repo.delete(p.id)
            reload()
          }
          .setNegativeButton(android.R.string.cancel, null)
          .show()
        true
      }
    }
  }

  private fun showEditor(existing: AiProvider?) {
    val density = resources.displayMetrics.density
    val pad = (16 * density).toInt()

    val form = LinearLayout(this).apply {
      orientation = LinearLayout.VERTICAL
      setPadding(pad, pad, pad, pad)
    }

    fun labeledField(hint: String, value: String, singleLine: Boolean = true): EditText {
      form.addView(TextView(this).apply { text = hint })
      val et = EditText(this).apply {
        setText(value)
        isSingleLine = singleLine
      }
      form.addView(et)
      return et
    }

    val nameEt = labeledField(getString(R.string.ai_provider_name), existing?.name.orEmpty())
    val endpointEt = labeledField(getString(R.string.ai_provider_endpoint), existing?.endpointUrl.orEmpty())
    val keyEt = labeledField(getString(R.string.ai_provider_api_key), existing?.apiKey.orEmpty())
    val modelEt = labeledField(getString(R.string.ai_provider_model), existing?.modelName.orEmpty())

    form.addView(TextView(this).apply { text = getString(R.string.ai_provider_type) })
    val typeSpinner = Spinner(this)
    val types = AiProvider.ApiType.entries.map { it.name }
    typeSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, types)
    existing?.let {
      typeSpinner.setSelection(types.indexOf(it.apiType.name).coerceAtLeast(0))
    }
    form.addView(typeSpinner)

    val caps = mutableMapOf<AiProvider.Capability, CheckBox>()
    AiProvider.Capability.entries.forEach { cap ->
      val cb = CheckBox(this).apply {
        text = cap.name
        isChecked = existing?.capabilities?.contains(cap)
          ?: (cap == AiProvider.Capability.TEXT)
      }
      caps[cap] = cb
      form.addView(cb)
    }

    val scroll = ScrollView(this).apply { addView(form) }

    val dialog = AlertDialog.Builder(this)
      .setTitle(if (existing == null) R.string.ai_provider_add else R.string.ai_provider_edit)
      .setView(scroll)
      .setPositiveButton(android.R.string.ok, null)
      .setNeutralButton(R.string.ai_provider_test, null)
      .setNegativeButton(android.R.string.cancel, null)
      .create()

    dialog.setOnShowListener {
      dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
        val name = nameEt.text.toString().trim()
        val endpoint = endpointEt.text.toString().trim()
        val key = keyEt.text.toString().trim()
        val model = modelEt.text.toString().trim()
        if (name.isEmpty() || endpoint.isEmpty() || key.isEmpty() || model.isEmpty()) {
          Toast.makeText(this, "Preencha todos os campos", Toast.LENGTH_SHORT).show()
          return@setOnClickListener
        }
        val selectedCaps = caps.filter { it.value.isChecked }.keys
        val provider = AiProvider(
          id = existing?.id ?: java.util.UUID.randomUUID().toString(),
          name = name,
          apiType = AiProvider.ApiType.valueOf(types[typeSpinner.selectedItemPosition]),
          endpointUrl = endpoint,
          apiKey = key,
          modelName = model,
          capabilities = if (selectedCaps.isEmpty()) setOf(AiProvider.Capability.TEXT) else selectedCaps
        )
        repo.save(provider)
        reload()
        dialog.dismiss()
      }

      dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
        val name = nameEt.text.toString().trim().ifEmpty { "test" }
        val endpoint = endpointEt.text.toString().trim()
        val key = keyEt.text.toString().trim()
        val model = modelEt.text.toString().trim()
        if (endpoint.isEmpty() || key.isEmpty() || model.isEmpty()) {
          Toast.makeText(this, "Preencha endpoint, key e model", Toast.LENGTH_SHORT).show()
          return@setOnClickListener
        }
        val testProvider = AiProvider(
          name = name,
          apiType = AiProvider.ApiType.valueOf(types[typeSpinner.selectedItemPosition]),
          endpointUrl = endpoint,
          apiKey = key,
          modelName = model
        )
        Toast.makeText(this, "Testando…", Toast.LENGTH_SHORT).show()
        lifecycleScope.launch {
          val result = withContext(Dispatchers.IO) {
            AiApiClient().testConnection(testProvider)
          }
          result.fold(
            onSuccess = { Toast.makeText(this@AiProvidersActivity, "OK: $it", Toast.LENGTH_LONG).show() },
            onFailure = { Toast.makeText(this@AiProvidersActivity, "Falha: ${it.message}", Toast.LENGTH_LONG).show() }
          )
        }
      }
    }

    dialog.show()
  }
}
