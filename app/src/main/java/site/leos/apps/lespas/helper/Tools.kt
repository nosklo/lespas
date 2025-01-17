package site.leos.apps.lespas.helper

import android.annotation.SuppressLint
import android.content.ContentResolver
import android.content.ContentUris
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.*
import android.graphics.drawable.AnimatedImageDrawable
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.DisplayMetrics
import android.util.Log
import android.util.TypedValue
import android.view.*
import android.widget.LinearLayout
import android.widget.ProgressBar
import androidx.core.content.ContextCompat
import androidx.exifinterface.media.ExifInterface
import androidx.preference.PreferenceManager
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.textview.MaterialTextView
import site.leos.apps.lespas.R
import site.leos.apps.lespas.album.Album
import site.leos.apps.lespas.cameraroll.CameraRollFragment
import site.leos.apps.lespas.photo.Photo
import site.leos.apps.lespas.settings.SettingsFragment
import java.io.File
import java.net.URLDecoder
import java.nio.file.Files
import java.nio.file.Paths
import java.text.CharacterIterator
import java.text.DecimalFormat
import java.text.SimpleDateFormat
import java.text.StringCharacterIterator
import java.time.Instant
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.util.*
import java.util.regex.Pattern
import kotlin.math.min
import kotlin.math.roundToInt

object Tools {
    const val DATE_FORMAT_PATTERN = "yyyy:MM:dd HH:mm:ss"
    val FORMATS_WITH_EXIF = arrayOf("jpeg", "png", "webp", "heif", "heic")
    val SUPPORTED_PICTURE_FORMATS = arrayOf("jpeg", "png", "gif", "webp", "bmp", "heif", "heic")
    const val ISO_6709_PATTERN = "([+-][0-9]{2}.[0-9]{4})([+-][0-9]{3}.[0-9]{4})"

    @SuppressLint("SimpleDateFormat")
    @JvmOverloads
    fun getPhotoParams(metadataRetriever: MediaMetadataRetriever?, exifInterface: ExifInterface?, localPath: String, mimeType: String, fileName: String, updateCreationDate: Boolean = false, keepOriginalOrientation: Boolean = false): Photo {
        val dateFormatter = SimpleDateFormat(DATE_FORMAT_PATTERN).apply { timeZone = TimeZone.getDefault() }
        var timeString: String?
        var mMimeType = mimeType
        var width = 0
        var height = 0
        var tDate: LocalDateTime = LocalDateTime.now()
        var latlong: DoubleArray = doubleArrayOf(Photo.NO_GPS_DATA, Photo.NO_GPS_DATA)
        var altitude = Photo.NO_GPS_DATA
        var bearing = Photo.NO_GPS_DATA
        //var caption = ""
        var orientation = 0

        val isLocalFileExist = localPath.isNotEmpty()
        val lastModified = Date(if (isLocalFileExist) File(localPath).lastModified() else System.currentTimeMillis())

        if (mimeType.startsWith("video/", true)) {
            metadataRetriever?.run {
                tDate = getVideoFileDate(this, fileName)

                extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.let { rotate ->
                    orientation = rotate.toInt()

                    if (rotate == "90" || rotate == "270") {
                        extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.let { height = it.toInt() }
                        extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.let { width = it.toInt() }
                    } else {
                        extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.let { width = it.toInt() }
                        extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.let { height = it.toInt() }
                    }
                }
                extractMetadata(MediaMetadataRetriever.METADATA_KEY_LOCATION)?.let {
                    val iso6709matcher = Pattern.compile(ISO_6709_PATTERN).matcher(it)
                    if (iso6709matcher.matches()) iso6709matcher.run { try { latlong = doubleArrayOf(group(1)?.toDouble() ?: Photo.NO_GPS_DATA, group(2)?.toDouble() ?: Photo.NO_GPS_DATA) } catch (e: Exception) {} }
                }
            }
        } else {
            // See if we can guess the taken date from file name
            timeString = parseFileName(fileName)
            if (isUnknown(timeString)) timeString = dateFormatter.format(lastModified)

            when(val imageFormat = mimeType.substringAfter("image/", "")) {
                in FORMATS_WITH_EXIF-> {
                    // Try extracting photo's capture date from EXIF, try rotating the photo if EXIF tell us to, save EXIF if we rotated the photo
                    var saveExif = false

                    exifInterface?.let { exif->
                        // TODO Photo caption, subject, tag
                        //exif.getAttribute(ExifInterface.TAG_IMAGE_DESCRIPTION)?.let { caption = it }
                        //if (caption.isBlank()) exif.getAttribute(ExifInterface.TAG_USER_COMMENT)?.let { caption = it }

                        // GPS data
                        exif.latLong?.let { latlong = it }
                        altitude = exif.getAltitude(Photo.NO_GPS_DATA)
                        exif.getAttribute(ExifInterface.TAG_GPS_DEST_BEARING)?.let { try { bearing = it.toDouble() } catch (e: NumberFormatException) {} }
                        if (bearing == Photo.NO_GPS_DATA) exif.getAttribute(ExifInterface.TAG_GPS_IMG_DIRECTION)?.let { try { bearing = it.toDouble() } catch (e: java.lang.NumberFormatException) {} }

                        // Taken date
                        timeString = getImageFileDate(exif, fileName)
                        if (isUnknown(timeString)) {
                            timeString = dateFormatter.format(lastModified)

                            if (updateCreationDate) {
                                exif.setAttribute(ExifInterface.TAG_DATETIME_DIGITIZED, timeString)
                                saveExif = true
                            }
                        }

                        width = exifInterface.getAttributeInt(ExifInterface.TAG_IMAGE_WIDTH, 0)
                        height = exifInterface.getAttributeInt(ExifInterface.TAG_IMAGE_LENGTH, 0)

                        orientation = exif.rotationDegrees
                        if (orientation != 0) {
                            if (isLocalFileExist) {
                                // Either by acquiring file from local or downloading media file from server for Local album, must rotate file
                                try {
                                    // TODO what if rotation fails?
                                    BitmapFactory.decodeFile(localPath)?.let {
                                        Bitmap.createBitmap(it, 0, 0, it.width, it.height, Matrix().apply { preRotate(orientation.toFloat()) }, true).apply {
                                            if (compress(Bitmap.CompressFormat.JPEG, 95, File(localPath).outputStream())) {
                                                mMimeType = Photo.DEFAULT_MIMETYPE

                                                // Swap width and height value, write back to exif and save in Room (see return value at the bottom)
                                                if (orientation == 90 || orientation == 270) {
                                                    val t = width
                                                    width = height
                                                    height = t
                                                }
                                                exif.resetOrientation()
                                                exif.setAttribute(ExifInterface.TAG_IMAGE_WIDTH, "$width")
                                                exif.setAttribute(ExifInterface.TAG_IMAGE_LENGTH, "$height")
                                                saveExif = true
                                            }
                                            recycle()
                                        }
                                    }
                                } catch (e: Exception) {}
                            } else {
                                // Swap width and height value if needed and save it to Room
                                if (orientation == 90 || orientation == 270) {
                                    val t = width
                                    width = height
                                    height = t
                                }
                            }
                        }

                        if (saveExif) {
                            try { exif.saveAttributes() }
                            catch (e: Exception) {
                                // TODO: better way to handle this
                                Log.e("****Exception", e.stackTraceToString())
                            }
                        }
                    }

                    if (imageFormat == "webp" && isLocalFileExist) {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                            // Set my own image/awebp mimetype for animated WebP
                            if (ImageDecoder.decodeDrawable(ImageDecoder.createSource(File(localPath))) is AnimatedImageDrawable) mMimeType = "image/awebp"
                        }
                    }
                }
                "gif"-> {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && isLocalFileExist) {
                        // Set my own image/agif mimetype for animated GIF
                        if (ImageDecoder.decodeDrawable(ImageDecoder.createSource(File(localPath))) is AnimatedImageDrawable) mMimeType = "image/agif"
                    }
                }
                else-> {}
            }

            // Get image width and height for local album if they can't fetched from EXIF
            if (isLocalFileExist && width == 0) try {
                val options = BitmapFactory.Options().apply {
                    inJustDecodeBounds = true
                    BitmapFactory.decodeFile(localPath, this)
                }
                width = options.outWidth
                height = options.outHeight
            } catch (e: Exception) {}

            tDate = try {
                LocalDateTime.parse(timeString, DateTimeFormatter.ofPattern(DATE_FORMAT_PATTERN))
            } catch (e: DateTimeParseException) {
                dateToLocalDateTime(lastModified)
            }
        }

        return Photo(
            mimeType = mMimeType,
            dateTaken = tDate, lastModified = dateToLocalDateTime(lastModified),
            width = width, height = height,
            //caption = caption,
            latitude = latlong[0], longitude = latlong[1], altitude = altitude, bearing = bearing,
            orientation = if (keepOriginalOrientation) orientation else 0
        )
        //return Photo("", "", "", Photo.ETAG_NOT_YET_UPLOADED, tDate, dateToLocalDateTime(lastModified), width, height, mMimeType, 0)
    }

    @SuppressLint("SimpleDateFormat")
    fun getVideoFileDate(extractor: MediaMetadataRetriever, fileName: String): LocalDateTime {
        var videoDate: LocalDateTime = LocalDateTime.MIN

        // Try get creation date from metadata
        extractor.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DATE)?.let { cDate->
            try { videoDate = LocalDateTime.parse(cDate, DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss.SSS'Z'")) } catch (e: Exception) { e.printStackTrace() }
        }

        // If metadata tells a funky date, reset it. Apple platform seems to set the date 1904/01/01 as default
        // Could not get creation date from metadata, try guessing from file name
        if (videoDate.year == 1904 || videoDate == LocalDateTime.MIN) videoDate = parseFileName(fileName)?.run { LocalDateTime.parse(this, DateTimeFormatter.ofPattern(DATE_FORMAT_PATTERN)) } ?: LocalDateTime.now()

        return videoDate
    }

    fun getImageFileDate(exifInterface: ExifInterface, fileName: String): String? {
        var timeString: String?

        timeString = exifInterface.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL)
        if (isUnknown(timeString)) timeString = exifInterface.getAttribute(ExifInterface.TAG_DATETIME_DIGITIZED)
        //if (isUnknown(timeString)) timeString = exif.getAttribute(ExifInterface.TAG_DATETIME)

        // Could not get creation date from exif, try guessing from file name
        if (isUnknown(timeString)) timeString = parseFileName(fileName)

        return timeString
    }


    // matching Wechat export file name, the 13 digits suffix is the export time in epoch long
    private const val wechatPattern = "^mmexport([0-9]{10}).*"
    private const val timeStampPattern = ".*([12][0-9]{3})(0[1-9]|1[0-2])(0[1-9]|[12][0-9]|3[01])_?([01][0-9]|2[0-3])([0-5][0-9])([0-5][0-9]).*"
    private fun parseFileName(fileName: String): String? {
        var matcher = Pattern.compile(wechatPattern).matcher(fileName)
        @Suppress("RECEIVER_NULLABILITY_MISMATCH_BASED_ON_JAVA_ANNOTATIONS")
        return if (matcher.matches()) LocalDateTime.ofEpochSecond(matcher.group(1).toLong(), 0, OffsetDateTime.now().offset).format(DateTimeFormatter.ofPattern(DATE_FORMAT_PATTERN))
            else {
                matcher = Pattern.compile(timeStampPattern).matcher(fileName)
                if (matcher.matches()) matcher.run { "${group(1)}:${group(2)}:${group(3)} ${group(4)}:${group(5)}:${group(6)}" }
                else null
            }
    }

    private fun isUnknown(date: String?): Boolean {
        return (date == null || date.isEmpty() || date == "    :  :     :  :  ")
    }

    fun dateToLocalDateTime(date: Date): LocalDateTime = date.toInstant().atZone(ZoneId.systemDefault()).toLocalDateTime()

    fun isMediaPlayable(mimeType: String): Boolean = (mimeType == "image/agif") || (mimeType == "image/awebp") || (mimeType.startsWith("video/", true))

    fun hasExif(mimeType: String): Boolean = mimeType.substringAfter('/') in FORMATS_WITH_EXIF

    @SuppressLint("DefaultLocale")
    fun humanReadableByteCountSI(size: Long): String {
        var bytes = size
        if (-1000 < bytes && bytes < 1000) return "$bytes B"
        val ci: CharacterIterator = StringCharacterIterator("kMGTPE")
        while (bytes <= -999950 || bytes >= 999950) {
            bytes /= 1000
            ci.next()
        }
        return java.lang.String.format("%s%cB", DecimalFormat("###.#").format(bytes/1000.0), ci.current())
    }

    fun getDeviceModel(): String {
        val manufacturer = Build.MANUFACTURER.lowercase()
        var model = Build.MODEL.lowercase()

        if (model.startsWith(manufacturer)) model = model.substring(manufacturer.length).trim()

        return "${manufacturer}_${model}"
    }

    fun getCameraRoll(cr: ContentResolver, imageOnly: Boolean): MutableList<Photo> = listMediaContent("DCIM", cr, imageOnly, false)
    fun listMediaContent(folder: String, cr: ContentResolver, imageOnly: Boolean, strict: Boolean): MutableList<Photo> {
        val medias = mutableListOf<Photo>()
        val externalStorageUri = MediaStore.Files.getContentUri("external")

        @Suppress("DEPRECATION")
        val pathSelection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) MediaStore.Files.FileColumns.RELATIVE_PATH else MediaStore.Files.FileColumns.DATA
        val dateSelection = "datetaken"     // MediaStore.MediaColumns.DATE_TAKEN, hardcoded here since it's only available in Android Q or above
        val projection = arrayOf(
            MediaStore.Files.FileColumns._ID,
            pathSelection,
            dateSelection,
            MediaStore.Files.FileColumns.DATE_ADDED,
            MediaStore.Files.FileColumns.MEDIA_TYPE,
            MediaStore.Files.FileColumns.MIME_TYPE,
            MediaStore.Files.FileColumns.DISPLAY_NAME,
            MediaStore.Files.FileColumns.SIZE,
            MediaStore.Files.FileColumns.WIDTH,
            MediaStore.Files.FileColumns.HEIGHT,
            "orientation",                  // MediaStore.Files.FileColumns.ORIENTATION, hardcoded here since it's only available in Android Q or above
        )
        val selection = if (imageOnly) "(${MediaStore.Files.FileColumns.MEDIA_TYPE}=${MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE}) AND ($pathSelection LIKE '%${folder}%')"
            else "(${MediaStore.Files.FileColumns.MEDIA_TYPE}=${MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE} OR ${MediaStore.Files.FileColumns.MEDIA_TYPE}=${MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO}) AND ($pathSelection LIKE '%${folder}%')"

        try {
            cr.query(externalStorageUri, projection, selection, null, "$dateSelection DESC")?.use { cursor ->
                val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns._ID)
                val nameColumn = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DISPLAY_NAME)
                //val pathColumn = cursor.getColumnIndexOrThrow(pathSelection)
                val dateColumn = cursor.getColumnIndexOrThrow(dateSelection)
                val dateAddedColumn = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DATE_ADDED)
                val typeColumn = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.MIME_TYPE)
                val sizeColumn = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.SIZE)
                val widthColumn = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.WIDTH)
                val heightColumn = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.HEIGHT)
                val orientationColumn = cursor.getColumnIndexOrThrow("orientation")    // MediaStore.Files.FileColumns.ORIENTATION, hardcoded here since it's only available in Android Q or above
                val defaultZone = ZoneId.systemDefault()
                var mimeType: String
                var date: Long
                var reSort = false
                var contentUri: Uri

                cursorLoop@ while (cursor.moveToNext()) {
                    if ((strict) && (cursor.getString(cursor.getColumnIndexOrThrow(pathSelection)) ?: folder).substringAfter(folder).contains('/')) continue

                    // Insert media
                    mimeType = cursor.getString(typeColumn)
                    // Make sure image type is supported
                    contentUri = if (mimeType.startsWith("image")) {
                        if (mimeType.substringAfter("image/", "") !in SUPPORTED_PICTURE_FORMATS) continue@cursorLoop
                        else MediaStore.Images.Media.EXTERNAL_CONTENT_URI
                    } else MediaStore.Video.Media.EXTERNAL_CONTENT_URI

                    date = cursor.getLong(dateColumn)
                    if (date == 0L) {
                        // Sometimes dateTaken is not available from system, use dateAdded instead
                        date = cursor.getLong(dateAddedColumn) * 1000
                        reSort = true
                    }
                    medias.add(
                        Photo(
                            id = ContentUris.withAppendedId(contentUri, cursor.getString(idColumn).toLong()).toString(),
                            albumId = CameraRollFragment.FROM_CAMERA_ROLL,
                            name = cursor.getString(nameColumn) ?: "",
                            dateTaken = LocalDateTime.ofInstant(Instant.ofEpochMilli(date), defaultZone),     // DATE_TAKEN has nano adjustment
                            lastModified = LocalDateTime.MIN,
                            width = cursor.getInt(widthColumn),
                            height = cursor.getInt(heightColumn),
                            mimeType = mimeType,
                            shareId = cursor.getInt(sizeColumn),                  // Saving photo size value in shareId property
                            orientation = cursor.getInt(orientationColumn)        // Saving photo orientation value in shareId property
                        )
                    )
                }

                // Resort the list if dateAdded used
                if (reSort) medias.sortWith(compareByDescending { it.dateTaken })
            }
        } catch (e: Exception) {}

        return medias
    }

    fun getCameraRollAlbum(cr: ContentResolver, albumName: String): Album? {
        val externalStorageUri = MediaStore.Files.getContentUri("external")
        var startDate = LocalDateTime.MIN
        var endDate: LocalDateTime
        var coverId: String
        val coverBaseline = 0   // TODO better default baseline
        var coverWidth: Int
        var coverHeight: Int
        var coverFileName: String
        var coverMimeType: String
        var orientation: Int

        @Suppress("DEPRECATION")
        val pathSelection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) MediaStore.Files.FileColumns.RELATIVE_PATH else MediaStore.Files.FileColumns.DATA
        val dateSelection = "datetaken"     // MediaStore.MediaColumns.DATE_TAKEN, hardcoded here since it's only available in Android Q or above
        val projection = arrayOf(
            MediaStore.Files.FileColumns._ID,
            MediaStore.Files.FileColumns.DISPLAY_NAME,
            dateSelection,
            MediaStore.Files.FileColumns.MEDIA_TYPE,
            MediaStore.Files.FileColumns.MIME_TYPE,
            MediaStore.Files.FileColumns.WIDTH,
            MediaStore.Files.FileColumns.HEIGHT,
            "orientation",                  // MediaStore.Files.FileColumns.ORIENTATION, hardcoded here since it's only available in Android Q or above
        )
        val selection ="(${MediaStore.Files.FileColumns.MEDIA_TYPE}=${MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE} OR ${MediaStore.Files.FileColumns.MEDIA_TYPE}=${MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO}) AND ($pathSelection LIKE '%DCIM%') AND (${MediaStore.Files.FileColumns.WIDTH}!=0)"

        try {
            cr.query(externalStorageUri, projection, selection, null, "$dateSelection DESC")?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val dateColumn = cursor.getColumnIndex(dateSelection)
                    val defaultZone = ZoneId.systemDefault()
                    coverMimeType = cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.MIME_TYPE))
                    val externalUri = if (coverMimeType.startsWith("video")) MediaStore.Video.Media.EXTERNAL_CONTENT_URI else MediaStore.Images.Media.EXTERNAL_CONTENT_URI

                    // Get album's end date, cover
                    endDate = LocalDateTime.ofInstant(Instant.ofEpochMilli(cursor.getLong(dateColumn)), defaultZone)
                    coverId = ContentUris.withAppendedId(externalUri, cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns._ID)).toLong()).toString()
                    coverFileName = cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DISPLAY_NAME))
                    coverWidth = cursor.getInt(cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.WIDTH))
                    coverHeight = cursor.getInt(cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.HEIGHT))
                    orientation = cursor.getInt(cursor.getColumnIndexOrThrow("orientation"))

                    // Get album's start date
                    if (cursor.moveToLast()) startDate = LocalDateTime.ofInstant(Instant.ofEpochMilli(cursor.getLong(dateColumn)), defaultZone)

                    // Cover's mimetype passed in property eTag, cover's orientation passed in property shareId
                    //return Album(CameraRollFragment.FROM_CAMERA_ROLL, albumName, startDate, endDate, coverId, coverBaseline, coverWidth, coverHeight, endDate, Album.BY_DATE_TAKEN_DESC, mimeType, orientation, 1.0F)
                    return Album(
                        id = CameraRollFragment.FROM_CAMERA_ROLL, name = albumName,
                        startDate = startDate, endDate = endDate, lastModified = endDate,
                        cover = coverId, coverFileName = coverFileName, coverBaseline = coverBaseline, coverWidth = coverWidth, coverHeight = coverHeight, coverMimeType = coverMimeType,
                        sortOrder = Album.BY_DATE_TAKEN_DESC,
                        eTag = Album.ETAG_CAMERA_ROLL_ALBUM,
                        shareId = Album.NULL_ALBUM,
                        coverOrientation = orientation,
                    )
                } else return null
            } ?: return null
        } catch (e: Exception) { return null }
    }

    fun getFolderFromUri(uriString: String, contentResolver: ContentResolver): Pair<String, String>? {
        val colon = "%3A"
        val storageUriSignature = "com.android.externalstorage.documents"
        val mediaProviderUriSignature = "com.android.providers.media.documents"
        val downloadProviderUriSignature = "com.android.providers.downloads.documents"

        //Log.e(">>>>>", "input: $uriString")
        return try {
            when {
                (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q && uriString.contains(downloadProviderUriSignature)) || uriString.contains(storageUriSignature) -> {
                    var id: String? = null
                    val folder = URLDecoder.decode(uriString.substringAfter(colon), "UTF-8").substringBeforeLast("/")
                    val externalStorageUri = MediaStore.Files.getContentUri("external")
                    @Suppress("DEPRECATION")
                    val pathColumn = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) MediaStore.Files.FileColumns.RELATIVE_PATH else MediaStore.Files.FileColumns.DATA
                    val projection = arrayOf(
                        MediaStore.Files.FileColumns._ID,
                        MediaStore.Files.FileColumns.DISPLAY_NAME,
                        pathColumn,
                    )
                    val selection = "($pathColumn LIKE '%${folder}%') AND (${MediaStore.Files.FileColumns.DISPLAY_NAME}='${URLDecoder.decode(uriString, "UTF-8").substringAfterLast('/')}')"

                    contentResolver.query(externalStorageUri, projection, selection, null, null)?.use { cursor ->
                        if (cursor.moveToFirst()) id = cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns._ID))
                    }

                    id?.let { Pair("$folder/", id!!) }
                }
                uriString.contains(mediaProviderUriSignature) || uriString.contains(downloadProviderUriSignature) -> {
                    var folderName: String? = null
                    val id = uriString.substringAfter(colon)
                    val externalStorageUri = MediaStore.Files.getContentUri("external")
                    @Suppress("DEPRECATION")
                    val pathSelection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) MediaStore.Files.FileColumns.RELATIVE_PATH else MediaStore.Files.FileColumns.DATA
                    val projection = arrayOf(
                        MediaStore.Files.FileColumns._ID,
                        pathSelection,
                    )
                    val selection = "${MediaStore.Files.FileColumns._ID} = $id"

                    contentResolver.query(externalStorageUri, projection, selection, null, null)?.use { cursor ->
                        if (cursor.moveToFirst()) folderName = cursor.getString(cursor.getColumnIndexOrThrow(pathSelection))
                    }

                    folderName?.let { Pair(if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) folderName!! else "${folderName!!.substringAfter("/storage/emulated/0/").substringBeforeLast('/')}/", id) }
                }
                else -> null
            }
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    fun getLocalRoot(context: Context): String {
        return "${if (PreferenceManager.getDefaultSharedPreferences(context).getBoolean(SettingsFragment.KEY_STORAGE_LOCATION, true)) "${context.filesDir}" else "${context.getExternalFilesDirs(null)[1]}"}${context.getString(R.string.lespas_base_folder_name)}"
    }

    fun getStorageSize(context: Context): Long {
        var totalBytes = 0L
        val path = getLocalRoot(context)

        try {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) { File(path).listFiles()?.forEach { file -> totalBytes += file.length() }}
            else { totalBytes = Files.walk(Paths.get(path)).mapToLong { p -> p.toFile().length() }.sum() }
        } catch (e: Exception) { e.printStackTrace() }

        return totalBytes
    }

    fun getRoundBitmap(context: Context, bitmap: Bitmap): Bitmap {
        val width = bitmap.width
        val height = bitmap.height
        return Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply {
            Canvas(this).apply {
                clipPath(Path().apply { addCircle((width.toFloat() / 2), (height.toFloat() / 2), min(width.toFloat(), (height.toFloat() / 2)), Path.Direction.CCW) })
                drawPaint(Paint().apply {
                    color = ContextCompat.getColor(context, R.color.color_avatar_default_background)
                    style = Paint.Style.FILL
                })
                drawBitmap(bitmap, 0f, 0f, null)
            }
        }
    }

    @Suppress("DEPRECATION")
    fun goImmersive(window: Window, delayTranslucentEffect: Boolean = false) {
        window.apply {
/*
            val systemBarBackground = ContextCompat.getColor(requireContext(), R.color.dark_gray_overlay_background)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                previousNavBarColor = navigationBarColor
                navigationBarColor = systemBarBackground
                statusBarColor = systemBarBackground
                insetsController?.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                setDecorFitsSystemWindows(false)
            } else {
                addFlags(WindowManager.LayoutParams.FLAG_TRANSLUCENT_NAVIGATION)
                addFlags(WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS)
            }
*/
            //previousNavBarColor = navigationBarColor
            //navigationBarColor = Color.TRANSPARENT
            //statusBarColor = Color.TRANSPARENT
            if (delayTranslucentEffect) Handler(Looper.getMainLooper()).postDelayed({ addFlags(WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS) }, 1000) else addFlags(WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS)
            addFlags(WindowManager.LayoutParams.FLAG_TRANSLUCENT_NAVIGATION)
        }
        if (window.decorView.systemUiVisibility and View.SYSTEM_UI_FLAG_FULLSCREEN != View.SYSTEM_UI_FLAG_FULLSCREEN) window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_IMMERSIVE or
            // Set the content to appear under the system bars so that the
            // content doesn't resize when the system bars hide and show.
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
            View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
            // Hide the nav bar and status bar
            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_FULLSCREEN
        )
/*
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_IMMERSIVE or
                // Set the content to appear under the system bars so that the
                // content doesn't resize when the system bars hide and show.
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                // Hide the nav bar and status bar
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_FULLSCREEN
            )
        } else {
            window.insetsController?.apply {
                systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                hide(WindowInsets.Type.systemBars())
            }
        }
*/
    }

    fun getPreparingSharesSnackBar(anchorView: View, strip: Boolean, cancelAction: View.OnClickListener?): Snackbar {
        val ctx = anchorView.context
        return Snackbar.make(anchorView, if (strip) R.string.striping_exif else R.string.preparing_shares, Snackbar.LENGTH_INDEFINITE).apply {
            try {
                (view.findViewById<MaterialTextView>(com.google.android.material.R.id.snackbar_text).parent as ViewGroup).addView(ProgressBar(ctx).apply {
                    // Android Snackbar text size is 14sp
                    val pbHeight = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 14f, resources.displayMetrics).roundToInt()
                    layoutParams = (LinearLayout.LayoutParams(pbHeight, pbHeight)).apply { gravity = Gravity.CENTER_VERTICAL or Gravity.END }
                    indeterminateTintList = ColorStateList.valueOf(ContextCompat.getColor(ctx, R.color.color_text_light))
                }, 0)
            } catch (e: Exception) {}
            animationMode = Snackbar.ANIMATION_MODE_FADE
            setBackgroundTint(ContextCompat.getColor(ctx, R.color.color_primary))
            setTextColor(ContextCompat.getColor(ctx, R.color.color_text_light))
            cancelAction?.let {
                setAction(android.R.string.cancel, it)
                setActionTextColor(ContextCompat.getColor(anchorView.context, R.color.color_error))
            }
        }
    }

    fun getDisplayWidth(wm: WindowManager): Int {
        return DisplayMetrics().run {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
                @Suppress("DEPRECATION")
                wm.defaultDisplay.getRealMetrics(this)
                widthPixels
            } else {
                wm.currentWindowMetrics.bounds.width()
            }
        }
    }

    fun isRemoteAlbum(album: Album): Boolean = (album.shareId and Album.REMOTE_ALBUM) == Album.REMOTE_ALBUM
    fun isExcludedAlbum(album: Album): Boolean = (album.shareId and Album.EXCLUDED_ALBUM) == Album.EXCLUDED_ALBUM
}