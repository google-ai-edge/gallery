/*
 * Copyright 2025 Google LLC
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

package com.google.ai.edge.gallery

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import androidx.datastore.core.CorruptionException
import androidx.datastore.core.Serializer
import com.google.ai.edge.gallery.proto.UserData
import com.google.protobuf.InvalidProtocolBufferException
import java.io.InputStream
import java.io.OutputStream
import java.nio.charset.StandardCharsets
import java.security.GeneralSecurityException
import java.security.KeyStore
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.IvParameterSpec

private const val DEFAULT_GCM_KEY_NAME = "__oss_secret_gallery_gcm__"
private const val DEFAULT_LEGACY_CBC_KEY_NAME = ""

private const val V2_PREFIX = "v2:"
private const val ANDROID_KEYSTORE = "AndroidKeyStore"
private const val ALGORITHM = KeyProperties.KEY_ALGORITHM_AES

private const val GCM_BLOCK_MODE = KeyProperties.BLOCK_MODE_GCM
private const val GCM_PADDING = KeyProperties.ENCRYPTION_PADDING_NONE
private const val GCM_TRANSFORMATION = "$ALGORITHM/$GCM_BLOCK_MODE/$GCM_PADDING"
private const val GCM_TAG_LENGTH_BITS = 128
private const val MIN_GCM_IV_LENGTH_BYTES = 12
private const val MAX_GCM_IV_LENGTH_BYTES = 16

private const val CBC_BLOCK_MODE = KeyProperties.BLOCK_MODE_CBC
private const val CBC_PADDING = KeyProperties.ENCRYPTION_PADDING_PKCS7
private const val CBC_TRANSFORMATION = "$ALGORITHM/$CBC_BLOCK_MODE/$CBC_PADDING"
private const val CBC_IV_LENGTH_BYTES = 16

internal fun interface UserDataKeyProvider {
  fun getKey(
    alias: String,
    blockMode: String,
    padding: String,
    createIfMissing: Boolean,
  ): SecretKey?
}

private object AndroidKeyStoreKeyProvider : UserDataKeyProvider {
  @Synchronized
  override fun getKey(
    alias: String,
    blockMode: String,
    padding: String,
    createIfMissing: Boolean,
  ): SecretKey? {
    val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
    val existingEntry = keyStore.getEntry(alias, null) as? KeyStore.SecretKeyEntry
    if (existingEntry != null) {
      return existingEntry.secretKey
    }
    if (!createIfMissing) {
      return null
    }
    return KeyGenerator.getInstance(ALGORITHM, ANDROID_KEYSTORE)
      .apply {
        init(
          KeyGenParameterSpec.Builder(
              alias,
              KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
            .setBlockModes(blockMode)
            .setEncryptionPaddings(padding)
            .build()
        )
      }
      .generateKey()
  }
}

internal class EncryptedUserDataSerializer(
  private val gcmKeyName: String = DEFAULT_GCM_KEY_NAME,
  private val legacyCbcKeyName: String = DEFAULT_LEGACY_CBC_KEY_NAME,
  private val keyProvider: UserDataKeyProvider = AndroidKeyStoreKeyProvider,
) : Serializer<UserData> {

  override val defaultValue: UserData = UserData.getDefaultInstance()

  override suspend fun readFrom(input: InputStream): UserData {
    val rawBytes = input.readBytes()
    if (rawBytes.isEmpty()) {
      return defaultValue
    }

    val rawText = String(rawBytes, StandardCharsets.UTF_8)
    if (rawText.startsWith(V2_PREFIX)) {
      return try {
        val decoded = Base64.getDecoder().decode(rawText.removePrefix(V2_PREFIX))
        val decrypted = decryptGcm(decoded)
        UserData.parseFrom(decrypted)
      } catch (e: Exception) {
        when (e) {
          is IllegalArgumentException,
          is GeneralSecurityException,
          is InvalidProtocolBufferException,
          is IndexOutOfBoundsException ->
            throw CorruptionException("Cannot read encrypted user data.", e)
          else -> throw e
        }
      }
    }

    if (legacyCbcKeyName.isNotEmpty()) {
      return try {
        val decoded = Base64.getDecoder().decode(rawText)
        val decrypted = decryptLegacyCbc(decoded)
        UserData.parseFrom(decrypted)
      } catch (e: Exception) {
        when (e) {
          is IllegalArgumentException,
          is GeneralSecurityException,
          is InvalidProtocolBufferException,
          is IndexOutOfBoundsException ->
            throw CorruptionException("Cannot read legacy encrypted user data.", e)
          else -> throw e
        }
      }
    }

    // Legacy OSS builds persisted unencrypted UserData protos; migrate seamlessly on next write.
    return try {
      UserData.parseFrom(rawBytes)
    } catch (e: InvalidProtocolBufferException) {
      throw CorruptionException("Cannot read proto.", e)
    }
  }

  override suspend fun writeTo(t: UserData, output: OutputStream) {
    val encrypted = encryptGcm(t.toByteArray())
    val encoded = V2_PREFIX + Base64.getEncoder().encodeToString(encrypted)
    output.write(encoded.toByteArray(StandardCharsets.UTF_8))
  }

  private fun encryptGcm(plaintext: ByteArray): ByteArray {
    val key =
      keyProvider.getKey(
        alias = gcmKeyName,
        blockMode = GCM_BLOCK_MODE,
        padding = GCM_PADDING,
        createIfMissing = true,
      ) ?: throw GeneralSecurityException("Unable to initialize encryption key.")
    val cipher = Cipher.getInstance(GCM_TRANSFORMATION)
    cipher.init(Cipher.ENCRYPT_MODE, key)
    val iv = cipher.iv
    if (iv == null || iv.size !in MIN_GCM_IV_LENGTH_BYTES..MAX_GCM_IV_LENGTH_BYTES) {
      throw GeneralSecurityException("Unexpected GCM IV length: ${iv?.size}")
    }
    val ciphertext = cipher.doFinal(plaintext)
    return byteArrayOf(iv.size.toByte()) + iv + ciphertext
  }

  private fun decryptGcm(payload: ByteArray): ByteArray {
    if (payload.isEmpty()) {
      throw GeneralSecurityException("Empty GCM payload.")
    }
    val ivLength = payload[0].toInt() and 0xFF
    if (ivLength !in MIN_GCM_IV_LENGTH_BYTES..MAX_GCM_IV_LENGTH_BYTES) {
      throw GeneralSecurityException("Invalid GCM IV length: $ivLength")
    }
    val headerAndIvLength = 1 + ivLength
    if (payload.size < headerAndIvLength + GCM_TAG_LENGTH_BITS / 8) {
      throw GeneralSecurityException("Truncated GCM payload.")
    }
    val key =
      keyProvider.getKey(
        alias = gcmKeyName,
        blockMode = GCM_BLOCK_MODE,
        padding = GCM_PADDING,
        createIfMissing = false,
      ) ?: throw GeneralSecurityException("GCM key not found.")
    val iv = payload.copyOfRange(1, headerAndIvLength)
    val ciphertext = payload.copyOfRange(headerAndIvLength, payload.size)
    val cipher = Cipher.getInstance(GCM_TRANSFORMATION)
    cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv))
    return cipher.doFinal(ciphertext)
  }

  private fun decryptLegacyCbc(payload: ByteArray): ByteArray {
    if (payload.size <= CBC_IV_LENGTH_BYTES) {
      throw GeneralSecurityException("Truncated legacy CBC payload.")
    }
    val key =
      keyProvider.getKey(
        alias = legacyCbcKeyName,
        blockMode = CBC_BLOCK_MODE,
        padding = CBC_PADDING,
        createIfMissing = false,
      ) ?: throw GeneralSecurityException("Legacy CBC key not found.")
    val iv = payload.copyOfRange(0, CBC_IV_LENGTH_BYTES)
    val ciphertext = payload.copyOfRange(CBC_IV_LENGTH_BYTES, payload.size)
    val cipher = Cipher.getInstance(CBC_TRANSFORMATION)
    cipher.init(Cipher.DECRYPT_MODE, key, IvParameterSpec(iv))
    return cipher.doFinal(ciphertext)
  }
}

/**
 * [Serializer] for persisting [UserData] protos encrypted at rest using an `AndroidKeyStore`
 * AES-GCM key, with seamless read migration from legacy formats.
 */
object UserDataSerializer : Serializer<UserData> by EncryptedUserDataSerializer()
