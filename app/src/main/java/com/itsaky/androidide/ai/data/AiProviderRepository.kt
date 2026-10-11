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

package com.itsaky.androidide.ai.data

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import org.json.JSONArray
import org.json.JSONObject

/**
 * Secure storage for AI providers using EncryptedSharedPreferences + Android Keystore.
 * API keys are never stored in plain text.
 */
class AiProviderRepository(context: Context) {

  private val prefs: SharedPreferences

  init {
    val masterKey = MasterKey.Builder(context)
      .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
      .build()

    prefs = EncryptedSharedPreferences.create(
      context,
      PREFS_NAME,
      masterKey,
      EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
      EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )
  }

  fun getAll(): List<AiProvider> {
    val json = prefs.getString(KEY_PROVIDERS, "[]") ?: "[]"
    return parseList(json)
  }

  fun getById(id: String): AiProvider? = getAll().find { it.id == id }

  fun getSelectedId(): String? = prefs.getString(KEY_SELECTED_ID, null)

  fun setSelectedId(id: String?) {
    prefs.edit().putString(KEY_SELECTED_ID, id).apply()
  }

  fun getSelected(): AiProvider? {
    val id = getSelectedId() ?: return getAll().firstOrNull()
    return getById(id) ?: getAll().firstOrNull()
  }

  fun save(provider: AiProvider) {
    val list = getAll().toMutableList()
    val index = list.indexOfFirst { it.id == provider.id }
    if (index >= 0) {
      list[index] = provider
    } else {
      list.add(provider)
    }
    writeList(list)
    if (getSelectedId() == null) {
      setSelectedId(provider.id)
    }
  }

  fun delete(id: String) {
    val list = getAll().filter { it.id != id }
    writeList(list)
    if (getSelectedId() == id) {
      setSelectedId(list.firstOrNull()?.id)
    }
  }

  private fun writeList(list: List<AiProvider>) {
    val arr = JSONArray()
    list.forEach { arr.put(toJson(it)) }
    prefs.edit().putString(KEY_PROVIDERS, arr.toString()).apply()
  }

  private fun toJson(p: AiProvider): JSONObject {
    return JSONObject().apply {
      put("id", p.id)
      put("name", p.name)
      put("apiType", p.apiType.name)
      put("endpointUrl", p.endpointUrl)
      put("apiKey", p.apiKey)
      put("modelName", p.modelName)
      put("capabilities", JSONArray(p.capabilities.map { it.name }))
      put("isDefault", p.isDefault)
    }
  }

  private fun parseList(json: String): List<AiProvider> {
    val arr = JSONArray(json)
    val result = mutableListOf<AiProvider>()
    for (i in 0 until arr.length()) {
      val o = arr.getJSONObject(i)
      val caps = mutableSetOf<AiProvider.Capability>()
      val capsArr = o.optJSONArray("capabilities")
      if (capsArr != null) {
        for (j in 0 until capsArr.length()) {
          runCatching {
            caps.add(AiProvider.Capability.valueOf(capsArr.getString(j)))
          }
        }
      }
      if (caps.isEmpty()) caps.add(AiProvider.Capability.TEXT)
      result.add(
        AiProvider(
          id = o.getString("id"),
          name = o.getString("name"),
          apiType = runCatching {
            AiProvider.ApiType.valueOf(o.getString("apiType"))
          }.getOrDefault(AiProvider.ApiType.OPENAI_COMPATIBLE),
          endpointUrl = o.getString("endpointUrl"),
          apiKey = o.getString("apiKey"),
          modelName = o.getString("modelName"),
          capabilities = caps,
          isDefault = o.optBoolean("isDefault", false)
        )
      )
    }
    return result
  }

  companion object {
    private const val PREFS_NAME = "ai_providers_secure"
    private const val KEY_PROVIDERS = "providers"
    private const val KEY_SELECTED_ID = "selected_id"

    @Volatile
    private var instance: AiProviderRepository? = null

    fun getInstance(context: Context): AiProviderRepository {
      return instance ?: synchronized(this) {
        instance ?: AiProviderRepository(context.applicationContext).also { instance = it }
      }
    }
  }
}
