package com.berry_med.spo2.usbserial;

import android.content.Context;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbManager;
import com.hoho.android.usbserial.driver.UsbSerialDriver;
import com.hoho.android.usbserial.driver.UsbSerialPort;
import com.hoho.android.usbserial.driver.UsbSerialProber;
import java.io.IOException;
import java.util.List;

public class USBCommManager {
    private static Context mContext;
    private static USBCommManager mUSBCommManager;
    public String TAG = USBCommManager.class.getSimpleName();
    /* access modifiers changed from: private */
    public List<UsbSerialDriver> availableDrivers;
    /* access modifiers changed from: private */
    public boolean mIsPlugged;
    /* access modifiers changed from: private */
    public USBCommListener mListener;
    /* access modifiers changed from: private */
    public UsbSerialPort mSerialPort;
    /* access modifiers changed from: private */
    //public UsbManager mUsbManager = ((UsbManager) mContext.getSystemService("usb"));
    public UsbManager mUsbManager = ((UsbManager) mContext.getSystemService(Context.USB_SERVICE));


    public interface USBCommListener {
        void onReceiveData(byte[] bArr);

        void onUSBStateChanged(boolean z);
    }

    private USBCommManager() {
    }

    public static USBCommManager getUSBManager(Context context) {
        if (mUSBCommManager == null) {
            mContext = context;
            mUSBCommManager = new USBCommManager();
        }
        return mUSBCommManager;
    }

    public void setListener(USBCommListener listener) {
        this.mListener = listener;
    }

    public void initConnection() {
        this.availableDrivers = UsbSerialProber.getDefaultProber().findAllDrivers(this.mUsbManager);
        if (this.availableDrivers.size() > 0) {
            buildConnection();
            this.mIsPlugged = true;
            this.mListener.onUSBStateChanged(this.mIsPlugged);
            startScan();
        }
    }

    private void startScan() {
        new Thread(new Runnable() {
            public void run() {
                while (USBCommManager.this.mIsPlugged) {
                    USBCommManager.this.availableDrivers = UsbSerialProber.getDefaultProber().findAllDrivers(USBCommManager.this.mUsbManager);
                    if (USBCommManager.this.availableDrivers.size() == 0) {
                        USBCommManager.this.mIsPlugged = false;
                    }
                    try {
                        Thread.sleep(100);
                    } catch (InterruptedException e) {
                        e.printStackTrace();
                    }
                }
                USBCommManager.this.mListener.onUSBStateChanged(USBCommManager.this.mIsPlugged);
            }
        }).start();
    }

    private void buildConnection() {
        UsbSerialDriver driver = (UsbSerialDriver) this.availableDrivers.get(0);
        UsbDeviceConnection connection = this.mUsbManager.openDevice(driver.getDevice());
        this.mSerialPort = (UsbSerialPort) driver.getPorts().get(0);
        try {
            this.mSerialPort.open(connection);
        } catch (IOException e) {
            e.printStackTrace();
        }
        new Thread(new Runnable() {
            public void run() {
                while (USBCommManager.this.mIsPlugged) {
                    try {
                        byte[] buffer = new byte[32];
                        int numBytesRead = USBCommManager.this.mSerialPort.read(buffer, 100);
                        if (numBytesRead > 0) {
                            byte[] dat = new byte[numBytesRead];
                            System.arraycopy(buffer, 0, dat, 0, numBytesRead);
                            USBCommManager.this.mListener.onReceiveData(dat);
                        }
                    } catch (IOException e) {
                    }
                }
            }
        }).start();
    }

    public boolean isPlugged() {
        return this.mIsPlugged;
    }
}
