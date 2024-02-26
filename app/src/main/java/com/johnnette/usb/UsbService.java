package com.johnnette.usb;

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
import android.os.Handler;
import android.os.IBinder;
import android.util.Log;

import com.felhr.usbserial.CDCSerialDevice;
import com.felhr.usbserial.SerialInputStream;
import com.felhr.usbserial.SerialOutputStream;
import com.felhr.usbserial.UsbSerialDevice;
import com.felhr.usbserial.UsbSerialInterface;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

import io.dronefleet.mavlink.MavlinkConnection;
import io.dronefleet.mavlink.MavlinkMessage;
import io.dronefleet.mavlink.minimal.Heartbeat;

public class UsbService extends Service {

    private static final String ACTION_USB_PERMISSION = "com.johnnette.usb.USB_PERMISSION";

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
    public static final int SYNC_READ = 3;
    private static final int BAUD_RATE = 9600; // BaudRate. Change this value if you need
    public static boolean SERVICE_CONNECTED = false;

    private IBinder binder = new UsbBinder();

    private Context context;
    private Handler mHandler;
    private UsbManager usbManager;
    private UsbDevice usbDevice;
    private UsbDeviceConnection connection;
    private UsbSerialDevice serialPortDevice;


    private boolean serialPortConnected;
    private static SerialInputStream serialInputStream;
    private static SerialOutputStream serialOutputStream;

    private ReadThread readThread;

    /*
     * Different notifications from OS will be received here (USB attached, detached, permission responses...)
     * About BroadcastReceiver: http://developer.android.com/reference/android/content/BroadcastReceiver.html
     */
    private final BroadcastReceiver usbReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {

            if(intent.getAction().equals(ACTION_USB_PERMISSION)) {
                boolean granted = Objects.requireNonNull(intent.getExtras()).getBoolean(UsbManager.EXTRA_PERMISSION_GRANTED);

                if (granted) {
                    Intent permissionGrantedIntent = new Intent(ACTION_USB_PERMISSION_GRANTED);
                    context.sendBroadcast(intent);

                    connection = usbManager.openDevice(usbDevice);

                    new ConnectionThread().start();
                } else // User not accepted our USB connection. Send an Intent to the Main Activity
                {
                    Intent permissionDeniedIntent = new Intent(ACTION_USB_PERMISSION_NOT_GRANTED);
                    context.sendBroadcast(intent);
                }
            }
            else if (intent.getAction().equals(ACTION_USB_ATTACHED)) {
                if (!serialPortConnected)
                    findSerialPortDevice(); // A USB device has been attached. Try to open it as a Serial port
            }
            else if (intent.getAction().equals(ACTION_USB_DETACHED)) {
                // Usb device was disconnected. send an intent to the Main Activity
                Intent usbDisconnnected = new Intent(ACTION_USB_DISCONNECTED);

                context.sendBroadcast(intent);
                if (serialPortConnected) {

                    serialPortDevice.syncClose();
                    readThread.setKeep(false);
                }
                serialPortConnected = false;
            }
        }
    };

    /*
     * onCreate will be executed when service is started. It configures an IntentFilter to listen for
     * incoming Intents (USB ATTACHED, USB DETACHED...) and it tries to open a serial port.
     */

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

    /* MUST READ about services
     * http://developer.android.com/guide/components/services.html
     * http://developer.android.com/guide/components/bound-services.html
     */
    @Override
    public IBinder onBind(Intent intent) {
        return binder;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return Service.START_NOT_STICKY;
    }



    /*
     * This function will be called from MainActivity to write data through Serial Port
     */
    public void write(byte[] data) {
        if (serialOutputStream != null)
            serialOutputStream.write(data);
    }


    /*
     * This function will be called from MainActivity to change baud rate
     */
    public void changeBaudRate(int baudRate){
        if(serialPortDevice != null)
            serialPortDevice.setBaudRate(baudRate);
    }
    public void setHandler(Handler mHandler) {
        this.mHandler = mHandler;
    }


    private void findSerialPortDevice(){
        HashMap<String, UsbDevice> usbDeviceHashMap = usbManager.getDeviceList();
        if(!usbDeviceHashMap.isEmpty()){

            for(Map.Entry<String, UsbDevice> entry: usbDeviceHashMap.entrySet()){
                usbDevice = entry.getValue();

                Log.d("USB", String.format("USBDevice.HashMap (vid:pid) (%X:%X)-%b class:%X:%X name:%s",
                        usbDevice.getVendorId(), usbDevice.getProductId(),
                        UsbSerialDevice.isSupported(usbDevice),
                        usbDevice.getDeviceClass(), usbDevice.getDeviceSubclass(),
                        usbDevice.getDeviceName()));
            }

            for (Map.Entry<String, UsbDevice> entry : usbDeviceHashMap.entrySet()) {
                usbDevice = entry.getValue();
                int deviceVID = usbDevice.getDeviceId();
                int devicePID = usbDevice.getProductId();

                if (UsbSerialDevice.isSupported(usbDevice)) {
                    // There is a device connected to our Android device. Try to open it as a Serial Port.
                    requestUserPermission();
                    break;
                } else {
                    connection = null;
                    usbDevice = null;
                }
            }
            if (usbDevice ==null) {
                // There are no USB devices connected (but usb host were listed). Send an intent to MainActivity.
                Intent intent = new Intent(ACTION_NO_USB);
                sendBroadcast(intent);
            }
            else {
                Log.d("USB", "findSerialPortDevice() usbManager returned empty device list." );
                // There is no USB devices connected. Send an intent to MainActivity
                Intent intent = new Intent(ACTION_NO_USB);
                sendBroadcast(intent);
            }
        }

    }

    private void setFilters(){
        IntentFilter filter = new IntentFilter();
        filter.addAction(ACTION_USB_PERMISSION);
        filter.addAction(ACTION_USB_DETACHED);
        filter.addAction(ACTION_USB_ATTACHED);
        registerReceiver(usbReceiver, filter);
    }

    /*
     * Request user permission. The response will be received in the BroadcastReceiver
     */
    private void requestUserPermission(){
        Log.d("USB", String.format("requestUserPermission(%X:%X)", usbDevice.getVendorId(), usbDevice.getProductId() ) );

        PendingIntent mPendingIntent = PendingIntent.getBroadcast(this,0, new Intent(ACTION_USB_PERMISSION), PendingIntent.FLAG_IMMUTABLE);
        usbManager.requestPermission(usbDevice, mPendingIntent);
    }

    public class UsbBinder extends Binder{
        public UsbService getService(){
            return UsbService.this;
        }
    }

    /*
     * A simple thread to open a serial port.
     * Although it should be a fast operation. moving usb operations away from UI thread is a good thing.
     */
    private class ConnectionThread extends  Thread{

        @Override
        public void run(){

            serialPortDevice = UsbSerialDevice.createUsbSerialDevice(usbDevice, connection);
            if(serialPortDevice != null){
                if(serialPortDevice.syncOpen()){
                    serialPortConnected = true;
                    serialPortDevice.setBaudRate(BAUD_RATE);
                    serialPortDevice.setDataBits(UsbSerialInterface.DATA_BITS_8);
                    serialPortDevice.setStopBits(UsbSerialInterface.STOP_BITS_1);
                    serialPortDevice.setParity(UsbSerialInterface.PARITY_NONE);

                    /**
                     * Current flow control Options:
                     * UsbSerialInterface.FLOW_CONTROL_OFF
                     * UsbSerialInterface.FLOW_CONTROL_RTS_CTS only for CP2102 and FT232
                     * UsbSerialInterface.FLOW_CONTROL_DSR_DTR only for CP2102 and FT232
                     */
                    serialPortDevice.setFlowControl(UsbSerialInterface.FLOW_CONTROL_OFF);

                    /**
                     * InputStream and OutputStream will be null if you are using async api.
                     */
                    serialInputStream   = serialPortDevice.getInputStream();
                    serialOutputStream  = serialPortDevice.getOutputStream();

                    readThread = new ReadThread();
                    readThread.start();

                    Intent intent = new Intent(ACTION_USB_READY);
                    context.sendBroadcast(intent);
                }
                else {

                    // Serial port could not be opened, maybe an I/O error or if CDC driver was chosen, it does not really fit
                    // Send an Intent to Main Activity
                    if (serialPortDevice instanceof CDCSerialDevice) {
                        Intent intent = new Intent(ACTION_CDC_DRIVER_NOT_WORKING);
                        context.sendBroadcast(intent);
                    } else {
                        Intent intent = new Intent(ACTION_USB_DEVICE_NOT_WORKING);
                        context.sendBroadcast(intent);
                    }

                }

            }else {
                // No driver for given device, even generic CDC driver could not be loaded
                Intent intent = new Intent(ACTION_USB_NOT_SUPPORTED);
                context.sendBroadcast(intent);
            }
        }
    }

    private static String toASCII(int value) {
        int length = 4;
        StringBuilder builder = new StringBuilder(length);
        for (int i = length - 1; i >= 0; i--) {
            builder.append((char) ((value >> (8 * i)) & 0xFF));
        }
        return builder.toString();
    }

    private class ReadThread extends Thread {
        private AtomicBoolean keep = new AtomicBoolean(true);
        @Override
        public void run() {
            while(keep.get()){
                if(serialInputStream == null)
                    return;
                int value = serialInputStream.read();
                if(value != -1) {
                    String str = toASCII(value);
                    mHandler.obtainMessage(SYNC_READ, str).sendToTarget();
                }
            }
        }

        public void setKeep(boolean keep){
            this.keep.set(keep);
        }
    }


    @Override
    public void onDestroy() {
        super.onDestroy();
        serialPortDevice.close();
        unregisterReceiver(usbReceiver);
        UsbService.SERVICE_CONNECTED = false;
    }
}
