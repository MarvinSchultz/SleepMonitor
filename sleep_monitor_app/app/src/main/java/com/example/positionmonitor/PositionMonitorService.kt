package com.example.positionmonitor

import android.app.*
import android.content.*
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.appcompat.app.AppCompatActivity
import java.io.DataOutputStream
import java.io.FileOutputStream
import java.io.File
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.abs
import kotlin.math.sqrt
import android.os.Binder
import android.util.Log
import com.felhr.usbserial.UsbSerialDevice
import com.felhr.usbserial.UsbSerialInterface
import android.app.Service
import android.content.Intent
import android.hardware.usb.*

import com.berry_med.spo2.fragment.MeasureFragment

open class MeanVector(protected val maxSize: Int) {
    protected val values = mutableListOf<FloatArray>()
    protected var currentMean = floatArrayOf(0f, 0f, 0f, 1f)

    open fun addValue(inputValue: FloatArray) {
        val value = inputValue.clone()
        currentMean[0] = currentMean[0] * values.size + value[0]
        currentMean[1] = currentMean[1] * values.size + value[1]
        currentMean[2] = currentMean[2] * values.size + value[2]
        values.add(value)
        if (values.size > maxSize) {
            currentMean[0] -= values[0][0]
            currentMean[1] -= values[0][1]
            currentMean[2] -= values[0][2]
            values.removeAt(0)
        }
        currentMean[0] /= values.size.toFloat()
        currentMean[1] /= values.size.toFloat()
        currentMean[2] /= values.size.toFloat()
    }

    fun mean(): FloatArray {
        return currentMean
    }
}

class MeanLine(maxSize: Int) : MeanVector(maxSize) {
    override fun addValue(inputValue: FloatArray) {
        val value = inputValue.clone()
        val maxDim = maxDimension(value)
        // Phone screen facing up means positive acceleration values point down to the ground. I want positive
        // values to point up to show stomach displacement.
        if (value[maxDim] > 0) {
            value[0] = -value[0]
            value[1] = -value[1]
            value[2] = -value[2]
        }
        currentMean[0] = currentMean[0] * values.size + value[0]
        currentMean[1] = currentMean[1] * values.size + value[1]
        currentMean[2] = currentMean[2] * values.size + value[2]
        values.add(value)
        if (values.size > maxSize) {
            currentMean[0] -= values[0][0]
            currentMean[1] -= values[0][1]
            currentMean[2] -= values[0][2]
            values.removeAt(0)
        }
        currentMean[0] /= values.size.toFloat()
        currentMean[1] /= values.size.toFloat()
        currentMean[2] /= values.size.toFloat()
    }

    protected fun maxDimension(value: FloatArray): Int {
        var maxDim = 0
        if (values.size > 0) {
            if (abs(currentMean[2]) > abs(currentMean[1]) && abs(currentMean[1]) > abs(currentMean[0]))
                maxDim = 2
            else if (abs(currentMean[1]) > abs(currentMean[2]) && abs(currentMean[1]) > abs(currentMean[0]))
                maxDim = 1
            return maxDim
        }
        if (abs(value[2]) > abs(value[1]) && abs(value[1]) > abs(value[0]))
            maxDim = 2
        else if (abs(value[1]) > abs(value[2]) && abs(value[1]) > abs(value[0]))
            maxDim = 1
        return maxDim
    }
}

class MeanValue(protected val maxSize: Int) {
    protected val values = mutableListOf<Float>()
    protected var currentMean = 0f

    open fun addValue(value: Float) {
        currentMean = currentMean * values.size + value
        values.add(value)
        if (values.size > maxSize) {
            currentMean -= values[0]
            values.removeAt(0)
        }
        currentMean /= values.size.toFloat()
    }

    fun mean(): Float {
        return currentMean
    }

    fun count(): Int {
        return values.size
    }
}

// Return vec1 - vec2
fun vecSubtract(vec1: FloatArray, vec2: FloatArray): FloatArray {
    return floatArrayOf(vec1[0] - vec2[0], vec1[1] - vec2[1], vec1[2] - vec2[2], 1f)
}

// Return vec1 + vec2*factor
fun vecAddProduct(vec1: FloatArray, vec2: FloatArray, factor: Float): FloatArray {
    return floatArrayOf(vec1[0] + factor * vec2[0], vec1[1] + factor * vec2[1],
        vec1[2] + factor * vec2[2], 1f)
}

fun vecProduct(vector: FloatArray, factor: Float): FloatArray {
    return floatArrayOf(vector[0] * factor, vector[1] * factor, vector[2] * factor, 1f)
}

fun vecString(vector: FloatArray): String {
    return vector[0].toString() + "," + vector[1].toString() + "," + vector[2].toString()
}

// Return the dot product of vectors
fun dotProduct(vec1: FloatArray, vec2: FloatArray): Float {
    return vec1[0] * vec2[0] + vec1[1] * vec2[1] + vec1[2] * vec2[2]
}

fun vecNorm(vector: FloatArray): Float {
    return sqrt(dotProduct(vector, vector))
}

// Returns the component of the point along the unit vector in the direction of vector
fun vecProjectionToScalar(point: FloatArray, vector: FloatArray): Float {
    val norm = vecNorm(vector)
    if (norm == 0f)
        return 0f
    return dotProduct(point, vector) / norm
}


class PositionMonitorService : Service(), SensorEventListener {

    val kChannelId = "PositionMonitorId"
    protected val kNotificationId = 1
    protected lateinit var channel: NotificationChannel

    private lateinit var sensorManager: SensorManager
    private val linearAccelerometerReading = floatArrayOf(0f, 0f, 0f, 1f)
    private val accelerometerReading = floatArrayOf(0f, 0f, 0f, 1f)
    private val magnetometerReading = floatArrayOf(0f, 0f, 0f, 1f)

    private var sensorUnixTimestamp = 0L
    private var firstSensorUnixTimestamp = 0L
    private var lastSensorTime: Long = 0
    private var deltaTime = 0f

    private var textFile: File? = null
    private var filename: String? = null
    private var currentFile: DataOutputStream? = null

    protected var powerManager: PowerManager? = null
    protected var wakeLock: PowerManager.WakeLock? = null
    protected var sensorCount = 0L

    var meanSpO2Wave = 0f

    // The last time the mean velocity and position were subtracted and set to 0
    private var lastNormTime: Long = 0L
    private var accelerationInitCount: Int = 0
    private var velocityInitCount: Int = 0

    var velocityVector = floatArrayOf(0f, 0f, 0f, 1f)
    var positionVector = floatArrayOf(0f, 0f, 0f, 1f)

    // Sensor readings come in every 0.19 seconds
    private val meanMaxSize = 75
    private var meanAcceleration = MeanVector(30)
    //private var meanVelocity = MeanVector(meanMaxSize)
    //private var meanPosition = MeanVector(meanMaxSize)
    private var meanPositionDiff = MeanLine(meanMaxSize)
    private var meanOrientation = MeanVector(meanMaxSize)
    private var meanOrientationDiff = MeanLine(meanMaxSize)
    private var meanThermistor = MeanValue(meanMaxSize)

    private val ACTION_USB_PERMISSION = "com.android.example.USB_PERMISSION"
    var device: UsbDevice? = null
    lateinit var usbManager: UsbManager
    var serial: UsbSerialDevice? = null
    var serialText: String = ""
    var currentThermistorValue = 0

    var statusText: String = ""

    val measureFragment = MeasureFragment()

    // This is the object that receives interactions from clients.
    private val mBinder = LocalBinder()

    inner class LocalBinder : Binder() {
        fun getService() : PositionMonitorService {
            return this@PositionMonitorService
        }
    }

    private val mCallback = UsbSerialInterface.UsbReadCallback {
        serialText += String(it)
        if (serialText.indexOf(";") != -1) {
            val startIdx = serialText.indexOf("|")
            var stopIdx = serialText.lastIndexOf(";")
            if (startIdx != -1 || stopIdx != -1) {
                val currentText = serialText.substring(startIdx, stopIdx)
                if (stopIdx + 1 < serialText.length)
                    serialText = serialText.substring(stopIdx + 1)
                else
                    serialText = ""

                val data = currentText.split(";")
                for (valueText in data) {
                    if (valueText.indexOf("|") == -1)
                        continue
                    val numberText = valueText.replace("|", "").replace("\n", "").replace("\r", "")
                    currentThermistorValue = numberText.toInt()
                }
                //Log.d("D", "Success")
            }

        }

    }

    private val usbReceiver = object : BroadcastReceiver() {
        var usbInterface: UsbInterface? = null
        var usbEndpoint: UsbEndpoint? = null
        lateinit var bytes: ByteArray

        override fun onReceive(context: Context, intent: Intent) {
            if (ACTION_USB_PERMISSION == intent.action) {
                synchronized(this) {
                    //val device: UsbDevice? = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)

                    if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) {
                        device?.apply {

                            /*usbInterface = device?.getInterface(0)
                            usbEndpoint = usbInterface?.getEndpoint(0)*/

                            val connection = usbManager.openDevice(device).apply {
                                claimInterface(usbInterface, true)
                                bytes = ByteArray(1)
                                bulkTransfer(usbEndpoint, bytes, bytes.size, 0) //do in another thread
                            }
                            val serialPort = UsbSerialDevice.createUsbSerialDevice(device, connection)
                            serial = serialPort
                            if (serialPort != null) {

                                if (serialPort.open()) { //Set Serial Connection Parameters.
                                    serialPort.setBaudRate(9600);
                                    serialPort.setDataBits(UsbSerialInterface.DATA_BITS_8);
                                    serialPort.setStopBits(UsbSerialInterface.STOP_BITS_1);
                                    serialPort.setParity(UsbSerialInterface.PARITY_NONE);
                                    serialPort.setFlowControl(UsbSerialInterface.FLOW_CONTROL_OFF);
                                    serialPort.read(mCallback); //
                                    //tvAppend(textView,"Serial Connection Opened!\n");

                                } else {
                                    Log.d("SERIAL", "PORT NOT OPEN");
                                }
                            } else {
                                Log.d("SERIAL", "PORT IS NULL");
                            }
                            //call method to set up device communication
                        }
                    } else {
                        // permission denied for device
                    }
                }
            }
        }
    }

    fun readUSB() {
        usbManager = getSystemService(Context.USB_SERVICE) as UsbManager

        val deviceList = usbManager.getDeviceList()
        //val accessoryList = usbManager.getAccessoryList()
        if (deviceList.size < 1) return
        device = deviceList.values.elementAt(0)
        //device = deviceList.get("/dev/bus/usb/001/002")
        val serialNumber = device?.serialNumber
        var permissionIntent = PendingIntent.getBroadcast(this, 0, Intent(ACTION_USB_PERMISSION), 0)
        val filter = IntentFilter(ACTION_USB_PERMISSION)
        registerReceiver(usbReceiver, filter)
        usbManager.requestPermission(device, permissionIntent)
    }

    override fun onStartCommand(intent: Intent, flags: Int, startId: Int): Int {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            // Create the NotificationChannel
            val name = getString(R.string.channel_name)
            val descriptionText = getString(R.string.channel_description)
            val importance = NotificationManager.IMPORTANCE_LOW
            channel = NotificationChannel(kChannelId, name, importance)
            channel.description = descriptionText
            // Register the channel with the system; you can't change the importance
            // or other notification behaviors after this
            val notificationManager = getSystemService(AppCompatActivity.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }

        val pendingIntent: PendingIntent =
            Intent(this, PositionMonitorService::class.java).let { notificationIntent ->
                PendingIntent.getActivity(this, 0, notificationIntent, 0)
            }

        val notification: Notification = Notification.Builder(this, kChannelId)
            .setContentTitle(getText(R.string.notification_title))
            .setContentText(getText(R.string.notification_message))
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentIntent(pendingIntent)
            .setTicker(getText(R.string.ticker_text))
            .build()

        measureFragment.connect(this)

        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        setCurrentFile()

        sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)?.also { accelerometer ->
            sensorManager.registerListener(
                this,
                accelerometer,
                SensorManager.SENSOR_DELAY_NORMAL,
                SensorManager.SENSOR_DELAY_UI
            )
        }
        sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)?.also { magnetometer ->
            sensorManager.registerListener(
                this,
                magnetometer,
                SensorManager.SENSOR_DELAY_NORMAL,
                SensorManager.SENSOR_DELAY_UI
            )
        }

        if (powerManager == null)
            powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "positionMonitor:wakeLock")
        wakeLock?.acquire(24*60*60*1000L)

        readUSB()

        setStatus("Recording to $filename")

        startForeground(kNotificationId, notification)

        //TODO do something useful
        return Service.START_NOT_STICKY
    }

    override fun onDestroy() {
        sensorManager.unregisterListener(this)
        currentFile?.close()
        currentFile = null
        wakeLock?.release()
        serial?.close()
        super.onDestroy()
    }

    override fun onBind(intent: Intent): IBinder? {
        return mBinder
    }

    fun setCurrentFile() {
        val path = this.getExternalFilesDir(null)
        val directory = File(path, "PositionMonitor")
        directory.mkdirs()
        val name = SimpleDateFormat("yyyy.MM.dd HH:mm:ss").format(Date())
        filename = File(directory, name).toString()
        currentFile = DataOutputStream(FileOutputStream(filename + ".dat"))
        textFile = File(directory, name + ".txt")
        lastNormTime = System.currentTimeMillis()
    }

    fun setStatus(text: String) {
        statusText = text
    }

    override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) {}

    override fun onSensorChanged(event: SensorEvent) {
        when (event.sensor?.type) {
            Sensor.TYPE_MAGNETIC_FIELD -> {
                System.arraycopy(event.values, 0, magnetometerReading, 0, 3)
            }
            Sensor.TYPE_ACCELEROMETER -> {
                sensorUnixTimestamp = System.currentTimeMillis()
                System.arraycopy(event.values, 0, accelerometerReading, 0, 3)
                deltaTime = (event.timestamp - lastSensorTime) / 1.0e9f
                lastSensorTime = event.timestamp
                processValues()
            }
        }
    }

    private fun processValues() {
        if (currentFile == null)
            return
        // Rotation matrix based on current readings from accelerometer and magnetometer.
        val rotationMatrix = FloatArray(16)
        SensorManager.getRotationMatrix(rotationMatrix, null, accelerometerReading,
            magnetometerReading)
        // Express the updated rotation matrix as three orientation angles in radians.
        val orientationAngles = floatArrayOf(0f, 0f, 0f, 1f)
        SensorManager.getOrientation(rotationMatrix, orientationAngles)

        meanOrientation.addValue(orientationAngles)
        val orientationDiff = vecSubtract(orientationAngles, meanOrientation.mean())
        meanOrientationDiff.addValue(orientationDiff)
        val projectionOrientation = vecProjectionToScalar(orientationDiff, meanOrientationDiff.mean())

        meanThermistor.addValue(currentThermistorValue.toFloat())
        val thermistorDiff = currentThermistorValue - meanThermistor.mean()

        // Vectors in a fixed reference frame where:
        // X is tangential to the ground at the device's current location and roughly points East
        // Y is tangential to the ground and points towards the magnetic North Pole
        // Z points towards the sky and is perpendicular to the ground.
        // Vectors in a fixed reference frame where y points east
        val accelerationVector = accelerometerReading

        meanAcceleration.addValue(accelerationVector)
        val acceleration = vecSubtract(accelerationVector, meanAcceleration.mean())

        val initializationSize = 20
        if (accelerationInitCount < initializationSize) {
            accelerationInitCount++
            return
        }

        // I tried double integrating acceleration to track position, but I wasn't able to get good values.
        // Acceleration produces a better periodic signal of breathing.
        meanPositionDiff.addValue(acceleration)
        val projectionPosition = vecProjectionToScalar(acceleration, meanPositionDiff.mean())

        sensorCount++
        val elapsed = (sensorUnixTimestamp - firstSensorUnixTimestamp) / 1000.0
        val sensorRate = sensorCount / elapsed
        val oxiParams = measureFragment.oxiParams
        val waveSize = measureFragment.wfSpO2Wave.size
        var toTake = 2
        if (waveSize > 20)
            toTake += waveSize - 20
        else if (toTake > waveSize)
            toTake = waveSize

        var value = 0
        for (idx in 1..toTake)
            value += measureFragment.wfSpO2Wave.take()
        if (toTake > 0)
            meanSpO2Wave = value / toTake.toFloat()

        val entry = floatArrayOf(projectionPosition, projectionOrientation,
            orientationAngles[0], orientationAngles[1], orientationAngles[2], thermistorDiff,
            oxiParams.spo2.toFloat(), oxiParams.pulseRate.toFloat(), oxiParams.pi.toFloat(), meanSpO2Wave)
        appendValues(lastSensorTime, entry)
        setStatus("sensorCount=$sensorCount\nelapsed=$elapsed\nsamples/sec=$sensorRate\nprojectedPosition=" +
                projectionPosition.toString() + "\nprojectedOrientation=" + projectionOrientation.toString() +
                "\nThermistor=" + thermistorDiff.toString() + "\nSpO2=${oxiParams.spo2}\n" +
                "PulseRate=${oxiParams.pulseRate}\nPi=${oxiParams.pi}\nmeanSpO2Wave=$meanSpO2Wave")
    }

    private fun appendValues(timestamp: Long, values: FloatArray) {
        if (firstSensorUnixTimestamp == 0L) {
            firstSensorUnixTimestamp = sensorUnixTimestamp
            currentFile?.writeLong(sensorUnixTimestamp)
        }
        currentFile?.writeLong(timestamp)
        for (value in values)
            currentFile?.writeFloat(value)
        currentFile?.flush()
    }

}