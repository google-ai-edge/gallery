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
import android.provider.OpenableColumns
import android.util.Log
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Email
import androidx.compose.material.icons.outlined.Kitchen
import androidx.compose.material.icons.outlined.LocalLibrary
import androidx.compose.material.icons.outlined.Map
import androidx.compose.material.icons.outlined.Password
import androidx.compose.material.icons.outlined.QrCode
import androidx.compose.material.icons.outlined.ScreenRotation
import androidx.compose.material.icons.outlined.SentimentVerySatisfied
import androidx.compose.material.icons.outlined.Tag
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.ai.edge.gallery.common.LOCAL_URL_BASE
import com.google.ai.edge.gallery.common.SkillTryOutChip
import com.google.ai.edge.gallery.common.getJsonResponse
import com.google.ai.edge.gallery.data.AllowedSkill
import com.google.ai.edge.gallery.data.DataStoreRepository
import com.google.ai.edge.gallery.data.SkillAllowlist
import com.google.ai.edge.gallery.proto.Skill
import com.google.ai.edge.litertlm.Contents
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.InputStreamReader
import java.net.URL
import javax.inject.Inject
import kotlin.collections.joinToString
import kotlin.io.encoding.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val TAG = "AGSkillManagerVM"

private const val SKILL_ALLOWLIST_URL = ""

val TRYOUT_CHIPS: List<SkillTryOutChip> =
  listOf(
    SkillTryOutChip(
      icon = Icons.Outlined.Map,
      label = "Interactive Map",
      prompt = "Show me Googleplex on interactive map.",
      skillName = "interactive-map",
    ),
    SkillTryOutChip(
      icon = Icons.Outlined.Kitchen,
      label = "Kitchen Adventure",
      prompt = "Start kitchen adventure",
      skillName = "kitchen-adventure",
    ),
    SkillTryOutChip(
      icon = Icons.Outlined.Tag,
      label = "Calculate Hash",
      prompt = "What is the sha1 hash of \"gemma\"?",
      skillName = "calculate-hash",
    ),
    SkillTryOutChip(
      icon = Icons.Outlined.ScreenRotation,
      label = "Text Spinner",
      prompt = "Spin \"Gemma\" on my head",
      skillName = "text-spinner",
    ),
    SkillTryOutChip(
      icon = Icons.Outlined.Email,
      label = "Send Email",
      prompt = "Send email 'Good morning' to abc@example.com. Content: 'Any plans for tonight?'",
      skillName = "send-email",
    ),
    SkillTryOutChip(
      icon = Icons.Outlined.SentimentVerySatisfied,
      label = "Track my mood",
      prompt =
        "Log yesterday's mood as 2 because it was raining quite heavily, and log today's mood as 9 because I had a great time playing pickleball again. Then show me my mood dashboard.",
      skillName = "mood-tracker",
    ),
    SkillTryOutChip(
      icon = Icons.Outlined.LocalLibrary,
      label = "Query Wikipedia",
      prompt = "Check Wikipedia about Oscars 2026. Tell me who won the best picture.",
      skillName = "query-wikipedia",
    ),
    SkillTryOutChip(
      icon = Icons.Outlined.QrCode,
      label = "Generate QR code",
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
class SkillManagerViewModel
@Inject
constructor(
  val dataStoreRepository: DataStoreRepository,
  @ApplicationContext private val context: Context,
) : ViewModel() {
  private val _uiState = MutableStateFlow(SkillManagerUiState())
  val uiState = _uiState.asStateFlow()
  var skillLoaded = false

  init {
    if (SKILL_ALLOWLIST_URL.isNotEmpty()) {
      loadSkillAllowlist()
    }
  }

  fun loadSkills(onDone: () -> Unit) {
    if (!skillLoaded) {
      setLoading(true)
      viewModelScope.launch(Dispatchers.IO) {
        Log.d(TAG, "Loading skills index...")

        // 1. Load all skills from DataStore.
        val allDataStoreSkills = dataStoreRepository.getAllSkills()
        val dataStoreBuiltInSkills = allDataStoreSkills.filter { it.builtIn }
        val dataStoreCustomSkills = allDataStoreSkills.filter { !it.builtIn }
        Log.d(
          TAG,
          "data store built-in skills:\n${dataStoreBuiltInSkills.joinToString(separator = "\n") { it.name }}",
        )
        Log.d(
          TAG,
          "data store custom skills:\n${dataStoreCustomSkills.joinToString(separator = "\n") { it.name }}",
        )

        // 2. Keep track of the selection state of existing built-in skills.
        val builtInSelectionMap = dataStoreBuiltInSkills.associate { it.name to it.selected }
        Log.d(TAG, "data store built-in skills selection map: $builtInSelectionMap")

        // 3. Read and parse SKILL.md files from assets/skills directories.
        val builtInSkills = mutableListOf<Skill>()
        try {
          val skillAssetDirs = context.assets.list("skills") ?: emptyArray()
          for (dirName in skillAssetDirs) {
            val skillMdPath = "skills/$dirName/SKILL.md"
            try {
              context.assets.open(skillMdPath).use { inputStream ->
                val mdContent = inputStream.bufferedReader().use { it.readText() }
                val (skillProto, errors) =
                  convertSkillMdToProto(
                    mdContent,
                    builtIn = true,
                    // Selection state will be reconciled with DataStore later
                    selected = true,
                    importDir = "assets/skills/$dirName",
                  )
                if (errors.isNotEmpty()) {
                  Log.w(TAG, "Error parsing asset skill $dirName: ${errors.joinToString(", ")}")
                } else {
                  skillProto?.let {
                    // Apply the previous selection state if it exists, otherwise default to
                    // true.
                    val selectedState = builtInSelectionMap[it.name] ?: true
                    builtInSkills.add(it.toBuilder().setSelected(selectedState).build())
                    Log.d(TAG, "Added built-in skill: ${it.name}")
                  }
                }
              }
            } catch (e: Exception) {
              Log.w(TAG, "SKILL.md not found or error reading for asset skill $dirName", e)
            }
          }
        } catch (e: Exception) {
          Log.e(TAG, "Error listing assets/skills", e)
        }
        Log.d(
          TAG,
          "Final built-in skills:\n${builtInSkills.joinToString(separator = "\n") { "${it.name}(${it.selected})" }}",
        )

        // 4. Combine the updated built-in skills with the existing custom skills.
        val finalSkills = builtInSkills.toMutableList()
        for (customSkill in dataStoreCustomSkills) {
          if (!finalSkills.any { it.name == customSkill.name }) {
            finalSkills.add(customSkill)
          }
        }

        // 5. Update the DataStore with the combined list of skills.
        dataStoreRepository.setSkills(finalSkills)

        // 6. Update UI State with the final set of skills.
        _uiState.update { currentState ->
          currentState.copy(skills = finalSkills.map { SkillState(skill = it) })
        }

        setLoading(false)
        skillLoaded = true
        withContext(Dispatchers.Default) { onDone() }
      }
    } else {
      onDone()
    }
  }

  private fun loadSkillAllowlist() {
    _uiState.update { it.copy(loadingSkillAllowlist = true, skillAllowlistError = null) }
    viewModelScope.launch(Dispatchers.IO) {
      try {
        val url = SKILL_ALLOWLIST_URL
        Log.d(TAG, "Fetching skill allowlist from: $url")
        val result =
          getJsonResponse<SkillAllowlist>(url)
            ?: throw Exception("Failed to fetch or parse JSON from $url")

        val allowlist = result.jsonObj
        Log.d(TAG, "Successfully loaded ${allowlist.featuredSkills.size} featured skills.")

        _uiState.update { currentState ->
          currentState.copy(
            loadingSkillAllowlist = false,
            featuredSkills = allowlist.featuredSkills,
          )
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

    viewModelScope.launch(Dispatchers.IO) {
      try {
        Log.d(TAG, "Validating skill from URL: $url")

        // 1. Normalize the URL: remove trailing "/SKILL.md" or "/".
        var normalizedUrl = url
        if (normalizedUrl.endsWith("/SKILL.md")) {
          normalizedUrl = normalizedUrl.dropLast("/SKILL.md".length)
        }
        if (normalizedUrl.endsWith("/")) {
          normalizedUrl = normalizedUrl.dropLast(1)
        }
        val skillMdUrl = "$normalizedUrl/SKILL.md"
        Log.d(TAG, "Fetching SKILL.md from: $skillMdUrl")

        // 2. Read url/SKILL.md.
        val mdContent =
          try {
            val connection = URL(skillMdUrl).openConnection()
            InputStreamReader(connection.getInputStream()).use { reader -> reader.readText() }
          } catch (e: Exception) {
            Log.e(TAG, "Error fetching SKILL.md from $skillMdUrl", e)
            val error = "Failed to fetch SKILL.md: ${e.message}"
            setValidationError(error)
            onValidationError(error)
            return@launch
          }

        if (mdContent.isEmpty()) {
          val error = "SKILL.md is empty at $skillMdUrl"
          setValidationError(error)
          onValidationError(error)
          return@launch
        }

        // 3. If it exists, read and convert it to proto.
        val (skillProto, errors) =
          convertSkillMdToProto(
            mdContent,
            builtIn = false,
            selected = true,
            skillUrl = normalizedUrl,
          )

        // 4. If conversion failed, report error.
        if (errors.isNotEmpty()) {
          val error = "Error parsing SKILL.md from $skillMdUrl: ${errors.joinToString(", ")}"
          setValidationError(error)
          onValidationError(error)
          return@launch
        }

        skillProto?.let { skill ->
          // 5. Check if the name already exists. If so, report error.
          if (_uiState.value.skills.any { curSkill -> curSkill.skill.name == skill.name }) {
            val error = "A skill with the name '${skill.name}' already exists."
            setValidationError(error)
            onValidationError(error)
            return@launch
          }

          // 6. Add to ui states and data store.
          addSkill(skill = skill, addToDataStore = true)
          Log.d(TAG, "Successfully added skill from URL: ${skill.name}")
          onSuccess()
        }
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
    val originalImportDirName = getDisplayName(context, directoryUri)
    if (originalImportDirName.isEmpty()) {
      return false
    }
    val destDir = getSkillDestinationDir(originalImportDirName)
    return destDir.exists()
  }

  /**
   * Checks if a built-in skill with the same name as the skill defined in the provided
   * [directoryUri]'s SKILL.md file already exists.
   */
  fun checkBuiltInSkillExistedForImportedSkill(directoryUri: Uri): Boolean {
    Log.d(TAG, "Checking built-in skill existed for imported skill: $directoryUri")

    val rootFile = DocumentFile.fromTreeUri(context, directoryUri)
    val skillMdFile = rootFile?.findFile("SKILL.md")

    if (skillMdFile == null || !skillMdFile.exists()) {
      Log.w(TAG, "SKILL.md not found in the selected directory for built-in check.")
      return false
    }

    val mdContent =
      try {
        context.contentResolver.openInputStream(skillMdFile.uri)?.use { inputStream ->
          inputStream.bufferedReader().use { it.readText() }
        }
      } catch (e: Exception) {
        Log.e(TAG, "Error reading SKILL.md for built-in check", e)
        return false
      } ?: ""

    if (mdContent.isEmpty()) {
      Log.w(TAG, "SKILL.md is empty for built-in check.")
      return false
    }

    val (skillProto, errors) = convertSkillMdToProto(mdContent, builtIn = false, selected = false)

    if (errors.isNotEmpty() || skillProto == null) {
      Log.w(TAG, "Error parsing SKILL.md for built-in check: ${errors.joinToString(", ")}")
      return false
    }

    val importedSkillName = skillProto.name
    return _uiState.value.skills.any { it.skill.builtIn && it.skill.name == importedSkillName }
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

    viewModelScope.launch(Dispatchers.IO) {
      try {
        Log.d(TAG, "Validating skill from directory URI: $directoryUri")

        // Get the DocumentFile representing the selected directory
        val rootFile = DocumentFile.fromTreeUri(context, directoryUri)

        // Find the SKILL.md file within that directory
        val skillMdFile = rootFile?.findFile("SKILL.md")

        if (skillMdFile == null || !skillMdFile.exists()) {
          val error = "SKILL.md not found in the selected directory."
          setValidationError(error)
          onValidationError(error)
          return@launch
        }

        // Read the content using the correctly resolved URI
        val mdContent =
          try {
            context.contentResolver.openInputStream(skillMdFile.uri)?.use { inputStream ->
              inputStream.bufferedReader().use { it.readText() }
            }
          } catch (e: Exception) {
            Log.e(TAG, "Error reading SKILL.md", e)
            val error = "Failed to read SKILL.md: ${e.message}"
            setValidationError(error)
            onValidationError(error)
            return@launch
          } ?: ""

        val (skillProto, errors) =
          convertSkillMdToProto(mdContent, builtIn = false, selected = true)

        if (errors.isNotEmpty()) {
          val error = "Error parsing SKILL.md: ${errors.joinToString(", ")}"
          setValidationError(error)
          onValidationError(error)
          return@launch
        }

        skillProto?.let {
          // Successfully parsed the skill. Add the directory name.
          val originalImportDirName = getDisplayName(context, directoryUri)
          val destDir = getSkillDestinationDir(originalImportDirName)
          val newImportDirName = destDir.relativeTo(context.filesDir).path

          // Create the destination directory.
          if (destDir.exists()) {
            Log.d(TAG, "Destination directory already exists, deleting: ${destDir.path}")
            deleteSkill(name = skillProto.name)
          }
          if (!destDir.exists()) {
            destDir.mkdirs()
          }

          // Check if the skill already exists.
          if (_uiState.value.skills.any { curSkill -> curSkill.skill.name == skillProto.name }) {
            setValidating(false)
            val error = "A skill with the name '${skillProto.name}' already exists."
            setValidationError(error)
            onValidationError(error)
            return@launch
          }

          val sourceDocumentFile = DocumentFile.fromTreeUri(context, directoryUri)
          if (sourceDocumentFile == null) {
            Log.e(TAG, "Failed to get DocumentFile from URI: $directoryUri")
            val error = "Failed to access the selected directory."
            setValidationError(error)
            onValidationError(error)
            return@launch
          }

          // Recursive function to copy a DocumentFile to a File
          fun copyDocumentFile(source: DocumentFile, dest: File) {
            if (source.isDirectory) {
              dest.mkdirs()
              for (child in source.listFiles()) {
                val childDest = File(dest, child.name!!)
                copyDocumentFile(child, childDest)
              }
            } else if (source.isFile) {
              try {
                Log.d(TAG, "Copying file ${source.name} to ${dest.path}")
                context.contentResolver.openInputStream(source.uri)?.use { inputStream ->
                  dest.outputStream().use { outputStream -> inputStream.copyTo(outputStream) }
                }
              } catch (e: Exception) {
                Log.e(TAG, "Error copying file ${source.name} to ${dest.path}", e)
                // Log error but don't block the whole process for now.
              }
            }
          }

          // Start copying from the root of the selected directory
          copyDocumentFile(sourceDocumentFile, destDir)

          // Update the skill proto with the new import directory name.
          val skillWithDir = it.toBuilder().setImportDirName(newImportDirName).build()
          addSkill(skill = skillWithDir, addToDataStore = true)
          Log.d(TAG, "Successfully added skill from local import: ${skillWithDir.name}")
          onSuccess()
        }
          ?: run {
            // Should not happen if errors is empty, but good to handle.
            val error = "Unknown error during SKILL.md conversion."
            setValidationError(error)
            onValidationError(error)
          }
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
    Log.d(TAG, "Adding skill: $skill")

    // Update state.
    _uiState.update { currentState ->
      val newSkillState = SkillState(skill = skill)
      if (skill.builtIn) {
        currentState.copy(skills = currentState.skills + newSkillState)
      } else {
        val firstCustomIndex = currentState.skills.indexOfFirst { !it.skill.builtIn }
        val newSkills =
          if (firstCustomIndex == -1) {
            currentState.skills + newSkillState
          } else {
            currentState.skills.toMutableList().apply { add(firstCustomIndex, newSkillState) }
          }
        currentState.copy(skills = newSkills)
      }
    }

    if (addToDataStore) {
      // Add skill to data store.
      viewModelScope.launch(Dispatchers.IO) { dataStoreRepository.addSkill(skill) }
    }
  }

  fun deleteSkill(name: String) {
    // Locate the skill to be deleted.
    val skill = _uiState.value.skills.firstOrNull { it.skill.name == name }?.skill
    if (skill == null) {
      return
    }

    // Update state.
    _uiState.update { currentState ->
      currentState.copy(skills = currentState.skills.filter { it.skill.name != name })
    }

    viewModelScope.launch(Dispatchers.IO) {
      // Delete imported files from file system.
      if (skill.importDirName.isNotEmpty()) {
        try {
          val skillDir = context.filesDir.resolve(skill.importDirName)
          skillDir.deleteRecursively()
        } catch (e: Exception) {
          Log.w(TAG, "Failed to delete skill directory: ${skill.importDirName}", e)
        }
      }

      // Delete skill from data store.
      dataStoreRepository.deleteSkill(name)
    }
  }

  fun deleteSkills(names: Set<String>) {
    val skillsToDelete =
      _uiState.value.skills.filter { names.contains(it.skill.name) }.map { it.skill }
    if (skillsToDelete.isEmpty()) {
      return
    }

    // Update state.
    _uiState.update { currentState ->
      currentState.copy(skills = currentState.skills.filter { !names.contains(it.skill.name) })
    }

    viewModelScope.launch(Dispatchers.IO) {
      // Delete all imported files from file system.
      for (skill in skillsToDelete) {
        if (skill.importDirName.isNotEmpty()) {
          try {
            val skillDir = context.filesDir.resolve(skill.importDirName)
            skillDir.deleteRecursively()
          } catch (e: Exception) {
            Log.w(TAG, "Failed to delete skill directory: ${skill.importDirName}", e)
          }
        }
      }

      // Delete skills from data store.
      dataStoreRepository.deleteSkills(names)
    }
  }

  fun setSkillSelected(skill: SkillState, selected: Boolean) {
    // Update state.
    val updatedSkill = skill.skill.toBuilder().setSelected(selected).build()
    val updatedSkills =
      _uiState.value.skills.map { curSkill ->
        if (curSkill.skill.name == skill.skill.name) {
          SkillState(skill = updatedSkill)
        } else {
          curSkill
        }
      }
    _uiState.update { currentState -> currentState.copy(skills = updatedSkills) }

    // Update data store.
    viewModelScope.launch(Dispatchers.IO) {
      dataStoreRepository.setSkillSelected(skill.skill, selected)
    }
  }

  fun setAllSkillsSelected(selected: Boolean) {
    // Update state.
    _uiState.update { currentState ->
      val updatedSkills =
        currentState.skills.map { skillState ->
          SkillState(skill = skillState.skill.toBuilder().setSelected(selected).build())
        }
      currentState.copy(skills = updatedSkills)
    }

    // Update data store.
    viewModelScope.launch(Dispatchers.IO) { dataStoreRepository.setAllSkillsSelected(selected) }
  }

  fun getSelectedSkills(): List<Skill> {
    return _uiState.value.skills.filter { it.skill.selected }.map { it.skill }
  }

  fun getSystemPrompt(baseSystemPrompt: String): Contents {
    // Replace ___SKILLS___ with the following skills list:
    //
    // # Skill name: skill_name_1
    // ##Description: skill_description_1
    // ------
    // Skill name: skill_name_2
    // Description: skill_description_2
    // ------
    // Skill name: skill_name_3
    // Description: skill_description_3
    // ------
    val selectedSkillsNamesAndDescriptions = getSelectedSkillsNamesAndDescriptions()
    val systemPrompt = baseSystemPrompt.replace("___SKILLS___", selectedSkillsNamesAndDescriptions)
    Log.d(TAG, "System prompt:\n$systemPrompt")
    return Contents.of(systemPrompt)
  }

  fun getSkill(name: String): Skill? {
    return _uiState.value.skills.firstOrNull { it.skill.name == name }?.skill
  }

  fun getJsSkillUrl(skillName: String, scriptName: String): String? {
    val skill = getSkill(name = skillName) ?: return null
    var baseUrl = ""
    // Construct a local URL for imported skill and built-in skills.
    if (skill.importDirName.isNotEmpty()) {
      baseUrl = "$LOCAL_URL_BASE/${skill.importDirName}"
    }
    // Use skill.skillUrl if set.
    else if (skill.skillUrl.isNotEmpty()) {
      baseUrl = skill.skillUrl
    }
    if (baseUrl.isEmpty()) {
      return null
    }
    return "$baseUrl/scripts/$scriptName"
  }

  fun getJsSkillWebviewUrl(skillName: String, url: String): String {
    val skill = getSkill(name = skillName) ?: return url

    // Return the url if it is an absolute url.
    if (url.startsWith("http")) {
      return url
    }

    var baseUrl = ""
    // Construct a local URL for imported skill.
    if (skill.importDirName.isNotEmpty()) {
      baseUrl = "$LOCAL_URL_BASE/${skill.importDirName}"
    }
    // Use skill.skillUrl if set.
    else if (skill.skillUrl.isNotEmpty()) {
      baseUrl = skill.skillUrl
    }
    if (baseUrl.isEmpty()) {
      return url
    }
    return "$baseUrl/assets/$url"
  }

  fun getSelectedSkillsNamesAndDescriptions(): String {
    return this.getSelectedSkills().joinToString("\n") { skill ->
      "- ${skill.name}: ${skill.description}"
    }
  }

  /**
   * Converts the content of a skill.md file to a [Skill] proto.
   *
   * The expected format is:
   * ```
   * ---
   * name: name-of-the-skill
   * description: description of the skill
   * metadata:
   *   key: value
   * ---
   *
   * other instructions text
   * ```
   *
   * @return A [Pair] containing the parsed [Skill] proto (or null if errors occurred) and a list of
   *   error messages.
   */
  fun convertSkillMdToProto(
    mdContent: String,
    builtIn: Boolean,
    selected: Boolean,
    skillUrl: String = "",
    importDir: String = "",
  ): Pair<Skill?, List<String>> {
    val parts = mdContent.split("---")
    val errors = mutableListOf<String>()

    if (parts.size < 3) {
      errors.add("Invalid format: Expected at least two '---' sections.")
      return Pair(null, errors)
    }

    // Part 1: Header (index 1)
    val header = parts[1].trim()
    var name: String? = null
    var description: String? = null
    var requireSecret = false
    var requireSecretDescription = ""
    var homepage: String? = null

    var startMetadata = false
    for (line in header.lines()) {
      val trimmedLine = line.trim()
      if (trimmedLine == "metadata:") {
        startMetadata = true
        continue
      }
      if (!startMetadata) {
        when {
          trimmedLine.startsWith("name:") -> name = trimmedLine.substringAfter("name:").trim()
          trimmedLine.startsWith("description:") ->
            description = trimmedLine.substringAfter("description:").trim()
        }
      } else {
        when {
          trimmedLine.startsWith("require-secret:") ->
            requireSecret = trimmedLine.substringAfter("require-secret:").trim().toBoolean()
          trimmedLine.startsWith("require-secret-description:") ->
            requireSecretDescription =
              trimmedLine.substringAfter("require-secret-description:").trim()
          trimmedLine.startsWith("homepage:") ->
            homepage = trimmedLine.substringAfter("homepage:").trim()
        }
      }
    }

    if (name.isNullOrEmpty()) {
      errors.add("Missing or empty 'name' in the header.")
    }
    if (description.isNullOrEmpty()) {
      errors.add("Missing or empty 'description' in the header.")
    }

    // Part 2: Instructions (index 2 onwards)
    val instructions = parts.drop(2).joinToString("---").trim()

    if (errors.isNotEmpty()) {
      return Pair(null, errors)
    }

    val skill =
      Skill.newBuilder()
        .setName(name!!)
        .setDescription(description!!)
        .setInstructions(instructions)
        .setBuiltIn(builtIn)
        .setSelected(selected)
        .setSkillUrl(skillUrl)
        .setRequireSecret(requireSecret)
        .setRequireSecretDescription(requireSecretDescription)
        .setHomepage(homepage ?: "")
        .setImportDirName(importDir)
        .build()

    return Pair(skill, emptyList())
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
    viewModelScope.launch(Dispatchers.IO) {
      try {
        Log.d(TAG, "saveSkillEdit: $name")

        val isNewSkill = index < 0 || index >= _uiState.value.skills.size

        if (isNewSkill) {
          Log.d(TAG, "Saving new skill: $name")

          // Check for name conflict
          if (_uiState.value.skills.any { it.skill.name == name }) {
            val error = "A skill with the name '${name}' already exists."
            Log.w(TAG, error)
            onError(error)
            return@launch
          }

          val normalizedName = name.replace("\\s+".toRegex(), "-")
          val skillDestDir = context.filesDir.resolve("skills/${normalizedName}")
          val scriptDestDir = File(skillDestDir, "scripts")
          // If the directory exists from a previous failed attempt, clear it.
          if (skillDestDir.exists()) {
            Log.w(
              TAG,
              "Skill destination directory already exists for new skill: ${skillDestDir.path}, deleting.",
            )
            skillDestDir.deleteRecursively()
          }

          // Create directories
          skillDestDir.mkdirs()
          scriptDestDir.mkdirs()
          val skillMdFile = File(skillDestDir, "SKILL.md")

          // Write SKILL.md
          writeSkillMd(skillMdFile, normalizedName, description, instructions)

          // Save scripts
          saveScripts(scriptDestDir, scriptsContent)

          // Create and add new skill proto
          val newSkill =
            Skill.newBuilder()
              .setName(normalizedName)
              .setDescription(description)
              .setInstructions(instructions)
              .setBuiltIn(false)
              .setSelected(true)
              .setSkillUrl("")
              .setImportDirName(skillDestDir.relativeTo(context.filesDir).path)
              .build()
          addSkill(newSkill, addToDataStore = true)
          onSuccess()
        } else {
          Log.d(TAG, "Saving skill edit: $name")

          // Editing existing skill
          val existingSkillState = _uiState.value.skills[index]
          val existingSkill = existingSkillState.skill

          val oldName = existingSkill.name
          val normalizedNewName = name.replace("\\s+".toRegex(), "-")
          val newSkillDestDir = context.filesDir.resolve("skills/${normalizedNewName}")
          val newScriptDestDir = File(newSkillDestDir, "scripts")
          val newSkillMdFile = File(newSkillDestDir, "SKILL.md")

          if (existingSkill.builtIn) {
            onError("Cannot edit built-in skills.")
            return@launch
          }

          var updatedImportDirName = existingSkill.importDirName

          if (oldName != normalizedNewName) {
            Log.d(TAG, "Renaming skill from $oldName to $normalizedNewName")

            // Check for name conflict with the new name
            if (_uiState.value.skills.any { it.skill.name == normalizedNewName }) {
              val error = "A skill with the name '${normalizedNewName}' already exists."
              Log.w(TAG, error)
              onError(error)
              return@launch
            }

            val oldSkillDestDir = context.filesDir.resolve(existingSkill.importDirName)
            if (oldSkillDestDir.exists()) {
              Log.d(
                TAG,
                "Renaming directory from ${oldSkillDestDir.path} to ${newSkillDestDir.path}",
              )
              if (!oldSkillDestDir.renameTo(newSkillDestDir)) {
                val error =
                  "Failed to rename skill directory from ${oldSkillDestDir.name} to ${newSkillDestDir.name}."
                Log.e(TAG, error)
                onError(error)
                return@launch
              }
              updatedImportDirName = newSkillDestDir.relativeTo(context.filesDir).path
            } else {
              Log.w(TAG, "Old skill directory not found: ${oldSkillDestDir.path}")
              // If the old directory doesn't exist, create the new one.
              newSkillDestDir.mkdirs()
            }
          }

          // Update SKILL.md
          writeSkillMd(newSkillMdFile, normalizedNewName, description, instructions)

          // Update scripts: Clear existing scripts and save new ones.
          newScriptDestDir.deleteRecursively()
          newScriptDestDir.mkdirs()
          saveScripts(newScriptDestDir, scriptsContent)

          // Update skill proto in state and data store
          val updatedSkill =
            existingSkill
              .toBuilder()
              .setName(normalizedNewName)
              .setDescription(description)
              .setInstructions(instructions)
              .setImportDirName(updatedImportDirName)
              .build()

          // Update state
          _uiState.update { currentState ->
            val updatedSkillsList =
              currentState.skills.mapIndexed { i, skillState ->
                if (i == index) SkillState(skill = updatedSkill) else skillState
              }
            currentState.copy(skills = updatedSkillsList)
          }

          // Update data store
          updateSkillInDataStore(oldName, updatedSkill)
          onSuccess()
        }
      } catch (e: Exception) {
        Log.e(TAG, "Error saving skill edit", e)
        onError("Failed to save skill: ${e.message}")
      }
    }
  }

  /** Loads the content of skill scripts from the local file system. */
  fun loadSkillScriptsContent(skill: Skill, onDone: (Map<String, String>) -> Unit) {
    viewModelScope.launch(Dispatchers.IO) {
      if (skill.importDirName.isEmpty()) {
        Log.d(TAG, "Skill ${skill.name} has no import directory, returning empty scripts.")
        withContext(Dispatchers.Default) { onDone(emptyMap()) }
        return@launch
      }

      val skillDir = context.filesDir.resolve(skill.importDirName)
      val scriptDir = File(skillDir, "scripts")

      if (!scriptDir.exists() || !scriptDir.isDirectory) {
        Log.w(TAG, "Script directory not found for skill ${skill.name}: ${scriptDir.path}")
        withContext(Dispatchers.Default) { onDone(emptyMap()) }
        return@launch
      }

      val scriptsContent = mutableMapOf<String, String>()
      for (file in scriptDir.listFiles() ?: emptyArray()) {
        if (file.isFile && (file.name.endsWith(".html") || file.name.endsWith(".js"))) {
          try {
            val content = file.readText()
            scriptsContent[file.name] = content
            Log.d(TAG, "Loaded script ${file.name} for skill ${skill.name}")
          } catch (e: Exception) {
            Log.e(TAG, "Error reading script file ${file.name} for skill ${skill.name}", e)
            scriptsContent[file.name] = "" // Use empty string on error
          }
        }
      }
      withContext(Dispatchers.Default) { onDone(scriptsContent) }
    }
  }

  /** Deletes a specific script file associated with a locally imported skill. */
  fun deleteSkillScript(skill: Skill, scriptName: String) {
    if (skill.importDirName.isEmpty()) {
      Log.d(TAG, "Skill ${skill.name} is not locally imported, cannot delete script.")
      return
    }

    viewModelScope.launch(Dispatchers.IO) {
      val skillDir = context.filesDir.resolve(skill.importDirName)
      val scriptDir = File(skillDir, "scripts")
      val scriptFile = File(scriptDir, scriptName)

      if (scriptFile.exists()) {
        try {
          if (scriptFile.delete()) {
            Log.d(TAG, "Successfully deleted script: ${scriptFile.path}")
          } else {
            Log.w(TAG, "Failed to delete script: ${scriptFile.path}")
          }
        } catch (e: Exception) {
          Log.e(TAG, "Error deleting script ${scriptFile.path}", e)
        }
      } else {
        Log.d(TAG, "Script file not found, ignoring delete: ${scriptFile.path}")
      }
    }
  }

  /** Checks if a skill with the given [skillName] is currently selected. */
  fun isSkillSelected(skillName: String): Boolean {
    return _uiState.value.skills.firstOrNull { it.skill.name == skillName }?.skill?.selected == true
  }

  private fun writeSkillMd(
    skillMdFile: File,
    name: String,
    description: String,
    instructions: String,
  ) {
    Log.d(TAG, "Writing skill.md: ${skillMdFile.path}")
    val mdContent =
      """
    ---
    name: $name
    description: $description
    ---

    $instructions
    """
        .trimIndent()
    skillMdFile.writeText(mdContent)
  }

  private fun saveScripts(scriptDestDir: File, scriptsContent: Map<String, String>) {
    scriptDestDir.mkdirs() // Ensure directory exists

    // Clear existing files in the script directory
    scriptDestDir.listFiles()?.forEach { it.delete() }

    for ((scriptName, content) in scriptsContent) {
      val scriptFile = File(scriptDestDir, scriptName)
      Log.d(TAG, "Saving script: ${scriptFile.path}")
      try {
        scriptFile.writeText(content)
        Log.d(TAG, "Saved script: ${scriptFile.path}")
      } catch (e: Exception) {
        Log.e(TAG, "Error saving script ${scriptName} to ${scriptFile.path}", e)
      }
    }
  }

  private fun updateSkillInDataStore(oldName: String, updatedSkill: Skill) {
    val allSkills = dataStoreRepository.getAllSkills()
    val updatedList = allSkills.map { if (it.name == oldName) updatedSkill else it }
    dataStoreRepository.setSkills(updatedList)
  }

  private fun getSkillDestinationDir(originalImportDirName: String): File {
    val normalizedDirName = originalImportDirName.replace("\\s+".toRegex(), "-")
    val newImportDirName = "skills/${normalizedDirName}"
    return context.filesDir.resolve(newImportDirName)
  }
}

fun getDisplayName(context: Context, uri: Uri): String {
  var name = ""
  try {
    context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
      val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
      if (nameIndex != -1 && cursor.moveToFirst()) {
        name = cursor.getString(nameIndex)
      }
    }
  } catch (e: Exception) {
    // Ignore
  }
  return name.ifEmpty { uri.path?.substringAfterLast('/') ?: "Unknown" }
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
