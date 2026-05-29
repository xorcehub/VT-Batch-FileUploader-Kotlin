package com.vtbatch.desktop

import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.vtbatch.desktop.ui.App

// Entry point — the `application` block creates a Compose desktop app.
// `Window` is the main application window (like Tkinter's Tk() root).
fun main() = application {
    Window(
        onCloseRequest = ::exitApplication,
        title = "VirusTotal Batch Uploader",
        state = rememberWindowState(width = 900.dp, height = 750.dp)
    ) {
        App()
    }
}
