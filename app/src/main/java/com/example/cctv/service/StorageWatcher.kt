package com.example.cctv.service

import android.content.Context
import android.os.Environment
import android.util.Log
import java.io.File

/**
 * StorageWatcher
 *
 * Monitors video storage directories (Internal Storage & SD Card).
 * Calculates free storage metrics and handles automatic retention cleanup
 * (deleting MP4 recordings older than 5 days if disk space is low).
 */
object StorageWatcher {

    private const val TAG = "StorageWatcher"
    const val VIDEO_SUBDIRECTORY = "LoveCCTV"
    const val FIVE_DAYS_MS = 5 * 24 * 60 * 60 * 1000L
    const val LOW_DISK_THRESHOLD_BYTES = 500 * 1024 * 1024L // 500 MB

    data class StorageOption(
        val name: String,
        val path: File,
        val totalSpaceBytes: Long,
        val freeSpaceBytes: Long,
        val isSdCard: Boolean
    )

    /**
     * Discovers all available storage locations (Internal storage + external SD cards).
     */
    fun getAvailableStorageOptions(context: Context): List<StorageOption> {
        val options = mutableListOf<StorageOption>()

        // Internal Storage Movies/LoveCCTV
        val primaryMovies = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES)
        val primaryCctvDir = File(primaryMovies, VIDEO_SUBDIRECTORY)
        if (!primaryCctvDir.exists()) primaryCctvDir.mkdirs()

        options.add(
            StorageOption(
                name = "Phone Internal Storage",
                path = primaryCctvDir,
                totalSpaceBytes = primaryMovies.totalSpace,
                freeSpaceBytes = primaryMovies.usableSpace,
                isSdCard = false
            )
        )

        // External SD Cards
        val externalDirs = context.getExternalFilesDirs(Environment.DIRECTORY_MOVIES)
        for ((index, dir) in externalDirs.withIndex()) {
            if (dir != null && index > 0) {
                val sdCctvDir = File(dir, VIDEO_SUBDIRECTORY)
                if (!sdCctvDir.exists()) sdCctvDir.mkdirs()

                options.add(
                    StorageOption(
                        name = "Removable SD Card #${index}",
                        path = sdCctvDir,
                        totalSpaceBytes = dir.totalSpace,
                        freeSpaceBytes = dir.usableSpace,
                        isSdCard = true
                    )
                )
            }
        }

        return options
    }

    /**
     * Determines the optimal directory to save video recordings based on user preference
     * and smart auto-switching if the primary volume is low on space.
     */
    fun getTargetStorageDir(context: Context, preferredPath: String? = null): File {
        val options = getAvailableStorageOptions(context)

        if (!preferredPath.isNull_or_blank()) {
            val preferredFile = File(preferredPath)
            if (preferredFile.exists() && preferredFile.usableSpace > LOW_DISK_THRESHOLD_BYTES) {
                return preferredFile
            }
        }

        // Auto-switch logic: Pick SD card if available & has space, else default internal
        val sdOption = options.firstOrNull { it.isSdCard && it.freeSpaceBytes > LOW_DISK_THRESHOLD_BYTES }
        if (sdOption != null) {
            return sdOption.path
        }

        val primaryOption = options.firstOrNull { it.freeSpaceBytes > LOW_DISK_THRESHOLD_BYTES }
        return primaryOption?.path ?: options.first().path
    }

    private fun String?.isNull_or_blank(): Boolean = this == null || this.trim().isEmpty()

    /**
     * Runs 5-day automatic retention cleanup across all recording directories.
     */
    fun performRetentionCleanup(context: Context): Int {
        var deletedCount = 0
        val options = getAvailableStorageOptions(context)
        val cutoffTimestamp = System.currentTimeMillis() - FIVE_DAYS_MS

        for (option in options) {
            val dir = option.path
            if (dir.exists() && dir.isDirectory) {
                val videoFiles = dir.listFiles { file -> file.isFile && file.extension.equals("mp4", ignoreCase = true) }
                videoFiles?.forEach { file ->
                    if (file.lastModified() < cutoffTimestamp || option.freeSpaceBytes < LOW_DISK_THRESHOLD_BYTES) {
                        val sizeMb = file.length() / (1024 * 1024)
                        if (file.delete()) {
                            deletedCount++
                            Log.i(TAG, "Deleted old/low-space video: ${file.name} (${sizeMb} MB)")
                        }
                    }
                }
            }
        }
        return deletedCount
    }
}
