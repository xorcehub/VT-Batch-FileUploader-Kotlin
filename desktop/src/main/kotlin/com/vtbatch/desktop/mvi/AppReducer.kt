package com.vtbatch.desktop.mvi

// AppReducer = pure function: (oldState, intent) -> newState
// No side effects here — no API calls, no file I/O, nothing impure.
// This makes state transitions trivially testable.
// Phase 3 implements the full reducer.

object AppReducer {
    fun reduce(state: AppState, intent: AppIntent): AppState {
        // TODO: Phase 3 — implement all intent handling
        return state
    }
}
