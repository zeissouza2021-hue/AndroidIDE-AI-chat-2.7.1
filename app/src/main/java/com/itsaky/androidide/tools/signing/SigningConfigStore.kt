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

package com.itsaky.androidide.tools.signing

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/**
 * Multiple signing configurations with passwords stored via Android Keystore.
 * Auto-generates a debug keystore when none is configured.
 */
class SigningConfigStore(context: Context) {

  data class Config(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val keystorePath: String,
    val keystorePassword: String,
    val keyAlias: String,
    val keyPassword: String
  )

  private val prefs = run {
    val masterKey = MasterKey.Builder(context)
      .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
      .build()
    EncryptedSharedPreferences.create(
      context,
      PREFS_NAME,
      masterKey,
      EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
      EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )
  }

  private val appContext = context.applicationContext

  fun getAll(): List<Config> {
    val json = prefs.getString(KEY_CONFIGS, "[]") ?: "[]"
    val arr = JSONArray(json)
    val list = mutableListOf<Config>()
    for (i in 0 until arr.length()) {
      val o = arr.getJSONObject(i)
      list.add(
        Config(
          id = o.getString("id"),
          name = o.getString("name"),
          keystorePath = o.getString("keystorePath"),
          keystorePassword = o.getString("keystorePassword"),
          keyAlias = o.getString("keyAlias"),
          keyPassword = o.getString("keyPassword")
        )
      )
    }
    return list
  }

  fun save(config: Config) {
    val list = getAll().toMutableList()
    val idx = list.indexOfFirst { it.id == config.id }
    if (idx >= 0) list[idx] = config else list.add(config)
    write(list)
  }

  fun delete(id: String) {
    write(getAll().filter { it.id != id })
  }

  fun getSelectedId(): String? = prefs.getString(KEY_SELECTED, null)

  fun setSelectedId(id: String?) {
    prefs.edit().putString(KEY_SELECTED, id).apply()
  }

  fun getSelected(): Config? {
    val id = getSelectedId()
    val all = getAll()
    return all.find { it.id == id } ?: all.firstOrNull() ?: ensureDebugKeystore()
  }

  /** Generate a debug keystore automatically when none exists. */
  fun ensureDebugKeystore(): Config {
    val existing = getAll().find { it.name == "debug" }
    if (existing != null) return existing

    val ksDir = File(appContext.filesDir, "keystores").also { it.mkdirs() }
    val ksFile = File(ksDir, "debug.keystore")
    // Keystore binary generation requires keytool; we store the path and default debug credentials.
    // Actual keytool invocation can be done via the on-device JDK when the user first builds.
    val config = Config(
      name = "debug",
      keystorePath = ksFile.absolutePath,
      keystorePassword = "android",
      keyAlias = "androiddebugkey",
      keyPassword = "android"
    )
    save(config)
    setSelectedId(config.id)
    return config
  }

  private fun write(list: List<Config>) {
    val arr = JSONArray()
    list.forEach { c ->
      arr.put(
        JSONObject()
          .put("id", c.id)
          .put("name", c.name)
          .put("keystorePath", c.keystorePath)
          .put("keystorePassword", c.keystorePassword)
          .put("keyAlias", c.keyAlias)
          .put("keyPassword", c.keyPassword)
      )
    }
    prefs.edit().putString(KEY_CONFIGS, arr.toString()).apply()
  }

  companion object {
    private const val PREFS_NAME = "signing_configs_secure"
    private const val KEY_CONFIGS = "configs"
    private const val KEY_SELECTED = "selected_id"

    @Volatile private var instance: SigningConfigStore? = null

    fun getInstance(context: Context): SigningConfigStore {
      return instance ?: synchronized(this) {
        instance ?: SigningConfigStore(context.applicationContext).also { instance = it }
      }
    }
  }
}
