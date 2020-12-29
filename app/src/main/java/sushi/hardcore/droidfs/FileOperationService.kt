package sushi.hardcore.droidfs

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.*
import androidx.documentfile.provider.DocumentFile
import sushi.hardcore.droidfs.explorers.ExplorerElement
import sushi.hardcore.droidfs.file_operations.OperationFile
import sushi.hardcore.droidfs.util.GocryptfsVolume
import sushi.hardcore.droidfs.util.PathUtils
import sushi.hardcore.droidfs.util.Wiper
import java.io.File
import java.io.FileNotFoundException


class FileOperationService : Service() {
    companion object {
        const val NOTIFICATION_ID = 1
        const val NOTIFICATION_CHANNEL_ID = "FileOperations"
    }

    private val binder = LocalBinder()
    private lateinit var gocryptfsVolume: GocryptfsVolume
    private lateinit var notificationManager: NotificationManager

    inner class LocalBinder : Binder() {
        fun getService(): FileOperationService = this@FileOperationService
        fun setGocryptfsVolume(g: GocryptfsVolume) {
            gocryptfsVolume = g
        }
    }

    override fun onBind(p0: Intent?): IBinder {
        return binder
    }

    private fun showNotification(message: String, total: Int): Notification.Builder {
        if (!::notificationManager.isInitialized){
            notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        }
        val notificationBuilder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(NOTIFICATION_CHANNEL_ID, getString(R.string.file_operations), NotificationManager.IMPORTANCE_LOW)
            notificationManager.createNotificationChannel(channel)
            Notification.Builder(this, NOTIFICATION_CHANNEL_ID)
        } else {
            Notification.Builder(this)
        }
        notificationBuilder.setOngoing(true)
                .setContentTitle(getString(R.string.file_op_notification_title))
                .setContentText(message)
                .setSmallIcon(R.mipmap.icon_launcher)
                .setProgress(total, 0, false)
        notificationManager.notify(NOTIFICATION_ID, notificationBuilder.build())
        return notificationBuilder
    }

    private fun updateNotificationProgress(notificationBuilder: Notification.Builder, progress: Int, total: Int){
        notificationBuilder.setProgress(total, progress, false)
        notificationManager.notify(NOTIFICATION_ID, notificationBuilder.build())
    }

    private fun cancelNotification(){
        notificationManager.cancel(NOTIFICATION_ID)
    }

    private fun copyFile(srcPath: String, dstPath: String, remoteGocryptfsVolume: GocryptfsVolume = gocryptfsVolume): Boolean {
        var success = true
        val srcHandleId = remoteGocryptfsVolume.openReadMode(srcPath)
        if (srcHandleId != -1){
            val dstHandleId = gocryptfsVolume.openWriteMode(dstPath)
            if (dstHandleId != -1){
                var offset: Long = 0
                val ioBuffer = ByteArray(GocryptfsVolume.DefaultBS)
                var length: Int
                while (remoteGocryptfsVolume.readFile(srcHandleId, offset, ioBuffer).also { length = it } > 0) {
                    val written = gocryptfsVolume.writeFile(dstHandleId, offset, ioBuffer, length).toLong()
                    if (written == length.toLong()) {
                        offset += written
                    } else {
                        success = false
                        break
                    }
                }
                gocryptfsVolume.closeFile(dstHandleId)
            } else {
                success = false
            }
            remoteGocryptfsVolume.closeFile(srcHandleId)
        } else {
            success = false
        }
        return success
    }

    fun copyElements(items: ArrayList<OperationFile>, remoteGocryptfsVolume: GocryptfsVolume = gocryptfsVolume, callback: (String?) -> Unit){
        Thread {
            val notificationBuilder = showNotification(getString(R.string.file_op_copy_msg), items.size)
            var failedItem: String? = null
            for (i in 0 until items.size){
                if (items[i].explorerElement.isDirectory){
                    if (!gocryptfsVolume.pathExists(items[i].dstPath!!)) {
                        if (!gocryptfsVolume.mkdir(items[i].dstPath!!)) {
                            failedItem = items[i].explorerElement.fullPath
                        }
                    }
                } else {
                    if (!copyFile(items[i].explorerElement.fullPath, items[i].dstPath!!, remoteGocryptfsVolume)){
                        failedItem = items[i].explorerElement.fullPath
                    }
                }
                if (failedItem == null){
                    updateNotificationProgress(notificationBuilder, i, items.size)
                } else {
                    break
                }
            }
            cancelNotification()
            callback(failedItem)
        }.start()
    }

    fun moveElements(items: ArrayList<OperationFile>, callback: (String?) -> Unit){
        Thread {
            val notificationBuilder = showNotification(getString(R.string.file_op_move_msg), items.size)
            val mergedFolders = ArrayList<String>()
            var failedItem: String? = null
            for (i in 0 until items.size){
                if (items[i].explorerElement.isDirectory && gocryptfsVolume.pathExists(items[i].dstPath!!)){ //folder will be merged
                    mergedFolders.add(items[i].explorerElement.fullPath)
                } else {
                    if (!gocryptfsVolume.rename(items[i].explorerElement.fullPath, items[i].dstPath!!)){
                        failedItem = items[i].explorerElement.fullPath
                        break
                    } else {
                        updateNotificationProgress(notificationBuilder, i, items.size)
                    }
                }
            }
            if (failedItem == null){
                for (i in 0 until mergedFolders.size) {
                    if (!gocryptfsVolume.rmdir(mergedFolders[i])){
                        failedItem = mergedFolders[i]
                        break
                    } else {
                        updateNotificationProgress(notificationBuilder, items.size-(mergedFolders.size-i), items.size)
                    }
                }
            }
            cancelNotification()
            callback(failedItem)
        }.start()
    }

    fun importFilesFromUris(items: ArrayList<OperationFile>, uris: List<Uri>, callback: (String?) -> Unit){
        Thread {
            val notificationBuilder = showNotification(getString(R.string.file_op_import_msg), items.size)
            var failedIndex = -1
            for (i in 0 until items.size) {
                try {
                    if (!gocryptfsVolume.importFile(this, uris[i], items[i].dstPath!!)){
                        failedIndex = i
                    }
                } catch (e: FileNotFoundException){
                    failedIndex = i
                }
                if (failedIndex == -1) {
                    updateNotificationProgress(notificationBuilder, i, items.size)
                } else {
                    cancelNotification()
                    callback(uris[failedIndex].toString())
                    break
                }
            }
            if (failedIndex == -1){
                cancelNotification()
                callback(null)
            }
        }.start()
    }

    fun wipeUris(uris: List<Uri>, callback: (String?) -> Unit){
        Thread {
            val notificationBuilder = showNotification(getString(R.string.file_op_wiping_msg), uris.size)
            var errorMsg: String? = null
            for (i in uris.indices) {
                errorMsg = Wiper.wipe(this, uris[i])
                if (errorMsg == null) {
                    updateNotificationProgress(notificationBuilder, i, uris.size)
                } else {
                    break
                }
            }
            cancelNotification()
            callback(errorMsg)
        }.start()
    }

    private fun exportFileInto(srcPath: String, treeDocumentFile: DocumentFile): Boolean {
        val outputStream = treeDocumentFile.createFile("*/*", File(srcPath).name)?.uri?.let {
            contentResolver.openOutputStream(it)
        }
        return if (outputStream == null) {
            false
        } else {
            gocryptfsVolume.exportFile(srcPath, outputStream)
        }
    }

    private fun recursiveExportDirectory(plain_directory_path: String, treeDocumentFile: DocumentFile): String? {
        treeDocumentFile.createDirectory(File(plain_directory_path).name)?.let { childTree ->
            val explorerElements = gocryptfsVolume.listDir(plain_directory_path)
            for (e in explorerElements) {
                val fullPath = PathUtils.pathJoin(plain_directory_path, e.name)
                if (e.isDirectory) {
                    val failedItem = recursiveExportDirectory(fullPath, childTree)
                    failedItem?.let { return it }
                } else {
                    if (!exportFileInto(fullPath, childTree)){
                        return fullPath
                    }
                }
            }
            return null
        }
        return treeDocumentFile.name
    }

    fun exportFiles(uri: Uri, items: List<ExplorerElement>, callback: (String?) -> Unit){
        Thread {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            DocumentFile.fromTreeUri(this, uri)?.let { treeDocumentFile ->
                val notificationBuilder = showNotification(getString(R.string.file_op_export_msg), items.size)
                var failedItem: String? = null
                for (i in items.indices) {
                    failedItem = if (items[i].isDirectory) {
                        recursiveExportDirectory(items[i].fullPath, treeDocumentFile)
                    } else {
                        if (exportFileInto(items[i].fullPath, treeDocumentFile)) null else items[i].fullPath
                    }
                    if (failedItem == null) {
                        updateNotificationProgress(notificationBuilder, i, items.size)
                    } else {
                        break
                    }
                }
                cancelNotification()
                callback(failedItem)
            }
        }.start()
    }
}