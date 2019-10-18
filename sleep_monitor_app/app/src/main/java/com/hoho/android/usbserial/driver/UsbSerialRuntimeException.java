package com.hoho.android.usbserial.driver;

public class UsbSerialRuntimeException extends RuntimeException {
    public UsbSerialRuntimeException() {
    }

    public UsbSerialRuntimeException(String detailMessage, Throwable throwable) {
        super(detailMessage, throwable);
    }

    public UsbSerialRuntimeException(String detailMessage) {
        super(detailMessage);
    }

    public UsbSerialRuntimeException(Throwable throwable) {
        super(throwable);
    }
}
