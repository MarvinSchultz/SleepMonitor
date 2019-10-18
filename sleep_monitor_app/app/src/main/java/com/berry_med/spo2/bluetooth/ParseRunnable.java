package com.berry_med.spo2.bluetooth;

//import android.support.p000v4.media.TransportMediator;
import java.util.concurrent.LinkedBlockingQueue;

public class ParseRunnable implements Runnable {
    private static int PACKAGE_LEN = 5;
    private boolean isStop = false;
    private OnDataChangeListener mOnDataChangeListener;
    private OxiParams mOxiParams = new OxiParams();
    private LinkedBlockingQueue<Integer> oxiData = new LinkedBlockingQueue<>(256);
    private int[] parseBuf = new int[5];

    public interface OnDataChangeListener {
        void onPulseWaveDetected();

        void onSpO2ParamsChanged();

        void onSpO2WaveChanged(int i);
    }

    static final public class OxiParams {
        public int PI_INVALID_VALUE = 15;
        public int PR_INVALID_VALUE = 255;
        public int SPO2_INVALID_VALUE = 127;//TransportMediator.KEYCODE_MEDIA_PAUSE;
        /* access modifiers changed from: private */

        /* renamed from: pi */
        public int f26pi;
        /* access modifiers changed from: private */
        public int pulseRate;
        /* access modifiers changed from: private */
        public int spo2;

        public OxiParams() {
        }

        /* access modifiers changed from: private */
        public void update(int spo22, int pulseRate2, int pi) {
            this.spo2 = spo22;
            this.pulseRate = pulseRate2;
            this.f26pi = pi;
        }

        public int getSpo2() {
            return this.spo2;
        }

        public int getPulseRate() {
            return this.pulseRate;
        }

        public int getPi() {
            return this.f26pi;
        }

        public boolean isParamsValid() {
            if (this.spo2 == this.SPO2_INVALID_VALUE || this.pulseRate == this.PR_INVALID_VALUE || this.f26pi == this.PI_INVALID_VALUE || this.spo2 == 0 || this.pulseRate == 0 || this.f26pi == 0) {
                return false;
            }
            return true;
        }
    }

    public ParseRunnable(OnDataChangeListener onDataChangeListener) {
        this.mOnDataChangeListener = onDataChangeListener;
    }

    public OxiParams getOxiParams() {
        return this.mOxiParams;
    }

    public void add(byte[] data) {
        for (byte d : data) {
            try {
                this.oxiData.put(Integer.valueOf(toUnsignedInt(d)));
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }
    }

    public void run() {
        while (!this.isStop) {
            int dat = getData();
            if ((dat & 128) > 0) {
                this.parseBuf[0] = dat;
                for (int i = 1; i < PACKAGE_LEN; i++) {
                    int dat2 = getData();
                    if ((dat2 & 128) == 0) {
                        this.parseBuf[i] = dat2;
                    }
                }
                int spo2 = this.parseBuf[4];
                int pulseRate = this.parseBuf[3] | ((this.parseBuf[2] & 64) << 1);
                int pi = this.parseBuf[0] & 15;
                if (!(spo2 == this.mOxiParams.spo2 && pulseRate == this.mOxiParams.pulseRate && pi == this.mOxiParams.f26pi)) {
                    this.mOxiParams.update(spo2, pulseRate, pi);
                    this.mOnDataChangeListener.onSpO2ParamsChanged();
                }
                this.mOnDataChangeListener.onSpO2WaveChanged(this.parseBuf[1]);
                if ((this.parseBuf[0] & 64) != 0) {
                    this.mOnDataChangeListener.onPulseWaveDetected();
                }
            }
        }
    }

    public void stop() {
        this.isStop = false;
    }

    public static float getFloatPi(int pi) {
        switch (pi) {
            case 0:
                return 0.1f;
            case 1:
                return 0.2f;
            case 2:
                return 0.4f;
            case 3:
                return 0.7f;
            case 4:
                return 1.4f;
            case 5:
                return 2.7f;
            case 6:
                return 5.3f;
            case 7:
                return 10.3f;
            case 8:
                return 20.0f;
            default:
                return 0.0f;
        }
    }

    private int toUnsignedInt(byte x) {
        return x & 255;
    }

    private int getData() {
        int dat = 0;
        try {
            return ((Integer) this.oxiData.take()).intValue();
        } catch (InterruptedException e) {
            e.printStackTrace();
            return dat;
        }
    }
}
