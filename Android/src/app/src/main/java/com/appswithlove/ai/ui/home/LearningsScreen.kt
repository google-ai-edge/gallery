package com.appswithlove.ai.ui.home

import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.Smartphone
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.appswithlove.ai.R

data class LearningArticle(
  val id: Int,
  val icon: ImageVector,
  val tags: List<Tag>,
  @StringRes val titleRes: Int,
  @StringRes val sourceRes: Int,
  @StringRes val descriptionRes: Int,
  @StringRes val dateRes: Int,
  @StringRes val readTimeRes: Int,
  @StringRes val bodyRes: Int,
)

data class Tag(
  @StringRes val labelRes: Int,
  val color: Color,
)

val articles = listOf(
  LearningArticle(
    id = 0,
    icon = Icons.Rounded.Shield,
    tags = listOf(
      Tag(R.string.learnings_tag_privacy, Color(0xFF2E7D32)),
      Tag(R.string.learnings_tag_strategy, Color(0xFF6A1B9A)),
    ),
    titleRes = R.string.learnings_article0_title,
    sourceRes = R.string.learnings_article0_source,
    descriptionRes = R.string.learnings_article0_description,
    dateRes = R.string.learnings_article0_date,
    readTimeRes = R.string.learnings_article0_read_time,
    bodyRes = R.string.learnings_article0_body,
  ),
  LearningArticle(
    id = 1,
    icon = Icons.Rounded.Speed,
    tags = listOf(
      Tag(R.string.learnings_tag_framework, Color(0xFF1565C0)),
      Tag(R.string.learnings_tag_official, Color(0xFF1565C0)),
    ),
    titleRes = R.string.learnings_article1_title,
    sourceRes = R.string.learnings_article1_source,
    descriptionRes = R.string.learnings_article1_description,
    dateRes = R.string.learnings_article1_date,
    readTimeRes = R.string.learnings_article1_read_time,
    bodyRes = R.string.learnings_article1_body,
  ),
  LearningArticle(
    id = 2,
    icon = Icons.Rounded.Smartphone,
    tags = listOf(
      Tag(R.string.learnings_tag_deep_dive, Color(0xFF6A1B9A)),
      Tag(R.string.learnings_tag_advanced, Color(0xFFE65100)),
    ),
    titleRes = R.string.learnings_article2_title,
    sourceRes = R.string.learnings_article2_source,
    descriptionRes = R.string.learnings_article2_description,
    dateRes = R.string.learnings_article2_date,
    readTimeRes = R.string.learnings_article2_read_time,
    bodyRes = R.string.learnings_article2_body,
  ),
)

fun getArticleById(id: Int): LearningArticle? = articles.find { it.id == id }

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun LearningsScreen(
  onArticleClick: (Int) -> Unit,
  sharedTransitionScope: SharedTransitionScope,
  animatedVisibilityScope: AnimatedVisibilityScope,
  modifier: Modifier = Modifier,
) {
  Column(
    modifier = modifier
      .fillMaxSize()
      .background(MaterialTheme.colorScheme.surfaceContainer)
      .verticalScroll(rememberScrollState())
      .padding(horizontal = 24.dp),
  ) {
    Spacer(modifier = Modifier.height(24.dp))

    Text(
      text = stringResource(R.string.learnings_screen_title),
      style = MaterialTheme.typography.headlineLarge.copy(fontWeight = FontWeight.Bold),
      color = MaterialTheme.colorScheme.onSurface,
    )

    Spacer(modifier = Modifier.height(8.dp))

    Text(
      text = stringResource(R.string.learnings_screen_subtitle),
      style = MaterialTheme.typography.bodyMedium,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    Spacer(modifier = Modifier.height(24.dp))

    articles.forEach { article ->
      LearningCard(
        article = article,
        sharedTransitionScope = sharedTransitionScope,
        animatedVisibilityScope = animatedVisibilityScope,
        onClick = { onArticleClick(article.id) },
      )
      Spacer(modifier = Modifier.height(16.dp))
    }

    Spacer(modifier = Modifier.height(8.dp))
  }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LearningCard(
  article: LearningArticle,
  sharedTransitionScope: SharedTransitionScope,
  animatedVisibilityScope: AnimatedVisibilityScope,
  onClick: () -> Unit,
) {
  with(sharedTransitionScope) {
    Card(
      shape = RoundedCornerShape(20.dp),
      colors = CardDefaults.cardColors(
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
      ),
      modifier = Modifier
        .fillMaxWidth()
        .sharedBounds(
          rememberSharedContentState(key = "article-card-${article.id}"),
          animatedVisibilityScope = animatedVisibilityScope,
          enter = fadeIn(animationSpec = tween(300)),
          exit = fadeOut(animationSpec = tween(300)),
        )
        .clickable(onClick = onClick),
    ) {
      Column(modifier = Modifier.padding(20.dp)) {
        Row(
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
          Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.surfaceContainerHighest,
            modifier = Modifier.size(40.dp),
          ) {
            Icon(
              imageVector = article.icon,
              contentDescription = null,
              modifier = Modifier
                .padding(8.dp)
                .sharedBounds(
                  rememberSharedContentState(key = "article-icon-${article.id}"),
                  animatedVisibilityScope = animatedVisibilityScope,
                ),
              tint = MaterialTheme.colorScheme.primary,
            )
          }

          FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            article.tags.forEach { tag ->
              Surface(
                shape = RoundedCornerShape(12.dp),
                color = tag.color.copy(alpha = 0.15f),
              ) {
                Text(
                  text = stringResource(tag.labelRes),
                  style = MaterialTheme.typography.labelSmall,
                  color = tag.color,
                  modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                )
              }
            }
          }
        }

        Spacer(modifier = Modifier.height(16.dp))

        Text(
          text = stringResource(article.titleRes),
          style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
          color = MaterialTheme.colorScheme.onSurface,
          modifier = Modifier.sharedBounds(
            rememberSharedContentState(key = "article-title-${article.id}"),
            animatedVisibilityScope = animatedVisibilityScope,
          ),
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
          text = stringResource(article.descriptionRes),
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          lineHeight = 18.sp,
        )

        Spacer(modifier = Modifier.height(16.dp))

        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.SpaceBetween,
          verticalAlignment = Alignment.CenterVertically,
        ) {
          Text(
            text = "${stringResource(article.dateRes)}  \u2022  ${stringResource(article.readTimeRes)}",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )

          Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.surfaceContainerHighest,
            modifier = Modifier.size(32.dp),
          ) {
            Icon(
              imageVector = Icons.AutoMirrored.Rounded.ArrowForward,
              contentDescription = null,
              modifier = Modifier.padding(6.dp),
              tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
          }
        }
      }
    }
  }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ArticleDetailScreen(
  article: LearningArticle,
  sharedTransitionScope: SharedTransitionScope,
  animatedVisibilityScope: AnimatedVisibilityScope,
  onBack: () -> Unit,
  modifier: Modifier = Modifier,
) {
  with(sharedTransitionScope) {
    Column(
      modifier = modifier
        .fillMaxSize()
        .sharedBounds(
          rememberSharedContentState(key = "article-card-${article.id}"),
          animatedVisibilityScope = animatedVisibilityScope,
          enter = fadeIn(animationSpec = tween(300)),
          exit = fadeOut(animationSpec = tween(300)),
        )
        .background(MaterialTheme.colorScheme.surfaceContainer)
        .statusBarsPadding()
        .verticalScroll(rememberScrollState()),
    ) {
      IconButton(
        onClick = onBack,
        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
      ) {
        Icon(
          imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
          contentDescription = "Back",
          tint = MaterialTheme.colorScheme.onSurface,
        )
      }

      Column(modifier = Modifier.padding(horizontal = 24.dp)) {
        Row(
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
          Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.surfaceContainerHighest,
            modifier = Modifier.size(40.dp),
          ) {
            Icon(
              imageVector = article.icon,
              contentDescription = null,
              modifier = Modifier
                .padding(8.dp)
                .sharedBounds(
                  rememberSharedContentState(key = "article-icon-${article.id}"),
                  animatedVisibilityScope = animatedVisibilityScope,
                ),
              tint = MaterialTheme.colorScheme.primary,
            )
          }

          FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            article.tags.forEach { tag ->
              Surface(
                shape = RoundedCornerShape(12.dp),
                color = tag.color.copy(alpha = 0.15f),
              ) {
                Text(
                  text = stringResource(tag.labelRes),
                  style = MaterialTheme.typography.labelSmall,
                  color = tag.color,
                  modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                )
              }
            }
          }
        }

        Spacer(modifier = Modifier.height(16.dp))

        Text(
          text = stringResource(article.titleRes),
          style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Bold),
          color = MaterialTheme.colorScheme.onSurface,
          modifier = Modifier.sharedBounds(
            rememberSharedContentState(key = "article-title-${article.id}"),
            animatedVisibilityScope = animatedVisibilityScope,
          ),
        )

        Spacer(modifier = Modifier.height(12.dp))

        Text(
          text = "${stringResource(article.sourceRes)}  \u2022  ${stringResource(article.dateRes)}  \u2022  ${stringResource(article.readTimeRes)}",
          style = MaterialTheme.typography.labelMedium,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(modifier = Modifier.height(24.dp))

        stringResource(article.bodyRes).split("\n\n").forEach { paragraph ->
          Text(
            text = paragraph,
            style = MaterialTheme.typography.bodyLarge.copy(lineHeight = 26.sp),
            color = MaterialTheme.colorScheme.onSurface,
          )
          Spacer(modifier = Modifier.height(16.dp))
        }

        Spacer(modifier = Modifier.height(32.dp))
      }
    }
  }
}
