package com.berry_med.spo2.bluetooth;

import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothServerSocket;
import android.bluetooth.BluetoothSocket;
import android.content.Context;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.util.Log;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.UUID;

public class BluetoothChatService {

    /* renamed from: D */
    private static final boolean f25D = true;
    /* access modifiers changed from: private */
    public static final UUID MY_UUID_INSECURE = UUID.fromString("8ce255c0-200a-11e0-ac64-0800200c9a66");
    /* access modifiers changed from: private */
    public static final UUID MY_UUID_SPP = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB");
    private static final String NAME_INSECURE = "BluetoothChatInsecure";
    private static final String NAME_SECURE = "BluetoothChatSecure";
    public static final int STATE_CONNECTED = 3;
    public static final int STATE_CONNECTING = 2;
    public static final int STATE_LISTEN = 1;
    public static final int STATE_NONE = 0;
    private static final String TAG = "BluetoothChatService";
    /* access modifiers changed from: private */
    public final BluetoothAdapter mAdapter = BluetoothAdapter.getDefaultAdapter();
    /* access modifiers changed from: private */
    public ConnectThread mConnectThread;
    private ConnectedThread mConnectedThread;
    private Context mContext;
    /* access modifiers changed from: private */
    public final Handler mHandler;
    private AcceptThread mInsecureAcceptThread;
    private AcceptThread mSecureAcceptThread;
    /* access modifiers changed from: private */
    public int mState = 0;

    private class AcceptThread extends Thread {
        private String mSocketType;
        private final BluetoothServerSocket mmServerSocket;

        @SuppressLint({"NewApi"})
        public AcceptThread(boolean secure) {
            BluetoothServerSocket tmp = null;
            this.mSocketType = secure ? "Secure" : "Insecure";
            if (secure) {
                try {
                    tmp = BluetoothChatService.this.mAdapter.listenUsingRfcommWithServiceRecord(BluetoothChatService.NAME_SECURE, BluetoothChatService.MY_UUID_SPP);
                } catch (IOException e) {
                    Log.e(BluetoothChatService.TAG, "Socket Type: " + this.mSocketType + "listen() failed", e);
                }
            } else {
                try {
                    tmp = BluetoothChatService.this.mAdapter.listenUsingInsecureRfcommWithServiceRecord(BluetoothChatService.NAME_INSECURE, BluetoothChatService.MY_UUID_INSECURE);
                } catch (IOException e) {
                    Log.e(BluetoothChatService.TAG, "Socket Type: " + this.mSocketType + "listen() failed", e);
                }
            }
            this.mmServerSocket = tmp;
        }

        public void run() {
            Log.d(BluetoothChatService.TAG, "Socket Type: " + this.mSocketType + "BEGIN mAcceptThread" + this);
            setName("AcceptThread" + this.mSocketType);
            while (BluetoothChatService.this.mState != 3) {
                try {
                    BluetoothSocket socket = this.mmServerSocket.accept();
                    if (socket != null) {
                        synchronized (BluetoothChatService.this) {
                            switch (BluetoothChatService.this.mState) {
                                case 0:
                                case 3:
                                    try {
                                        socket.close();
                                        break;
                                    } catch (IOException e) {
                                        Log.e(BluetoothChatService.TAG, "Could not close unwanted socket", e);
                                        break;
                                    }
                                case 1:
                                case 2:
                                    BluetoothChatService.this.connected(socket, socket.getRemoteDevice(), this.mSocketType);
                                    break;
                            }
                        }
                    }
                } catch (IOException e2) {
                    Log.e(BluetoothChatService.TAG, "Socket Type: " + this.mSocketType + "accept() failed", e2);
                }
            }
            Log.i(BluetoothChatService.TAG, "END mAcceptThread, socket Type: " + this.mSocketType);
            return;
        }

        public void cancel() {
            Log.d(BluetoothChatService.TAG, "Socket Type" + this.mSocketType + "cancel " + this);
            try {
                this.mmServerSocket.close();
            } catch (IOException e) {
                Log.e(BluetoothChatService.TAG, "Socket Type" + this.mSocketType + "close() of server failed", e);
            }
        }
    }

    private class ConnectThread extends Thread {
        private String mSocketType;
        private final BluetoothDevice mmDevice;
        private final BluetoothSocket mmSocket;

        @SuppressLint({"NewApi"})
        public ConnectThread(BluetoothDevice device, boolean secure) {
            this.mmDevice = device;
            BluetoothSocket tmp = null;
            this.mSocketType = secure ? "Secure" : "Insecure";
            if (secure) {
                try {
                    tmp = device.createRfcommSocketToServiceRecord(BluetoothChatService.MY_UUID_SPP);
                } catch (IOException e) {
                    Log.e(BluetoothChatService.TAG, "Socket Type: " + this.mSocketType + "create() failed", e);
                }
            } else {
                try {
                    tmp = device.createInsecureRfcommSocketToServiceRecord(BluetoothChatService.MY_UUID_INSECURE);
                } catch (IOException e) {
                    Log.e(BluetoothChatService.TAG, "Socket Type: " + this.mSocketType + "create() failed", e);
                }
            }
            this.mmSocket = tmp;
        }

        public void run() {
            Log.i(BluetoothChatService.TAG, "BEGIN mConnectThread SocketType:" + this.mSocketType);
            setName("ConnectThread" + this.mSocketType);
            BluetoothChatService.this.mAdapter.cancelDiscovery();
            try {
                this.mmSocket.connect();
                synchronized (BluetoothChatService.this) {
                    BluetoothChatService.this.mConnectThread = null;
                }
                BluetoothChatService.this.connected(this.mmSocket, this.mmDevice, this.mSocketType);
            } catch (IOException e) {
                try {
                    this.mmSocket.close();
                } catch (IOException e2) {
                    Log.e(BluetoothChatService.TAG, "unable to close() " + this.mSocketType + " socket during connection failure", e2);
                }
                BluetoothChatService.this.connectionFailed();
            }
        }

        public void cancel() {
            try {
                this.mmSocket.close();
            } catch (IOException e) {
                Log.e(BluetoothChatService.TAG, "close() of connect " + this.mSocketType + " socket failed", e);
            }
        }
    }

    private class ConnectedThread extends Thread {
        private final InputStream mmInStream;
        private final OutputStream mmOutStream;
        private final BluetoothSocket mmSocket;

        public ConnectedThread(BluetoothSocket socket, String socketType) {
            Log.d(BluetoothChatService.TAG, "create ConnectedThread: " + socketType);
            this.mmSocket = socket;
            InputStream tmpIn = null;
            OutputStream tmpOut = null;
            try {
                tmpIn = socket.getInputStream();
                tmpOut = socket.getOutputStream();
            } catch (IOException e) {
                Log.e(BluetoothChatService.TAG, "temp sockets not created", e);
            }
            this.mmInStream = tmpIn;
            this.mmOutStream = tmpOut;
        }

        public void run() {
            Log.i(BluetoothChatService.TAG, "BEGIN mConnectedThread");
            try {
                Thread.sleep(100);
            } catch (InterruptedException e1) {
                e1.printStackTrace();
            }
            while (BluetoothChatService.this.mState == 3) {
                try {
                    byte[] buffer = new byte[256];
                    int bytes = this.mmInStream.read(buffer);
                    if (bytes > 0) {
                        byte[] dat = new byte[bytes];
                        System.arraycopy(buffer, 0, dat, 0, bytes);
                        BluetoothChatService.this.mHandler.obtainMessage(9, dat).sendToTarget();
                    }
                    try {
                        Thread.sleep(40);
                    } catch (InterruptedException e) {
                        e.printStackTrace();
                    }
                } catch (IOException e2) {
                    Log.e(BluetoothChatService.TAG, "disconnected", e2);
                    BluetoothChatService.this.connectionLost();
                    BluetoothChatService.this.start();
                    return;
                }
            }
        }

        public void write(byte[] buffer) {
            try {
                this.mmOutStream.write(buffer);
                BluetoothChatService.this.mHandler.obtainMessage(7, -1, -1, buffer).sendToTarget();
            } catch (IOException e) {
                Log.e(BluetoothChatService.TAG, "Exception during write", e);
            }
        }

        public void cancel() {
            try {
                this.mmSocket.close();
            } catch (IOException e) {
                Log.e(BluetoothChatService.TAG, "close() of connect socket failed", e);
            }
        }
    }

    public BluetoothChatService(Context context, Handler handler) {
        this.mHandler = handler;
        this.mContext = context;
    }

    private synchronized void setState(int state) {
        Log.d(TAG, "setState() " + this.mState + " -> " + state);
        this.mState = state;
        this.mHandler.obtainMessage(3, state, -1).sendToTarget();
    }

    public synchronized int getState() {
        return this.mState;
    }

    public synchronized void start() {
        Log.d(TAG, "start");
        if (this.mConnectThread != null) {
            this.mConnectThread.cancel();
            this.mConnectThread = null;
        }
        if (this.mConnectedThread != null) {
            this.mConnectedThread.cancel();
            this.mConnectedThread = null;
        }
        setState(1);
        if (this.mSecureAcceptThread == null) {
            this.mSecureAcceptThread = new AcceptThread(f25D);
            this.mSecureAcceptThread.start();
        }
        if (this.mInsecureAcceptThread == null) {
            this.mInsecureAcceptThread = new AcceptThread(false);
            this.mInsecureAcceptThread.start();
        }
    }

    public synchronized void connect(BluetoothDevice device, boolean secure) {
        Log.d(TAG, "connect to: " + device);
        if (this.mState == 2 && this.mConnectThread != null) {
            this.mConnectThread.cancel();
            this.mConnectThread = null;
        }
        if (this.mConnectedThread != null) {
            this.mConnectedThread.cancel();
            this.mConnectedThread = null;
        }
        this.mConnectThread = new ConnectThread(device, secure);
        this.mConnectThread.start();
        setState(2);
    }

    public synchronized void connected(BluetoothSocket socket, BluetoothDevice device, String socketType) {
        Log.d(TAG, "connected, Socket Type:" + socketType);
        if (this.mConnectThread != null) {
            this.mConnectThread.cancel();
            this.mConnectThread = null;
        }
        if (this.mConnectedThread != null) {
            this.mConnectedThread.cancel();
            this.mConnectedThread = null;
        }
        if (this.mSecureAcceptThread != null) {
            this.mSecureAcceptThread.cancel();
            this.mSecureAcceptThread = null;
        }
        if (this.mInsecureAcceptThread != null) {
            this.mInsecureAcceptThread.cancel();
            this.mInsecureAcceptThread = null;
        }
        this.mConnectedThread = new ConnectedThread(socket, socketType);
        this.mConnectedThread.start();
        Message msg = this.mHandler.obtainMessage(4);
        Bundle bundle = new Bundle();
        bundle.putString(Const.DEVICE_NAME, device.getName());
        msg.setData(bundle);
        this.mHandler.sendMessage(msg);
        setState(3);
    }

    public synchronized void stop() {
        Log.d(TAG, "stop");
        if (this.mConnectThread != null) {
            this.mConnectThread.cancel();
            this.mConnectThread = null;
        }
        if (this.mConnectedThread != null) {
            this.mConnectedThread.cancel();
            this.mConnectedThread = null;
        }
        if (this.mSecureAcceptThread != null) {
            this.mSecureAcceptThread.cancel();
            this.mSecureAcceptThread = null;
        }
        if (this.mInsecureAcceptThread != null) {
            this.mInsecureAcceptThread.cancel();
            this.mInsecureAcceptThread = null;
        }
        setState(0);
    }

    public void write(byte[] out) {
        synchronized (this) {
            if (this.mState == 3) {
                ConnectedThread r = this.mConnectedThread;
                r.write(out);
            }
        }
    }

    /* access modifiers changed from: private */
    public void connectionFailed() {
        Message msg = this.mHandler.obtainMessage(5);
        Bundle bundle = new Bundle();
        bundle.putString(Const.TOAST, "Unable to connect device");
        msg.setData(bundle);
        this.mHandler.sendMessage(msg);
        this.mHandler.obtainMessage(6).sendToTarget();
        start();
    }

    /* access modifiers changed from: private */
    public void connectionLost() {
        Message msg = this.mHandler.obtainMessage(5);
        Bundle bundle = new Bundle();
        bundle.putString(Const.TOAST, "Device connection was lost");
        msg.setData(bundle);
        this.mHandler.sendMessage(msg);
        start();
    }
}
