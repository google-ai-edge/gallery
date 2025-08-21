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
import java.security.KeyStore
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.IvParameterSpec

object UserDataSerializer : Serializer<UserData> {
  private const val ALGORITHM = KeyProperties.KEY_ALGORITHM_AES
  private const val BLOCK_MODE = KeyProperties.BLOCK_MODE_CBC
  private const val PADDING = KeyProperties.ENCRYPTION_PADDING_PKCS7
  private const val TRANSFORMATION = "$ALGORITHM/$BLOCK_MODE/$PADDING"

  private val cipher = Cipher.getInstance(TRANSFORMATION)
  private val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

  override val defaultValue: UserData = UserData.getDefaultInstance()

  override suspend fun readFrom(input: InputStream): UserData {
    try {
      val bytes = input.readBytes().decodeToString()
      val decrypted = decrypt(decodeString(bytes))
      return UserData.parseFrom(decrypted)
    } catch (exception: InvalidProtocolBufferException) {
      throw CorruptionException("Cannot read proto.", exception)
    }
  }

  override suspend fun writeTo(t: UserData, output: OutputStream) {
    val bytes = t.toByteArray()
    val str = encodeByteArray(encrypt(bytes))
    output.write(str.toByteArray())
  }

  /** Retrieves or generates a secret key. */
  private fun getKey(): SecretKey {
    val existingKey = keyStore.getEntry("secret", null) as? KeyStore.SecretKeyEntry
    return existingKey?.secretKey ?: createKey()
  }

  /** Generates a new key if not already present. */
  private fun createKey(): SecretKey {
    return KeyGenerator.getInstance(ALGORITHM)
      .apply {
        init(
          KeyGenParameterSpec.Builder(
              "secret",
              KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
            .setBlockModes(BLOCK_MODE)
            .setEncryptionPaddings(PADDING)
            .build()
        )
      }
      .generateKey()
  }

  /** Initializes the cipher in encrypt mode and encrypts data. */
  private fun encrypt(bytes: ByteArray): ByteArray {
    cipher.init(Cipher.ENCRYPT_MODE, getKey())
    val iv = cipher.iv
    val encrypted = cipher.doFinal(bytes)
    return iv + encrypted
  }

  /** Extracts IV and decrypts the data. */
  private fun decrypt(bytes: ByteArray): ByteArray {
    val iv = bytes.copyOfRange(0, cipher.blockSize)
    val data = bytes.copyOfRange(cipher.blockSize, bytes.size)
    cipher.init(Cipher.DECRYPT_MODE, getKey(), IvParameterSpec(iv))
    return cipher.doFinal(data)
  }

  private fun decodeString(str: String): ByteArray = Base64.getDecoder().decode(str)

  private fun encodeByteArray(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)
}
