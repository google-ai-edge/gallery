package com.google.ai.edge.gallery.customtasks.agentchat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SkillManagerViewModelTest {
  @Test
  fun resolveSkillUrl_convertsGithubTreeUrlToRawSkillMdUrl() {
    val resolved =
      resolveSkillUrl(
        "https://github.com/google-ai-edge/gallery/tree/main/skills/featured/restaurant-roulette"
      )

    assertEquals(
      "https://raw.githubusercontent.com/google-ai-edge/gallery/main/skills/featured/restaurant-roulette",
      resolved.baseUrl,
    )
    assertEquals(
      "https://raw.githubusercontent.com/google-ai-edge/gallery/main/skills/featured/restaurant-roulette/SKILL.md",
      resolved.skillMdUrl,
    )
  }

  @Test
  fun resolveSkillUrl_keepsRawSkillMdUrlStable() {
    val resolved =
      resolveSkillUrl(
        "https://raw.githubusercontent.com/google-ai-edge/gallery/refs/heads/main/skills/featured/restaurant-roulette/SKILL.md"
      )

    assertEquals(
      "https://raw.githubusercontent.com/google-ai-edge/gallery/refs/heads/main/skills/featured/restaurant-roulette",
      resolved.baseUrl,
    )
    assertEquals(
      "https://raw.githubusercontent.com/google-ai-edge/gallery/refs/heads/main/skills/featured/restaurant-roulette/SKILL.md",
      resolved.skillMdUrl,
    )
  }

  @Test
  fun resolveSkillUrl_keepsGithubPagesSkillMdUrlStable() {
    val resolved = resolveSkillUrl("https://orgname.github.io/skill-name/SKILL.md")

    assertEquals("https://orgname.github.io/skill-name", resolved.baseUrl)
    assertEquals("https://orgname.github.io/skill-name/SKILL.md", resolved.skillMdUrl)
  }

  @Test
  fun isLikelyHtmlResponse_detectsHtmlFromContentTypeAndBody() {
    assertTrue(isLikelyHtmlResponse("text/html; charset=utf-8", "---"))
    assertTrue(isLikelyHtmlResponse("text/plain", "<!DOCTYPE html><html></html>"))
    assertFalse(isLikelyHtmlResponse("text/plain", "---\nname: sample\n---\ntext"))
  }

  @Test
  fun hasUnsupportedJsSkillHost_matchesGithubAndRawGithubHostsOnly() {
    assertTrue(hasUnsupportedJsSkillHost("https://github.com/google-ai-edge/gallery/tree/main/foo"))
    assertTrue(
      hasUnsupportedJsSkillHost(
        "https://raw.githubusercontent.com/google-ai-edge/gallery/main/foo"
      )
    )
    assertFalse(hasUnsupportedJsSkillHost("https://google-ai-edge.github.io/gallery/skills/foo"))
  }

  @Test
  fun usesRunJs_detectsJavascriptSkills() {
    assertTrue(usesRunJs("Call the `run_js` tool with the following exact parameters"))
    assertFalse(usesRunJs("Call the wikipedia tool"))
  }
}
