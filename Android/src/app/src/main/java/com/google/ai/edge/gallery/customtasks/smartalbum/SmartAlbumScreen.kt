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

package com.google.ai.edge.gallery.customtasks.smartalbum

import androidx.compose.material3.rememberModalBottomSheetState
import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.result.contract.ActivityResultContracts.PickMultipleVisualMedia
import androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia.ImageAndVideo
import androidx.annotation.VisibleForTesting
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AddPhotoAlternate
import androidx.compose.material.icons.outlined.ImageSearch
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.ai.edge.gallery.R
import com.google.ai.edge.gallery.common.logButtonClick
import com.google.ai.edge.gallery.services.photolibrary.PhotoAsset
import com.google.ai.edge.gallery.services.photolibrary.PhotoLibraryService
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private const val TAG = "SmartAlbumScreen"

/** Main landing and onboarding screen composable for Smart Album / Instant Media Search. */
@Composable
fun SmartAlbumScreen(
  viewModel: SmartAlbumViewModel,
  searchViewModel: SmartAlbumSearchViewModel,
  bottomPadding: Dp = 0.dp,
  setCustomNavigateUpCallback: ((() -> Unit)?) -> Unit = {},
  showOnboarding: Boolean = true,
  setTopBarVisible: (Boolean) -> Unit = {},
) {
  val context = LocalContext.current

  val permissions =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
      arrayOf(
        Manifest.permission.READ_MEDIA_IMAGES,
        Manifest.permission.READ_MEDIA_VIDEO,
        Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED,
      )
    } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
      arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO)
    } else {
      arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
    }

  val userIndexingProgress by viewModel.indexingProgress.collectAsState()
  val recentUserAssets by viewModel.recentAssets.collectAsState()
  var hasPermission by rememberSaveable {
    mutableStateOf(
      permissions.any {
        ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
      } &&
        (viewModel.photoLibraryService.isFullLibraryAccessEnabled() ||
          viewModel.indexingProgress.value.totalCount > 0 ||
          viewModel.recentAssets.value.isNotEmpty())
    )
  }

  LaunchedEffect(userIndexingProgress.totalCount, recentUserAssets.size) {
    if (userIndexingProgress.totalCount > 0 || recentUserAssets.isNotEmpty()) {
      hasPermission = true
    }
  }

  val coroutineScope = rememberCoroutineScope()
  val showAnalyzeAllConfirmSheet by viewModel.showAnalyzeAllConfirmSheet.collectAsState()
  val photosToAnalyzeCount by viewModel.photosToAnalyzeCount.collectAsState()

  val permissionLauncher =
    rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
      results ->
      val isGranted = results.values.any { it }
      val isFullAccess =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
          results[Manifest.permission.READ_MEDIA_IMAGES] == true
        } else {
          results[Manifest.permission.READ_EXTERNAL_STORAGE] == true
        }
      viewModel.onPermissionResult(
        isGranted = isGranted,
        isFullAccess = isFullAccess,
        onPermissionGranted = {
          hasPermission = true
          searchViewModel.refreshAssets()
          searchViewModel.selectSource(SmartAlbumSource.USER_PHOTOS)
        },
      )
    }

  val multiplePhotoPickerLauncher =
    rememberLauncherForActivityResult(contract = PickMultipleVisualMedia()) { uris ->
      if (uris.isNotEmpty()) {
        hasPermission = true
        searchViewModel.addCustomPhotos(uris) {
          viewModel.refreshUserPhotos()
          viewModel.selectSource(SmartAlbumSource.USER_PHOTOS)
          searchViewModel.selectSource(SmartAlbumSource.USER_PHOTOS)
          viewModel.triggerPrioritizedIndexing()
        }
      }
    }

  LaunchedEffect(hasPermission) {
    if (hasPermission) {
      viewModel.retryIndexingIfNeeded()
      searchViewModel.refreshAssets()
    }
  }

  val currentHasPermission by rememberUpdatedState(hasPermission)
  val lifecycleOwner = LocalLifecycleOwner.current
  DisposableEffect(lifecycleOwner, viewModel) {
    val observer = LifecycleEventObserver { _, event ->
      when (event) {
        Lifecycle.Event.ON_RESUME -> {
          if (currentHasPermission) {
            viewModel.refreshUserPhotos()
            searchViewModel.refreshAssets()
          }
          viewModel.triggerPrioritizedIndexing()
        }
        Lifecycle.Event.ON_STOP -> {
          viewModel.stopAllIndexing()
        }
        else -> {}
      }
    }
    lifecycleOwner.lifecycle.addObserver(observer)
    onDispose {
      lifecycleOwner.lifecycle.removeObserver(observer)
      viewModel.stopAllIndexing()
    }
  }

  var showSearch by remember { mutableStateOf(false) }

  LaunchedEffect(showSearch) {
    if (showSearch) {
      setCustomNavigateUpCallback { showSearch = false }
    } else {
      setCustomNavigateUpCallback(null)
    }
  }

  DisposableEffect(Unit) { onDispose { setCustomNavigateUpCallback(null) } }

  if (showOnboarding) {
    SmartAlbumOnboardingDialog()
  }

  if (showAnalyzeAllConfirmSheet) {
    StartAnalyzingAllPhotosBottomSheet(
      photoCount = photosToAnalyzeCount,
      onConfirm = {
        viewModel.confirmAnalyzeAllPhotos()
        hasPermission = true
        searchViewModel.refreshAssets()
        searchViewModel.selectSource(SmartAlbumSource.USER_PHOTOS)
      },
      onSelectPhotos = {
        viewModel.dismissAnalyzeAllConfirmation()
        coroutineScope.launch { viewModel.photoLibraryService.setFullLibraryAccessEnabled(false) }
        multiplePhotoPickerLauncher.launch(PickVisualMediaRequest(ImageAndVideo))
      },
      onDismiss = { viewModel.dismissAnalyzeAllConfirmation() },
    )
  }

  val snackbarHostState = remember { SnackbarHostState() }
  var showProgressPopup by remember { mutableStateOf(false) }
  var isCardDismissed by remember { mutableStateOf(false) }

  val totalProgressUpdate by viewModel.totalIndexingProgress.collectAsState()
  val isAllIndexingPaused by viewModel.isAllIndexingPaused.collectAsState()
  val activeSource by viewModel.activeSource.collectAsState()
  val isNotificationRequested by viewModel.isNotificationRequested.collectAsState()
  var isCardExpanded by rememberSaveable { mutableStateOf(false) }

  val isIndexingIncomplete =
    totalProgressUpdate.totalCount > 0 &&
      totalProgressUpdate.processedCount < totalProgressUpdate.totalCount

  LaunchedEffect(isIndexingIncomplete) {
    if (!isIndexingIncomplete) {
      isCardDismissed = false
    }
  }

  LaunchedEffect(isIndexingIncomplete, isAllIndexingPaused) {
    if (isIndexingIncomplete) {
      if (!showProgressPopup && !isAllIndexingPaused) {
        delay(3000)
      }
      showProgressPopup = true
    } else {
      showProgressPopup = false
    }
  }

  val isProgressCardVisible = showProgressPopup && isIndexingIncomplete && !isCardDismissed

  if (showSearch) {
    SmartAlbumSearchScreen(
      searchViewModel = searchViewModel,
      onBack = { showSearch = false },
      bottomPadding = bottomPadding,
      setTopBarVisible = setTopBarVisible,
    )
  } else {
    Box(modifier = Modifier.fillMaxSize()) {
      SmartAlbumMainContent(
        viewModel = viewModel,
        searchViewModel = searchViewModel,
        hasPermission = hasPermission,
        onRequestPermission = { permissionLauncher.launch(permissions) },
        onAddPhotos = {
          viewModel.selectSource(SmartAlbumSource.USER_PHOTOS)
          searchViewModel.selectSource(SmartAlbumSource.USER_PHOTOS)
          multiplePhotoPickerLauncher.launch(PickVisualMediaRequest(ImageAndVideo))
        },
        onOpenSearch = { showSearch = true },
        bottomPadding =
          if (isProgressCardVisible) {
            if (isCardExpanded) bottomPadding + 190.dp else bottomPadding + 100.dp
          } else {
            bottomPadding
          },
      )

      Column(
        modifier =
          Modifier.align(Alignment.BottomCenter)
            .fillMaxWidth()
            .padding(bottom = bottomPadding + 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
      ) {
        SnackbarHost(
          hostState = snackbarHostState,
          snackbar = { data ->
            Snackbar(
              snackbarData = data,
              modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
          },
        )

        if (isProgressCardVisible) {
          val userProgressUpdate by viewModel.indexingProgress.collectAsState()
          val sampleProgressUpdate by viewModel.sampleIndexingProgress.collectAsState()
          val isUserIndexingPaused by viewModel.isUserIndexingPaused.collectAsState()
          val isSampleIndexingPaused by viewModel.isSampleIndexingPaused.collectAsState()
          SmartAlbumExpandableAnalysisProgressCard(
            sampleProgress = sampleProgressUpdate,
            userProgress = userProgressUpdate,
            isSamplePaused = isSampleIndexingPaused,
            isUserPaused = isUserIndexingPaused,
            isAllPaused = isAllIndexingPaused,
            isSampleAlbumAvailable = viewModel.isSampleAlbumAvailable,
            hasUserPermission = hasPermission,
            activeSource = activeSource,
            isExpanded = isCardExpanded,
            onToggleExpand = { isCardExpanded = !isCardExpanded },
            onPauseResumeAllClick = {
              if (isAllIndexingPaused) {
                viewModel.resumeAllIndexing()
              } else {
                viewModel.pauseAllIndexing()
              }
            },
            onPauseCollectionClick = { source -> viewModel.pauseIndexing(source) },
            onResumeCollectionClick = { source -> viewModel.resumeIndexing(source) },
            onStartNowCollectionClick = { source -> viewModel.startNow(source) },
            onDismiss = {
              isCardDismissed = true
              showProgressPopup = false
            },
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
          )
        }
      }
    }
  }
}

private val STACK_ANGLES = floatArrayOf(0f, 4f, 8f, 12f, 7f, 2f, -3f, -8f, -12f, -7f, -2f, 3f)
private val STACK_OFFSETS =
  arrayOf(
    DpOffset(0.dp, 0.dp),
    DpOffset(9.dp, (-7).dp),
    DpOffset((-9).dp, (-5).dp),
    DpOffset(11.dp, 6.dp),
    DpOffset((-8).dp, 8.dp),
    DpOffset(7.dp, (-9).dp),
    DpOffset((-11).dp, (-7).dp),
    DpOffset(9.dp, 8.dp),
    DpOffset((-9).dp, 6.dp),
    DpOffset(8.dp, (-8).dp),
    DpOffset((-7).dp, (-9).dp),
    DpOffset(10.dp, 7.dp),
  )

@VisibleForTesting
@Composable
internal fun PhotoStackView(
  recentAssets: List<PhotoAsset>,
  photoLibraryService: PhotoLibraryService,
  processedCount: Int,
  totalCount: Int,
  isIndexing: Boolean,
  modifier: Modifier = Modifier,
  onClick: (() -> Unit)? = null,
  placeholderIcon: ImageVector = Icons.Outlined.PhotoLibrary,
) {
  val clickableModifier = if (onClick != null) Modifier.clickable { onClick() } else Modifier
  Box(
    modifier = modifier.then(clickableModifier).padding(vertical = 10.dp),
    contentAlignment = Alignment.Center,
  ) {
    if (recentAssets.isEmpty()) {
      for (index in 2 downTo 0) {
        key(index) {
          PhotoStackCard(
            asset = null,
            photoLibraryService = photoLibraryService,
            sequenceIndex = index,
            modifier = Modifier.zIndex((3 - index).toFloat()),
            isPrimaryPlaceholder = index == 0,
            placeholderIcon = placeholderIcon,
          )
        }
      }
    } else {
      val sequenceTracker = remember {
        object {
          val map = mutableMapOf<String, Int>()
          var next = 0
        }
      }
      val visibleAssets = recentAssets.distinctBy { it.id }.takeLast(12)
      if (visibleAssets.none { it.id in sequenceTracker.map }) {
        sequenceTracker.map.clear()
        sequenceTracker.next = maxOf(0, processedCount - visibleAssets.size)
      } else if (sequenceTracker.map.size > 32) {
        val visibleIds = visibleAssets.map { it.id }.toSet()
        sequenceTracker.map.keys.retainAll(visibleIds)
      }
      for ((index, asset) in visibleAssets.withIndex()) {
        key(asset.id) {
          val seqIndex = sequenceTracker.map.getOrPut(asset.id) { sequenceTracker.next++ }
          PhotoStackCard(
            asset = asset,
            photoLibraryService = photoLibraryService,
            sequenceIndex = seqIndex,
            modifier = Modifier.zIndex(index.toFloat()),
          )
        }
      }
    }

    Surface(
      shape = CircleShape,
      color = Color.Black.copy(alpha = 0.85f),
      shadowElevation = 4.dp,
      modifier = Modifier.align(Alignment.BottomEnd).offset(x = 12.dp, y = 12.dp).zIndex(100f),
    ) {
      Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        modifier = Modifier.padding(horizontal = 9.dp, vertical = 4.dp),
      ) {
        Text(
          text = if (totalCount == 0) "0" else "${minOf(processedCount, totalCount)}",
          style = MaterialTheme.typography.labelMedium,
          color = Color.White,
          fontWeight = FontWeight.Bold,
        )
        if (isIndexing && processedCount < totalCount) {
          CircularProgressIndicator(
            modifier = Modifier.size(10.dp),
            color = Color.White,
            strokeWidth = 1.5.dp,
          )
        }
      }
    }
  }
}

@Composable
private fun PhotoStackCard(
  asset: PhotoAsset?,
  photoLibraryService: PhotoLibraryService,
  sequenceIndex: Int,
  modifier: Modifier = Modifier,
  isPrimaryPlaceholder: Boolean = true,
  placeholderIcon: ImageVector = Icons.Outlined.PhotoLibrary,
) {
  val angle = STACK_ANGLES[sequenceIndex % STACK_ANGLES.size]
  val offset = STACK_OFFSETS[sequenceIndex % STACK_OFFSETS.size]

  val initialBitmap =
    remember(asset?.id) {
      asset?.let { photoLibraryService.getCachedBitmap(it, targetDimension = 512) }
    }
  val bitmap by
    produceState<Bitmap?>(initialValue = initialBitmap, key1 = asset?.id) {
      value = initialBitmap
      if (value == null && asset != null) {
        val cached = photoLibraryService.getCachedBitmap(asset, targetDimension = 512)
        value = cached ?: photoLibraryService.loadBitmap(asset, targetDimension = 512)
      } else if (asset == null) {
        value = null
      }
    }

  Surface(
    modifier =
      modifier.offset(x = offset.x, y = offset.y).graphicsLayer(rotationZ = angle).size(168.dp),
    shape = RoundedCornerShape(14.dp),
    color = MaterialTheme.colorScheme.surface,
    shadowElevation = 4.dp,
  ) {
    Box(
      modifier =
        Modifier.fillMaxSize()
          .padding(4.dp)
          .clip(RoundedCornerShape(10.dp))
          .background(
            if (
              asset == null &&
                isPrimaryPlaceholder &&
                placeholderIcon == Icons.Outlined.AddPhotoAlternate
            ) {
              MaterialTheme.colorScheme.primaryContainer
            } else {
              MaterialTheme.colorScheme.surfaceVariant
            }
          ),
      contentAlignment = Alignment.Center,
    ) {
      val currentBitmap = bitmap
      if (currentBitmap != null) {
        Image(
          bitmap = currentBitmap.asImageBitmap(),
          contentDescription = null,
          contentScale = ContentScale.Crop,
          modifier = Modifier.fillMaxSize(),
        )
      } else if (isPrimaryPlaceholder) {
        Icon(
          imageVector = placeholderIcon,
          contentDescription = null,
          tint =
            if (placeholderIcon == Icons.Outlined.AddPhotoAlternate) {
              MaterialTheme.colorScheme.primary
            } else {
              MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
            },
          modifier = Modifier.size(48.dp),
        )
      }
    }
  }
}

@Composable
private fun SmartAlbumMainContent(
  viewModel: SmartAlbumViewModel,
  searchViewModel: SmartAlbumSearchViewModel,
  hasPermission: Boolean,
  onRequestPermission: () -> Unit,
  onAddPhotos: () -> Unit,
  onOpenSearch: () -> Unit,
  bottomPadding: Dp,
) {
  val progress by viewModel.indexingProgress.collectAsState()
  val sampleProgress by viewModel.sampleIndexingProgress.collectAsState()
  val recentAssets by viewModel.recentAssets.collectAsState()
  val recentSampleAssets by viewModel.recentSampleAssets.collectAsState()
  val sampleAssets by viewModel.sampleAssets.collectAsState()
  val isUserIndexingPaused by viewModel.isUserIndexingPaused.collectAsState()
  val isSampleIndexingPaused by viewModel.isSampleIndexingPaused.collectAsState()

  val isIndexingActive =
    progress.totalCount > 0 &&
      progress.processedCount < progress.totalCount &&
      !isUserIndexingPaused
  val isSampleAvailable = viewModel.isSampleAlbumAvailable || sampleAssets.isNotEmpty()
  val isSampleIndexingActive =
    isSampleAvailable &&
      sampleProgress.totalCount > 0 &&
      sampleProgress.processedCount < sampleProgress.totalCount &&
      !isSampleIndexingPaused

  val userTitle =
    when {
      !hasPermission -> stringResource(R.string.smartalbum_add_photos_title)
      progress.totalCount == 0 -> stringResource(R.string.smartalbum_add_photos_title)
      isIndexingActive ->
        pluralStringResource(
          R.plurals.smartalbum_adding_photos_format,
          progress.totalCount,
          progress.totalCount,
        )
      else -> {
        val hasVideos = recentAssets.any { it.isVideo }
        val hasPhotos = recentAssets.any { !it.isVideo }
        when {
          hasVideos && !hasPhotos ->
            pluralStringResource(
              R.plurals.smartalbum_videos_format,
              progress.totalCount,
              progress.totalCount,
            )
          !hasVideos && hasPhotos ->
            pluralStringResource(
              R.plurals.smartalbum_photos_format,
              progress.totalCount,
              progress.totalCount,
            )
          else ->
            pluralStringResource(
              R.plurals.smartalbum_media_items_format,
              progress.totalCount,
              progress.totalCount,
            )
        }
      }
    }

  val userSubtitle =
    when {
      !hasPermission -> stringResource(R.string.smartalbum_grant_photo_library_access_subtitle)
      isIndexingActive -> null
      else -> stringResource(R.string.smartalbum_photos_granted_access_subtitle)
    }

  Column(
    modifier =
      Modifier.fillMaxSize()
        .padding(bottom = bottomPadding)
        .padding(horizontal = 16.dp, vertical = 8.dp)
        .verticalScroll(rememberScrollState()),
    horizontalAlignment = Alignment.CenterHorizontally,
  ) {
    Spacer(modifier = Modifier.height(4.dp))

    val primaryColor = MaterialTheme.colorScheme.primary

    Icon(
      imageVector = Icons.Outlined.ImageSearch,
      contentDescription = null,
      tint = primaryColor,
      modifier = Modifier.size(36.dp).padding(bottom = 2.dp),
    )

    Text(
      text = stringResource(R.string.smartalbum_welcome_title),
      style = MaterialTheme.typography.titleLarge,
      fontWeight = FontWeight.Bold,
      textAlign = TextAlign.Center,
    )

    Spacer(modifier = Modifier.height(4.dp))

    val descriptionText = buildAnnotatedString {
      val modelName = "EmbeddingGemma 2"
      val fullText = stringResource(R.string.smartalbum_welcome_description_format, modelName)
      val startIndex = fullText.indexOf(modelName)
      if (startIndex >= 0) {
        append(fullText.substring(0, startIndex))
        withStyle(style = SpanStyle(color = primaryColor, fontWeight = FontWeight.Bold)) {
          append(modelName)
        }
        append(fullText.substring(startIndex + modelName.length))
      } else {
        append(fullText)
      }
    }

    Text(
      text = descriptionText,
      style = MaterialTheme.typography.bodySmall,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
      textAlign = TextAlign.Center,
      modifier = Modifier.padding(horizontal = 16.dp),
    )

    Spacer(modifier = Modifier.height(12.dp))

    // Stack 1: Sample photos (Demo collection)
    Box(
      modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
      contentAlignment = Alignment.Center,
    ) {
      PhotoStackView(
        recentAssets =
          if (recentSampleAssets.isNotEmpty()) recentSampleAssets else sampleAssets.take(12),
        photoLibraryService = viewModel.photoLibraryService,
        processedCount = sampleProgress.processedCount,
        totalCount = sampleProgress.totalCount,
        isIndexing = isSampleIndexingActive,
        onClick =
          if (isSampleAvailable) {
            {
              logButtonClick("smartalbum_open_sample_photos")
              viewModel.selectSource(SmartAlbumSource.SAMPLE_ALBUM)
              searchViewModel.selectSource(SmartAlbumSource.SAMPLE_ALBUM)
              onOpenSearch()
            }
          } else {
            null
          },
      )
    }

    Spacer(modifier = Modifier.height(2.dp))

    val sampleTitle =
      if (isSampleAvailable && sampleProgress.totalCount > 0 && isSampleIndexingActive) {
        pluralStringResource(
          R.plurals.smartalbum_adding_photos_format,
          sampleProgress.totalCount,
          sampleProgress.totalCount,
        )
      } else {
        stringResource(R.string.smartalbum_sample_photos_title)
      }

    Text(
      text = sampleTitle,
      style = MaterialTheme.typography.titleMedium,
      fontWeight = FontWeight.Bold,
      textAlign = TextAlign.Center,
    )

    Spacer(modifier = Modifier.height(2.dp))

    Text(
      text = stringResource(R.string.smartalbum_sample_photos_subtitle),
      style = MaterialTheme.typography.bodySmall,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
      textAlign = TextAlign.Center,
    )

    Spacer(modifier = Modifier.height(14.dp))

    // Stack 2: User photos / Add photos
    Box(
      modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
      contentAlignment = Alignment.Center,
    ) {
      PhotoStackView(
        recentAssets = recentAssets,
        photoLibraryService = viewModel.photoLibraryService,
        processedCount = progress.processedCount,
        totalCount = progress.totalCount,
        isIndexing = isIndexingActive,
        placeholderIcon = Icons.Outlined.AddPhotoAlternate,
        onClick = {
          if (!hasPermission && progress.totalCount == 0 && recentAssets.isEmpty()) {
            logButtonClick("smartalbum_request_photo_permission")
            onRequestPermission()
          } else {
            logButtonClick("smartalbum_open_user_photos")
            viewModel.selectSource(SmartAlbumSource.USER_PHOTOS)
            searchViewModel.selectSource(SmartAlbumSource.USER_PHOTOS)
            onOpenSearch()
          }
        },
      )
    }

    Spacer(modifier = Modifier.height(2.dp))

    Text(
      text = userTitle,
      style = MaterialTheme.typography.titleMedium,
      fontWeight = FontWeight.Bold,
      textAlign = TextAlign.Center,
    )

    if (userSubtitle != null) {
      Spacer(modifier = Modifier.height(2.dp))
      Text(
        text = userSubtitle,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
      )
    }

    Spacer(modifier = Modifier.height(8.dp))
  }
}

@OptIn(ExperimentalMaterial3Api::class)
@VisibleForTesting
@Composable
internal fun StartAnalyzingAllPhotosBottomSheet(
  photoCount: Int,
  onConfirm: () -> Unit,
  onSelectPhotos: () -> Unit,
  onDismiss: () -> Unit,
) {
  val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
  ModalBottomSheet(
    onDismissRequest = onDismiss,
    sheetState = sheetState,
    shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
    containerColor = MaterialTheme.colorScheme.surfaceContainer,
    dragHandle = null,
  ) {
    Column(
      modifier =
        Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(top = 32.dp, bottom = 32.dp),
      horizontalAlignment = Alignment.CenterHorizontally,
    ) {
      Text(
        text = stringResource(R.string.smartalbum_start_analyzing_all_photos_title),
        style = MaterialTheme.typography.headlineSmall,
        fontWeight = FontWeight.Bold,
        textAlign = TextAlign.Center,
        color = MaterialTheme.colorScheme.onSurface,
      )
      Spacer(modifier = Modifier.height(12.dp))
      Text(
        text =
          pluralStringResource(
            R.plurals.smartalbum_start_analyzing_all_photos_desc,
            photoCount,
            photoCount,
          ),
        style = MaterialTheme.typography.bodyMedium,
        textAlign = TextAlign.Center,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
      Spacer(modifier = Modifier.height(32.dp))
      Button(
        onClick = onConfirm,
        modifier = Modifier.fillMaxWidth().height(52.dp),
        shape = CircleShape,
      ) {
        Text(
          text = stringResource(R.string.smartalbum_yes_add_all),
          style = MaterialTheme.typography.titleSmall,
          fontWeight = FontWeight.Bold,
        )
      }
      Spacer(modifier = Modifier.height(12.dp))
      FilledTonalButton(
        onClick = onSelectPhotos,
        modifier = Modifier.fillMaxWidth().height(52.dp),
        shape = CircleShape,
      ) {
        Text(
          text = stringResource(R.string.smartalbum_select_photos),
          style = MaterialTheme.typography.titleSmall,
          fontWeight = FontWeight.Bold,
        )
      }
    }
  }
}
