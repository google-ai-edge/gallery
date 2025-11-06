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

package com.neuralforge.mobile.data

import androidx.datastore.core.DataStore
import com.neuralforge.mobile.proto.AccessTokenData
import com.neuralforge.mobile.proto.ImportedModel
import com.neuralforge.mobile.proto.Settings
import com.neuralforge.mobile.proto.Theme
import com.neuralforge.mobile.proto.UserData
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

// TODO(b/423700720): Change to async (suspend) functions
interface DataStoreRepository {
  fun saveTextInputHistory(history: List<String>)

  fun readTextInputHistory(): List<String>

  fun saveTheme(theme: Theme)

  fun readTheme(): Theme

  fun saveAccessTokenData(accessToken: String, refreshToken: String, expiresAt: Long)

  fun clearAccessTokenData()

  fun readAccessTokenData(): AccessTokenData?

  fun saveImportedModels(importedModels: List<ImportedModel>)

  fun readImportedModels(): List<ImportedModel>

  fun isTosAccepted(): Boolean

  fun acceptTos()
}

/** Repository for managing data using Proto DataStore. */
class DefaultDataStoreRepository(
  private val dataStore: DataStore<Settings>,
  private val userDataDataStore: DataStore<UserData>,
) : DataStoreRepository {
  override fun saveTextInputHistory(history: List<String>) {
    runBlocking {
      dataStore.updateData { settings ->
        settings.toBuilder().clearTextInputHistory().addAllTextInputHistory(history).build()
      }
    }
  }

  override fun readTextInputHistory(): List<String> {
    return runBlocking {
      val settings = dataStore.data.first()
      settings.textInputHistoryList
    }
  }

  override fun saveTheme(theme: Theme) {
    runBlocking {
      dataStore.updateData { settings -> settings.toBuilder().setTheme(theme).build() }
    }
  }

  override fun readTheme(): Theme {
    return runBlocking {
      val settings = dataStore.data.first()
      val curTheme = settings.theme
      // Use "auto" as the default theme.
      if (curTheme == Theme.THEME_UNSPECIFIED) Theme.THEME_AUTO else curTheme
    }
  }

  override fun saveAccessTokenData(accessToken: String, refreshToken: String, expiresAt: Long) {
    runBlocking {
      // Clear the entry in old data store.
      dataStore.updateData { settings ->
        settings.toBuilder().setAccessTokenData(AccessTokenData.getDefaultInstance()).build()
      }

      userDataDataStore.updateData { userData ->
        userData
          .toBuilder()
          .setAccessTokenData(
            AccessTokenData.newBuilder()
              .setAccessToken(accessToken)
              .setRefreshToken(refreshToken)
              .setExpiresAtMs(expiresAt)
              .build()
          )
          .build()
      }
    }
  }

  override fun clearAccessTokenData() {
    runBlocking {
      dataStore.updateData { settings -> settings.toBuilder().clearAccessTokenData().build() }
      userDataDataStore.updateData { userData ->
        userData.toBuilder().clearAccessTokenData().build()
      }
    }
  }

  override fun readAccessTokenData(): AccessTokenData? {
    return runBlocking {
      val userData = userDataDataStore.data.first()
      userData.accessTokenData
    }
  }

  override fun saveImportedModels(importedModels: List<ImportedModel>) {
    runBlocking {
      dataStore.updateData { settings ->
        settings.toBuilder().clearImportedModel().addAllImportedModel(importedModels).build()
      }
    }
  }

  override fun readImportedModels(): List<ImportedModel> {
    return runBlocking {
      val settings = dataStore.data.first()
      settings.importedModelList
    }
  }

  override fun isTosAccepted(): Boolean {
    return runBlocking {
      val settings = dataStore.data.first()
      settings.isTosAccepted
    }
  }

  override fun acceptTos() {
    runBlocking {
      dataStore.updateData { settings -> settings.toBuilder().setIsTosAccepted(true).build() }
    }
  }
}
