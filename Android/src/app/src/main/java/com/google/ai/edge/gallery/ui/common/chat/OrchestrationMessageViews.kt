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

package com.google.ai.edge.gallery.ui.common.chat

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.HourglassEmpty
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.ai.edge.gallery.orchestration.StepStatus

/** Renders an orchestration plan as a collapsible card in the chat. */
@Composable
fun MessageBodyOrchestrationPlan(message: ChatMessageOrchestrationPlan) {
  var expanded by remember { mutableStateOf(true) }
  val plan = message.plan

  Column(
    modifier =
      Modifier.fillMaxWidth()
        .clip(RoundedCornerShape(12.dp))
        .background(MaterialTheme.colorScheme.surfaceContainerLow)
        .animateContentSize()
        .padding(12.dp)
  ) {
    // Header
    Row(
      modifier = Modifier.fillMaxWidth().clickable { expanded = !expanded },
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.SpaceBetween,
    ) {
      Row(verticalAlignment = Alignment.CenterVertically) {
        if (message.inProgress) {
          CircularProgressIndicator(
            modifier = Modifier.size(16.dp),
            strokeWidth = 2.dp,
            color = MaterialTheme.colorScheme.primary,
          )
          Spacer(modifier = Modifier.width(8.dp))
        }
        Text(
          text = "Plan${if (message.iteration > 1) " (iteration ${message.iteration})" else ""}",
          style = MaterialTheme.typography.titleSmall,
          fontWeight = FontWeight.SemiBold,
        )
      }
      Icon(
        imageVector = if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
        contentDescription = if (expanded) "Collapse" else "Expand",
        modifier = Modifier.size(20.dp),
        tint = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }

    // Reasoning
    if (expanded && plan.reasoning.isNotBlank()) {
      Spacer(modifier = Modifier.height(4.dp))
      Text(
        text = plan.reasoning,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }

    // Steps
    if (expanded) {
      Spacer(modifier = Modifier.height(8.dp))
      for (step in plan.steps) {
        val status = message.stepStatuses[step.id] ?: StepStatus.PENDING
        Row(
          modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
          verticalAlignment = Alignment.CenterVertically,
        ) {
          // Status icon
          when (status) {
            StepStatus.PENDING ->
              Icon(
                Icons.Rounded.HourglassEmpty,
                contentDescription = "Pending",
                modifier = Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
              )
            StepStatus.RUNNING ->
              CircularProgressIndicator(
                modifier = Modifier.size(16.dp),
                strokeWidth = 2.dp,
                color = MaterialTheme.colorScheme.primary,
              )
            StepStatus.COMPLETED ->
              Icon(
                Icons.Rounded.Check,
                contentDescription = "Completed",
                modifier = Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.primary,
              )
            StepStatus.FAILED ->
              Icon(
                Icons.Rounded.Close,
                contentDescription = "Failed",
                modifier = Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.error,
              )
            StepStatus.SKIPPED ->
              Icon(
                Icons.Rounded.SkipNext,
                contentDescription = "Skipped",
                modifier = Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
              )
          }

          Spacer(modifier = Modifier.width(8.dp))

          Column {
            Text(
              text = step.description,
              style = MaterialTheme.typography.bodySmall,
              fontWeight = FontWeight.Medium,
            )
            if (step.skillName != null) {
              Text(
                text = step.skillName,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
              )
            }
          }
        }
      }
    }
  }
}

/** Renders an orchestration evaluation result as a card in the chat. */
@Composable
fun MessageBodyOrchestrationEvaluation(message: ChatMessageOrchestrationEvaluation) {
  val eval = message.evaluation

  Column(
    modifier =
      Modifier.fillMaxWidth()
        .clip(RoundedCornerShape(12.dp))
        .background(
          if (eval.goalAchieved) MaterialTheme.colorScheme.primaryContainer
          else MaterialTheme.colorScheme.errorContainer
        )
        .padding(12.dp)
  ) {
    Text(
      text =
        if (eval.goalAchieved) "Goal achieved"
        else "Evaluation (iteration ${message.iteration})",
      style = MaterialTheme.typography.titleSmall,
      fontWeight = FontWeight.SemiBold,
      color =
        if (eval.goalAchieved) MaterialTheme.colorScheme.onPrimaryContainer
        else MaterialTheme.colorScheme.onErrorContainer,
    )

    if (eval.assessment.isNotBlank()) {
      Spacer(modifier = Modifier.height(4.dp))
      Text(
        text = eval.assessment,
        style = MaterialTheme.typography.bodySmall,
        color =
          if (eval.goalAchieved) MaterialTheme.colorScheme.onPrimaryContainer
          else MaterialTheme.colorScheme.onErrorContainer,
      )
    }

    if (eval.missingItems.isNotEmpty()) {
      Spacer(modifier = Modifier.height(6.dp))
      Text(
        text = "Missing:",
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onErrorContainer,
      )
      for (item in eval.missingItems) {
        Text(
          text = "- $item",
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onErrorContainer,
        )
      }
    }

    if (eval.shouldReplan) {
      Spacer(modifier = Modifier.height(4.dp))
      Text(
        text = "Re-planning...",
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.Medium,
        color = MaterialTheme.colorScheme.onErrorContainer,
      )
    }
  }
}
