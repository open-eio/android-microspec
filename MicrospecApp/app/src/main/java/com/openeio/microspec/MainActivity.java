package com.openeio.microspec;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbManager;
import android.media.Ringtone;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Parcelable;
import android.support.v7.app.AppCompatActivity;
import android.os.Bundle;
import android.support.v4.app.NotificationCompat;
import android.text.method.ScrollingMovementMethod;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import com.felhr.usbserial.UsbSerialDevice;
import com.felhr.usbserial.UsbSerialInterface;
import com.openeio.microspec.R;

import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.util.Arrays;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.Map;
import java.util.Timer;

import static android.content.Intent.ACTION_MAIN;


public class MainActivity extends AppCompatActivity {

    private static final long USB_DATA_READY_SIGNAL_TIMEOUT_NANOS = 2 * 1_000_000_000; //2 seconds
    private static final int TEENSY_USB_VID = 0x16C0;

    private static final String TAG = "MyActivity";
    protected static final int DATA_CHUNK_SIZE = 1024;

    Button clearButton;
    TextView textView;
    UsbManager usbManager;

    UsbSerialDevice serialPort = null;
    UsbDeviceConnection connection;

    volatile boolean usbDataReadySignal = false;
    volatile byte[] volatileUsbData = null;
    volatile int argLength = 0;

    String command;


    // seriaPort: CDCSerialDevice@731a650 for teensy

    //----------------------------------------------------------------------------------------------
    // MainActivity method overrides
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        usbManager = (UsbManager) getSystemService(this.USB_SERVICE);
        clearButton = (Button) findViewById(R.id.buttonClear);
        textView = (TextView) findViewById(R.id.textView3);

        textView.setMovementMethod(new ScrollingMovementMethod());

        //Handle the initialization of USB devices:
        //  If the app is started by the icon (ACTION_MAIN), then see if USB devices are already
        //  attached via searchUsbDevices.
        //  If the GearVR or Teensy is connected before application is running, then the intent filter
        //  should have called this onCreate method, so we must handle the attachment
        Context context = getApplicationContext();
        Intent intent = getIntent();
        if (intent != null) {
            String action = intent.getAction();
            //tvAppend(textView, "App launched with: " + intentToString(intent) + "\n");
            tvAppend(textView, "App launched with: " + action + "\n");
            if (action == ACTION_MAIN) {
                searchUsbDevices();
            } else {
                //something other than the icon launched this application, so handle
                broadcastReceiver.onReceive(context, intent);
            }
        }

        //Set up the broadcastReciever to handle any USB state changes while the application is
        //  running
        //PendingIntent mPermissionIntent = PendingIntent.getBroadcast(this, 0, new Intent(ACTION_USB_PERMISSION), 0);
        IntentFilter filter = new IntentFilter();
        filter.addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED);
        filter.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED);
        registerReceiver(broadcastReceiver, filter);

    }

    @Override
    protected void onDestroy() {
        super.onDestroy();

        if (serialPort != null) {
            closeSerialConnection();
        }
    }

    //----------------------------------------------------------------------------------------------
    // UI
    public void onClickClear(View view) {
        textView.setText("");
    }

    public void tvAppend(TextView tv, CharSequence text) {
        final TextView ftv = tv;
        final CharSequence ftext = text;

        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                ftv.append(ftext);
            }
        });
    }


    public void doNotify(final String msg) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
                final Intent emptyIntent = new Intent();
                PendingIntent pendingIntent = PendingIntent.getActivity(getApplicationContext(), 0, emptyIntent, PendingIntent.FLAG_UPDATE_CURRENT);

                NotificationCompat.Builder mBuilder =
                        new NotificationCompat.Builder(getApplicationContext())
                                .setSmallIcon(R.drawable.notification_icon)
                                .setContentTitle("MicroSpec")
                                .setContentText(msg)
                                .setContentIntent(pendingIntent) //Required on Gingerbread and below
                                .setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION));
                nm.notify(1, mBuilder.build());  //this does work in VR mode
                //also show a brief message (doesn't work in VR mode)
                Toast.makeText(getApplicationContext(), msg, Toast.LENGTH_SHORT).show();
            }
        });

    }

    //----------------------------------------------------------------------------------------------
    // USB initialization and utils
    private void searchUsbDevices() {
        tvAppend(textView, "USB: searching for devices...\n");
        HashMap<String, UsbDevice> usbDevices = usbManager.getDeviceList();
        if (!usbDevices.isEmpty()) {
            //search for the Teensy by VID #
            for (Map.Entry<String, UsbDevice> entry : usbDevices.entrySet()) {
                UsbDevice device = entry.getValue();
                int deviceVID = device.getVendorId();
                int devicePID = device.getProductId();
                tvAppend(textView, "USB: checking USB device with ID: " +
                        String.format("%04X", deviceVID) + ":" +
                        String.format("%04X", devicePID) + "\n");
                if (deviceVID == TEENSY_USB_VID) {
                    tvAppend(textView, "USB: found Teensy!\n");
                    openSerialConnection(device);
                    tvAppend(textView, "USB: Teensy connected!\n");
                } else {
                    tvAppend(textView, "USB: unkown device.\n");
                }
            }
        } else {
            tvAppend(textView, "USB: ...nothing attached yet.\n");
        }
    }

    private final BroadcastReceiver broadcastReceiver = new BroadcastReceiver() { //Broadcast Receiver to automatically start and stop the Serial connection.
        @Override
        public void onReceive(Context context, Intent intent) {
            try {
                tvAppend(textView, "USB: handling intent broadcast...\n");
                String action = intent.getAction();
                UsbDevice device = (UsbDevice) intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
                if (device == null) {
                    tvAppend(textView, "USB: WARNING got null device!\n");
                } else if (action.equals(UsbManager.ACTION_USB_DEVICE_ATTACHED)) {
                    handleUsbDeviceAttached(device);
                } else if (action.equals(UsbManager.ACTION_USB_DEVICE_DETACHED)) {
                    handleUsbDeviceDetached(device);
                }
            } catch (Exception e) {
                tvAppend(textView, "USB: broadcastReceiver ERROR : " + e);
            }
        }
    };


    private void handleUsbDeviceAttached(UsbDevice device) {
        int deviceVID = device.getVendorId();
        int devicePID = device.getProductId();

        tvAppend(textView, "USB: ACTION_USB_DEVICE_ATTACHED\n");
        if (deviceVID == TEENSY_USB_VID) {

            openSerialConnection(device);
            tvAppend(textView, "USB: Teensy connected!\n");

            //notify
            doNotify("Device connected!");

            //FIXME just for testing
            //test command here
            String command = "SPEC.READ?\n";
            serialPort.write(command.getBytes());

        }
    }

    private void handleUsbDeviceDetached(UsbDevice device) {
        int deviceVID = device.getVendorId();
        int devicePID = device.getProductId();

        tvAppend(textView, "USB: ACTION_USB_DEVICE_DETACHED\n");
        if (deviceVID == TEENSY_USB_VID) {
            tvAppend(textView, "USB: Teensy disconnected!\n");
            if (serialPort != null) {
                closeSerialConnection();
                //notify
                doNotify("Device disconnected!");
            }
        }
    }

    protected void openSerialConnection(UsbDevice device) {
        connection = usbManager.openDevice(device);
        serialPort = UsbSerialDevice.createUsbSerialDevice(device, connection);
        if (serialPort != null) {
            if (serialPort.open()) { //Set Serial Connection Parameters.
                serialPort.setBaudRate(115200);
                serialPort.setDataBits(UsbSerialInterface.DATA_BITS_8);
                serialPort.setStopBits(UsbSerialInterface.STOP_BITS_1);
                serialPort.setParity(UsbSerialInterface.PARITY_NONE);
                serialPort.setFlowControl(UsbSerialInterface.FLOW_CONTROL_OFF);
                serialPort.read(mCallback);
                tvAppend(textView, "USB: Serial Connection opened!\n");
            } else {
                tvAppend(textView, "USB: ERROR : PORT NOT OPEN.");
                Log.d("SERIAL", "PORT NOT OPEN");
            }
        } else {
            Log.d("SERIAL", "PORT IS NULL");
            tvAppend(textView, "USB: ERROR : PORT IS NULL.");
        }
    }

    protected void closeSerialConnection() {
        //cleanup
        serialPort.close();
        serialPort = null;
        tvAppend(textView, "USB: Serial Connection closed!\n");
    }

    UsbSerialInterface.UsbReadCallback mCallback = new UsbSerialInterface.UsbReadCallback() { //Defining a Callback which triggers whenever data is read.
        @Override
        public void onReceivedData(byte[] arg0) {

            try {
                // we MUST block until this flag is cleared by the SocketServerReplyThread!
                // TODO replace volatileUsbData with a threadsafe Queue so blocking is not necessary
//                while(usbDataReadySignal){
//                    Thread.sleep(0,100_000); //delay for 100 microseconds
//                }
                argLength = arg0.length;
                volatileUsbData = arg0;
                usbDataReadySignal = true;
                //FIXME just for testing
                String tempUsbRecvData = new String(volatileUsbData);
                tvAppend(textView, "USB recv <- " + tempUsbRecvData);
            } catch (Exception e) {
                e.printStackTrace();
                tvAppend(textView, "ERROR :Probably Out.println error./n");
            }
        }
    };

    private boolean waitOnUsbDataReadySignal() {
        long startTime = System.nanoTime();

        while (!usbDataReadySignal) {
            long estimatedTime = System.nanoTime() - startTime;
            if (estimatedTime >= USB_DATA_READY_SIGNAL_TIMEOUT_NANOS) {
                return false;
            }
            try {
                Thread.sleep(0, 100_000); //delay for 100 microseconds
            } catch (InterruptedException e) {
                //ignore interruption
            }
        }
        return true;

    }
}