package site.leos.apps.lespas.publication

import android.accounts.AccountManager
import android.annotation.SuppressLint
import android.app.ActivityManager
import android.app.Application
import android.content.Context
import android.graphics.*
import android.graphics.drawable.AnimatedImageDrawable
import android.graphics.drawable.Drawable
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Parcelable
import android.util.LruCache
import android.view.View
import android.widget.ImageView
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.thegrizzlylabs.sardineandroid.impl.SardineException
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.parcelize.Parcelize
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody
import okio.IOException
import org.json.JSONException
import org.json.JSONObject
import site.leos.apps.lespas.R
import site.leos.apps.lespas.album.Album
import site.leos.apps.lespas.album.Cover
import site.leos.apps.lespas.helper.ImageLoaderViewModel
import site.leos.apps.lespas.helper.Tools
import site.leos.apps.lespas.photo.PhotoRepository
import java.io.File
import java.lang.Thread.sleep
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.time.OffsetDateTime
import java.util.concurrent.Executors
import kotlin.math.min

class NCShareViewModel(application: Application): AndroidViewModel(application) {
    private val _shareByMe = MutableStateFlow<List<ShareByMe>>(arrayListOf())
    private val _shareWithMe = MutableStateFlow<List<ShareWithMe>>(arrayListOf())
    private val _sharees = MutableStateFlow<List<Sharee>>(arrayListOf())
    val shareByMe: StateFlow<List<ShareByMe>> = _shareByMe
    val shareWithMe: StateFlow<List<ShareWithMe>> = _shareWithMe
    val sharees: StateFlow<List<Sharee>> = _sharees

    private val baseUrl: String
    private val userName: String
    private var httpClient: OkHttpClient? = null
    private var cachedHttpClient: OkHttpClient? = null
    private val resourceRoot: String
    private val lespasBase = application.getString(R.string.lespas_base_folder_name)
    private val localRootFolder = "${application.cacheDir}${lespasBase}"

    private val placeholderBitmap = Tools.getBitmapFromVector(application, R.drawable.ic_baseline_placeholder_24)
    private val videoThumbnail = Tools.getBitmapFromVector(application, R.drawable.ic_baseline_play_circle_24)

    private val imageCache = ImageCache(((application.getSystemService(Context.ACTIVITY_SERVICE)) as ActivityManager).memoryClass / 8 * 1024 * 1024)
    private val diskCache = Cache(File(localRootFolder), 300L * 1024L * 1024L)
    private val decoderJobMap = HashMap<Int, Job>()
    private val downloadDispatcher = Executors.newFixedThreadPool(2).asCoroutineDispatcher()

    private val photoRepository = PhotoRepository(application)

    fun interface LoadCompleteListener{
        fun onLoadComplete()
    }

    init {
        AccountManager.get(application).run {
            val account = accounts[0]
            userName = getUserData(account, application.getString(R.string.nc_userdata_username))
            baseUrl = getUserData(account, application.getString(R.string.nc_userdata_server))
            resourceRoot = "$baseUrl${application.getString(R.string.dav_files_endpoint)}$userName"
            try {
                val builder = OkHttpClient.Builder().apply {
                    if (getUserData(account, application.getString(R.string.nc_userdata_selfsigned)).toBoolean()) hostnameVerifier { _, _ -> true }
                    addInterceptor { chain -> chain.proceed(chain.request().newBuilder().header("Authorization", Credentials.basic(userName, peekAuthToken(account, baseUrl), StandardCharsets.UTF_8)).build()) }
                    addNetworkInterceptor { chain -> chain.proceed((chain.request().newBuilder().removeHeader("User-Agent").addHeader("User-Agent", "LesPas_${application.getString(R.string.lespas_version)}").build())) }
                }
                httpClient = builder.build()
                cachedHttpClient = builder.cache(diskCache)
                    .addNetworkInterceptor { chain -> chain.proceed(chain.request()).newBuilder().removeHeader("Pragma").header("Cache-Control", "public, max-age=864000").build() }
                    .build()
            } catch (e: Exception) { e.printStackTrace() }
        }

        viewModelScope.launch(Dispatchers.IO) {
            _sharees.value = getSharees()
            getShareList()
        }
    }

    private fun ocsGet(url: String): JSONObject? {
        var result: JSONObject? = null

        httpClient?.apply {
            Request.Builder().url(url).addHeader(NEXTCLOUD_OCSAPI_HEADER, "true").apply {
                newCall(build()).execute().use {
                    result = it.body?.string()?.let { response -> JSONObject(response).getJSONObject("ocs") }
                }
            }
        }

        return result
    }

    val themeColor: Flow<Int> = flow {
        var color = 0

        try {
            ocsGet("$baseUrl$CAPABILLITIES_ENDPOINT")?.apply {
                color = Integer.parseInt(getJSONObject("data").getJSONObject("capabilities").getJSONObject("theming").getString("color").substringAfter('#'), 16)
            }
            if (color != 0) emit(color)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }.flowOn(Dispatchers.IO)

    private fun getShareList() {
        val sharesBy = mutableListOf<ShareByMe>()
        val sharesWith = mutableListOf<ShareWithMe>()
        var sharee: Recipient
        var backOff = 2500L
        var sPath = ""
        var rCode = 200
        val lespasBaseLength = lespasBase.length
        val group = _sharees.value.filter { it.type == SHARE_TYPE_GROUP }

        while(true) {
            try {
                ocsGet("$baseUrl$SHARE_LISTING_ENDPOINT")?.apply {
                    //if (getJSONObject("meta").getInt("statuscode") != 200) return null  // TODO this safety check is not necessary
                    var shareType: Int
                    var idString: String
                    var labelString: String
                    var pathString: String
                    var recipientString: String

                    val data = getJSONArray("data")
                    for (i in 0 until data.length()) {
                        data.getJSONObject(i).apply {
                            shareType = when (getString("type")) {
                                SHARE_TYPE_USER_STRING -> SHARE_TYPE_USER
                                SHARE_TYPE_GROUP_STRING -> SHARE_TYPE_GROUP
                                else -> -1
                            }
                            pathString = getString("path")
                            if (shareType >= 0 && getBoolean("is_directory") && pathString.startsWith(lespasBase) && pathString.length > lespasBaseLength) {
                                // Only interested in shares of subfolders under /lespas

                                recipientString = getString("recipient")
                                if (getString("owner") == userName) {
                                    // This is a share by me, get recipient's label
                                    idString = recipientString
                                    labelString = _sharees.value.find { it.name == idString }?.label ?: idString

                                    sharee = Recipient(getString("id"), getInt("permissions"), OffsetDateTime.parse(getString("time")).toInstant().epochSecond, Sharee(idString, labelString, shareType))

                                    @Suppress("SimpleRedundantLet")
                                    sharesBy.find { share -> share.fileId == getString("file_id") }?.let { item ->
                                        // If this folder existed in result, add new sharee only
                                        item.with.add(sharee)
                                    } ?: run {
                                        // Create new share by me item
                                        sharesBy.add(ShareByMe(getString("file_id"), getString("name"), mutableListOf(sharee)))
                                    }
                                } else if (sharesWith.indexOfFirst { it.albumId == getString("file_id") } == -1 && (recipientString == userName || group.indexOfFirst { it.name == recipientString } != -1)) {
                                    // This is a share with me, either direct to me or to my groups, get owner's label
                                    idString = getString("owner")
                                    labelString = _sharees.value.find { it.name == idString }?.label ?: idString

                                    sharesWith.add(ShareWithMe(getString("id"),"", getString("file_id"), getString("name"), idString, labelString, getInt("permissions"), OffsetDateTime.parse(getString("time")).toInstant().epochSecond, Cover("", 0, 0, 0),"", Album.BY_DATE_TAKEN_ASC))
                                }
                            }
                        }
                    }
                }

                _shareByMe.value = sharesBy

                // To avoid flooding http calls to server, cache share path, since in most cases, user won't change share path often
                if (sharesWith.size > 0) sPath = (getSharePath(sharesWith[0].shareId) ?: "").substringBeforeLast('/')
                for (share in sharesWith) {
                    share.sharePath = "${sPath}/${share.albumName}"
                    cachedHttpClient?.apply {
                        newCall(Request.Builder().url("${resourceRoot}${share.sharePath}/${share.albumId}.json").build()).execute().use {
                            rCode = it.code
                            JSONObject(it.body?.string() ?: "").getJSONObject("lespas").let { meta ->
                                meta.getJSONObject("cover").apply {
                                    share.cover = Cover(getString("id"), getInt("baseline"), getInt("width"), getInt("height"))
                                    share.coverFileName = getString("filename")
                                }
                                share.sortOrder = meta.getInt("sort")
                            }
                        }
                    }

                    if (rCode == 404) {
                        // If we the meta file is not found on server, share path might be different, try again after updating the share path from server
                        sPath = (getSharePath(share.shareId) ?: "").substringBeforeLast('/')

                        share.sharePath = "${sPath}/${share.albumName}"
                        cachedHttpClient?.apply {
                            newCall(Request.Builder().url("${resourceRoot}${share.sharePath}/${share.albumId}.json").build()).execute().use {
                                JSONObject(it.body?.string() ?: "").getJSONObject("lespas").let { meta ->
                                    meta.getJSONObject("cover").apply {
                                        share.cover = Cover(getString("id"), getInt("baseline"), getInt("width"), getInt("height"))
                                        share.coverFileName = getString("filename")
                                    }
                                    share.sortOrder = meta.getInt("sort")
                                }
                            }
                        }
                    }
                }

                _shareWithMe.value = sharesWith.apply { sort() }
                break
            }
            catch (e: UnknownHostException) {
                // Retry for network unavailable, hope it's temporarily
                backOff *= 2
                sleep(backOff)
            }
            catch (e: SocketTimeoutException) {
                // Retry for network unavailable, hope it's temporarily
                backOff *= 2
                sleep(backOff)
            } catch (e: Exception) {
                e.printStackTrace()
                break
            }
        }
    }

    private fun updateShareByMe(): MutableList<ShareByMe> {
        val result = mutableListOf<ShareByMe>()
        var sharee: Recipient

        try {
            ocsGet("$baseUrl$SHARE_LISTING_ENDPOINT")?.apply {
                //if (getJSONObject("meta").getInt("statuscode") != 200) return null  // TODO this safety check is not necessary
                var shareType: Int
                var idString: String
                var labelString: String
                var pathString: String
                val lespasBaseLength = lespasBase.length

                val data = getJSONArray("data")
                for (i in 0 until data.length()) {
                    data.getJSONObject(i).apply {
                        shareType = when(getString("type")) {
                            SHARE_TYPE_USER_STRING-> SHARE_TYPE_USER
                            SHARE_TYPE_GROUP_STRING-> SHARE_TYPE_GROUP
                            else-> -1
                        }
                        pathString = getString("path")
                        if (shareType >= 0 && getString("owner") == userName && getBoolean("is_directory") && pathString.startsWith("/lespas") && pathString.length > lespasBaseLength) {
                            // Only interested in shares of subfolders under lespas/

                            idString = getString("recipient")
                            labelString = _sharees.value.find { it.name == idString }?.label ?: idString
                            sharee = Recipient(getString("id"), getInt("permissions"), OffsetDateTime.parse(getString("time")).toInstant().epochSecond, Sharee(idString, labelString, shareType))

                            @Suppress("SimpleRedundantLet")
                            result.find { share-> share.fileId == getString("file_id") }?.let { item->
                                // If this folder existed in result, add new sharee only
                                item.with.add(sharee)
                            } ?: run {
                                // Create new folder share item
                                result.add(ShareByMe(getString("file_id"), getString("name"), mutableListOf(sharee)))
                            }
                        }
                    }
                }
            }

            return result
        }
        catch (e: IOException) { e.printStackTrace() }
        catch (e: IllegalStateException) { e.printStackTrace() }
        catch (e: JSONException) { e.printStackTrace() }

        return arrayListOf()
    }

    private fun getShareWithMe(): MutableList<ShareWithMe> {
        val result = mutableListOf<ShareWithMe>()
        var sPath = ""
        var rCode = 200
        val group = _sharees.value.filter { it.type == SHARE_TYPE_GROUP }

        try {
            ocsGet("$baseUrl$SHARE_LISTING_ENDPOINT")?.apply {
                //if (getJSONObject("meta").getInt("statuscode") != 200) return null  // TODO this safety check is not necessary
                var shareType: Int
                var idString: String
                var labelString: String
                var pathString: String
                var recipientString: String
                val lespasBaseLength = lespasBase.length

                val data = getJSONArray("data")
                for (i in 0 until data.length()) {
                    data.getJSONObject(i).apply {
                        shareType = when (getString("type")) {
                            SHARE_TYPE_USER_STRING -> SHARE_TYPE_USER
                            SHARE_TYPE_GROUP_STRING -> SHARE_TYPE_GROUP
                            else -> -1
                        }
                        pathString = getString("path")
                        if (shareType >= 0 && getBoolean("is_directory") && pathString.startsWith(lespasBase) && pathString.length > lespasBaseLength) {
                            // Only interested in shares of subfolders under lespas/

                            recipientString = getString("recipient")
                            if (result.indexOfFirst { it.albumId == getString("file_id") } == -1 && (recipientString == userName || group.indexOfFirst { it.name == recipientString } != -1)) {
                                // This is a share with me, either direct to me or to my groups, get owner's label
                                idString = getString("owner")
                                labelString = _sharees.value.find { it.name == idString }?.label ?: idString

                                result.add(ShareWithMe(getString("id"), "", getString("file_id"), getString("name"), idString, labelString, getInt("permissions"), OffsetDateTime.parse(getString("time")).toInstant().epochSecond, Cover("", 0, 0, 0), "", Album.BY_DATE_TAKEN_ASC))
                            }
                        }
                    }
                }
            }

            // To avoid flooding http calls to server, cache share path, since in most cases, user won't change share path often
            if (result.size > 0) sPath = (getSharePath(result[0].shareId) ?: "").substringBeforeLast('/')
            for (share in result) {
                share.sharePath = "${sPath}/${share.albumName}"
                cachedHttpClient?.apply {
                    newCall(Request.Builder().url("${resourceRoot}${share.sharePath}/${share.albumId}.json").build()).execute().use {
                        rCode = it.code
                        JSONObject(it.body?.string() ?: "").getJSONObject("lespas").let { meta ->
                            meta.getJSONObject("cover").apply {
                                share.cover = Cover(getString("id"), getInt("baseline"), getInt("width"), getInt("height"))
                                share.coverFileName = getString("filename")
                            }
                            share.sortOrder = meta.getInt("sort")
                        }
                    }
                }

                if (rCode == 404) {
                    // If we the meta file is not found on server, share path might be different, try again after updating the share path from server
                    sPath = (getSharePath(share.shareId) ?: "").substringBeforeLast('/')

                    share.sharePath = "${sPath}/${share.albumName}"
                    cachedHttpClient?.apply {
                        newCall(Request.Builder().url("${resourceRoot}${share.sharePath}/${share.albumId}.json").build()).execute().use {
                            JSONObject(it.body?.string() ?: "").getJSONObject("lespas").let { meta ->
                                meta.getJSONObject("cover").apply {
                                    share.cover = Cover(getString("id"), getInt("baseline"), getInt("width"), getInt("height"))
                                    share.coverFileName = getString("filename")
                                }
                                share.sortOrder = meta.getInt("sort")
                            }
                        }
                    }
                }
            }

            return result.apply { sort() }
        }
        catch (e: IOException) { e.printStackTrace() }
        catch (e: IllegalStateException) { e.printStackTrace() }
        catch (e: JSONException) { e.printStackTrace() }

        return arrayListOf()
    }

    private fun getSharees(): MutableList<Sharee> {
        val result = mutableListOf<Sharee>()
        var backOff = 2500L

        while(true) {
            try {
                ocsGet("$baseUrl$SHAREE_LISTING_ENDPOINT")?.apply {
                    //if (getJSONObject("meta").getInt("statuscode") != 100) return null
                    val data = getJSONObject("data")
                    val users = data.getJSONArray("users")
                    for (i in 0 until users.length()) {
                        users.getJSONObject(i).apply {
                            result.add(Sharee(getString("shareWithDisplayNameUnique"), getString("label"), SHARE_TYPE_USER))
                        }
                    }
                    val groups = data.getJSONArray("groups")
                    for (i in 0 until groups.length()) {
                        groups.getJSONObject(i).apply {
                            result.add(Sharee(getJSONObject("value").getString("shareWith"), getString("label"), SHARE_TYPE_GROUP))
                        }
                    }
                }

                return result
            } catch (e: UnknownHostException) {
                // Retry for network unavailable, hope it's temporarily
                backOff *= 2
                sleep(backOff)
            } catch (e: SocketTimeoutException) {
                // Retry for network unavailable, hope it's temporarily
                backOff *= 2
                sleep(backOff)
            } catch (e: Exception) {
                e.printStackTrace()
                break
            }
        }

        return arrayListOf()
    }

    private fun createShares(albums: List<ShareByMe>) {
        var response: Response? = null

        httpClient?.let { httpClient->
            for (album in albums) {
                for (recipient in album.with) {
                    try {
                        response = httpClient.newCall(Request.Builder().url("$baseUrl$PUBLISH_ENDPOINT").addHeader(NEXTCLOUD_OCSAPI_HEADER, "true")
                            .post(
                                FormBody.Builder()
                                    .add("path", "/lespas/${album.folderName}")
                                    .add("shareWith", recipient.sharee.name)
                                    .add("shareType", recipient.sharee.type.toString())
                                    .add("permissions", recipient.permission.toString())
                                    .build()
                            )
                            .build()
                        ).execute()
                    }
                    catch (e: java.io.IOException) { e.printStackTrace() }
                    catch (e: IllegalStateException) { e.printStackTrace() }
                    finally { response?.close() }
                }
            }
        }
    }

    private fun deleteShares(recipients: List<Recipient>) {
        var response: Response? = null

        httpClient?.let { httpClient->
            for (recipient in recipients) {
                try {
                    response = httpClient.newCall(Request.Builder().url("$baseUrl$PUBLISH_ENDPOINT/${recipient.shareId}").delete().addHeader(NEXTCLOUD_OCSAPI_HEADER, "true").build()).execute()
                }
                catch (e: java.io.IOException) { e.printStackTrace() }
                catch (e: IllegalStateException) { e.printStackTrace() }
                finally { response?.close() }
            }
        }
    }

    private fun getSharePath(shareId: String): String? {
        var path: String? = null

        try {
            ocsGet("$baseUrl$PUBLISH_ENDPOINT/${shareId}?format=json")?.apply {
                path = getJSONArray("data").getJSONObject(0).getString("path")
            }
        }
        catch (e: java.io.IOException) { e.printStackTrace() }
        catch (e: IllegalStateException) { e.printStackTrace() }
        catch (e: JSONException) { e.printStackTrace() }

        return path
    }

    fun publish(albums: List<ShareByMe>) {
        viewModelScope.launch(Dispatchers.IO) {
            createShares(albums)
            _shareByMe.value = updateShareByMe()
        }
    }

    fun unPublish(recipients: List<Recipient>) {
        viewModelScope.launch(Dispatchers.IO) {
            deleteShares(recipients)
            _shareByMe.value = updateShareByMe()
        }
    }

    fun updatePublish(album: ShareByMe, removeRecipients: List<Recipient>) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                if (album.with.isNotEmpty()) {
                    if (!isShared(album.fileId)) {
                        // If sharing this album for the 1st time, create content.json on server
                        var content = "{\"lespas\":{\"photos\":["
                        photoRepository.getPhotoMetaInAlbum(album.fileId).forEach {
                            content += String.format(PHOTO_META_JSON, it.id, it.name, it.dateTaken.toEpochSecond(OffsetDateTime.now().offset), it.mimeType, it.width, it.height)
                        }

                        content = content.dropLast(1) + "]}}"
                        httpClient?.let { httpClient->
                            httpClient.newCall(Request.Builder()
                                .url("${resourceRoot}${lespasBase}/${Uri.encode(album.folderName)}/${album.fileId}-content.json")
                                .put(content.toRequestBody("application/json".toMediaTypeOrNull()))
                                .build()
                            ).execute().use {}
                        }
                    }

                    createShares(listOf(album))
                }
                if (removeRecipients.isNotEmpty()) deleteShares(removeRecipients)
                if (album.with.isNotEmpty() || removeRecipients.isNotEmpty()) _shareByMe.value = updateShareByMe()
            }
            catch (e: Exception) { e.printStackTrace() }
        }
    }

    fun isShared(albumId: String): Boolean = _shareByMe.value.indexOfFirst { it.fileId == albumId } != -1

    @Suppress("BlockingMethodInNonBlockingContext")
    suspend fun getRemotePhotoList(share: ShareWithMe): List<RemotePhoto> {
        val result = mutableListOf<RemotePhoto>()

        withContext(Dispatchers.IO) {
            try {
                cachedHttpClient?.apply {
                    newCall(Request.Builder().url("${resourceRoot}${share.sharePath}/${share.albumId}-content.json").build()).execute().use {
                        val photos = JSONObject(it.body?.string() ?: "").getJSONObject("lespas").getJSONArray("photos")
                        for (i in 0 until photos.length()) {
                            photos.getJSONObject(i).apply {
                                result.add(RemotePhoto(getString("id"), "${share.sharePath}/${getString("name")}", getString("mime"), getInt("width"), getInt("height"), 0, getLong("stime")))
                            }
                        }
                    }
                }

                when (share.sortOrder) {
                    Album.BY_NAME_ASC -> result.sortWith { o1, o2 -> o1.path.compareTo(o2.path) }
                    Album.BY_NAME_DESC -> result.sortWith { o1, o2 -> o2.path.compareTo(o1.path) }
                    Album.BY_DATE_TAKEN_ASC -> result.sortWith { o1, o2 -> (o1.timestamp - o2.timestamp).toInt() }
                    Album.BY_DATE_TAKEN_DESC -> result.sortWith { o1, o2 -> (o2.timestamp - o1.timestamp).toInt() }
                }
            } catch (e: SardineException) { e.printStackTrace() } catch (e: Exception) { e.printStackTrace() }
        }

        return result
    }

    fun getPhoto(photo: RemotePhoto, view: ImageView, type: String) { getPhoto(photo, view, type, null) }
    @SuppressLint("NewApi")
    @Suppress("BlockingMethodInNonBlockingContext")
    fun getPhoto(photo: RemotePhoto, view: ImageView, type: String, callBack: LoadCompleteListener?) {
        val jobKey = System.identityHashCode(view)

        //view.imageAlpha = 0
        var bitmap: Bitmap? = null
        var animatedDrawable: Drawable? = null
        val job = viewModelScope.launch(downloadDispatcher) {
            try {
                val key = "${photo.fileId}$type"
                imageCache.get(key)?.let { bitmap = it } ?: run {
                    // Get preview for TYPE_GRID. To speed up the process, should run Preview Generator app on Nextcloud server to pre-generate 1024x1024 size of preview files, if not, the 1st time of viewing this shared image would be slow
                    try {
                        if (type == ImageLoaderViewModel.TYPE_GRID) {
                            cachedHttpClient?.apply {
                                newCall(Request.Builder().url("${baseUrl}/index.php/core/preview?x=1024&y=1024&a=true&fileId=${photo.fileId}").get().build()).execute().body?.use {
                                    bitmap = BitmapFactory.decodeStream(it.byteStream())
                                }
                            }
                        }
                    } catch(e: Exception) {
                        // Catch all exception, give TYPE_GRID another chance below
                        e.printStackTrace()
                        bitmap = null
                    }

                    // If preview download fail (like no preview for video etc), or other types than TYPE_GRID, then we need to download the media file itself
                    bitmap ?: run {
                        // Show cached low resolution bitmap first
                        imageCache.get("${photo.fileId}${ImageLoaderViewModel.TYPE_GRID}")?.let {
                            withContext(Dispatchers.Main) { view.setImageBitmap(it) }
                            callBack?.onLoadComplete()
                        }

                        val option = BitmapFactory.Options().apply {
                            // TODO the following setting make picture larger, care to find a new way?
                            //inPreferredConfig = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) Bitmap.Config.RGBA_F16 else Bitmap.Config.ARGB_8888
                        }
                        cachedHttpClient?.apply {
                            newCall(Request.Builder().url("$resourceRoot${photo.path}").get().build()).execute().body?.use {
                                when (type) {
                                    ImageLoaderViewModel.TYPE_COVER -> {
                                        val bottom = min(photo.coverBaseLine + (photo.width.toFloat() * 9 / 21).toInt(), photo.height)
                                        val rect = Rect(0, photo.coverBaseLine, photo.width, bottom)
                                        val sampleSize = when (photo.width) {
                                            in (0..2000) -> 1
                                            in (2000..3000) -> 2
                                            else -> 4
                                        }
                                        bitmap = BitmapRegionDecoder.newInstance(it.byteStream(), false).decodeRegion(rect, option.apply { inSampleSize = sampleSize })
                                    }
                                    ImageLoaderViewModel.TYPE_GRID -> {
                                        if (photo.mimeType.startsWith("video")) {
                                            val videoFolder = File(localRootFolder, "videos").apply { mkdir() }
                                            val fileName = photo.path.substringAfterLast('/')
                                            it.byteStream().use { input ->
                                                File(videoFolder, fileName).outputStream().use { output ->
                                                    input.copyTo(output, 8192)
                                                }
                                            }
                                            MediaMetadataRetriever().apply {
                                                setDataSource("$videoFolder/$fileName")
                                                bitmap = getFrameAtTime(3) ?: videoThumbnail
                                                release()
                                            }
                                        } else bitmap = BitmapFactory.decodeStream(it.byteStream(), null, option.apply { inSampleSize = if (photo.width < 2000) 2 else 8 })
                                    }
                                    ImageLoaderViewModel.TYPE_FULL -> {
                                        // only image files would be requested as TYPE_FULL
                                        if (photo.mimeType == "image/awebp" || photo.mimeType == "image/agif") {
                                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                                                animatedDrawable = ImageDecoder.decodeDrawable(ImageDecoder.createSource(ByteBuffer.wrap(it.bytes())))
                                            } else {
                                                bitmap = BitmapFactory.decodeStream(it.byteStream(), null, option.apply { inSampleSize = if (photo.width < 2000) 2 else 8 })
                                            }
                                        } else bitmap = BitmapFactory.decodeStream(it.byteStream(), null, option)
                                    }
                                }
                            }
                        }

                        // If decoded bitmap is too large
                        bitmap?.let {
                            if (it.allocationByteCount > 100000000) {
                                bitmap = null
                                cachedHttpClient?.apply {
                                    newCall(Request.Builder().url("$resourceRoot${photo.path}").get().cacheControl(CacheControl.FORCE_CACHE).build()).execute().body?.use {
                                        bitmap = BitmapFactory.decodeStream(it.byteStream(), null, option.apply { inSampleSize = 2 })
                                    }
                                }
                            }
                        }
                    }
                    if (bitmap != null && type != ImageLoaderViewModel.TYPE_FULL) imageCache.put(key, bitmap)
                }
            }
            catch (e: Exception) { e.printStackTrace() }
            finally {
                if (isActive) withContext(Dispatchers.Main) {
                    animatedDrawable?.let { view.setImageDrawable(it.apply { (this as AnimatedImageDrawable).start() })} ?: run { view.setImageBitmap(bitmap ?: placeholderBitmap) }
                    //view.imageAlpha = 255
                }
                callBack?.onLoadComplete()
            }
        }

        // Replacing previous job
        replacePrevious(jobKey, job)
    }

    fun cancelGetPhoto(view: View) {
        decoderJobMap[System.identityHashCode(view)]?.cancel()
    }

    private fun replacePrevious(key: Int, newJob: Job) {
        decoderJobMap[key]?.cancel()
        decoderJobMap[key] = newJob
    }

    override fun onCleared() {
        File(localRootFolder, "videos").deleteRecursively()
        decoderJobMap.forEach { if (it.value.isActive) it.value.cancel() }
        downloadDispatcher.close()
        super.onCleared()
    }

    class ImageCache (maxSize: Int): LruCache<String, Bitmap>(maxSize) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }


    @Parcelize
    data class Sharee (
        var name: String,
        var label: String,
        var type: Int,
    ): Parcelable

    @Parcelize
    data class Recipient (
        var shareId: String,
        var permission: Int,
        var sharedTime: Long,
        var sharee: Sharee,
    ): Parcelable

    @Parcelize
    data class ShareByMe (
        var fileId: String,
        var folderName: String,
        var with: MutableList<Recipient>,
    ): Parcelable

    @Parcelize
    data class ShareWithMe (
        var shareId: String,
        var sharePath: String,
        var albumId: String,
        var albumName: String,
        var shareBy: String,
        var shareByLabel: String,
        var permission: Int,
        var sharedTime: Long,
        var cover: Cover,
        var coverFileName: String,
        var sortOrder: Int,
    ): Parcelable, Comparable<ShareWithMe> {
        override fun compareTo(other: ShareWithMe): Int = (other.sharedTime - this.sharedTime).toInt()
    }

    @Parcelize
    data class RemotePhoto (
        val fileId: String,
        val path: String,
        val mimeType: String,
        val width: Int,
        val height: Int,
        val coverBaseLine: Int,
        val timestamp: Long,
    ): Parcelable

    companion object {
        private const val NEXTCLOUD_OCSAPI_HEADER = "OCS-APIRequest"
        private const val SHARE_LISTING_ENDPOINT = "/ocs/v2.php/apps/sharelisting/api/v1/sharedSubfolders?format=json&path="
        private const val SHAREE_LISTING_ENDPOINT = "/ocs/v1.php/apps/files_sharing/api/v1/sharees?itemType=file&format=json"
        private const val CAPABILLITIES_ENDPOINT = "/ocs/v1.php/cloud/capabilities?format=json"
        private const val PUBLISH_ENDPOINT = "/ocs/v2.php/apps/files_sharing/api/v1/shares"

        const val PHOTO_META_JSON = "{\"id\":\"%s\",\"name\":\"%s\",\"stime\":%d,\"mime\":\"%s\",\"width\":%d,\"height\":%d},"

        const val SHARE_TYPE_USER = 0
        private const val SHARE_TYPE_USER_STRING = "user"
        const val SHARE_TYPE_GROUP = 1
        private const val SHARE_TYPE_GROUP_STRING = "group"

        const val PERMISSION_CAN_READ = 1
        private const val PERMISSION_CAN_UPDATE = 2
        private const val PERMISSION_CAN_CREATE = 4
        private const val PERMISSION_CAN_DELETE = 8
        private const val PERMISSION_CAN_SHARE = 16
        private const val PERMISSION_ALL = 31
    }
}