package site.leos.apps.lespas.helper

import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.provider.OpenableColumns
import androidx.preference.PreferenceManager
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import site.leos.apps.lespas.LespasDatabase
import site.leos.apps.lespas.R
import site.leos.apps.lespas.sync.Action
import java.io.File

class SnapseedResultWorker(private val context: Context, workerParams: WorkerParameters) : CoroutineWorker(context, workerParams) {
    override suspend fun doWork(): Result {
        var result = Result.failure()

        @Suppress("DEPRECATION")
        val pathColumn = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) MediaStore.Files.FileColumns.RELATIVE_PATH else MediaStore.Files.FileColumns.DATA
        val appRootFolder = Tools.getLocalRoot(context)

        val cr = context.contentResolver
        var imagePath = ""
        var imageName = ""
        var imageSize = 0L
        val photoDao = LespasDatabase.getDatabase(context).photoDao()
        val albumDao = LespasDatabase.getDatabase(context).albumDao()
        val actionDao = LespasDatabase.getDatabase(context).actionDao()
        val uri = Uri.parse(inputData.keyValueMap[KEY_IMAGE_URI] as String)
        val originalPhoto = photoDao.getPhotoById(inputData.keyValueMap[KEY_SHARED_PHOTO] as String)
        val album = albumDao.getThisAlbum(inputData.keyValueMap[KEY_ALBUM] as String)

        withContext(Dispatchers.IO) {
            cr.query(uri, null, null, null, null)?.use { cursor ->
                cursor.moveToFirst()
                imagePath = cursor.getString(cursor.getColumnIndexOrThrow(pathColumn))
                imageName = cursor.getString(cursor.getColumnIndexOrThrow(OpenableColumns.DISPLAY_NAME))
                imageSize = cursor.getLong(cursor.getColumnIndexOrThrow(OpenableColumns.SIZE))
            }

            // Wait until Snapseed result file is synced to disk. Seems like system media store start scanning it even before the file is written completely and reporting 0 file size
            // TODO any other proper way to do this?
            while (imageSize == 0L) {
                delay(200)

                cr.query(uri, null, null, null, null)?.use { cursor ->
                    cursor.moveToFirst()
                    imageSize = cursor.getLong(cursor.getColumnIndexOrThrow(OpenableColumns.SIZE))
                }
            }

            if (imagePath.contains("Snapseed/")) {
                // If this is under Snapseed's folder
                if (PreferenceManager.getDefaultSharedPreferences(context).getBoolean(context.getString(R.string.snapseed_replace_pref_key), false)) {
                    /* Replace the original */

                    // Make a copy of this file after imageName, e.g. the photo name, so that when new eTag synced back from server, file will not need to be downloaded
                    // If we share this photo to Snapseed again, the share function will use this new name, then snapseed will append another "-01" to the result filename
                    try {
                        @Suppress("BlockingMethodInNonBlockingContext")
                        cr.openInputStream(uri)?.use { input ->
                            // Name new photo filename after Snapseed's output name
                            File(appRootFolder, imageName).outputStream().use { output ->
                                input.copyTo(output)
                            }
                        }
                    } catch (e: Exception) {
                        e.printStackTrace()
                        // Quit when exception happens during file copy
                        return@withContext
                    }

                    // Update local database
                    val newPhoto = Tools.getPhotoParams("$appRootFolder/$imageName", JPEG, imageName).copy(
                        //id = originalPhoto.id, albumId = album.id, name = imageName, eTag = originalPhoto.eTag, shareId = originalPhoto.shareId)
                        // Mark sync status by setting eTag to empty
                        id = originalPhoto.id, albumId = album.id, name = imageName, eTag = "", shareId = originalPhoto.shareId
                    )
                    photoDao.update(newPhoto)
                    // Invalid image cache to show new image and change CurrentPhotoModel's filename
                    //setProgress(workDataOf( KEY_INVALID_OLD_PHOTO_CACHE to true))

                    // Remove file name after photo id, ImageLoaderViewModel will load file named after photo name instead
                    try { File(appRootFolder, originalPhoto.id).delete() } catch (e: Exception) { e.printStackTrace() }

                    // When the photo being replaced has not being uploaded yet, remove file named after old photo name if any
                    try { File(appRootFolder, originalPhoto.name).delete() } catch (e: Exception) { e.printStackTrace() }


                    // Update server
                    with(mutableListOf<Action>()) {
                        // Rename file to new filename on server, so that we can overwrite it in the next action and keep it's fileId intact
                        add(Action(null, Action.ACTION_RENAME_FILE, album.id, album.name, originalPhoto.name, newPhoto.name, System.currentTimeMillis(), 1))
                        // Upload new photo to server. Photo mimeType passed in folderId property, and since this is actually an file update on server, pass the current photo id in property fileId
                        //add(Action(null, Action.ACTION_UPDATE_FILE, newPhoto.mimeType, album.name, "", newPhoto.name, System.currentTimeMillis(), 1))
                        add(Action(null, Action.ACTION_ADD_FILES_ON_SERVER, newPhoto.mimeType, album.name, newPhoto.id, newPhoto.name, System.currentTimeMillis(), 1))

                        actionDao.insert(this)
                    }
                } else {
                    /* Copy Snapseed output */

                    // Append content uri _id as suffix to make a unique filename, this will be use as both fileId and filename
                    val fileName = "${imageName.substringBeforeLast('.')}_${uri.lastPathSegment!!}.${imageName.substringAfterLast('.')}"

                    // Copy file to our private storage area
                    try {
                        @Suppress("BlockingMethodInNonBlockingContext")
                        cr.openInputStream(uri)?.use { input ->
                            File(appRootFolder, fileName).outputStream().use { output ->
                                input.copyTo(output)
                            }
                        }
                    } catch (e: Exception) {
                        e.printStackTrace()
                        return@withContext
                    }

                    // Create new photo in local database
                    photoDao.insert(Tools.getPhotoParams("$appRootFolder/$fileName", JPEG, fileName).copy(id = fileName, albumId = album.id, name = fileName))

                    with(mutableListOf<Action>()) {
                        // Upload changes to server, mimetype passed in folderId property, fileId is the same as fileName, reflecting what it's in local Room table
                        add(Action(null, Action.ACTION_ADD_FILES_ON_SERVER, JPEG, album.name, fileName, fileName, System.currentTimeMillis(), 1))

                        actionDao.insert(this)
                    }
                }

                // Remove cache copy
                try { File(context.cacheDir, originalPhoto.name).delete() } catch (e: Exception) { e.printStackTrace() }

                // Remove snapseed output if running on Android Q or lower
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) try { cr.delete(uri, null, null) } catch (e: Exception) { e.printStackTrace() }

                result = Result.success()
            }
        }

        return result
    }

    companion object {
        private const val JPEG = "image/jpeg"

        const val KEY_IMAGE_URI = "IMAGE_URI"
        const val KEY_SHARED_PHOTO = "SHARE_PHOTO"
        const val KEY_ALBUM = "ALBUM"
        //const val KEY_INVALID_OLD_PHOTO_CACHE = "INVALID_OLD_PHOTO_CACHE"
        //const val KEY_PUBLISHED = "IS_ALBUM_PUBLISHED"
    }
}
