/*
 * Copyright 2026 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.ai.edge.gallery.customtasks.metasploitagent

import android.util.Log
import com.google.ai.edge.gallery.common.AgentAction
import com.google.ai.edge.gallery.common.AskInfoAgentAction
import com.google.ai.edge.gallery.common.SkillProgressAgentAction
import com.google.ai.edge.litertlm.Tool
import com.google.ai.edge.litertlm.ToolParam
import com.google.ai.edge.litertlm.ToolSet
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.json.JSONObject

private const val TAG = "MSFTools"

/** Max time (ms) to poll a console for output before giving up. */
private const val CONSOLE_POLL_MAX_MS = 60_000L

/** Polling interval (ms) while waiting for a console command to finish. */
private const val CONSOLE_POLL_INTERVAL_MS = 600L

/** Extra read time (ms) after busy=false to catch late output. */
private const val CONSOLE_DRAIN_DELAY_MS = 300L

/**
 * LiteRT-LM ToolSet exposing the Metasploit RPC API to the on-device LLM.
 *
 * The LLM can call these tools to:
 *  - Connect / authenticate to a running `msfrpcd` service
 *  - Run arbitrary msfconsole commands (console API)
 *  - Search, inspect, and execute modules
 *  - Inspect and interact with active sessions
 *  - List and stop jobs
 *
 * Each tool sends [SkillProgressAgentAction] events to [actionChannel] so the
 * UI can show live progress in the collapsable progress panel.
 */
class MetasploitTools : ToolSet {
  val client = MetasploitApiClient()

  private val _actionChannel = Channel<AgentAction>(Channel.UNLIMITED)
  val actionChannel: ReceiveChannel<AgentAction> = _actionChannel

  // ─────────────────────────────────────────────────────────────────────────
  // Connection
  // ─────────────────────────────────────────────────────────────────────────

  @Tool(
    description =
      "Connect and authenticate to a running Metasploit Framework RPC service (msfrpcd). " +
        "Must be called once before any other Metasploit tool. " +
        "Start msfrpcd on device with: msfrpcd -P <password> -U msf -n"
  )
  fun msfConnect(
    @ToolParam(description = "Hostname or IP address of the msfrpcd server (default: localhost)")
    host: String = "localhost",
    @ToolParam(description = "RPC server port (default: 55553)") port: Int = 55553,
    @ToolParam(description = "Metasploit username (default: msf)") username: String = "msf",
    @ToolParam(description = "Metasploit password") password: String,
    @ToolParam(
      description = "Use HTTPS/SSL. Set true only if msfrpcd was started with -S (default: false)"
    )
    ssl: Boolean = false,
  ): Map<String, String> =
    runBlocking(Dispatchers.IO) {
      sendProgress("Connecting to Metasploit at $host:$port…")
      runCatching { client.login(host, port, ssl, username, password) }
        .fold(
          onSuccess = {
            sendProgress("Connected to Metasploit", inProgress = false)
            mapOf(
              "status" to "success",
              "message" to "Authenticated to Metasploit RPC at $host:$port.",
            )
          },
          onFailure = { e ->
            sendProgress("Connection failed", inProgress = false)
            mapOf("status" to "failed", "error" to (e.message ?: "Unknown error"))
          },
        )
    }

  @Tool(description = "Returns the current connection status (host, port, whether authenticated).")
  fun msfStatus(): Map<String, String> =
    runBlocking(Dispatchers.Default) {
      if (client.isConnected) {
        mapOf(
          "connected" to "true",
          "host" to client.host,
          "port" to client.port.toString(),
          "ssl" to client.ssl.toString(),
        )
      } else {
        mapOf(
          "connected" to "false",
          "message" to "Not connected. Call msfConnect first.",
        )
      }
    }

  // ─────────────────────────────────────────────────────────────────────────
  // Console (arbitrary msfconsole commands)
  // ─────────────────────────────────────────────────────────────────────────

  @Tool(
    description =
      "Run any msfconsole command and return its text output. " +
        "Handles the full command lifecycle: creates a console, writes the command, polls until " +
        "output is ready, then destroys the console. " +
        "Examples: 'version', 'use exploit/multi/handler', 'set LHOST 192.168.1.5', " +
        "'run', 'sessions -l', 'back', 'search eternalblue'."
  )
  fun msfConsoleCommand(
    @ToolParam(
      description = "msfconsole command to run (exactly as you would type in the MSF console)"
    )
    command: String
  ): Map<String, String> =
    runBlocking(Dispatchers.IO) {
      if (!client.isConnected) {
        return@runBlocking notConnected()
      }
      val preview = command.take(50) + if (command.length > 50) "…" else ""
      sendProgress("MSF › $preview")

      runCatching {
          // 1. Create a fresh console.
          val cid = client.call("console.create").getInt("id")

          // 2. Write the command followed by newline.
          client.call("console.write", cid, "$command\n")

          // 3. Poll until console is not busy.
          val buf = StringBuilder()
          val deadline = System.currentTimeMillis() + CONSOLE_POLL_MAX_MS
          while (System.currentTimeMillis() < deadline) {
            delay(CONSOLE_POLL_INTERVAL_MS)
            val read = client.call("console.read", cid)
            val chunk = read.optString("data")
            if (chunk.isNotEmpty()) buf.append(chunk)
            if (!read.optBoolean("busy", true)) {
              // One extra read after busy=false to capture any trailing output.
              delay(CONSOLE_DRAIN_DELAY_MS)
              val tail = client.call("console.read", cid).optString("data")
              if (tail.isNotEmpty()) buf.append(tail)
              break
            }
          }

          // 4. Destroy the console.
          runCatching { client.call("console.destroy", cid) }

          buf.toString().trim()
        }
        .fold(
          onSuccess = { output ->
            sendProgress("MSF command done", inProgress = false)
            mapOf("status" to "success", "output" to output)
          },
          onFailure = { e ->
            sendProgress("MSF command error", inProgress = false)
            mapOf("status" to "failed", "error" to (e.message ?: "Unknown error"))
          },
        )
    }

  // ─────────────────────────────────────────────────────────────────────────
  // Module discovery
  // ─────────────────────────────────────────────────────────────────────────

  @Tool(
    description =
      "Search for Metasploit modules by keyword, CVE, platform, or type. " +
        "Returns a JSON array of matching modules with type, name, rank, and disclosure date. " +
        "Examples: 'eternalblue', 'cve:2021-44228', 'type:auxiliary platform:linux'."
  )
  fun msfSearchModules(
    @ToolParam(
      description =
        "Search query. Supports keywords and filters: " +
          "type:<exploit|auxiliary|post|payload|encoder|nop|evasion>, " +
          "platform:<windows|linux|osx|…>, cve:<number>, name:<string>, rank:<excellent|great|…>"
    )
    query: String
  ): Map<String, String> =
    runBlocking(Dispatchers.IO) {
      if (!client.isConnected) return@runBlocking notConnected()
      sendProgress("Searching modules: $query")
      runCatching { client.call("module.search", query) }
        .fold(
          onSuccess = { resp ->
            sendProgress("Search complete", inProgress = false)
            // The API may return results under different keys depending on version.
            val results =
              resp.optJSONArray("modules")?.toString()
                ?: resp.optJSONArray("result")?.toString()
                ?: resp.toString()
            mapOf("status" to "success", "results" to results)
          },
          onFailure = { e ->
            sendProgress("Search failed", inProgress = false)
            mapOf("status" to "failed", "error" to (e.message ?: "Unknown error"))
          },
        )
    }

  @Tool(
    description =
      "Get detailed information about a specific Metasploit module: description, " +
        "options, targets, references, authors, and rank."
  )
  fun msfModuleInfo(
    @ToolParam(
      description =
        "Module type: exploit, auxiliary, post, payload, encoder, nop, or evasion"
    )
    type: String,
    @ToolParam(description = "Module name without the type prefix (e.g. 'windows/smb/ms17_010_eternalblue')")
    name: String,
  ): Map<String, String> =
    runBlocking(Dispatchers.IO) {
      if (!client.isConnected) return@runBlocking notConnected()
      sendProgress("Loading info: $type/$name")
      runCatching { client.call("module.info", type, name) }
        .fold(
          onSuccess = { resp ->
            sendProgress("Module info loaded", inProgress = false)
            mapOf("status" to "success", "info" to resp.toString(2))
          },
          onFailure = { e ->
            sendProgress("Module info error", inProgress = false)
            mapOf("status" to "failed", "error" to (e.message ?: "Unknown error"))
          },
        )
    }

  @Tool(
    description =
      "Get the configurable options for a Metasploit module (RHOSTS, LHOST, LPORT, etc.)."
  )
  fun msfModuleOptions(
    @ToolParam(description = "Module type: exploit, auxiliary, post, payload, encoder, nop, evasion")
    type: String,
    @ToolParam(description = "Module name (e.g. 'windows/smb/ms17_010_eternalblue')")
    name: String,
  ): Map<String, String> =
    runBlocking(Dispatchers.IO) {
      if (!client.isConnected) return@runBlocking notConnected()
      sendProgress("Loading options: $type/$name")
      runCatching { client.call("module.options", type, name) }
        .fold(
          onSuccess = { resp ->
            sendProgress("Options loaded", inProgress = false)
            mapOf("status" to "success", "options" to resp.toString(2))
          },
          onFailure = { e ->
            sendProgress("Options error", inProgress = false)
            mapOf("status" to "failed", "error" to (e.message ?: "Unknown error"))
          },
        )
    }

  // ─────────────────────────────────────────────────────────────────────────
  // Module execution
  // ─────────────────────────────────────────────────────────────────────────

  @Tool(
    description =
      "Execute a Metasploit module (exploit, auxiliary, post, etc.) with the given options. " +
        "Returns the job ID and UUID for tracking. " +
        "Use msfListJobs to check running jobs and msfConsoleCommand 'sessions -l' to see results."
  )
  fun msfExecuteModule(
    @ToolParam(description = "Module type: exploit, auxiliary, post, payload")
    type: String,
    @ToolParam(description = "Module name (e.g. 'auxiliary/scanner/smb/smb_ms17_010')")
    name: String,
    @ToolParam(
      description =
        "JSON object of module options, e.g. " +
          "{\"RHOSTS\":\"192.168.1.0/24\",\"THREADS\":\"8\"}. " +
          "Use empty object {} for defaults."
    )
    optionsJson: String = "{}",
  ): Map<String, String> =
    runBlocking(Dispatchers.IO) {
      if (!client.isConnected) return@runBlocking notConnected()
      sendProgress("Executing $type/$name…")
      runCatching {
          val opts = JSONObject(optionsJson)
          client.call("module.execute", type, name, opts)
        }
        .fold(
          onSuccess = { resp ->
            sendProgress("Module launched", inProgress = false)
            mapOf(
              "status" to "success",
              "job_id" to resp.optString("job_id", ""),
              "uuid" to resp.optString("uuid", ""),
              "message" to "Module dispatched as job ${resp.optString("job_id", "?")}.",
            )
          },
          onFailure = { e ->
            sendProgress("Module execution failed", inProgress = false)
            mapOf("status" to "failed", "error" to (e.message ?: "Unknown error"))
          },
        )
    }

  // ─────────────────────────────────────────────────────────────────────────
  // Session management
  // ─────────────────────────────────────────────────────────────────────────

  @Tool(description = "List all active Metasploit sessions (shell, meterpreter, etc.).")
  fun msfListSessions(): Map<String, String> =
    runBlocking(Dispatchers.IO) {
      if (!client.isConnected) return@runBlocking notConnected()
      sendProgress("Listing sessions…")
      runCatching { client.call("session.list") }
        .fold(
          onSuccess = { resp ->
            sendProgress("Sessions loaded", inProgress = false)
            mapOf("status" to "success", "sessions" to resp.toString(2))
          },
          onFailure = { e ->
            sendProgress("Session list error", inProgress = false)
            mapOf("status" to "failed", "error" to (e.message ?: "Unknown error"))
          },
        )
    }

  @Tool(
    description =
      "Run a command inside a Metasploit session. " +
        "For meterpreter sessions use meterpreter commands (sysinfo, getuid, shell, download, upload, …). " +
        "For shell sessions use native shell commands (id, whoami, ls, cat, …)."
  )
  fun msfSessionCommand(
    @ToolParam(description = "Session ID integer (from msfListSessions)") sessionId: Int,
    @ToolParam(
      description = "Command to run in the session (e.g. 'sysinfo', 'getuid', 'ls /tmp')"
    )
    command: String,
    @ToolParam(description = "Session type: meterpreter (default) or shell")
    sessionType: String = "meterpreter",
  ): Map<String, String> =
    runBlocking(Dispatchers.IO) {
      if (!client.isConnected) return@runBlocking notConnected()
      sendProgress("Session $sessionId › $command")
      runCatching {
          if (sessionType == "shell") {
            client.call("session.shell_write", sessionId, "$command\n")
            delay(1_200L)
            client.call("session.shell_read", sessionId).optString("data", "")
          } else {
            // Meterpreter: send command, wait briefly, then read output.
            client.call("session.meterpreter_run_single", sessionId, command)
            delay(1_500L)
            client.call("session.meterpreter_read", sessionId).optString("data", "")
          }
        }
        .fold(
          onSuccess = { output ->
            sendProgress("Session command done", inProgress = false)
            mapOf("status" to "success", "output" to output)
          },
          onFailure = { e ->
            sendProgress("Session command error", inProgress = false)
            mapOf("status" to "failed", "error" to (e.message ?: "Unknown error"))
          },
        )
    }

  @Tool(description = "Stop (kill) an active Metasploit session.")
  fun msfStopSession(
    @ToolParam(description = "Session ID to stop") sessionId: Int
  ): Map<String, String> =
    runBlocking(Dispatchers.IO) {
      if (!client.isConnected) return@runBlocking notConnected()
      sendProgress("Stopping session $sessionId…")
      runCatching { client.call("session.stop", sessionId) }
        .fold(
          onSuccess = { resp ->
            sendProgress("Session stopped", inProgress = false)
            mapOf("status" to "success", "result" to resp.optString("result", ""))
          },
          onFailure = { e ->
            sendProgress("Session stop error", inProgress = false)
            mapOf("status" to "failed", "error" to (e.message ?: "Unknown error"))
          },
        )
    }

  // ─────────────────────────────────────────────────────────────────────────
  // Job management
  // ─────────────────────────────────────────────────────────────────────────

  @Tool(description = "List all currently running Metasploit jobs (background handlers, scanners, etc.).")
  fun msfListJobs(): Map<String, String> =
    runBlocking(Dispatchers.IO) {
      if (!client.isConnected) return@runBlocking notConnected()
      sendProgress("Listing jobs…")
      runCatching { client.call("job.list") }
        .fold(
          onSuccess = { resp ->
            sendProgress("Jobs loaded", inProgress = false)
            mapOf("status" to "success", "jobs" to resp.toString(2))
          },
          onFailure = { e ->
            sendProgress("Job list error", inProgress = false)
            mapOf("status" to "failed", "error" to (e.message ?: "Unknown error"))
          },
        )
    }

  @Tool(description = "Stop (kill) a running Metasploit job by its ID.")
  fun msfKillJob(
    @ToolParam(description = "Job ID to stop (integer from msfListJobs)") jobId: Int
  ): Map<String, String> =
    runBlocking(Dispatchers.IO) {
      if (!client.isConnected) return@runBlocking notConnected()
      sendProgress("Stopping job $jobId…")
      runCatching { client.call("job.stop", jobId) }
        .fold(
          onSuccess = { resp ->
            sendProgress("Job stopped", inProgress = false)
            mapOf("status" to "success", "result" to resp.optString("result", ""))
          },
          onFailure = { e ->
            sendProgress("Job stop error", inProgress = false)
            mapOf("status" to "failed", "error" to (e.message ?: "Unknown error"))
          },
        )
    }

  // ─────────────────────────────────────────────────────────────────────────
  // Helpers
  // ─────────────────────────────────────────────────────────────────────────

  /** Send a [SkillProgressAgentAction] to the UI action channel. */
  fun sendProgress(label: String, inProgress: Boolean = true) {
    runBlocking(Dispatchers.Default) {
      _actionChannel.send(SkillProgressAgentAction(label = label, inProgress = inProgress))
    }
  }

  private fun notConnected(): Map<String, String> =
    mapOf("status" to "failed", "error" to "Not connected. Call msfConnect first.")
}
