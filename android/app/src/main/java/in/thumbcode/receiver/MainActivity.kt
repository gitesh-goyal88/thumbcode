package `in`.thumbcode.receiver

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import `in`.thumbcode.receiver.ui.ScanScreen
import `in`.thumbcode.receiver.ui.SettingsScreen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.opencv.android.OpenCVLoader
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Core
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class MainActivity : ComponentActivity() {

    private val analysisExecutor = Executors.newSingleThreadExecutor()
    private val busy = AtomicBoolean(false)

    private lateinit var settings: Settings
    private lateinit var client: VerifyClient

    private var frame by mutableStateOf(Frame(Stage.SEARCHING, "Point the camera at a printed code"))
    private var verdict by mutableStateOf<Verdict?>(null)
    private var checking by mutableStateOf(false)
    private var granted by mutableStateOf(false)
    private var openCvReady = false

    private val askCamera = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        granted = it
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settings = Settings(this)
        client = VerifyClient(settings)

        openCvReady = OpenCVLoader.initLocal()
        if (!openCvReady) {
            frame = Frame(Stage.SEARCHING, "OpenCV failed to load on this device")
        }

        granted = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED
        if (!granted) askCamera.launch(Manifest.permission.CAMERA)

        setContent {
            var showSettings by remember { mutableStateOf(!settings.configured) }

            if (showSettings) {
                SettingsScreen(settings) { showSettings = false }
            } else {
                ScanScreen(
                    frame = frame,
                    verdict = verdict,
                    checking = checking,
                    onOpenSettings = { showSettings = true },
                    onScanAgain = {
                        verdict = null
                        frame = Frame(Stage.SEARCHING, "Point the camera at a printed code")
                    },
                ) { modifier ->
                    if (granted && openCvReady) CameraPreview(modifier)
                }
            }
        }
    }

    @Composable
    private fun CameraPreview(modifier: Modifier) {
        val context = LocalContext.current
        val lifecycleOwner = this
        AndroidView(
            modifier = modifier,
            factory = { ctx ->
                val view = PreviewView(ctx).apply {
                    scaleType = PreviewView.ScaleType.FILL_CENTER
                }
                val future = ProcessCameraProvider.getInstance(ctx)
                future.addListener({
                    val provider = future.get()
                    val preview = Preview.Builder().build()
                        .also { it.setSurfaceProvider(view.surfaceProvider) }

                    val analysis = ImageAnalysis.Builder()
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .build()
                        .also { it.setAnalyzer(analysisExecutor, ::analyse) }

                    provider.unbindAll()
                    provider.bindToLifecycle(
                        lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis,
                    )
                }, ContextCompat.getMainExecutor(ctx))
                view
            },
        )
    }

    /** Y plane only. Chroma is irrelevant to a black-and-white code and costs a conversion. */
    private fun analyse(image: ImageProxy) {
        if (verdict != null || checking || busy.get()) {
            image.close()
            return
        }
        busy.set(true)
        try {
            val gray = yPlaneToMat(image)
            val rotated = rotateUpright(gray, image.imageInfo.rotationDegrees)
            val result = Decoder.decode(rotated)
            rotated.release()

            frame = result
            if (result.complete) {
                val id = result.docId!!
                val spots = result.spots ?: emptyList()
                checking = true
                lifecycleScope.launch {
                    val v = withContext(Dispatchers.IO) { client.verify(id, spots) }
                    verdict = v
                    checking = false
                }
            }
        } catch (e: Exception) {
            frame = Frame(Stage.SEARCHING, e.message ?: "Frame could not be read")
        } finally {
            busy.set(false)
            image.close()
        }
    }

    private fun yPlaneToMat(image: ImageProxy): Mat {
        val plane = image.planes[0]
        val buffer = plane.buffer
        val bytes = ByteArray(buffer.remaining())
        buffer.get(bytes)

        val rowStride = plane.rowStride
        val width = image.width
        val height = image.height

        val mat = Mat(height, width, CvType.CV_8UC1)
        if (rowStride == width) {
            mat.put(0, 0, bytes)
        } else {
            val row = ByteArray(width)
            for (y in 0 until height) {
                System.arraycopy(bytes, y * rowStride, row, 0, width)
                mat.put(y, 0, row)
            }
        }
        return mat
    }

    private fun rotateUpright(mat: Mat, degrees: Int): Mat {
        val code = when (degrees) {
            90 -> Core.ROTATE_90_CLOCKWISE
            180 -> Core.ROTATE_180
            270 -> Core.ROTATE_90_COUNTERCLOCKWISE
            else -> return mat
        }
        val out = Mat()
        Core.rotate(mat, out, code)
        mat.release()
        return out
    }

    override fun onDestroy() {
        super.onDestroy()
        analysisExecutor.shutdown()
    }
}
