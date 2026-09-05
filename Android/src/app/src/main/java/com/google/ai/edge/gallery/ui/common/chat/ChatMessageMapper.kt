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

package com.google.ai.edge.gallery.ui.common.chat

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import androidx.compose.ui.graphics.asImageBitmap
import com.google.ai.edge.gallery.proto.AudioMessageProto
import com.google.ai.edge.gallery.proto.ChatMessageProto
import com.google.ai.edge.gallery.proto.ChatSideProto
import java.io.File
import java.io.FileOutputStream
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val TAG = "ChatMessageMapper"

/**
 * Mapper utility for bidirectional serialization and deserialization between [ChatMessage] UI
 * models and [ChatMessageProto] protobuf representations.
 */
object ChatMessageMapper {

  /** Maps the domain [ChatSide] enum to its corresponding protobuf representation. */
  fun mapChatSide(side: ChatSide): ChatSideProto {
    return when (side) {
      ChatSide.USER -> ChatSideProto.CHAT_SIDE_USER
      ChatSide.AGENT -> ChatSideProto.CHAT_SIDE_MODEL
      ChatSide.SYSTEM -> ChatSideProto.CHAT_SIDE_SYSTEM
    }
  }

  /** Maps the protobuf [ChatSideProto] enum to its corresponding domain representation. */
  fun mapChatSideProto(sideProto: ChatSideProto): ChatSide {
    return when (sideProto) {
      ChatSideProto.CHAT_SIDE_USER -> ChatSide.USER
      ChatSideProto.CHAT_SIDE_MODEL -> ChatSide.AGENT
      ChatSideProto.CHAT_SIDE_SYSTEM -> ChatSide.SYSTEM
      else -> ChatSide.SYSTEM
    }
  }

  /**
   * Deserializes a single [ChatMessageProto] into the corresponding [ChatMessage] UI model.
   *
   * @param protoMsg The saved protobuf message.
   * @param dispatcher The coroutine dispatcher for background I/O operations (defaults to
   *   [Dispatchers.IO]).
   * @return The restored domain message object, or null if unsupported.
   */
  suspend fun deserializeProtoMessage(
    protoMsg: ChatMessageProto,
    dispatcher: CoroutineDispatcher = Dispatchers.IO,
  ): ChatMessage? =
    withContext(dispatcher) {
      val side = mapChatSideProto(protoMsg.side)

      when (protoMsg.messageType) {
        "TEXT" ->
          ChatMessageText(
            content = protoMsg.content,
            side = side,
            latencyMs = protoMsg.latencyMs,
            isMarkdown = protoMsg.isMarkdown,
            accelerator = protoMsg.accelerator,
            hideSenderLabel = protoMsg.hideSenderLabel,
          )
        "THINKING" ->
          ChatMessageThinking(
            content = protoMsg.content,
            side = side,
            inProgress = protoMsg.inProgress,
            accelerator = protoMsg.accelerator,
            hideSenderLabel = protoMsg.hideSenderLabel,
          )
        "INFO" -> ChatMessageInfo(protoMsg.content)
        "WARNING" -> ChatMessageWarning(protoMsg.content)
        "ERROR" -> ChatMessageError(protoMsg.content)
        "IMAGE" -> {
          val loaded =
            protoMsg.imageFilePathsList.mapNotNull { path ->
              try {
                val bitmap = BitmapFactory.decodeFile(path)
                if (bitmap == null) {
                  Log.e(TAG, "Failed to decode bitmap from $path")
                }
                bitmap?.let { it to path }
              } catch (e: Exception) {
                Log.e(TAG, "Failed to decode bitmap from $path: ${e.message}", e)
                null
              }
            }
          if (loaded.isNotEmpty()) {
            val bitmaps = loaded.map { it.first }
            ChatMessageImage(
              bitmaps = bitmaps,
              imageBitMaps = bitmaps.map { it.asImageBitmap() },
              side = side,
              latencyMs = protoMsg.latencyMs,
              accelerator = protoMsg.accelerator,
              hideSenderLabel = protoMsg.hideSenderLabel,
              persistedPaths = loaded.map { it.second },
            )
          } else {
            Log.e(
              TAG,
              "Failed to deserialize IMAGE message: no valid bitmaps decoded from ${protoMsg.imageFilePathsList}",
            )
            null
          }
        }
        "AUDIO_CLIP" -> {
          val firstAudio = protoMsg.audioClipsList.firstOrNull()
          if (firstAudio != null) {
            try {
              ChatMessageAudioClip(
                audioData = File(firstAudio.filePath).readBytes(),
                sampleRate = firstAudio.sampleRate,
                side = side,
                latencyMs = protoMsg.latencyMs,
                persistedPath = firstAudio.filePath,
              )
            } catch (e: Exception) {
              Log.e(
                TAG,
                "Failed to deserialize audio clip from ${firstAudio.filePath}: ${e.message}",
                e,
              )
              null
            }
          } else {
            Log.e(TAG, "Failed to deserialize AUDIO_CLIP message: audioClipsList is empty")
            null
          }
        }
        else -> {
          Log.w(TAG, "Unsupported messageType: ${protoMsg.messageType}")
          null
        }
      }
    }

  /**
   * Deserializes a list of [ChatMessageProto] messages into corresponding [ChatMessage] UI models.
   *
   * @param protoMessages The list of saved protobuf messages.
   * @param dispatcher The coroutine dispatcher for background I/O operations (defaults to
   *   [Dispatchers.IO]).
   * @return The list of restored domain message objects.
   */
  suspend fun deserializeProtoMessages(
    protoMessages: List<ChatMessageProto>,
    dispatcher: CoroutineDispatcher = Dispatchers.IO,
  ): List<ChatMessage> =
    withContext(dispatcher) { protoMessages.mapNotNull { deserializeProtoMessage(it, dispatcher) } }

  /**
   * Serializes a single [ChatMessage] into a [ChatMessageProto] protobuf representation.
   *
   * @param msg The domain chat message to serialize.
   * @param sessionId Unique identifier for the active session (used for cache file naming).
   * @param context Optional Android [Context] for persisting media to the local cache dir.
   * @param dispatcher The coroutine dispatcher for background I/O operations (defaults to
   *   [Dispatchers.IO]).
   * @return The built [ChatMessageProto], or null if unsupported.
   */
  suspend fun serializeMessage(
    msg: ChatMessage,
    sessionId: String,
    context: Context? = null,
    dispatcher: CoroutineDispatcher = Dispatchers.IO,
  ): ChatMessageProto? =
    withContext(dispatcher) {
      val now = System.currentTimeMillis()
      val builder = ChatMessageProto.newBuilder()
      when (msg) {
        is ChatMessageText -> {
          builder
            .setMessageType("TEXT")
            .setContent(msg.content)
            .setSide(mapChatSide(msg.side))
            .setLatencyMs(msg.latencyMs)
            .setAccelerator(msg.accelerator)
            .setHideSenderLabel(msg.hideSenderLabel)
            .setIsMarkdown(msg.isMarkdown)
        }
        is ChatMessageThinking -> {
          builder
            .setMessageType("THINKING")
            .setContent(msg.content)
            .setSide(mapChatSide(msg.side))
            .setInProgress(msg.inProgress)
            .setAccelerator(msg.accelerator)
            .setHideSenderLabel(msg.hideSenderLabel)
        }
        is ChatMessageInfo -> {
          builder.setMessageType("INFO").setContent(msg.content).setSide(mapChatSide(msg.side))
        }
        is ChatMessageWarning -> {
          builder.setMessageType("WARNING").setContent(msg.content).setSide(mapChatSide(msg.side))
        }
        is ChatMessageError -> {
          builder.setMessageType("ERROR").setContent(msg.content).setSide(mapChatSide(msg.side))
        }
        is ChatMessageImage -> {
          builder.setMessageType("IMAGE").setSide(mapChatSide(msg.side)).setLatencyMs(msg.latencyMs)
          synchronized(msg) {
            val cachedPaths = msg.persistedPaths
            if (cachedPaths != null) {
              builder.addAllImageFilePaths(cachedPaths)
            } else if (context != null) {
              msg.persistedPaths = buildList {
                msg.bitmaps.forEachIndexed { index, bitmap ->
                  val fileName = "img_${sessionId}_${now}_$index.png"
                  val file = File(context.cacheDir, fileName)
                  try {
                    FileOutputStream(file).use { fos ->
                      bitmap.compress(Bitmap.CompressFormat.PNG, 100, fos)
                    }
                    add(file.absolutePath)
                    builder.addImageFilePaths(file.absolutePath)
                  } catch (e: Exception) {
                    Log.e(TAG, "Failed to serialize image to ${file.absolutePath}: ${e.message}", e)
                  }
                }
              }
            }
          }
        }
        is ChatMessageAudioClip -> {
          builder
            .setMessageType("AUDIO_CLIP")
            .setSide(mapChatSide(msg.side))
            .setLatencyMs(msg.latencyMs)
          synchronized(msg) {
            val cachedPath = msg.persistedPath
            if (cachedPath != null) {
              val audioProto =
                AudioMessageProto.newBuilder()
                  .setFilePath(cachedPath)
                  .setSampleRate(msg.sampleRate)
                  .build()
              builder.addAudioClips(audioProto)
            } else if (context != null) {
              val fileName = "audio_${sessionId}_$now.pcm"
              val file = File(context.cacheDir, fileName)
              try {
                FileOutputStream(file).use { fos -> fos.write(msg.audioData) }
                msg.persistedPath = file.absolutePath
                val audioProto =
                  AudioMessageProto.newBuilder()
                    .setFilePath(file.absolutePath)
                    .setSampleRate(msg.sampleRate)
                    .build()
                builder.addAudioClips(audioProto)
              } catch (e: Exception) {
                Log.e(
                  TAG,
                  "Failed to serialize audio clip to ${file.absolutePath}: ${e.message}",
                  e,
                )
              }
            }
          }
        }
        else -> return@withContext null
      }
      builder.build()
    }

  /**
   * Serializes a list of [ChatMessage] objects into corresponding [ChatMessageProto]
   * representations.
   *
   * @param messages List of messages to serialize.
   * @param sessionId Unique identifier for the active session.
   * @param context Optional Android [Context] for media file caching.
   * @param dispatcher The coroutine dispatcher for background I/O operations (defaults to
   *   [Dispatchers.IO]).
   * @return List of protobuf messages.
   */
  suspend fun serializeMessages(
    messages: List<ChatMessage>,
    sessionId: String,
    context: Context? = null,
    dispatcher: CoroutineDispatcher = Dispatchers.IO,
  ): List<ChatMessageProto> =
    withContext(dispatcher) {
      messages.mapNotNull { serializeMessage(it, sessionId, context, dispatcher) }
    }
}
