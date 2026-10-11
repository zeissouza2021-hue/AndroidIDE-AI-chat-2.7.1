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

package com.itsaky.androidide.ai.provider

import com.itsaky.androidide.ai.data.AiProvider
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import okhttp3.sse.EventSources
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Real HTTP client for AI providers (OpenAI-compatible, Anthropic, Gemini, custom).
 * Supports streaming via SSE when the provider declares STREAMING capability.
 */
class AiApiClient {

  private val client: OkHttpClient = OkHttpClient.Builder()
    .connectTimeout(30, TimeUnit.SECONDS)
    .readTimeout(120, TimeUnit.SECONDS)
    .writeTimeout(60, TimeUnit.SECONDS)
    .build()

  data class ChatMessage(
    val role: String, // system | user | assistant | tool
    val content: String,
    val toolCallId: String? = null,
    val name: String? = null,
    val toolCalls: List<ToolCall>? = null
  )

  data class ToolCall(
    val id: String,
    val name: String,
    val arguments: String
  )

  data class ChatResult(
    val content: String?,
    val toolCalls: List<ToolCall> = emptyList(),
    val finishReason: String? = null
  )

  interface StreamCallback {
    fun onToken(token: String)
    fun onToolCalls(calls: List<ToolCall>)
    fun onComplete(result: ChatResult)
    fun onError(message: String)
  }

  fun chat(
    provider: AiProvider,
    messages: List<ChatMessage>,
    tools: JSONArray? = null,
    stream: Boolean = false,
    streamCallback: StreamCallback? = null
  ): ChatResult {
    return when (provider.apiType) {
      AiProvider.ApiType.ANTHROPIC -> chatAnthropic(provider, messages, tools, stream, streamCallback)
      AiProvider.ApiType.GEMINI -> chatGemini(provider, messages, tools, stream, streamCallback)
      else -> chatOpenAiCompatible(provider, messages, tools, stream, streamCallback)
    }
  }

  fun testConnection(provider: AiProvider): Result<String> {
    return runCatching {
      val messages = listOf(
        ChatMessage("user", "Reply with exactly: OK")
      )
      val result = chat(provider, messages, stream = false)
      result.content ?: "(empty response)"
    }
  }

  // ── OpenAI-compatible ──────────────────────────────────────────────

  private fun chatOpenAiCompatible(
    provider: AiProvider,
    messages: List<ChatMessage>,
    tools: JSONArray?,
    stream: Boolean,
    streamCallback: StreamCallback?
  ): ChatResult {
    val body = JSONObject().apply {
      put("model", provider.modelName)
      put("messages", messagesToOpenAi(messages))
      if (tools != null && tools.length() > 0) put("tools", tools)
      put("stream", stream && provider.supports(AiProvider.Capability.STREAMING))
    }

    val url = provider.endpointUrl.trimEnd('/') +
      if (provider.endpointUrl.contains("/chat/completions")) "" else "/chat/completions"

    val request = Request.Builder()
      .url(url)
      .addHeader("Authorization", "Bearer ${provider.apiKey}")
      .addHeader("Content-Type", "application/json")
      .post(body.toString().toRequestBody(JSON_MEDIA))
      .build()

    if (stream && streamCallback != null && provider.supports(AiProvider.Capability.STREAMING)) {
      return streamOpenAi(request, streamCallback)
    }

    client.newCall(request).execute().use { response ->
      val text = response.body?.string().orEmpty()
      if (!response.isSuccessful) {
        throw RuntimeException("HTTP ${response.code}: $text")
      }
      return parseOpenAiResponse(text)
    }
  }

  private fun streamOpenAi(request: Request, callback: StreamCallback): ChatResult {
    val contentBuf = StringBuilder()
    val toolCalls = mutableListOf<ToolCall>()
    var finished = false
    val lock = Object()

    val factory = EventSources.createFactory(client)
    factory.newEventSource(request, object : EventSourceListener() {
      override fun onEvent(
        eventSource: EventSource,
        id: String?,
        type: String?,
        data: String
      ) {
        if (data == "[DONE]") {
          synchronized(lock) {
            finished = true
            lock.notifyAll()
          }
          return
        }
        runCatching {
          val json = JSONObject(data)
          val choices = json.optJSONArray("choices") ?: return
          if (choices.length() == 0) return
          val delta = choices.getJSONObject(0).optJSONObject("delta") ?: return
          val token = delta.optString("content", "")
          if (token.isNotEmpty()) {
            contentBuf.append(token)
            callback.onToken(token)
          }
          val tcs = delta.optJSONArray("tool_calls")
          if (tcs != null) {
            for (i in 0 until tcs.length()) {
              val tc = tcs.getJSONObject(i)
              val fn = tc.optJSONObject("function") ?: continue
              val call = ToolCall(
                id = tc.optString("id", "call_$i"),
                name = fn.optString("name"),
                arguments = fn.optString("arguments", "{}")
              )
              toolCalls.add(call)
            }
          }
        }
      }

      override fun onFailure(eventSource: EventSource, t: Throwable?, response: okhttp3.Response?) {
        callback.onError(t?.message ?: "Stream failed: ${response?.code}")
        synchronized(lock) {
          finished = true
          lock.notifyAll()
        }
      }

      override fun onClosed(eventSource: EventSource) {
        synchronized(lock) {
          finished = true
          lock.notifyAll()
        }
      }
    })

    synchronized(lock) {
      while (!finished) lock.wait(120_000)
    }

    val result = ChatResult(contentBuf.toString().ifEmpty { null }, toolCalls)
    if (toolCalls.isNotEmpty()) callback.onToolCalls(toolCalls)
    callback.onComplete(result)
    return result
  }

  private fun messagesToOpenAi(messages: List<ChatMessage>): JSONArray {
    val arr = JSONArray()
    messages.forEach { m ->
      val o = JSONObject().put("role", m.role).put("content", m.content)
      m.toolCallId?.let { o.put("tool_call_id", it) }
      m.name?.let { o.put("name", it) }
      m.toolCalls?.let { calls ->
        val tca = JSONArray()
        calls.forEach { c ->
          tca.put(
            JSONObject()
              .put("id", c.id)
              .put("type", "function")
              .put("function", JSONObject().put("name", c.name).put("arguments", c.arguments))
          )
        }
        o.put("tool_calls", tca)
      }
      arr.put(o)
    }
    return arr
  }

  private fun parseOpenAiResponse(text: String): ChatResult {
    val json = JSONObject(text)
    val choices = json.optJSONArray("choices") ?: return ChatResult(null)
    if (choices.length() == 0) return ChatResult(null)
    val msg = choices.getJSONObject(0).optJSONObject("message") ?: return ChatResult(null)
    val content = msg.optString("content", null)
    val toolCalls = mutableListOf<ToolCall>()
    val tcs = msg.optJSONArray("tool_calls")
    if (tcs != null) {
      for (i in 0 until tcs.length()) {
        val tc = tcs.getJSONObject(i)
        val fn = tc.optJSONObject("function") ?: continue
        toolCalls.add(
          ToolCall(
            id = tc.optString("id", "call_$i"),
            name = fn.optString("name"),
            arguments = fn.optString("arguments", "{}")
          )
        )
      }
    }
    val finish = choices.getJSONObject(0).optString("finish_reason", null)
    return ChatResult(content, toolCalls, finish)
  }

  // ── Anthropic ──────────────────────────────────────────────────────

  private fun chatAnthropic(
    provider: AiProvider,
    messages: List<ChatMessage>,
    tools: JSONArray?,
    stream: Boolean,
    streamCallback: StreamCallback?
  ): ChatResult {
    val system = messages.filter { it.role == "system" }.joinToString("\n") { it.content }
    val userMsgs = messages.filter { it.role != "system" }

    val body = JSONObject().apply {
      put("model", provider.modelName)
      put("max_tokens", 4096)
      if (system.isNotBlank()) put("system", system)
      put("messages", messagesToOpenAi(userMsgs)) // similar shape
      if (tools != null && tools.length() > 0) {
        // Anthropic tool format differs slightly; pass through for compatible gateways
        put("tools", tools)
      }
    }

    val url = provider.endpointUrl.trimEnd('/') +
      if (provider.endpointUrl.contains("/messages")) "" else "/v1/messages"

    val request = Request.Builder()
      .url(url)
      .addHeader("x-api-key", provider.apiKey)
      .addHeader("anthropic-version", "2023-06-01")
      .addHeader("Content-Type", "application/json")
      .post(body.toString().toRequestBody(JSON_MEDIA))
      .build()

    client.newCall(request).execute().use { response ->
      val text = response.body?.string().orEmpty()
      if (!response.isSuccessful) throw RuntimeException("HTTP ${response.code}: $text")
      val json = JSONObject(text)
      val contentArr = json.optJSONArray("content")
      val sb = StringBuilder()
      val toolCalls = mutableListOf<ToolCall>()
      if (contentArr != null) {
        for (i in 0 until contentArr.length()) {
          val block = contentArr.getJSONObject(i)
          when (block.optString("type")) {
            "text" -> sb.append(block.optString("text"))
            "tool_use" -> toolCalls.add(
              ToolCall(
                id = block.optString("id"),
                name = block.optString("name"),
                arguments = block.optJSONObject("input")?.toString() ?: "{}"
              )
            )
          }
        }
      }
      val result = ChatResult(sb.toString().ifEmpty { null }, toolCalls)
      streamCallback?.onComplete(result)
      return result
    }
  }

  // ── Gemini ─────────────────────────────────────────────────────────

  private fun chatGemini(
    provider: AiProvider,
    messages: List<ChatMessage>,
    tools: JSONArray?,
    stream: Boolean,
    streamCallback: StreamCallback?
  ): ChatResult {
    val contents = JSONArray()
    messages.filter { it.role != "system" }.forEach { m ->
      val role = if (m.role == "assistant") "model" else "user"
      contents.put(
        JSONObject()
          .put("role", role)
          .put("parts", JSONArray().put(JSONObject().put("text", m.content)))
      )
    }

    val body = JSONObject().put("contents", contents)
    val system = messages.filter { it.role == "system" }.joinToString("\n") { it.content }
    if (system.isNotBlank()) {
      body.put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", system))))
    }

    val base = provider.endpointUrl.trimEnd('/')
    val url = if (base.contains(":generateContent")) {
      "$base?key=${provider.apiKey}"
    } else {
      "$base/models/${provider.modelName}:generateContent?key=${provider.apiKey}"
    }

    val request = Request.Builder()
      .url(url)
      .addHeader("Content-Type", "application/json")
      .post(body.toString().toRequestBody(JSON_MEDIA))
      .build()

    client.newCall(request).execute().use { response ->
      val text = response.body?.string().orEmpty()
      if (!response.isSuccessful) throw RuntimeException("HTTP ${response.code}: $text")
      val json = JSONObject(text)
      val candidates = json.optJSONArray("candidates") ?: return ChatResult(null)
      if (candidates.length() == 0) return ChatResult(null)
      val parts = candidates.getJSONObject(0)
        .optJSONObject("content")
        ?.optJSONArray("parts")
      val sb = StringBuilder()
      if (parts != null) {
        for (i in 0 until parts.length()) {
          sb.append(parts.getJSONObject(i).optString("text"))
        }
      }
      val result = ChatResult(sb.toString().ifEmpty { null })
      streamCallback?.onComplete(result)
      return result
    }
  }

  companion object {
    private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()
  }
}
