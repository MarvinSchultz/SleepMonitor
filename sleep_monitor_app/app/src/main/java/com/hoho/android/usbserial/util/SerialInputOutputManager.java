package com.hoho.android.usbserial.util;

import android.util.Log;
import com.hoho.android.usbserial.driver.UsbSerialPort;
import java.io.IOException;
import java.nio.ByteBuffer;

public class SerialInputOutputManager implements Runnable {
    private static final int BUFSIZ = 4096;
    private static final boolean DEBUG = true;
    private static final int READ_WAIT_MILLIS = 200;
    private static final String TAG = SerialInputOutputManager.class.getSimpleName();
    private final UsbSerialPort mDriver;
    private Listener mListener;
    private final ByteBuffer mReadBuffer;
    private State mState;
    private final ByteBuffer mWriteBuffer;

    public interface Listener {
        void onNewData(byte[] bArr);

        void onRunError(Exception exc);
    }

    private enum State {
        STOPPED,
        RUNNING,
        STOPPING
    }

    public SerialInputOutputManager(UsbSerialPort driver) {
        this(driver, null);
    }

    public SerialInputOutputManager(UsbSerialPort driver, Listener listener) {
        this.mReadBuffer = ByteBuffer.allocate(4096);
        this.mWriteBuffer = ByteBuffer.allocate(4096);
        this.mState = State.STOPPED;
        this.mDriver = driver;
        this.mListener = listener;
    }

    public synchronized void setListener(Listener listener) {
        this.mListener = listener;
    }

    public synchronized Listener getListener() {
        return this.mListener;
    }

    public void writeAsync(byte[] data) {
        synchronized (this.mWriteBuffer) {
            this.mWriteBuffer.put(data);
        }
    }

    public synchronized void stop() {
        if (getState() == State.RUNNING) {
            Log.i(TAG, "Stop requested");
            this.mState = State.STOPPING;
        }
    }

    private synchronized State getState() {
        return this.mState;
    }

    public void run() {
        synchronized (this) {
            if (getState() != State.STOPPED) {
                throw new IllegalStateException("Already running.");
            }
            this.mState = State.RUNNING;
        }
        Log.i(TAG, "Running ..");
        while (getState() == State.RUNNING) {
            try {
                step();
            } catch (Exception e) {
                Log.w(TAG, "Run ending due to exception: " + e.getMessage(), e);
                Listener listener = getListener();
                if (listener != null) {
                    listener.onRunError(e);
                }
                synchronized (this) {
                    this.mState = State.STOPPED;
                    Log.i(TAG, "Stopped.");
                    return;
                }
            } catch (Throwable th) {
                synchronized (this) {
                    this.mState = State.STOPPED;
                    Log.i(TAG, "Stopped.");
                    throw th;
                }
            }
        }
        Log.i(TAG, "Stopping mState=" + getState());
        synchronized (this) {
            this.mState = State.STOPPED;
            Log.i(TAG, "Stopped.");
        }
    }

    private void step() throws IOException {
        int len;
        int len2 = this.mDriver.read(this.mReadBuffer.array(), 200);
        if (len2 > 0) {
            Log.d(TAG, "Read data len=" + len2);
            Listener listener = getListener();
            if (listener != null) {
                byte[] data = new byte[len2];
                this.mReadBuffer.get(data, 0, len2);
                listener.onNewData(data);
            }
            this.mReadBuffer.clear();
        }
        byte[] outBuff = null;
        synchronized (this.mWriteBuffer) {
            len = this.mWriteBuffer.position();
            if (len > 0) {
                outBuff = new byte[len];
                this.mWriteBuffer.rewind();
                this.mWriteBuffer.get(outBuff, 0, len);
                this.mWriteBuffer.clear();
            }
        }
        if (outBuff != null) {
            Log.d(TAG, "Writing data len=" + len);
            this.mDriver.write(outBuff, 200);
        }
    }
}
