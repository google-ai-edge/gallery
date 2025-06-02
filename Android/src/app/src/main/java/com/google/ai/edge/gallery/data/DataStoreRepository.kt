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

package com.google.ai.edge.gallery.data

import android.content.Context // Added import
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.google.ai.edge.gallery.R // Added import for R class
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.google.ai.edge.gallery.ui.theme.THEME_AUTO
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import java.security.KeyStore
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

data class AccessTokenData(
  val accessToken: String,
  val refreshToken: String,
  val expiresAtMs: Long
)

interface DataStoreRepository {
  fun saveTextInputHistory(history: List<String>)
  fun readTextInputHistory(): List<String>
  fun saveThemeOverride(theme: String)
  fun readThemeOverride(): String
  fun saveAccessTokenData(accessToken: String, refreshToken: String, expiresAt: Long)
  fun clearAccessTokenData()
  fun readAccessTokenData(): AccessTokenData?
  fun saveImportedModels(importedModels: List<ImportedModelInfo>)
  fun readImportedModels(): List<ImportedModelInfo>

  fun saveUserProfile(userProfile: UserProfile)
  fun readUserProfile(): UserProfile?
  fun savePersonas(personas: List<Persona>)
  fun readPersonas(): List<Persona>
  fun addPersona(persona: Persona)
  fun updatePersona(persona: Persona)
  fun deletePersona(personaId: String)
  fun saveConversations(conversations: List<Conversation>)
  fun readConversations(): List<Conversation>
  fun getConversationById(conversationId: String): Conversation?
  fun addConversation(conversation: Conversation)
  fun updateConversation(conversation: Conversation)
  fun deleteConversation(conversationId: String)

  fun saveActivePersonaId(personaId: String?)
  fun readActivePersonaId(): Flow<String?>

  fun saveUserDocuments(documents: List<UserDocument>)
  fun readUserDocuments(): Flow<List<UserDocument>>
  fun addUserDocument(document: UserDocument)
  fun updateUserDocument(document: UserDocument)
  fun deleteUserDocument(documentId: String)
  fun getUserDocumentById(documentId: String): Flow<UserDocument?>
}

/**
 * Repository for managing data using DataStore, with JSON serialization.
 *
 * This class provides methods to read, add, remove, and clear data stored in DataStore,
 * using JSON serialization for complex objects. It uses Gson for serializing and deserializing
 * lists of objects to/from JSON strings.
 *
 * DataStore is used to persist data as JSON strings under specified keys.
 */
class DefaultDataStoreRepository(
  private val dataStore: DataStore<Preferences>,
  private val context: Context // Added context
) :
  DataStoreRepository {

  private object PreferencesKeys {
    val TEXT_INPUT_HISTORY = stringPreferencesKey("text_input_history")

    val THEME_OVERRIDE = stringPreferencesKey("theme_override")

    val ENCRYPTED_ACCESS_TOKEN = stringPreferencesKey("encrypted_access_token")

    // Store Initialization Vector
    val ACCESS_TOKEN_IV = stringPreferencesKey("access_token_iv")

    val ENCRYPTED_REFRESH_TOKEN = stringPreferencesKey("encrypted_refresh_token")

    // Store Initialization Vector
    val REFRESH_TOKEN_IV = stringPreferencesKey("refresh_token_iv")

    val ACCESS_TOKEN_EXPIRES_AT = longPreferencesKey("access_token_expires_at")

    // Data for all imported models.
    val IMPORTED_MODELS = stringPreferencesKey("imported_models")

    val ENCRYPTED_USER_PROFILE = stringPreferencesKey("encrypted_user_profile")
    val USER_PROFILE_IV = stringPreferencesKey("user_profile_iv")
    val PERSONAS_LIST = stringPreferencesKey("personas_list")
    val CONVERSATIONS_LIST = stringPreferencesKey("conversations_list")
    val ACTIVE_PERSONA_ID = stringPreferencesKey("active_persona_id")
    val USER_DOCUMENTS_LIST = stringPreferencesKey("user_documents_list")
  }

  private val keystoreAlias: String = "com_google_aiedge_gallery_access_token_key"
  private val keyStore: KeyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

  override fun saveTextInputHistory(history: List<String>) {
    runBlocking {
      dataStore.edit { preferences ->
        val gson = Gson()
        val jsonString = gson.toJson(history)
        preferences[PreferencesKeys.TEXT_INPUT_HISTORY] = jsonString
      }
    }
  }

  override fun readTextInputHistory(): List<String> {
    return runBlocking {
      val preferences = dataStore.data.first()
      getTextInputHistory(preferences)
    }
  }

  override fun saveThemeOverride(theme: String) {
    runBlocking {
      dataStore.edit { preferences ->
        preferences[PreferencesKeys.THEME_OVERRIDE] = theme
      }
    }
  }

  override fun readThemeOverride(): String {
    return runBlocking {
      val preferences = dataStore.data.first()
      preferences[PreferencesKeys.THEME_OVERRIDE] ?: THEME_AUTO
    }
  }

  override fun saveAccessTokenData(accessToken: String, refreshToken: String, expiresAt: Long) {
    runBlocking {
      val (encryptedAccessToken, accessTokenIv) = encrypt(accessToken)
      val (encryptedRefreshToken, refreshTokenIv) = encrypt(refreshToken)
      dataStore.edit { preferences ->
        preferences[PreferencesKeys.ENCRYPTED_ACCESS_TOKEN] = encryptedAccessToken
        preferences[PreferencesKeys.ACCESS_TOKEN_IV] = accessTokenIv
        preferences[PreferencesKeys.ENCRYPTED_REFRESH_TOKEN] = encryptedRefreshToken
        preferences[PreferencesKeys.REFRESH_TOKEN_IV] = refreshTokenIv
        preferences[PreferencesKeys.ACCESS_TOKEN_EXPIRES_AT] = expiresAt
      }
    }
  }

  override fun clearAccessTokenData() {
    return runBlocking {
      dataStore.edit { preferences ->
        preferences.remove(PreferencesKeys.ENCRYPTED_ACCESS_TOKEN)
        preferences.remove(PreferencesKeys.ACCESS_TOKEN_IV)
        preferences.remove(PreferencesKeys.ENCRYPTED_REFRESH_TOKEN)
        preferences.remove(PreferencesKeys.REFRESH_TOKEN_IV)
        preferences.remove(PreferencesKeys.ACCESS_TOKEN_EXPIRES_AT)
      }
    }
  }

  override fun readAccessTokenData(): AccessTokenData? {
    return runBlocking {
      val preferences = dataStore.data.first()
      val encryptedAccessToken = preferences[PreferencesKeys.ENCRYPTED_ACCESS_TOKEN]
      val encryptedRefreshToken = preferences[PreferencesKeys.ENCRYPTED_REFRESH_TOKEN]
      val accessTokenIv = preferences[PreferencesKeys.ACCESS_TOKEN_IV]
      val refreshTokenIv = preferences[PreferencesKeys.REFRESH_TOKEN_IV]
      val expiresAt = preferences[PreferencesKeys.ACCESS_TOKEN_EXPIRES_AT]

      var decryptedAccessToken: String? = null
      var decryptedRefreshToken: String? = null
      if (encryptedAccessToken != null && accessTokenIv != null) {
        decryptedAccessToken = decrypt(encryptedAccessToken, accessTokenIv)
      }
      if (encryptedRefreshToken != null && refreshTokenIv != null) {
        decryptedRefreshToken = decrypt(encryptedRefreshToken, refreshTokenIv)
      }
      if (decryptedAccessToken != null && decryptedRefreshToken != null && expiresAt != null) {
        AccessTokenData(decryptedAccessToken, decryptedRefreshToken, expiresAt)
      } else {
        null
      }
    }
  }

  override fun saveImportedModels(importedModels: List<ImportedModelInfo>) {
    runBlocking {
      dataStore.edit { preferences ->
        val gson = Gson()
        val jsonString = gson.toJson(importedModels)
        preferences[PreferencesKeys.IMPORTED_MODELS] = jsonString
      }
    }
  }

  override fun readImportedModels(): List<ImportedModelInfo> {
    return runBlocking {
      val preferences = dataStore.data.first()
      val infosStr = preferences[PreferencesKeys.IMPORTED_MODELS] ?: "[]"
      val gson = Gson()
      val listType = object : TypeToken<List<ImportedModelInfo>>() {}.type
      // Ensure to return emptyList() if fromJson returns null
      return gson.fromJson(infosStr, listType) ?: emptyList()
    }
  }

  private fun getTextInputHistory(preferences: Preferences): List<String> {
    val infosStr = preferences[PreferencesKeys.TEXT_INPUT_HISTORY] ?: "[]"
    val gson = Gson()
    val listType = object : TypeToken<List<String>>() {}.type
    // Ensure to return emptyList() if fromJson returns null
    return gson.fromJson(infosStr, listType) ?: emptyList()
  }

  private fun getOrCreateSecretKey(): SecretKey {
    return (keyStore.getKey(keystoreAlias, null) as? SecretKey) ?: run {
      val keyGenerator =
        KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
      val keyGenParameterSpec = KeyGenParameterSpec.Builder(
        keystoreAlias,
        KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
      )
        .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
        .setUserAuthenticationRequired(false) // Consider setting to true for added security
        .build()
      keyGenerator.init(keyGenParameterSpec)
      keyGenerator.generateKey()
    }
  }

  private fun encrypt(plainText: String): Pair<String, String> {
    val secretKey = getOrCreateSecretKey()
    val cipher = Cipher.getInstance("AES/GCM/NoPadding")
    cipher.init(Cipher.ENCRYPT_MODE, secretKey)
    val iv = cipher.iv
    val encryptedBytes = cipher.doFinal(plainText.toByteArray(Charsets.UTF_8))
    return Base64.encodeToString(encryptedBytes, Base64.DEFAULT) to Base64.encodeToString(
      iv,
      Base64.DEFAULT
    )
  }

  private fun decrypt(encryptedText: String, ivText: String): String? {
    val secretKey = getOrCreateSecretKey()
    val cipher = Cipher.getInstance("AES/GCM/NoPadding")
    val ivBytes = Base64.decode(ivText, Base64.DEFAULT)
    val spec = javax.crypto.spec.GCMParameterSpec(128, ivBytes) // 128 bit tag length
    cipher.init(Cipher.DECRYPT_MODE, secretKey, spec)
    val encryptedBytes = Base64.decode(encryptedText, Base64.DEFAULT)
    return try {
      String(cipher.doFinal(encryptedBytes), Charsets.UTF_8)
    } catch (e: Exception) {
      // Handle decryption errors (e.g., key not found)
      null
    }
  }

  override fun saveUserProfile(userProfile: UserProfile) {
    runBlocking {
      val gson = Gson()
      val jsonString = gson.toJson(userProfile)
      val (encryptedProfile, iv) = encrypt(jsonString)
      dataStore.edit { preferences ->
        preferences[PreferencesKeys.ENCRYPTED_USER_PROFILE] = encryptedProfile
        preferences[PreferencesKeys.USER_PROFILE_IV] = iv
      }
    }
  }

  override fun readUserProfile(): UserProfile? {
    return runBlocking {
      val preferences = dataStore.data.first()
      val encryptedProfile = preferences[PreferencesKeys.ENCRYPTED_USER_PROFILE]
      val iv = preferences[PreferencesKeys.USER_PROFILE_IV]

      if (encryptedProfile != null && iv != null) {
        try {
          val decryptedJson = decrypt(encryptedProfile, iv)
          if (decryptedJson != null) {
            Gson().fromJson(decryptedJson, UserProfile::class.java)
          } else {
            UserProfile() // Return default if decryption fails
          }
        } catch (e: Exception) {
          UserProfile() // Return default on error
        }
      } else {
        UserProfile() // Return default if not found
      }
    }
  }

  override fun savePersonas(personas: List<Persona>) {
    runBlocking {
      dataStore.edit { preferences ->
        val gson = Gson()
        val jsonString = gson.toJson(personas)
        preferences[PreferencesKeys.PERSONAS_LIST] = jsonString
      }
    }
  }

  override fun readPersonas(): List<Persona> {
    return runBlocking {
      val preferences = dataStore.data.first()
      val jsonString = preferences[PreferencesKeys.PERSONAS_LIST]
      val gson = Gson()
      val listType = object : TypeToken<List<Persona>>() {}.type
      var personas: List<Persona> = if (jsonString != null) {
        try {
          gson.fromJson(jsonString, listType) ?: emptyList<Persona>()
        } catch (e: Exception) {
          emptyList<Persona>() // Return empty list on deserialization error
        }
      } else {
        emptyList<Persona>()
      }

      if (personas.isEmpty()) {
        personas = listOf(
          Persona(
            id = UUID.randomUUID().toString(),
            name = context.getString(R.string.persona_add_edit_dialog_name_default_assist),
            prompt = context.getString(R.string.persona_add_edit_dialog_prompt_default_assist),
            isDefault = true
          ),
          Persona(
            id = UUID.randomUUID().toString(),
            name = context.getString(R.string.persona_add_edit_dialog_name_default_creative),
            prompt = context.getString(R.string.persona_add_edit_dialog_prompt_default_creative),
            isDefault = true
          )
        )
        // Save these default personas back to DataStore
        savePersonas(personas)
      }
      personas
    }
  }

  override fun addPersona(persona: Persona) {
    val currentPersonas = readPersonas().toMutableList()
    currentPersonas.add(persona)
    savePersonas(currentPersonas)
  }

  override fun updatePersona(persona: Persona) {
    val currentPersonas = readPersonas().toMutableList()
    val index = currentPersonas.indexOfFirst { it.id == persona.id }
    if (index != -1) {
      currentPersonas[index] = persona
      savePersonas(currentPersonas)
    }
  }

  override fun deletePersona(personaId: String) {
    val currentPersonas = readPersonas().toMutableList()
    currentPersonas.removeAll { it.id == personaId }
    savePersonas(currentPersonas)
  }

  override fun saveConversations(conversations: List<Conversation>) {
    runBlocking {
      dataStore.edit { preferences ->
        val gson = Gson()
        val jsonString = gson.toJson(conversations)
        preferences[PreferencesKeys.CONVERSATIONS_LIST] = jsonString
      }
    }
  }

  override fun readConversations(): List<Conversation> {
    return runBlocking {
      val preferences = dataStore.data.first()
      val jsonString = preferences[PreferencesKeys.CONVERSATIONS_LIST]
      val gson = Gson()
      val listType = object : TypeToken<List<Conversation>>() {}.type
      if (jsonString != null) {
        try {
          gson.fromJson(jsonString, listType) ?: emptyList<Conversation>()
        } catch (e: Exception) {
          emptyList<Conversation>() // Return empty list on deserialization error
        }
      } else {
        emptyList<Conversation>()
      }
    }
  }

  override fun getConversationById(conversationId: String): Conversation? {
    return readConversations().firstOrNull { it.id == conversationId }
  }

  override fun addConversation(conversation: Conversation) {
    val currentConversations = readConversations().toMutableList()
    currentConversations.add(conversation)
    saveConversations(currentConversations)
  }

  override fun updateConversation(conversation: Conversation) {
    val currentConversations = readConversations().toMutableList()
    val index = currentConversations.indexOfFirst { it.id == conversation.id }
    if (index != -1) {
      currentConversations[index] = conversation
      saveConversations(currentConversations)
    }
  }

  override fun deleteConversation(conversationId: String) {
    val currentConversations = readConversations().toMutableList()
    currentConversations.removeAll { it.id == conversationId }
    saveConversations(currentConversations)
  }

  override fun saveActivePersonaId(personaId: String?) {
    runBlocking {
      dataStore.edit { preferences ->
        if (personaId == null) {
          preferences.remove(PreferencesKeys.ACTIVE_PERSONA_ID)
        } else {
          preferences[PreferencesKeys.ACTIVE_PERSONA_ID] = personaId
        }
      }
    }
  }

  override fun readActivePersonaId(): Flow<String?> {
    return dataStore.data.map { preferences ->
      preferences[PreferencesKeys.ACTIVE_PERSONA_ID]
    }.distinctUntilChanged()
  }

  override fun saveUserDocuments(documents: List<UserDocument>) {
      runBlocking {
          dataStore.edit { preferences ->
              val gson = Gson()
              val jsonString = gson.toJson(documents)
              preferences[PreferencesKeys.USER_DOCUMENTS_LIST] = jsonString
          }
      }
  }

  override fun readUserDocuments(): Flow<List<UserDocument>> {
      return dataStore.data.map { preferences ->
          val jsonString = preferences[PreferencesKeys.USER_DOCUMENTS_LIST]
          if (jsonString != null) {
              val gson = Gson()
              val type = object : TypeToken<List<UserDocument>>() {}.type
              gson.fromJson(jsonString, type) ?: emptyList<UserDocument>()
          } else {
              emptyList<UserDocument>()
          }
      }.distinctUntilChanged()
  }

  override fun addUserDocument(document: UserDocument) {
      runBlocking { // Consider making these suspend functions if runBlocking becomes an issue
          val currentDocuments = readUserDocuments().first().toMutableList()
          currentDocuments.removeAll { it.id == document.id } // Remove if already exists by ID, then add
          currentDocuments.add(document)
          saveUserDocuments(currentDocuments)
      }
  }

  override fun updateUserDocument(document: UserDocument) {
      runBlocking {
          val currentDocuments = readUserDocuments().first().toMutableList()
          val index = currentDocuments.indexOfFirst { it.id == document.id }
          if (index != -1) {
              currentDocuments[index] = document
              saveUserDocuments(currentDocuments)
          } else {
              // Optionally add if not found, or log an error
              addUserDocument(document) // Or handle error: document to update not found
          }
      }
  }

  override fun deleteUserDocument(documentId: String) {
      runBlocking {
          val currentDocuments = readUserDocuments().first().toMutableList()
          currentDocuments.removeAll { it.id == documentId }
          saveUserDocuments(currentDocuments)
      }
  }

  override fun getUserDocumentById(documentId: String): Flow<UserDocument?> {
     return readUserDocuments().map { documents ->
         documents.find { it.id == documentId }
     }
  }
}
