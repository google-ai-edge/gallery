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

package com.google.ai.edge.gallery.customtasks.agentchat

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Log
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Lightbulb
import androidx.compose.material.icons.outlined.LocalLibrary
import androidx.compose.material.icons.outlined.Map
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.QrCode
import androidx.compose.material.icons.outlined.SentimentVerySatisfied
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.ai.edge.gallery.R
import com.google.ai.edge.gallery.common.SkillTryOutChip
import com.google.ai.edge.gallery.data.AllowedSkill
import com.google.ai.edge.gallery.data.DataStoreRepository
import com.google.ai.edge.gallery.proto.Skill
import com.google.ai.edge.gallery.skills.SkillManager
import com.google.ai.edge.gallery.skills.getJsSkillUrl
import com.google.ai.edge.gallery.skills.getJsSkillWebviewUrl
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlin.io.encoding.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

private const val TAG = "AGSkillManagerVM"

fun getTryOutChips(context: Context): List<SkillTryOutChip> =
  listOf(
    SkillTryOutChip(
      icon = Icons.Outlined.Map,
      label = context.getString(R.string.skill_chip_interactive_map_label),
      prompt = "Show me Googleplex on interactive map.",
      skillName = "interactive-map",
    ),
    SkillTryOutChip(
      icon = Icons.Outlined.Notifications,
      label = context.getString(R.string.skill_chip_schedule_reminder_label),
      prompt = "Set a daily reminder at 9am to check my schedule for today.",
      skillName = "schedule-notification",
    ),
    SkillTryOutChip(
      icon = Icons.Outlined.SentimentVerySatisfied,
      label = context.getString(R.string.skill_chip_track_mood_label),
      prompt =
        "Log yesterday's mood as 2 because it was raining quite heavily, and log today's mood as 9 because I had a great time playing pickleball again. Then show me my mood dashboard.",
      skillName = "mood-tracker",
    ),
    SkillTryOutChip(
      icon = Icons.Outlined.Lightbulb,
      label = context.getString(R.string.skill_chip_learn_something_new_label),
      prompt = "I want to learn something new!",
      skillName = "learn-something-new",
    ),
    SkillTryOutChip(
      icon = Icons.Outlined.LocalLibrary,
      label = context.getString(R.string.skill_chip_query_wikipedia_label),
      prompt = "Check Wikipedia about Oscars 2026. Tell me who won the best picture.",
      skillName = "query-wikipedia",
    ),
    SkillTryOutChip(
      icon = Icons.Outlined.QrCode,
      label = context.getString(R.string.skill_chip_generate_qr_code_label),
      prompt = "Generate QR code for https://deepmind.google/models/gemma/",
      skillName = "qr-code",
    ),
  )

data class SkillState(val skill: Skill)

data class SkillManagerUiState(
  val loading: Boolean = false,
  val skills: List<SkillState> = listOf(),
  val validating: Boolean = false,
  val validationError: String? = null,
  val importDirectoryUri: Uri? = null,
  val loadingSkillAllowlist: Boolean = false,
  val featuredSkills: List<AllowedSkill> = listOf(),
  val skillAllowlistError: String? = null,
)

@HiltViewModel
class SkillManagerViewModel @Inject constructor(val skillManager: SkillManager) : ViewModel() {

  constructor(
    dataStoreRepository: DataStoreRepository,
    context: Context,
  ) : this(SkillManager(dataStoreRepository, context))

  private val _uiState = MutableStateFlow(SkillManagerUiState())
  val uiState = _uiState.asStateFlow()

  var skillLoaded: Boolean
    get() = skillManager.skillLoaded
    set(value) {
      skillManager.skillLoaded = value
    }

  init {
    viewModelScope.launch {
      skillManager.skills.collect { updatedSkills ->
        _uiState.update { currentState ->
          currentState.copy(skills = updatedSkills.map { SkillState(skill = it) })
        }
      }
    }
    loadSkillAllowlist()
  }

  private fun syncSkillsFromManager() {
    _uiState.update { currentState ->
      currentState.copy(skills = skillManager.skills.value.map { SkillState(skill = it) })
    }
  }

  suspend fun loadSkills() {
    setLoading(true)
    skillManager.loadSkills(DEFAULT_DISABLED_SKILLS)
    syncSkillsFromManager()
    setLoading(false)
  }

  private fun loadSkillAllowlist() {
    _uiState.update { it.copy(loadingSkillAllowlist = true, skillAllowlistError = null) }
    viewModelScope.launch(Dispatchers.IO) {
      try {
        val featured = skillManager.loadSkillAllowlist()
        _uiState.update { currentState ->
          currentState.copy(loadingSkillAllowlist = false, featuredSkills = featured)
        }
      } catch (e: Exception) {
        Log.e(TAG, "Error loading skill allowlist", e)
        _uiState.update { currentState ->
          currentState.copy(
            loadingSkillAllowlist = false,
            skillAllowlistError = "Failed to load skill list: ${e.message}",
          )
        }
      }
    }
  }

  fun validateAndAddSkillFromUrl(
    url: String,
    onSuccess: () -> Unit,
    onValidationError: (error: String) -> Unit,
  ) {
    setValidating(true)
    setValidationError(null)

    viewModelScope.launch {
      try {
        val unused = skillManager.addSkillFromUrl(url, _uiState.value.featuredSkills)
        syncSkillsFromManager()
        onSuccess()
      } catch (e: Exception) {
        val error = e.message ?: "Failed to validate skill from URL"
        setValidationError(error)
        onValidationError(error)
      } finally {
        setValidating(false)
      }
    }
  }

  /**
   * Checks if a local skill with the given [directoryUri] already exists in the app's internal
   * storage.
   */
  fun checkLocalSkillExisted(directoryUri: Uri): Boolean {
    return skillManager.checkLocalSkillExisted(directoryUri)
  }

  /**
   * Checks if a built-in skill with the same name as the skill defined in the provided
   * [directoryUri]'s SKILL.md file already exists.
   */
  fun checkBuiltInSkillExistedForImportedSkill(directoryUri: Uri): Boolean {
    return skillManager.checkBuiltInSkillExistedForImportedSkill(directoryUri)
  }

  fun validateAndAddSkillFromLocalImport(
    onSuccess: () -> Unit,
    onValidationError: (error: String) -> Unit,
  ) {
    // Set validation state to true and clear any previous errors.
    setValidating(true)
    setValidationError(null)

    val directoryUri = _uiState.value.importDirectoryUri
    if (directoryUri == null) {
      setValidating(false)
      val error = "No directory URI set."
      setValidationError(error)
      onValidationError(error)
      return
    }

    viewModelScope.launch {
      try {
        val unused =
          skillManager.addSkillFromLocalImport(directoryUri, _uiState.value.featuredSkills)
        syncSkillsFromManager()
        onSuccess()
      } catch (e: Exception) {
        val error = e.message ?: "Failed to import local skill"
        setValidationError(error)
        onValidationError(error)
      } finally {
        setValidating(false)
        setImportDirectoryUri(null)
      }
    }
  }

  fun setLoading(loading: Boolean) {
    _uiState.update { currentState -> currentState.copy(loading = loading) }
  }

  fun setValidating(validating: Boolean) {
    _uiState.update { currentState -> currentState.copy(validating = validating) }
  }

  fun setValidationError(error: String?) {
    _uiState.update { currentState -> currentState.copy(validationError = error) }
  }

  fun setImportDirectoryUri(uri: Uri?) {
    _uiState.update { currentState -> currentState.copy(importDirectoryUri = uri) }
  }

  fun addSkill(skill: Skill, addToDataStore: Boolean) {
    skillManager.addSkill(skill, addToDataStore, _uiState.value.featuredSkills)
    syncSkillsFromManager()
  }

  fun deleteSkill(name: String) {
    skillManager.deleteSkill(name, _uiState.value.featuredSkills)
    syncSkillsFromManager()
  }

  fun deleteSkills(names: Set<String>) {
    viewModelScope.launch {
      skillManager.deleteSkills(names, _uiState.value.featuredSkills)
      syncSkillsFromManager()
    }
  }

  fun setSkillSelected(skill: SkillState, selected: Boolean) {
    skillManager.setSkillSelected(skill.skill, selected, _uiState.value.featuredSkills)
    syncSkillsFromManager()
  }

  fun setAllSkillsSelected(selected: Boolean) {
    skillManager.setAllSkillsSelected(selected)
    syncSkillsFromManager()
  }

  fun getSelectedSkills(): List<Skill> {
    return skillManager.getSelectedSkills()
  }

  fun getSkill(name: String): Skill? {
    return skillManager.getSkill(name)
  }

  /** Resolves the local URL path to a JavaScript file [scriptName] within [skillName]. */
  fun getJsSkillUrl(skillName: String, scriptName: String): String? {
    val skill = skillManager.getSkill(name = skillName) ?: return null
    return skill.getJsSkillUrl(scriptName = scriptName)
  }

  /** Resolves the webview URL for a JavaScript skill given [skillName] and base [url]. */
  fun getJsSkillWebviewUrl(skillName: String, url: String): String {
    val skill = skillManager.getSkill(name = skillName) ?: return url
    return skill.getJsSkillWebviewUrl(url = url)
  }

  fun getSelectedSkillsNamesAndDescriptions(): String {
    return skillManager.getSelectedSkillsNamesAndDescriptions()
  }

  /** Saves or updates a custom skill. */
  fun saveSkillEdit(
    index: Int,
    name: String,
    description: String,
    instructions: String,
    scriptsContent: Map<String, String>,
    onSuccess: () -> Unit,
    onError: (error: String) -> Unit,
  ) {
    viewModelScope.launch {
      try {
        val unused =
          skillManager.saveSkillEdit(
            index = index,
            name = name,
            description = description,
            instructions = instructions,
            scriptsContent = scriptsContent,
            featuredSkills = _uiState.value.featuredSkills,
          )
        onSuccess()
      } catch (e: Exception) {
        Log.e(TAG, "Error saving skill edit", e)
        onError(e.message ?: "Failed to save skill")
      }
    }
  }

  /** Loads the content of skill scripts from the local file system. */
  fun loadSkillScriptsContent(skill: Skill, onDone: (Map<String, String>) -> Unit) {
    viewModelScope.launch {
      val result = skillManager.loadSkillScriptsContent(skill)
      onDone(result)
    }
  }

  /** Deletes a specific script file associated with a locally imported skill. */
  fun deleteSkillScript(skill: Skill, scriptName: String) {
    skillManager.deleteSkillScript(skill, scriptName)
  }

  /** Checks if a skill with the given [skillName] is currently selected. */
  fun isSkillSelected(skillName: String): Boolean {
    return skillManager.isSkillSelected(skillName)
  }

  fun getSkillShortId(skill: Skill): String {
    return skillManager.getSkillShortId(skill, _uiState.value.featuredSkills)
  }

  companion object {
    val DEFAULT_DISABLED_SKILLS =
      setOf("calculate-hash", "kitchen-adventure", "text-spinner", "send-email")

    fun convertSkillMdToProto(
      mdContent: String,
      builtIn: Boolean,
      selected: Boolean,
      skillUrl: String = "",
      importDir: String = "",
    ): Pair<Skill?, List<String>> {
      return SkillManager.convertSkillMdToProto(
        mdContent = mdContent,
        builtIn = builtIn,
        selected = selected,
        skillUrl = skillUrl,
        importDir = importDir,
      )
    }

    fun loadBuiltInSkills(
      context: Context,
      builtInSelectionMap: Map<String, Pair<Boolean, Boolean>> = emptyMap(),
      defaultDisabledSkills: Set<String> = emptySet(),
    ): List<Skill> {
      return SkillManager.loadBuiltInSkills(
        context = context,
        builtInSelectionMap = builtInSelectionMap,
        defaultDisabledSkills = defaultDisabledSkills,
      )
    }
  }
}

fun decodeBase64ToBitmap(base64String: String): Bitmap? {
  return try {
    // 1. Clean the string (remove headers if present)
    val pureBase64 = base64String.substringAfter(",")

    // 2. Decode the Base64 string into a byte array
    val imageBytes = Base64.decode(pureBase64)

    // 3. Convert the byte array into a Bitmap
    BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size)
  } catch (e: java.lang.Exception) {
    e.printStackTrace()
    null
  }
}
