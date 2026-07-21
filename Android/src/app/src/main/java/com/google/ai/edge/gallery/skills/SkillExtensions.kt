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

import com.google.ai.edge.gallery.common.LOCAL_URL_BASE
import com.google.ai.edge.gallery.proto.Skill

const val SKILL_INSTRUCTIONS_TEMPLATE = "---\nname: %s\ndescription: %s\n---\n\n%s"

fun Skill.getSkillContent(): String {
  return SKILL_INSTRUCTIONS_TEMPLATE.format(name, description, instructions)
}

fun Skill.getJsSkillUrl(scriptName: String): String? {
  var baseUrl = ""
  // Construct a local URL for imported skill and built-in skills.
  if (importDirName.isNotEmpty()) {
    baseUrl = "$LOCAL_URL_BASE/$importDirName"
  }
  // Use skill.skillUrl if set.
  else if (skillUrl.isNotEmpty()) {
    baseUrl = skillUrl
  }
  if (baseUrl.isEmpty()) return null
  return "$baseUrl/scripts/$scriptName"
}

fun Skill.getJsSkillWebviewUrl(url: String): String {
  if (url.startsWith("http")) return url
  var baseUrl = ""
  // Construct a local URL for imported skill.
  if (importDirName.isNotEmpty()) {
    baseUrl = "$LOCAL_URL_BASE/$importDirName"
  }
  // Use skill.skillUrl if set.
  else if (skillUrl.isNotEmpty()) {
    baseUrl = skillUrl
  }
  if (baseUrl.isEmpty()) return url
  return "$baseUrl/assets/$url"
}
