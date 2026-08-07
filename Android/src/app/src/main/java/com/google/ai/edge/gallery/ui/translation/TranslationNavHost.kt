package com.google.ai.edge.gallery.ui.translation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.google.ai.edge.gallery.ui.modelmanager.ModelManagerViewModel

private const val ROUTE_TRANSLATION = "translation/main"
private const val ROUTE_VOICE_MODELS = "translation/voice-models"

@Composable
internal fun TranslationNavHost(
    modelManagerViewModel: ModelManagerViewModel,
    navigateUp: () -> Unit,
    viewModel: TranslationViewModel,
) {
    val navController = rememberNavController()
    val context = LocalContext.current

    NavHost(
        navController = navController,
        startDestination = ROUTE_TRANSLATION,
    ) {
        composable(ROUTE_TRANSLATION) {
            TranslationScreen(
                modelManagerViewModel = modelManagerViewModel,
                navigateUp = navigateUp,
                navigateToVoiceModels = {
                    navController.navigate(ROUTE_VOICE_MODELS)
                },
                viewModel = viewModel,
            )
        }

        composable(ROUTE_VOICE_MODELS) {
            val selectedModel by viewModel.ttsModel.collectAsStateWithLifecycle()
            val installState by viewModel.ttsInstallUiState.collectAsStateWithLifecycle()

            TranslationTtsModelManager(
                selectedModel = selectedModel,
                installUiState = installState,
                onDownloadRequested = {
                    viewModel.downloadTtsModel(context, it)
                },
                onModelSelected = viewModel::setTtsModel,
                navigateUp = navController::navigateUp,
            )
        }
    }
}