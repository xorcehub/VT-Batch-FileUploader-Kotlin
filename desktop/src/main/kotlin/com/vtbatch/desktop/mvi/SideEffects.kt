package com.vtbatch.desktop.mvi

// SideEffects = all the async operations (API calls, file I/O, hashing, etc.)
// These are suspend functions (Kotlin coroutines — like Python async def).
// They run on background threads and emit result intents back to the store.
// Phase 3 implements these.

object SideEffects {
    // TODO: Phase 3 — implement all side effect handlers
    // scanFiles, processFile, uploadFile, checkHash, fetchQuota, etc.
}
