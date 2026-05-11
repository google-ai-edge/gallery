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

package com.google.ai.edge.gallery.ui.notifications

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import com.google.ai.edge.gallery.R
import com.google.ai.edge.gallery.notifications.NotificationScheduleManager
import com.google.ai.edge.gallery.proto.ScheduledNotification
import com.google.ai.edge.gallery.ui.theme.customColors
import dagger.hilt.android.lifecycle.HiltViewModel
import java.util.Locale
import javax.inject.Inject

@HiltViewModel
class NotificationsViewModel @Inject constructor(val scheduleManager: NotificationScheduleManager) :
  ViewModel() {
  val notifications = scheduleManager.scheduledNotifications

  fun removeNotification(id: String) {
    scheduleManager.removeNotification(id)
  }
}

/** A screen to display the list of scheduled notifications. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NotificationsScreen(
  navigateUp: () -> Unit,
  modifier: Modifier = Modifier,
  viewModel: NotificationsViewModel = hiltViewModel(),
) {
  val notifications by viewModel.notifications.collectAsState()
  var notificationToDelete by remember { mutableStateOf<ScheduledNotification?>(null) }

  val groupedNotifications by
    remember(notifications) { derivedStateOf { notifications.groupBy { it.channelName } } }
  val expandedStates = remember { mutableStateMapOf<String, Boolean>() }

  Scaffold(
    modifier = modifier,
    topBar = {
      CenterAlignedTopAppBar(
        title = {
          Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Row(
              verticalAlignment = Alignment.CenterVertically,
              horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
              Icon(
                imageVector = Icons.Rounded.Notifications,
                modifier = Modifier.size(20.dp),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurface,
              )
              Text(
                text = "${stringResource(R.string.notifications_title)} (${notifications.size})",
                color = MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.titleMedium,
              )
            }
          }
        },
        actions = {
          IconButton(onClick = { navigateUp() }) {
            Icon(
              imageVector = Icons.Rounded.Close,
              contentDescription = stringResource(R.string.cd_close_icon),
              tint = MaterialTheme.colorScheme.onSurface,
            )
          }
        },
      )
    },
  ) { innerPadding ->
    LazyColumn(
      modifier =
        Modifier.background(MaterialTheme.colorScheme.surfaceContainer)
          .fillMaxSize()
          .padding(innerPadding)
          .padding(horizontal = 16.dp),
      verticalArrangement = Arrangement.spacedBy(8.dp),
      contentPadding = PaddingValues(vertical = 16.dp),
    ) {
      if (notifications.isEmpty()) {
        item {
          Box(
            modifier = Modifier.fillMaxWidth().padding(vertical = 32.dp),
            contentAlignment = Alignment.Center,
          ) {
            Text(
              text = stringResource(R.string.notifications_empty_state),
              style = MaterialTheme.typography.bodyLarge,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
          }
        }
      } else {
        for ((channelName, list) in groupedNotifications) {
          val name = channelName.ifEmpty { "Default Channel" }
          item(key = "header_$name") {
            val isExpanded = expandedStates.getOrDefault(name, true)
            Row(
              modifier =
                Modifier.fillMaxWidth()
                  .clip(RoundedCornerShape(8.dp))
                  .clickable { expandedStates[name] = !isExpanded }
                  .padding(vertical = 8.dp, horizontal = 16.dp),
              verticalAlignment = Alignment.CenterVertically,
              horizontalArrangement = Arrangement.SpaceBetween,
            ) {
              Text(
                text = name,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
              )
              Icon(
                imageVector =
                  if (isExpanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                contentDescription = if (isExpanded) "Collapse" else "Expand",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
              )
            }
          }
          if (expandedStates.getOrDefault(name, true)) {
            items(list, key = { it.id }) { notification ->
              NotificationItem(
                notification = notification,
                onDeleteClick = { notificationToDelete = notification },
              )
            }
          }
        }
      }
    }
  }

  if (notificationToDelete != null) {
    AlertDialog(
      onDismissRequest = { notificationToDelete = null },
      title = { Text(stringResource(R.string.notifications_delete_dialog_title)) },
      text = {
        Text(
          stringResource(
            R.string.notifications_delete_dialog_content,
            notificationToDelete?.title ?: "",
          )
        )
      },
      confirmButton = {
        Button(
          onClick = {
            notificationToDelete?.let { viewModel.removeNotification(it.id) }
            notificationToDelete = null
          },
          colors =
            ButtonDefaults.buttonColors(
              containerColor = MaterialTheme.customColors.errorTextColor,
              contentColor = Color.White,
            ),
        ) {
          Text(stringResource(R.string.delete))
        }
      },
      dismissButton = {
        OutlinedButton(onClick = { notificationToDelete = null }) {
          Text(stringResource(R.string.cancel))
        }
      },
    )
  }
}

private val BUTTON_CONTENT_PADDING =
  PaddingValues(start = 12.dp, top = 2.dp, end = 12.dp, bottom = 2.dp)

@Composable
fun NotificationItem(notification: ScheduledNotification, onDeleteClick: () -> Unit) {
  Card(
    modifier = Modifier.fillMaxWidth(),
    shape = RoundedCornerShape(12.dp),
    colors = CardDefaults.cardColors(containerColor = MaterialTheme.customColors.taskCardBgColor),
  ) {
    Column(modifier = Modifier.padding(16.dp).fillMaxWidth()) {
      Text(
        text = notification.title,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Bold,
      )
      Spacer(modifier = Modifier.height(4.dp))
      Text(text = notification.message, style = MaterialTheme.typography.bodyMedium)
      Spacer(modifier = Modifier.height(8.dp))
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Bottom,
      ) {
        Column {
          val timeStr =
            String.format(Locale.getDefault(), "%02d:%02d", notification.hour, notification.minute)
          Text(
            text = stringResource(R.string.notifications_time_label, timeStr),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
          if (
            !notification.repeatDaily &&
              notification.hasYear() &&
              notification.hasMonth() &&
              notification.hasDay()
          ) {
            Spacer(modifier = Modifier.height(4.dp))
            val dateStr = "${notification.year}/${notification.month}/${notification.day}"
            Text(
              text = stringResource(R.string.notifications_date_label, dateStr),
              style = MaterialTheme.typography.labelMedium,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
          }
        }
        val repeatStr =
          if (notification.repeatDaily) {
            stringResource(R.string.notifications_repeat_daily)
          } else {
            stringResource(R.string.notifications_repeat_one_time)
          }
        Text(
          text = repeatStr,
          style = MaterialTheme.typography.labelMedium,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }

      Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.End,
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
      ) {
        OutlinedButton(
          onClick = onDeleteClick,
          modifier = Modifier.height(32.dp),
          contentPadding = BUTTON_CONTENT_PADDING,
        ) {
          Icon(
            Icons.Outlined.Delete,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(18.dp),
          )
          Text(
            stringResource(R.string.delete),
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(start = 4.dp),
          )
        }
      }
    }
  }
}
