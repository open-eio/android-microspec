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

    private static final long USB_DATA_READY_SIGNAL_TIMEOUT_NANOS = 2*1_000_000_000; //2 seconds
    private static final int  TEENSY_USB_VID  = 0x16C0;
    private static final int  SAMSUNG_USB_VID = 0x04E8;

    private static final String TAG = "MyActivity";
    protected static final int DATA_CHUNK_SIZE = 1024;

    Button clearButton;
    TextView textView;
    UsbManager usbManager;

    UsbSerialDevice serialPort = null;
    UsbDeviceConnection connection;

    ServerSocket serverSocket;
    Thread socketServerThread = null;

    volatile boolean usbDataReadySignal = false;
    volatile byte[]  volatileUsbData = null;
    volatile int     argLength = 0;
    //volatile String volatileUsbData;

    volatile boolean shutdownReplyThread = false;
    String command ;


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
            } else{
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

        tvAppend(textView, getIpAddress());
        if (socketServerThread == null) {
            socketServerThread = new Thread(new SocketServerThread());
            socketServerThread.start();
        }
    }
    @Override
    protected void onDestroy() {
        super.onDestroy();

        if (serialPort != null){
            closeSerialConnection();
        }

        if (serverSocket != null) {
            try {
                serverSocket.close();
            } catch (IOException e) {
                e.printStackTrace();
            }
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

    public static String intentToString(Intent intent) {
        if (intent == null) {
            return null;
        }

        return intent.toString() + " " + bundleToString(intent.getExtras());
    }

    public static String bundleToString(Bundle bundle) {
        StringBuilder out = new StringBuilder("Bundle[");

        if (bundle == null) {
            out.append("null");
        } else {
            boolean first = true;
            for (String key : bundle.keySet()) {
                if (!first) {
                    out.append(", ");
                }

                out.append(key).append('=');

                Object value = bundle.get(key);

                if (value instanceof int[]) {
                    out.append(Arrays.toString((int[]) value));
                } else if (value instanceof byte[]) {
                    out.append(Arrays.toString((byte[]) value));
                } else if (value instanceof boolean[]) {
                    out.append(Arrays.toString((boolean[]) value));
                } else if (value instanceof short[]) {
                    out.append(Arrays.toString((short[]) value));
                } else if (value instanceof long[]) {
                    out.append(Arrays.toString((long[]) value));
                } else if (value instanceof float[]) {
                    out.append(Arrays.toString((float[]) value));
                } else if (value instanceof double[]) {
                    out.append(Arrays.toString((double[]) value));
                } else if (value instanceof String[]) {
                    out.append(Arrays.toString((String[]) value));
                } else if (value instanceof CharSequence[]) {
                    out.append(Arrays.toString((CharSequence[]) value));
                } else if (value instanceof Parcelable[]) {
                    out.append(Arrays.toString((Parcelable[]) value));
                } else if (value instanceof Bundle) {
                    out.append(bundleToString((Bundle) value));
                } else {
                    out.append(value);
                }

                first = false;
            }
        }

        out.append("]");
        return out.toString();
    }

    public void doNotify(final String msg){
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
                final Intent emptyIntent = new Intent();
                PendingIntent pendingIntent = PendingIntent.getActivity(getApplicationContext(), 0, emptyIntent, PendingIntent.FLAG_UPDATE_CURRENT);

                NotificationCompat.Builder mBuilder =
                        new NotificationCompat.Builder(getApplicationContext())
                                .setSmallIcon(R.drawable.notification_icon)
                                .setContentTitle("NeuroDot Gateway")
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
                } else if (deviceVID == SAMSUNG_USB_VID) {
                    tvAppend(textView, "USB: found Samsung GearVR!\n");
                } else{
                    tvAppend(textView, "USB: unkown device.\n");
                }
            }
        } else{
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
                if (device == null){
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


    private void handleUsbDeviceAttached(UsbDevice device){
        int deviceVID = device.getVendorId();
        int devicePID = device.getProductId();

        tvAppend(textView, "USB: ACTION_USB_DEVICE_ATTACHED\n");
        if (deviceVID == TEENSY_USB_VID) {

            openSerialConnection(device);
            tvAppend(textView, "USB: Teensy connected!\n");

            //notify
            doNotify("NeuroDot Device connected!");


        } else if (deviceVID == SAMSUNG_USB_VID) {
            tvAppend(textView, "USB: Samsung GearVR connected!\n");
        }
    }

    private void handleUsbDeviceDetached(UsbDevice device){
        int deviceVID = device.getVendorId();
        int devicePID = device.getProductId();

        tvAppend(textView, "USB: ACTION_USB_DEVICE_DETACHED\n");
        if (deviceVID == TEENSY_USB_VID) {
            tvAppend(textView, "USB: Teensy disconnected!\n");
            if(serialPort != null) {
                closeSerialConnection();
                //notify
                doNotify("NeuroDot Device disconnected!");
            }
        } else if (deviceVID == SAMSUNG_USB_VID) {
            tvAppend(textView, "USB: Samsung GearVR disconnected!\n");
        }
    }

    protected void openSerialConnection(UsbDevice device){
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

    protected void closeSerialConnection(){
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
            } catch (Exception e) {
                e.printStackTrace();
                tvAppend(textView, "ERROR :Probably Out.println error./n");
            }
        }
    };

    private boolean waitOnUsbDataReadySignal() {
        long startTime = System.nanoTime();

        while(!usbDataReadySignal){
            long estimatedTime = System.nanoTime() - startTime;
            if (estimatedTime >= USB_DATA_READY_SIGNAL_TIMEOUT_NANOS){
                return false;
            }
            try {
                Thread.sleep(0,100_000); //delay for 100 microseconds
            } catch (InterruptedException e) {
                //ignore interruption
            }
        }
        return true;

    }
    //----------------------------------------------------------------------------------------------
    private class SocketServerThread extends Thread {

        static final int SocketServerPORT = 8080;
        static final int REPLY_THREAD_COMPLETION_TIMEOUT_MILLIS = 1000;
        int count = 0;

        SocketServerReplyThread socketServerReplyThread = null;

        @Override
        public void run()  {
            try {
                serverSocket = new ServerSocket(SocketServerPORT);
                tvAppend(textView,"Port : "
                        + serverSocket.getLocalPort()+"\n");

                while (true) {
                    tvAppend(textView, "SocketServer: ready, waiting on connection #" + count + ".\n");
                    Socket socket = serverSocket.accept();
                    tvAppend(textView, "SocketServer: accepted connection #" + count + " from " + socket.getInetAddress()
                            + ":" + socket.getPort() + "\n");
                    doNotify("The network client has been connected!");
                    //tvAppend(textView, "default send buffer size: " + socket.getSendBufferSize()  + "\n");
                    if((socketServerReplyThread != null) && socketServerReplyThread.isAlive()){
                        tvAppend(textView, "SocketServer: waiting for previous reply thread to shutdown...\n");
                        //if the thread is still running, wait a bit...
                        try {
                            socketServerReplyThread.join(REPLY_THREAD_COMPLETION_TIMEOUT_MILLIS);
                            //if still alive, then shut it down!
                            if (socketServerReplyThread.isAlive()) {
                                tvAppend(textView, "SocketServer: ...timeout, requesting shutdown\n");
                                shutdownReplyThread = true;
                                socketServerReplyThread.join();
                                shutdownReplyThread = false;
                            }
                        } catch (InterruptedException e){
                            tvAppend(textView, "SocketServer: ...interrupted! \n");
                        }
                        tvAppend(textView, "SocketServer: ...finished.\n");
                    }
                    socketServerReplyThread = new SocketServerReplyThread(socket, count);
                    socketServerReplyThread.start();
                    count++;
                }
            } catch (IOException e) {
                tvAppend(textView, "ERROR : "+e);
            }
        }

    }

   private class SocketServerReplyThread extends Thread {

        private Socket hostThreadSocket;

        private static final long CONNECTION_WATCHDOG_TIMEOUT_MILLIS = 30*1_000; //10 seconds
        private static final long BYTES_TO_SEND_CAP = DATA_CHUNK_SIZE;

        int id;

        SocketServerReplyThread(Socket socket, int c) {
            hostThreadSocket = socket;
            id = c;
        }

        @Override
        public void run() {
            OutputStream outputStream   = null;
            PrintWriter printStream     = null;
            BufferedOutputStream bufferedOutputStream = null;
            BufferedReader bufferedReader = null;
            String receiveMsg;
            String tempUsbRecvData;
            boolean isStreaming = false; //start in DIALOG mode
            boolean watchDogIsActive = false;
            long connectionWatchDogLastFeedTime = System.currentTimeMillis();
            long bytes_to_send = 0;
            try {
                outputStream = hostThreadSocket.getOutputStream();
                printStream = new PrintWriter(outputStream);
                bufferedOutputStream = new BufferedOutputStream(outputStream);
                bufferedReader = new BufferedReader(new InputStreamReader(hostThreadSocket.getInputStream()));
                boolean isIdle = true;
                while(true){
                    isIdle = true; //assume we are idle going into the loop
                    //check to see if this thread should be shutdown
                    if (shutdownReplyThread){
                        shutdownReplyThread = false;
                        throw new Exception("The thread was signalled to shutdown.\n");
                    }
                    //check to see if the watchdog timer has run out
                    if (watchDogIsActive){
                        long elapsedTime = System.currentTimeMillis() - connectionWatchDogLastFeedTime;
                        if (elapsedTime >= CONNECTION_WATCHDOG_TIMEOUT_MILLIS){
                            throw new Exception("The Connection Watch Dog Timer has expired!\n");
                        }
                    }
                    //handle any commands if input is waiting
                    if (bufferedReader.ready()) {
                        isIdle = false; //we have network data waiting
                        receiveMsg = bufferedReader.readLine();
                        //prevent watchdog from force exiting thread
                        connectionWatchDogLastFeedTime = System.currentTimeMillis();
                        tvAppend(textView, "ReplyThread #"+id+": network recv -> " + receiveMsg + "\n");
                        if (receiveMsg == "") {
                            //do nothing, skip to next loop iteration NOTE sending empty command
                            //to the USB device would hang the usbDataReadySignal waiting loop
                            tvAppend(textView, "ReplyThread #"+id+": <ignoring empty command>\n");
                            printStream.print("");
                            printStream.flush();
                        } else if (receiveMsg == null) {
                            tvAppend(textView, "ReplyThread #"+id+": <null recv>\n");
                            throw new Exception("receive yielded null\n"); //must treat as error condition, likely cause socket closure on client end
                        } else if(receiveMsg.contains("GATEWAY:IDN?")){
                            tvAppend(textView, "ReplyThread #"+id+": <GATEWAY:IDN? requested>\n");
                            printStream.print("NeuroDot Gateway Android\n");
                            printStream.flush();
                        }
                        else if(receiveMsg.contains("GATEWAY:DISCONNECT")){
                            tvAppend(textView, "ReplyThread #"+id+": <GATEWAY:DISCONNECT requested>\n");
                            break;// jump out of loop
                        } else if(receiveMsg.contains("GATEWAY:WATCHDOG.ACTIVATE")){
                            tvAppend(textView, "ReplyThread #"+id+": <watchdog is ACTIVE>\n");
                            watchDogIsActive = true;
                        } else if(receiveMsg.contains("GATEWAY:WATCHDOG.DEACTIVATE")){
                            tvAppend(textView, "ReplyThread #"+id+": <watchdog is NOT ACTIVE>\n");
                            watchDogIsActive = false;
                        } else if(receiveMsg.contains("GATEWAY:WATCHDOG.FEED")){
                            tvAppend(textView, "ReplyThread #"+id+": <watchdog has been fed>\n");
                            connectionWatchDogLastFeedTime = System.currentTimeMillis();
                        }
                        //remainder of conditions require serial port connection!
                        else if (serialPort == null) {
                            tvAppend(textView, "ReplyThread #"+id+": <sending ERROR: USB device not connected>\n");
                            printStream.print("#ERROR: USB device not connected\n");
                            printStream.flush();
                            //FIXME we have to terminate and restart the connection or else this
                            //      thread hangs, WHY?
                            throw new Exception("USB device not connected\n");
                        } else if (isStreaming) {
                            if(receiveMsg.contains("TEST.STREAM.STOP")   ||
                                    receiveMsg.contains("IOMODE.DIALOG") ||
                                    receiveMsg.contains("ADS.STOP")){
                                //send command to USB device serial port
                                command = receiveMsg+"\n";
                                tvAppend(textView,"ReplyThread #"+id+": <sending to USB>\n");
                                usbDataReadySignal = false;    //reset state of usbDataReadySignal event flag
                                serialPort.write(command.getBytes());
                                //exit STREAMING mode
                                tvAppend(textView,"ReplyThread #"+id+": exit Streaming mode.\n");
                                isStreaming = false;
                            } else {
                                tvAppend(textView,"ReplyThread #"+id+": <#WARNING: Ignoring that command while in streaming mode.>\n");
                            }
                        } else {
                            //in DIALOG mode send command to USB device serial port
                            command = receiveMsg + "\n";
                            tvAppend(textView, "ReplyThread #"+id+": <sending to USB>\n");
                            usbDataReadySignal = false;    //reset state of usbDataReadySignal event flag
                            serialPort.write(command.getBytes());
                            if (receiveMsg.contains("TEST.STREAM.START") ||
                                    receiveMsg.contains("IOMODE.STREAM") ||
                                    receiveMsg.contains("ADS.START")) {
                                //enter STREAMING mode
                                tvAppend(textView, "ReplyThread #"+id+": entering Streaming mode.\n");
                                isStreaming = true;
                                usbDataReadySignal = false;    //quickly allow UsbReadCallback to work again
                                continue; //skip to next loop iteration
                            }
                            //block until UsbSerialInterfaceOwn.UsbReadCallback sets the data in volatileUsbData
                            boolean success = waitOnUsbDataReadySignal(); //will return false on timeout
                            if (success ){
                                tempUsbRecvData = new String(volatileUsbData);  //store the data from the UsbReadCallback
                                usbDataReadySignal = false;         //quickly allow UsbReadCallback to work again
                                tvAppend(textView, "ReplyThread #"+id+": USB recv <- " + tempUsbRecvData);
                                printStream.print(tempUsbRecvData);
                                printStream.flush();
                            } else { //waiting on USB response timedout
                                tvAppend(textView,"ReplyThread #"+id+": <sending WARNING: USB recv timedout>\n");
                                printStream.print("#WARNING: USB recv timedout\n");
                                printStream.flush();
                            }
                        }
                    }
                    //handle streaming data sent from USB device
                    if (isStreaming) {
                        if (usbDataReadySignal) {
                            isIdle = false; //we have USB data waiting
                            bufferedOutputStream.write(volatileUsbData, 0, argLength);
                            bytes_to_send += argLength;
                            usbDataReadySignal = false;
                            //if (bytes_to_send >= BYTES_TO_SEND_CAP) {
                            //    bufferedOutputStream.flush(); //IMPORTANT make sure streaming is smooth
                            //    bytes_to_send = 0; //IMPORTANT clear the total after sending
                            //}
                            //FIXME flush immediately, is this much less efficient in data streaming?
                            bufferedOutputStream.flush(); //IMPORTANT make sure streaming is smooth
                            bytes_to_send = 0; //IMPORTANT clear the total after sending
                        }
                        //check if this loop iteration has been idle
                        if(isIdle){
                            //rest a little bit
                            Thread.sleep(0, 100_000); //delay for 100 microseconds
                        }
                    } else{
                        //check if this loop iteration has been idle
                        if(isIdle) {
                            //rest a little longer in dialog mode
                            Thread.sleep(10); //sleep for 10 milliseconds
                        }
                    }

                }
            } catch (Exception e) {
                tvAppend(textView, "ReplyThread #"+id+": ERROR : "+e);
                if ((serialPort != null) && isStreaming) {
                    tvAppend(textView, "ReplyThread #"+id+": sending STOP commands to USB device.\n");
                    command = "ADS.STOP\n";
                    serialPort.write(command.getBytes());
                    command = "TEST.STREAM.STOP\n";
                    serialPort.write(command.getBytes());
                }
            } finally{
                tvAppend(textView,"ReplyThread #"+id+": exiting SocketServerReplyThread and cleaning up.\n");
                try {
                    outputStream.close();
                    bufferedOutputStream.close();
                    bufferedReader.close();
                    printStream.close();
                } catch (java.io.IOException e){
                    //ignore errors on close
                }
            }

        }

   }

    private String getIpAddress() {
        String ip = "";
        try {
            Enumeration<NetworkInterface> enumNetworkInterfaces = NetworkInterface
                    .getNetworkInterfaces();
            while (enumNetworkInterfaces.hasMoreElements()) {
                NetworkInterface networkInterface = enumNetworkInterfaces
                        .nextElement();
                Enumeration<InetAddress> enumInetAddress = networkInterface
                        .getInetAddresses();
                while (enumInetAddress.hasMoreElements()) {
                    InetAddress inetAddress = enumInetAddress.nextElement();

                    if (inetAddress.isSiteLocalAddress()) {
                        ip += "My IP Address: "
                                + inetAddress.getHostAddress() + "\n";
                    }

                }

            }

        } catch (SocketException e) {
            // TODO Auto-generated catch block
            e.printStackTrace();
            ip += "Something Wrong! " + e.toString() + "\n";
        }

        return ip;
    }
}