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

package com.google.ai.edge.gallery.customtasks.videomomentfinder

import android.content.ContentUris
import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.ai.edge.gallery.GalleryEvent
import com.google.ai.edge.gallery.common.logErrorToFirebase
import com.google.ai.edge.gallery.common.openSafeInputStream
import com.google.ai.edge.gallery.data.BuiltInTaskId
import com.google.ai.edge.gallery.di.IoDispatcher
import com.google.ai.edge.gallery.firebaseAnalytics
import com.google.ai.edge.gallery.proto.VideoClip
import com.google.ai.edge.gallery.proto.VideoMomentFinderData
import com.google.ai.edge.gallery.proto.VideoMomentProject
import com.google.ai.edge.gallery.proto.copy
import com.google.ai.edge.gallery.proto.videoClip
import com.google.ai.edge.gallery.proto.videoMomentProject
import com.google.ai.edge.gallery.services.semanticretrieval.EmbedPart
import com.google.ai.edge.gallery.services.semanticretrieval.SemanticRetrievalService
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import javax.inject.Inject
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val TAG = "AGVideoMomentFinderVM"
const val VIDEO_MOMENT_FINDER_DIR = "video_moment_finder"
const val CLIPS_DIR = "clips"
const val VIDEO_FILE_NAME = "video.mp4"
const val THUMBNAIL_FILE_NAME = "thumbnail.jpg"
const val MAX_SEARCH_RESULT_FRAME_DIMENSION = 800
const val DEFAULT_FRAMES_PER_WINDOW = 2
const val DEFAULT_WINDOW_DURATION_SEC = 2
const val DEFAULT_OVERLAP_DURATION_SEC = 0
const val DEFAULT_TOP_K = 5
const val DEFAULT_INCLUDE_AUDIO = true

/**
 * Returns whether audio indexing is enabled for this [VideoMomentProject], falling back to
 * [DEFAULT_INCLUDE_AUDIO] when the field has not been set yet.
 */
internal fun VideoMomentProject.effectiveIncludeAudio(): Boolean =
  if (hasIncludeAudio()) includeAudio else DEFAULT_INCLUDE_AUDIO

/** Metadata key scoping a vector-store record to the video project that produced it. */
internal const val METADATA_KEY_PARENT_ID = "parent_id"

/** Metadata key holding the start timestamp (ms) of an indexed time window. */
internal const val METADATA_KEY_START_TIME_MS = "start_time_ms"

/** Metadata key holding the end timestamp (ms) of an indexed time window. */
internal const val METADATA_KEY_END_TIME_MS = "end_time_ms"

/**
 * Returns the vector-store record ID prefix shared by every window of the project with [projectId].
 */
internal fun projectRecordIdPrefix(projectId: String) = "$projectId/"

/** Returns the vector-store record ID for one time window of the project with [projectId]. */
internal fun windowRecordId(projectId: String, startTimeMs: Long, endTimeMs: Long) =
  "${projectRecordIdPrefix(projectId)}$startTimeMs-$endTimeMs"

/** A single scored frame window, ranked by cosine similarity against the search query. */
internal data class ScoredFrame(val startTimeMs: Long, val endTimeMs: Long, val score: Float)

data class MomentSearchResult(
  val id: String,
  val startTimeMs: Long,
  val endTimeMs: Long,
  val timeMs: Long,
  val frameBitmap: Bitmap,
  val similarityScore: Float? = null,
)

data class MergedMomentInterval(
  val startTimeMs: Long,
  val endTimeMs: Long,
  val resultIds: Set<String> = emptySet(),
)

data class SavedMomentClipItem(
  val clip: VideoClip,
  val videoDisplayName: String,
  val projectId: String,
)

data class SavedMomentsQueryGroup(
  val searchQuery: String,
  val clips: List<SavedMomentClipItem>,
  val videoCount: Int,
)

data class VideoMomentFinderUiState(
  val projects: List<VideoMomentProject> = emptyList(),
  val selectedProject: VideoMomentProject? = null,
  val projectProcessProgress: Float? = null,
  /**
   * Whether the most recent processing pass for [selectedProject] ended without marking it
   * processed.
   *
   * A failed pass and a pass that has not started yet are otherwise identical in this state (no
   * progress, project still unprocessed), so the UI cannot tell them apart without this. It lives
   * here rather than in composition state because the processing job outlives any single
   * composition.
   */
  val projectProcessFailed: Boolean = false,
  /**
   * Whether the failure recorded in [projectProcessFailed] was caused by the video itself (for
   * example a corrupt or truncated file), as opposed to the embedding model or engine.
   *
   * Retrying cannot fix such a video, so [VideoMomentFinderViewModel.closeSelectedProject] removes
   * the project when the user leaves it. Model and engine failures keep the project, since they can
   * clear up on retry.
   */
  val projectProcessFailedDueToVideo: Boolean = false,
  /** Whether the active embedding model supports audio input alongside video frames. */
  val supportsAudio: Boolean = false,
  val searchResults: List<MomentSearchResult> = emptyList(),
  val mergedIntervals: List<MergedMomentInterval> = emptyList(),
  val isSearching: Boolean = false,
  val hasPerformedSearch: Boolean = false,
  val selectedResultId: String? = null,
)

@HiltViewModel
class VideoMomentFinderViewModel
@Inject
constructor(
  private val dataStore: DataStore<VideoMomentFinderData>,
  @ApplicationContext private val context: Context,
  @IoDispatcher private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : ViewModel() {
  private val _uiState = MutableStateFlow(VideoMomentFinderUiState())
  val uiState = _uiState.asStateFlow()

  @Volatile private var semanticRetrievalService: SemanticRetrievalService? = null
  private var processJob: Job? = null
  @Volatile private var processingProjectId: String? = null

  init {
    viewModelScope.launch {
      dataStore.data.collect { data ->
        _uiState.update { state ->
          val updatedSelected =
            state.selectedProject?.let { selected ->
              data.projectsList.find { it.id == selected.id }
            }
          state.copy(projects = data.projectsList, selectedProject = updatedSelected)
        }
      }
    }
  }

  /**
   * Sets the [SemanticRetrievalService] used to index video frame windows and to run semantic
   * search over them. Supplied by the task once the embedding model is initialized.
   */
  fun setSemanticRetrievalService(service: SemanticRetrievalService?) {
    this.semanticRetrievalService = service
    if (service != null) {
      viewModelScope.launch { markUnindexedProjectsAsUnprocessed(service) }
    } else {
      _uiState.update { it.copy(supportsAudio = false) }
    }
  }

  /**
   * Updates the top K results count for the given [project] and persists the change to [dataStore].
   */
  fun setTopK(project: VideoMomentProject, topK: Int) {
    _uiState.update { state ->
      val updatedSelected =
        if (state.selectedProject?.id == project.id) {
          state.selectedProject.copy { this.topK = topK }
        } else {
          state.selectedProject
        }
      state.copy(selectedProject = updatedSelected)
    }
    viewModelScope.launch {
      withContext(ioDispatcher) {
        dataStore.updateData { data ->
          val modifiedProjects =
            data.projectsList.map { p ->
              if (p.id == project.id) {
                p.copy { this.topK = topK }
              } else {
                p
              }
            }
          data.copy {
            projects.clear()
            projects += modifiedProjects
          }
        }
      }
    }
  }

  fun selectProject(project: VideoMomentProject?) {
    // Clearing the failure here scopes it to one visit: opening a project again retries indexing,
    // but a recomposition or rotation within the same visit does not.
    _uiState.update { state ->
      val isProcessingSelected =
        project != null && project.id == processingProjectId && processJob?.isActive == true
      state.copy(
        selectedProject = project,
        projectProcessProgress = if (isProcessingSelected) state.projectProcessProgress else null,
        projectProcessFailed = false,
        projectProcessFailedDueToVideo = false,
      )
    }
  }

  /**
   * Leaves the selected project and returns to the project list.
   *
   * A project whose video could not be analyzed because of the video itself is deleted on the way
   * out: retrying cannot recover it, and leaving it behind only clutters the list with an entry
   * that can never be searched. It is kept if it has saved clips, since those are user work that
   * would otherwise be lost with it, and it is kept for model or engine failures, which a retry can
   * still fix.
   */
  fun closeSelectedProject() {
    val state = _uiState.value
    val project = state.selectedProject
    val shouldDelete =
      project != null &&
        state.projectProcessFailed &&
        state.projectProcessFailedDueToVideo &&
        !project.isProcessed &&
        project.savedClipsCount == 0
    selectProject(null)
    if (project != null && shouldDelete) {
      Log.d(TAG, "Deleting project ${project.id}: its video could not be analyzed")
      deleteProject(project)
    }
  }

  /**
   * Returns the existing project that was imported from [uri], or null if the video has not been
   * imported yet.
   *
   * Projects created before the source URI was recorded never match, so they can still be imported
   * again.
   */
  fun findProjectImportedFrom(uri: Uri): VideoMomentProject? {
    val source = uri.toString()
    return _uiState.value.projects.find { it.sourceUri.isNotEmpty() && it.sourceUri == source }
  }

  /**
   * Imports a user-selected video from the provided [uri].
   *
   * This method performs several actions on a background IO dispatcher:
   * 1. Creates a new project directory under the external files directory.
   * 2. Copies the resolved video content from the content provider to the local project file
   *    (`video.mp4`).
   * 3. Uses [MediaMetadataRetriever] to extract the video duration and the first frame that is not
   *    black to save as a thumbnail (`thumbnail.jpg`).
   * 4. Updates the [DataStore] with the newly created [VideoMomentProject], persisting the relative
   *    paths and metadata.
   *
   * The processing parameters are taken from the caller rather than defaulted here, because the
   * user picks them in the configuration dialog shown before the import begins. They are stored on
   * the project, which is what the indexing pass at the end reads them back from.
   */
  fun importVideo(
    uri: Uri,
    framesPerWindow: Int = DEFAULT_FRAMES_PER_WINDOW,
    windowDurationSec: Int = DEFAULT_WINDOW_DURATION_SEC,
    overlapDurationSec: Int = DEFAULT_OVERLAP_DURATION_SEC,
    includeAudio: Boolean = DEFAULT_INCLUDE_AUDIO,
  ) {
    viewModelScope.launch {
      withContext(ioDispatcher) {
        val projectId = UUID.randomUUID().toString()
        val projectDir =
          File(context.getExternalFilesDir(null), "$VIDEO_MOMENT_FINDER_DIR/$projectId")
        var persisted = false
        try {
          projectDir.mkdirs()

          val videoFile = File(projectDir, VIDEO_FILE_NAME)
          val inputStream = openSafeInputStream(context, uri)
          inputStream?.use { input ->
            FileOutputStream(videoFile).use { output -> input.copyTo(output) }
          }

          // Camera apps and screen recorders sometimes hand out fragmented MP4s, which carry their
          // samples in moof fragments and, more often than not, no seek index at all. ExoPlayer
          // then reports the timeline as unseekable and rewrites every seek to position 0, while
          // MediaExtractor and MediaMetadataRetriever keep handing back the opening frame, which
          // breaks the scrubber, the thumbnail, the indexing pass and clip export alike. Rewriting
          // the copy as a progressive MP4 restores the sample table they all seek through.
          try {
            if (normalizeFragmentedMp4(videoFile, ioDispatcher)) {
              Log.d(TAG, "Rewrote the fragmented video of project $projectId as a seekable MP4")
            }
          } catch (e: CancellationException) {
            throw e
          } catch (e: Exception) {
            // The original copy is still playable, only seeking into it stays broken, so the import
            // continues rather than failing outright.
            Log.e(TAG, "Failed to rewrite the fragmented video of project $projectId", e)
            logErrorToFirebase(
              GalleryEvent.BUTTON_CLICKED,
              "videomomentfinder_add_video_normalize_error",
              e.message,
            )
          }

          // Extract duration and thumbnail
          val retriever = MediaMetadataRetriever()
          var durationMs = 0L
          try {
            retriever.setDataSource(videoFile.absolutePath)
            val dStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
            if (!dStr.isNullOrEmpty()) {
              durationMs = dStr.toLong()
            }

            // Extract frame to save as thumbnail. Videos that fade in from black, or that open on a
            // camera exposure ramp, would otherwise get a black cover, so skip forward to the first
            // frame that has something visible in it. OPTION_CLOSEST, not OPTION_CLOSEST_SYNC:
            // the chosen time is usually inside the opening group of pictures, and snapping to the
            // nearest keyframe would land back on the black frame the search just rejected.
            val thumbnailTimeUs = findFirstNonBlackFrameTimeUs(retriever, durationMs)
            val frame =
              retriever.getFrameAtTime(thumbnailTimeUs, MediaMetadataRetriever.OPTION_CLOSEST)
                ?: retriever.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
            try {
              if (frame != null) {
                val thumbnailFile = File(projectDir, THUMBNAIL_FILE_NAME)
                FileOutputStream(thumbnailFile).use { out ->
                  frame.compress(Bitmap.CompressFormat.JPEG, 90, out)
                }
              }
            } finally {
              frame?.recycle()
            }
          } catch (e: Exception) {
            Log.e(TAG, "Error extracting video metadata or thumbnail for project $projectId", e)
            logErrorToFirebase(
              GalleryEvent.BUTTON_CLICKED,
              "videomomentfinder_add_video_metadata_error",
              e.message,
            )
          } finally {
            retriever.release()
          }

          // Add project to data-store
          var displayName = "Project ${projectId.take(4)}"
          try {
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
              if (cursor.moveToFirst()) {
                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (nameIndex != -1) {
                  val name = cursor.getString(nameIndex)
                  if (!name.isNullOrEmpty()) {
                    displayName = name
                  }
                }
              }
            }
          } catch (e: Exception) {
            Log.e(TAG, "Failed to query display name from uri", e)
          }

          // If from PhotoPicker, the display name might be a synthetic ID (e.g. "384.mp4").
          // Query MediaStore with the media ID to resolve the original file name.
          try {
            val mediaId = uri.lastPathSegment?.toLongOrNull()
            if (mediaId != null) {
              val mediaStoreUri =
                ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, mediaId)
              context.contentResolver
                .query(
                  mediaStoreUri,
                  arrayOf(MediaStore.Video.Media.DISPLAY_NAME),
                  null,
                  null,
                  null,
                )
                ?.use { cursor ->
                  if (cursor.moveToFirst()) {
                    val nameIndex = cursor.getColumnIndex(MediaStore.Video.Media.DISPLAY_NAME)
                    if (nameIndex != -1) {
                      val realName = cursor.getString(nameIndex)
                      if (!realName.isNullOrEmpty()) {
                        displayName = realName
                      }
                    }
                  }
                }
            }
          } catch (e: Exception) {
            Log.w(TAG, "Failed to query MediaStore for display name", e)
          }

          val newProject = videoMomentProject {
            id = projectId
            relativeVideoPath = "$VIDEO_MOMENT_FINDER_DIR/$projectId/$VIDEO_FILE_NAME"
            relativeThumbnailPath = "$VIDEO_MOMENT_FINDER_DIR/$projectId/$THUMBNAIL_FILE_NAME"
            this.durationMs = durationMs
            this.displayName = displayName
            this.framesPerWindow = framesPerWindow
            this.windowDurationSec = windowDurationSec
            this.overlapDurationSec = overlapDurationSec
            topK = DEFAULT_TOP_K
            this.includeAudio = includeAudio
            sourceUri = uri.toString()
          }

          dataStore.updateData { data ->
            val newProjectsList = listOf(newProject) + data.projectsList
            data.copy {
              projects.clear()
              projects += newProjectsList
            }
          }
          persisted = true

          // Directly open the project detail page by setting the selected project and initializing
          // progress to 0%
          _uiState.update { it.copy(selectedProject = newProject, projectProcessProgress = 0.0f) }

          // Allow navigation transition to animate smoothly before background processing starts.
          delay(500)

          // Process the new project
          processProject(newProject)
        } finally {
          if (!persisted) {
            withContext(NonCancellable) { projectDir.deleteRecursively() }
          }
        }
      }
    }
  }

  /**
   * Clears current search results and reprocesses the [project] with the specified parameters.
   *
   * Failures surface as [VideoMomentFinderUiState.projectProcessFailed] rather than an exception,
   * because callers cannot otherwise tell a failed pass from one that is still running.
   */
  fun reprocessProject(
    project: VideoMomentProject,
    framesPerWindow: Int = DEFAULT_FRAMES_PER_WINDOW,
    windowDurationSec: Int = DEFAULT_WINDOW_DURATION_SEC,
    overlapDurationSec: Int = DEFAULT_OVERLAP_DURATION_SEC,
    topK: Int = DEFAULT_TOP_K,
    includeAudio: Boolean = project.effectiveIncludeAudio(),
    searchQueryAfterProcess: String? = null,
  ) {
    clearSearchResults()
    processProject(
      project = project,
      framesPerWindow = framesPerWindow,
      windowDurationSec = windowDurationSec,
      overlapDurationSec = overlapDurationSec,
      topK = topK,
      includeAudio = includeAudio,
      searchQueryAfterProcess = searchQueryAfterProcess,
    )
  }

  /**
   * Deletes the specified [project] by removing its directory and files from disk, dropping its
   * indexed frame embeddings from the vector store, and removing it from [DataStore].
   */
  fun deleteProject(project: VideoMomentProject) {
    viewModelScope.launch {
      withContext(ioDispatcher) {
        // Delete the indexed frame embeddings for this project. A vector store failure must not
        // abort the rest of the deletion, otherwise the user's delete silently does nothing and
        // the video files are orphaned on disk.
        try {
          val service = semanticRetrievalService
          if (service == null) {
            Log.w(
              TAG,
              "Retrieval service unavailable while deleting project ${project.id}; its indexed" +
                " windows leak in ${VideoMomentFinderTask.DATABASE_NAME} until the model" +
                " directory holding that database is deleted, since this project is about to be" +
                " removed from DataStore and can never be reprocessed",
            )
          } else {
            val deletedRecordCount =
              service.deleteRecordsWithIdPrefix(projectRecordIdPrefix(project.id))
            Log.d(TAG, "Deleted $deletedRecordCount indexed window(s) for project ${project.id}")
          }
        } catch (e: CancellationException) {
          throw e
        } catch (e: Exception) {
          Log.e(TAG, "Failed to delete indexed windows for project ${project.id}", e)
          logErrorToFirebase(
            GalleryEvent.BUTTON_CLICKED,
            "videomomentfinder_delete_project_windows_error",
            e.message,
          )
        }

        // Delete the directory and its contents
        val projectDir =
          File(context.getExternalFilesDir(null), "$VIDEO_MOMENT_FINDER_DIR/${project.id}")
        if (projectDir.exists()) {
          projectDir.deleteRecursively()
        }

        // Remove from datastore
        dataStore.updateData { data ->
          val newProjectsList = data.projectsList.filter { it.id != project.id }
          data.copy {
            projects.clear()
            projects += newProjectsList
          }
        }
      }
    }
  }

  /**
   * Deletes a saved [clip] belonging to the specified [project].
   *
   * Removes the clip directory and thumbnail from disk, updates [DataStore] to remove the clip from
   * the project, and updates the UI state if the owning project is currently selected.
   */
  fun deleteClip(project: VideoMomentProject, clip: VideoClip) {
    viewModelScope.launch {
      withContext(ioDispatcher) {
        // Delete the clip directory and its contents
        val clipDir =
          File(
            context.getExternalFilesDir(null),
            "$VIDEO_MOMENT_FINDER_DIR/${project.id}/$CLIPS_DIR/${clip.id}",
          )
        if (clipDir.exists()) {
          clipDir.deleteRecursively()
        }

        // Remove from datastore
        dataStore.updateData { data ->
          val modifiedProjects =
            data.projectsList.map { p ->
              if (p.id == project.id) {
                val remainingClips = p.savedClipsList.filter { it.id != clip.id }
                p.copy {
                  savedClips.clear()
                  savedClips += remainingClips
                }
              } else {
                p
              }
            }
          data.copy {
            projects.clear()
            projects += modifiedProjects
          }
        }

        _uiState.update { state ->
          val currentSelected = state.selectedProject ?: return@update state
          if (currentSelected.id != project.id) return@update state
          val remainingClips = currentSelected.savedClipsList.filter { it.id != clip.id }
          state.copy(
            selectedProject =
              currentSelected.copy {
                savedClips.clear()
                savedClips += remainingClips
              }
          )
        }
      }
    }
  }

  /**
   * Deletes the given list of saved moment clips across their owning projects.
   *
   * For each clip, removes its directory and contents from disk, updates the [DataStore] to remove
   * the clip from its parent project, and updates the currently selected project in UI state if
   * affected.
   */
  fun deleteClips(clips: List<SavedMomentClipItem>) {
    viewModelScope.launch {
      withContext(ioDispatcher) {
        // Delete the clip directories and their contents
        val clipsByProjectId = clips.groupBy { it.projectId }
        for ((projectId, projectClips) in clipsByProjectId) {
          for (item in projectClips) {
            val clipDir =
              File(
                context.getExternalFilesDir(null),
                "$VIDEO_MOMENT_FINDER_DIR/$projectId/$CLIPS_DIR/${item.clip.id}",
              )
            if (clipDir.exists()) {
              clipDir.deleteRecursively()
            }
          }
        }

        // Remove from datastore
        val clipIdsByProject = clips.groupBy({ it.projectId }, { it.clip.id })
        dataStore.updateData { data ->
          val modifiedProjects =
            data.projectsList.map { p ->
              val clipIdsToDelete = clipIdsByProject[p.id]?.toSet()
              if (clipIdsToDelete != null) {
                val remainingClips = p.savedClipsList.filter { it.id !in clipIdsToDelete }
                p.copy {
                  savedClips.clear()
                  savedClips += remainingClips
                }
              } else {
                p
              }
            }
          data.copy {
            projects.clear()
            projects += modifiedProjects
          }
        }

        // Update selected project if currently selected
        _uiState.update { state ->
          val currentSelected = state.selectedProject ?: return@update state
          val clipIdsToDelete = clipIdsByProject[currentSelected.id]?.toSet() ?: return@update state
          val remainingClips = currentSelected.savedClipsList.filter { it.id !in clipIdsToDelete }
          state.copy(
            selectedProject =
              currentSelected.copy {
                savedClips.clear()
                savedClips += remainingClips
              }
          )
        }
      }
    }
  }

  /**
   * Searches for video moments matching the given text [query] within the specified [project].
   *
   * Delegates ranking to the semantic retrieval vector store, scoping candidates to this project
   * via a `parent_id` metadata filter, then extracts representative preview frames for the UI while
   * merging overlapping intervals for scrubber range indicators.
   */
  fun searchForMoment(project: VideoMomentProject, query: String) {
    viewModelScope.launch {
      // Log the search. Only the length is reported: the query itself is user content.
      firebaseAnalytics?.logEvent(
        GalleryEvent.GENERATE_ACTION.id,
        Bundle().apply {
          putString("capability_name", BuiltInTaskId.VIDEO_MOMENT_FINDER)
          putString("action", "search")
          putInt("query_length", query.trim().length)
        },
      )

      // Step 1: Update UI state to indicate that a search is in progress. Previous results are
      // cleared here so that no stale results or scrubber intervals survive a new search or an
      // early exit below.
      _uiState.update {
        it.copy(
          isSearching = true,
          hasPerformedSearch = true,
          searchResults = emptyList(),
          mergedIntervals = emptyList(),
          selectedResultId = null,
        )
      }
      try {
        withContext(ioDispatcher) {
          // Step 2: Ensure the retrieval service is available.
          val service = semanticRetrievalService
          if (service == null) {
            Log.w(TAG, "Semantic retrieval service is not available for search")
            logErrorToFirebase(
              GalleryEvent.GENERATE_ACTION,
              "videomomentfinder_search_service_unavailable",
              null,
            )
            return@withContext
          }

          // Step 3: Validate video duration and local video file existence.
          val durationMs = project.durationMs
          if (durationMs <= 0L) {
            logErrorToFirebase(
              GalleryEvent.GENERATE_ACTION,
              "videomomentfinder_search_invalid_duration",
              null,
            )
            return@withContext
          }

          val projectDir =
            File(context.getExternalFilesDir(null), "$VIDEO_MOMENT_FINDER_DIR/${project.id}")
          val videoFile = File(projectDir, VIDEO_FILE_NAME)
          if (!videoFile.exists()) {
            logErrorToFirebase(
              GalleryEvent.GENERATE_ACTION,
              "videomomentfinder_search_video_file_missing",
              null,
            )
            return@withContext
          }

          // Step 4: Query the vector store for the top K windows belonging to this project.
          val currentProject =
            _uiState.value.selectedProject?.takeIf { it.id == project.id } ?: project
          val topK = if (currentProject.topK > 0) currentProject.topK else DEFAULT_TOP_K
          val results =
            try {
              service.retrieveEntities(
                queryText = query,
                metadataFilter = mapOf(METADATA_KEY_PARENT_ID to currentProject.id),
                limit = topK,
              )
            } catch (e: CancellationException) {
              throw e
            } catch (e: Exception) {
              Log.d(TAG, "Failed semantic search query: '$query'")
              Log.e(TAG, "Failed to run semantic search (queryLength=${query.length})", e)
              logErrorToFirebase(
                GalleryEvent.GENERATE_ACTION,
                "videomomentfinder_search_retrieval_error",
                e.message,
              )
              return@withContext
            }

          // Step 5: Map retrieved records back to their time windows.
          val topFrames = results.mapNotNull { result ->
            val startTimeMs = result.metadata[METADATA_KEY_START_TIME_MS]?.toLongOrNull()
            val endTimeMs = result.metadata[METADATA_KEY_END_TIME_MS]?.toLongOrNull()
            if (startTimeMs == null || endTimeMs == null) {
              Log.w(TAG, "Skipping record ${result.id} with missing time window metadata")
              null
            } else {
              ScoredFrame(
                startTimeMs = startTimeMs,
                endTimeMs = endTimeMs,
                score = result.similarityScore ?: 0f,
              )
            }
          }

          Log.d(TAG, "Retrieved ${topFrames.size} windows for '$query' (topK requested: $topK)")
          for (frame in topFrames) {
            Log.d(
              TAG,
              "Selected moment window [${frame.startTimeMs / 1000f}s - ${frame.endTimeMs / 1000f}s] with score: ${frame.score}",
            )
          }

          if (topFrames.isEmpty()) {
            // An empty result can mean either "nothing matched" or "this project has no vectors at
            // all", which happens when the model directory holding the database was deleted while
            // the project stayed marked processed. Distinguish them so the cause is diagnosable.
            val indexedWindowCount =
              service.indexedIds.value.count {
                it.startsWith(projectRecordIdPrefix(currentProject.id))
              }
            if (indexedWindowCount == 0) {
              Log.w(
                TAG,
                "Project ${currentProject.id} is marked processed but has no indexed windows; it" +
                  " must be reprocessed before search can return results",
              )
            }
            _uiState.update { state ->
              state.copy(searchResults = emptyList(), mergedIntervals = emptyList())
            }
            return@withContext
          }

          // Step 6: Extract representative preview frames for each unmerged moment in top-K
          // matches.
          val searchResults = mutableListOf<MomentSearchResult>()
          val retriever = MediaMetadataRetriever()
          try {
            retriever.setDataSource(videoFile.absolutePath)
            for (frame in topFrames) {
              coroutineContext.ensureActive()
              val startMs = frame.startTimeMs
              val endMs = frame.endTimeMs
              val midMs = (startMs + endMs) / 2L
              val score = frame.score

              val bitmap =
                retriever.getScaledFrameAtTime(
                  startMs * 1000L,
                  MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
                  MAX_SEARCH_RESULT_FRAME_DIMENSION,
                  MAX_SEARCH_RESULT_FRAME_DIMENSION,
                )
                  ?: retriever.getFrameAtTime(
                    startMs * 1000L,
                    MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
                  )
              if (bitmap != null) {
                val clipId = UUID.randomUUID().toString()
                searchResults.add(
                  MomentSearchResult(
                    id = clipId,
                    startTimeMs = startMs,
                    endTimeMs = endMs,
                    timeMs = midMs,
                    frameBitmap = bitmap,
                    similarityScore = score,
                  )
                )
              }
            }
          } catch (t: Throwable) {
            for (result in searchResults) {
              result.frameBitmap.recycle()
            }
            searchResults.clear()
            if (t is Exception && t !is CancellationException) {
              Log.e(TAG, "Error extracting frames for search results", t)
              logErrorToFirebase(
                GalleryEvent.GENERATE_ACTION,
                "videomomentfinder_search_frame_extraction_error",
                t.message,
              )
            } else {
              throw t
            }
          } finally {
            retriever.release()
          }

          // Step 7: Merge adjacent or overlapping matches for scrubber range indicators.
          val mergedIntervals = computeMergedIntervals(searchResults)

          // Step 8: Publish search results (sorted by similarity score descending) and merged
          // intervals.
          _uiState.update { state ->
            state.copy(searchResults = searchResults, mergedIntervals = mergedIntervals)
          }
        }
      } finally {
        _uiState.update { it.copy(isSearching = false) }
      }
    }
  }

  /** Clears the current search results and resets search state in the UI. */
  fun clearSearchResults() {
    _uiState.update {
      it.copy(
        searchResults = emptyList(),
        mergedIntervals = emptyList(),
        selectedResultId = null,
        hasPerformedSearch = false,
      )
    }
  }

  /** Selects a specific search result by its [id]. */
  fun selectResult(id: String) {
    _uiState.update { state -> state.copy(selectedResultId = id) }
  }

  /**
   * Generates a representative thumbnail for the moment defined by [startTimeMs]..[endTimeMs],
   * saves it under `$VIDEO_MOMENT_FINDER_DIR/{projectId}/$CLIPS_DIR/{clipId}/$THUMBNAIL_FILE_NAME`,
   * and saves the [VideoClip] metadata to the project.
   *
   * If [clipId] is provided, it updates the existing clip's thumbnail and metadata in place without
   * creating a duplicate clip. If omitted or `null`, a new UUID is generated and a new clip is
   * appended to the project.
   */
  suspend fun saveClip(
    project: VideoMomentProject,
    startTimeMs: Long,
    endTimeMs: Long,
    searchQuery: String = "",
    clipId: String? = null,
  ): Boolean =
    withContext(ioDispatcher) {
      val targetClipId = clipId ?: UUID.randomUUID().toString()
      val isUpdating = clipId != null
      val relativeScreenshotPath =
        "$VIDEO_MOMENT_FINDER_DIR/${project.id}/$CLIPS_DIR/$targetClipId/$THUMBNAIL_FILE_NAME"

      val clipScreenshotFile = File(context.getExternalFilesDir(null), relativeScreenshotPath)
      clipScreenshotFile.parentFile?.mkdirs()

      // Extract frame at startTimeMs to save as clip thumbnail
      val sourceVideoFile = File(context.getExternalFilesDir(null), project.relativeVideoPath)
      val retriever = MediaMetadataRetriever()
      try {
        retriever.setDataSource(sourceVideoFile.absolutePath)
        val frame =
          retriever.getFrameAtTime(startTimeMs * 1000L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
        try {
          if (frame != null) {
            FileOutputStream(clipScreenshotFile).use { out ->
              frame.compress(Bitmap.CompressFormat.JPEG, 90, out)
            }
          }
        } finally {
          frame?.recycle()
        }
      } catch (e: Exception) {
        Log.e(TAG, "Failed to generate thumbnail for clip $targetClipId", e)
        logErrorToFirebase(
          GalleryEvent.BUTTON_CLICKED,
          "videomomentfinder_clip_thumbnail_error",
          e.message,
        )
      } finally {
        retriever.release()
      }

      // Build the VideoClip proto instance with time boundaries, screenshot path, and search query
      val videoClip = videoClip {
        id = targetClipId
        this.startTimeMs = startTimeMs
        this.endTimeMs = endTimeMs
        this.relativeScreenshotPath = relativeScreenshotPath
        this.searchQuery = searchQuery
      }

      // Persist the clip to DataStore: update existing clip at its index or append as a new clip
      dataStore.updateData { data ->
        val modifiedProjects =
          data.projectsList.map { p ->
            if (p.id == project.id) {
              p.copy {
                if (isUpdating) {
                  val clipIndex = p.savedClipsList.indexOfFirst { it.id == targetClipId }
                  if (clipIndex >= 0) {
                    savedClips[clipIndex] = videoClip
                  } else {
                    savedClips += videoClip
                  }
                } else {
                  savedClips += videoClip
                }
              }
            } else {
              p
            }
          }
        data.copy {
          projects.clear()
          projects += modifiedProjects
        }
      }

      true
    }

  /**
   * Ranks [scoredFrames] by descending cosine similarity and returns at most [topK] of them.
   *
   * [topK] is the value configured on the project, which falls back to [DEFAULT_TOP_K] when it is
   * not positive, e.g. for projects created before top-K became configurable.
   */
  internal fun selectTopScoredFrames(
    scoredFrames: List<ScoredFrame>,
    topK: Int,
  ): List<ScoredFrame> {
    val effectiveTopK = if (topK > 0) topK else DEFAULT_TOP_K
    return scoredFrames.sortedByDescending { it.score }.take(effectiveTopK)
  }

  /**
   * Merges adjacent or overlapping search result moments into continuous time intervals with a
   * tolerance of [overlapToleranceMs], mapping each interval to the IDs of its constituent results.
   */
  internal fun computeMergedIntervals(
    results: List<MomentSearchResult>,
    overlapToleranceMs: Long = 500L,
  ): List<MergedMomentInterval> {
    if (results.isEmpty()) return emptyList()
    val sortedResults = results.sortedBy { it.startTimeMs }
    val merged = mutableListOf<MergedMomentInterval>()

    for (result in sortedResults) {
      val last = merged.lastOrNull()
      if (last != null && result.startTimeMs <= last.endTimeMs + overlapToleranceMs) {
        merged[merged.lastIndex] =
          MergedMomentInterval(
            startTimeMs = last.startTimeMs,
            endTimeMs = maxOf(last.endTimeMs, result.endTimeMs),
            resultIds = last.resultIds + result.id,
          )
      } else {
        merged.add(
          MergedMomentInterval(
            startTimeMs = result.startTimeMs,
            endTimeMs = result.endTimeMs,
            resultIds = setOf(result.id),
          )
        )
      }
    }
    return merged
  }

  /**
   * Clears `is_processed` for every project whose indexed windows can no longer be trusted: either
   * the vector store holds none of them, or they were embedded against a model whose audio support
   * differs from the current one.
   *
   * The flag lives in [dataStore] while the vectors live in the model directory, so deleting the
   * model wipes the vectors without touching the flag. Reconciling against the store each time a
   * service is attached makes that divergence self-healing, whatever caused it.
   *
   * The empty case is unambiguous because a successfully processed project always has at least one
   * indexed window: [processProject] aborts without setting the flag when it indexes none. So
   * "processed but zero windows" is always stale, never legitimately empty.
   *
   * The audio case matters because windows embedded with interleaved audio and windows embedded
   * from frames alone are not comparable within one vector space, so a model swap that adds or
   * removes audio support silently degrades ranking until the project is rebuilt.
   */
  private suspend fun markUnindexedProjectsAsUnprocessed(service: SemanticRetrievalService) {
    // Fetched rather than read from the state flow: the service loads its IDs from disk lazily, and
    // treating a not-yet-loaded (empty) set as truth would unprocess every project below.
    val indexedIds = service.fetchRecordIdentifiers()
    withContext(ioDispatcher) {
      // Null means the capability could not be read, e.g. the model is not on the device. Left
      // unreconciled in that case rather than assumed absent, which would reprocess everything.
      val supportsAudio = service.supportsAudioEmbedding()
      _uiState.update { it.copy(supportsAudio = supportsAudio == true) }
      try {
        dataStore.updateData { data ->
          val reconciled =
            data.projectsList.map { project ->
              val hasIndexedWindows = indexedIds.any {
                it.startsWith(projectRecordIdPrefix(project.id))
              }
              val expectedAudioSupport =
                supportsAudio != null && project.effectiveIncludeAudio() && supportsAudio
              val audioSupportChanged =
                supportsAudio != null && project.indexedWithAudioSupport != expectedAudioSupport
              when {
                !project.isProcessed -> project
                !hasIndexedWindows -> {
                  Log.w(
                    TAG,
                    "Project ${project.id} is marked processed but has no indexed windows;" +
                      " marking it unprocessed so it is reindexed when next opened",
                  )
                  project.copy { isProcessed = false }
                }
                audioSupportChanged -> {
                  Log.i(
                    TAG,
                    "Project ${project.id} was indexed with audio support" +
                      " ${project.indexedWithAudioSupport} but now requires" +
                      " $expectedAudioSupport; marking it unprocessed so it is reindexed",
                  )
                  project.copy { isProcessed = false }
                }
                else -> project
              }
            }
          // Return the original instance when nothing changed to avoid a pointless disk write on
          // every screen open.
          if (reconciled == data.projectsList) {
            data
          } else {
            data.copy {
              projects.clear()
              projects += reconciled
            }
          }
        }
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        Log.e(TAG, "Failed to reconcile processed flags against the vector store", e)
        logErrorToFirebase(
          GalleryEvent.GENERATE_ACTION,
          "videomomentfinder_reconcile_error",
          e.message,
        )
      }
    }
  }

  private fun processProject(
    project: VideoMomentProject,
    framesPerWindow: Int =
      if (project.framesPerWindow > 0) project.framesPerWindow else DEFAULT_FRAMES_PER_WINDOW,
    windowDurationSec: Int =
      if (project.windowDurationSec > 0) project.windowDurationSec else DEFAULT_WINDOW_DURATION_SEC,
    overlapDurationSec: Int =
      if (project.overlapDurationSec >= 0 && project.framesPerWindow > 0) {
        project.overlapDurationSec
      } else {
        DEFAULT_OVERLAP_DURATION_SEC
      },
    topK: Int =
      if (project.topK > 0) {
        project.topK
      } else {
        DEFAULT_TOP_K
      },
    includeAudio: Boolean = project.effectiveIncludeAudio(),
    searchQueryAfterProcess: String? = null,
  ) {
    // Log the indexing pass. Logged before the coroutine so every requested pass is counted.
    firebaseAnalytics?.logEvent(
      GalleryEvent.GENERATE_ACTION.id,
      Bundle().apply {
        putString("capability_name", BuiltInTaskId.VIDEO_MOMENT_FINDER)
        putString("action", "process_project")
        putInt("frames_per_window", framesPerWindow)
        putInt("window_duration_sec", windowDurationSec)
        putInt("overlap_duration_sec", overlapDurationSec)
        putInt("top_k", topK)
        putBoolean("include_audio", includeAudio)
        putLong("video_duration_ms", project.durationMs)
        putBoolean("is_reprocess", project.isProcessed)
      },
    )

    // Enter the processing state synchronously so no caller and no recomposition can observe the
    // gap between asking for a pass and the coroutine starting it. During that gap the project
    // looks unprocessed with no progress, which is exactly what a failed pass looks like.
    val previousJob = processJob
    processingProjectId = project.id
    _uiState.update { state ->
      if (isCurrentProcessingProject(project.id, state)) {
        state.copy(
          projectProcessProgress = 0.0f,
          projectProcessFailed = false,
          projectProcessFailedDueToVideo = false,
        )
      } else {
        state
      }
    }

    processJob = viewModelScope.launch {
      previousJob?.cancelAndJoin()
      withContext(ioDispatcher) {
        // Step 1: Validate video duration and file existence. A zero duration means the import
        // could not read the video's metadata, which is what a corrupt or non-video file looks
        // like.
        val durationMs = project.durationMs
        if (durationMs <= 0L) {
          markProcessingFailed(
            project.id,
            "videomomentfinder_process_invalid_duration",
            causedByVideo = true,
          )
          return@withContext
        }

        val projectDir =
          File(context.getExternalFilesDir(null), "$VIDEO_MOMENT_FINDER_DIR/${project.id}")
        val videoFile = File(projectDir, VIDEO_FILE_NAME)
        if (!videoFile.exists()) {
          markProcessingFailed(
            project.id,
            "videomomentfinder_process_video_file_missing",
            causedByVideo = true,
          )
          return@withContext
        }

        val service = semanticRetrievalService
        if (service == null) {
          Log.e(TAG, "Semantic retrieval service is not available to process project ${project.id}")
          markProcessingFailed(project.id, "videomomentfinder_process_service_unavailable")
          return@withContext
        }

        // Drop any previously indexed windows so reprocessing cannot leave orphaned records
        // behind when the window parameters change. Progress was already set to 0%, so a failure
        // here must reset it or the UI stays stuck at 0% forever.
        try {
          val staleRecordCount =
            service.deleteRecordsWithIdPrefix(projectRecordIdPrefix(project.id))
          if (staleRecordCount > 0) {
            Log.d(
              TAG,
              "Cleared $staleRecordCount stale indexed window(s) for project ${project.id}",
            )
          }
        } catch (e: CancellationException) {
          throw e
        } catch (e: Exception) {
          Log.e(TAG, "Failed to clear stale indexed windows for project ${project.id}", e)
          markProcessingFailed(
            project.id,
            "videomomentfinder_process_clear_windows_error",
            e.message,
          )
          return@withContext
        }

        // Step 2: Calculate sliding time windows and sample frame timestamps.
        val windowDurationMs = windowDurationSec * 1000L
        val overlapMs = overlapDurationSec * 1000L
        val stepMs = (windowDurationMs - overlapMs).coerceAtLeast(1000L)

        val windows = mutableListOf<Triple<Long, Long, List<Long>>>()
        var windowStartMs = 0L
        while (windowStartMs < durationMs) {
          val windowEndMs = minOf(windowStartMs + windowDurationMs, durationMs)
          val curWindowDuration = windowEndMs - windowStartMs
          val sampleTimestamps = mutableListOf<Long>()
          for (i in 0 until framesPerWindow) {
            val t =
              if (framesPerWindow == 1) {
                windowStartMs + curWindowDuration / 2
              } else {
                windowStartMs + (i * curWindowDuration) / (framesPerWindow - 1)
              }
            sampleTimestamps.add(t.coerceIn(0L, durationMs))
          }
          windows.add(Triple(windowStartMs, windowEndMs, sampleTimestamps))
          windowStartMs += stepMs
        }

        val totalWindows = windows.size
        var indexedWindowCount = 0
        var embeddingFailed = false
        // Carries the specific cause to the single failure report at the end of the pass. Each
        // failure below breaks out of the loop, so at most one cause is ever recorded.
        var embeddingErrorType: String? = null
        var embeddingErrorMessage: String? = null
        // Whether the recorded failure came from the video (unreadable container, undecodable
        // frames) rather than from the embedding model or engine.
        var embeddingFailureCausedByVideo = false

        // Step 3: Decide whether this pass interleaves audio with the frames it indexes.
        //
        // Gated on both the user's [includeAudio] setting and the modality the model itself
        // declares, since audio-stripped builds of an otherwise audio-capable model ship to some
        // hardware tiers. [effectiveAudioSupport] is what gets persisted, because it describes
        // the embedding space these windows live in; whether audio is actually interleaved
        // additionally depends on this particular video having a decodable audio track, which
        // must not make the project look stale the next time it is opened.
        val audioSupported = service.supportsAudioEmbedding() == true
        _uiState.update { it.copy(supportsAudio = audioSupported) }
        val effectiveAudioSupport = includeAudio && audioSupported
        val estimatedWindowTokens =
          estimateWindowTokenCount(
            frameCount = framesPerWindow,
            visionTokensPerFrame = VideoMomentFinderTask.VISION_TOKEN_BUDGET,
            audioSliceCount = framesPerWindow,
            audioSliceDurationSec = windowDurationSec.toFloat() / framesPerWindow.toFloat(),
          )
        val fitsTokenBudget =
          estimatedWindowTokens <= VideoMomentFinderTask.MAX_INPUT_SEQUENCE_LENGTH
        if (effectiveAudioSupport && !fitsTokenBudget) {
          Log.w(
            TAG,
            "A $framesPerWindow frame window over ${windowDurationSec}s needs about" +
              " $estimatedWindowTokens tokens with audio, beyond the" +
              " ${VideoMomentFinderTask.MAX_INPUT_SEQUENCE_LENGTH} token limit; indexing frames only",
          )
        }
        var audioExtractor: VideoAudioExtractor? =
          if (effectiveAudioSupport && fitsTokenBudget) {
            VideoAudioExtractor.createOrNull(videoFile.absolutePath)
          } else {
            null
          }
        var interleaveAudio = audioExtractor != null

        // Step 4: Extract each window's frames and matching audio, then index their embedding.
        val retriever = MediaMetadataRetriever()
        try {
          // Opened separately from the loop below so that a container the platform cannot parse is
          // attributed to the video, not lumped in with model and engine errors.
          val videoOpened =
            try {
              retriever.setDataSource(videoFile.absolutePath)
              true
            } catch (e: CancellationException) {
              throw e
            } catch (e: Exception) {
              Log.e(TAG, "Failed to open the video of project ${project.id}", e)
              embeddingFailed = true
              embeddingErrorType = "videomomentfinder_process_open_video_error"
              embeddingErrorMessage = e.message
              embeddingFailureCausedByVideo = true
              false
            }
          val windowsToIndex = if (videoOpened) windows else emptyList()
          for ((index, window) in windowsToIndex.withIndex()) {
            val (startMs, endMs, sampleTimes) = window
            val windowFrames = mutableListOf<ByteArray>()
            // Collected alongside the frames rather than reused from sampleTimes: a frame that
            // fails to decode is skipped, and every frame has to stay paired with its own
            // timestamp and audio slice.
            val windowFrameTimesMs = mutableListOf<Long>()
            for (timeMs in sampleTimes) {
              coroutineContext.ensureActive()
              val frame =
                retriever.getFrameAtTime(timeMs * 1000L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
              if (frame != null) {
                try {
                  val stream = ByteArrayOutputStream()
                  frame.compress(Bitmap.CompressFormat.JPEG, 80, stream)
                  windowFrames.add(stream.toByteArray())
                  windowFrameTimesMs.add(timeMs)
                } finally {
                  frame.recycle()
                }
              }
            }

            if (windowFrames.isEmpty()) {
              Log.e(TAG, "Failed to extract frames for window [${startMs}ms, ${endMs}ms]")
              embeddingFailed = true
              embeddingErrorType = "videomomentfinder_process_frame_extraction_error"
              embeddingFailureCausedByVideo = true
              break
            }

            val extractor = audioExtractor
            val audioSlices =
              if (interleaveAudio && extractor != null) {
                readWindowAudioSlices(extractor, startMs, endMs, windowFrames.size)
              } else {
                emptyList()
              }
            val recordId = windowRecordId(project.id, startMs, endMs)
            val metadata =
              mapOf(
                METADATA_KEY_PARENT_ID to project.id,
                METADATA_KEY_START_TIME_MS to startMs.toString(),
                METADATA_KEY_END_TIME_MS to endMs.toString(),
              )

            var failure =
              indexWindow(
                service = service,
                recordId = recordId,
                parts = buildWindowEmbedParts(windowFrames, windowFrameTimesMs, audioSlices),
                metadata = metadata,
              )
            if (failure != null && interleaveAudio && indexedWindowCount == 0) {
              // The model declared audio support but the engine refused the audio part, which is
              // what an audio encoder that cannot run on the configured backend looks like from
              // here. Retry this window without audio and keep the rest of the pass frame-only,
              // so that every window of this project still lands in a single embedding space.
              Log.w(
                TAG,
                "Audio-interleaved indexing failed on the first window; falling back to indexing" +
                  " frames only for project ${project.id}",
                failure,
              )
              interleaveAudio = false
              audioExtractor?.close()
              audioExtractor = null
              failure =
                indexWindow(
                  service = service,
                  recordId = recordId,
                  parts = buildWindowEmbedParts(windowFrames, windowFrameTimesMs),
                  metadata = metadata,
                )
            }
            if (failure != null) {
              Log.e(TAG, "Failed to index window [${startMs}ms, ${endMs}ms]", failure)
              embeddingFailed = true
              embeddingErrorType = "videomomentfinder_process_index_window_error"
              embeddingErrorMessage = failure.message
              break
            }
            indexedWindowCount++

            Log.d(
              TAG,
              "Indexed window [${startMs}ms, ${endMs}ms] with ${windowFrames.size} frames" +
                if (interleaveAudio) " and interleaved audio" else "",
            )

            val progress = (index + 1).toFloat() / totalWindows.toFloat()
            _uiState.update { state ->
              if (isCurrentProcessingProject(project.id, state)) {
                state.copy(projectProcessProgress = progress)
              } else {
                state
              }
            }
            // Yield to allow UI thread and GPU RenderThread to smoothly draw frames
            delay(15L)
          }
        } catch (e: CancellationException) {
          throw e
        } catch (e: Exception) {
          Log.e(TAG, "Error generating frame embeddings for project ${project.id}", e)
          embeddingFailed = true
          embeddingErrorType = "videomomentfinder_process_embedding_error"
          embeddingErrorMessage = e.message
        } finally {
          retriever.release()
          audioExtractor?.close()
        }

        if (embeddingFailed || indexedWindowCount == 0) {
          Log.e(
            TAG,
            "Frame embedding generation failed for project ${project.id}; aborting without marking processed",
          )
          // Record the failure before the discard below: if the discard throws, the UI must not
          // be left stuck showing a processing indicator forever.
          // A null error type means nothing threw, so the pass simply produced no windows to
          // index.
          markProcessingFailed(
            project.id,
            embeddingErrorType ?: "videomomentfinder_process_no_windows_indexed",
            embeddingErrorMessage,
            causedByVideo = embeddingFailureCausedByVideo,
          )
          try {
            val discardedRecordCount =
              service.deleteRecordsWithIdPrefix(projectRecordIdPrefix(project.id))
            Log.d(
              TAG,
              "Discarded $discardedRecordCount partially indexed window(s) for project" +
                " ${project.id}",
            )
          } catch (e: CancellationException) {
            throw e
          } catch (e: Exception) {
            Log.e(TAG, "Failed to discard partially indexed windows for ${project.id}", e)
          }
          return@withContext
        }

        // Step 5: Persist configuration parameters to DataStore. The embeddings themselves now
        // live in the vector store.
        var updatedProject: VideoMomentProject = project
        dataStore.updateData { data ->
          val modifiedProjects =
            data.projectsList.map { p ->
              if (p.id == project.id) {
                val newProject = p.copy {
                  isProcessed = true
                  this.framesPerWindow = framesPerWindow
                  this.windowDurationSec = windowDurationSec
                  this.overlapDurationSec = overlapDurationSec
                  this.topK = topK
                  this.includeAudio = includeAudio
                  indexedWithAudioSupport = effectiveAudioSupport
                }
                updatedProject = newProject
                newProject
              } else {
                p
              }
            }
          data.copy {
            projects.clear()
            projects += modifiedProjects
          }
        }

        // Logged separately from the pass request because whether audio was actually interleaved
        // is only known once the model and the video's audio track have both been inspected.
        firebaseAnalytics?.logEvent(
          GalleryEvent.GENERATE_ACTION.id,
          Bundle().apply {
            putString("capability_name", BuiltInTaskId.VIDEO_MOMENT_FINDER)
            putString("action", "process_project_complete")
            putBoolean("include_audio", includeAudio)
            putBoolean("audio_supported", audioSupported)
            putBoolean("audio_interleaved", interleaveAudio)
            putInt("indexed_window_count", indexedWindowCount)
          },
        )

        // Step 6: Update UI state with the processed project and clear progress.
        _uiState.update { state ->
          val isCurrent = isCurrentProcessingProject(project.id, state)
          state.copy(
            selectedProject =
              if (state.selectedProject?.id == project.id) {
                updatedProject
              } else {
                state.selectedProject
              },
            projectProcessProgress = if (isCurrent) null else state.projectProcessProgress,
          )
        }

        // Step 7: Automatically execute search if a pending query was specified.
        if (!searchQueryAfterProcess.isNullOrBlank()) {
          searchForMoment(updatedProject, searchQueryAfterProcess)
        }
      }
    }
  }

  /**
   * Reads the audio of the window `[startTimeMs], [endTimeMs])` as one decode, split into one slice
   * per frame.
   *
   * Read a whole window at a time because [VideoAudioExtractor] decodes in a single forward pass
   * and releases the samples behind each request. One call per frame would ask for the leading
   * frames of every overlapping window after the previous window had already released them, and
   * their audio would be dropped without failing the pass. Window starts always advance by a
   * positive step, so window-sized requests stay in the non-decreasing order the extractor needs.
   *
   * Entries are positional and may be null where the video has no decodable audio for that moment,
   * which [buildWindowEmbedParts] handles by simply omitting the audio for that frame.
   */
  private suspend fun readWindowAudioSlices(
    audioExtractor: VideoAudioExtractor,
    startTimeMs: Long,
    endTimeMs: Long,
    sliceCount: Int,
  ): List<ShortArray?> {
    if (sliceCount <= 0) return emptyList()
    val windowPcm = audioExtractor.readWindow(startTimeMs, endTimeMs) ?: return emptyList()
    return splitWindowPcm(windowPcm, startTimeMs, endTimeMs, sliceCount)
  }

  /**
   * Indexes one window as a single record, returning the failure instead of throwing so the caller
   * can decide whether to retry it without audio or abort the pass.
   */
  private suspend fun indexWindow(
    service: SemanticRetrievalService,
    recordId: String,
    parts: List<EmbedPart>,
    metadata: Map<String, String>,
  ): IllegalStateException? =
    try {
      service.addMultimodalRecord(id = recordId, parts = parts, metadata = metadata)
      null
    } catch (e: CancellationException) {
      // CancellationException extends IllegalStateException, so it must be rethrown before the
      // handler below or cancelling a reprocess is misreported as an indexing failure.
      throw e
    } catch (e: IllegalStateException) {
      e
    }

  private fun isCurrentProcessingProject(
    projectId: String,
    state: VideoMomentFinderUiState,
  ): Boolean =
    processingProjectId == projectId &&
      (state.selectedProject == null || state.selectedProject.id == projectId)

  /**
   * Records that a processing pass ended without marking its project processed.
   *
   * Clearing the progress and raising the failure flag have to happen together: a project that is
   * unprocessed with neither set is indistinguishable from one whose pass has not started, and the
   * UI reads that as "still processing".
   *
   * Every failed pass leaves through here, so this is also where the failure is reported to
   * Firebase. [errorType] names the cause and [errorMessage] carries the exception text when there
   * was one. [causedByVideo] marks failures that come from the video itself rather than from the
   * model or engine; see [VideoMomentFinderUiState.projectProcessFailedDueToVideo].
   */
  private fun markProcessingFailed(
    projectId: String,
    errorType: String,
    errorMessage: String? = null,
    causedByVideo: Boolean = false,
  ) {
    logErrorToFirebase(GalleryEvent.GENERATE_ACTION, errorType, errorMessage)
    _uiState.update { state ->
      if (isCurrentProcessingProject(projectId, state)) {
        state.copy(
          projectProcessProgress = null,
          projectProcessFailed = true,
          projectProcessFailedDueToVideo = causedByVideo,
        )
      } else {
        state
      }
    }
  }
}
