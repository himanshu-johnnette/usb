package com.johnnette.usb;

import android.annotation.SuppressLint;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import android.os.Binder;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.util.Log;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.felhr.usbserial.CDCSerialDevice;
import com.felhr.usbserial.CH34xSerialDevice;
import com.felhr.usbserial.CP2102SerialDevice;
import com.felhr.usbserial.FTDISerialDevice;
import com.felhr.usbserial.PL2303SerialDevice;
import com.felhr.usbserial.SerialInputStream;
import com.felhr.usbserial.SerialOutputStream;
import com.felhr.usbserial.SerialPortBuilder;
import com.felhr.usbserial.SerialPortCallback;
import com.felhr.usbserial.UsbSerialDevice;
import com.felhr.usbserial.UsbSerialInterface;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

public class UsbService extends Service implements SerialPortCallback {


    //  these  string will work from android USB MANAGER

    private static final String ACTION_USB_PERMISSION = "com.android.example.USB_PERMISSION";

    public static final String ACTION_USB_ATTACHED = "android.hardware.usb.action.USB_DEVICE_ATTACHED";
    public static final String ACTION_USB_DETACHED = "android.hardware.usb.action.USB_DEVICE_DETACHED";

    // ALL these string from lib of UsbSerial--> felhr86
    public static final String ACTION_USB_READY = "com.felhr.connectivityservices.USB_READY";
    public static final String ACTION_USB_NOT_SUPPORTED = "com.felhr.usbservice.USB_NOT_SUPPORTED";
    public static final String ACTION_NO_USB = "com.felhr.usbservice.NO_USB";
    public static final String ACTION_USB_PERMISSION_GRANTED = "com.felhr.usbservice.USB_PERMISSION_GRANTED";
    public static final String ACTION_USB_PERMISSION_NOT_GRANTED = "com.felhr.usbservice.USB_PERMISSION_NOT_GRANTED";
    public static final String ACTION_USB_DISCONNECTED = "com.felhr.usbservice.USB_DISCONNECTED";
//    public static final String ACTION_CDC_DRIVER_NOT_WORKING = "com.felhr.connectivityservices.ACTION_CDC_DRIVER_NOT_WORKING";
//    public static final String ACTION_USB_DEVICE_NOT_WORKING = "com.felhr.connectivityservices.ACTION_USB_DEVICE_NOT_WORKING";


    public static final int MESSAGE_FROM_SERIAL_PORT = 0;
    public static final int CTS_CHANGE = 1;
    public static final int DSR_CHANGE = 2;
    public static final int SYNC_READ = 3;
    public static final int BAUD_RATE = 57600;


    private SerialPortBuilder builder;
    private List<UsbSerialDevice> serialPorts;
    private WriteThread writeThread;
    private Handler writeHandler;
    public static Handler mHandlers;
    public UsbManager usbManager;
    public UsbDevice device;
    public UsbSerialDevice serialport;


    public static boolean SERVICE_CONNECTED = false;

    private final IBinder binder = new UsbBinder();


    // variable for PIX_HAWK
    private ReadThreadCOM readThread_Pixhawk, readThread_Sensor;
    public static SerialInputStream pixhawk_serialInputStream;
    public static SerialOutputStream pixhawk_serialOutputStream;
    public static SerialOutputStream sensor_serialOutputStream;
    private Context context;
    private static final int MY_PERMISSION_REQUEST_CODE = 123;

    private final BroadcastReceiver usbReceiver = new BroadcastReceiver() {

        @Override
        public void onReceive(Context arg0, Intent arg1) {
            synchronized (this) {
                // action for USB ATTACHING and DETACHING -->  This action is Listen by Broadcast Receiver
                Log.d("Broadcast manager", "my broadcast Receiver in USB service!" + 1 );
                UsbDevice usbDevice = arg1.getParcelableExtra(UsbManager.EXTRA_DEVICE);
                // Log.d("usbDevice", usbDevice.getDeviceName());
                usbManager = (UsbManager) getSystemService(Context.USB_SERVICE);
                //usbManager.openDevice(usbDevice);
                if (Objects.equals(arg1.getAction(), ACTION_USB_ATTACHED)) {
//                        requestUSBPermission(usbDevice);
                    if (usbDevice != null) {
                        // checking this is serial port or not ---> using this ret boolean
                        boolean ret = builder.openSerialPorts(
                                context,
                                BAUD_RATE,
                                UsbSerialInterface.DATA_BITS_8,
                                UsbSerialInterface.STOP_BITS_1,
                                UsbSerialInterface.PARITY_NONE,
                                UsbSerialInterface.FLOW_CONTROL_OFF);
                        if (!ret) {
                            Toast.makeText(context, "Sorry Could not open the Device", Toast.LENGTH_SHORT).show();
                            Log.d("Broadcast manager", "my broadcast Receiver  in USB service!" + 2);

                        } else {
                            Toast.makeText(context, "Pixhawk Connected", Toast.LENGTH_SHORT).show();
                            Log.d("Broadcast manager", "my broadcast Receiver  in USB service!" + 3);

                        }

                        Intent intent = new Intent(ACTION_USB_ATTACHED);
                        context.sendBroadcast(intent);

                    }
                }

                else if (Objects.equals(arg1.getAction(), ACTION_USB_DETACHED)) {

                    if (usbDevice != null) {
                        boolean ret = builder.disconnectDevice(usbDevice);
                        if (ret) {
                            Toast.makeText(context, "usb disconnected", Toast.LENGTH_SHORT).show();
                        } else {
                            Toast.makeText(context, " Permission denied", Toast.LENGTH_SHORT).show();

                        }
                        Intent intent = new Intent(ACTION_USB_DISCONNECTED);
                        arg0.sendBroadcast(intent);
                    }
                }
            }
        }
    };

    //
    //  request USB permission


    // find serial port

    private void findSerialPortDevice() {
        Log.d("Connection", "In findSerialPortDevice");

        Log.d("findSerialPort", "my findSerialPortDevice()  in USB service!" + 4);
        usbManager = (UsbManager) getSystemService(Context.USB_SERVICE);
        HashMap<String, UsbDevice> usbDeviceHashMap = usbManager.getDeviceList();
        if (!usbDeviceHashMap.isEmpty()) {
            for (Map.Entry<String, UsbDevice> entry : usbDeviceHashMap.entrySet()) {
                device = entry.getValue();
                if (UsbSerialDevice.isSupported(device)) {
                    requestUserPermission();
                    setFilter();

                }
            }
        }
    }


    private void requestUserPermission() {

        Log.d("Connection", "In requesetUserPermission");

        PendingIntent pendingIntent = PendingIntent.getBroadcast(
                this,
                0,
                new Intent(ACTION_USB_PERMISSION),
                PendingIntent.FLAG_IMMUTABLE);
        usbManager.requestPermission(device, pendingIntent);
    }

    // When port will detected
    @Override
    public void onSerialPortsDetected(List<UsbSerialDevice> serialPorts) {

        Log.d("Connection", "In onSerialPortdetected");

        //  store the list of UsbSerialDevice objects passed as a parameter (serialPorts) in the----> instance variable serialPorts of the class.
        this.serialPorts = serialPorts;
        // if there is no serial port
        if (serialPorts.size() == 0)
            return;
        // WriteThread -> writing data to a USB device.
        if (writeThread == null) {
            writeThread = new WriteThread();
            writeThread.start();
        }
        try {

            Log.d("SerialPortsDetails", serialPorts.size() + "");
            Log.d("SerialPortsDetails", ""+serialPorts.get(0));
            Log.d("SerialPortsDetails", ""+serialPorts.get(1));

            for (int i = 0; i < serialPorts.size(); i++) {
                // Input stream is closed // Async or Sync //problem
                if (serialPorts.get(i) instanceof CDCSerialDevice) {

                    mHandlers.obtainMessage(111, 0, 0, "CDCSerialDevice" + serialPorts.size()).sendToTarget();
                    // getting closed problem
                    if ( readThread_Pixhawk == null && serialPorts.get(i).isOpen())
                    {
                        Log.d("SerialPortsStatus", "my stream is open here!");
                        pixhawk_serialInputStream = serialPorts.get(i).getInputStream();
                        pixhawk_serialOutputStream = serialPorts.get(i).getOutputStream();



                        MVConnection.Create_Connection(1,pixhawk_serialInputStream,pixhawk_serialOutputStream);

                        Log.d("pxhInputStream", pixhawk_serialInputStream.read() + "");
                        Log.d("pxhInputStream", pixhawk_serialOutputStream.toString());

                    }
                    readThread_Pixhawk = null;
                } else if (serialPorts.get(i) instanceof CH34xSerialDevice) {
                    mHandlers.obtainMessage(111, 0, 0, "CH34xSerialDevice" + serialPorts.size()).sendToTarget();
                    if (readThread_Sensor == null && serialPorts.get(i).isOpen()) {
                        Log.i("on serial Port Detection" , "instance of sensors" + 5);
                        readThread_Sensor = new ReadThreadCOM(i, serialPorts.get(i).getInputStream());
                        readThread_Sensor.start();
                        sensor_serialOutputStream = serialPorts.get(i).getOutputStream();
                    }
                    readThread_Sensor = null;

                }  else if (serialPorts.get(i) instanceof FTDISerialDevice) {
                    mHandlers.obtainMessage(111, 0, 0, "FTDISerialDevice" + serialPorts.size()).sendToTarget();
                }
            }
        } catch (Exception e) {
//            throw new RuntimeException(e);
            e.printStackTrace();
            Log.i("on serial Port Detection", "serialPort", e);
        }
    }


    // Creating service and bindingService
    @Override
    public void onCreate() {

        Log.d("Connection", "In Create service");
        try {
            //assigns the current context to a member variable
            this.context = this;
            // connect Usb  service
            UsbService.SERVICE_CONNECTED = true;
            // UsbManager is used to access and communicate with USB devices
            usbManager = (UsbManager) getSystemService(Context.USB_SERVICE);
            builder = SerialPortBuilder.createSerialPortBuilder(this);
            findSerialPortDevice();
            setFilter();
            // boolean value (ret) checking operation is successful or not
            boolean ret = builder.openSerialPorts(context, BAUD_RATE,
                    UsbSerialInterface.DATA_BITS_8,
                    UsbSerialInterface.STOP_BITS_1,
                    UsbSerialInterface.PARITY_NONE,
                    UsbSerialInterface.FLOW_CONTROL_OFF);
            if (!ret)
                Toast.makeText(context, "No usb serial ports available", Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
//            throw new RuntimeException(e);
            e.printStackTrace();
            Log.i("serialPortDetection", "port");
        }


    }


    @Nullable
    @Override
    //  called when a activity wants to bind to the service
    // intent will provide the activity Request
    public IBinder onBind(Intent intent) {
        Log.d("Connection", "In onBind");

        return binder;
    }

    @Override
    // It receives the intent that started the service, along with flags and an ID.
    // called when the service is started using startService,
    public int onStartCommand(Intent intent, int flags, int startId) {
        //if  service is killed ,  not be restarted until the client explicitly requests it using

        Log.d("Connection", " in start command");


        return Service.START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (builder != null) {
            builder.unregisterListeners(context);
            // USB service is marked as disconnected
            UsbService.SERVICE_CONNECTED = false;
        }
    }


    // to listen for specific USB-related actions

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    private void setFilter() {
        Log.d("Connection", "In setFilter");


        IntentFilter filter = new IntentFilter();
        Log.i("set filter of USB service" , "Intents" + 6 ) ;
        filter.addAction(ACTION_USB_PERMISSION);
        filter.addAction(ACTION_USB_PERMISSION_GRANTED);
        filter.addAction(ACTION_USB_ATTACHED);
        filter.addAction(ACTION_USB_DETACHED);
        registerReceiver(usbReceiver, filter);
    }

    public void write(byte[] data, int port) {
        // if Thread is Active
        if (writeThread != null) {
            //this is an instance of a Handler associated with the thread responsible for writing data.
            //sendToTarget():- This method sends the message to the target thread for processing.
            writeHandler.obtainMessage(0, port, 0, data).sendToTarget();
        }
    }

    public void UsbHandler(Handler mHandlers, HomeViewModel model) {

        UsbService.mHandlers = mHandlers;
    }

    // for binding the activity with service
    // this binder will called usb connection in Service Connection Interface
    public class UsbBinder extends Binder {
        public UsbService getService() {
            // refer to the instance of the outer class (UsbService) from within the inner class (UsbBinder).
            return UsbService.this;
        }
    }


    private class WriteThread extends Thread {

        // when the  thread is started
        @SuppressLint("HandlerLeak")
        public void run() {
            //Looper is for  manage message queues ,used for message handing
            Looper.prepare();
            writeHandler = new Handler() {
                public void handleMessage(@NonNull Message msg) {
                    // Retrieve  an integer argument and byte array from the message.
                    int port = msg.arg1;
                    byte[] data = (byte[]) msg.obj;
                    if (port <= serialPorts.size() - 1) {
                        // retrieves objects from serial port
                        UsbSerialDevice serialDevice = serialPorts.get(port);
                        // write the byte array to the output stream
                        serialDevice.getOutputStream().write(data);
                    }
                }
            };
            Looper.loop();
        }
    }

    //  this class is , for reading from a serial port using a SerialInputStream
    private class ReadThreadCOM extends Thread {
        private final int port;
        // this  flag is for --> control the execution of the thread, suitable for concurrent programming
        private final AtomicBoolean keep = new AtomicBoolean(true);

        //input stream associated with the serial port.
        private final SerialInputStream inputStream;

        public ReadThreadCOM(int port, SerialInputStream serialInputStream) {
            this.port = port;
            this.inputStream = serialInputStream;
        }

        @Override
        public void run() {
            try {
                while (keep.get()) {
                    if (inputStream == null)
                        return;
                    int value = inputStream.read();
                    if (value != -1) {
                        String str = toASCII(value);
                        // 0: This is an integer representing the "what" parameter of the message
                        // sendToTarget():- This method sends the Message to the Handler's target, which is associated with a Looper.
                        mHandlers.obtainMessage(SYNC_READ, port, 0, str).sendToTarget();

                    }
                }
            } catch (Exception e) {
//                throw new RuntimeException(e);
                e.printStackTrace();
                Log.i("READ THREAD " , "MSG");
            }

        }

        public void setKeep(boolean keep) {
            this.keep.set(keep);
        }
    }

    // for converting byte data into string
    // where each character represents one byte of the integer in ASCII
    //resulting string may not be meaningful unless the integer is representing characters in some encoding
    private String toASCII(int value) {
        int length = 4;
        StringBuilder builder1 = new StringBuilder(length);
        for (int i = length - 1; i >= 0; i--) {
            builder1.append((char) ((value >> (8 * i)) & 0xFF));

        }
        return builder1.toString();
    }


}
