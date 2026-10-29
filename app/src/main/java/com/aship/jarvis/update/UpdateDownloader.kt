package com.aship.jarvis.update

import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import androidx.core.content.FileProvider
import java.io.File

/**
 * In-app update: APK download (system DownloadManager) + installer.
 *
 * The APK lands in the app's external files dir as
 * `jarvis-update.apk`, so no storage permission is needed and the
 * fixed CI signing key lets the new build install over this one —
 * the downloaded Hindi model and settings survive the update.
 *
 * The install intent goes through FileProvider: plain file:// URIs
 * are banned since Android 7.
 */
object UpdateDownloader {

    const val UPDATE_FILE_NAME = "jarvis-update.apk"

    /** Where the update APK lives on disk. */
    fun updateFile(context: Context): File =
        File(
            context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS),
            UPDATE_FILE_NAME
        )

    /**
     * Starts the download and returns the DownloadManager id,
     * or -1 when it could not be started at all.
     */
    fun startDownload(context: Context, downloadUrl: String): Long {
        val downloadManager =
            context.getSystemService(Context.DOWNLOAD_SERVICE) as? DownloadManager
                ?: return -1L
        // Remove a stale/partial APK first, otherwise DownloadManager
        // renames the new one and the installer would open the old file.
        try {
            updateFile(context).delete()
        } catch (e: Exception) {
            // Missing file is fine.
        }
        val request = DownloadManager.Request(Uri.parse(downloadUrl)).apply {
            setTitle("ASHIP JARVIS AI — naya version")
            setDescription("Update APK download ho raha hai")
            setNotificationVisibility(
                DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED
            )
            setDestinationInExternalFilesDir(
                context,
                Environment.DIRECTORY_DOWNLOADS,
                UPDATE_FILE_NAME
            )
            setMimeType("application/vnd.android.package-archive")
        }
        return try {
            downloadManager.enqueue(request)
        } catch (e: Exception) {
            -1L
        }
    }

    /** True when [downloadId] finished with STATUS_SUCCESSFUL. */
    fun isDownloadSuccessful(context: Context, downloadId: Long): Boolean {
        if (downloadId < 0L) return false
        val downloadManager =
            context.getSystemService(Context.DOWNLOAD_SERVICE) as? DownloadManager
                ?: return false
        val cursor = downloadManager.query(
            DownloadManager.Query().setFilterById(downloadId)
        ) ?: return false
        return cursor.use {
            if (!it.moveToFirst()) {
                false
            } else {
                val statusIndex = it.getColumnIndex(DownloadManager.COLUMN_STATUS)
                statusIndex >= 0 &&
                    it.getInt(statusIndex) == DownloadManager.STATUS_SUCCESSFUL
            }
        }
    }

    /** Intent that hands the downloaded APK to the system installer. */
    fun installIntent(context: Context): Intent {
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            updateFile(context)
        )
        return Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        }
    }
}
