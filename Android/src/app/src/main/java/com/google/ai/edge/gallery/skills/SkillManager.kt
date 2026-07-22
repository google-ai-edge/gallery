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

package com.google.ai.edge.gallery.skills

import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import com.google.ai.edge.gallery.GalleryEvent
import com.google.ai.edge.gallery.common.getJsonResponse
import com.google.ai.edge.gallery.data.AllowedSkill
import com.google.ai.edge.gallery.data.DataStoreRepository
import com.google.ai.edge.gallery.data.SkillAllowlist
import com.google.ai.edge.gallery.firebaseAnalytics
import com.google.ai.edge.gallery.proto.Skill
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStreamReader
import java.net.URL
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val TAG = "AGSkillManager"

private const val SKILL_ALLOWLIST_URL = ""

enum class SkillSource(val sourceName: String) {
  BUILTIN("builtin"),
  FEATURED("featured"),
  REMOTE_URL("remote_url"),
  LOCAL_IMPORT("local_import"),
  UNKNOWN("unknown"),
}

enum class SkillAction(val value: String) {
  ADD("add"),
  DELETE("delete"),
  ENABLE("enable"),
  DISABLE("disable"),
  ENABLE_ALL("enable_all"),
  DISABLE_ALL("disable_all"),
}

/**
 * Manages skills backend logic including discovery, loading, DataStore storage, and file
 * operations.
 */
@Singleton
class SkillManager
@Inject
constructor(
  val dataStoreRepository: DataStoreRepository,
  @ApplicationContext private val context: Context,
) : SkillsProvider {
  private val _skills = MutableStateFlow<List<Skill>>(emptyList())
  val skills: StateFlow<List<Skill>> = _skills.asStateFlow()
  var skillLoaded = false
  private val coroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

  /**
   * Loads all skills from [DataStoreRepository] and merges them with built-in asset skills.
   * Performs disk reads on [kotlinx.coroutines.Dispatchers.IO] and updates [skills] when finished.
   */
  override suspend fun loadSkills(defaultDisabledSkills: Set<String>) {
    if (!skillLoaded) {
      withContext(Dispatchers.IO) {
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
        val builtInSelectionMap = dataStoreBuiltInSkills.associate {
          it.name to Pair(it.selected, it.userModifiedSelection)
        }
        Log.d(TAG, "data store built-in skills selection map: $builtInSelectionMap")

        // 3. Read and parse SKILL.md files from assets/skills directories.
        val builtInSkills = loadBuiltInSkills(context, builtInSelectionMap, defaultDisabledSkills)
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

        // 6. Update state with the final set of skills.
        _skills.value = finalSkills

        skillLoaded = true
      }
    }
  }

  /**
   * Fetches the featured skill allowlist from [SKILL_ALLOWLIST_URL].
   *
   * @return A list of [AllowedSkill] entries from the remote allowlist, or an empty list if
   *   unconfigured.
   */
  suspend fun loadSkillAllowlist(): List<AllowedSkill> {
    return withContext(Dispatchers.IO) {
      if (SKILL_ALLOWLIST_URL.isEmpty()) {
        emptyList()
      } else {
        val url = SKILL_ALLOWLIST_URL
        Log.d(TAG, "Fetching skill allowlist from: $url")
        val result =
          getJsonResponse<SkillAllowlist>(url)
            ?: throw IOException("Failed to fetch or parse JSON from $url")

        val allowlist = result.jsonObj
        Log.d(TAG, "Successfully loaded ${allowlist.featuredSkills.size} featured skills.")
        allowlist.featuredSkills
      }
    }
  }

  /**
   * Fetches, parses, and validates a remote skill definition (`SKILL.md`) given its base [url].
   * Automatically adds the parsed skill to DataStore and memory upon validation.
   *
   * @param url The URL pointing to the skill directory or `SKILL.md`.
   * @param featuredSkills Optional list of featured skills for analytics tagging.
   * @return The newly added [Skill] proto.
   */
  suspend fun addSkillFromUrl(
    url: String,
    featuredSkills: List<AllowedSkill> = emptyList(),
  ): Skill {
    return withContext(Dispatchers.IO) {
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
          throw IOException("Failed to fetch SKILL.md: ${e.message}", e)
        }

      if (mdContent.isEmpty()) {
        throw IllegalArgumentException("SKILL.md is empty at $skillMdUrl")
      }

      // 3. If it exists, read and convert it to proto.
      val (skillProto, errors) =
        convertSkillMdToProto(mdContent, builtIn = false, selected = true, skillUrl = normalizedUrl)

      // 4. If conversion failed, report error.
      if (errors.isNotEmpty()) {
        throw IllegalArgumentException(
          "Error parsing SKILL.md from $skillMdUrl: ${errors.joinToString(", ")}"
        )
      }

      val skill =
        skillProto ?: throw IllegalArgumentException("Error converting SKILL.md to proto.")

      // 5. Check if the name already exists. If so, report error.
      if (_skills.value.any { curSkill -> curSkill.name == skill.name }) {
        throw IllegalArgumentException("A skill with the name '${skill.name}' already exists.")
      }

      // 6. Add to state and data store.
      addSkill(skill = skill, addToDataStore = true, featuredSkills = featuredSkills)
      Log.d(TAG, "Successfully added skill from URL: ${skill.name}")
      skill
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
    return _skills.value.any { it.builtIn && it.name == importedSkillName }
  }

  /**
   * Imports, parses, and copies a local skill directory from [directoryUri] into internal storage.
   *
   * @param directoryUri The tree URI of the selected skill directory.
   * @param featuredSkills Optional list of featured skills for analytics tagging.
   * @return The imported [Skill] proto containing its local internal import path.
   */
  suspend fun addSkillFromLocalImport(
    directoryUri: Uri,
    featuredSkills: List<AllowedSkill> = emptyList(),
  ): Skill {
    return withContext(Dispatchers.IO) {
      Log.d(TAG, "Validating skill from directory URI: $directoryUri")

      // Get the DocumentFile representing the selected directory
      val rootFile = DocumentFile.fromTreeUri(context, directoryUri)

      // Find the SKILL.md file within that directory
      val skillMdFile = rootFile?.findFile("SKILL.md")

      if (skillMdFile == null || !skillMdFile.exists()) {
        throw FileNotFoundException("SKILL.md not found in the selected directory.")
      }

      // Read the content using the correctly resolved URI
      val mdContent =
        try {
          context.contentResolver.openInputStream(skillMdFile.uri)?.use { inputStream ->
            inputStream.bufferedReader().use { it.readText() }
          }
        } catch (e: Exception) {
          Log.e(TAG, "Error reading SKILL.md", e)
          throw IOException("Failed to read SKILL.md: ${e.message}", e)
        } ?: ""

      val (skillProto, errors) = convertSkillMdToProto(mdContent, builtIn = false, selected = true)

      if (errors.isNotEmpty()) {
        throw IllegalArgumentException("Error parsing SKILL.md: ${errors.joinToString(", ")}")
      }

      val parsedSkill =
        skillProto ?: throw IllegalArgumentException("Unknown error during SKILL.md conversion.")

      // Successfully parsed the skill. Add the directory name.
      val originalImportDirName = getDisplayName(context, directoryUri)
      val destDir = getSkillDestinationDir(originalImportDirName)
      val newImportDirName = destDir.relativeTo(context.filesDir).path

      // Create the destination directory.
      if (destDir.exists()) {
        Log.d(TAG, "Destination directory already exists, deleting: ${destDir.path}")
        deleteSkill(name = parsedSkill.name, featuredSkills = featuredSkills)
      }
      if (!destDir.exists()) {
        destDir.mkdirs()
      }

      // Check if the skill already exists.
      if (_skills.value.any { curSkill -> curSkill.name == parsedSkill.name }) {
        throw Exception("A skill with the name '${parsedSkill.name}' already exists.")
      }

      val sourceDocumentFile = DocumentFile.fromTreeUri(context, directoryUri)
      if (sourceDocumentFile == null) {
        Log.e(TAG, "Failed to get DocumentFile from URI: $directoryUri")
        throw Exception("Failed to access the selected directory.")
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
      val skillWithDir = parsedSkill.toBuilder().setImportDirName(newImportDirName).build()
      addSkill(skill = skillWithDir, addToDataStore = true, featuredSkills = featuredSkills)
      Log.d(TAG, "Successfully added skill from local import: ${skillWithDir.name}")
      skillWithDir
    }
  }

  /**
   * Adds [skill] to the in-memory state flow and optionally persists it to [DataStoreRepository].
   *
   * @param skill The skill to add.
   * @param addToDataStore Whether to save the skill to DataStore asynchronously.
   * @param featuredSkills Optional list of featured skills for analytics tagging.
   */
  fun addSkill(
    skill: Skill,
    addToDataStore: Boolean,
    featuredSkills: List<AllowedSkill> = emptyList(),
  ) {
    Log.d(TAG, "Adding skill: $skill")

    // Update state.
    _skills.update { currentSkills ->
      if (skill.builtIn) {
        currentSkills + skill
      } else {
        val firstCustomIndex = currentSkills.indexOfFirst { !it.builtIn }
        if (firstCustomIndex == -1) {
          currentSkills + skill
        } else {
          currentSkills.toMutableList().apply { add(firstCustomIndex, skill) }
        }
      }
    }

    firebaseAnalytics?.logEvent(
      GalleryEvent.SKILL_MANAGEMENT.id,
      getSkillLoggingParams(skill, featuredSkills).apply {
        putString("action", SkillAction.ADD.value)
      },
    )

    if (addToDataStore) {
      // Add skill to data store.
      coroutineScope.launch { dataStoreRepository.addSkill(skill) }
    }
  }

  /**
   * Removes a single skill by [name] from in-memory state, disk storage, and DataStore.
   *
   * @param name The unique name of the skill to delete.
   * @param featuredSkills Optional list of featured skills for analytics tagging.
   */
  fun deleteSkill(name: String, featuredSkills: List<AllowedSkill> = emptyList()) {
    // Locate the skill to be deleted.
    val skill = _skills.value.firstOrNull { it.name == name } ?: return

    val loggingParams = getSkillLoggingParams(skill, featuredSkills)
    Log.d(
      TAG,
      "Analytics: skill_management, action=${SkillAction.DELETE.value}, params=$loggingParams",
    )
    firebaseAnalytics?.logEvent(
      GalleryEvent.SKILL_MANAGEMENT.id,
      loggingParams.apply { putString("action", SkillAction.DELETE.value) },
    )

    // Update state.
    _skills.update { currentSkills -> currentSkills.filter { it.name != name } }

    coroutineScope.launch {
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

  /**
   * Removes multiple skills specified by [names] from in-memory state, disk storage, and DataStore.
   *
   * @param names A set of skill names to delete.
   * @param featuredSkills Optional list of featured skills for analytics tagging.
   */
  suspend fun deleteSkills(names: Set<String>, featuredSkills: List<AllowedSkill> = emptyList()) {
    val skillsToDelete = _skills.value.filter { names.contains(it.name) }
    if (skillsToDelete.isEmpty()) {
      return
    }

    for (skill in skillsToDelete) {
      val loggingParams = getSkillLoggingParams(skill, featuredSkills)
      Log.d(
        TAG,
        "Analytics: skill_management, action=${SkillAction.DELETE.value}, params=$loggingParams",
      )
      firebaseAnalytics?.logEvent(
        GalleryEvent.SKILL_MANAGEMENT.id,
        loggingParams.apply { putString("action", SkillAction.DELETE.value) },
      )
    }

    // Update state.
    _skills.update { currentSkills -> currentSkills.filter { !names.contains(it.name) } }

    withContext(Dispatchers.IO) {
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

  /**
   * Updates the selection (enabled/disabled) state of [skill] in memory and DataStore.
   *
   * @param skill The skill to modify.
   * @param selected True if the skill should be enabled, false otherwise.
   * @param featuredSkills Optional list of featured skills for analytics tagging.
   */
  fun setSkillSelected(
    skill: Skill,
    selected: Boolean,
    featuredSkills: List<AllowedSkill> = emptyList(),
  ) {
    // Update state.
    val updatedSkill = skill.toBuilder().setSelected(selected).build()

    firebaseAnalytics?.logEvent(
      GalleryEvent.SKILL_MANAGEMENT.id,
      getSkillLoggingParams(skill, featuredSkills).apply {
        putString("action", if (selected) SkillAction.ENABLE.value else SkillAction.DISABLE.value)
      },
    )

    _skills.update { currentSkills ->
      currentSkills.map { curSkill ->
        if (curSkill.name == skill.name) {
          updatedSkill
        } else {
          curSkill
        }
      }
    }

    // Update data store.
    coroutineScope.launch { dataStoreRepository.setSkillSelected(skill, selected) }
  }

  /**
   * Updates the selection (enabled/disabled) state of all skills in memory and DataStore.
   *
   * @param selected True to enable all skills, false to disable all skills.
   */
  fun setAllSkillsSelected(selected: Boolean) {
    // Update state.
    _skills.update { currentSkills ->
      currentSkills.map { skill -> skill.toBuilder().setSelected(selected).build() }
    }

    Log.d(
      TAG,
      "Analytics: skill_management, action=${if (selected) SkillAction.ENABLE_ALL.value else SkillAction.DISABLE_ALL.value}",
    )
    firebaseAnalytics?.logEvent(
      GalleryEvent.SKILL_MANAGEMENT.id,
      Bundle().apply {
        putString(
          "action",
          if (selected) SkillAction.ENABLE_ALL.value else SkillAction.DISABLE_ALL.value,
        )
      },
    )

    // Update data store.
    coroutineScope.launch { dataStoreRepository.setAllSkillsSelected(selected) }
  }

  /** Returns all skills currently marked as selected (enabled). */
  fun getSelectedSkills(): List<Skill> {
    return _skills.value.filter { it.selected }
  }

  private fun matchesSkillName(actualName: String, targetName: String): Boolean {
    val normalizedActual = actualName.trim().lowercase().replace("[\\s_]+".toRegex(), "-")
    val normalizedTarget = targetName.trim().lowercase().replace("[\\s_]+".toRegex(), "-")
    return normalizedActual == normalizedTarget ||
      actualName.equals(targetName.trim(), ignoreCase = true)
  }

  /** Looks up a skill by [name] flexibly matching hyphens, underscores, and case. */
  fun getSkill(name: String): Skill? {
    return _skills.value.firstOrNull { matchesSkillName(it.name, name) }
  }

  override suspend fun getAvailableSkills(): List<Skill> {
    return getSelectedSkills()
  }

  override suspend fun loadSkill(skillName: String): Skill? {
    return getSelectedSkills().find { matchesSkillName(it.name, skillName) }
  }

  /**
   * Returns a formatted multi-line summary of all currently selected skill names and descriptions.
   */
  fun getSelectedSkillsNamesAndDescriptions(): String {
    return this.getSelectedSkills().joinToString("\n") { skill ->
      "- ${skill.name}: ${skill.description}"
    }
  }

  /** Saves or updates a custom skill. */
  suspend fun saveSkillEdit(
    index: Int,
    name: String,
    description: String,
    instructions: String,
    scriptsContent: Map<String, String>,
    featuredSkills: List<AllowedSkill> = emptyList(),
  ): Skill {
    return withContext(Dispatchers.IO) {
      Log.d(TAG, "saveSkillEdit: $name")

      val currentSkills = _skills.value
      val isNewSkill = index < 0 || index >= currentSkills.size

      if (isNewSkill) {
        Log.d(TAG, "Saving new skill: $name")

        // Check for name conflict
        if (currentSkills.any { it.name == name }) {
          throw IllegalArgumentException("A skill with the name '${name}' already exists.")
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
        addSkill(newSkill, addToDataStore = true, featuredSkills = featuredSkills)
        newSkill
      } else {
        Log.d(TAG, "Saving skill edit: $name")

        // Editing existing skill
        val existingSkill = currentSkills[index]
        val oldName = existingSkill.name
        val normalizedNewName = name.replace("\\s+".toRegex(), "-")
        val newSkillDestDir = context.filesDir.resolve("skills/${normalizedNewName}")
        val newScriptDestDir = File(newSkillDestDir, "scripts")
        val newSkillMdFile = File(newSkillDestDir, "SKILL.md")

        if (existingSkill.builtIn) {
          throw IllegalArgumentException("Cannot edit built-in skills.")
        }

        var updatedImportDirName = existingSkill.importDirName

        if (oldName != normalizedNewName) {
          Log.d(TAG, "Renaming skill from $oldName to $normalizedNewName")

          // Check for name conflict with the new name
          if (currentSkills.any { it.name == normalizedNewName }) {
            throw IllegalArgumentException(
              "A skill with the name '${normalizedNewName}' already exists."
            )
          }

          val oldSkillDestDir = context.filesDir.resolve(existingSkill.importDirName)
          if (oldSkillDestDir.exists()) {
            Log.d(TAG, "Renaming directory from ${oldSkillDestDir.path} to ${newSkillDestDir.path}")
            if (!oldSkillDestDir.renameTo(newSkillDestDir)) {
              throw IOException(
                "Failed to rename skill directory from ${oldSkillDestDir.name} to ${newSkillDestDir.name}."
              )
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
        _skills.update { skillsList ->
          skillsList.mapIndexed { i, s -> if (i == index) updatedSkill else s }
        }

        // Update data store
        updateSkillInDataStore(oldName, updatedSkill)
        updatedSkill
      }
    }
  }

  /** Loads the content of skill scripts from the local file system. */
  suspend fun loadSkillScriptsContent(skill: Skill): Map<String, String> {
    return withContext(Dispatchers.IO) {
      if (skill.importDirName.isEmpty()) {
        Log.d(TAG, "Skill ${skill.name} has no import directory, returning empty scripts.")
        return@withContext emptyMap()
      }

      val skillDir = context.filesDir.resolve(skill.importDirName)
      val scriptDir = File(skillDir, "scripts")

      if (!scriptDir.exists() || !scriptDir.isDirectory) {
        Log.w(TAG, "Script directory not found for skill ${skill.name}: ${scriptDir.path}")
        return@withContext emptyMap()
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
      scriptsContent
    }
  }

  /** Deletes a specific script file associated with a locally imported skill. */
  fun deleteSkillScript(skill: Skill, scriptName: String) {
    if (skill.importDirName.isEmpty()) {
      Log.d(TAG, "Skill ${skill.name} is not locally imported, cannot delete script.")
      return
    }

    coroutineScope.launch {
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
    return _skills.value.firstOrNull { it.name == skillName }?.selected == true
  }

  /**
   * Determines the origin [SkillSource] (builtin, featured, remote URL, local import) of [skill].
   */
  fun getSkillSource(skill: Skill, featuredSkills: List<AllowedSkill> = emptyList()): SkillSource {
    val isFeatured =
      skill.skillUrl.isNotEmpty() && featuredSkills.any { it.skillUrl == skill.skillUrl }
    return when {
      skill.builtIn -> SkillSource.BUILTIN
      isFeatured -> SkillSource.FEATURED
      skill.skillUrl.isNotEmpty() -> SkillSource.REMOTE_URL
      skill.importDirName.isNotEmpty() -> SkillSource.LOCAL_IMPORT
      else -> SkillSource.UNKNOWN
    }
  }

  /**
   * Generates a short 4-character hash to act as a stable ID. This solves the 100-character limit
   * for list logging in GA4 AND allows us to distinguish between different custom skills in
   * reports. Note: When we migrate to Cleancut or a similar service that doesn't have severe
   * character limits, we can drop the human-readable skill_name from setup events and rely purely
   * on this hash ID.
   */
  fun getSkillShortId(skill: Skill, featuredSkills: List<AllowedSkill> = emptyList()): String {
    val source = getSkillSource(skill, featuredSkills)
    val identifier =
      when (source) {
        SkillSource.BUILTIN,
        SkillSource.FEATURED -> skill.name
        SkillSource.LOCAL_IMPORT -> skill.importDirName
        else -> skill.skillUrl
      }
    if (identifier.isEmpty()) return "xxxx"

    val prefix =
      when (source) {
        SkillSource.BUILTIN -> "b_"
        SkillSource.FEATURED -> "f_"
        SkillSource.LOCAL_IMPORT -> "l_"
        else -> "c_"
      }

    return try {
      val digest = MessageDigest.getInstance("SHA-256")
      val hashBytes = digest.digest(identifier.toByteArray())
      val hexString = hashBytes.joinToString("") { "%02x".format(it) }
      prefix + hexString.take(4)
    } catch (e: Exception) {
      prefix + "fail"
    }
  }

  private fun getSkillLoggingParams(
    skill: Skill,
    featuredSkills: List<AllowedSkill> = emptyList(),
  ): Bundle {
    val source = getSkillSource(skill, featuredSkills)
    val skillName =
      if (source == SkillSource.BUILTIN || source == SkillSource.FEATURED) skill.name
      else "custom_skill"
    return Bundle().apply {
      putString("source", source.sourceName)
      putString("skill_name", skillName)
      putString("skill_id", getSkillShortId(skill, featuredSkills))
    }
  }

  private fun getSkillDestinationDir(originalImportDirName: String): File {
    val normalizedDirName = originalImportDirName.replace("\\s+".toRegex(), "-")
    val newImportDirName = "skills/${normalizedDirName}"
    return context.filesDir.resolve(newImportDirName)
  }

  /**
   * Writes the `SKILL.md` content to disk. Note: Performs blocking disk I/O; must be called within
   * a [kotlinx.coroutines.Dispatchers.IO] context.
   */
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

  /**
   * Saves script files to the skill's script directory on disk. Note: Performs blocking disk I/O;
   * must be called within a [kotlinx.coroutines.Dispatchers.IO] context.
   */
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

  /**
   * Updates an existing skill entry in the DataStore repository. Note: Performs blocking DataStore
   * reads/writes; must be called within a [kotlinx.coroutines.Dispatchers.IO] context.
   */
  private fun updateSkillInDataStore(oldName: String, updatedSkill: Skill) {
    val allSkills = dataStoreRepository.getAllSkills()
    val updatedList = allSkills.map { if (it.name == oldName) updatedSkill else it }
    dataStoreRepository.setSkills(updatedList)
  }

  companion object {
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
     * @return A [Pair] containing the parsed [Skill] proto (or null if errors occurred) and a list
     *   of error messages.
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

    /**
     * Reads and parses SKILL.md files from assets/skills directories to load all built-in skills.
     *
     * @param context The application context.
     * @param builtInSelectionMap A map of skill names to their selection state and whether they
     *   were user-modified.
     * @return A list of [Skill] protos representing the built-in skills.
     */
    fun loadBuiltInSkills(
      context: Context,
      builtInSelectionMap: Map<String, Pair<Boolean, Boolean>> = emptyMap(),
      defaultDisabledSkills: Set<String> = emptySet(),
    ): List<Skill> {
      val builtInSkills = mutableListOf<Skill>()
      try {
        val skillAssetDirs = context.assets.list("skills") ?: emptyArray()
        val skillDirNames = skillAssetDirs.map { it.substringBefore('/') }.distinct()
        for (dirName in skillDirNames) {
          val skillMdPath = "skills/$dirName/SKILL.md"
          try {
            context.assets.open(skillMdPath).use { inputStream ->
              val mdContent = inputStream.bufferedReader().use { it.readText() }
              val (skillProto, errors) =
                convertSkillMdToProto(
                  mdContent,
                  builtIn = true,
                  selected = true,
                  importDir = "assets/skills/$dirName",
                )
              if (errors.isNotEmpty()) {
                Log.w(TAG, "Error parsing asset skill $dirName: ${errors.joinToString(", ")}")
              } else {
                skillProto?.let {
                  // Apply the previous selection state if the user explicitly modified it,
                  // otherwise use the default selection state.
                  val defaultSelected = it.name !in defaultDisabledSkills
                  val (persistedSelected, userModified) =
                    builtInSelectionMap[it.name] ?: Pair(defaultSelected, false)
                  val selectedState = if (userModified) persistedSelected else defaultSelected
                  builtInSkills.add(
                    it
                      .toBuilder()
                      .setSelected(selectedState)
                      .setUserModifiedSelection(userModified)
                      .build()
                  )
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
      return builtInSkills
    }
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
