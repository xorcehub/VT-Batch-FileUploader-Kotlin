package com.vtbatch.desktop.mvi

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

// AppStore = holds the current state and dispatches intents.
// StateFlow = Kotlin's reactive state holder (like a Python observable).
// UI components subscribe to the StateFlow and auto-update when state changes.
// Phase 3 adds side effect handling.

class AppStore {
    private val _state = MutableStateFlow(AppState())
    val state: StateFlow<AppState> = _state.asStateFlow()

    fun dispatch(intent: AppIntent) {
        val currentState = _state.value
        val newState = AppReducer.reduce(currentState, intent)
        _state.value = newState
        // TODO: Phase 3 — trigger side effects based on intent
    }
}
