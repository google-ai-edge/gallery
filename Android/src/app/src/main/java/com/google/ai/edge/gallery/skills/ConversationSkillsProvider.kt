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

import com.google.ai.edge.gallery.proto.Skill

/**
 * [SkillsProvider] decorator that layers conversation-scoped slash-activated skill names on top of
 * a base [SkillsProvider] without mutating global skill selection state.
 */
class ConversationSkillsProvider(private val baseProvider: SkillsProvider) : SkillsProvider {

  private val lock = Any()
  private val activatedSkillNames = linkedSetOf<String>()

  /**
   * Activates [skillNames] for the current conversation without modifying persisted selection
   * state.
   *
   * @return `true` if at least one newly normalized skill name was added.
   */
  fun activateSkills(skillNames: List<String>): Boolean {
    if (skillNames.isEmpty()) {
      return false
    }
    synchronized(lock) {
      var addedNewSkill = false
      for (skillName in skillNames) {
        val normalized = SlashSkillMatcher.normalizedName(skillName)
        if (normalized.isNotEmpty() && activatedSkillNames.add(normalized)) {
          addedNewSkill = true
        }
      }
      return addedNewSkill
    }
  }

  /** Clears all conversation-scoped skill activations, restoring global defaults. */
  fun clearActivatedSkills() {
    synchronized(lock) { activatedSkillNames.clear() }
  }

  /** Returns whether any conversation-scoped skills are currently activated. */
  val hasActivatedSkills: Boolean
    get() = synchronized(lock) { activatedSkillNames.isNotEmpty() }

  override suspend fun loadSkills(defaultDisabledSkills: Set<String>) {
    baseProvider.loadSkills(defaultDisabledSkills)
  }

  override suspend fun getAvailableSkills(): List<Skill> {
    val globallyEnabled = baseProvider.getAvailableSkills()
    val extraNames = synchronized(lock) { activatedSkillNames.toList() }
    if (extraNames.isEmpty()) {
      return globallyEnabled
    }

    val seenNormalizedNames =
      globallyEnabled.mapTo(hashSetOf()) { SlashSkillMatcher.normalizedName(it.name) }
    val merged = globallyEnabled.toMutableList()
    val allSkillsByNormalizedName =
      baseProvider.getAllSkills().associateBy { SlashSkillMatcher.normalizedName(it.name) }

    for (extraName in extraNames) {
      if (extraName !in seenNormalizedNames) {
        val extraSkill =
          allSkillsByNormalizedName[extraName]
            ?: baseProvider.loadSkill(skillName = extraName, includeDisabled = true)
        if (extraSkill != null && seenNormalizedNames.add(extraName)) {
          merged.add(extraSkill)
        }
      }
    }
    return merged
  }

  override suspend fun getAllSkills(): List<Skill> = baseProvider.getAllSkills()

  override suspend fun loadSkill(skillName: String): Skill? {
    baseProvider.loadSkill(skillName)?.let {
      return it
    }
    val normalizedTarget = SlashSkillMatcher.normalizedName(skillName)
    val isActivated = synchronized(lock) { normalizedTarget in activatedSkillNames }
    if (!isActivated) {
      return null
    }
    return baseProvider.loadSkill(skillName = skillName, includeDisabled = true)
  }

  override suspend fun loadSkill(skillName: String, includeDisabled: Boolean): Skill? {
    return baseProvider.loadSkill(skillName = skillName, includeDisabled = includeDisabled)
  }
}
