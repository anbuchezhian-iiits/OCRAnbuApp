package com.example.ocranbuapp

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Bundle
import android.util.Log
import android.widget.Toast
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

    // Start the camera preview with size 320x320
    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()

            val preview = Preview.Builder()
                .setTargetResolution(android.util.Size(320, 320)) // Set preview size to 320x320
                .setTargetRotation(windowManager.defaultDisplay.rotation)
                .build()
                .also {
                    it.setSurfaceProvider(binding.previewView.surfaceProvider)
                }

            imageCapture = ImageCapture.Builder()
                .setTargetRotation(windowManager.defaultDisplay.rotation)
                .build()

            val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

            try {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(
                    this, cameraSelector, preview, imageCapture
                )
                binding.previewView.rotation = 90f
            } catch (e: Exception) {
                Log.e("CameraX", "Use case binding failed", e)
            }
        }, ContextCompat.getMainExecutor(this))
    }

    // Capture an image and pass it to the OCR model
    private fun captureImage() {
        val photoFile = File(outputDirectory, "captured_image.jpg")
        val outputOptions = ImageCapture.OutputFileOptions.Builder(photoFile).build()

        imageCapture.takePicture(
            outputOptions, cameraExecutor, object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                    // Decode the captured image file
                    var capturedImage = BitmapFactory.decodeFile(photoFile.absolutePath)

                    // Correct the orientation if needed
                    capturedImage = adjustImageOrientation(capturedImage)

                    // Resize to 320x320
                    val resizedImage = Bitmap.createScaledBitmap(capturedImage, 320, 320, false)

                    // Display the resized image
                    displayCapturedImage(resizedImage)

                    // Call the OCR model executor
                    onApplyModel(resizedImage, ocrModelExecutor, inferenceThread) { recognizedText ->
                        updateUIWithResults(recognizedText)
                    }
                }

                override fun onError(exception: ImageCaptureException) {
                    Log.e("Camera", "Error capturing image: ${exception.message}")
                    Toast.makeText(this@MainActivity, "Error capturing image", Toast.LENGTH_SHORT).show()
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

    // Handle OCR execution
    private fun onApplyModel(
        contentImage: Bitmap,
        ocrModel: OCRModelExecutor?,
        inferenceThread: ExecutorCoroutineDispatcher,
        onResult: (String) -> Unit // Callback to return the result
    ) {
        CoroutineScope(Dispatchers.Main).launch {
            withContext(inferenceThread) {
                try {
                    // Ensure the result is obtained from ModelExecutionResult
                    val result = ocrModel?.execute(contentImage) as ModelExecutionResult
                    val recognizedText = result.reading ?: "No reading detected"
                    onResult(recognizedText) // Return the result via the callback
                } catch (e: Exception) {
                    Log.e("OCR", "OCR execution failed: ${e.message}")
                    onResult("OCR execution failed") // If there was an error, return an error message
                }
            }
        }
    }

    // Update the UI with the OCR result
    private fun updateUIWithResults(recognizedText: String) {
        binding.tvExecutionLog.text = "Execution Log: Success"
        binding.tvReading.text = "Meter Reading: $recognizedText"
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_PERMISSION_CAMERA && grantResults.isNotEmpty()
            && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            startCamera()
        } else {
            Toast.makeText(this, "Camera permission is required", Toast.LENGTH_SHORT).show()
        }
    }
}