package com.berry_med.spo2.bluetooth;

import android.annotation.TargetApi;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothAdapter.LeScanCallback;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattService;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.os.Build.VERSION;
import android.os.Handler;
import android.os.IBinder;
import android.os.Message;
import android.util.Log;
import com.berry_med.spo2.bluetooth.BluetoothLeService.LocalBinder;
import java.util.HashMap;

import static android.content.Context.BIND_AUTO_CREATE;

@TargetApi(18)
public class BluetoothUtils {
    private static BluetoothUtils mBtUtils = null;
    private final int BLUETOOTH_SEARCH_TIME;
    /* access modifiers changed from: private */
    public final String TAG;
    final Runnable cancelRunnable;
    /* access modifiers changed from: private */
    public BluetoothDevice curDevice;
    private Intent gattServiceIntent;
    /* access modifiers changed from: private */
    public boolean isScanning;
    private boolean isServiceBinded;
    private final LeScanCallback leScanCallback;
    /* access modifiers changed from: private */
    public BluetoothLeService mBLEService;
    private BluetoothChatService mBluetoothChatService;
    private BluetoothAdapter mBtAdapter;
    /* access modifiers changed from: private */
    public BTConnectListener mConnectListener;
    private BroadcastReceiver mGattUpdateReceiver;
    Handler mHandler;
    public HashMap<String, Integer> mRssiMap;
    /* access modifiers changed from: private */
    public ServiceConnection mServiceConnection;
    final Handler postHandler;

    public interface BTConnectListener {
        void onConnected();

        void onDisconnected();

        void onFoundDevice(BluetoothDevice bluetoothDevice);

        void onReceiveData(byte[] bArr);

        void onStartScan();

        void onStopScan();
    }

    private BluetoothUtils() {
        this.TAG = getClass().getName();
        this.BLUETOOTH_SEARCH_TIME = 8000;
        this.isScanning = false;
        this.mBtAdapter = null;
        this.mBLEService = null;
        this.curDevice = null;
        this.mRssiMap = new HashMap<>();
        this.cancelRunnable = new Runnable() {
            public void run() {
                BluetoothUtils.this.startScan(false);
            }
        };
        this.postHandler = new Handler();
        this.mServiceConnection = new ServiceConnection() {
            public void onServiceConnected(ComponentName componentName, IBinder service) {
                BluetoothUtils.this.mBLEService = ((LocalBinder) service).getService();
                if (!BluetoothUtils.this.mBLEService.initialize()) {
                    Log.e(BluetoothUtils.this.TAG, "Unable to initialize Bluetooth");
                }
                Log.w(BluetoothUtils.this.TAG, "-------------------connect--------------------------");
                BluetoothUtils.this.mBLEService.connect(BluetoothUtils.this.curDevice.getAddress());
            }

            public void onServiceDisconnected(ComponentName componentName) {
                BluetoothUtils.this.mBLEService = null;
                BluetoothUtils.this.mServiceConnection = null;
                BluetoothUtils.this.curDevice = null;
            }
        };
        this.mHandler = new Handler() {
            public void handleMessage(Message msg) {
                super.handleMessage(msg);
                switch (msg.what) {
                    case 3:
                        switch (msg.arg1) {
                            case 0:
                                BluetoothUtils.this.mConnectListener.onDisconnected();
                                return;
                            case 2:
                                Log.i(BluetoothUtils.this.TAG, "handleMessage: Connecting");
                                return;
                            case 3:
                                Log.i(BluetoothUtils.this.TAG, "handleMessage: Connected");
                                BluetoothUtils.this.mConnectListener.onConnected();
                                return;
                            default:
                                return;
                        }
                    case 9:
                        BluetoothUtils.this.mConnectListener.onReceiveData((byte[]) msg.obj);
                        return;
                    default:
                        return;
                }
            }
        };
        this.isServiceBinded = false;
        this.mGattUpdateReceiver = new BroadcastReceiver() {
            public void onReceive(Context context, Intent intent) {
                String action = intent.getAction();
                if (BluetoothLeService.ACTION_GATT_CONNECTED.equals(action)) {
                    BluetoothUtils.this.mConnectListener.onConnected();
                    Log.i(BluetoothUtils.this.TAG, "Bluetooth Connected...");
                } else if (BluetoothLeService.ACTION_GATT_DISCONNECTED.equals(action)) {
                    BluetoothUtils.this.mBLEService.disconnect();
                    context.unbindService(BluetoothUtils.this.mServiceConnection);
                    BluetoothUtils.this.mConnectListener.onDisconnected();
                    Log.i(BluetoothUtils.this.TAG, "Bluetooth Disonnected...");
                } else if (BluetoothLeService.ACTION_GATT_SERVICES_DISCOVERED.equals(action)) {
                    for (BluetoothGattService service : BluetoothUtils.this.mBLEService.getSupportedGattServices()) {
                        if (service.getUuid().equals(Const.UUID_SERVICE_DATA)) {
                            for (BluetoothGattCharacteristic ch : service.getCharacteristics()) {
                                if (ch.getUuid().equals(Const.UUID_CHARACTER_RECEIVE)) {
                                    BluetoothUtils.this.mBLEService.setCharacteristicNotification(ch, true);
                                }
                            }
                        }
                    }
                } else if (BluetoothLeService.ACTION_SPO2_DATA_AVAILABLE.equals(action)) {
                    BluetoothUtils.this.mConnectListener.onReceiveData(intent.getByteArrayExtra(BluetoothLeService.EXTRA_DATA));
                } else if ("android.bluetooth.device.action.FOUND".equals(action)) {
                    BluetoothDevice device = (BluetoothDevice) intent.getParcelableExtra("android.bluetooth.device.extra.DEVICE");
                    BluetoothUtils.this.mConnectListener.onFoundDevice(device);
                    BluetoothUtils.this.mRssiMap.put(device.getAddress(), Integer.valueOf(intent.getExtras().getShort("android.bluetooth.device.extra.RSSI")));
                    Log.i(BluetoothUtils.this.TAG, "onReceive: GATT:" + device.getName());
                } else if ("android.bluetooth.adapter.action.DISCOVERY_FINISHED".equals(action)) {
                    BluetoothUtils.this.isScanning = false;
                    BluetoothUtils.this.mConnectListener.onStopScan();
                } else if ("android.bluetooth.adapter.action.DISCOVERY_STARTED".equals(action)) {
                    BluetoothUtils.this.isScanning = true;
                    BluetoothUtils.this.mConnectListener.onStartScan();
                }
            }
        };
        this.mBtAdapter = BluetoothAdapter.getDefaultAdapter();
        if (VERSION.SDK_INT >= 18) {
            this.leScanCallback = new LeScanCallback() {
                public void onLeScan(BluetoothDevice device, int rssi, byte[] scanRecord) {
                    BluetoothUtils.this.mConnectListener.onFoundDevice(device);
                    BluetoothUtils.this.mRssiMap.put(device.getAddress(), Integer.valueOf(rssi));
                }
            };
        } else {
            this.leScanCallback = null;
        }
    }

    public static BluetoothUtils getDefaultBluetoothUtils() {
        if (mBtUtils == null) {
            mBtUtils = new BluetoothUtils();
        }
        return mBtUtils;
    }

    public void enable() {
        if (!this.mBtAdapter.isEnabled()) {
            this.mBtAdapter.enable();
        }
    }

    @TargetApi(21)
    public void startScan(boolean b) {
        if (b) {
            this.mRssiMap.clear();
            this.mConnectListener.onStartScan();
            if (VERSION.SDK_INT >= 18) {
                if (this.isScanning) {
                    this.postHandler.removeCallbacks(this.cancelRunnable);
                    this.mBtAdapter.stopLeScan(this.leScanCallback);
                }
                this.mBtAdapter.startLeScan(this.leScanCallback);
                this.postHandler.postDelayed(this.cancelRunnable, 8000);
            } else {
                this.mBtAdapter.startDiscovery();
            }
            this.isScanning = true;
            return;
        }
        if (VERSION.SDK_INT >= 18) {
            this.postHandler.removeCallbacks(this.cancelRunnable);
            this.mBtAdapter.stopLeScan(this.leScanCallback);
        } else {
            this.mBtAdapter.cancelDiscovery();
        }
        this.mConnectListener.onStopScan();
        this.isScanning = false;
    }

    public void connect(Context context, BluetoothDevice device) {
        this.curDevice = device;
        if (this.curDevice.getAddress().toLowerCase().startsWith("00:a0:50")) {
            bindService(context);
            return;
        }
        this.mBluetoothChatService = new BluetoothChatService(context, this.mHandler);
        this.mBluetoothChatService.connect(device, true);
    }

    public void disconnect() {
        if (this.mBLEService != null) {
            this.mBLEService.disconnect();
        }
    }

    public void bindService(Context context) {
        this.gattServiceIntent = new Intent(context, BluetoothLeService.class);
        context.bindService(this.gattServiceIntent, this.mServiceConnection, BIND_AUTO_CREATE);
        this.isServiceBinded = true;
    }

    public void unbindService(Context context) {
        if (this.isServiceBinded) {
            context.unbindService(this.mServiceConnection);
            this.isServiceBinded = false;
        }
    }

    private static IntentFilter makeGattUpdateIntentFilter() {
        IntentFilter intentFilter = new IntentFilter();
        intentFilter.addAction("android.bluetooth.device.action.FOUND");
        intentFilter.addAction("android.bluetooth.device.action.BOND_STATE_CHANGED");
        intentFilter.addAction("android.bluetooth.adapter.action.DISCOVERY_FINISHED");
        intentFilter.addAction("android.bluetooth.adapter.action.DISCOVERY_STARTED");
        intentFilter.addAction(BluetoothLeService.ACTION_GATT_CONNECTED);
        intentFilter.addAction(BluetoothLeService.ACTION_GATT_DISCONNECTED);
        intentFilter.addAction(BluetoothLeService.ACTION_GATT_SERVICES_DISCOVERED);
        intentFilter.addAction(BluetoothLeService.ACTION_DATA_AVAILABLE);
        intentFilter.addAction(BluetoothLeService.ACTION_SPO2_DATA_AVAILABLE);
        return intentFilter;
    }

    public void registerBroadcastReceiver(Context context) {
        context.registerReceiver(this.mGattUpdateReceiver, makeGattUpdateIntentFilter());
    }

    public void unregisterBroadcastReceiver(Context context) {
        context.unregisterReceiver(this.mGattUpdateReceiver);
    }

    public void setConnectListener(BTConnectListener listener) {
        this.mConnectListener = listener;
    }
}
