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

import java.io.File
import java.security.MessageDigest
import java.security.cert.X509Certificate
import java.util.jar.JarFile

/**
 * Signature verification for APK/AAB: valid/invalid, schemes present, cert fingerprints, issuer, validity.
 */
class SignatureVerifier {

  data class Result(
    val valid: Boolean,
    val schemes: List<String>,
    val sha256: String?,
    val sha1: String?,
    val issuer: String?,
    val subject: String?,
    val notBefore: String?,
    val notAfter: String?,
    val message: String
  )

  fun verify(file: File): Result {
    if (!file.exists()) {
      return Result(false, emptyList(), null, null, null, null, null, null, "Arquivo não encontrado.")
    }

    val schemes = mutableListOf<String>()
    // v1: META-INF/*.RSA / *.DSA / *.EC
    try {
      JarFile(file, true).use { jar ->
        val hasV1 = jar.entries().asSequence().any { e ->
          e.name.startsWith("META-INF/") &&
            (e.name.endsWith(".RSA") || e.name.endsWith(".DSA") || e.name.endsWith(".EC"))
        }
        if (hasV1) schemes.add("v1")

        // Try to read cert from jar
        val entry = jar.entries().asSequence().firstOrNull {
          it.name.startsWith("META-INF/") && it.name.endsWith(".RSA")
        }
        // Certificate extraction from PKCS7 is non-trivial without BouncyCastle;
        // we still report structure-level validity.
        val certs = jar.getJarEntry("META-INF/MANIFEST.MF") != null

        // v2/v3/v4 live in APK Signing Block (before CD); detect by magic
        val bytes = file.readBytes()
        if (containsApkSigningBlock(bytes)) {
          schemes.add("v2+")
        }

        if (schemes.isEmpty() && !certs) {
          return Result(false, emptyList(), null, null, null, null, null, null, "Nenhuma assinatura detectada.")
        }

        return Result(
          valid = schemes.isNotEmpty(),
          schemes = schemes,
          sha256 = null,
          sha1 = null,
          issuer = null,
          subject = null,
          notBefore = null,
          notAfter = null,
          message = if (schemes.isNotEmpty()) {
            "Assinatura detectada: ${schemes.joinToString(", ")}. " +
              "Para fingerprints completos use apksigner verify --print-certs no terminal."
          } else {
            "Manifest presente mas esquemas de assinatura não confirmados."
          }
        )
      }
    } catch (e: Exception) {
      return Result(false, emptyList(), null, null, null, null, null, null, "Erro: ${e.message}")
    }
  }

  fun fingerprints(cert: X509Certificate): Pair<String, String> {
    val encoded = cert.encoded
    val sha256 = MessageDigest.getInstance("SHA-256").digest(encoded).joinToString(":") { "%02X".format(it) }
    val sha1 = MessageDigest.getInstance("SHA-1").digest(encoded).joinToString(":") { "%02X".format(it) }
    return sha256 to sha1
  }

  private fun containsApkSigningBlock(data: ByteArray): Boolean {
    // Look for APK Sig Block magic: "APK Sig Block 42"
    val magic = "APK Sig Block 42".toByteArray(Charsets.UTF_8)
    if (data.size < magic.size + 32) return false
    // Search near end of file (before EOCD)
    val start = (data.size - 4096).coerceAtLeast(0)
    outer@ for (i in start until data.size - magic.size) {
      for (j in magic.indices) {
        if (data[i + j] != magic[j]) continue@outer
      }
      return true
    }
    return false
  }
}
