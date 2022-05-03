/*
 * Copyright 2020 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.example.camerax.camera.fragments;

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Context.WINDOW_SERVICE
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Configuration
import android.hardware.camera2.CameraCharacteristics
import android.hardware.display.DisplayManager
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.util.Size
import android.view.*
import android.webkit.MimeTypeMap
import android.widget.ImageView
import android.widget.Toast
import androidx.annotation.RequiresApi
import androidx.camera.core.*
import androidx.camera.core.ImageCapture.Metadata
import androidx.camera.extensions.ExtensionMode
import androidx.camera.extensions.ExtensionsManager
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.core.content.ContextCompat.getSystemService
import androidx.core.net.toFile
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import androidx.navigation.Navigation
import androidx.window.WindowManager
import com.bumptech.glide.load.ImageHeaderParser.UNKNOWN_ORIENTATION
import com.example.camerax.databinding.CameraUiContainerBinding
import com.example.camerax.databinding.FragmentCameraBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File
import java.nio.ByteBuffer
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

import com.example.camerax.R
import com.example.camerax.camera.KEY_EVENT_ACTION
import com.example.camerax.camera.KEY_EVENT_EXTRA
import com.example.camerax.camera.MainActivity
import com.example.camerax.camera.photoeditor.CropImage
import com.example.camerax.camera.utils.simulateClick

/** Helper type alias used for analysis use case callbacks */
typealias LumaListener = (luma: Double) -> Unit

/**
 * Main fragment for this app. Implements all camera operations including:
 * - Viewfinder
 * - Photo taking
 * - Image analysis
 */
@RequiresApi(Build.VERSION_CODES.JELLY_BEAN_MR1)
class CameraFragment : Fragment() {

    private var _fragmentCameraBinding: FragmentCameraBinding? = null
    private val fragmentCameraBinding get() = _fragmentCameraBinding!!
    private var cameraUiContainerBinding: CameraUiContainerBinding? = null
    private lateinit var outputDirectory: File
    private lateinit var broadcastManager: LocalBroadcastManager
    private var displayId: Int = -1
    private var lensFacing: Int = CameraSelector.LENS_FACING_BACK
    private var preview: Preview? = null
    private var imageCapture: ImageCapture? = null
    private var imageAnalyzer: ImageAnalysis? = null
    private var camera: Camera? = null
    private var cameraProvider: ProcessCameraProvider? = null
    private lateinit var windowManager: WindowManager

    private val displayManager by lazy {
        requireContext().getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
    }

    /** Blocking camera operations are performed using this executor */
    private lateinit var cameraExecutor: ExecutorService

    /** Volume down button receiver used to trigger shutter */
    private val volumeDownReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.getIntExtra(KEY_EVENT_EXTRA, KeyEvent.KEYCODE_UNKNOWN)) {
                // When the volume down button is pressed, simulate a shutter button click
                KeyEvent.KEYCODE_VOLUME_DOWN -> {
                    cameraUiContainerBinding?.cameraCaptureButton?.simulateClick()
                }
            }
        }
    }

    /**
     * We need a display listener for orientation changes that do not trigger a configuration
     * change, for example if we choose to override config change in manifest or for 180-degree
     * orientation changes.
     */
    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) = Unit
        override fun onDisplayRemoved(displayId: Int) = Unit
        @RequiresApi(Build.VERSION_CODES.LOLLIPOP)
        override fun onDisplayChanged(displayId: Int) = view?.let { view ->
            if (displayId == this@CameraFragment.displayId) {
                Log.d(TAG, "Rotation changed: ${view.display.rotation}")
                imageCapture?.targetRotation = view.display.rotation
                imageAnalyzer?.targetRotation = view.display.rotation
            }
        } ?: Unit
    }

    override fun onResume() {
        super.onResume()
        // Make sure that all permissions are still present, since the
        // user could have removed them while the app was in paused state.
        if (!PermissionsFragment.hasPermissions(requireContext())) {
            Navigation.findNavController(requireActivity(), R.id.fragment_container).navigate(
                    CameraFragmentDirections.actionCameraToPermissions()
            )
        }
    }

    override fun onDestroyView() {
        _fragmentCameraBinding = null
        super.onDestroyView()


        // Shut down our background executor
        cameraExecutor.shutdown()
        // Unregister the broadcast receivers and listeners
        broadcastManager.unregisterReceiver(volumeDownReceiver)
        displayManager.unregisterDisplayListener(displayListener)
    }

    override fun onCreateView(
            inflater: LayoutInflater,
            container: ViewGroup?,
            savedInstanceState: Bundle?
    ): View {
        _fragmentCameraBinding = FragmentCameraBinding.inflate(inflater, container, false)
        return fragmentCameraBinding.root
    }

    private fun setGalleryThumbnail(uri: Uri) {
        // Run the operations in the view's thread

    }


    private val orientationEventListener by lazy {
        object : OrientationEventListener(requireActivity()) {
            override fun onOrientationChanged(orientation: Int) {
                if (orientation == UNKNOWN_ORIENTATION) {
                    return
                }

                val rotation = when (orientation) {
                    in 45 until 135 -> Surface.ROTATION_270
                    in 135 until 225 -> Surface.ROTATION_180
                    in 225 until 315 -> Surface.ROTATION_90
                    else -> Surface.ROTATION_0
                }

                imageCapture?.targetRotation = rotation
                imageAnalyzer?.targetRotation = rotation

            }
        }
    }


    var flashType=FLASH_AUTO;
    var hdr= HDR_OFF



    var mLastRotation=-1;
    @SuppressLint("MissingPermission")
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Initialize our background executor
        cameraExecutor = Executors.newSingleThreadExecutor()
        val display: Display = (requireActivity().getSystemService(WINDOW_SERVICE) as android.view.WindowManager)
            .getDefaultDisplay()
        broadcastManager = LocalBroadcastManager.getInstance(view.context)

        // Set up the intent filter that will receive events from our main activity
        val filter = IntentFilter().apply { addAction(KEY_EVENT_ACTION) }
        broadcastManager.registerReceiver(volumeDownReceiver, filter)

        // Every time the orientation of device changes, update rotation for use cases
        displayManager.registerDisplayListener(displayListener, null)

        //Initialize WindowManager to retrieve display metrics
        windowManager = WindowManager(view.context)

        // Determine the output directory
        outputDirectory = MainActivity.getCacheDirectory(requireContext())

        // Wait for the views to be properly laid out
        fragmentCameraBinding.viewFinder.post {

            // Keep track of the display in which this view is attached
            displayId = fragmentCameraBinding.viewFinder.display.displayId

            // Build UI controls
            updateCameraUi()

            // Set up the camera and its use cases
            setUpCamera()
        }



    }
    var isHdrEnable=false;
    var isNightEnable=false

    /**
     * Inflate camera controls and update the UI manually upon config changes to avoid removing
     * and re-adding the view finder from the view hierarchy; this provides a seamless rotation
     * transition on devices that support it.
     *
     * NOTE: The flag is supported starting in Android 8 but there still is a small flash on the
     * screen for devices that run Android 9 or below.
     */
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)

        // Rebind the camera with the updated display metrics
        bindCameraUseCases()

        // Enable or disable switching between cameras
        updateCameraSwitchButton()
    }

    /** Initialize CameraX, and prepare to bind the camera use cases  */
    private fun setUpCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(requireContext())
        cameraProviderFuture.addListener(Runnable {
            // CameraProvider
            cameraProvider = cameraProviderFuture.get()

            // Select lensFacing depending on the available cameras
            lensFacing = when {
                hasBackCamera() -> CameraSelector.LENS_FACING_BACK
                hasFrontCamera() -> CameraSelector.LENS_FACING_FRONT
                else -> throw IllegalStateException("Back and front camera are unavailable")
            }
            // Enable or disable switching between cameras
            updateCameraSwitchButton()
            // Build and bind the camera use cases
            bindCameraUseCases()
        }, ContextCompat.getMainExecutor(requireContext()))
    }

    /*   Declare and bind flash image, update image according to the flash mode */
    private fun setImageFlashType(){
        when(flashType){
            FLASH_AUTO->{
                cameraUiContainerBinding?.imgFlash!!.setImageResource(R.drawable.flash_auto)
            }
            FLASH_OFF->{
                cameraUiContainerBinding?.imgFlash!!.setImageResource(R.drawable.flash_off)
            }
            FLASH_ON->{
                cameraUiContainerBinding?.imgFlash!!.setImageResource(R.drawable.flash_on)
            }
        }
    }
    /** Declare and bind preview, capture and analysis use cases */
    @RequiresApi(Build.VERSION_CODES.LOLLIPOP)
    private fun bindCameraUseCases() {
        // Get screen metrics used to setup camera for full screen resolution
        val metrics = windowManager.getCurrentWindowMetrics().bounds
        Log.d(TAG, "Screen metrics: ${metrics.width()} x ${metrics.height()}")
        val screenAspectRatio = Size(metrics.width(), metrics.height())
        Log.d(TAG, "Preview aspect ratio: $screenAspectRatio")
        val rotation = fragmentCameraBinding.viewFinder.display.rotation
        // CameraProvider
        val cameraProvider = cameraProvider
                ?: throw IllegalStateException("Camera initialization failed.")
        // CameraSelector
        val cameraSelector = CameraSelector.Builder().requireLensFacing(lensFacing).build()
        // Preview
        preview = Preview.Builder()
                // We request aspect ratio but no resolution
                .setTargetResolution(screenAspectRatio)
                // Set initial target rotation
               // .setTargetRotation(rotation)
                .build()
        var bokehCameraSelector:CameraSelector?=null;
        val extensionsManager = ExtensionsManager.getInstanceAsync(requireActivity(), cameraProvider).get()
        if (extensionsManager.isExtensionAvailable(
                cameraSelector,
                ExtensionMode.HDR
            )){
            isHdrEnable=true
        }
        if (extensionsManager.isExtensionAvailable(
                cameraSelector,
                ExtensionMode.NIGHT
            )){
            isNightEnable=true
        }
        // ImageCapture
        val builder =ImageCapture.Builder()
        if(hdr ==  HDR_ON &&isHdrEnable){
            val extensionsManager =
                ExtensionsManager.getInstanceAsync(requireActivity(), cameraProvider).get()
            if (extensionsManager.isExtensionAvailable(
                    cameraSelector,
                    ExtensionMode.HDR
                )) {
                // Retrieve extension enabled camera selector
                 bokehCameraSelector = extensionsManager.getExtensionEnabledCameraSelector(
                    cameraSelector,
                    ExtensionMode.HDR
                )
                // Bind image capture and preview use cases with the extension enabled camera selector.
            }else{
                Toast.makeText(requireActivity(),"Hdr not supported on this device",Toast.LENGTH_LONG).show()
            }
        }else if(hdr ==  NIGHT_MODE && isNightEnable){
            val extensionsManager =
                ExtensionsManager.getInstanceAsync(requireActivity(), cameraProvider).get()
            if (extensionsManager.isExtensionAvailable(
                    cameraSelector,
                    ExtensionMode.NIGHT
                )) {
                // Retrieve extension enabled camera selector
                bokehCameraSelector = extensionsManager.getExtensionEnabledCameraSelector(
                    cameraSelector,
                    ExtensionMode.NIGHT
                )

                // Bind image capture and preview use cases with the extension enabled camera selector.
            }else{
                Toast.makeText(requireActivity(),"Night not supported on this device",Toast.LENGTH_LONG).show()
            }
        }
        imageCapture =builder.setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                .setFlashMode(flashType)  //  Flash Mode ON
                // We request aspect ratio but no resolution to match preview config, but letting
                // CameraX optimize for whatever specific resolution best fits our use cases
                .setTargetResolution(screenAspectRatio)
                // Set initial target rotation, we will have to call this again if rotation changes
                // during the lifecycle of this use case
                .setTargetRotation(rotation)
                .build()
        // ImageAnalysis
        imageAnalyzer = ImageAnalysis.Builder()
                // We request aspect ratio but no resolution
                .setTargetResolution(screenAspectRatio)
                // Set initial target rotation, we will have to call this again if rotation changes
                // during the lifecycle of this use case
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setTargetRotation(rotation)
                .build()
                // The analyzer can then be assigned to the instance
                .also {
                    it.setAnalyzer(cameraExecutor, LuminosityAnalyzer { luma ->
                        // Values returned from our analyzer are passed to the attached listener
                        // We log image analysis results here - you should do something useful
                        // instead!
                        Log.d(TAG, "Average luminosity: $luma")
                    })
                }

        // Must unbind the use-cases before rebinding them
        cameraProvider.unbindAll()
        try {
            // A variable number of use-cases can be passed here -
            // camera provides access to CameraControl & CameraInfo
            camera = cameraProvider.bindToLifecycle(
                    this,if (bokehCameraSelector ==null) cameraSelector else bokehCameraSelector, preview, imageCapture, imageAnalyzer).also {
                Log.e("SUCCESSAPPLY","SUCCESSAPPLY")
                it.cameraControl.setLinearZoom(0F)
            }

            // Attach the viewfinder's surface provider to preview use case
            preview?.setSurfaceProvider(fragmentCameraBinding.viewFinder.surfaceProvider)
            observeCameraState(camera?.cameraInfo!!)
        } catch (exc: Exception) {
            Log.e(TAG, "Use case binding failed", exc)
        }
    }



    @RequiresApi(Build.VERSION_CODES.LOLLIPOP)
    @SuppressLint("ClickableViewAccessibility")
    private fun observeCameraState(cameraInfo: CameraInfo) {
        ///  Auto Focus
        val autoFocusPoint = SurfaceOrientedMeteringPointFactory(1f, 1f)
            .createPoint(.5f, .5f)
        try {
            val autoFocusAction = FocusMeteringAction.Builder(
                autoFocusPoint,
                FocusMeteringAction.FLAG_AF
            ).apply {
                //start auto-focusing after 2 seconds
                setAutoCancelDuration(2, TimeUnit.SECONDS)
            }.build()
            camera!!.cameraControl.startFocusAndMetering(autoFocusAction)
        } catch (e: CameraInfoUnavailableException) {
            Log.d("ERROR", "cannot access camera", e)
        }
        //   Pinch to zoom
            val scaleGestureDetector = ScaleGestureDetector(context, listener)
        _fragmentCameraBinding!!.viewFinder.setOnTouchListener { _, event ->
            scaleGestureDetector.onTouchEvent(event)
            when (event.action) {
                MotionEvent.ACTION_UP -> {
                    // Get the MeteringPointFactory from PreviewView
                    val factory = _fragmentCameraBinding!!.viewFinder.getMeteringPointFactory()
                    // Create a MeteringPoint from the tap coordinates
                    val point = factory.createPoint(event.x, event.y)
                    // Create a MeteringAction from the MeteringPoint, you can configure it to specify the metering mode
                    val action = FocusMeteringAction.Builder(point).build()
                    // Trigger the focus and metering. The method returns a ListenableFuture since the operation
                    // is asynchronous. You can use it get notified when the focus is successful or if it fails.
                    camera!!.getCameraControl()!!.startFocusAndMetering(action)
                    return@setOnTouchListener true
                }
            }
            return@setOnTouchListener true
        }




        cameraInfo.cameraState.observe(viewLifecycleOwner) { cameraState ->
            run {
                when (cameraState.type) {
                    CameraState.Type.PENDING_OPEN -> {
                        // Ask the user to close other camera apps
                    }
                    CameraState.Type.OPENING -> {
                        // Show the Camera UI
                    }
                    CameraState.Type.OPEN -> {
                        camera!!.getCameraControl().enableTorch(flashType ==  FLASH_ON); // Enabe forcfully torch on
                        // Setup Camera resources and begin processing
                    }
                    CameraState.Type.CLOSING -> {
                        // Close camera UI
                    }
                    CameraState.Type.CLOSED -> {
                        // Free camera resources
                    }
                }
            }
            cameraState.error?.let { error ->
                when (error.code) {
                    // Open errors
                    CameraState.ERROR_STREAM_CONFIG -> {
                        // Make sure to setup the use cases properly
                        Toast.makeText(context,
                                "Stream config error",
                                Toast.LENGTH_SHORT).show()
                    }
                    // Opening errors
                    CameraState.ERROR_CAMERA_IN_USE -> {
                        // Close the camera or ask user to close another camera app that's using the
                        // camera
                        Toast.makeText(context,
                                "Camera in use",
                                Toast.LENGTH_SHORT).show()
                    }
                    CameraState.ERROR_MAX_CAMERAS_IN_USE -> {
                        // Close another open camera in the app, or ask the user to close another
                        // camera app that's using the camera
                        Toast.makeText(context,
                                "Max cameras in use",
                                Toast.LENGTH_SHORT).show()
                    }
                    CameraState.ERROR_OTHER_RECOVERABLE_ERROR -> {
                        Toast.makeText(context,
                                "Other recoverable error",
                                Toast.LENGTH_SHORT).show()
                    }
                    // Closing errors
                    CameraState.ERROR_CAMERA_DISABLED -> {
                        // Ask the user to enable the device's cameras
                        Toast.makeText(context,
                                "Camera disabled",
                                Toast.LENGTH_SHORT).show()
                    }
                    CameraState.ERROR_CAMERA_FATAL_ERROR -> {
                        // Ask the user to reboot the device to restore camera function
                        Toast.makeText(context,
                                "Fatal error",
                                Toast.LENGTH_SHORT).show()
                    }
                    // Closed errors
                    CameraState.ERROR_DO_NOT_DISTURB_MODE_ENABLED -> {
                        // Ask the user to disable the "Do Not Disturb" mode, then reopen the camera
                        Toast.makeText(context,
                                "Do not disturb mode enabled",
                                Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }

    val listener = object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        @RequiresApi(Build.VERSION_CODES.LOLLIPOP)
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            val currentZoomRatio = camera!!.cameraInfo.zoomState.value?.zoomRatio ?: 0F
            // Get the pinch gesture's scaling factor
            val delta = detector.scaleFactor
            // Update the camera's zoom ratio. This is an asynchronous operation that returns
            // a ListenableFuture, allowing you to listen to when the operation completes.
            camera!!.cameraControl.setZoomRatio(currentZoomRatio * delta)
            return true
        }
    }

    /**
     *  [androidx.camera.core.ImageAnalysis.Builder] requires enum value of
     *  [androidx.camera.core.AspectRatio]. Currently it has values of 4:3 & 16:9.
     *
     *  Detecting the most suitable ratio for dimensions provided in @params by counting absolute
     *  of preview ratio to one of the provided values.
     *
     *  @param width - preview width
     *  @param height - preview height
     *  @return suitable aspect ratio
     */
    private fun aspectRatio(width: Int, height: Int): Int {
        val previewRatio = max(width, height).toDouble() / min(width, height)
        if (abs(previewRatio - RATIO_4_3_VALUE) <= abs(previewRatio - RATIO_16_9_VALUE)) {
            return AspectRatio.RATIO_4_3
        }
        return AspectRatio.RATIO_4_3
    }

    /** Method used to re-draw the camera UI controls, called every time configuration changes. */
    @RequiresApi(Build.VERSION_CODES.LOLLIPOP)
    private fun updateCameraUi() {

        // Remove previous UI if any
        cameraUiContainerBinding?.root?.let {
            fragmentCameraBinding.root.removeView(it)
        }

        cameraUiContainerBinding = CameraUiContainerBinding.inflate(
                LayoutInflater.from(requireContext()),
                fragmentCameraBinding.root,
                true
        )

        // In the background, load latest photo taken (if any) for gallery thumbnail
        lifecycleScope.launch(Dispatchers.IO) {
            outputDirectory.listFiles { file ->
                EXTENSION_WHITELIST.contains(file.extension.toUpperCase(Locale.ROOT))
            }?.maxOrNull()?.let {
                setGalleryThumbnail(Uri.fromFile(it))
            }
        }
        cameraUiContainerBinding?.imgHdr?.setOnClickListener {
            when(hdr){
                HDR_OFF->{
                    if(isHdrEnable){
                        hdr= HDR_ON
                        (it as ImageView).setImageResource(R.drawable.hdr_on)
                        bindCameraUseCases()
                    }
                  }
                HDR_ON->{
                    if(isNightEnable){
                        hdr= NIGHT_MODE
                        (it as ImageView).setImageResource(R.drawable.night_mode)
                        bindCameraUseCases()
                    }
                }
                NIGHT_MODE->{
                    hdr=  HDR_OFF
                    (it as ImageView).setImageResource(R.drawable.hdr_off)
                    bindCameraUseCases()
                }
            }
        }
        cameraUiContainerBinding?.imgFlash?.setOnClickListener {
            when(flashType){
                FLASH_ON->{
                    camera!!.getCameraControl().enableTorch(false); // or false
                    flashType= FLASH_OFF
                }
                FLASH_OFF->{
                    flashType= FLASH_AUTO
                }
                FLASH_AUTO->{
                    if ( camera!!.getCameraInfo().hasFlashUnit() ) {
                        camera!!.getCameraControl().enableTorch(true); // or false
                    }
                    flashType= FLASH_ON
                }
                }
            setImageFlashType()
            camera!!.getCameraControl().enableTorch(flashType ==  FLASH_ON); // Enable forcefully torch on
            imageCapture?.flashMode=flashType
        }
        // Listener for button used to capture photo
        cameraUiContainerBinding?.cameraCaptureButton?.setOnClickListener {
            // Get a stable reference of the modifiable image capture use case
            imageCapture?.let { imageCapture ->
                // Create output file to hold the image
                val photoFile = createFile(outputDirectory, FILENAME, PHOTO_EXTENSION)
                // Setup image capture metadata
                val metadata = Metadata().apply {
                    // Mirror image when using the front camera
                    isReversedHorizontal = lensFacing == CameraSelector.LENS_FACING_FRONT
                }
                // Create output options object which contains file + metadata
                val outputOptions = ImageCapture.OutputFileOptions.Builder(photoFile)
                        .setMetadata(metadata)
                        .build()
                // Setup image capture listener which is triggered after photo has been taken
                imageCapture.takePicture(
                        outputOptions, cameraExecutor, object : ImageCapture.OnImageSavedCallback {
                    override fun onError(exc: ImageCaptureException) {
                        Log.e(TAG, "Photo capture failed: ${exc.message}", exc)
                    }
                    override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                        val savedUri = output.savedUri ?: Uri.fromFile(photoFile)
                        Log.d(TAG, "Photo capture succeeded: $savedUri")
                        CropImage.activity(output.savedUri!!)
                            .start(requireActivity());

                     /*   Navigation.findNavController(
                            requireActivity(), R.id.fragment_container
                        ).navigate(CameraFragmentDirections
                            .actionCameraToGallery(outputDirectory.absolutePath))*/
                        // If the folder selected is an external media directory, this is
                        // unnecessary but otherwise other apps will not be able to access our
                        // images unless we scan them using [MediaScannerConnection]
                        val mimeType = MimeTypeMap.getSingleton()
                                .getMimeTypeFromExtension(savedUri.toFile().extension)
                        MediaScannerConnection.scanFile(
                                context,
                                arrayOf(savedUri.toFile().absolutePath),
                                arrayOf(mimeType)
                        ) { _, uri ->
                            Log.d(TAG, "Image capture scanned into media store: $uri")
                        }
                    }
                })

                // We can only change the foreground Drawable using API level 23+ API
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {


                  /*  // Display flash animation to indicate that photo was captured
                        fragmentCameraBinding.root.postDelayed({
                          //  fragmentCameraBinding.root.foreground = ColorDrawable(Color.WHITE)
                            fragmentCameraBinding.root.postDelayed(
                                    { fragmentCameraBinding.root.foreground = null }, ANIMATION_FAST_MILLIS)
                        }, ANIMATION_SLOW_MILLIS)*/
                }
            }
        }

        // Setup for button used to switch cameras
        cameraUiContainerBinding?.cameraSwitchButton?.let {

            // Disable the button until the camera is set up
            it.isEnabled = false

            // Listener for button used to switch cameras. Only called if the button is enabled
            it.setOnClickListener {
                lensFacing = if (CameraSelector.LENS_FACING_FRONT == lensFacing) {
                    CameraSelector.LENS_FACING_BACK
                } else {
                    CameraSelector.LENS_FACING_FRONT
                }
                // Re-bind use cases to update selected camera
                bindCameraUseCases()
            }
        }


    }

    /** Enabled or disabled a button to switch cameras depending on the available cameras */
    private fun updateCameraSwitchButton() {
        try {
            cameraUiContainerBinding?.cameraSwitchButton?.isEnabled = hasBackCamera() && hasFrontCamera()
        } catch (exception: CameraInfoUnavailableException) {
            cameraUiContainerBinding?.cameraSwitchButton?.isEnabled = false
        }
    }

    /** Returns true if the device has an available back camera. False otherwise */
    private fun hasBackCamera(): Boolean {
        return cameraProvider?.hasCamera(CameraSelector.DEFAULT_BACK_CAMERA) ?: false
    }

    /** Returns true if the device has an available front camera. False otherwise */
    private fun hasFrontCamera(): Boolean {
        return cameraProvider?.hasCamera(CameraSelector.DEFAULT_FRONT_CAMERA) ?: false
    }


    override fun onStart() {
        super.onStart()
        orientationEventListener.enable()
    }

    override fun onStop() {
        super.onStop()
        orientationEventListener.disable()
    }
    /**
     * Our custom image analysis class.
     *
     * <p>All we need to do is override the function `analyze` with our desired operations. Here,
     * we compute the average luminosity of the image by looking at the Y plane of the YUV frame.
     */
    private class LuminosityAnalyzer(listener: LumaListener? = null) : ImageAnalysis.Analyzer {
        private val frameRateWindow = 8
        private val frameTimestamps = ArrayDeque<Long>(5)
        private val listeners = ArrayList<LumaListener>().apply { listener?.let { add(it) } }
        private var lastAnalyzedTimestamp = 0L
        var framesPerSecond: Double = -1.0
            private set

        /**
         * Used to add listeners that will be called with each luma computed
         */
        fun onFrameAnalyzed(listener: LumaListener) = listeners.add(listener)

        /**
         * Helper extension function used to extract a byte array from an image plane buffer
         */
        private fun ByteBuffer.toByteArray(): ByteArray {
            rewind()    // Rewind the buffer to zero
            val data = ByteArray(remaining())
            get(data)   // Copy the buffer into a byte array
            return data // Return the byte array
        }

        /**
         * Analyzes an image to produce a result.
         *
         * <p>The caller is responsible for ensuring this analysis method can be executed quickly
         * enough to prevent stalls in the image acquisition pipeline. Otherwise, newly available
         * images will not be acquired and analyzed.
         *
         * <p>The image passed to this method becomes invalid after this method returns. The caller
         * should not store external references to this image, as these references will become
         * invalid.
         *
         * @param image image being analyzed VERY IMPORTANT: Analyzer method implementation must
         * call image.close() on received images when finished using them. Otherwise, new images
         * may not be received or the camera may stall, depending on back pressure setting.
         *
         */
        override fun analyze(image: ImageProxy) {
            // If there are no listeners attached, we don't need to perform analysis
            if (listeners.isEmpty()) {
                image.close()
                return
            }

            // Keep track of frames analyzed
            val currentTime = System.currentTimeMillis()
            frameTimestamps.push(currentTime)

            // Compute the FPS using a moving average
            while (frameTimestamps.size >= frameRateWindow) frameTimestamps.removeLast()
            val timestampFirst = frameTimestamps.peekFirst() ?: currentTime
            val timestampLast = frameTimestamps.peekLast() ?: currentTime
            framesPerSecond = 1.0 / ((timestampFirst - timestampLast) /
                    frameTimestamps.size.coerceAtLeast(1).toDouble()) * 1000.0

            // Analysis could take an arbitrarily long amount of time
            // Since we are running in a different thread, it won't stall other use cases

            lastAnalyzedTimestamp = frameTimestamps.first

            // Since format in ImageAnalysis is YUV, image.planes[0] contains the luminance plane
            val buffer = image.planes[0].buffer

            // Extract image data from callback object
            val data = buffer.toByteArray()

            // Convert the data into an array of pixel values ranging 0-255
            val pixels = data.map { it.toInt() and 0xFF }

            // Compute average luminance for the image
            val luma = pixels.average()

            // Call all listeners with new value
            listeners.forEach { it(luma) }

            image.close()
        }
    }

    companion object {
        private const val FLASH_ON=ImageCapture.FLASH_MODE_ON;
        private const val FLASH_OFF=ImageCapture.FLASH_MODE_OFF;
        private const val FLASH_AUTO=ImageCapture.FLASH_MODE_AUTO;

        private const val HDR_ON    =  1;
        private const val HDR_OFF   =  2;
        private const val NIGHT_MODE = 3;

        private const val TAG = "CameraXBasic"
        private const val FILENAME = "yyyy-MM-dd-HH-mm-ss-SSS"
        private const val PHOTO_EXTENSION = ".jpg"
        private const val RATIO_4_3_VALUE = 4.0 / 3.0
        private const val RATIO_16_9_VALUE = 16.0 / 9.0

        /** Helper function used to create a timestamped file */
        private fun createFile(baseFolder: File, format: String, extension: String) =
                File(baseFolder, SimpleDateFormat(format, Locale.US)
                        .format(System.currentTimeMillis()) + extension)
    }
}
