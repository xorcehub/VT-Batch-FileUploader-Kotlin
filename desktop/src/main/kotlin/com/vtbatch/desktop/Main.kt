package com.vtbatch.desktop

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.vtbatch.desktop.mvi.AppStore
import com.vtbatch.desktop.mvi.AppIntent
import com.vtbatch.desktop.ui.App
import com.vtbatch.model.CrashLogger
import com.vtbatch.model.LocalTelemetry
import com.vtbatch.model.VT_DEFAULT_USER
import java.awt.Canvas
import java.awt.Component
import java.awt.Container
import java.awt.datatransfer.DataFlavor
import java.awt.dnd.DnDConstants
import java.awt.dnd.DropTarget
import java.awt.dnd.DropTargetAdapter
import java.awt.dnd.DropTargetDropEvent
import java.awt.dnd.DropTargetDragEvent
import java.awt.dnd.DropTargetEvent
import java.io.File

fun main() = application {
    // ── Crash Logger ────────────────────────────────────────────────
    Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
        CrashLogger.logCrash(throwable)
        System.err.println("Uncaught exception in thread $thread: $throwable")
    }

    val previousCrash = CrashLogger.checkPreviousCrash()
    if (previousCrash != null) {
        System.err.println("WARNING: Previous crash detected. See ~/.vtbatch/crash.log")
        CrashLogger.clearCrashLog()
    }

    // ── Telemetry ───────────────────────────────────────────────────
    val telemetry = LocalTelemetry()
    kotlinx.coroutines.runBlocking { telemetry.recordSession() }

    // ── Credentials ──────────────────────────────────────────────────
    val envApiKey = System.getenv("VT_API_KEY")
    val envUser = System.getenv("VT_USER")

    // ── Main UI ─────────────────────────────────────────────────────
    val store = remember { AppStore(apiKey = envApiKey, user = envUser) }

    // Auto-prompt for credentials if no env vars set
    LaunchedEffect(Unit) {
        if (envApiKey.isNullOrBlank()) {
            // Check saved credentials from ~/.vtbatch/credentials
            val savedKey = store.container.credentialStore.load()
            if (!savedKey.isNullOrBlank()) {
                store.dispatch(AppIntent.SubmitCredentials(savedKey, envUser ?: VT_DEFAULT_USER, persist = false))
            } else {
                store.dispatch(AppIntent.ShowCredentialDialog)
            }
        } else {
            // Env vars found - validate them automatically, don't write to file
            store.dispatch(AppIntent.SubmitCredentials(envApiKey, envUser ?: VT_DEFAULT_USER, persist = false))
        }
    }

    Window(
        onCloseRequest = {
            store.shutdown()
            exitApplication()
        },
        title = "VirusTotal Batch Uploader",
        state = rememberWindowState(width = 900.dp, height = 750.dp)
    ) {
        // Compose Desktop uses Skiko which renders on a heavyweight AWT Canvas.
        // That Canvas sits on top of Swing's lightweight layer and intercepts
        // all DnD events. We need to set the DropTarget on the Canvas itself.
        val awtWindow = window
        remember(awtWindow) {
            // Find the Skiko Canvas in the window's component hierarchy
            val canvas = findCanvas(awtWindow)
            val target = canvas ?: awtWindow // fallback to window if no canvas found

            target.dropTarget = DropTarget(target, DnDConstants.ACTION_COPY, object : DropTargetAdapter() {
                override fun dragEnter(e: DropTargetDragEvent) {
                    if (e.isDataFlavorSupported(DataFlavor.javaFileListFlavor)) {
                        e.acceptDrag(DnDConstants.ACTION_COPY)
                        store.dispatch(AppIntent.DragEnter)
                    } else {
                        e.rejectDrag()
                    }
                }

                override fun dragOver(e: DropTargetDragEvent) {
                    if (e.isDataFlavorSupported(DataFlavor.javaFileListFlavor)) {
                        e.acceptDrag(DnDConstants.ACTION_COPY)
                    }
                }

                override fun dragExit(e: DropTargetEvent) {
                    store.dispatch(AppIntent.DragExit)
                }

                override fun drop(e: DropTargetDropEvent) {
                    store.dispatch(AppIntent.DragExit)
                    try {
                        e.acceptDrop(DnDConstants.ACTION_COPY)
                        val transferable = e.transferable
                        if (transferable.isDataFlavorSupported(DataFlavor.javaFileListFlavor)) {
                            @Suppress("UNCHECKED_CAST")
                            val files = transferable.getTransferData(DataFlavor.javaFileListFlavor) as? List<File>
                            if (files != null && files.isNotEmpty()) {
                                val paths = files.map { it.absolutePath }
                                store.dispatch(AppIntent.DropFiles(paths))
                            }
                        }
                        e.dropComplete(true)
                    } catch (ex: Exception) {
                        e.dropComplete(false)
                    }
                }
            })
            awtWindow
        }

        App(store)
    }
}

/** Recursively find the Skiko rendering Canvas in the component hierarchy */
private fun findCanvas(container: Container): Canvas? {
    for (component in container.components) {
        if (component is Canvas) return component
        if (component is Container) {
            val found = findCanvas(component)
            if (found != null) return found
        }
    }
    return null
}
