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
 * Active trailing `/` query parsed from user input.
 *
 * @property tokenRange Inclusive character range covering `/` and trailing query characters in the
 *   source string.
 * @property query Query text following the leading `/`.
 */
data class SlashSkillQuery(val tokenRange: IntRange, val query: String)

/**
 * Result of matching an active `/` query against available skills.
 *
 * @property query Active trailing slash query found in input.
 * @property matchedSkills Matching skills in their original input order.
 */
data class SlashSkillMatchResult(val query: SlashSkillQuery, val matchedSkills: List<Skill>)

/** Matches `/` slash-triggered skill invocations and selections against available skills. */
object SlashSkillMatcher {

  private val WHITESPACE_REGEX = Regex("\\s")

  private data class SlashToken(
    val normalizedName: String,
    val range: IntRange,
    val leadingSlashCount: Int,
  )

  /** Normalizes a skill name for case-, whitespace-, and separator-insensitive lookups. */
  fun normalizedName(name: String): String =
    name.trim().lowercase().replace(WHITESPACE_REGEX, "-").replace('_', '-')

  /**
   * Returns the active trailing `/query` token if [input] ends in a whitespace-delimited token
   * starting with one or more `/` characters and containing no additional `/` characters after the
   * leading slashes.
   */
  fun activeQuery(input: String): SlashSkillQuery? {
    val lastWhitespaceIndex = input.indexOfLast { it.isWhitespace() }
    val tokenStart = lastWhitespaceIndex + 1
    if (tokenStart >= input.length || input[tokenStart] != '/') {
      return null
    }

    var nameStart = tokenStart
    while (nameStart < input.length && input[nameStart] == '/') {
      nameStart++
    }
    val query = input.substring(nameStart)
    if (query.contains('/')) {
      return null
    }
    return SlashSkillQuery(tokenRange = tokenStart until input.length, query = query)
  }

  /**
   * Filters [skills] where [query] is a case-insensitive substring of `name` or `description`,
   * preserving the original order of [skills]. An empty [query] returns all [skills].
   */
  fun matchingSkills(query: String, skills: List<Skill>): List<Skill> {
    if (query.isEmpty()) {
      return skills
    }
    return skills.filter { skill ->
      skill.name.contains(query, ignoreCase = true) ||
        skill.description.contains(query, ignoreCase = true)
    }
  }

  /**
   * Matches any active trailing `/query` in [input] against [skills], returning `null` if [input]
   * does not have an active slash trigger.
   */
  fun match(input: String, skills: List<Skill>): SlashSkillMatchResult? {
    val active = activeQuery(input) ?: return null
    return SlashSkillMatchResult(
      query = active,
      matchedSkills = matchingSkills(query = active.query, skills = skills),
    )
  }

  /**
   * Replaces the active trailing `/query` token in [input] with `/<skill.name> `, or returns
   * [input] unchanged if there is no active slash trigger.
   */
  fun applyingSelection(skill: Skill, input: String): String {
    val active = activeQuery(input) ?: return input
    return input.replaceRange(active.tokenRange, "/${skill.name} ")
  }

  /**
   * Extracts distinct skills explicitly referenced as whitespace-delimited `/<skill-name>` tokens
   * in [input], preserving the order in which they first appear.
   *
   * Token matching uses [normalizedName] against [skills]. Tokens embedded in paths or URLs (such
   * as `https://example.com/qr-code` or `/a/b`) are ignored.
   */
  fun selectedSkills(input: String, skills: List<Skill>): List<Skill> {
    if (input.isEmpty() || skills.isEmpty()) {
      return emptyList()
    }
    val skillsByNormalizedName = linkedMapOf<String, Skill>()
    for (skill in skills) {
      skillsByNormalizedName.putIfAbsent(normalizedName(skill.name), skill)
    }

    val seenNames = hashSetOf<String>()
    val matchedSkills = mutableListOf<Skill>()
    for (token in scanSlashTokens(input)) {
      val skill = skillsByNormalizedName[token.normalizedName]
      if (skill != null && seenNames.add(token.normalizedName)) {
        matchedSkills.add(skill)
      }
    }
    return matchedSkills
  }

  /**
   * Returns inclusive character ranges in [input] for every whitespace-delimited `/<skill-name>`
   * token whose normalized name matches a normalized entry in [skillNames].
   *
   * Tokens embedded inside paths or URLs (such as `https://example.com/qr-code` or `/a/b`) are
   * ignored.
   */
  fun recognizedTokenRanges(input: String, skillNames: Set<String>): List<IntRange> {
    if (input.isEmpty() || skillNames.isEmpty()) {
      return emptyList()
    }
    val normalizedSkillNames = skillNames.mapTo(hashSetOf()) { normalizedName(it) }
    return scanSlashTokens(input).mapNotNull { token ->
      if (token.normalizedName in normalizedSkillNames) token.range else null
    }
  }

  /**
   * Reduces duplicate leading `/` characters on recognized `/<skill-name>` tokens in [input] down
   * to a single `/` (for example `//qr-code` -> `/qr-code`), while leaving single-slash skill
   * references, URLs, nested paths, and non-skill tokens unchanged.
   */
  fun normalizeSkillSlashes(input: String, skills: List<Skill>): String {
    if (input.isEmpty() || skills.isEmpty()) {
      return input
    }
    val knownSkillNames = skills.mapTo(hashSetOf()) { normalizedName(it.name) }
    val extraSlashRanges =
      scanSlashTokens(input).mapNotNull { token ->
        if (token.leadingSlashCount > 1 && token.normalizedName in knownSkillNames) {
          // Drop the extra leading slashes, keeping the final `/` before the skill name.
          token.range.first until (token.range.first + token.leadingSlashCount - 1)
        } else {
          null
        }
      }
    if (extraSlashRanges.isEmpty()) {
      return input
    }
    return buildString(input.length) {
      var cursor = 0
      for (dropRange in extraSlashRanges) {
        append(input, cursor, dropRange.first)
        cursor = dropRange.last + 1
      }
      append(input, cursor, input.length)
    }
  }

  private fun scanSlashTokens(input: String): List<SlashToken> {
    val tokens = mutableListOf<SlashToken>()
    var index = 0
    val length = input.length

    while (index < length) {
      if (input[index].isWhitespace()) {
        index++
        continue
      }

      val tokenStart = index
      while (index < length && !input[index].isWhitespace()) {
        index++
      }
      val tokenEnd = index

      if (input[tokenStart] != '/') {
        continue
      }
      var nameStart = tokenStart
      while (nameStart < tokenEnd && input[nameStart] == '/') {
        nameStart++
      }
      if (nameStart >= tokenEnd) {
        continue
      }
      val rawName = input.substring(nameStart, tokenEnd)
      if (rawName.contains('/')) {
        continue
      }
      tokens.add(
        SlashToken(
          normalizedName = normalizedName(rawName),
          range = tokenStart until tokenEnd,
          leadingSlashCount = nameStart - tokenStart,
        )
      )
    }

    return tokens
  }
}
