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

package com.itsaky.androidide.ai.agent

import com.itsaky.androidide.ai.data.AiProvider
import com.itsaky.androidide.ai.provider.AiApiClient
import com.itsaky.androidide.ai.provider.AiApiClient.ChatMessage
import com.itsaky.androidide.ai.provider.AiApiClient.ToolCall
import com.itsaky.androidide.ai.tools.AgentTools
import com.itsaky.androidide.projects.IProjectManager
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Real agent loop: send messages + tools → execute tool calls → feed results back → repeat
 * until the model finishes or max iterations (25) is reached.
 */
class AgentLoop(
  private val client: AiApiClient = AiApiClient(),
  private val onStatus: (String) -> Unit = {},
  private val onAssistantToken: (String) -> Unit = {},
  private val onAssistantMessage: (String) -> Unit = {},
  private val requestDeleteConfirmation: suspend (String) -> Boolean = { true }
) {

  private val cancelled = AtomicBoolean(false)
  private val tools = AgentTools(
    onStatus = onStatus,
    requestDeleteConfirmation = requestDeleteConfirmation
  )

  fun cancel() {
    cancelled.set(true)
  }

  suspend fun run(
    provider: AiProvider,
    userMessage: String,
    history: List<ChatMessage> = emptyList(),
    agentMode: Boolean = true
  ): String {
    cancelled.set(false)

    val projectPath = IProjectManager.getInstance().projectDirPath ?: "(nenhum projeto aberto)"
    val systemPrompt = buildSystemPrompt(projectPath, agentMode)

    val messages = mutableListOf<ChatMessage>()
    messages.add(ChatMessage("system", systemPrompt))
    messages.addAll(history)
    messages.add(ChatMessage("user", userMessage))

    val toolDefs = if (agentMode && provider.supports(AiProvider.Capability.TOOL_CALLING)) {
      toolDefinitions()
    } else null

    var iterations = 0
    var lastContent = ""

    while (iterations < MAX_ITERATIONS) {
      if (cancelled.get()) {
        onStatus("Cancelado")
        return lastContent.ifEmpty { "Execução cancelada." }
      }

      iterations++
      onStatus("Pensando… (passo $iterations)")

      val result = try {
        client.chat(
          provider = provider,
          messages = messages,
          tools = toolDefs,
          stream = provider.supports(AiProvider.Capability.STREAMING),
          streamCallback = object : AiApiClient.StreamCallback {
            override fun onToken(token: String) {
              onAssistantToken(token)
            }
            override fun onToolCalls(calls: List<ToolCall>) {}
            override fun onComplete(result: AiApiClient.ChatResult) {}
            override fun onError(message: String) {
              onStatus("Erro: $message")
            }
          }
        )
      } catch (e: Exception) {
        val err = "Erro da API: ${e.message}"
        onStatus(err)
        return err
      }

      if (result.toolCalls.isNotEmpty() && agentMode) {
        // Append assistant message with tool calls
        messages.add(
          ChatMessage(
            role = "assistant",
            content = result.content ?: "",
            toolCalls = result.toolCalls
          )
        )

        for (call in result.toolCalls) {
          if (cancelled.get()) break
          val toolResult = executeTool(call)
          messages.add(
            ChatMessage(
              role = "tool",
              content = toolResult,
              toolCallId = call.id,
              name = call.name
            )
          )
        }
        // Continue loop so the model can use tool results
        continue
      }

      // Final text response
      lastContent = result.content.orEmpty()
      if (lastContent.isNotBlank()) {
        onAssistantMessage(lastContent)
      }
      onStatus("")
      return lastContent
    }

    onStatus("Limite de $MAX_ITERATIONS iterações atingido")
    return lastContent.ifEmpty {
      "O agente atingiu o limite de $MAX_ITERATIONS iterações sem concluir."
    }
  }

  private suspend fun executeTool(call: ToolCall): String {
    val args = runCatching { JSONObject(call.arguments) }.getOrElse { JSONObject() }
    return try {
      when (call.name) {
        "list_files" -> tools.listFiles(args.optString("path", "."))
        "read_file" -> tools.readFile(args.optString("path"))
        "search_code" -> tools.searchCode(
          args.optString("query"),
          args.optString("path", ".")
        )
        "create_file" -> tools.createFile(
          args.optString("path"),
          args.optString("content")
        )
        "edit_file" -> tools.editFile(
          args.optString("path"),
          args.optString("old_text"),
          args.optString("new_text")
        )
        "delete_file" -> tools.deleteFile(args.optString("path"))
        "run_build" -> tools.runBuild()
        "read_build_log" -> tools.readBuildLog()
        else -> "Ferramenta desconhecida: ${call.name}"
      }
    } catch (e: Exception) {
      "Erro ao executar ${call.name}: ${e.message}"
    }
  }

  private fun buildSystemPrompt(projectPath: String, agentMode: Boolean): String {
    val base = """
      Você é um agente de desenvolvimento dentro do AndroidIDE.
      O projeto aberto está em: $projectPath
      Responda sempre no idioma do usuário.
      
      Regras para UI:
      - Antes de criar telas/interfaces, leia build.gradle / libs.versions.toml e identifique a versão de androidx.compose.material3:material3.
      - Se o usuário pedir Material Expressive (ou o contexto pedir componentes expressivos): use dependência 1.5.0-alpha ou superior (ou estável se existir), MaterialExpressiveTheme, MotionScheme.expressive(), componentes expressivos, e @OptIn(ExperimentalMaterial3ExpressiveApi::class) onde necessário.
      - Se o projeto estiver no material3 estável e o usuário NÃO pedir Expressive: use M3 padrão, sem opt-ins experimentais.
      - Nunca invente nomes de componentes ou parâmetros — use apenas APIs da versão declarada no projeto.
      - O AndroidIDE NÃO tem renderizador de @Preview. Após gerar UI, compile o projeto e ofereça instalar/executar no dispositivo. PROIBIDO simular preview com imagens mockadas ou telas estáticas falsas.
    """.trimIndent()

    return if (agentMode) {
      base + "\n\nVocê está no modo Agent: pode usar ferramentas para ler, criar, editar e excluir arquivos do projeto, e disparar o build. Use as ferramentas quando necessário para cumprir a tarefa."
    } else {
      base + "\n\nVocê está no modo Chat: apenas converse e explique. Não execute ações no projeto."
    }
  }

  private fun toolDefinitions(): JSONArray {
    fun tool(name: String, description: String, params: JSONObject): JSONObject {
      return JSONObject()
        .put("type", "function")
        .put(
          "function",
          JSONObject()
            .put("name", name)
            .put("description", description)
            .put("parameters", params)
        )
    }

    fun obj(vararg props: Pair<String, JSONObject>, required: List<String> = emptyList()): JSONObject {
      val properties = JSONObject()
      props.forEach { (k, v) -> properties.put(k, v) }
      return JSONObject()
        .put("type", "object")
        .put("properties", properties)
        .put("required", JSONArray(required))
    }

    fun str(desc: String) = JSONObject().put("type", "string").put("description", desc)

    return JSONArray()
      .put(tool("list_files", "Lista arquivos e pastas em um caminho relativo ao projeto.",
        obj("path" to str("Caminho relativo (default: .)"))))
      .put(tool("read_file", "Lê o conteúdo de um arquivo do projeto.",
        obj("path" to str("Caminho relativo do arquivo"), required = listOf("path"))))
      .put(tool("search_code", "Busca texto no código do projeto.",
        obj(
          "query" to str("Texto a buscar"),
          "path" to str("Pasta relativa (default: .)"),
          required = listOf("query")
        )))
      .put(tool("create_file", "Cria um novo arquivo com o conteúdo informado.",
        obj(
          "path" to str("Caminho relativo"),
          "content" to str("Conteúdo completo do arquivo"),
          required = listOf("path", "content")
        )))
      .put(tool("edit_file", "Substitui um trecho exato (old_text) por new_text no arquivo.",
        obj(
          "path" to str("Caminho relativo"),
          "old_text" to str("Trecho exato a substituir"),
          "new_text" to str("Novo trecho"),
          required = listOf("path", "old_text", "new_text")
        )))
      .put(tool("delete_file", "Exclui um arquivo ou pasta (pede confirmação ao usuário).",
        obj("path" to str("Caminho relativo"), required = listOf("path"))))
      .put(tool("run_build", "Dispara o build do projeto aberto no AndroidIDE.",
        obj()))
      .put(tool("read_build_log", "Lê o log do último build para diagnosticar erros.",
        obj()))
  }

  companion object {
    const val MAX_ITERATIONS = 25
  }
}
