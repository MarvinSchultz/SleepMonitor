package com.hoho.android.usbserial.driver;

import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbEndpoint;
import android.hardware.usb.UsbInterface;
import android.hardware.usb.UsbRequest;
import android.os.Build.VERSION;
import android.util.Log;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class CdcAcmSerialDriver implements UsbSerialDriver {
    /* access modifiers changed from: private */
    public final String TAG = CdcAcmSerialDriver.class.getSimpleName();
    private final UsbDevice mDevice;
    private final UsbSerialPort mPort;

    class CdcAcmSerialPort extends CommonUsbSerialPort {
        private static final int GET_LINE_CODING = 33;
        private static final int SEND_BREAK = 35;
        private static final int SET_CONTROL_LINE_STATE = 34;
        private static final int SET_LINE_CODING = 32;
        private static final int USB_RECIP_INTERFACE = 1;
        private static final int USB_RT_ACM = 33;
        private UsbEndpoint mControlEndpoint;
        private UsbInterface mControlInterface;
        private UsbInterface mDataInterface;
        private boolean mDtr = false;
        private final boolean mEnableAsyncReads;
        private UsbEndpoint mReadEndpoint;
        private boolean mRts = false;
        private UsbEndpoint mWriteEndpoint;
        final /* synthetic */ CdcAcmSerialDriver this$0;

        public CdcAcmSerialPort(CdcAcmSerialDriver this$02, UsbDevice device, int portNumber) {
            super(device, portNumber);
            boolean z = false;
            this.this$0 = this$02;
            if (VERSION.SDK_INT >= 17) {
                z = true;
            }
            this.mEnableAsyncReads = z;
        }

        public UsbSerialDriver getDriver() {
            return this.this$0;
        }

        /* JADX INFO: finally extract failed */
        public void open(UsbDeviceConnection connection) throws IOException {
            if (this.mConnection != null) {
                throw new IOException("Already open");
            }
            this.mConnection = connection;
            try {
                Log.d(this.this$0.TAG, "claiming interfaces, count=" + this.mDevice.getInterfaceCount());
                this.mControlInterface = this.mDevice.getInterface(0);
                Log.d(this.this$0.TAG, "Control iface=" + this.mControlInterface);
                if (!this.mConnection.claimInterface(this.mControlInterface, true)) {
                    throw new IOException("Could not claim control interface.");
                }
                this.mControlEndpoint = this.mControlInterface.getEndpoint(0);
                Log.d(this.this$0.TAG, "Control endpoint direction: " + this.mControlEndpoint.getDirection());
                Log.d(this.this$0.TAG, "Claiming data interface.");
                this.mDataInterface = this.mDevice.getInterface(1);
                Log.d(this.this$0.TAG, "data iface=" + this.mDataInterface);
                if (!this.mConnection.claimInterface(this.mDataInterface, true)) {
                    throw new IOException("Could not claim data interface.");
                }
                this.mReadEndpoint = this.mDataInterface.getEndpoint(1);
                Log.d(this.this$0.TAG, "Read endpoint direction: " + this.mReadEndpoint.getDirection());
                this.mWriteEndpoint = this.mDataInterface.getEndpoint(0);
                Log.d(this.this$0.TAG, "Write endpoint direction: " + this.mWriteEndpoint.getDirection());
                if (this.mEnableAsyncReads) {
                    Log.d(this.this$0.TAG, "Async reads enabled");
                } else {
                    Log.d(this.this$0.TAG, "Async reads disabled.");
                }
                if (1 == 0) {
                    this.mConnection = null;
                }
            } catch (Throwable th) {
                if (0 == 0) {
                    this.mConnection = null;
                }
                throw th;
            }
        }

        private int sendAcmControlMessage(int request, int value, byte[] buf) {
            return this.mConnection.controlTransfer(33, request, value, 0, buf, buf != null ? buf.length : 0, 5000);
        }

        public void close() throws IOException {
            if (this.mConnection == null) {
                throw new IOException("Already closed");
            }
            this.mConnection.close();
            this.mConnection = null;
        }

        public int read(byte[] dest, int timeoutMillis) throws IOException {
            if (this.mEnableAsyncReads) {
                UsbRequest request = new UsbRequest();
                try {
                    request.initialize(this.mConnection, this.mReadEndpoint);
                    ByteBuffer buf = ByteBuffer.wrap(dest);
                    if (!request.queue(buf, dest.length)) {
                        throw new IOException("Error queueing request.");
                    } else if (this.mConnection.requestWait() == null) {
                        throw new IOException("Null response");
                    } else {
                        int nread = buf.position();
                        if (nread > 0) {
                            return nread;
                        }
                        request.close();
                        return 0;
                    }
                } finally {
                    request.close();
                }
            } else {
                synchronized (this.mReadBufferLock) {
                    int numBytesRead = this.mConnection.bulkTransfer(this.mReadEndpoint, this.mReadBuffer, Math.min(dest.length, this.mReadBuffer.length), timeoutMillis);
                    if (numBytesRead >= 0) {
                        System.arraycopy(this.mReadBuffer, 0, dest, 0, numBytesRead);
                        return numBytesRead;
                    } else if (timeoutMillis == Integer.MAX_VALUE) {
                        return -1;
                    } else {
                        return 0;
                    }
                }
            }
        }

        public int write(byte[] src, int timeoutMillis) throws IOException {
            int writeLength;
            byte[] writeBuffer;
            int amtWritten;
            int offset = 0;
            while (offset < src.length) {
                synchronized (this.mWriteBufferLock) {
                    writeLength = Math.min(src.length - offset, this.mWriteBuffer.length);
                    if (offset == 0) {
                        writeBuffer = src;
                    } else {
                        System.arraycopy(src, offset, this.mWriteBuffer, 0, writeLength);
                        writeBuffer = this.mWriteBuffer;
                    }
                    amtWritten = this.mConnection.bulkTransfer(this.mWriteEndpoint, writeBuffer, writeLength, timeoutMillis);
                }
                if (amtWritten <= 0) {
                    throw new IOException("Error writing " + writeLength + " bytes at offset " + offset + " length=" + src.length);
                }
                Log.d(this.this$0.TAG, "Wrote amt=" + amtWritten + " attempted=" + writeLength);
                offset += amtWritten;
            }
            return offset;
        }

        public void setParameters(int baudRate, int dataBits, int stopBits, int parity) {
            byte stopBitsByte;
            byte parityBitesByte;
            switch (stopBits) {
                case 1:
                    stopBitsByte = 0;
                    break;
                case 2:
                    stopBitsByte = 2;
                    break;
                case 3:
                    stopBitsByte = 1;
                    break;
                default:
                    throw new IllegalArgumentException("Bad value for stopBits: " + stopBits);
            }
            switch (parity) {
                case 0:
                    parityBitesByte = 0;
                    break;
                case 1:
                    parityBitesByte = 1;
                    break;
                case 2:
                    parityBitesByte = 2;
                    break;
                case 3:
                    parityBitesByte = 3;
                    break;
                case 4:
                    parityBitesByte = 4;
                    break;
                default:
                    throw new IllegalArgumentException("Bad value for parity: " + parity);
            }
            sendAcmControlMessage(32, 0, new byte[]{(byte) (baudRate & 255), (byte) ((baudRate >> 8) & 255), (byte) ((baudRate >> 16) & 255), (byte) ((baudRate >> 24) & 255), stopBitsByte, parityBitesByte, (byte) dataBits});
        }

        public boolean getCD() throws IOException {
            return false;
        }

        public boolean getCTS() throws IOException {
            return false;
        }

        public boolean getDSR() throws IOException {
            return false;
        }

        public boolean getDTR() throws IOException {
            return this.mDtr;
        }

        public void setDTR(boolean value) throws IOException {
            this.mDtr = value;
            setDtrRts();
        }

        public boolean getRI() throws IOException {
            return false;
        }

        public boolean getRTS() throws IOException {
            return this.mRts;
        }

        public void setRTS(boolean value) throws IOException {
            this.mRts = value;
            setDtrRts();
        }

        private void setDtrRts() {
            int i;
            int i2 = 0;
            if (this.mRts) {
                i = 2;
            } else {
                i = 0;
            }
            if (this.mDtr) {
                i2 = 1;
            }
            sendAcmControlMessage(34, i | i2, null);
        }
    }

    public CdcAcmSerialDriver(UsbDevice device) {
        this.mDevice = device;
        this.mPort = new CdcAcmSerialPort(this, device, 0);
    }

    public UsbDevice getDevice() {
        return this.mDevice;
    }

    public List<UsbSerialPort> getPorts() {
        return Collections.singletonList(this.mPort);
    }

    public static Map<Integer, int[]> getSupportedDevices() {
        Map<Integer, int[]> supportedDevices = new LinkedHashMap<>();
        supportedDevices.put(Integer.valueOf(UsbId.VENDOR_ARDUINO), new int[]{1, 67, 16, 66, 59, 68, 63, 68, UsbId.ARDUINO_LEONARDO});
        supportedDevices.put(Integer.valueOf(UsbId.VENDOR_VAN_OOIJEN_TECH), new int[]{1155});
        supportedDevices.put(Integer.valueOf(UsbId.VENDOR_ATMEL), new int[]{8260});
        supportedDevices.put(Integer.valueOf(UsbId.VENDOR_LEAFLABS), new int[]{4});
        return supportedDevices;
    }
}
