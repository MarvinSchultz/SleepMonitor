package com.hoho.android.usbserial.driver;

import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import java.io.IOException;

abstract class CommonUsbSerialPort implements UsbSerialPort {
    public static final int DEFAULT_READ_BUFFER_SIZE = 16384;
    public static final int DEFAULT_WRITE_BUFFER_SIZE = 16384;
    protected UsbDeviceConnection mConnection = null;
    protected final UsbDevice mDevice;
    protected final int mPortNumber;
    protected byte[] mReadBuffer;
    protected final Object mReadBufferLock = new Object();
    protected byte[] mWriteBuffer;
    protected final Object mWriteBufferLock = new Object();

    public abstract void close() throws IOException;

    public abstract boolean getCD() throws IOException;

    public abstract boolean getCTS() throws IOException;

    public abstract boolean getDSR() throws IOException;

    public abstract boolean getDTR() throws IOException;

    public abstract boolean getRI() throws IOException;

    public abstract boolean getRTS() throws IOException;

    public abstract void open(UsbDeviceConnection usbDeviceConnection) throws IOException;

    public abstract int read(byte[] bArr, int i) throws IOException;

    public abstract void setDTR(boolean z) throws IOException;

    public abstract void setParameters(int i, int i2, int i3, int i4) throws IOException;

    public abstract void setRTS(boolean z) throws IOException;

    public abstract int write(byte[] bArr, int i) throws IOException;

    public CommonUsbSerialPort(UsbDevice device, int portNumber) {
        this.mDevice = device;
        this.mPortNumber = portNumber;
        this.mReadBuffer = new byte[16384];
        this.mWriteBuffer = new byte[16384];
    }

    public String toString() {
        return String.format("<%s device_name=%s device_id=%s port_number=%s>", new Object[]{getClass().getSimpleName(), this.mDevice.getDeviceName(), Integer.valueOf(this.mDevice.getDeviceId()), Integer.valueOf(this.mPortNumber)});
    }

    public final UsbDevice getDevice() {
        return this.mDevice;
    }

    public int getPortNumber() {
        return this.mPortNumber;
    }

    public String getSerial() {
        return this.mConnection.getSerial();
    }

    public final void setReadBufferSize(int bufferSize) {
        synchronized (this.mReadBufferLock) {
            if (bufferSize != this.mReadBuffer.length) {
                this.mReadBuffer = new byte[bufferSize];
            }
        }
    }

    public final void setWriteBufferSize(int bufferSize) {
        synchronized (this.mWriteBufferLock) {
            if (bufferSize != this.mWriteBuffer.length) {
                this.mWriteBuffer = new byte[bufferSize];
            }
        }
    }

    public boolean purgeHwBuffers(boolean flushReadBuffers, boolean flushWriteBuffers) throws IOException {
        return !flushReadBuffers && !flushWriteBuffers;
    }
}
