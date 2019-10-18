package com.hoho.android.usbserial.driver;

import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbEndpoint;
import android.hardware.usb.UsbInterface;
import android.util.Log;
import java.io.IOException;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class Cp21xxSerialDriver implements UsbSerialDriver {
    /* access modifiers changed from: private */
    public static final String TAG = Cp21xxSerialDriver.class.getSimpleName();
    private final UsbDevice mDevice;
    //private final UsbSerialPort mPort = new Cp21xxSerialPort(this.mDevice, 0);
    private final UsbSerialPort mPort;

    public class Cp21xxSerialPort extends CommonUsbSerialPort {
        private static final int BAUD_RATE_GEN_FREQ = 3686400;
        private static final int CONTROL_WRITE_DTR = 256;
        private static final int CONTROL_WRITE_RTS = 512;
        private static final int DEFAULT_BAUD_RATE = 9600;
        private static final int FLUSH_READ_CODE = 10;
        private static final int FLUSH_WRITE_CODE = 5;
        private static final int MCR_ALL = 3;
        private static final int MCR_DTR = 1;
        private static final int MCR_RTS = 2;
        private static final int REQTYPE_HOST_TO_DEVICE = 65;
        private static final int SILABSER_FLUSH_REQUEST_CODE = 18;
        private static final int SILABSER_IFC_ENABLE_REQUEST_CODE = 0;
        private static final int SILABSER_SET_BAUDDIV_REQUEST_CODE = 1;
        private static final int SILABSER_SET_BAUDRATE = 30;
        private static final int SILABSER_SET_LINE_CTL_REQUEST_CODE = 3;
        private static final int SILABSER_SET_MHS_REQUEST_CODE = 7;
        private static final int UART_DISABLE = 0;
        private static final int UART_ENABLE = 1;
        private static final int USB_WRITE_TIMEOUT_MILLIS = 5000;
        private UsbEndpoint mReadEndpoint;
        private UsbEndpoint mWriteEndpoint;

        public /* bridge */ /* synthetic */ int getPortNumber() {
            return super.getPortNumber();
        }

        public /* bridge */ /* synthetic */ String getSerial() {
            return super.getSerial();
        }

        public /* bridge */ /* synthetic */ String toString() {
            return super.toString();
        }

        public Cp21xxSerialPort(UsbDevice device, int portNumber) {
            super(device, portNumber);
        }

        public UsbSerialDriver getDriver() {
            return Cp21xxSerialDriver.this;
        }

        private int setConfigSingle(int request, int value) {
            return this.mConnection.controlTransfer(65, request, value, 0, null, 0, 5000);
        }

        public void open(UsbDeviceConnection connection) throws IOException {
            if (this.mConnection != null) {
                throw new IOException("Already opened.");
            }
            this.mConnection = connection;
            boolean opened = false;
            int i = 0;
            while (i < this.mDevice.getInterfaceCount()) {
                try {
                    if (this.mConnection.claimInterface(this.mDevice.getInterface(i), true)) {
                        Log.d(Cp21xxSerialDriver.TAG, "claimInterface " + i + " SUCCESS");
                    } else {
                        Log.d(Cp21xxSerialDriver.TAG, "claimInterface " + i + " FAIL");
                    }
                    i++;
                } finally {
                    if (!opened) {
                        try {
                            close();
                        } catch (IOException e) {
                        }
                    }
                }
            }
            UsbInterface dataIface = this.mDevice.getInterface(this.mDevice.getInterfaceCount() - 1);
            for (int i2 = 0; i2 < dataIface.getEndpointCount(); i2++) {
                UsbEndpoint ep = dataIface.getEndpoint(i2);
                if (ep.getType() == 2) {
                    if (ep.getDirection() == 128) {
                        this.mReadEndpoint = ep;
                    } else {
                        this.mWriteEndpoint = ep;
                    }
                }
            }
            setConfigSingle(0, 1);
            setConfigSingle(7, 771);
            setConfigSingle(1, 384);
            opened = true;
            if (!opened) {
                try {
                    close();
                } catch (IOException e2) {
                }
            }
        }

        public void close() throws IOException {
            if (this.mConnection == null) {
                throw new IOException("Already closed");
            }
            try {
                setConfigSingle(0, 0);
                this.mConnection.close();
            } finally {
                this.mConnection = null;
            }
        }

        public int read(byte[] dest, int timeoutMillis) throws IOException {
            synchronized (this.mReadBufferLock) {
                int numBytesRead = this.mConnection.bulkTransfer(this.mReadEndpoint, this.mReadBuffer, Math.min(dest.length, this.mReadBuffer.length), timeoutMillis);
                if (numBytesRead < 0) {
                    return 0;
                }
                System.arraycopy(this.mReadBuffer, 0, dest, 0, numBytesRead);
                return numBytesRead;
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
                Log.d(Cp21xxSerialDriver.TAG, "Wrote amt=" + amtWritten + " attempted=" + writeLength);
                offset += amtWritten;
            }
            return offset;
        }

        private void setBaudRate(int baudRate) throws IOException {
            if (this.mConnection.controlTransfer(65, 30, 0, 0, new byte[]{(byte) (baudRate & 255), (byte) ((baudRate >> 8) & 255), (byte) ((baudRate >> 16) & 255), (byte) ((baudRate >> 24) & 255)}, 4, 5000) < 0) {
                throw new IOException("Error setting baud rate.");
            }
        }

        public void setParameters(int baudRate, int dataBits, int stopBits, int parity) throws IOException {
            int configDataBits;
            setBaudRate(baudRate);
            switch (dataBits) {
                case 5:
                    configDataBits = 0 | 1280;
                    break;
                case 6:
                    configDataBits = 0 | 1536;
                    break;
                case 7:
                    configDataBits = 0 | 1792;
                    break;
                case 8:
                    configDataBits = 0 | 2048;
                    break;
                default:
                    configDataBits = 0 | 2048;
                    break;
            }
            switch (parity) {
                case 1:
                    configDataBits |= 16;
                    break;
                case 2:
                    configDataBits |= 32;
                    break;
            }
            switch (stopBits) {
                case 1:
                    configDataBits |= 0;
                    break;
                case 2:
                    configDataBits |= 2;
                    break;
            }
            setConfigSingle(3, configDataBits);
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
            return true;
        }

        public void setDTR(boolean value) throws IOException {
        }

        public boolean getRI() throws IOException {
            return false;
        }

        public boolean getRTS() throws IOException {
            return true;
        }

        public void setRTS(boolean value) throws IOException {
        }

        public boolean purgeHwBuffers(boolean purgeReadBuffers, boolean purgeWriteBuffers) throws IOException {
            int i;
            int i2 = 0;
            if (purgeReadBuffers) {
                i = 10;
            } else {
                i = 0;
            }
            if (purgeWriteBuffers) {
                i2 = 5;
            }
            int value = i | i2;
            if (value != 0) {
                setConfigSingle(18, value);
            }
            return true;
        }
    }

    public Cp21xxSerialDriver(UsbDevice device) {
        this.mDevice = device;
        mPort = new Cp21xxSerialPort(this.mDevice, 0);
    }

    public UsbDevice getDevice() {
        return this.mDevice;
    }

    public List<UsbSerialPort> getPorts() {
        return Collections.singletonList(this.mPort);
    }

    public static Map<Integer, int[]> getSupportedDevices() {
        Map<Integer, int[]> supportedDevices = new LinkedHashMap<>();
        supportedDevices.put(Integer.valueOf(UsbId.VENDOR_SILABS), new int[]{UsbId.SILABS_CP2102, 8, UsbId.SILABS_CP2108, UsbId.SILABS_CP2110});
        return supportedDevices;
    }
}
