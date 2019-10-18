package com.example.positionmonitor

import android.view.View
import android.os.Bundle
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import android.content.pm.PackageManager
import androidx.core.app.ActivityCompat
import android.Manifest

import kotlin.system.exitProcess
import android.content.*
import android.os.Handler
import android.os.IBinder
import android.os.Looper


class MainActivity : AppCompatActivity() {
    private val INIT_PERMISSIONS = 200
    protected var positionIntent: Intent? = null

    var myService: PositionMonitorService? = null
    private var isBound = false
    private val myConnection = object : ServiceConnection {
        override fun onServiceConnected(className: ComponentName,
                                        service: IBinder) {
            val binder = service as PositionMonitorService.LocalBinder
            myService = binder.getService()
            isBound = true
        }

        override fun onServiceDisconnected(name: ComponentName) {
            isBound = false
        }
    }

    lateinit var mainHandler: Handler

    private val updateTextTask = object : Runnable {
        override fun run() {
            setStatus()
            mainHandler.postDelayed(this, 200)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        mainHandler = Handler(Looper.getMainLooper())

        if (ActivityCompat.checkSelfPermission(this,
                Manifest.permission.BLUETOOTH) != PackageManager.PERMISSION_GRANTED ||
            ActivityCompat.checkSelfPermission(this,
                Manifest.permission.BLUETOOTH_ADMIN) != PackageManager.PERMISSION_GRANTED ||
            ActivityCompat.checkSelfPermission(this,
                Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this,
                arrayOf(
                    Manifest.permission.BLUETOOTH,
                    Manifest.permission.BLUETOOTH_ADMIN,
                    Manifest.permission.ACCESS_FINE_LOCATION),
                INIT_PERMISSIONS)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int,
                                            permissions: Array<String>, grantResults: IntArray) {
        when (requestCode) {
            INIT_PERMISSIONS -> {
                // If request is cancelled, the result arrays are empty.
                if (grantResults.isNotEmpty() &&
                    grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                    // permission was granted
                } else {
                    // permission denied
                    quit()
                }
                return
            }
            else -> {
                // Ignore all other requests.
            }
        }
    }

    fun startRecording(view: View) {
        positionIntent = Intent(this, PositionMonitorService::class.java)
        startService(positionIntent)
        bindService(positionIntent, myConnection, Context.BIND_AUTO_CREATE)
    }

    fun stopRecording(view: View) {
        if (positionIntent != null)
            stopService(positionIntent)
        positionIntent = null
        quit()
    }

    fun quit() {
        finish()
        exitProcess(0)
    }

    fun setStatus() {
        val text = myService?.statusText
        val status = findViewById(R.id.statusText) as TextView
        status.setText(text)
    }

    override fun onResume() {
        super.onResume()
        mainHandler.post(updateTextTask)
    }

    override fun onPause() {
        super.onPause()
        mainHandler.removeCallbacks(updateTextTask)
    }

}

