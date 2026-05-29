package com.vtbatch.desktop

import androidx.compose.runtime.remember
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.vtbatch.desktop.mvi.AppStore
import com.vtbatch.desktop.ui.App
import com.vtbatch.model.AppConfig

// Entry point — the `application` block creates a Compose desktop app.
// `Window` is the main application window (like Tkinter's Tk() root).
fun main() = application {
    val store = remember { AppStore() }

    Window(
        onCloseRequest = {
            store.shutdown()
            exitApplication()
        },
        title = "VirusTotal Batch Uploader",
        state = rememberWindowState(width = 900.dp, height = 750.dp)
    ) {
        App(store)
    }
}
