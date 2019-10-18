package com.berry_med.spo2.bluetooth;

import java.util.UUID;

public class Const {
    public static final String BT_NAME = "BerryMed";
    public static final String DEVICE_NAME = "bt_name";
    public static final int MESSAGE_BLUETOOTH_A_DEVICE = 0;
    public static final int MESSAGE_BLUETOOTH_CONNECT_FAIL = 6;
    public static final int MESSAGE_BLUETOOTH_DATA = 9;
    public static final int MESSAGE_BLUETOOTH_DEVICE_NAME = 4;
    public static final int MESSAGE_BLUETOOTH_LOST = 8;
    public static final int MESSAGE_BLUETOOTH_START_SCAN = 2;
    public static final int MESSAGE_BLUETOOTH_STATE_CHANGE = 3;
    public static final int MESSAGE_BLUETOOTH_STOP_SCAN = 1;
    public static final int MESSAGE_BLUETOOTH_TOAST = 5;
    public static final int MESSAGE_BLUETOOTH_WRITE = 7;
    public static final int MESSAGE_OXIMETER_PARAMS = 2003;
    public static final int MESSAGE_OXIMETER_WAVE = 2004;
    public static final String TOAST = "toast";
    public static final UUID UUID_CHARACTER_RECEIVE = UUID.fromString("49535343-1e4d-4bd9-ba61-23c647249616");
    public static final UUID UUID_CLIENT_CHARACTER_CONFIG = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");
    public static final UUID UUID_MODIFY_BT_NAME = UUID.fromString("00005343-0000-1000-8000-00805F9B34FB");
    public static final UUID UUID_SERVICE_DATA = UUID.fromString("49535343-fe7d-4ae5-8fa9-9fafd205e455");
}
