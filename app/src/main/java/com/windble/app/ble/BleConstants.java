package com.windble.app.ble;

import java.util.UUID;

public class BleConstants {
    // Primary wind service
    public static final UUID SERVICE_AE00 = UUID.fromString("0000ae00-0000-1000-8000-00805f9b34fb");
    // Secondary service (ae30 range)
    public static final UUID SERVICE_AE30 = UUID.fromString("0000ae30-0000-1000-8000-00805f9b34fb");
    // Wind data characteristic (NOTIFY)
    public static final UUID CHAR_AE02  = UUID.fromString("0000ae02-0000-1000-8000-00805f9b34fb");

    // Standard Client Characteristic Config Descriptor
    public static final UUID CCCD = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");

    // Connection states
    public static final int STATE_DISCONNECTED = 0;
    public static final int STATE_CONNECTING    = 1;
    public static final int STATE_CONNECTED     = 2;

    // Broadcast actions
    public static final String ACTION_GATT_CONNECTED    = "com.windble.GATT_CONNECTED";
    public static final String ACTION_GATT_DISCONNECTED = "com.windble.GATT_DISCONNECTED";
    public static final String ACTION_DATA_AVAILABLE    = "com.windble.DATA_AVAILABLE";
    public static final String EXTRA_DATA               = "com.windble.EXTRA_DATA";
    public static final String EXTRA_DEVICE_ADDRESS     = "com.windble.EXTRA_DEVICE_ADDRESS";
}
