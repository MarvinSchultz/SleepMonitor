package com.hoho.android.usbserial.driver;

import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.List;

public class UsbSerialProber {
    private final ProbeTable mProbeTable;

    public UsbSerialProber(ProbeTable probeTable) {
        this.mProbeTable = probeTable;
    }

    public static UsbSerialProber getDefaultProber() {
        return new UsbSerialProber(getDefaultProbeTable());
    }

    public static ProbeTable getDefaultProbeTable() {
        ProbeTable probeTable = new ProbeTable();
        probeTable.addDriver(CdcAcmSerialDriver.class);
        probeTable.addDriver(Cp21xxSerialDriver.class);
        probeTable.addDriver(FtdiSerialDriver.class);
        probeTable.addDriver(ProlificSerialDriver.class);
        return probeTable;
    }

    public List<UsbSerialDriver> findAllDrivers(UsbManager usbManager) {
        List<UsbSerialDriver> result = new ArrayList<>();
        for (UsbDevice usbDevice : usbManager.getDeviceList().values()) {
            UsbSerialDriver driver = probeDevice(usbDevice);
            if (driver != null) {
                result.add(driver);
            }
        }
        return result;
    }

    public UsbSerialDriver probeDevice(UsbDevice usbDevice) {
        Class<? extends UsbSerialDriver> driverClass = this.mProbeTable.findDriver(usbDevice.getVendorId(), usbDevice.getProductId());
        if (driverClass == null) {
            return null;
        }
        try {
            return (UsbSerialDriver) driverClass.getConstructor(new Class[]{UsbDevice.class}).newInstance(new Object[]{usbDevice});
        } catch (NoSuchMethodException e) {
            throw new RuntimeException(e);
        } catch (IllegalArgumentException e2) {
            throw new RuntimeException(e2);
        } catch (InstantiationException e3) {
            throw new RuntimeException(e3);
        } catch (IllegalAccessException e4) {
            throw new RuntimeException(e4);
        } catch (InvocationTargetException e5) {
            throw new RuntimeException(e5);
        }
    }
}
