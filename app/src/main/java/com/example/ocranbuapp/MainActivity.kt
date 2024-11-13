package com.example.ocranbuapp

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Bundle
import android.util.Log
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.example.ocranbuapp.databinding.ActivityMainBinding
import kotlinx.coroutines.*
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import android.content.pm.PackageManager
import org.tensorflow.lite.meeterreader.ocr.ModelExecutionResult
import org.tensorflow.lite.meeterreader.ocr.OCRModelExecutor

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var ocrModelExecutor: OCRModelExecutor
    private lateinit var cameraExecutor: ExecutorService
    private lateinit var imageCapture: ImageCapture
    private val REQUEST_PERMISSION_CAMERA = 100

    // Field for logging execution messages
    private var executionLog: String = ""

    private val outputDirectory: File by lazy {
        val mediaDir = externalMediaDirs.firstOrNull()?.let {
            File(it, resources.getString(R.string.app_name)).apply { mkdirs() }
        }
        mediaDir ?: filesDir
    }

    private val inferenceThread = Executors.newSingleThreadExecutor().asCoroutineDispatcher()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Initialize OCR model executor
        ocrModelExecutor = OCRModelExecutor(this)

        // Request camera permissions
        if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.CAMERA)
            != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(
                this, arrayOf(android.Manifest.permission.CAMERA), REQUEST_PERMISSION_CAMERA
            )
        }

        // Initialize camera preview
        cameraExecutor = Executors.newSingleThreadExecutor()
        startCamera()

        // Capture photo button click listener
        binding.btnCapturePhoto.setOnClickListener {
            captureImage()
        }
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()

            // Define the preview
            val preview = Preview.Builder()
                .setTargetResolution(android.util.Size(320, 320)) // Set preview resolution
                .setTargetRotation(windowManager.defaultDisplay.rotation)
                .build()
                .also {
                    it.setSurfaceProvider(binding.previewView.surfaceProvider)
                }

            // Define image capture use case
            imageCapture = ImageCapture.Builder()
                .setTargetRotation(windowManager.defaultDisplay.rotation)
                .build()

            val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

            try {
                // Unbind all use cases and bind the required ones
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(this, cameraSelector, preview, imageCapture)
            } catch (e: Exception) {
                Log.e("CameraX", "Use case binding failed", e)
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun captureImage() {
        val photoFile = File(outputDirectory, "captured_image.jpg")
        val outputOptions = ImageCapture.OutputFileOptions.Builder(photoFile).build()

        imageCapture.takePicture(
            outputOptions, cameraExecutor, object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                    var capturedImage = BitmapFactory.decodeFile(photoFile.absolutePath)
                    capturedImage = adjustImageOrientation(capturedImage)
                    val resizedImage = Bitmap.createScaledBitmap(capturedImage, 320, 320, false)

                    displayCapturedImage(resizedImage)

                    onApplyModel(resizedImage, ocrModelExecutor, inferenceThread) { result ->
                        updateUIWithResults(result)
                    }
                }

                override fun onError(exception: ImageCaptureException) {
                    executionLog = "Error capturing image: ${exception.message}"
                    updateExecutionLog()
                }
            }
        )
    }

    private fun adjustImageOrientation(image: Bitmap): Bitmap {
        // Check and correct orientation
        val matrix = android.graphics.Matrix()
        matrix.postRotate(90f) // Apply no rotation to ensure default orientation

        // Apply the transformation to ensure correct orientation
        return Bitmap.createBitmap(image, 0, 0, image.width, image.height, matrix, true)
    }

    // Display the captured image in the ImageView
    private fun displayCapturedImage(image: Bitmap) {
        runOnUiThread {
            binding.ivCapturedImage.setImageBitmap(image)
        }
    }

    private fun onApplyModel(
        contentImage: Bitmap,
        ocrModel: OCRModelExecutor?,
        inferenceThread: ExecutorCoroutineDispatcher,
        onResult: (ModelExecutionResult) -> Unit
    ) {
        CoroutineScope(Dispatchers.Main).launch {
            withContext(inferenceThread) {
                try {
                    val result = ocrModel?.execute(contentImage) as ModelExecutionResult
                    executionLog = "OCR execution completed successfully."
                    onResult(result)
                } catch (e: Exception) {
                    executionLog = "OCR execution failed: ${e.message}"
                    onResult(ModelExecutionResult(contentImage, executionLog, null))
                }
                updateExecutionLog()
            }
        }
    }

    private fun updateUIWithResults(result: ModelExecutionResult) {
        runOnUiThread {
            // Show annotated image in new ImageView
            binding.ivAnnotatedImage.setImageBitmap(result.bitmapResult)
            // Display reading and execution log
            binding.tvReading.text = "Meter Reading: ${result.reading ?: "No reading detected"}"
            updateExecutionLog()
        }
    }

    // Updates the Execution Log UI
    private fun updateExecutionLog() {
        runOnUiThread {
            binding.tvExecutionLog.text = "Execution Log: $executionLog"
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_PERMISSION_CAMERA && grantResults.isNotEmpty()
            && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            startCamera()
        } else {
            executionLog = "Camera permission is required."
            updateExecutionLog()
        }
    }
}