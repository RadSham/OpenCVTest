package com.example.opencvtest

import android.Manifest.permission.CAMERA
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import android.view.View
import android.view.Window
import android.view.WindowManager
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.example.opencvtest.databinding.ActivityCamBinding
import org.opencv.android.BaseLoaderCallback
import org.opencv.android.CameraBridgeViewBase
import org.opencv.android.CameraBridgeViewBase.CvCameraViewFrame
import org.opencv.android.CameraBridgeViewBase.CvCameraViewListener2
import org.opencv.android.LoaderCallbackInterface
import org.opencv.android.OpenCVLoader
import org.opencv.core.Mat
import org.opencv.core.Point
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.dnn.Dnn
import org.opencv.dnn.Net
import org.opencv.imgproc.Imgproc
import org.opencv.imgproc.Imgproc.FONT_HERSHEY_SIMPLEX
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

// https://docs.opencv.org/4.7.0/d0/d6c/tutorial_dnn_android.html

class CamActivity : Activity(), CvCameraViewListener2 {

    private lateinit var binding: ActivityCamBinding
    private var net: Net? = null
    private var mOpenCvCameraView: CameraBridgeViewBase? = null

    // Initialize OpenCV manager.
    private val mLoaderCallback: BaseLoaderCallback = object : BaseLoaderCallback(this) {
        override fun onManagerConnected(status: Int) {
            when (status) {
                SUCCESS -> {
                    run {
                        Log.i(TAG, "OpenCV loaded successfully")
                        mOpenCvCameraView!!.enableView()
                    }
                    run { super.onManagerConnected(status) }
                }
                else -> {
                    super.onManagerConnected(status)
                }
            }
        }
    }

    public override fun onResume() {
        super.onResume()
        Log.d(TAG, "onResume")

        if (OpenCVLoader.initDebug()) {
            //if load success
            Log.d(TAG, "Opencv initialization is done")
            mLoaderCallback.onManagerConnected(LoaderCallbackInterface.SUCCESS)
        } else {
            //if not loaded
            Log.d(TAG, "Opencv is not loaded. try again")
            OpenCVLoader.initAsync(OpenCVLoader.OPENCV_VERSION_3_4_0, this, mLoaderCallback)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityCamBinding.inflate(layoutInflater)
        val view: View = binding.root

        requestWindowFeature(Window.FEATURE_NO_TITLE)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val MY_PERMISSIONS_REQUEST_CAMERA = 0
        // if camera permission is not given it will ask for it on device
        if (ContextCompat.checkSelfPermission(this@CamActivity, CAMERA)
            == PackageManager.PERMISSION_DENIED
        ) {
            ActivityCompat.requestPermissions(
                this@CamActivity,
                arrayOf(CAMERA),
                MY_PERMISSIONS_REQUEST_CAMERA
            )
        }

        setContentView(view)
        // Set up camera listener.
        mOpenCvCameraView = binding.frameSurface
        mOpenCvCameraView!!.visibility = CameraBridgeViewBase.VISIBLE
        mOpenCvCameraView!!.setCameraPermissionGranted()
        mOpenCvCameraView!!.setCvCameraViewListener(this)
    }

    // Load a network.
    override fun onCameraViewStarted(width: Int, height: Int) {
        val proto: String = getPath("MobileNetSSD_deploy.prototxt", this)
        val weights: String = getPath("MobileNetSSD_deploy.caffemodel", this)
        net = Dnn.readNetFromCaffe(proto, weights)
        Log.i(TAG, "Network loaded successfully")
    }

    override fun onCameraFrame(inputFrame: CvCameraViewFrame): Mat {
        val IN_WIDTH = 300
        val IN_HEIGHT = 300
        val WH_RATIO = IN_WIDTH.toFloat() / IN_HEIGHT
        val IN_SCALE_FACTOR = 0.007843
        val MEAN_VAL = 127.5
        val THRESHOLD = 0.2
        // Get a new frame
        val frame = inputFrame.rgba()
        Imgproc.cvtColor(frame, frame, Imgproc.COLOR_RGBA2RGB)
        // Forward image through network.
        val blob = Dnn.blobFromImage(
            frame, IN_SCALE_FACTOR,
            Size(IN_WIDTH.toDouble(), IN_HEIGHT.toDouble()),
            Scalar(MEAN_VAL, MEAN_VAL, MEAN_VAL),  /*swapRB*/false,  /*crop*/false
        )
        net!!.setInput(blob)
        var detections = net!!.forward()
        val cols = frame.cols()
        val rows = frame.rows()
        detections = detections.reshape(1, detections.total().toInt() / 7)
        for (i in 0 until detections.rows()) {
            val confidence = detections[i, 2][0]
            if (confidence > THRESHOLD) {
                val classId = detections[i, 1][0].toInt()
                val left = (detections[i, 3][0] * cols).toInt()
                val top = (detections[i, 4][0] * rows).toInt()
                val right = (detections[i, 5][0] * cols).toInt()
                val bottom = (detections[i, 6][0] * rows).toInt()
                // Draw rectangle around detected object.
                Imgproc.rectangle(
                    frame, Point(left.toDouble(), top.toDouble()), Point(
                        right.toDouble(),
                        bottom.toDouble()
                    ),
                    Scalar(0.0, 255.0, 0.0)
                )
                val label: String =
                    classNames[classId] + ": " + confidence
                val baseLine = IntArray(1)
                val labelSize = Imgproc.getTextSize(label, FONT_HERSHEY_SIMPLEX, 0.5, 1, baseLine)
                // Draw background for label.
                Imgproc.rectangle(
                    frame, Point(left.toDouble(), top - labelSize.height),
                    Point(left + labelSize.width, (top + baseLine[0]).toDouble()),
                    Scalar(255.0, 255.0, 255.0), Imgproc.FILLED
                )
                // Write class name and confidence.
                Imgproc.putText(
                    frame, label, Point(left.toDouble(), top.toDouble()),
                    FONT_HERSHEY_SIMPLEX, 0.5, Scalar(0.0, 0.0, 0.0)
                )
            }
        }
        return frame
    }

    override fun onCameraViewStopped() {}


    companion object {
        // Upload file to storage and return a path.
        private fun getPath(file: String, context: Context): String {
            val assetManager = context.assets
            val inputStream: BufferedInputStream?
            try {
                // Read data from assets.
                inputStream = BufferedInputStream(assetManager.open(file))
                val data = ByteArray(inputStream.available())
                inputStream.read(data)
                inputStream.close()
                // Create copy file in storage.
                val outFile = File(context.filesDir, file)
                val os = FileOutputStream(outFile)
                os.write(data)
                os.close()
                // Return a path to file which may be read in common way.
                return outFile.absolutePath
            } catch (ex: IOException) {
                Log.i(TAG, "Failed to upload a file")
            }
            return ""
        }

        private const val TAG = "OpenCV/Sample/MobileNet"
        private val classNames = arrayOf(
            "background",
            "aeroplane", "bicycle", "bird", "boat",
            "bottle", "bus", "car", "cat", "chair",
            "cow", "diningtable", "dog", "horse",
            "motorbike", "person", "pottedplant",
            "sheep", "sofa", "train", "tvmonitor"
        )
    }
}

