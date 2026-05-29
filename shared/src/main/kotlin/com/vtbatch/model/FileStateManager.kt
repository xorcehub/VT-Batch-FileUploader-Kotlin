package com.vtbatch.model

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import io.github.oshai.kotlinlogging.KotlinLogging

private val logger = KotlinLogging.logger {}

/**
 * Thread-safe file state manager using Kotlin coroutines Mutex.
 * In Python this used threading.RLock — Mutex is the coroutine equivalent.
 * Instead of blocking a thread, Mutex suspends the coroutine (lighter weight).
 */
class FileStateManager {
    private val mutex = Mutex()
    private val fileStatuses = mutableMapOf<String, FileEntry>()

    suspend fun addFile(entry: FileEntry) {
        mutex.withLock {
            fileStatuses[entry.path] = entry
        }
    }

    suspend fun updateFileStatus(filePath: String, status: FileStatus) {
        mutex.withLock {
            fileStatuses[filePath]?.let { existing ->
                fileStatuses[filePath] = existing.copy(status = status)
            }
        }
    }

    suspend fun updateFile(filePath: String, transform: (FileEntry) -> FileEntry) {
        mutex.withLock {
            fileStatuses[filePath]?.let { existing ->
                fileStatuses[filePath] = transform(existing)
            }
        }
    }

    suspend fun getFile(filePath: String): FileEntry? = mutex.withLock {
        fileStatuses[filePath]
    }

    suspend fun getAllFiles(): Map<String, FileEntry> = mutex.withLock {
        fileStatuses.toMap() // Return a copy for safety
    }

    suspend fun allFilesHaveLastAnalysisDate(): Boolean = mutex.withLock {
        if (fileStatuses.isEmpty()) return@withLock false
        fileStatuses.values.all { it.lastAnalysisDate != null }
    }

    suspend fun clear() {
        mutex.withLock { fileStatuses.clear() }
    }

    suspend fun removeFile(filePath: String): Boolean = mutex.withLock {
        fileStatuses.remove(filePath) != null
    }

    suspend fun getFilesByStatus(status: FileStatus): List<FileEntry> = mutex.withLock {
        fileStatuses.values.filter { it.status == status }
    }
}
