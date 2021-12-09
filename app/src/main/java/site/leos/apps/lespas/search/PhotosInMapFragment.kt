package site.leos.apps.lespas.search

import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.graphics.drawable.BitmapDrawable
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.ActionBar
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.doOnLayout
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.osmdroid.api.IGeoPoint
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.CopyrightOverlay
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.TilesOverlay
import org.osmdroid.views.overlay.infowindow.InfoWindow
import org.osmdroid.views.overlay.simplefastpoint.SimpleFastPointOverlay
import org.osmdroid.views.overlay.simplefastpoint.SimpleFastPointOverlayOptions
import org.osmdroid.views.overlay.simplefastpoint.SimplePointTheme
import site.leos.apps.lespas.BuildConfig
import site.leos.apps.lespas.R
import site.leos.apps.lespas.helper.ImageLoaderViewModel
import site.leos.apps.lespas.helper.Tools
import site.leos.apps.lespas.photo.Photo
import site.leos.apps.lespas.photo.PhotoWithCoordinate
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.*
import kotlin.collections.ArrayList

class PhotosInMapFragment: Fragment() {
    private var locality: String? = null
    private var country: String? = null
    private var albumNames: HashMap<String, String>? = null
    private var albumName: String? = null
    private var photos = mutableListOf<PhotoWithCoordinate>()

    private lateinit var rootPath: String

    private lateinit var mapView: MapView
    private lateinit var poisBoundingBox: BoundingBox
    private val markerClickListener = MarkerClickListener()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        requireArguments().apply {
            locality = getString(KEY_LOCALITY)
            country = getString(KEY_COUNTRY)
            getSerializable(KEY_ALBUM_NAMES)?.let { albumNames = it as HashMap<String, String> }
            albumName = getString(KEY_ALBUM)
            getParcelableArrayList<PhotoWithCoordinate>(KEY_PHOTOS)?.let { photos.addAll(it) }
        }

        rootPath = Tools.getLocalRoot(requireContext())
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? = inflater.inflate(R.layout.fragment_photos_in_map, container, false)
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        mapView = view.findViewById(R.id.map)
        org.osmdroid.config.Configuration.getInstance().userAgentValue = BuildConfig.APPLICATION_ID

        mapView.apply {
            if (this.context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES) overlayManager.tilesOverlay.setColorFilter(TilesOverlay.INVERT_COLORS)
            setMultiTouchControls(true)
            setUseDataConnection(true)
            setTileSource(TileSourceFactory.MAPNIK)
            overlays.add(CopyrightOverlay(requireContext()))

            val pin = ContextCompat.getDrawable(this.context, R.drawable.ic_baseline_location_marker_24)
            var poi: GeoPoint
            val points = arrayListOf<IGeoPoint>()


            if (locality != null) ViewModelProvider(requireParentFragment(), LocationSearchHostFragment.LocationSearchViewModelFactory(requireActivity().application, true))[LocationSearchHostFragment.LocationSearchViewModel::class.java].getResult().value?.find { it.locality == locality && it.country == country }?.photos?.forEach { photo->
                poi = GeoPoint(photo.lat, photo.long)
                val marker = Marker(this).apply {
                    position = poi
                    icon = pin
                    loadImage(this, photo.photo)
                }
                marker.infoWindow = object : InfoWindow(R.layout.map_info_window, mapView) {
                    override fun onOpen(item: Any?) {
                        mView.apply {
                            findViewById<ImageView>(R.id.photo).setImageDrawable(marker.image)
                            findViewById<TextView>(R.id.label).text =
                                if (photo.photo.albumId == ImageLoaderViewModel.FROM_CAMERA_ROLL) photo.photo.dateTaken.run { this.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.SHORT)) }
                                else albumNames?.get(photo.photo.albumId)
                            setOnClickListener { close() }
                        }
                    }

                    override fun onClose() {}
                }
                marker.setOnMarkerClickListener(markerClickListener)
                overlays.add(marker)

                points.add(poi)
            } else photos.forEach { photo->
                if (photo.lat != 0.0 && photo.long != 0.0) {
                    poi = GeoPoint(photo.lat, photo.long)
                    val marker = Marker(this).apply {
                        position = poi
                        icon = pin
                        loadImage(this, photo.photo)
                    }
                    marker.infoWindow = object : InfoWindow(R.layout.map_info_window, mapView) {
                        override fun onOpen(item: Any?) {
                            mView.apply {
                                findViewById<ImageView>(R.id.photo).setImageDrawable(marker.image)
                                findViewById<TextView>(R.id.label).isVisible = false
                                setOnClickListener { close() }
                            }
                        }

                        override fun onClose() {}
                    }
                    marker.setOnMarkerClickListener(markerClickListener)
                    overlays.add(marker)

                    points.add(poi)
                }
            }

            // Get bounding box for all pois for zoom level setting and show them
            poisBoundingBox = SimpleFastPointOverlay(SimplePointTheme(points, false), SimpleFastPointOverlayOptions.getDefaultStyle()).boundingBox
            invalidate()
        }

        // Set zoom after mapview been layout
        mapView.doOnLayout {
            (it as MapView).apply {
                controller.setCenter(boundingBox.centerWithDateLine)
                zoomToBoundingBox(poisBoundingBox, savedInstanceState == null, 100, 19.5, 800)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        (requireActivity() as AppCompatActivity).supportActionBar?.run {
            title = locality ?: albumName
            displayOptions = ActionBar.DISPLAY_HOME_AS_UP or ActionBar.DISPLAY_SHOW_TITLE
        }
        mapView.onResume()
    }

    override fun onPause() {
        mapView.onPause()
        super.onPause()
    }

    private fun loadImage(marker: Marker, photo: Photo) {
        lifecycleScope.launch(Dispatchers.IO) {
            val option = BitmapFactory.Options().apply { inSampleSize = 8 }
            marker.image = BitmapDrawable(resources,
                if (photo.albumId == ImageLoaderViewModel.FROM_CAMERA_ROLL) {
                    var bmp = BitmapFactory.decodeStream(requireContext().contentResolver.openInputStream(Uri.parse(photo.id)), null, option)
                    if (photo.shareId != 0) bmp?.let { bmp = Bitmap.createBitmap(bmp!!, 0, 0, it.width, it.height, Matrix().apply { preRotate((photo.shareId).toFloat()) }, true) }
                    bmp
                }
                else BitmapFactory.decodeFile("$rootPath/${photo.id}", option)
            )
        }
    }

    class MarkerClickListener: Marker.OnMarkerClickListener {
        override fun onMarkerClick(marker: Marker, mapView: MapView): Boolean {
            if (marker.isInfoWindowShown) marker.closeInfoWindow()
            else {
                mapView.overlays.forEach { if (it is Marker) it.closeInfoWindow() }
                marker.showInfoWindow()
                mapView.controller.animateTo(marker.position)
            }

            return true
        }
    }

    companion object {
        private const val KEY_LOCALITY = "KEY_LOCALITY"
        private const val KEY_COUNTRY = "KEY_COUNTRY"
        private const val KEY_ALBUM_NAMES = "KEY_ALBUM_NAMES"
        private const val KEY_ALBUM = "KEY_ALBUM"
        private const val KEY_PHOTOS = "KEY_PHOTOS"

        @JvmStatic
        fun newInstance(locality: String, country: String, albumNames: HashMap<String, String>) = PhotosInMapFragment().apply {
            arguments = Bundle().apply {
                putString(KEY_LOCALITY, locality)
                putString(KEY_COUNTRY, country)
                putSerializable(KEY_ALBUM_NAMES, albumNames)
            }
        }

        @JvmStatic
        fun newInstance(albumName: String, photos: MutableList<PhotoWithCoordinate>) = PhotosInMapFragment().apply {
            arguments = Bundle().apply {
                putString(KEY_ALBUM, albumName)
                putParcelableArrayList(KEY_PHOTOS, ArrayList(photos))
            }
        }
    }
}