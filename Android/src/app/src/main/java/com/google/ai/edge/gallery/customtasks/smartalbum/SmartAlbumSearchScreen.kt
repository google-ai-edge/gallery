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

import android.text.format.DateUtils
import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.util.Log
import android.view.Gravity
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.VideoView
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.result.contract.ActivityResultContracts.PickMultipleVisualMedia
import androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia.ImageAndVideo
import androidx.camera.core.CameraSelector
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.ArrowDropUp
import androidx.compose.material.icons.filled.Cameraswitch
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material.icons.filled.FastRewind
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.AddPhotoAlternate
import androidx.compose.material.icons.outlined.CropOriginal
import androidx.compose.material.icons.outlined.DateRange
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.ImageSearch
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.PhotoCamera
import androidx.compose.material.icons.outlined.Place
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.Smartphone
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.BiasAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.ai.edge.gallery.R
import com.google.ai.edge.gallery.common.ImageUtils
import com.google.ai.edge.gallery.common.logButtonClick
import com.google.ai.edge.gallery.services.photolibrary.PhotoAsset
import com.google.ai.edge.gallery.services.photolibrary.PhotoLibraryService
import com.google.ai.edge.gallery.services.semanticretrieval.SemanticRetrievalResult
import com.google.ai.edge.gallery.services.semanticretrieval.SemanticRetrievalService
import com.google.ai.edge.gallery.ui.common.LiveCameraView
import com.google.ai.edge.gallery.ui.common.SmallFilledTonalButton
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private const val TAG = "SmartAlbumSearchScreen"
private const val MATCH_SCORE_GRAYOUT_MIN_FLOOR = 0.40f
private const val MATCH_SCORE_GRAYOUT_DELTA = 0.20f

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SmartAlbumSearchScreen(
  searchViewModel: SmartAlbumSearchViewModel,
  onBack: () -> Unit,
  bottomPadding: Dp = 0.dp,
  setTopBarVisible: (Boolean) -> Unit = {},
  onRequestPermission: (() -> Unit)? = null,
) {
  val activeSource by searchViewModel.activeSource.collectAsState()
  val searchText by searchViewModel.searchText.collectAsState()
  val uiState by searchViewModel.uiState.collectAsState()
  val allAssets by searchViewModel.allAssets.collectAsState()
  val addAllPhotosCount by searchViewModel.addAllPhotosCount.collectAsState()
  val shouldRequestPhotoAccess by searchViewModel.shouldRequestPhotoAccess.collectAsState()
  val isLoadingAssets by searchViewModel.isLoadingAssets.collectAsState()
  val selectedImageAsset by searchViewModel.selectedImageAsset.collectAsState()
  val indexedIds by searchViewModel.indexedIds.collectAsState()
  val isSelectionMode by searchViewModel.isSelectionMode.collectAsState()
  val selectedPhotoIds by searchViewModel.selectedPhotoIds.collectAsState()
  val isLiveCameraSearchActive by searchViewModel.isLiveCameraSearchActive.collectAsState()
  val cameraSearchResults by searchViewModel.cameraSearchResults.collectAsState()
  val isSearching by searchViewModel.isSearching.collectAsState()
  val galleryGridState = rememberLazyGridState()
  val resultsGridState = rememberLazyGridState()

  LaunchedEffect(shouldRequestPhotoAccess) {
    if (shouldRequestPhotoAccess) {
      searchViewModel.onPhotoAccessRequestHandled()
      onRequestPermission?.invoke()
    }
  }

  LaunchedEffect(galleryGridState.isScrollInProgress, resultsGridState.isScrollInProgress) {
    SmartAlbumIndexingCoordinator.isScrollInProgress =
      galleryGridState.isScrollInProgress || resultsGridState.isScrollInProgress
  }

  LaunchedEffect((uiState as? SearchUiState.Results)?.matches) {
    val matches = (uiState as? SearchUiState.Results)?.matches
    if (!matches.isNullOrEmpty()) {
      resultsGridState.animateScrollToItem(0)
    }
  }

  val context = LocalContext.current
  var hasCameraPermission by remember {
    mutableStateOf(
      ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
        PackageManager.PERMISSION_GRANTED
    )
  }

  val cameraPermissionLauncher =
    rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted ->
      hasCameraPermission = isGranted
    }

  LaunchedEffect(isLiveCameraSearchActive) {
    if (isLiveCameraSearchActive) {
      hasCameraPermission =
        ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
          PackageManager.PERMISSION_GRANTED
    }
    setTopBarVisible(!isLiveCameraSearchActive)
  }

  DisposableEffect(Unit) {
    SmartAlbumIndexingCoordinator.isGalleryVisible = true
    onDispose {
      SmartAlbumIndexingCoordinator.isGalleryVisible = false
      SmartAlbumIndexingCoordinator.isScrollInProgress = false
      setTopBarVisible(true)
    }
  }

  var selectedMatch by remember { mutableStateOf<MatchedAssetResult?>(null) }
  var showRemoveConfirmDialog by remember { mutableStateOf(false) }
  val snackbarHostState = remember { SnackbarHostState() }
  val coroutineScope = rememberCoroutineScope()

  var isSearchFocused by remember { mutableStateOf(false) }
  val isImeVisible = WindowInsets.isImeVisible
  val focusRequester = remember { FocusRequester() }
  val keyboardController = LocalSoftwareKeyboardController.current
  val focusManager = LocalFocusManager.current
  val clearFocusAndHideKeyboard: () -> Unit = {
    focusManager.clearFocus()
    keyboardController?.hide()
  }

  val multiplePhotoPickerLauncher =
    rememberLauncherForActivityResult(contract = PickMultipleVisualMedia()) { uris ->
      if (uris.isNotEmpty()) {
        searchViewModel.addCustomPhotos(uris)
        clearFocusAndHideKeyboard()
      }
    }

  LaunchedEffect(isImeVisible) {
    if (!isImeVisible) {
      focusManager.clearFocus()
    }
  }

  val lifecycleOwner = LocalLifecycleOwner.current
  DisposableEffect(lifecycleOwner) {
    val observer = LifecycleEventObserver { _, event ->
      if (event == Lifecycle.Event.ON_RESUME) {
        searchViewModel.mainViewModel?.triggerPrioritizedIndexing()
      }
    }
    lifecycleOwner.lifecycle.addObserver(observer)
    onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
  }

  BackHandler(
    enabled =
      isLiveCameraSearchActive ||
        isSelectionMode ||
        isSearchFocused ||
        searchText.isNotEmpty() ||
        selectedImageAsset != null ||
        isImeVisible
  ) {
    if (isLiveCameraSearchActive) {
      searchViewModel.closeLiveCameraSearch()
      return@BackHandler
    }
    if (isSelectionMode) {
      searchViewModel.exitSelectionMode()
      return@BackHandler
    }
    if (searchText.isNotEmpty() || selectedImageAsset != null) {
      searchViewModel.clearSearch()
      searchViewModel.clearSelectedImage()
    }
    clearFocusAndHideKeyboard()
  }

  val suggestionTerms =
    listOf(
      stringResource(R.string.smartalbum_suggestion_grocery_receipt),
      stringResource(R.string.smartalbum_suggestion_hiking_selfie),
      stringResource(R.string.smartalbum_suggestion_dog_backyard),
      stringResource(R.string.smartalbum_suggestion_cloudy_beach),
    )

  if (selectedMatch != null) {
    val currentResults =
      if (isLiveCameraSearchActive) {
        cameraSearchResults
      } else if (uiState is SearchUiState.Results) {
        (uiState as SearchUiState.Results).matches
      } else {
        allAssets.map { MatchedAssetResult(asset = it, similarity = null) }
      }
    PhotoDetailDialog(
      initialMatch = selectedMatch!!,
      allMatches = currentResults,
      searchViewModel = searchViewModel,
      onDismiss = { selectedMatch = null },
      onSearchSimilar = { asset ->
        selectedMatch = null
        if (isLiveCameraSearchActive) {
          searchViewModel.closeLiveCameraSearch()
        }
        searchViewModel.searchSimilarImages(asset)
      },
      onSearchLocation = { location ->
        selectedMatch = null
        if (isLiveCameraSearchActive) {
          searchViewModel.closeLiveCameraSearch()
        }
        searchViewModel.onSearchTextChanged(location, immediate = true)
      },
    )
  }

  if (showRemoveConfirmDialog) {
    RemovePhotosConfirmationDialog(
      selectedCount = selectedPhotoIds.size,
      onConfirm = {
        logButtonClick("smartalbum_remove_selected_confirm")
        showRemoveConfirmDialog = false
        searchViewModel.removeSelectedPhotos()
      },
      onDismiss = { showRemoveConfirmDialog = false },
      onCancelButtonClick = { logButtonClick("smartalbum_remove_selected_cancel") },
    )
  }

  if (isLiveCameraSearchActive) {
    if (!hasCameraPermission) {
      CameraPermissionRequestContent(
        activeSource = activeSource,
        isSampleAlbumAvailable = searchViewModel.isSampleAlbumAvailable,
        onSourceSelected = { searchViewModel.selectSource(it) },
        onBack = { searchViewModel.closeLiveCameraSearch() },
        onRequestPermission = { cameraPermissionLauncher.launch(Manifest.permission.CAMERA) },
        modifier = Modifier.fillMaxSize().padding(bottom = bottomPadding),
      )
    } else {
      LiveCameraSearchContent(
        searchViewModel = searchViewModel,
        activeSource = activeSource,
        onClose = { searchViewModel.closeLiveCameraSearch() },
        onSelectMatch = { selectedMatch = it },
        snackbarHostState = snackbarHostState,
        modifier = Modifier.fillMaxSize().padding(bottom = bottomPadding),
      )
    }
  } else {
    Box(
      modifier =
        Modifier.fillMaxSize().padding(bottom = bottomPadding).clickable(
          interactionSource = remember { MutableInteractionSource() },
          indication = null,
        ) {
          clearFocusAndHideKeyboard()
        }
    ) {
      // Content layer: background asset grid when searchText is empty, or search results when
      // searching
      if (searchText.isBlank() && selectedImageAsset == null) {
        if (!isSearchFocused) {
          val displayAssets = remember(allAssets) { allAssets.distinctBy { it.id } }
          Column(modifier = Modifier.fillMaxSize()) {
            // Subheader row (count, + Add button, My photos dropdown)
            Row(
              modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
              verticalAlignment = Alignment.CenterVertically,
              horizontalArrangement = Arrangement.SpaceBetween,
            ) {
              val isIndexingIncomplete =
                remember(allAssets, indexedIds) {
                  allAssets.any { asset -> asset.id !in indexedIds }
                }
              val isIndexingPaused by searchViewModel.isIndexingPaused.collectAsState()
              Row(verticalAlignment = Alignment.CenterVertically) {
                if (isLoadingAssets && allAssets.isEmpty()) {
                  CircularProgressIndicator(
                    modifier = Modifier.size(16.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.primary,
                  )
                  Spacer(modifier = Modifier.width(8.dp))
                } else if (allAssets.isNotEmpty() && isIndexingIncomplete) {
                  if (isIndexingPaused) {
                    Icon(
                      imageVector = Icons.Default.Pause,
                      contentDescription = null,
                      tint = MaterialTheme.colorScheme.onSurface,
                      modifier = Modifier.size(16.dp),
                    )
                  } else {
                    CircularProgressIndicator(
                      modifier = Modifier.size(16.dp),
                      strokeWidth = 2.dp,
                      color = MaterialTheme.colorScheme.primary,
                    )
                  }
                  Spacer(modifier = Modifier.width(8.dp))
                } else if (allAssets.isNotEmpty()) {
                  Icon(
                    imageVector = Icons.Filled.CheckCircle,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.size(16.dp),
                  )
                  Spacer(modifier = Modifier.width(8.dp))
                }
                val (hasVideos, hasPhotos) =
                  remember(displayAssets) {
                    displayAssets.any { it.isVideo } to displayAssets.any { !it.isVideo }
                  }
                val countText =
                  when {
                    hasVideos && !hasPhotos ->
                      pluralStringResource(
                        R.plurals.smartalbum_videos_format,
                        displayAssets.size,
                        displayAssets.size,
                      )
                    !hasVideos && hasPhotos ->
                      pluralStringResource(
                        R.plurals.smartalbum_photos_format,
                        displayAssets.size,
                        displayAssets.size,
                      )
                    else ->
                      pluralStringResource(
                        R.plurals.smartalbum_media_items_format,
                        displayAssets.size,
                        displayAssets.size,
                      )
                  }
                Text(
                  text = countText,
                  style = MaterialTheme.typography.titleMedium,
                  fontWeight = FontWeight.Bold,
                  color = MaterialTheme.colorScheme.onSurface,
                )
              }

              var showManageMenu by remember { mutableStateOf(false) }

              Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
              ) {
                if (activeSource == SmartAlbumSource.USER_PHOTOS) {
                  if (allAssets.isNotEmpty()) {
                    Box {
                      Surface(
                        shape = RoundedCornerShape(20.dp),
                        color = MaterialTheme.colorScheme.secondaryContainer,
                        modifier =
                          Modifier.clickable {
                            logButtonClick("smartalbum_open_manage_menu")
                            coroutineScope.launch {
                              searchViewModel.refreshAddAllPhotosCount()
                              showManageMenu = true
                            }
                          },
                      ) {
                        Row(
                          verticalAlignment = Alignment.CenterVertically,
                          modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                        ) {
                          Icon(
                            imageVector = Icons.Default.ArrowDropDown,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSecondaryContainer,
                            modifier = Modifier.size(18.dp),
                          )
                          Spacer(modifier = Modifier.width(2.dp))
                          Text(
                            text = stringResource(R.string.smartalbum_manage),
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                          )
                        }
                      }

                      DropdownMenu(
                        expanded = showManageMenu,
                        onDismissRequest = { showManageMenu = false },
                      ) {
                        DropdownMenuItem(
                          text = { Text(stringResource(R.string.smartalbum_add_more_photos)) },
                          onClick = {
                            logButtonClick("smartalbum_manage_add_photos")
                            showManageMenu = false
                            multiplePhotoPickerLauncher.launch(
                              PickVisualMediaRequest(ImageAndVideo)
                            )
                          },
                        )
                        if (addAllPhotosCount > 0) {
                          DropdownMenuItem(
                            text = {
                              Text(
                                pluralStringResource(
                                  R.plurals.smartalbum_add_all_photos,
                                  addAllPhotosCount,
                                  addAllPhotosCount,
                                )
                              )
                            },
                            onClick = {
                              logButtonClick("smartalbum_manage_add_all_photos")
                              showManageMenu = false
                              searchViewModel.addAllPermittedPhotos()
                            },
                          )
                        }
                        DropdownMenuItem(
                          text = {
                            Text(stringResource(R.string.smartalbum_select_photos_to_remove))
                          },
                          onClick = {
                            logButtonClick("smartalbum_manage_select_to_remove")
                            showManageMenu = false
                            searchViewModel.enterSelectionMode()
                          },
                        )
                        DropdownMenuItem(
                          text = { Text(stringResource(R.string.smartalbum_remove_all_photos)) },
                          onClick = {
                            logButtonClick("smartalbum_manage_remove_all")
                            showManageMenu = false
                            searchViewModel.removeAllPhotos()
                          },
                        )
                      }
                    }
                  } else {
                    SmallFilledTonalButton(
                      onClick = {
                        logButtonClick("smartalbum_add_photos_subheader")
                        if (onRequestPermission != null) {
                          onRequestPermission()
                        } else {
                          multiplePhotoPickerLauncher.launch(PickVisualMediaRequest(ImageAndVideo))
                        }
                      },
                      imageVector = Icons.Outlined.AddPhotoAlternate,
                      labelResId = R.string.smartalbum_add_photos_title,
                    )
                  }
                }

                SmartAlbumSourcePicker(
                  activeSource = activeSource,
                  isSampleAlbumAvailable = searchViewModel.isSampleAlbumAvailable,
                  onSourceSelected = {
                    searchViewModel.selectSource(it)
                    clearFocusAndHideKeyboard()
                  },
                )
              }
            }

            if (isLoadingAssets && allAssets.isEmpty()) {
              Box(
                modifier = Modifier.fillMaxSize().padding(bottom = 80.dp),
                contentAlignment = Alignment.Center,
              ) {
                CircularProgressIndicator()
              }
            } else if (allAssets.isEmpty()) {
              Box(
                modifier = Modifier.fillMaxSize().padding(bottom = 80.dp),
                contentAlignment = Alignment.Center,
              ) {
                Column(
                  horizontalAlignment = Alignment.CenterHorizontally,
                  verticalArrangement = Arrangement.Center,
                  modifier = Modifier.padding(32.dp),
                ) {
                  Icon(
                    imageVector = Icons.Outlined.Image,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                    modifier = Modifier.size(64.dp),
                  )
                  Spacer(modifier = Modifier.height(16.dp))
                  Text(
                    text = stringResource(R.string.smartalbum_empty_collection_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                  )
                  Spacer(modifier = Modifier.height(4.dp))
                  Text(
                    text = stringResource(R.string.smartalbum_empty_collection_subtitle),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                  )
                  if (activeSource == SmartAlbumSource.USER_PHOTOS) {
                    Spacer(modifier = Modifier.height(16.dp))
                    SmallFilledTonalButton(
                      onClick = {
                        logButtonClick("smartalbum_add_photos_empty_state")
                        if (onRequestPermission != null) {
                          onRequestPermission()
                        } else {
                          multiplePhotoPickerLauncher.launch(PickVisualMediaRequest(ImageAndVideo))
                        }
                      },
                      imageVector = Icons.Outlined.AddPhotoAlternate,
                      labelResId = R.string.smartalbum_add_photos_title,
                    )
                  }
                }
              }
            } else {
              LazyVerticalGrid(
                state = galleryGridState,
                columns = GridCells.Fixed(3),
                contentPadding =
                  PaddingValues(top = 4.dp, start = 8.dp, end = 8.dp, bottom = 120.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
                modifier = Modifier.weight(1f).fillMaxWidth(),
              ) {
                items(displayAssets, key = { it.id }) { asset ->
                  val isAnalyzed = asset.id in indexedIds
                  val isSelected = asset.id in selectedPhotoIds
                  BackgroundAssetGridTile(
                    asset = asset,
                    isAnalyzed = isAnalyzed,
                    isSelected = isSelected,
                    photoLibraryService = searchViewModel.photoLibraryService,
                    onClick = {
                      if (isSelectionMode) {
                        logButtonClick("smartalbum_toggle_select_photo")
                        searchViewModel.togglePhotoSelection(asset.id)
                      } else {
                        logButtonClick("smartalbum_open_photo_detail")
                        clearFocusAndHideKeyboard()
                        selectedMatch = MatchedAssetResult(asset = asset, similarity = null)
                      }
                    },
                    onLongClick = {
                      logButtonClick("smartalbum_long_press_select_photo")
                      searchViewModel.togglePhotoSelection(asset.id)
                    },
                  )
                }
              }
            }
          }
        }
      } else {
        when (val state = uiState) {
          SearchUiState.Empty -> {}

          SearchUiState.Loading -> {
            Box(
              modifier = Modifier.fillMaxSize().padding(bottom = 80.dp),
              contentAlignment = Alignment.Center,
            ) {
              CircularProgressIndicator()
            }
          }

          is SearchUiState.Results -> {
            if (state.matches.isEmpty()) {
              Box(
                modifier =
                  Modifier.fillMaxSize().padding(horizontal = 24.dp).clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                  ) {
                    clearFocusAndHideKeyboard()
                  },
                contentAlignment = BiasAlignment(horizontalBias = 0f, verticalBias = -0.5f),
              ) {
                Text(
                  text = stringResource(R.string.smartalbum_no_matching_photos),
                  style = MaterialTheme.typography.bodyLarge,
                  color = MaterialTheme.colorScheme.onSurfaceVariant,
                  textAlign = TextAlign.Center,
                )
              }
            } else {
              var isWarningBannerDismissed by remember { mutableStateOf(false) }
              val isIndexingIncomplete =
                remember(allAssets, indexedIds) {
                  allAssets.any { asset -> asset.id !in indexedIds }
                }

              Column(modifier = Modifier.fillMaxSize()) {
                // Results Header Row: count & My photos selector
                Row(
                  modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
                  verticalAlignment = Alignment.CenterVertically,
                  horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                  Text(
                    text =
                      pluralStringResource(
                        R.plurals.smartalbum_results_format,
                        state.matches.size,
                        state.matches.size,
                      ),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                  )

                  Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                  ) {
                    SmartAlbumSourcePicker(
                      activeSource = activeSource,
                      isSampleAlbumAvailable = searchViewModel.isSampleAlbumAvailable,
                      onSourceSelected = {
                        searchViewModel.selectSource(it)
                        clearFocusAndHideKeyboard()
                      },
                    )
                  }
                }

                if (isIndexingIncomplete && !isWarningBannerDismissed) {
                  Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surfaceContainer,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                  ) {
                    Row(
                      modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                      verticalAlignment = Alignment.CenterVertically,
                    ) {
                      Icon(
                        imageVector = Icons.Outlined.Info,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp),
                      )
                      Spacer(modifier = Modifier.width(10.dp))
                      Text(
                        text = stringResource(R.string.smartalbum_indexing_warning_banner),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f),
                      )
                      Spacer(modifier = Modifier.width(6.dp))
                      Icon(
                        imageVector = Icons.Default.Clear,
                        contentDescription = "Dismiss warning",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier =
                          Modifier.size(18.dp).clickable {
                            logButtonClick("smartalbum_dismiss_indexing_warning")
                            isWarningBannerDismissed = true
                          },
                      )
                    }
                  }
                }

                val topScore = state.matches.firstOrNull { it.similarity != null }?.similarity
                val grayoutThreshold = topScore?.let {
                  maxOf(MATCH_SCORE_GRAYOUT_MIN_FLOOR, it - MATCH_SCORE_GRAYOUT_DELTA)
                }

                val resultsAlpha by
                  animateFloatAsState(
                    targetValue = if (isSearching) 0.6f else 1f,
                    animationSpec = tween(durationMillis = 200),
                    label = "resultsAlpha",
                  )

                LazyVerticalGrid(
                  columns = GridCells.Fixed(3),
                  state = resultsGridState,
                  contentPadding =
                    PaddingValues(top = 8.dp, start = 8.dp, end = 8.dp, bottom = 120.dp),
                  horizontalArrangement = Arrangement.spacedBy(4.dp),
                  verticalArrangement = Arrangement.spacedBy(4.dp),
                  modifier =
                    Modifier.weight(1f).fillMaxWidth().graphicsLayer { alpha = resultsAlpha },
                ) {
                  items(state.matches.distinctBy { it.asset.id }, key = { it.asset.id }) { match ->
                    val isSelected = match.asset.id in selectedPhotoIds
                    val isBelowThreshold =
                      grayoutThreshold != null &&
                        match.similarity != null &&
                        match.similarity < grayoutThreshold
                    PhotoGridTile(
                      match = match,
                      isSelected = isSelected,
                      photoLibraryService = searchViewModel.photoLibraryService,
                      onClick = {
                        if (isSelectionMode) {
                          logButtonClick("smartalbum_toggle_select_search_result")
                          searchViewModel.togglePhotoSelection(match.asset.id)
                        } else {
                          logButtonClick("smartalbum_select_search_result")
                          clearFocusAndHideKeyboard()
                          selectedMatch = match
                        }
                      },
                      onLongClick = {
                        logButtonClick("smartalbum_long_press_select_search_result")
                        searchViewModel.togglePhotoSelection(match.asset.id)
                      },
                      isBelowThreshold = isBelowThreshold,
                      isSearching = isSearching,
                    )
                  }
                }
              }
            }
          }
        }
      }

      // Progress Popup Banner (shows if indexing is in progress or paused)
      var showProgressPopup by remember { mutableStateOf(false) }
      var isCardDismissed by remember { mutableStateOf(false) }

      val currentProgressUpdate by searchViewModel.currentIndexingProgress.collectAsState()
      val isIndexingPaused by searchViewModel.isIndexingPaused.collectAsState()
      val activeSource by searchViewModel.activeSource.collectAsState()

      val isIndexingIncomplete =
        currentProgressUpdate.totalCount > 0 &&
          currentProgressUpdate.processedCount < currentProgressUpdate.totalCount

      LaunchedEffect(isIndexingIncomplete) {
        if (!isIndexingIncomplete) {
          isCardDismissed = false
        }
      }

      LaunchedEffect(isIndexingIncomplete, isIndexingPaused, activeSource) {
        if (isIndexingIncomplete) {
          if (!showProgressPopup && !isIndexingPaused) {
            delay(3000)
          }
          showProgressPopup = true
        } else {
          showProgressPopup = false
        }
      }

      // Bottom floating search bar / selection bar (positioned above IME keyboard)
      Column(
        modifier =
          Modifier.align(Alignment.BottomCenter)
            .fillMaxWidth()
            .imePadding()
            .padding(bottom = 12.dp),
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

        if (isSelectionMode) {
          val totalAssetsCount =
            if (searchText.isBlank() && selectedImageAsset == null) {
              allAssets.size
            } else {
              (uiState as? SearchUiState.Results)?.matches?.size ?: allAssets.size
            }
          val isAllSelected = selectedPhotoIds.size >= totalAssetsCount && totalAssetsCount > 0
          SelectionBottomBar(
            selectedCount = selectedPhotoIds.size,
            isAllSelected = isAllSelected,
            onSelectAll = {
              logButtonClick("smartalbum_select_all")
              searchViewModel.selectAll()
            },
            onDeselectAll = {
              logButtonClick("smartalbum_deselect_all")
              searchViewModel.deselectAll()
            },
            onRemove = {
              logButtonClick("smartalbum_remove_selected_start")
              if (selectedPhotoIds.isNotEmpty()) {
                showRemoveConfirmDialog = true
              }
            },
            onClose = {
              logButtonClick("smartalbum_close_selection_mode")
              searchViewModel.exitSelectionMode()
            },
          )
        } else {
          if (showProgressPopup && isIndexingIncomplete && !isCardDismissed) {
            val isNotificationRequested by searchViewModel.isNotificationRequested.collectAsState()
            SmartAlbumAnalysisProgressCard(
              progressUpdate = currentProgressUpdate,
              isIndexingPaused = isIndexingPaused,
              onPauseResumeClick = {
                if (isIndexingPaused) {
                  searchViewModel.resumeIndexing()
                } else {
                  searchViewModel.pauseIndexing()
                }
              },
              isNotificationRequested = isNotificationRequested,
              onNotificationRequested = { searchViewModel.requestIndexingNotification() },
              onNotificationCancelled = { searchViewModel.cancelIndexingNotification() },
              snackbarHostState = snackbarHostState,
              onDismiss = {
                isCardDismissed = true
                showProgressPopup = false
              },
            )
          }

          // Example suggestion chips (shown above search bar when search input is focused with
          // empty
          // query and keyboard is visible)
          if (
            searchText.isBlank() && selectedImageAsset == null && isSearchFocused && isImeVisible
          ) {
            LazyRow(
              modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
              contentPadding = PaddingValues(horizontal = 16.dp),
              horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
              items(suggestionTerms) { term ->
                Surface(
                  modifier =
                    Modifier.clickable {
                      logButtonClick("smartalbum_select_suggestion_chip")
                      searchViewModel.onSearchTextChanged(term, immediate = true)
                      clearFocusAndHideKeyboard()
                    },
                  shape = RoundedCornerShape(24.dp),
                  color = MaterialTheme.colorScheme.secondaryContainer,
                  shadowElevation = 2.dp,
                ) {
                  Text(
                    text = term,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(horizontal = 18.dp, vertical = 8.dp),
                  )
                }
              }
            }
          }

          val photoPickerLauncher =
            rememberLauncherForActivityResult(contract = ActivityResultContracts.GetContent()) { uri
              ->
              uri?.let {
                searchViewModel.selectImageFromUri(it)
                clearFocusAndHideKeyboard()
              }
            }

          // Search bar
          Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.surfaceContainer,
            shadowElevation = 6.dp,
            modifier =
              Modifier.fillMaxWidth().padding(horizontal = 16.dp).height(56.dp).clickable {
                focusRequester.requestFocus()
                keyboardController?.show()
              },
          ) {
            Row(
              modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp),
              verticalAlignment = Alignment.CenterVertically,
            ) {
              if (selectedImageAsset != null) {
                // Image attachment chip: [ (thumbnail) IMG3001.JPG  X ]
                val chipBitmap by
                  produceState<Bitmap?>(initialValue = null, key1 = selectedImageAsset!!.id) {
                    value =
                      searchViewModel.photoLibraryService.loadBitmap(
                        selectedImageAsset!!,
                        targetDimension = 128,
                      )
                  }

                Surface(
                  shape = RoundedCornerShape(16.dp),
                  color = MaterialTheme.colorScheme.primaryContainer,
                  modifier = Modifier.padding(end = 8.dp),
                ) {
                  Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                  ) {
                    if (chipBitmap != null) {
                      Image(
                        bitmap = chipBitmap!!.asImageBitmap(),
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.size(24.dp).clip(RoundedCornerShape(4.dp)),
                      )
                      Spacer(modifier = Modifier.width(6.dp))
                    }
                    Text(
                      text = selectedImageAsset!!.displayName.ifEmpty { "<unknown>" },
                      style = MaterialTheme.typography.bodyMedium,
                      fontWeight = FontWeight.Bold,
                      color = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Icon(
                      imageVector = Icons.Default.Clear,
                      contentDescription = "Remove image",
                      tint = MaterialTheme.colorScheme.onPrimaryContainer,
                      modifier =
                        Modifier.size(16.dp).clickable {
                          logButtonClick("smartalbum_remove_image_attachment")
                          searchViewModel.clearSelectedImage()
                          clearFocusAndHideKeyboard()
                        },
                    )
                  }
                }
              } else {
                var showAddMenu by remember { mutableStateOf(false) }
                Box {
                  IconButton(
                    onClick = {
                      logButtonClick("smartalbum_open_search_add_menu")
                      showAddMenu = true
                    }
                  ) {
                    Icon(
                      imageVector = Icons.Default.Add,
                      contentDescription = stringResource(R.string.add),
                      tint = MaterialTheme.colorScheme.onSurfaceVariant,
                      modifier = Modifier.size(24.dp),
                    )
                  }
                  DropdownMenu(expanded = showAddMenu, onDismissRequest = { showAddMenu = false }) {
                    DropdownMenuItem(
                      text = { Text(stringResource(R.string.smartalbum_search_with_camera)) },
                      leadingIcon = {
                        Icon(
                          imageVector = Icons.Outlined.PhotoCamera,
                          contentDescription = null,
                          tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                      },
                      onClick = {
                        logButtonClick("smartalbum_search_with_camera")
                        showAddMenu = false
                        clearFocusAndHideKeyboard()
                        searchViewModel.openLiveCameraSearch()
                      },
                    )
                    DropdownMenuItem(
                      text = { Text(stringResource(R.string.smartalbum_search_with_image)) },
                      leadingIcon = {
                        Icon(
                          imageVector = Icons.Outlined.Image,
                          contentDescription = null,
                          tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                      },
                      onClick = {
                        logButtonClick("smartalbum_search_with_image")
                        showAddMenu = false
                        clearFocusAndHideKeyboard()
                        photoPickerLauncher.launch("image/*")
                      },
                    )
                  }
                }
              }

              Box(modifier = Modifier.weight(1f)) {
                if (searchText.isEmpty() && selectedImageAsset == null) {
                  Text(
                    text = stringResource(R.string.smartalbum_search_placeholder_term),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                  )
                }
                BasicTextField(
                  value = searchText,
                  onValueChange = { searchViewModel.onSearchTextChanged(it) },
                  singleLine = true,
                  textStyle =
                    MaterialTheme.typography.bodyLarge.copy(
                      color = MaterialTheme.colorScheme.onSurface
                    ),
                  keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                  keyboardActions = KeyboardActions(onSearch = { clearFocusAndHideKeyboard() }),
                  modifier =
                    Modifier.fillMaxWidth().focusRequester(focusRequester).onFocusChanged {
                      focusState ->
                      isSearchFocused = focusState.isFocused
                    },
                )
              }

              if (searchText.isNotEmpty() || selectedImageAsset != null) {
                IconButton(
                  onClick = {
                    logButtonClick("smartalbum_clear_search_query")
                    searchViewModel.clearSearch()
                    searchViewModel.clearSelectedImage()
                    clearFocusAndHideKeyboard()
                  }
                ) {
                  Icon(
                    imageVector = Icons.Default.Clear,
                    contentDescription = "Clear",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                  )
                }
              } else {
                IconButton(
                  onClick = {
                    logButtonClick("smartalbum_focus_search_input")
                    focusRequester.requestFocus()
                    keyboardController?.show()
                  }
                ) {
                  Icon(
                    imageVector = Icons.Default.Search,
                    contentDescription = "Search",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(24.dp),
                  )
                }
              }
            }
          }
        }
      }
    }
  }
}

private val DESATURATED_COLOR_FILTER =
  ColorFilter.colorMatrix(ColorMatrix().apply { setToSaturation(0.2f) })

@Composable
private fun BackgroundAssetGridTile(
  asset: PhotoAsset,
  isAnalyzed: Boolean,
  isSelected: Boolean,
  photoLibraryService: PhotoLibraryService,
  onClick: () -> Unit,
  onLongClick: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val initialBitmap =
    remember(asset.id) { photoLibraryService.getCachedBitmap(asset, targetDimension = 256) }
  val bitmap by
    produceState<Bitmap?>(initialValue = initialBitmap, key1 = asset.id) {
      if (value == null) {
        value = photoLibraryService.loadBitmap(asset, targetDimension = 256)
      }
    }

  Box(
    modifier =
      modifier
        .aspectRatio(1f)
        .clip(RoundedCornerShape(8.dp))
        .then(
          if (isSelected) {
            Modifier.border(
              BorderStroke(2.5.dp, MaterialTheme.colorScheme.primary),
              shape = RoundedCornerShape(8.dp),
            )
          } else {
            Modifier
          }
        )
        .background(MaterialTheme.colorScheme.surfaceVariant)
        .combinedClickable(onClick = onClick, onLongClick = onLongClick)
  ) {
    if (bitmap != null) {
      Image(
        bitmap = bitmap!!.asImageBitmap(),
        contentDescription = null,
        contentScale = ContentScale.Crop,
        colorFilter = if (!isAnalyzed) DESATURATED_COLOR_FILTER else null,
        modifier =
          Modifier.fillMaxSize()
            .then(
              when {
                !isAnalyzed -> Modifier.alpha(0.75f)
                isSelected -> Modifier.alpha(0.85f)
                else -> Modifier
              }
            ),
      )
      if (isSelected) {
        Box(
          modifier =
            Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface.copy(alpha = 0.2f))
        )
      }
    }

    if (isSelected) {
      Box(
        modifier =
          Modifier.align(Alignment.TopStart)
            .padding(6.dp)
            .size(20.dp)
            .background(MaterialTheme.colorScheme.primary, shape = CircleShape),
        contentAlignment = Alignment.Center,
      ) {
        Icon(
          imageVector = Icons.Rounded.Check,
          contentDescription = "Selected",
          tint = MaterialTheme.colorScheme.onPrimary,
          modifier = Modifier.size(14.dp),
        )
      }
    }

    // Fade out to black the bottom of the photo
    Box(
      modifier =
        Modifier.fillMaxSize()
          .background(
            Brush.verticalGradient(
              0.45f to Color.Transparent,
              1.0f to Color.Black.copy(alpha = 0.8f),
            )
          )
    )

    Text(
      text =
        if (isAnalyzed) stringResource(R.string.smartalbum_analyzed)
        else stringResource(R.string.smartalbum_analyzing),
      style = MaterialTheme.typography.labelSmall,
      color = Color.White,
      fontWeight = FontWeight.Bold,
      fontSize = 11.sp,
      modifier = Modifier.align(Alignment.BottomStart).padding(start = 8.dp, bottom = 6.dp),
    )

    if (asset.isVideo) {
      Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.align(Alignment.BottomEnd).padding(end = 8.dp, bottom = 6.dp),
      ) {
        Icon(
          imageVector = Icons.Default.PlayArrow,
          contentDescription = "Video",
          tint = Color.White,
          modifier = Modifier.size(12.dp),
        )
        val durationMs = asset.durationMs
        if (durationMs != null && durationMs > 0L) {
          Spacer(modifier = Modifier.width(2.dp))
          Text(
            text = formatDuration(durationMs),
            style = MaterialTheme.typography.labelSmall,
            color = Color.White,
            fontSize = 10.sp,
          )
        }
      }
    }
  }
}

@Composable
private fun SelectionBottomBar(
  selectedCount: Int,
  isAllSelected: Boolean,
  onSelectAll: () -> Unit,
  onDeselectAll: () -> Unit,
  onRemove: () -> Unit,
  onClose: () -> Unit,
  modifier: Modifier = Modifier,
) {
  Surface(
    shape = CircleShape,
    color = MaterialTheme.colorScheme.surfaceContainer,
    shadowElevation = 8.dp,
    modifier = modifier.padding(horizontal = 16.dp).height(56.dp),
  ) {
    Row(
      modifier = Modifier.padding(start = 16.dp, end = 6.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
      Text(
        text =
          pluralStringResource(
            R.plurals.smartalbum_selected_count_format,
            selectedCount,
            selectedCount,
          ),
        style = MaterialTheme.typography.bodyMedium,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.onSurface,
      )

      Surface(
        shape = CircleShape,
        color = MaterialTheme.colorScheme.secondaryContainer,
        modifier = Modifier.clickable { if (isAllSelected) onDeselectAll() else onSelectAll() },
      ) {
        Text(
          text =
            if (isAllSelected) stringResource(R.string.smartalbum_deselect_all)
            else stringResource(R.string.smartalbum_select_all),
          style = MaterialTheme.typography.labelMedium,
          fontWeight = FontWeight.Bold,
          color = MaterialTheme.colorScheme.onSecondaryContainer,
          modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
        )
      }

      Surface(
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surfaceContainer,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        modifier = Modifier.clickable(enabled = selectedCount > 0) { onRemove() },
      ) {
        Row(
          verticalAlignment = Alignment.CenterVertically,
          modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
        ) {
          Icon(
            imageVector = Icons.Outlined.Delete,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(18.dp),
          )
          Spacer(modifier = Modifier.width(4.dp))
          Text(
            text = stringResource(R.string.smartalbum_remove),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
      }

      IconButton(onClick = onClose, modifier = Modifier.size(36.dp)) {
        Icon(
          imageVector = Icons.Default.Clear,
          contentDescription = "Close selection",
          tint = MaterialTheme.colorScheme.onSurfaceVariant,
          modifier = Modifier.size(20.dp),
        )
      }
    }
  }
}

@Composable
private fun RemovePhotosConfirmationDialog(
  selectedCount: Int,
  onConfirm: () -> Unit,
  onDismiss: () -> Unit,
  onCancelButtonClick: () -> Unit = {},
) {
  Dialog(
    onDismissRequest = onDismiss,
    properties = DialogProperties(usePlatformDefaultWidth = false),
  ) {
    Surface(
      shape = RoundedCornerShape(28.dp),
      color = MaterialTheme.colorScheme.surfaceContainer,
      modifier = Modifier.fillMaxWidth().padding(horizontal = 28.dp),
    ) {
      Column(modifier = Modifier.padding(24.dp)) {
        Text(
          text =
            pluralStringResource(
              R.plurals.smartalbum_remove_confirm_title,
              selectedCount,
              selectedCount,
            ),
          style = MaterialTheme.typography.headlineSmall,
          fontWeight = FontWeight.Normal,
          color = MaterialTheme.colorScheme.onSurface,
          lineHeight = 32.sp,
        )

        Spacer(modifier = Modifier.height(16.dp))

        Text(
          text = stringResource(R.string.smartalbum_remove_confirm_body),
          style = MaterialTheme.typography.bodyMedium,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(modifier = Modifier.height(24.dp))

        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.End,
          verticalAlignment = Alignment.CenterVertically,
        ) {
          TextButton(
            onClick = {
              onCancelButtonClick()
              onDismiss()
            }
          ) {
            Text(
              text = stringResource(R.string.cancel),
              style = MaterialTheme.typography.labelLarge,
              fontWeight = FontWeight.Bold,
              color = MaterialTheme.colorScheme.primary,
            )
          }

          Spacer(modifier = Modifier.width(8.dp))

          Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.clickable { onConfirm() },
          ) {
            Text(
              text = stringResource(R.string.smartalbum_yes_remove),
              style = MaterialTheme.typography.labelLarge,
              fontWeight = FontWeight.Bold,
              color = MaterialTheme.colorScheme.onPrimary,
              modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp),
            )
          }
        }
      }
    }
  }
}

private fun formatDate(timestamp: Long): String {
  if (timestamp <= 0) return "Unknown"
  val sdf = SimpleDateFormat("MMM d, yyyy", Locale.getDefault())
  return sdf.format(Date(timestamp))
}

private fun formatTimeAndLocation(timestamp: Long, location: String?): String {
  val loc = location ?: "Location unavailable"
  if (timestamp <= 0) return "9:19am  $loc"
  val sdf = SimpleDateFormat("h:mma", Locale.getDefault())
  val timeStr = sdf.format(Date(timestamp)).lowercase()
  return "$timeStr  $loc"
}

private fun formatFullDateTime(context: Context, timestamp: Long): String {
  if (timestamp <= 0) return context.getString(R.string.smartalbum_unknown_date)
  return DateUtils.formatDateTime(
    context,
    timestamp,
    DateUtils.FORMAT_SHOW_DATE or
      DateUtils.FORMAT_SHOW_YEAR or
      DateUtils.FORMAT_ABBREV_MONTH or
      DateUtils.FORMAT_SHOW_TIME,
  )
}

private fun formatFileSize(bytes: Long): String {
  if (bytes <= 0) return "Unknown size"
  val kb = bytes / 1024.0
  val mb = kb / 1024.0
  return if (mb >= 1.0) {
    String.format(Locale.getDefault(), "%.1f MB", mb)
  } else {
    String.format(Locale.getDefault(), "%.0f KB", kb)
  }
}

@Composable
private fun PhotoDetailDialog(
  initialMatch: MatchedAssetResult,
  allMatches: List<MatchedAssetResult>,
  searchViewModel: SmartAlbumSearchViewModel,
  onDismiss: () -> Unit,
  onSearchSimilar: (PhotoAsset) -> Unit,
  onSearchLocation: (String) -> Unit = {},
) {
  val photoLibraryService = searchViewModel.photoLibraryService
  val semanticRetrievalService = searchViewModel.currentRetrievalService
  val activeSource by searchViewModel.activeSource.collectAsState()

  val initialIndex =
    remember(initialMatch, allMatches) {
      allMatches.indexOfFirst { it.asset.id == initialMatch.asset.id }.coerceAtLeast(0)
    }
  val pagerState = rememberPagerState(initialPage = initialIndex) { allMatches.size }
  val coroutineScope = rememberCoroutineScope()
  val lazyListState = rememberLazyListState()

  var showInfoScreen by remember { mutableStateOf(false) }

  LaunchedEffect(pagerState.currentPage) {
    if (allMatches.isNotEmpty()) {
      lazyListState.animateScrollToItem(pagerState.currentPage)
    }
  }

  val currentMatch = allMatches.getOrNull(pagerState.currentPage) ?: initialMatch
  val isSamplePhoto =
    activeSource == SmartAlbumSource.SAMPLE_ALBUM || currentMatch.asset.id.startsWith("sample_")
  val detailedAsset by
    produceState(initialValue = currentMatch.asset, key1 = currentMatch.asset.id) {
      if (!isSamplePhoto) {
        value = photoLibraryService.fetchDetailedAsset(currentMatch.asset)
      }
    }
  val detailedCurrentMatch = currentMatch.copy(asset = detailedAsset)

  Dialog(
    onDismissRequest = onDismiss,
    properties = DialogProperties(usePlatformDefaultWidth = false),
  ) {
    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
      AnimatedContent(
        targetState = showInfoScreen,
        transitionSpec = {
          if (targetState) {
            slideInVertically(
              animationSpec = tween(300),
              initialOffsetY = { fullHeight -> fullHeight },
            ) + fadeIn(animationSpec = tween(300)) togetherWith
              slideOutVertically(
                animationSpec = tween(300),
                targetOffsetY = { fullHeight -> -fullHeight },
              ) + fadeOut(animationSpec = tween(300))
          } else {
            slideInVertically(
              animationSpec = tween(300),
              initialOffsetY = { fullHeight -> -fullHeight },
            ) + fadeIn(animationSpec = tween(300)) togetherWith
              slideOutVertically(
                animationSpec = tween(300),
                targetOffsetY = { fullHeight -> fullHeight },
              ) + fadeOut(animationSpec = tween(300))
          }
        },
        label = "EmbeddingInfoTransition",
      ) { isInfo ->
        if (isInfo) {
          EmbeddingInfoScreen(
            match = detailedCurrentMatch,
            photoLibraryService = photoLibraryService,
            semanticRetrievalService = semanticRetrievalService,
            onBack = { showInfoScreen = false },
            onSearchLocation = onSearchLocation,
            isSamplePhoto = isSamplePhoto,
          )
        } else {
          Column(modifier = Modifier.fillMaxSize()) {
            // Top Header Bar
            Row(
              modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp),
              verticalAlignment = Alignment.CenterVertically,
              horizontalArrangement = Arrangement.SpaceBetween,
            ) {
              IconButton(
                onClick = {
                  logButtonClick("smartalbum_photo_detail_back")
                  onDismiss()
                }
              ) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
              }

              Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                  text = formatDate(detailedAsset.dateTaken),
                  style = MaterialTheme.typography.titleMedium,
                  fontWeight = FontWeight.Bold,
                )
                Text(
                  text =
                    if (isSamplePhoto) {
                      stringResource(R.string.smartalbum_sample_photo_credit)
                    } else {
                      formatTimeAndLocation(
                        detailedAsset.dateTaken,
                        detailedAsset.getLocationString(),
                      )
                    },
                  style = MaterialTheme.typography.bodySmall,
                  color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
              }

              IconButton(
                onClick = {
                  logButtonClick("smartalbum_photo_detail_close")
                  onDismiss()
                }
              ) {
                Icon(Icons.Default.Clear, contentDescription = "Close")
              }
            }

            // Horizontal Pager for Photo/Video Carousel (Swipe Left / Right)
            HorizontalPager(
              state = pagerState,
              modifier =
                Modifier.weight(1f).fillMaxWidth().background(MaterialTheme.colorScheme.scrim),
            ) { page ->
              val matchForPage = allMatches[page]
              val isCurrentPage = page == pagerState.currentPage

              Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                if (matchForPage.asset.isVideo) {
                  FullscreenVideoPlayer(
                    asset = matchForPage.asset,
                    photoLibraryService = photoLibraryService,
                    isPageActive = isCurrentPage,
                    onDismiss = onDismiss,
                  )
                } else {
                  FullscreenImageViewer(
                    asset = matchForPage.asset,
                    photoLibraryService = photoLibraryService,
                    onDismiss = onDismiss,
                  )
                }

                // Top-Left Matching Score Badge
                val similarity = matchForPage.similarity
                if (similarity != null) {
                  Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    shadowElevation = 4.dp,
                    modifier = Modifier.align(Alignment.TopStart).padding(16.dp),
                  ) {
                    val score = (similarity * 100).toInt()
                    Text(
                      text = stringResource(R.string.smartalbum_match_format, score),
                      style = MaterialTheme.typography.labelMedium,
                      color = MaterialTheme.colorScheme.onSecondaryContainer,
                      fontWeight = FontWeight.Bold,
                      modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                  }
                }

                // Bottom Action Button: Find similar images
                Surface(
                  onClick = {
                    logButtonClick("smartalbum_find_similar_images")
                    onSearchSimilar(matchForPage.asset)
                  },
                  shape = CircleShape,
                  color = MaterialTheme.colorScheme.primaryContainer,
                  shadowElevation = 4.dp,
                  modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 16.dp),
                ) {
                  Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp),
                  ) {
                    Icon(
                      imageVector = Icons.Outlined.ImageSearch,
                      contentDescription = null,
                      tint = MaterialTheme.colorScheme.onPrimaryContainer,
                      modifier = Modifier.size(24.dp),
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                      text = stringResource(R.string.smartalbum_find_similar_images),
                      style = MaterialTheme.typography.bodyMedium,
                      color = MaterialTheme.colorScheme.onPrimaryContainer,
                      fontWeight = FontWeight.SemiBold,
                    )
                  }
                }
              }
            }

            // Carousel of Other Results
            if (allMatches.isNotEmpty()) {
              LazyRow(
                state = lazyListState,
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface),
              ) {
                items(allMatches.size) { index ->
                  val match = allMatches[index]
                  val isSelected = index == pagerState.currentPage
                  CarouselThumbnailTile(
                    match = match,
                    isSelected = isSelected,
                    photoLibraryService = photoLibraryService,
                    onClick = {
                      logButtonClick("smartalbum_select_carousel_thumbnail")
                      coroutineScope.launch { pagerState.animateScrollToPage(index) }
                    },
                  )
                }
              }
            }

            // Bottom Expandable Embedding Info Bar
            Surface(
              shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
              color = MaterialTheme.colorScheme.surfaceContainerHigh,
              modifier =
                Modifier.fillMaxWidth().clickable {
                  logButtonClick("smartalbum_open_embedding_info")
                  showInfoScreen = true
                },
            ) {
              Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                Row(
                  verticalAlignment = Alignment.CenterVertically,
                  modifier = Modifier.fillMaxWidth(),
                ) {
                  Icon(
                    imageVector = Icons.Outlined.Info,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp),
                  )
                  Spacer(modifier = Modifier.width(12.dp))
                  Text(
                    text = stringResource(R.string.smartalbum_embedding_info),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                  )
                  Icon(
                    imageVector = Icons.Default.ArrowDropUp,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                  )
                }
              }
            }
          }
        }
      }
    }
  }
}

@Composable
private fun FullscreenImageViewer(
  asset: PhotoAsset,
  photoLibraryService: PhotoLibraryService,
  onDismiss: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val pageBitmap by
    produceState<Bitmap?>(initialValue = null, key1 = asset.id) {
      value = photoLibraryService.loadBitmap(asset, targetDimension = 1024)
    }

  var scale by remember(asset.id) { mutableFloatStateOf(1f) }
  var offset by remember(asset.id) { mutableStateOf(Offset.Zero) }

  Box(
    modifier =
      modifier
        .fillMaxSize()
        .clipToBounds()
        .pointerInput(asset.id) {
          detectTapGestures(
            onDoubleTap = { tapOffset ->
              if (scale > 1f) {
                scale = 1f
                offset = Offset.Zero
              } else {
                scale = 2.5f
                val centerX = size.width / 2f
                val centerY = size.height / 2f
                offset =
                  Offset(
                    (centerX - tapOffset.x) * (2.5f - 1f),
                    (centerY - tapOffset.y) * (2.5f - 1f),
                  )
              }
            }
          )
        }
        .then(
          if (scale > 1f) {
            Modifier.pointerInput(asset.id) {
              detectTransformGestures { _, pan, zoom, _ ->
                val newScale = (scale * zoom).coerceIn(1f, 5f)
                if (newScale > 1f) {
                  val maxOffsetX = (size.width * (newScale - 1f)) / 2f
                  val maxOffsetY = (size.height * (newScale - 1f)) / 2f
                  val newOffset = offset + pan
                  offset =
                    Offset(
                      x = newOffset.x.coerceIn(-maxOffsetX, maxOffsetX),
                      y = newOffset.y.coerceIn(-maxOffsetY, maxOffsetY),
                    )
                } else {
                  offset = Offset.Zero
                }
                scale = newScale
              }
            }
          } else {
            Modifier.pointerInput(asset.id) {
              awaitEachGesture {
                awaitFirstDown(requireUnconsumed = false)
                var totalY = 0f
                var totalX = 0f
                var isVerticalSwipe = false

                while (true) {
                  val event = awaitPointerEvent()
                  val change = event.changes.firstOrNull() ?: break
                  if (!change.pressed) break

                  val delta = change.positionChange()
                  totalY += delta.y
                  totalX += delta.x

                  if (
                    !isVerticalSwipe && totalY > 30f && Math.abs(totalY) > Math.abs(totalX) * 1.5f
                  ) {
                    isVerticalSwipe = true
                  }

                  if (isVerticalSwipe && totalY > 120f) {
                    change.consume()
                    onDismiss()
                    break
                  }
                }
              }
            }
          }
        ),
    contentAlignment = Alignment.Center,
  ) {
    if (pageBitmap != null) {
      Image(
        bitmap = pageBitmap!!.asImageBitmap(),
        contentDescription = null,
        contentScale = ContentScale.Fit,
        modifier =
          Modifier.fillMaxSize()
            .graphicsLayer(
              scaleX = scale,
              scaleY = scale,
              translationX = offset.x,
              translationY = offset.y,
            ),
      )
    } else {
      CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
    }
  }
}

private const val VIDEO_SEEK_STEP_MS = 5_000

internal enum class VideoSeekDirection {
  BACKWARD,
  FORWARD,
}

@Composable
internal fun FullscreenVideoPlayer(
  asset: PhotoAsset,
  photoLibraryService: PhotoLibraryService,
  modifier: Modifier = Modifier,
  isPageActive: Boolean = true,
  onDismiss: () -> Unit = {},
  initialIsPrepared: Boolean = true,
  initialIsPlaying: Boolean = true,
  initialSeekFeedback: VideoSeekDirection? = null,
  hideProgressIndicator: Boolean = false,
) {
  var isPrepared by remember(asset.id) { mutableStateOf(initialIsPrepared) }
  var isPlaying by remember(asset.id) { mutableStateOf(initialIsPlaying) }
  var videoViewRef by remember(asset.id) { mutableStateOf<VideoView?>(null) }
  var seekFeedback by remember(asset.id) { mutableStateOf(initialSeekFeedback) }
  var seekFeedbackTrigger by remember(asset.id) { mutableIntStateOf(0) }

  val thumbnailBitmap by
    produceState<Bitmap?>(initialValue = null, key1 = asset.id) {
      value = photoLibraryService.loadBitmap(asset, targetDimension = 1024)
    }

  LaunchedEffect(isPageActive) {
    if (!isPageActive) {
      isPrepared = false
    } else {
      videoViewRef?.let { vv ->
        isPrepared = true
        if (isPlaying && !vv.isPlaying) {
          vv.start()
        }
      }
    }
  }

  LaunchedEffect(isPageActive, isPlaying, isPrepared, videoViewRef) {
    videoViewRef?.let { vv ->
      if (isPageActive && isPlaying && isPrepared) {
        if (!vv.isPlaying) {
          vv.start()
        }
      } else {
        if (vv.isPlaying) {
          vv.pause()
        }
      }
    }
  }

  LaunchedEffect(seekFeedbackTrigger) {
    if (seekFeedbackTrigger > 0) {
      delay(650)
      seekFeedback = null
    }
  }

  DisposableEffect(asset.id) {
    onDispose {
      videoViewRef?.stopPlayback()
      videoViewRef = null
      isPrepared = false
    }
  }

  Box(
    modifier =
      modifier
        .fillMaxSize()
        .clipToBounds()
        .pointerInput(asset.id) {
          detectTapGestures(
            onDoubleTap = { tapOffset ->
              val isLeft = tapOffset.x < size.width / 2f
              logButtonClick(if (isLeft) "smartalbum_video_rewind" else "smartalbum_video_forward")
              videoViewRef?.let { vv ->
                val currentPos = vv.currentPosition
                val duration = vv.duration
                val targetPos =
                  if (isLeft) {
                    maxOf(0, currentPos - VIDEO_SEEK_STEP_MS)
                  } else {
                    if (duration > 0) minOf(duration, currentPos + VIDEO_SEEK_STEP_MS)
                    else currentPos + VIDEO_SEEK_STEP_MS
                  }
                vv.seekTo(targetPos)
                seekFeedback =
                  if (isLeft) VideoSeekDirection.BACKWARD else VideoSeekDirection.FORWARD
                seekFeedbackTrigger++
              }
            },
            onTap = {
              videoViewRef?.let { vv ->
                if (vv.isPlaying) {
                  logButtonClick("smartalbum_video_pause")
                  vv.pause()
                  isPlaying = false
                } else {
                  logButtonClick("smartalbum_video_play")
                  vv.start()
                  isPlaying = true
                }
              }
            },
          )
        }
        .pointerInput(asset.id) {
          awaitEachGesture {
            awaitFirstDown(requireUnconsumed = false)
            var totalY = 0f
            var totalX = 0f
            var isVerticalSwipe = false

            while (true) {
              val event = awaitPointerEvent()
              val change = event.changes.firstOrNull() ?: break
              if (!change.pressed) break

              val delta = change.positionChange()
              totalY += delta.y
              totalX += delta.x

              if (!isVerticalSwipe && totalY > 30f && Math.abs(totalY) > Math.abs(totalX) * 1.5f) {
                isVerticalSwipe = true
              }

              if (isVerticalSwipe && totalY > 120f) {
                change.consume()
                onDismiss()
                break
              }
            }
          }
        },
    contentAlignment = Alignment.Center,
  ) {
    if (thumbnailBitmap != null) {
      Image(
        bitmap = thumbnailBitmap!!.asImageBitmap(),
        contentDescription = null,
        contentScale = ContentScale.Fit,
        modifier = Modifier.fillMaxSize(),
      )
    }

    if (isPageActive) {
      AndroidView(
        factory = { ctx ->
          VideoView(ctx).apply {
            layoutParams =
              FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
                Gravity.CENTER,
              )
            setVideoURI(asset.contentUri)
            setOnPreparedListener { mp ->
              Log.d(TAG, "*** VideoView prepared for ${asset.contentUri}")
              mp.isLooping = true
              isPrepared = true
              if (isPageActive && isPlaying) {
                start()
              }
            }
            setOnErrorListener { _, what, extra ->
              Log.e(TAG, "*** VideoView error: what=$what extra=$extra for ${asset.contentUri}")
              isPrepared = false
              true
            }
            videoViewRef = this
          }
        },
        onRelease = { vv ->
          vv.stopPlayback()
          videoViewRef = null
          isPrepared = false
        },
        update = { vv ->
          videoViewRef = vv
          if (isPageActive && isPlaying && isPrepared) {
            if (!vv.isPlaying) {
              vv.start()
            }
          }
        },
        modifier = Modifier.fillMaxSize(),
      )
    }

    if (!isPrepared && thumbnailBitmap == null && !hideProgressIndicator) {
      CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
    }

    if (isPrepared && !isPlaying) {
      Surface(
        shape = CircleShape,
        color = MaterialTheme.colorScheme.scrim.copy(alpha = 0.6f),
        modifier = Modifier.size(64.dp),
      ) {
        Box(contentAlignment = Alignment.Center) {
          Icon(
            imageVector = Icons.Default.PlayArrow,
            contentDescription = "Play",
            tint = Color.White,
            modifier = Modifier.size(36.dp),
          )
        }
      }
    }

    // Double-tap Seek Feedback: Left (Rewind 5s)
    AnimatedVisibility(
      visible = seekFeedback == VideoSeekDirection.BACKWARD,
      enter = fadeIn() + scaleIn(),
      exit = fadeOut() + scaleOut(),
      modifier = Modifier.align(Alignment.CenterStart).padding(start = 36.dp),
    ) {
      Surface(
        shape = CircleShape,
        color = MaterialTheme.colorScheme.scrim.copy(alpha = 0.7f),
        modifier = Modifier.size(72.dp),
      ) {
        Column(
          horizontalAlignment = Alignment.CenterHorizontally,
          verticalArrangement = Arrangement.Center,
          modifier = Modifier.fillMaxSize(),
        ) {
          Icon(
            imageVector = Icons.Default.FastRewind,
            contentDescription = "Rewind 5s",
            tint = Color.White,
            modifier = Modifier.size(32.dp),
          )
          Text(
            text = "-5s",
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            color = Color.White,
          )
        }
      }
    }

    // Double-tap Seek Feedback: Right (Forward 5s)
    AnimatedVisibility(
      visible = seekFeedback == VideoSeekDirection.FORWARD,
      enter = fadeIn() + scaleIn(),
      exit = fadeOut() + scaleOut(),
      modifier = Modifier.align(Alignment.CenterEnd).padding(end = 36.dp),
    ) {
      Surface(
        shape = CircleShape,
        color = MaterialTheme.colorScheme.scrim.copy(alpha = 0.7f),
        modifier = Modifier.size(72.dp),
      ) {
        Column(
          horizontalAlignment = Alignment.CenterHorizontally,
          verticalArrangement = Arrangement.Center,
          modifier = Modifier.fillMaxSize(),
        ) {
          Icon(
            imageVector = Icons.Default.FastForward,
            contentDescription = "Forward 5s",
            tint = Color.White,
            modifier = Modifier.size(32.dp),
          )
          Text(
            text = "+5s",
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            color = Color.White,
          )
        }
      }
    }
  }
}

@Composable
private fun EmbeddingInfoScreen(
  match: MatchedAssetResult,
  photoLibraryService: PhotoLibraryService,
  semanticRetrievalService: SemanticRetrievalService,
  onBack: () -> Unit,
  onSearchLocation: (String) -> Unit = {},
  isSamplePhoto: Boolean = false,
) {
  val detailedAsset by
    produceState(initialValue = match.asset, key1 = match.asset.id) {
      value = photoLibraryService.fetchDetailedAsset(match.asset)
    }

  val bitmap by
    produceState<Bitmap?>(initialValue = null, key1 = match.asset.id) {
      value = photoLibraryService.loadBitmap(match.asset, targetDimension = 512)
    }

  var record by remember(match.asset.id) { mutableStateOf<SemanticRetrievalResult?>(null) }
  var isGeneratingEmbedding by remember { mutableStateOf(false) }
  val coroutineScope = rememberCoroutineScope()

  LaunchedEffect(match.asset.id) { record = semanticRetrievalService.fetchRecord(match.asset.id) }

  val hasEmbedding = record != null

  var isInputDataExpanded by remember { mutableStateOf(true) }
  var isOutputDataExpanded by remember { mutableStateOf(true) }

  val locationText = detailedAsset.getLocationString()
  val hasLocation = locationText.isNotBlank() && locationText != "Location unavailable"

  Column(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)) {
    // 1. Top Header Bar
    Row(
      modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.SpaceBetween,
    ) {
      IconButton(
        onClick = {
          logButtonClick("smartalbum_embedding_info_back")
          onBack()
        }
      ) {
        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
      }

      Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
          text = formatDate(match.asset.dateTaken),
          style = MaterialTheme.typography.titleMedium,
          fontWeight = FontWeight.Bold,
        )
        Text(
          text =
            if (isSamplePhoto) {
              stringResource(R.string.smartalbum_sample_photo_credit)
            } else {
              formatTimeAndLocation(match.asset.dateTaken, locationText)
            },
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }

      Spacer(modifier = Modifier.width(48.dp))
    }

    // 2. Partial Photo Preview Header (Clicking image navigates back to photo detail view)
    Box(
      modifier =
        Modifier.fillMaxWidth()
          .height(180.dp)
          .background(MaterialTheme.colorScheme.scrim)
          .clickable {
            logButtonClick("smartalbum_embedding_info_preview_close")
            onBack()
          },
      contentAlignment = Alignment.Center,
    ) {
      if (bitmap != null) {
        Image(
          bitmap = bitmap!!.asImageBitmap(),
          contentDescription = null,
          contentScale = ContentScale.Crop,
          modifier = Modifier.fillMaxSize(),
        )
      }
    }

    // 3. Embedding Info Scrollable Content Sheet
    Surface(
      shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
      color = MaterialTheme.colorScheme.surfaceContainerLow,
      modifier = Modifier.fillMaxWidth().weight(1f),
    ) {
      Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
      ) {
        // Main Panel Title Row (Tapping closes the details view)
        Row(
          verticalAlignment = Alignment.CenterVertically,
          modifier =
            Modifier.fillMaxWidth().clickable {
              logButtonClick("smartalbum_embedding_info_close")
              onBack()
            },
        ) {
          Icon(
            imageVector = Icons.Outlined.Info,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(24.dp),
          )
          Spacer(modifier = Modifier.width(12.dp))
          Text(
            text = stringResource(R.string.smartalbum_embedding_info),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
          )
          Icon(
            imageVector = Icons.Default.ArrowDropDown,
            contentDescription = "Close image info",
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }

        // Section 1: INPUT DATA Card
        Surface(
          shape = RoundedCornerShape(16.dp),
          color = MaterialTheme.colorScheme.surfaceContainerLowest,
          shadowElevation = 1.dp,
          modifier = Modifier.fillMaxWidth(),
        ) {
          Column(modifier = Modifier.padding(16.dp)) {
            Row(
              verticalAlignment = Alignment.CenterVertically,
              modifier =
                Modifier.fillMaxWidth().clickable {
                  logButtonClick(
                    if (isInputDataExpanded) "smartalbum_collapse_input_data"
                    else "smartalbum_expand_input_data"
                  )
                  isInputDataExpanded = !isInputDataExpanded
                },
            ) {
              Icon(
                imageVector =
                  if (isInputDataExpanded) Icons.Default.ArrowDropDown
                  else Icons.AutoMirrored.Filled.ArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
              )
              Spacer(modifier = Modifier.width(8.dp))
              Text(
                text = "INPUT DATA",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                letterSpacing = 1.sp,
              )
            }

            if (isInputDataExpanded) {
              Spacer(modifier = Modifier.height(12.dp))

              InfoDetailRow(
                icon = Icons.Outlined.DateRange,
                label = "Date and time: ",
                value = formatFullDateTime(LocalContext.current, match.asset.dateTaken),
              )
              Spacer(modifier = Modifier.height(12.dp))
              InfoDetailRow(
                icon = Icons.Outlined.Place,
                label = "Location: ",
                value = locationText,
                isClickable = hasLocation,
                onClick =
                  if (hasLocation) {
                    {
                      logButtonClick("smartalbum_search_by_location")
                      onSearchLocation(locationText)
                    }
                  } else null,
              )
              Spacer(modifier = Modifier.height(12.dp))
              InfoDetailRow(
                icon = Icons.Outlined.CropOriginal,
                label = "Size: ",
                value = formatFileSize(match.asset.sizeBytes),
              )
              Spacer(modifier = Modifier.height(12.dp))
              InfoDetailRow(
                icon = Icons.Outlined.Smartphone,
                label = "Device: ",
                value = match.asset.deviceModel ?: "Unknown",
              )
            }
          }
        }

        // Section 2: OUTPUT DATA Card
        Surface(
          shape = RoundedCornerShape(16.dp),
          color = MaterialTheme.colorScheme.surfaceContainerLowest,
          shadowElevation = 1.dp,
          modifier = Modifier.fillMaxWidth(),
        ) {
          Column(modifier = Modifier.padding(16.dp)) {
            Row(
              verticalAlignment = Alignment.CenterVertically,
              modifier = Modifier.fillMaxWidth(),
            ) {
              Icon(
                imageVector =
                  if (isOutputDataExpanded) Icons.Default.ArrowDropDown
                  else Icons.AutoMirrored.Filled.ArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier =
                  Modifier.clickable {
                    logButtonClick(
                      if (isOutputDataExpanded) "smartalbum_collapse_output_data"
                      else "smartalbum_expand_output_data"
                    )
                    isOutputDataExpanded = !isOutputDataExpanded
                  },
              )
              Spacer(modifier = Modifier.width(8.dp))
              Text(
                text = "OUTPUT DATA",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                letterSpacing = 1.sp,
                modifier = Modifier.weight(1f),
              )
              if (!hasEmbedding) {
                Surface(
                  shape = RoundedCornerShape(20.dp),
                  color = MaterialTheme.colorScheme.primaryContainer,
                  modifier =
                    Modifier.clickable(enabled = !isGeneratingEmbedding) {
                      logButtonClick("smartalbum_generate_embedding")
                      isGeneratingEmbedding = true
                      coroutineScope.launch {
                        try {
                          val loadedBitmap =
                            bitmap
                              ?: photoLibraryService.loadBitmap(match.asset, targetDimension = 1024)
                          if (loadedBitmap != null) {
                            val tgaBytes = ImageUtils.encodeTga(loadedBitmap)
                            semanticRetrievalService.addRecord(
                              id = match.asset.id,
                              imageData = tgaBytes,
                              metadata =
                                mapOf(
                                  "displayName" to match.asset.displayName,
                                  "locationName" to (match.asset.locationName ?: ""),
                                ),
                            )
                            semanticRetrievalService.saveToDisk()
                            record = semanticRetrievalService.fetchRecord(match.asset.id)
                          }
                        } catch (e: Exception) {
                          Log.e(TAG, "Failed to generate embedding for asset ${match.asset.id}", e)
                        } finally {
                          isGeneratingEmbedding = false
                        }
                      }
                    },
                ) {
                  Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                  ) {
                    if (isGeneratingEmbedding) {
                      CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                      )
                    } else {
                      Icon(
                        imageVector = Icons.Outlined.ImageSearch,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.size(16.dp),
                      )
                    }
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                      text = "Generate embedding",
                      style = MaterialTheme.typography.labelSmall,
                      color = MaterialTheme.colorScheme.onPrimaryContainer,
                      fontWeight = FontWeight.Bold,
                    )
                  }
                }
              }
            }

            if (isOutputDataExpanded) {
              Spacer(modifier = Modifier.height(12.dp))

              Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                  imageVector = Icons.Outlined.Share,
                  contentDescription = null,
                  tint = MaterialTheme.colorScheme.onSurfaceVariant,
                  modifier = Modifier.size(20.dp),
                )
                Spacer(modifier = Modifier.width(12.dp))
                Text(
                  text = "Embedding vector",
                  style = MaterialTheme.typography.bodyMedium,
                  fontWeight = FontWeight.Bold,
                  color = MaterialTheme.colorScheme.onSurface,
                )
              }
              Spacer(modifier = Modifier.height(8.dp))

              Surface(
                shape = RoundedCornerShape(8.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                modifier = Modifier.fillMaxWidth(),
              ) {
                val embeddingDim = record?.embeddings?.size ?: if (hasEmbedding) 768 else null
                val vectorText =
                  if (hasEmbedding && embeddingDim != null) {
                    "[$embeddingDim floats] Computed on-device via EmbeddingGemma"
                  } else {
                    "Not available yet"
                  }
                Text(
                  text = vectorText,
                  style = MaterialTheme.typography.bodyMedium,
                  color = MaterialTheme.colorScheme.onSurface,
                  modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                )
              }

              Spacer(modifier = Modifier.height(12.dp))
              InfoDetailRow(
                icon = if (match.asset.isVideo) Icons.Default.PlayArrow else Icons.Outlined.Image,
                label = "Modality: ",
                value = if (match.asset.isVideo) "Video" else "Image",
              )
              Spacer(modifier = Modifier.height(12.dp))
              InfoDetailRow(
                icon = Icons.Outlined.Info,
                label = "ID: ",
                value = match.asset.id.ifEmpty { "Not available yet" },
              )
            }
          }
        }
      }
    }
  }
}

@Composable
private fun InfoDetailRow(
  icon: ImageVector,
  label: String,
  value: String,
  onClick: (() -> Unit)? = null,
  isClickable: Boolean = false,
) {
  Row(
    verticalAlignment = Alignment.CenterVertically,
    modifier =
      Modifier.then(
        if (isClickable && onClick != null) {
          Modifier.clickable { onClick() }
        } else {
          Modifier
        }
      ),
  ) {
    Icon(
      imageVector = icon,
      contentDescription = null,
      tint =
        if (isClickable) MaterialTheme.colorScheme.primary
        else MaterialTheme.colorScheme.onSurfaceVariant,
      modifier = Modifier.size(20.dp),
    )
    Spacer(modifier = Modifier.width(12.dp))
    Text(
      text = label,
      style = MaterialTheme.typography.bodyMedium,
      fontWeight = FontWeight.Bold,
      color = MaterialTheme.colorScheme.onSurface,
    )
    Text(
      text = value,
      style = MaterialTheme.typography.bodyMedium,
      color =
        if (isClickable) MaterialTheme.colorScheme.primary
        else MaterialTheme.colorScheme.onSurfaceVariant,
    )
  }
}

@Composable
private fun CarouselThumbnailTile(
  match: MatchedAssetResult,
  isSelected: Boolean,
  photoLibraryService: PhotoLibraryService,
  onClick: () -> Unit,
) {
  val initialBitmap =
    remember(match.asset.id) {
      photoLibraryService.getCachedBitmap(match.asset, targetDimension = 128)
    }
  val bitmap by
    produceState<Bitmap?>(initialValue = initialBitmap, key1 = match.asset.id) {
      if (value == null) {
        value = photoLibraryService.loadBitmap(match.asset, targetDimension = 128)
      }
    }

  Surface(
    shape = RoundedCornerShape(8.dp),
    border = if (isSelected) BorderStroke(3.dp, MaterialTheme.colorScheme.primary) else null,
    modifier = Modifier.size(64.dp).clickable { onClick() },
  ) {
    Box(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceVariant)) {
      if (bitmap != null) {
        Image(
          bitmap = bitmap!!.asImageBitmap(),
          contentDescription = null,
          contentScale = ContentScale.Crop,
          modifier = Modifier.fillMaxSize(),
        )
      }
      if (match.asset.isVideo) {
        Surface(
          shape = CircleShape,
          color = MaterialTheme.colorScheme.scrim.copy(alpha = 0.6f),
          modifier = Modifier.align(Alignment.BottomEnd).padding(3.dp).size(16.dp),
        ) {
          Box(contentAlignment = Alignment.Center) {
            Icon(
              imageVector = Icons.Default.PlayArrow,
              contentDescription = "Video",
              tint = Color.White,
              modifier = Modifier.size(10.dp),
            )
          }
        }
      }
    }
  }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PhotoGridTile(
  match: MatchedAssetResult,
  isSelected: Boolean,
  photoLibraryService: PhotoLibraryService,
  onClick: () -> Unit,
  onLongClick: () -> Unit,
  modifier: Modifier = Modifier,
  isBelowThreshold: Boolean = false,
  isSearching: Boolean = false,
) {
  val initialBitmap =
    remember(match.asset.id) {
      photoLibraryService.getCachedBitmap(match.asset, targetDimension = 256)
    }
  val bitmap by
    produceState<Bitmap?>(initialValue = initialBitmap, key1 = match.asset.id) {
      if (value == null) {
        value = photoLibraryService.loadBitmap(match.asset, targetDimension = 256)
      }
    }

  val isGrayedOut = isBelowThreshold || isSearching

  Box(
    modifier =
      modifier
        .aspectRatio(1f)
        .clip(RoundedCornerShape(8.dp))
        .then(
          if (isSelected) {
            Modifier.border(
              BorderStroke(2.5.dp, MaterialTheme.colorScheme.primary),
              shape = RoundedCornerShape(8.dp),
            )
          } else {
            Modifier
          }
        )
        .background(MaterialTheme.colorScheme.surfaceVariant)
        .combinedClickable(onClick = onClick, onLongClick = onLongClick)
  ) {
    if (bitmap != null) {
      Image(
        bitmap = bitmap!!.asImageBitmap(),
        contentDescription = null,
        contentScale = ContentScale.Crop,
        colorFilter = if (isGrayedOut) DESATURATED_COLOR_FILTER else null,
        modifier =
          Modifier.fillMaxSize()
            .then(
              when {
                isGrayedOut -> Modifier.alpha(0.75f)
                isSelected -> Modifier.alpha(0.85f)
                else -> Modifier
              }
            ),
      )
      if (isSelected) {
        Box(
          modifier =
            Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface.copy(alpha = 0.2f))
        )
      }
    }

    // Fade out to black the bottom of the photo
    Box(
      modifier =
        Modifier.fillMaxSize()
          .background(
            Brush.verticalGradient(
              0.45f to Color.Transparent,
              1.0f to Color.Black.copy(alpha = 0.8f),
            )
          )
    )

    if (isSelected) {
      Box(
        modifier =
          Modifier.align(Alignment.TopStart)
            .padding(6.dp)
            .size(20.dp)
            .background(MaterialTheme.colorScheme.primary, shape = CircleShape),
        contentAlignment = Alignment.Center,
      ) {
        Icon(
          imageVector = Icons.Rounded.Check,
          contentDescription = "Selected",
          tint = MaterialTheme.colorScheme.onPrimary,
          modifier = Modifier.size(14.dp),
        )
      }
    }

    if (match.asset.isVideo) {
      Surface(
        shape = RoundedCornerShape(4.dp),
        color = MaterialTheme.colorScheme.scrim.copy(alpha = 0.6f),
        modifier = Modifier.align(Alignment.BottomStart).padding(4.dp),
      ) {
        Row(
          verticalAlignment = Alignment.CenterVertically,
          modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
        ) {
          Icon(
            imageVector = Icons.Default.PlayArrow,
            contentDescription = "Video",
            tint = Color.White,
            modifier = Modifier.size(12.dp),
          )
          val durationMs = match.asset.durationMs
          if (durationMs != null && durationMs > 0L) {
            Spacer(modifier = Modifier.width(2.dp))
            Text(
              text = formatDuration(durationMs),
              style = MaterialTheme.typography.labelSmall,
              color = Color.White,
              fontSize = 10.sp,
            )
          }
        }
      }
    }

    if (match.similarity != null) {
      Text(
        text = String.format(Locale.US, "%.2f", match.similarity),
        style = MaterialTheme.typography.labelSmall,
        color = if (isBelowThreshold) Color.White.copy(alpha = 0.7f) else Color.White,
        fontWeight = FontWeight.Bold,
        fontSize = 11.sp,
        modifier = Modifier.align(Alignment.BottomEnd).padding(end = 8.dp, bottom = 6.dp),
      )
    }
  }
}

@Composable
private fun SmartAlbumSourcePicker(
  activeSource: SmartAlbumSource,
  onSourceSelected: (SmartAlbumSource) -> Unit,
  modifier: Modifier = Modifier,
  isSampleAlbumAvailable: Boolean = true,
) {
  if (!isSampleAlbumAvailable) return

  var expanded by remember { mutableStateOf(false) }

  Box(modifier = modifier) {
    Surface(
      shape = RoundedCornerShape(20.dp),
      color = MaterialTheme.colorScheme.surfaceVariant,
      modifier =
        Modifier.clickable {
          logButtonClick("smartalbum_open_source_picker")
          expanded = true
        },
    ) {
      Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
      ) {
        Text(
          text =
            when (activeSource) {
              SmartAlbumSource.SAMPLE_ALBUM -> stringResource(R.string.smartalbum_sample_photos)
              SmartAlbumSource.USER_PHOTOS -> stringResource(R.string.smartalbum_my_photos)
            },
          style = MaterialTheme.typography.labelMedium,
          fontWeight = FontWeight.Medium,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.width(4.dp))
        Icon(
          imageVector = Icons.Default.ArrowDropDown,
          contentDescription = null,
          tint = MaterialTheme.colorScheme.onSurfaceVariant,
          modifier = Modifier.size(16.dp),
        )
      }
    }

    DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
      DropdownMenuItem(
        text = {
          Text(
            text = stringResource(R.string.smartalbum_sample_photos),
            style = MaterialTheme.typography.bodyMedium,
            fontWeight =
              if (activeSource == SmartAlbumSource.SAMPLE_ALBUM) FontWeight.Bold
              else FontWeight.Normal,
          )
        },
        onClick = {
          logButtonClick(
            "smartalbum_select_source",
            buttonId = SmartAlbumSource.SAMPLE_ALBUM.name.lowercase(),
          )
          expanded = false
          onSourceSelected(SmartAlbumSource.SAMPLE_ALBUM)
        },
      )
      DropdownMenuItem(
        text = {
          Text(
            text = stringResource(R.string.smartalbum_my_photos),
            style = MaterialTheme.typography.bodyMedium,
            fontWeight =
              if (activeSource == SmartAlbumSource.USER_PHOTOS) FontWeight.Bold
              else FontWeight.Normal,
          )
        },
        onClick = {
          logButtonClick(
            "smartalbum_select_source",
            buttonId = SmartAlbumSource.USER_PHOTOS.name.lowercase(),
          )
          expanded = false
          onSourceSelected(SmartAlbumSource.USER_PHOTOS)
        },
      )
    }
  }
}

@Composable
private fun CameraSearchHeaderBar(
  activeSource: SmartAlbumSource,
  isSampleAlbumAvailable: Boolean,
  onSourceSelected: (SmartAlbumSource) -> Unit,
  onBack: () -> Unit,
  modifier: Modifier = Modifier,
) {
  Row(
    modifier = modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    IconButton(
      onClick = {
        logButtonClick("smartalbum_camera_search_back")
        onBack()
      }
    ) {
      Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
    }

    Column(modifier = Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
      Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
          imageVector = Icons.Filled.PhotoCamera,
          contentDescription = null,
          tint = MaterialTheme.colorScheme.onSurface,
          modifier = Modifier.size(20.dp),
        )
        Spacer(modifier = Modifier.width(6.dp))
        Text(
          text = stringResource(R.string.smartalbum_search_with_camera),
          style = MaterialTheme.typography.titleMedium,
          fontWeight = FontWeight.Bold,
          color = MaterialTheme.colorScheme.onSurface,
        )
      }
      Spacer(modifier = Modifier.height(2.dp))
      Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
          text = stringResource(R.string.smartalbum_searching_prefix),
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.width(4.dp))
        SmartAlbumSourcePicker(
          activeSource = activeSource,
          isSampleAlbumAvailable = isSampleAlbumAvailable,
          onSourceSelected = onSourceSelected,
        )
      }
    }

    Spacer(modifier = Modifier.width(48.dp))
  }
}

@Composable
private fun CameraPermissionRequestContent(
  activeSource: SmartAlbumSource,
  isSampleAlbumAvailable: Boolean,
  onSourceSelected: (SmartAlbumSource) -> Unit,
  onBack: () -> Unit,
  onRequestPermission: () -> Unit,
  modifier: Modifier = Modifier,
) {
  Column(modifier = modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)) {
    CameraSearchHeaderBar(
      activeSource = activeSource,
      isSampleAlbumAvailable = isSampleAlbumAvailable,
      onSourceSelected = onSourceSelected,
      onBack = onBack,
    )

    Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
      Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.padding(horizontal = 32.dp),
      ) {
        Text(
          text = stringResource(R.string.smartalbum_grant_camera_access_title),
          style = MaterialTheme.typography.headlineSmall,
          fontWeight = FontWeight.Bold,
          textAlign = TextAlign.Center,
          color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(modifier = Modifier.height(24.dp))
        Surface(
          shape = CircleShape,
          color = MaterialTheme.colorScheme.primary,
          modifier =
            Modifier.clickable {
              logButtonClick("smartalbum_grant_camera_permission")
              onRequestPermission()
            },
        ) {
          Text(
            text = stringResource(R.string.smartalbum_grant_access),
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onPrimary,
            modifier = Modifier.padding(horizontal = 28.dp, vertical = 14.dp),
          )
        }
      }
    }
  }
}

@Composable
private fun LiveCameraSearchContent(
  searchViewModel: SmartAlbumSearchViewModel,
  activeSource: SmartAlbumSource,
  onClose: () -> Unit,
  onSelectMatch: (MatchedAssetResult) -> Unit,
  snackbarHostState: SnackbarHostState,
  modifier: Modifier = Modifier,
) {
  var cameraSelector by remember { mutableStateOf(CameraSelector.DEFAULT_BACK_CAMERA) }
  val isCameraSearchPaused by searchViewModel.isCameraSearchPaused.collectAsState()
  val cameraSearchResults by searchViewModel.cameraSearchResults.collectAsState()
  val isCameraSearching by searchViewModel.isCameraSearching.collectAsState()
  val carouselListState = rememberLazyListState()

  LaunchedEffect(cameraSearchResults) {
    if (cameraSearchResults.isNotEmpty()) {
      carouselListState.scrollToItem(0)
    }
  }

  Column(modifier = modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)) {
    CameraSearchHeaderBar(
      activeSource = activeSource,
      isSampleAlbumAvailable = searchViewModel.isSampleAlbumAvailable,
      onSourceSelected = { searchViewModel.selectSource(it) },
      onBack = onClose,
    )

    Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
      LiveCameraView(
        onBitmap = { bitmap, imageProxy ->
          if (!isCameraSearchPaused) {
            searchViewModel.processCameraFrame(bitmap)
          }
          imageProxy.close()
        },
        cameraSelector = cameraSelector,
        preferredSize = 1080,
        isPaused = isCameraSearchPaused,
        onError = onClose,
        modifier = Modifier.fillMaxSize(),
      )

      Box(
        modifier =
          Modifier.align(Alignment.TopCenter)
            .fillMaxWidth()
            .padding(top = 20.dp, start = 24.dp, end = 24.dp),
        contentAlignment = Alignment.Center,
      ) {
        val promptText =
          if (cameraSearchResults.isNotEmpty()) {
            stringResource(R.string.smartalbum_camera_searching)
          } else {
            stringResource(R.string.smartalbum_camera_search_prompt)
          }
        Text(
          text = promptText,
          style = MaterialTheme.typography.titleMedium,
          fontWeight = FontWeight.SemiBold,
          color = Color.White,
          textAlign = TextAlign.Center,
        )
      }

      SnackbarHost(
        hostState = snackbarHostState,
        modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 88.dp),
      )

      Row(
        modifier =
          Modifier.align(Alignment.BottomCenter)
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 20.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Surface(
          shape = CircleShape,
          color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.9f),
          shadowElevation = 4.dp,
          modifier =
            Modifier.size(56.dp).clickable {
              logButtonClick(
                if (isCameraSearchPaused) "smartalbum_resume_camera" else "smartalbum_pause_camera"
              )
              searchViewModel.toggleCameraSearchPause()
            },
        ) {
          Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Icon(
              imageVector =
                if (isCameraSearchPaused) Icons.Default.PlayArrow else Icons.Default.Pause,
              contentDescription =
                if (isCameraSearchPaused) stringResource(R.string.smartalbum_resume_camera)
                else stringResource(R.string.smartalbum_pause_camera),
              tint = MaterialTheme.colorScheme.onPrimaryContainer,
              modifier = Modifier.size(28.dp),
            )
          }
        }

        Surface(
          shape = CircleShape,
          color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.9f),
          shadowElevation = 4.dp,
          modifier =
            Modifier.size(56.dp).clickable {
              logButtonClick("smartalbum_switch_camera")
              cameraSelector =
                if (cameraSelector == CameraSelector.DEFAULT_FRONT_CAMERA) {
                  CameraSelector.DEFAULT_BACK_CAMERA
                } else {
                  CameraSelector.DEFAULT_FRONT_CAMERA
                }
            },
        ) {
          Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Icon(
              imageVector = Icons.Default.Cameraswitch,
              contentDescription = stringResource(R.string.cd_toggle_front_back_camera_icon),
              tint = MaterialTheme.colorScheme.onPrimaryContainer,
              modifier = Modifier.size(28.dp),
            )
          }
        }
      }
    }

    Box(
      modifier =
        Modifier.fillMaxWidth()
          .height(140.dp)
          .background(MaterialTheme.colorScheme.surfaceContainerLow),
      contentAlignment = Alignment.Center,
    ) {
      if (cameraSearchResults.isEmpty()) {
        Text(
          text = stringResource(R.string.smartalbum_similar_photos_appear_here),
          style = MaterialTheme.typography.bodyMedium,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          textAlign = TextAlign.Center,
        )
      } else {
        LazyRow(
          state = carouselListState,
          contentPadding = PaddingValues(horizontal = 12.dp, vertical = 12.dp),
          horizontalArrangement = Arrangement.spacedBy(8.dp),
          modifier = Modifier.fillMaxSize(),
        ) {
          items(cameraSearchResults.distinctBy { it.asset.id }, key = { it.asset.id }) { match ->
            CameraSearchResultTile(
              match = match,
              photoLibraryService = searchViewModel.photoLibraryService,
              onClick = {
                logButtonClick("smartalbum_select_camera_search_result")
                onSelectMatch(match)
              },
            )
          }
        }
      }
    }
  }
}

@Composable
private fun CameraSearchResultTile(
  match: MatchedAssetResult,
  photoLibraryService: PhotoLibraryService,
  onClick: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val initialBitmap =
    remember(match.asset.id) {
      photoLibraryService.getCachedBitmap(match.asset, targetDimension = 256)
    }
  val bitmap by
    produceState<Bitmap?>(initialValue = initialBitmap, key1 = match.asset.id) {
      if (value == null) {
        value = photoLibraryService.loadBitmap(match.asset, targetDimension = 256)
      }
    }

  Surface(
    shape = RoundedCornerShape(12.dp),
    modifier = modifier.size(116.dp).clickable { onClick() },
  ) {
    Box(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceVariant)) {
      if (bitmap != null) {
        Image(
          bitmap = bitmap!!.asImageBitmap(),
          contentDescription = null,
          contentScale = ContentScale.Crop,
          modifier = Modifier.fillMaxSize(),
        )
      }
      // Fade out to black the bottom of the photo
      Box(
        modifier =
          Modifier.fillMaxSize()
            .background(
              Brush.verticalGradient(
                0.45f to Color.Transparent,
                1.0f to Color.Black.copy(alpha = 0.8f),
              )
            )
      )
      if (match.asset.isVideo) {
        Surface(
          shape = CircleShape,
          color = MaterialTheme.colorScheme.scrim.copy(alpha = 0.6f),
          modifier = Modifier.align(Alignment.BottomEnd).padding(6.dp).size(20.dp),
        ) {
          Box(contentAlignment = Alignment.Center) {
            Icon(
              imageVector = Icons.Default.PlayArrow,
              contentDescription = "Video",
              tint = Color.White,
              modifier = Modifier.size(12.dp),
            )
          }
        }
      }
      if (match.similarity != null) {
        Text(
          text = String.format(Locale.US, "%.2f", match.similarity),
          style = MaterialTheme.typography.labelSmall,
          fontWeight = FontWeight.Bold,
          color = Color.White,
          fontSize = 11.sp,
          modifier = Modifier.align(Alignment.BottomStart).padding(start = 8.dp, bottom = 6.dp),
        )
      }
    }
  }
}

private fun formatDuration(durationMs: Long): String {
  val totalSeconds = durationMs / 1000
  val minutes = totalSeconds / 60
  val seconds = totalSeconds % 60
  return String.format(Locale.getDefault(), "%d:%02d", minutes, seconds)
}
