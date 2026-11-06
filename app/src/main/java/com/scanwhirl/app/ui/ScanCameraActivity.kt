package com.scanwhirl.app.ui

import android.Manifest
import android.app.Dialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.util.Size
import android.widget.ImageButton
import android.widget.ProgressBar
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import com.scanwhirl.app.R
import com.scanwhirl.app.ScanWhirlApp
import com.scanwhirl.app.databinding.ActivityScanCameraBinding
import com.scanwhirl.app.util.PaperScanner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Collections
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.math.abs

/**
 * The in-house scanner: live CameraX preview with a document-edge overlay, a
 * shutter button, auto-capture when the paper holds still, and a full screen
 * capture that is straightened by `PaperScanner` into **Original** (no
 * enhancement). Produces pages for the existing review screen.
 */
class ScanCameraActivity : BaseActivity() {

    private lateinit var binding: ActivityScanCameraBinding
    private val repo by lazy { (application as ScanWhirlApp).repository }

    private val pages = Collections.synchronizedList(mutableListOf<ByteArray>())
    private var appendMode = false

    private lateinit var imageCapture: ImageCapture
    private var camera: Camera? = null
    private var torchOn = false

    private var lastQuad: FloatArray? = null
    private var steadyFrames = 0

    /** Guard so a slow capture can't queue a second one on top of itself. */
    @Volatile
    private var capturing = false

    /** Frame throttle: skip analysis frames while one is still running. */
    @Volatile
    private var analyzing = false

    private val executor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "doc-scanner").apply { isDaemon = true }
    }

    private val permission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) startCamera()
            else {
                Toast.makeText(this, R.string.scan_no_camera, Toast.LENGTH_LONG).show()
                finish()
            }
        }

    private val galleryPick =
        registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
            uri?.let { importFromGallery(it) }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityScanCameraBinding.inflate(layoutInflater)
        setContentView(binding.root)

        appendMode = intent.getBooleanExtra(EXTRA_APPEND, false)
        binding.btnClose.setOnClickListener { finish() }
        binding.btnTorch.setOnClickListener { toggleTorch() }
        binding.btnGallery.setOnClickListener { galleryPick.launch("image/*") }
        binding.btnShutter.setOnClickListener { capturePage() }
        binding.btnDone.setOnClickListener { finishScanner() }

        updateChrome(0)

        val granted = ContextCompat.checkSelfPermission(
            this, Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED
        if (granted) startCamera() else permission.launch(Manifest.permission.CAMERA)
    }

    // ------------------------------------------------------------------
    // CameraX wiring
    // ------------------------------------------------------------------

    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            try {
                val provider = future.get()
                val preview = Preview.Builder().build().also {
                    it.surfaceProvider = binding.previewView.surfaceProvider
                }
                imageCapture = ImageCapture.Builder()
                    .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
                    .build()
                val analysis = ImageAnalysis.Builder()
                    .setTargetResolution(Size(640, 640))
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()

                analysis.setAnalyzer(executor) { proxy -> analyzeFrame(proxy) }
                provider.unbindAll()
                camera = provider.bindToLifecycle(
                    this, CameraSelector.DEFAULT_BACK_CAMERA, preview, imageCapture, analysis
                )
            } catch (e: Exception) {
                Toast.makeText(
                    this,
                    getString(R.string.scan_camera_failed, e.message),
                    Toast.LENGTH_LONG
                ).show()
                finish()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun toggleTorch() {
        torchOn = !torchOn
        binding.btnTorch.alpha = if (torchOn) 1f else 0.5f
        camera?.cameraControl?.enableTorch(torchOn)
    }

    /** Live paper detection from a cheap analysis frame. */
    private fun analyzeFrame(proxy: ImageProxy) {
        if (analyzing) {
            proxy.close()
            return
        }
        analyzing = true
        try {
            val bitmap = proxy.toBitmap()
            proxy.close()
            bitmap ?: return

            val w = bitmap.width
            val h = bitmap.height
            val pixels = IntArray(w * h)
            bitmap.getPixels(pixels, 0, w, 0, 0, w, h)
            bitmap.recycle()

            val quad = PaperScanner.detectQuadPixels(pixels, w, h)
            val steady = quad != null && isSteady(lastQuad, quad)
            lastQuad = quad
            steadyFrames = if (steady) steadyFrames + 1 else 0
            val shouldShoot = quad != null && steadyFrames >= STEADY_FRAMES_NEEDED

            runOnUiThread {
                binding.edgeOverlay.setQuad(quad)
                binding.holdSteady.isVisible =
                    quad != null && steadyFrames < STEADY_FRAMES_NEEDED
                if (shouldShoot) {
                    steadyFrames = 0
                    capturePage()
                }
            }
        } catch (e: Exception) {
            // Detection failures are harmless: keep scanning.
        } finally {
            analyzing = false
        }
    }

    private fun isSteady(previous: FloatArray?, current: FloatArray?): Boolean {
        previous ?: return false
        current ?: return false
        var drift = 0f
        for (i in 0 until 8) drift += abs(previous[i] - current[i])
        return drift < STEADY_DRIFT_MAX
    }

    // ------------------------------------------------------------------
    // Capture + gallery import
    // ------------------------------------------------------------------

    private fun capturePage() {
        val ic = imageCapture ?: return
        if (capturing) return
        capturing = true
        val target = File(cacheDir, "documents").apply { mkdirs() }
            .let { File(it, "capture_${System.currentTimeMillis()}.jpg") }
        val options = ImageCapture.OutputFileOptions.Builder(target).build()
        ic.takePicture(options, executor, object : ImageCapture.OnImageSavedCallback {
            override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                try {
                    val full = target.readBytes()
                    // Auto-crop is the point of this scanner; if no paper is
                    // found the full frame is still a usable page.
                    val page = PaperScanner.autoCrop(full)?.bytes ?: full
                    pages.add(page)
                    runOnUiThread { updateChrome(pages.size) }
                } catch (e: Exception) {
                    runOnUiThread {
                        Toast.makeText(
                            this@ScanCameraActivity,
                            getString(R.string.page_op_failed, e.message),
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                } finally {
                    target.delete()
                    capturing = false
                }
            }

            override fun onError(exception: ImageCaptureException) {
                runOnUiThread {
                    Toast.makeText(
                        this@ScanCameraActivity,
                        getString(R.string.scan_camera_failed, exception.message),
                        Toast.LENGTH_SHORT
                    ).show()
                }
                capturing = false
            }
        })
    }

    private fun importFromGallery(uri: Uri) {
        val progress = progress(R.string.progress_title)
        lifecycleScope.launch {
            try {
                val bytes = withContext(Dispatchers.IO) {
                    contentResolver.openInputStream(uri)?.use { it.readBytes() }
                        ?: throw IllegalStateException("unreadable image")
                }
                val page = withContext(Dispatchers.IO) { PaperScanner.autoCrop(bytes) }
                pages.add(page?.bytes ?: bytes)
                withContext(Dispatchers.Main) { updateChrome(pages.size) }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(
                        this@ScanCameraActivity,
                        getString(R.string.resize_failed, e.message),
                        Toast.LENGTH_LONG
                    ).show()
                }
            } finally {
                withContext(Dispatchers.Main) { progress.dismiss() }
            }
        }
    }

    // ------------------------------------------------------------------
    // Finishing: open review, or hand pages back to it in append mode
    // ------------------------------------------------------------------

    private fun finishScanner() {
        if (pages.isEmpty()) {
            finish()
            return
        }
        val progress = progress(R.string.progress_title)
        lifecycleScope.launch {
            try {
                val staging = withContext(Dispatchers.IO) {
                    File(cacheDir, "pending_scan_${System.currentTimeMillis()}").apply { mkdirs() }
                        .also { dir ->
                            pages.forEachIndexed { i, bytes ->
                                File(
                                    dir,
                                    String.format(Locale.ROOT, "page_%03d.jpg", i)
                                ).writeBytes(bytes)
                            }
                        }
                }
                if (appendMode) {
                    val intent = Intent().apply {
                        putExtra(ScanReviewActivity.EXTRA_ADDED_PAGES_DIR, staging.path)
                    }
                    setResult(RESULT_OK, intent)
                    finish()
                } else {
                    startActivity(
                        ScanReviewActivity.intent(
                            this@ScanCameraActivity,
                            staging.path,
                            null,
                            repo.nextDocumentName()
                        )
                    )
                    finish()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(
                        this@ScanCameraActivity,
                        getString(R.string.page_op_failed, e.message),
                        Toast.LENGTH_LONG
                    ).show()
                }
            } finally {
                withContext(Dispatchers.Main) { progress.dismiss() }
            }
        }
    }

    private fun updateChrome(count: Int) {
        binding.pageCounter.text = resources.getQuantityString(R.plurals.pages, count, count)
        binding.pageCounter.isVisible = count > 0
        binding.btnDone.isVisible = count > 0
    }

    private fun progress(titleRes: Int): Dialog {
        val bar = ProgressBar(this).apply {
            isIndeterminate = true
            val pad = (24 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad, pad, pad)
        }
        return AlertDialog.Builder(this)
            .setTitle(titleRes)
            .setView(bar)
            .setCancelable(false)
            .show()
    }

    override fun onDestroy() {
        executor.shutdown()
        super.onDestroy()
    }

    companion object {
        private const val EXTRA_APPEND = "append"

        /** Steady pose = "the paper is where the user wants it". */
        private const val STEADY_FRAMES_NEEDED = 4
        private const val STEADY_DRIFT_MAX = 0.02f

        fun intent(context: Context): Intent =
            Intent(context, ScanCameraActivity::class.java)

        fun intentForAppend(context: Context): Intent =
            Intent(context, ScanCameraActivity::class.java).putExtra(EXTRA_APPEND, true)
    }
}
