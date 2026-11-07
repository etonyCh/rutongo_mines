package rw.rutongo.mmems.android.camera

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.exifinterface.media.ExifInterface
import java.io.File
import java.util.concurrent.Executors

@Composable
fun CameraCaptureScreen(
    documentName: String,
    onClose: () -> Unit,
    onCaptured: (Uri) -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var hasPermission by remember { mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) }
    var imageCapture by remember { mutableStateOf<ImageCapture?>(null) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val currentOnCaptured by rememberUpdatedState(onCaptured)
    val previewView = remember { PreviewView(context).apply { scaleType = PreviewView.ScaleType.FILL_CENTER } }
    val cameraExecutor = remember { Executors.newSingleThreadExecutor() }
    val permissionLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            hasPermission = granted
            if (!granted) error = "Camera permission is required to attach photo evidence."
        }

    LaunchedEffect(Unit) {
        if (!hasPermission) permissionLauncher.launch(Manifest.permission.CAMERA)
    }

    LaunchedEffect(hasPermission) {
        if (!hasPermission) return@LaunchedEffect
        val providerFuture = ProcessCameraProvider.getInstance(context)
        providerFuture.addListener(
            {
                runCatching {
                    val provider = providerFuture.get()
                    val preview = Preview.Builder().build().also { it.surfaceProvider = previewView.surfaceProvider }
                    val capture =
                        ImageCapture
                            .Builder()
                            .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                            .setTargetRotation(previewView.display.rotation)
                            .build()
                    provider.unbindAll()
                    provider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, capture)
                    imageCapture = capture
                }.onFailure { error = "The camera could not be started: ${it.message}" }
            },
            ContextCompat.getMainExecutor(context),
        )
    }

    DisposableEffect(Unit) {
        onDispose {
            runCatching { ProcessCameraProvider.getInstance(context).get().unbindAll() }
            cameraExecutor.shutdown()
        }
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        if (hasPermission) {
            AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())
        } else {
            Column(
                Modifier.fillMaxSize().padding(32.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text("Camera permission is needed for photo evidence.", color = Color.White)
                Button(onClick = { permissionLauncher.launch(Manifest.permission.CAMERA) }, modifier = Modifier.padding(top = 16.dp)) { Text("GRANT PERMISSION") }
            }
        }
        Row(
            Modifier.fillMaxWidth().align(Alignment.TopCenter).padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Photo evidence · $documentName", color = Color.White, fontWeight = FontWeight.Bold)
            OutlinedButton(onClick = onClose) { Icon(Icons.Default.Close, contentDescription = "Close camera") }
        }
        Column(
            Modifier.fillMaxWidth().align(Alignment.BottomCenter).padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            error?.let {
                Text(it, color = Color(0xFFFFB4AB), modifier = Modifier.padding(bottom = 12.dp))
            }
            Button(
                onClick = {
                    val capture = imageCapture ?: return@Button
                    busy = true
                    error = null
                    val raw = File(context.cacheDir, "camera_${System.currentTimeMillis()}.jpg")
                    val options = ImageCapture.OutputFileOptions.Builder(raw).build()
                    capture.takePicture(
                        options,
                        cameraExecutor,
                        object : ImageCapture.OnImageSavedCallback {
                            override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                                val compressed = runCatching { compress(raw, File(context.cacheDir, "photo_${System.currentTimeMillis()}.jpg")) }.getOrNull()
                                ContextCompat.getMainExecutor(context).execute {
                                    busy = false
                                    if (compressed == null) {
                                        error = "The photo could not be compressed."
                                    } else {
                                        currentOnCaptured(Uri.fromFile(compressed))
                                    }
                                    raw.delete()
                                }
                            }

                            override fun onError(exception: ImageCaptureException) {
                                ContextCompat.getMainExecutor(context).execute {
                                    busy = false
                                    error = "Capture failed: ${exception.message}"
                                }
                                raw.delete()
                            }
                        },
                    )
                },
                enabled = !busy && imageCapture != null,
                shape = CircleShape,
                modifier = Modifier.size(84.dp),
            ) {
                if (busy) CircularProgressIndicator(color = Color.White, modifier = Modifier.size(28.dp)) else Icon(Icons.Default.CameraAlt, contentDescription = "Capture", modifier = Modifier.size(34.dp))
            }
            Text("Photos are compressed to about 1600 px before encrypted storage.", color = Color.White.copy(alpha = 0.7f), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 10.dp))
        }
    }
}

private fun compress(
    source: File,
    target: File,
    maxDimension: Int = 1600,
    quality: Int = 80,
): File {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(source.absolutePath, bounds)
    var sample = 1
    while (maxOf(bounds.outWidth, bounds.outHeight) / sample > maxDimension * 2) sample *= 2
    val decoded =
        BitmapFactory.decodeFile(
            source.absolutePath,
            BitmapFactory.Options().apply { inSampleSize = sample },
        ) ?: error("Unreadable image")
    val scaled = scaleDown(decoded, maxDimension)
    val rotated = rotateFromExif(scaled, source)
    target.outputStream().use { rotated.compress(Bitmap.CompressFormat.JPEG, quality, it) }
    if (rotated !== decoded) rotated.recycle()
    if (scaled !== decoded) scaled.recycle()
    decoded.recycle()
    return target
}

private fun scaleDown(
    bitmap: Bitmap,
    maxDimension: Int,
): Bitmap {
    val largest = maxOf(bitmap.width, bitmap.height)
    if (largest <= maxDimension) return bitmap
    val ratio = maxDimension.toFloat() / largest
    return Bitmap.createScaledBitmap(bitmap, (bitmap.width * ratio).toInt(), (bitmap.height * ratio).toInt(), true)
}

private fun rotateFromExif(
    bitmap: Bitmap,
    source: File,
): Bitmap {
    val orientation =
        runCatching {
            ExifInterface(source.absolutePath).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)
    val matrix = Matrix()
    when (orientation) {
        ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
        ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
        ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
        ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.postScale(-1f, 1f)
        ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.postScale(1f, -1f)
    }
    return if (matrix.isIdentity) bitmap else Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
}
