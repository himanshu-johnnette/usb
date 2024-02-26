package com.johnnette.usb;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbManager;
import android.os.Bundle;
import android.util.Log;

import androidx.appcompat.app.AppCompatActivity;

import com.felhr.usbserial.UsbSerialDevice;
import com.felhr.usbserial.UsbSerialInterface;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

public class TestingHome extends AppCompatActivity {
    private static final String TAG = MainActivity.class.getSimpleName();

    private UsbManager usbManager;
    private UsbSerialDevice serialPort;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        usbManager = (UsbManager) getSystemService(Context.USB_SERVICE);

        // Find the Pixhawk device
        UsbDevice device = findDevice();

        if (device != null) {
            // Request permission to access the device
            usbManager.requestPermission(device, permissionIntent);
        } else {
            Log.e(TAG, "Pixhawk device not found!");
        }
    }

    private UsbDevice findDevice() {

        HashMap<String, UsbDevice> deviceList = usbManager.getDeviceList();

        for (Map.Entry<String, UsbDevice> entry : deviceList.entrySet()) {
            UsbDevice device = entry.getValue();
            Log.d("DEVICES", ""+device.getDeviceName());

           /* // Check if this is the Pixhawk device based on vendor and product IDs
            if (device.getVendorId() == YOUR_VENDOR_ID && device.getProductId() == YOUR_PRODUCT_ID) {
                return device;
            }*/
        }
        return null;
    }


    // Permission intent for USB access
    private final PendingIntent permissionIntent = PendingIntent.getBroadcast(this, 0, new Intent(ACTION_USB_PERMISSION), PendingIntent.FLAG_ONE_SHOT | PendingIntent.FLAG_IMMUTABLE);
    private static final String ACTION_USB_PERMISSION = "com.android.example.USB_PERMISSION";

    // USB permission receiver
    private final BroadcastReceiver usbReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (ACTION_USB_PERMISSION.equals(action)) {
                synchronized (this) {
                    UsbDevice device = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);

                    if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) {
                        if (device != null) {
                            // Permission granted, open the device and start communication
                            openDevice(device);
                        }
                    } else {
                        Log.e(TAG, "Permission denied for device " + device);
                    }
                }
            }
        }
    };

    // Open the USB device and start communication
    private void openDevice(UsbDevice device) {
        UsbDeviceConnection connection = usbManager.openDevice(device);
        if (connection != null) {
            serialPort = UsbSerialDevice.createUsbSerialDevice(device, connection);
            if (serialPort != null) {
                if (serialPort.open()) {
                    // Communication started, now you can send/receive data
                    serialPort.setBaudRate(115200);
                    serialPort.setDataBits(UsbSerialInterface.DATA_BITS_8);
                    serialPort.setStopBits(UsbSerialInterface.STOP_BITS_1);
                    serialPort.setParity(UsbSerialInterface.PARITY_NONE);
                    serialPort.setFlowControl(UsbSerialInterface.FLOW_CONTROL_OFF);
                    serialPort.read(mCallback);
                } else {
                    Log.e(TAG, "Error opening device: " + serialPort.getClass().getSimpleName());
                }
            } else {
                Log.e(TAG, "Error creating serial port.");
            }
        } else {
            Log.e(TAG, "Error opening USB device.");
        }
    }

    // Callback for receiving data
    private UsbSerialInterface.UsbReadCallback mCallback = new UsbSerialInterface.UsbReadCallback() {
        @Override
        public void onReceivedData(byte[] data) {
            // Handle received data
            String dataString = new String(data, StandardCharsets.UTF_8);
            Log.d(TAG, "Received data: " + dataString);
        }
    };

    @Override
    protected void onResume() {
        super.onResume();
        registerReceiver(usbReceiver, new IntentFilter(ACTION_USB_PERMISSION));
    }

    @Override
    protected void onPause() {
        super.onPause();
        unregisterReceiver(usbReceiver);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (serialPort != null) {
            serialPort.close();
        }
    }
}