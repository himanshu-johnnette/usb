
package com.johnnette.usb;


import static com.johnnette.usb.MainActivity.updateText;

import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbManager;
import android.os.Binder;
import android.os.Bundle;
import android.os.IBinder;
import android.util.Log;

import androidx.annotation.Nullable;

import com.felhr.usbserial.CDCSerialDevice;
import com.felhr.usbserial.SerialInputStream;
import com.felhr.usbserial.SerialOutputStream;
import com.felhr.usbserial.UsbSerialDevice;
import com.felhr.usbserial.UsbSerialInterface;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

import io.dronefleet.mavlink.MavlinkConnection;
import io.dronefleet.mavlink.MavlinkMessage;
import io.dronefleet.mavlink.minimal.Heartbeat;

public class NewUsbService extends Service {

    public static final String ACTION_USB_READY = "com.felhr.connectivityservices.USB_READY";
    public static final String ACTION_USB_ATTACHED = "android.hardware.usb.action.USB_DEVICE_ATTACHED";
    public static final String ACTION_USB_DETACHED = "android.hardware.usb.action.USB_DEVICE_DETACHED";


    public static final String ACTION_USB_NOT_SUPPORTED = "com.felhr.usbservice.USB_NOT_SUPPORTED";
    public static final String ACTION_NO_USB = "com.felhr.usbservice.NO_USB";
    public static final String ACTION_USB_PERMISSION_GRANTED = "com.felhr.usbservice.USB_PERMISSION_GRANTED";
    public static final String ACTION_USB_PERMISSION_NOT_GRANTED = "com.felhr.usbservice.USB_PERMISSION_NOT_GRANTED";
    public static final String ACTION_USB_DISCONNECTED = "com.felhr.usbservice.USB_DISCONNECTED";
    public static final String ACTION_CDC_DRIVER_NOT_WORKING = "com.felhr.connectivityservices.ACTION_CDC_DRIVER_NOT_WORKING";
    public static final String ACTION_USB_DEVICE_NOT_WORKING = "com.felhr.connectivityservices.ACTION_USB_DEVICE_NOT_WORKING";
    public static final int MESSAGE_FROM_SERIAL_PORT = 0;

    public static final int CTS_CHANGE = 1;
    public static final int DSR_CHANGE = 2;
    private static final String ACTION_USB_PERMISSION = "com.johnnette.usb.USB_PERMISSION";
    private static final int BAUD_RATE = 9600; // BaudRate. Change this value if you need
    public static boolean SERVICE_CONNECTED = false;

    private Context context;
    public static String DEVICE_NAME = "";

    Binder mbinder = new NewUsbBinder();
    private UsbManager usbManager;
    private UsbDevice usbDevice;
    private UsbDeviceConnection usbDeviceConnection;
    private UsbSerialDevice usbSerialDevice;

    private SerialInputStream serialInputStream;
    private SerialOutputStream serialOutputStream;

    private boolean serialPortConnected;

    private final BroadcastReceiver permissionBroadcastReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {

                synchronized (this)
                {
                    if (Objects.equals(intent.getAction(), ACTION_USB_PERMISSION)) {
                /*Bundle bundle = intent.getExtras();
                boolean granted = false;
                if (bundle != null)
                    granted = bundle.getBoolean(UsbManager.EXTRA_PERMISSION_GRANTED);
*/
                    if ( intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false) ) {
                        Intent permissionGrantedIntent = new Intent(ACTION_USB_PERMISSION_GRANTED);
                        context.sendBroadcast(intent);

                        usbDeviceConnection = usbManager.openDevice(usbDevice);

                        new MavlinkConnectionThread().start();

                    } else // User not accepted our USB connection. Send an Intent to the Main Activity
                    {
                        Intent permissionDeniedIntent = new Intent(ACTION_USB_PERMISSION_NOT_GRANTED);
                        context.sendBroadcast(intent);
                    }
                } else if (Objects.equals(intent.getAction(), ACTION_USB_ATTACHED)) {
                    if (!serialPortConnected)
                        findSerialPortDevice(); // A USB device has been attached. Try to open it as a Serial port
                } else if (Objects.equals(intent.getAction(), ACTION_USB_DETACHED)) {
                    DEVICE_NAME = "";
                    updateText();
                    // Usb device was disconnected. send an intent to the Main Activity
                    Intent usbDisconnected = new Intent(ACTION_USB_DISCONNECTED);
                    context.sendBroadcast(new Intent(ACTION_USB_DISCONNECTED));
                    if (serialPortConnected) {
                        usbSerialDevice.syncClose();
                    }
                    serialPortConnected = false;
                }

            }
        }
    };


    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return mbinder;
    }

    public class NewUsbBinder extends Binder {
        public NewUsbService getService() {
            return NewUsbService.this;
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        this.context = this;
        serialPortConnected = false;
        UsbService.SERVICE_CONNECTED = true;
        setFilters();
        usbManager = (UsbManager) getSystemService(Context.USB_SERVICE);
        findSerialPortDevice();
    }

    private void findSerialPortDevice() {
        HashMap<String, UsbDevice> usbDeviceHashMap = usbManager.getDeviceList();

        if (!usbDeviceHashMap.isEmpty()) {
            for (Map.Entry<String, UsbDevice> entry : usbDeviceHashMap.entrySet()) {
                usbDevice = entry.getValue();

                DEVICE_NAME = usbDevice.getProductName();
                updateText();

                Log.d("USB", String.format("USBDevice.HashMap (vid:pid) (%X:%X)-%b class:%X:%X name:%s",
                        usbDevice.getVendorId(), usbDevice.getProductId(),
                        UsbSerialDevice.isSupported(usbDevice),
                        usbDevice.getDeviceClass(), usbDevice.getDeviceSubclass(),
                        usbDevice.getDeviceName()));

                if (UsbSerialDevice.isSupported(usbDevice)) {
                    // There is a device connected to our Android device. Try to open it as a Serial Port.
                    requestUserPermission();

                    break;
                } else {
                    usbDeviceConnection = null;
                    usbDevice = null;
                }

            }

            if (usbDevice == null) {
                // There are no USB devices connected (but usb host were listed). Send an intent to MainActivity.
                Intent intent = new Intent(ACTION_NO_USB);
                sendBroadcast(intent);
            }

        } else {
            Log.d("USB", "findSerialPortDevice() usbManager returned empty device list.");
            // There is no USB devices connected. Send an intent to MainActivity
            Intent intent = new Intent(ACTION_NO_USB);
            sendBroadcast(intent);
        }
    }

    private void setFilters() {
        IntentFilter filter = new IntentFilter();
        filter.addAction(ACTION_USB_PERMISSION);
        filter.addAction(ACTION_USB_DETACHED);
        filter.addAction(ACTION_USB_ATTACHED);
        registerReceiver(permissionBroadcastReceiver, filter);
    }

    private void requestUserPermission() {
        Log.d("USB", String.format("requestUserPermission(%X:%X)", usbDevice.getVendorId(), usbDevice.getProductId()));

        PendingIntent mPendingIntent = PendingIntent.getBroadcast(this, 0, new Intent(ACTION_USB_PERMISSION), PendingIntent.FLAG_IMMUTABLE);
        usbManager.requestPermission(usbDevice, mPendingIntent);
    }

    private class MavlinkConnectionThread extends Thread {

        @Override
        public void run() {
            usbSerialDevice = UsbSerialDevice.createUsbSerialDevice(usbDevice, usbDeviceConnection);

            if (usbSerialDevice != null) {
                try {

                    if ( usbSerialDevice.open() ) {
                        serialPortConnected = true;
                        usbSerialDevice.setBaudRate(BAUD_RATE);
                        usbSerialDevice.setDataBits(UsbSerialInterface.DATA_BITS_8);
                        usbSerialDevice.setStopBits(UsbSerialInterface.STOP_BITS_1);
                        usbSerialDevice.setParity(UsbSerialInterface.PARITY_NONE);
                        usbSerialDevice.setFlowControl(UsbSerialInterface.FLOW_CONTROL_OFF);


                        serialInputStream = usbSerialDevice.getInputStream();
                        serialOutputStream = usbSerialDevice.getOutputStream();

                        MavlinkConnection connection = MavlinkConnection.create(serialInputStream, serialOutputStream);
                        MavlinkMessage<?> message;

                        try {
                            while ((message = connection.next()) != null) {
                                if (message.getPayload() instanceof Heartbeat) {
                                    Log.d("USB", "HEARTBEAT FOUND");
                                }
                            }
                        } catch (IOException exception) {
                            Log.e("USB", "MavlinkConnection error");

                        }

                        Intent intent = new Intent(ACTION_USB_READY);
                        context.sendBroadcast(intent);
                    } else {

                        // Serial port could not be opened, maybe an I/O error or if CDC driver was chosen, it does not really fit
                        // Send an Intent to Main Activity
                        if (usbSerialDevice instanceof CDCSerialDevice) {
                            Intent intent = new Intent(ACTION_CDC_DRIVER_NOT_WORKING);
                            context.sendBroadcast(intent);
                        } else {
                            Intent intent = new Intent(ACTION_USB_DEVICE_NOT_WORKING);
                            context.sendBroadcast(intent);
                        }


                    }
                } catch (NullPointerException ext) {
                    Log.e("USB", "Null at usbSerialDevice");
                }
            }
        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();

        if (usbSerialDevice != null)
            usbSerialDevice.close();

        unregisterReceiver(permissionBroadcastReceiver);
        UsbService.SERVICE_CONNECTED = false;
    }

}