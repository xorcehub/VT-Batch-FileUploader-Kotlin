package com.vtbatch.desktop.mvi

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.vtbatch.model.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

// AppStore = holds the current state and dispatches intents.
// StateFlow = Kotlin's reactive state holder (like a Python observable).
// UI components subscribe to the StateFlow and auto-update when state changes.
//
// Flow: User action → dispatch(intent) → reducer(newState) → side effects → more intents

class AppStore(
    apiKey: String? = null,
    initialConfig: AppConfig = AppConfig.default,
    val container: AppContainer = AppContainer(apiKey = apiKey, initialConfig = initialConfig)
) {
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val _state = MutableStateFlow(AppState())
    val state: StateFlow<AppState> = _state.asStateFlow()

    private val sideEffects = SideEffects(container, ::dispatch, scope)

    /** The current state snapshot */
    val currentState: AppState get() = _state.value

    /** Dispatch an intent → reduce to new state → trigger side effects */
    fun dispatch(intent: AppIntent) {
        var reducedState: AppState? = null
        _state.update { current ->
            val new = AppReducer.reduce(current, intent)
            reducedState = new
            new
        }
        triggerSideEffects(intent, reducedState!!)
    }

    /** Map intents to their side effects */
    private fun triggerSideEffects(intent: AppIntent, state: AppState) {
        when (intent) {
            is AppIntent.DropFiles -> {
                sideEffects.scanFiles(intent.paths)
            }

            is AppIntent.StartProcessing -> {
                if (state.files.any { it.status == FileStatus.PENDING && it.md5Hash != null }) {
                    sideEffects.processFiles(state.files)
                }
            }

            is AppIntent.UploadNewFiles -> {
                if (state.files.any { it.status == FileStatus.HASHED_NOT_FOUND }) {
                    sideEffects.uploadFiles(state.files)
                }
            }

            is AppIntent.OpenHashedFiles -> {
                sideEffects.openHashedFiles(state.files)
            }

            is AppIntent.ExportFiles -> {
                sideEffects.exportFiles(state.files)
            }

            is AppIntent.ClearList -> {
                sideEffects.cancelAll()
            }

            is AppIntent.SubmitCredentials -> {
                sideEffects.validateCredentials(intent.apiKey, intent.persist)
            }

            is AppIntent.SaveSettings -> {
                sideEffects.saveSettings(intent.settings)
            }

            is AppIntent.SubmitCommand -> {
                sideEffects.executeCommand(intent.text, state.files)
            }

            is AppIntent.TogglePause -> {
                container.pauseController.toggle()
            }

            else -> {
                // No side effect needed — pure state change
                // (FindFiles, NavigateMatches, error/result intents, etc.)
            }
        }
    }

    /** Cancel all running coroutines. Call on app shutdown. */
    fun shutdown() {
        scope.cancel()
        container.shutdown()
    }
}

/**
 * Composable helper — subscribe to the store's state in a Compose UI.
 * Usage:
 *   val state by store.collectState()
 *   // now `state.files`, `state.isPaused`, etc. auto-update the UI
 */
@Composable
fun AppStore.collectState(): androidx.compose.runtime.State<AppState> {
    DisposableEffect(this) {
        onDispose { /* store lifetime > composable lifetime, no cleanup needed */ }
    }
    return this.state.collectAsState()
}
