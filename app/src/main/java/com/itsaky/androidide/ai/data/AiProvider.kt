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

import java.util.UUID

/**
 * Represents a user-configured AI provider.
 * No hardcoded providers — the list starts empty.
 */
data class AiProvider(
  val id: String = UUID.randomUUID().toString(),
  val name: String,
  val apiType: ApiType,
  val endpointUrl: String,
  val apiKey: String,
  val modelName: String,
  val capabilities: Set<Capability> = setOf(Capability.TEXT),
  val isDefault: Boolean = false
) {
  enum class ApiType {
    OPENAI_COMPATIBLE,
    ANTHROPIC,
    GEMINI,
    CUSTOM
  }

  enum class Capability {
    TEXT,
    IMAGE,
    VIDEO,
    FILE,
    TOOL_CALLING,
    STREAMING
  }

  fun supports(capability: Capability): Boolean = capability in capabilities
}
