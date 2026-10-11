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

package com.itsaky.androidide.ai.ui

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.itsaky.androidide.ai.agent.AgentLoop
import com.itsaky.androidide.ai.data.AiProvider
import com.itsaky.androidide.ai.data.AiProviderRepository
import com.itsaky.androidide.ai.provider.AiApiClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class ChatUiMessage(
  val id: String = java.util.UUID.randomUUID().toString(),
  val role: Role,
  val content: String,
  val isStreaming: Boolean = false
) {
  enum class Role { USER, ASSISTANT, STATUS, SYSTEM }
}

class AiChatViewModel : ViewModel() {

  private val _messages = MutableLiveData<List<ChatUiMessage>>(emptyList())
  val messages: LiveData<List<ChatUiMessage>> = _messages

  private val _status = MutableLiveData("")
  val status: LiveData<String> = _status

  private val _isBusy = MutableLiveData(false)
  val isBusy: LiveData<Boolean> = _isBusy

  private val _providers = MutableLiveData<List<AiProvider>>(emptyList())
  val providers: LiveData<List<AiProvider>> = _providers

  private val _selectedProvider = MutableLiveData<AiProvider?>(null)
  val selectedProvider: LiveData<AiProvider?> = _selectedProvider

  var agentMode: Boolean = true

  private var agentJob: Job? = null
  private var agentLoop: AgentLoop? = null
  private val history = mutableListOf<AiApiClient.ChatMessage>()

  private var repository: AiProviderRepository? = null

  fun init(repo: AiProviderRepository) {
    repository = repo
    refreshProviders()
  }

  fun refreshProviders() {
    val repo = repository ?: return
    val list = repo.getAll()
    _providers.postValue(list)
    _selectedProvider.postValue(repo.getSelected())
  }

  fun selectProvider(provider: AiProvider) {
    repository?.setSelectedId(provider.id)
    _selectedProvider.value = provider
  }

  fun send(text: String, onDeleteConfirm: suspend (String) -> Boolean = { true }) {
    val provider = _selectedProvider.value
    if (provider == null) {
      append(ChatUiMessage(role = ChatUiMessage.Role.SYSTEM, content = "Cadastre um provider em Settings → AI Providers."))
      return
    }
    if (text.isBlank()) return

    append(ChatUiMessage(role = ChatUiMessage.Role.USER, content = text.trim()))
    _isBusy.value = true
    _status.value = "Pensando…"

    val streamingId = java.util.UUID.randomUUID().toString()
    append(ChatUiMessage(id = streamingId, role = ChatUiMessage.Role.ASSISTANT, content = "", isStreaming = true))

    agentLoop = AgentLoop(
      onStatus = { s -> _status.postValue(s) },
      onAssistantToken = { token ->
        updateMessage(streamingId) { it.copy(content = it.content + token) }
      },
      onAssistantMessage = { full ->
        updateMessage(streamingId) { it.copy(content = full, isStreaming = false) }
      },
      requestDeleteConfirmation = onDeleteConfirm
    )

    agentJob = viewModelScope.launch {
      try {
        val result = withContext(Dispatchers.IO) {
          agentLoop!!.run(
            provider = provider,
            userMessage = text.trim(),
            history = history.toList(),
            agentMode = agentMode
          )
        }
        updateMessage(streamingId) { it.copy(content = result, isStreaming = false) }
        history.add(AiApiClient.ChatMessage("user", text.trim()))
        history.add(AiApiClient.ChatMessage("assistant", result))
        // keep history bounded
        while (history.size > 40) {
          history.removeAt(0)
        }
      } catch (e: Exception) {
        updateMessage(streamingId) {
          it.copy(content = "Erro: ${e.message}", isStreaming = false)
        }
      } finally {
        _isBusy.postValue(false)
        _status.postValue("")
      }
    }
  }

  fun cancel() {
    agentLoop?.cancel()
    agentJob?.cancel()
    _isBusy.value = false
    _status.value = "Cancelado"
  }

  fun clearChat() {
    cancel()
    history.clear()
    _messages.value = emptyList()
  }

  private fun append(msg: ChatUiMessage) {
    val list = _messages.value.orEmpty().toMutableList()
    list.add(msg)
    _messages.postValue(list)
  }

  private fun updateMessage(id: String, transform: (ChatUiMessage) -> ChatUiMessage) {
    val list = _messages.value.orEmpty().toMutableList()
    val idx = list.indexOfFirst { it.id == id }
    if (idx >= 0) {
      list[idx] = transform(list[idx])
      _messages.postValue(list)
    }
  }
}
