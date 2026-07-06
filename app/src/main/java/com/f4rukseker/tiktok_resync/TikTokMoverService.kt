package com.f4rukseker.tiktok_resync

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.media.MediaScannerConnection
import android.os.Build
import android.os.Environment
import android.os.FileObserver
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import java.io.File
import java.io.IOException
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/**
 * Foreground service that:
 * 1. On start, moves all existing files in DCIM/Camera that match the TikTok
 *    filename pattern (32 arbitrary characters + a video or image extension)
 *    into Download/tiktok.
 * 2. Then keeps watching DCIM/Camera with a FileObserver and moves any new
 *    matching file as soon as it finishes writing.
 *
 * Requires MANAGE_EXTERNAL_STORAGE (All files access) to be granted by the
 * user beforehand, since both DCIM and Download are outside the app sandbox.
 */
class TikTokMoverService : Service() {

    private var observer: FileObserver? = null

    // Used to delay moving newly detected files, so the source app (TikTok)
    // has time to fully release the file handle before it gets moved away.
    private val scheduler: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor()

    // Matches exactly 32 arbitrary characters followed by a TikTok media
    // extension (video or image). Equivalent to the wildcard mask:
    // ????????????????????????????????.mp4 / .jpg / .jpeg / .png
    private val tiktokFileRegex = Regex("^.{32}\\.(mp4|jpg|jpeg|png)$", RegexOption.IGNORE_CASE)

    private val sourceDir: File by lazy {
        File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM), "Camera")
    }

    private val targetDir: File by lazy {
        File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "tiktok")
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification("Watching for TikTok videos"))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ensureTargetDirExists()
        moveExistingMatches()
        startWatching()
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        observer?.stopWatching()
        observer = null
        scheduler.shutdownNow()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        // The user swiped the app away from recents. Restart the service so
        // watching continues, since some OEM battery managers stop it otherwise.
        Log.i(TAG, "Task removed, restarting service")
        val restartIntent = Intent(applicationContext, TikTokMoverService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            applicationContext.startForegroundService(restartIntent)
        } else {
            applicationContext.startService(restartIntent)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun ensureTargetDirExists() {
        if (!targetDir.exists()) {
            val created = targetDir.mkdirs()
            Log.i(TAG, "Target directory created: $created path=${targetDir.absolutePath}")
        }
    }

    private fun moveExistingMatches() {
        val files = sourceDir.listFiles()
        if (files == null) {
            Log.w(TAG, "Could not list files in ${sourceDir.absolutePath}, check permissions")
            return
        }
        var movedCount = 0
        for (file in files) {
            if (file.isFile && tiktokFileRegex.matches(file.name)) {
                if (moveFile(file)) movedCount++
            }
        }
        Log.i(TAG, "Initial scan complete, moved $movedCount file(s)")
    }

    private fun startWatching() {
        if (observer != null) return

        val mask = FileObserver.CLOSE_WRITE or FileObserver.MOVED_TO

        observer = object : FileObserver(sourceDir.absolutePath, mask) {
            override fun onEvent(event: Int, path: String?) {
                if (path == null) return
                if (!tiktokFileRegex.matches(path)) return

                Log.i(TAG, "New file detected: $path, scheduling move in $MOVE_DELAY_SECONDS seconds")
                scheduler.schedule({
                    val file = File(sourceDir, path)
                    if (file.exists() && file.isFile) {
                        moveFile(file)
                    } else {
                        Log.w(TAG, "File no longer exists when the scheduled move ran: $path")
                    }
                }, MOVE_DELAY_SECONDS, TimeUnit.SECONDS)
            }
        }
        observer?.startWatching()
        Log.i(TAG, "Started watching ${sourceDir.absolutePath}")
    }

    private fun moveFile(file: File): Boolean {
        val destination = File(targetDir, file.name)
        val sourcePath = file.absolutePath

        // Duplicate handling: the same TikTok video can be downloaded twice,
        // which reuses the same 32 character filename. In that case the file
        // is already present in the target folder, so just delete the source
        // instead of moving or overwriting it.
        if (destination.exists()) {
            val deleted = file.delete()
            Log.i(TAG, "Duplicate detected, target already exists: ${file.name}, sourceDeleted=$deleted")
            refreshMediaStore(sourcePath)
            return deleted
        }

        // Retry a few times in case the file is still briefly locked by the
        // downloading app or the media scanner right after the write finishes.
        var lastError: Exception? = null
        repeat(MAX_MOVE_ATTEMPTS) { attempt ->
            try {
                val renamed = file.renameTo(destination)
                if (renamed) {
                    Log.i(TAG, "Moved file: ${file.name}")
                    refreshMediaStore(sourcePath, destination.absolutePath)
                    return true
                }

                // renameTo can fail across filesystems, fall back to copy and delete.
                // overwrite=true as a safety net so a leftover destination file never
                // blocks the move (the duplicate check above already handles the
                // normal case).
                file.copyTo(destination, overwrite = true)
                val deleted = file.delete()
                Log.i(TAG, "Copied and deleted file: ${file.name}, sourceDeleted=$deleted")
                refreshMediaStore(sourcePath, destination.absolutePath)
                return true
            } catch (e: IOException) {
                lastError = e
                Log.w(TAG, "Move attempt ${attempt + 1}/$MAX_MOVE_ATTEMPTS failed for ${file.name}: ${e.message}")
                Thread.sleep(MOVE_RETRY_DELAY_MS)
            }
        }

        Log.e(TAG, "Failed to move file after $MAX_MOVE_ATTEMPTS attempts: ${file.name}", lastError)
        return false
    }

    /**
     * Refreshes the MediaStore index for the given paths so gallery, file
     * manager, and other apps immediately see the change instead of showing
     * a stale entry at the old location or missing the file at the new one.
     * Scanning a path that no longer exists makes MediaScanner drop the
     * stale entry; scanning a path that exists adds or refreshes it.
     */
    private fun refreshMediaStore(vararg paths: String) {
        MediaScannerConnection.scanFile(this, paths, null) { scannedPath, uri ->
            Log.i(TAG, "MediaStore scan completed for: $scannedPath, uri=$uri")
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "TikTok Mover",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(text: String): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("TikTok Mover running")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val TAG = "TikTokMoverService"
        private const val CHANNEL_ID = "tiktok_mover_channel"
        private const val NOTIFICATION_ID = 1001
        private const val MAX_MOVE_ATTEMPTS = 3
        private const val MOVE_RETRY_DELAY_MS = 300L
        private const val MOVE_DELAY_SECONDS = 10L
    }
}