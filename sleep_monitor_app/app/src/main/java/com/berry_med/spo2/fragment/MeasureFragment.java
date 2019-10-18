package com.berry_med.spo2.fragment;

import android.app.Fragment;
import android.bluetooth.BluetoothDevice;
import android.content.Context;
import android.util.Log;
import com.berry_med.spo2.bluetooth.BluetoothUtils.BTConnectListener;
import java.util.ArrayList;
import java.util.Timer;
import com.berry_med.spo2.Adapter.BluetoothDeviceAdapter;
import com.berry_med.spo2.bluetooth.BluetoothUtils;
import com.berry_med.spo2.bluetooth.ParseRunnable;
import com.berry_med.spo2.bluetooth.ParseRunnable.OnDataChangeListener;
import com.berry_med.spo2.usbserial.USBCommManager;
import com.berry_med.spo2.usbserial.USBCommManager.USBCommListener;
import java.util.concurrent.LinkedBlockingQueue;

public class MeasureFragment extends Fragment implements BTConnectListener, OnDataChangeListener, USBCommListener {
    public static final String TAG = MeasureFragment.class.getSimpleName();
    public ArrayList<BluetoothDevice> arrayBluetoothDevices;
    public BluetoothDevice connectedDevice = null;
    public BluetoothDeviceAdapter mBluetoothDeviceAdapter;
    public BluetoothUtils mBtUtils = BluetoothUtils.getDefaultBluetoothUtils();
    public Context mContext;
    public ParseRunnable mParseRunnable;
    public LinkedBlockingQueue<Integer> wfSpO2Wave;
    private Timer mRecordTimer;

    public ParseRunnable.OxiParams getOxiParams() {
        if (MeasureFragment.this.mParseRunnable != null && MeasureFragment.this.mParseRunnable.getOxiParams().isParamsValid())
            return mParseRunnable.getOxiParams();
        else {
            ParseRunnable.OxiParams empty = new ParseRunnable.OxiParams();
            empty.update(0, 0, 0);
            return empty;
        }
    }

    public MeasureFragment() {
        this.wfSpO2Wave = new LinkedBlockingQueue<>();
        this.mBtUtils.setConnectListener(this);
    }

    public void connect(Context context) {
        this.mContext = context;
        this.arrayBluetoothDevices = new ArrayList<>();
        this.mBluetoothDeviceAdapter = new BluetoothDeviceAdapter(this.mContext, this.arrayBluetoothDevices, this.mBtUtils.mRssiMap);

        this.mBtUtils.registerBroadcastReceiver(this.mContext);
        this.mParseRunnable = new ParseRunnable(this);
        new Thread(this.mParseRunnable).start();

        MeasureFragment.this.arrayBluetoothDevices.clear();
        MeasureFragment.this.mBluetoothDeviceAdapter.notifyDataSetChanged();

        USBCommManager.getUSBManager(context).setListener(this);

        MeasureFragment.this.mBtUtils.startScan(true);
    }

    public Boolean isConnected() {
        return mBtUtils.mBLEService.mConnectionState > 0;
    }

    public void onSpO2ParamsChanged() {}

    public void onSpO2WaveChanged(int amp) {
        this.wfSpO2Wave.add(amp);
    }

    public void onPulseWaveDetected() {}

    public void onFoundDevice(BluetoothDevice device) {
        if (device.getName() == null) return;
        if (device.getName().equals("BerryMed")) {
            if (!this.arrayBluetoothDevices.contains(device) && arrayBluetoothDevices.isEmpty()) {
                Log.i(MeasureFragment.class.getName(), "Bluetooth device: " + device.getName() + "---" + device.getAddress());
                this.arrayBluetoothDevices.add(device);
                MeasureFragment.this.mBluetoothDeviceAdapter.notifyDataSetChanged();
                Log.i("Bluetooth", "Bluetooth device: connecting...");
                connectedDevice = arrayBluetoothDevices.get(0);
                mBtUtils.connect(MeasureFragment.this.mContext, connectedDevice);
            }
        }
    }

    public void onStopScan() {
        Log.i(MeasureFragment.class.getName(), "Stop Scan..." + this.arrayBluetoothDevices.size());
        // Keep scanning until device is found
        if (!isConnected())
            mBtUtils.startScan(true);
    }

    public void onStartScan() {
        Log.i(MeasureFragment.class.getName(), "Start Scan..." + this.arrayBluetoothDevices.size());
    }

    public void onUSBStateChanged(boolean isPlugged) {
        Log.i(TAG, "onUSBStateChanged: ----------" + isPlugged);
        getActivity().runOnUiThread(new Runnable() {
            public void run() {
            }
        });
        if (isPlugged) {
            this.mBtUtils.disconnect();
            getActivity().runOnUiThread(new Runnable() {
                public void run() {
                    MeasureFragment.this.startRecord();
                }
            });
        }
    }

    public void onConnected() {
        Log.i("Bluetooth", "Bluetooth device: connected");
        startRecord();
    }

    public void onDisconnected() {
        if (this.mRecordTimer != null) {
            this.mRecordTimer.cancel();
        }
        Log.d("Bluetooth", "Disconnected");
        // Reconnect
        connect(this.mContext);
    }

    public void onReceiveData(byte[] dat) {
        this.mParseRunnable.add(dat);
    }

    public void startRecord() {}

    public void onDestroy() {
        super.onDestroy();
        this.mBtUtils.unregisterBroadcastReceiver(this.mContext);
        this.mBtUtils.unbindService(this.mContext);
        if (this.mRecordTimer != null) {
            this.mRecordTimer.cancel();
        }
    }
}
