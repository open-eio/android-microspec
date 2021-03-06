package com.openeio.microspec;

import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbManager;
import android.media.RingtoneManager;
import android.support.design.widget.FloatingActionButton;
import android.support.v7.app.AppCompatActivity;
import android.os.Bundle;
import android.support.v4.app.NotificationCompat;
import android.support.v7.widget.Toolbar;
import android.text.method.ScrollingMovementMethod;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import com.felhr.usbserial.UsbSerialDevice;
import com.felhr.usbserial.UsbSerialInterface;

import java.util.HashMap;
import java.util.Map;
import java.util.Timer;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static android.content.Intent.ACTION_MAIN;


public class MainActivity extends AppCompatActivity {

    public static final String SPEC_DATA_KEY = "spec_data";
    public static final String WAVELENGTH_VALS_KEY = "wavelength_vals";



    private static final long USB_DATA_READY_SIGNAL_TIMEOUT_NANOS = 2 * 1_000_000_000; //2 seconds
    private static final int TEENSY_USB_VID = 0x16C0;
    private static final String TAG = "MyActivity";
    protected static final int DATA_CHUNK_SIZE = 1024;
    private static final double DEFAULT_INTEGRATION_TIME = 1e-3; //one millisecond

    protected Map<String,Double> c12880CalibrationCoeffs;

    public static final double C12880_CAL_A0 = 3.152446842e+2;
    public static final double C12880_CAL_B1 = 2.688494791;
    public static final double C12880_CAL_B2 = -8.964262020e-4;
    public static final double C12880_CAL_B3 = -1.030880174e-5;
    public static final double C12880_CAL_B4 = 2.083514791e-8;
    public static final double C12880_CAL_B5 = -1.290505933e-11;

    Button clearButton;
    TextView textView;
    UsbManager usbManager;

    // seriaPort: CDCSerialDevice@731a650 for teensy
    UsbSerialDevice serialPort = null;
    UsbDeviceConnection connection;

    private BlockingQueue<byte[]> usbQueue; //queue for holding USB data

    private boolean useAutoExposure = true;
    private static final double autoExposureStartIntegTime = 0.0001; //seconds

    //----------------------------------------------------------------------------------------------
    // MainActivity method overrides
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        Toolbar toolbar = (Toolbar) findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);


        usbManager = (UsbManager) getSystemService(USB_SERVICE);
        clearButton = (Button) findViewById(R.id.buttonClear);
        textView = (TextView) findViewById(R.id.textView3);

        textView.setMovementMethod(new ScrollingMovementMethod());

        //set up USB data queue
        usbQueue = new LinkedBlockingQueue<>();

        //setup map for holding calibration coefficients
        c12880CalibrationCoeffs = new HashMap<>(6);
        c12880CalibrationCoeffs.put("A0",C12880_CAL_A0);
        c12880CalibrationCoeffs.put("B1",C12880_CAL_B1);
        c12880CalibrationCoeffs.put("B2",C12880_CAL_B2);
        c12880CalibrationCoeffs.put("B3",C12880_CAL_B3);
        c12880CalibrationCoeffs.put("B4",C12880_CAL_B4);
        c12880CalibrationCoeffs.put("B5",C12880_CAL_B5);

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

        //setup Floating Action Button
        FloatingActionButton fab = (FloatingActionButton) findViewById(R.id.fab);
        fab.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                //FIXME testing only
                //launch the SpectrumPlot activity
//                Intent intent = new Intent(view.getContext(), SpectrumPlotActivity.class);
//                //intent.putExtra(EXTRA_MESSAGE, message);
//                startActivity(intent);

                if (serialPort != null) {
                    try {
                        //set the integration time
                        if (useAutoExposure){
                            specInteg(autoExposureStartIntegTime);
                        } else {
                            specInteg(DEFAULT_INTEGRATION_TIME);
                        }
                        //read the spectrometer and get the data
                        int[] specData = specRead();
                        //should we adjust the integration time and take another
                        if (useAutoExposure){
                            //compute the max of the array
                            int max_Y = 0;
                            for (int i = 0; i < specData.length; i++) {
                                int y = specData[i];
                                if (y > max_Y) {
                                    max_Y = y;
                                }
                            }
                            //compute the fraction of 80% max ADC
                            double M = 0.8*Math.pow(2.0,16.0)/max_Y;
                            //set the new integration time
                            specInteg(autoExposureStartIntegTime*M);
                            //recapture the spectrum
                            specData = specRead();
                        }
                        //compute the wavelengths array
                        double[] wavelengthVals = new double[specData.length];
                        for (int i=0; i < wavelengthVals.length; i++){
                            double p = i+1.0;
                            double x = C12880_CAL_A0 +
                                       C12880_CAL_B1*p +
                                       C12880_CAL_B2*Math.pow(p,2.0) +
                                       C12880_CAL_B3*Math.pow(p,3.0) +
                                       C12880_CAL_B4*Math.pow(p,4.0) +
                                       C12880_CAL_B5*Math.pow(p,5.0);
                            //tvAppend(textView,"WL " + p + "," + x + "\n");
                            wavelengthVals[i] = x;
                        }
                        //launch the SpectrumPlot activity and send the data
                        Intent intent = new Intent(view.getContext(), SpectrumPlotActivity.class);
                        intent.putExtra(SPEC_DATA_KEY, specData);
                        intent.putExtra(WAVELENGTH_VALS_KEY, wavelengthVals);
                        startActivity(intent);

                    } catch (InterruptedException e) {
                        e.printStackTrace();
                    }
                } else{
                    tvAppend(textView, "ERROR: no device is attached!\n");
                }
            }
        });


    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        // Inflate the menu; this adds items to the action bar if it is present.
        getMenuInflater().inflate(R.menu.menu_main, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        // Handle action bar item clicks here. The action bar will
        // automatically handle clicks on the Home/Up button, so long
        // as you specify a parent activity in AndroidManifest.xml.
        int id = item.getItemId();

        //noinspection SimplifiableIfStatement
        if (id == R.id.action_settings) {
            return true;
        }

        return super.onOptionsItemSelected(item);
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

    protected void dumpUsbQueueData(boolean display){
        try {
            while(true){
                byte[] usbRecvData = usbQueue.poll();
                if (usbRecvData == null){
                    break;
                }
                tvAppend(textView, "USB: Dumping Queue contents: (" + usbRecvData.length + " bytes)\n");
                if (display){
                    String s = new String(usbRecvData); //interpret as ASCII
                    tvAppend(textView, s);
                    tvAppend(textView, "\n");
                }

                Thread.sleep(10); //sleep for a short time 10 ms to allow queue to fill in between
            }
        } catch (InterruptedException e){};

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
                usbQueue.put(arg0);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }
    };


    private int[] specRead() throws InterruptedException {
        //send the command to the device
        String command = "SPEC.READ?\n";
        tvAppend(textView, command);
        serialPort.write(command.getBytes());
        //fetch the data from the queue
        byte[] usbRecvData = usbQueue.poll(5, TimeUnit.SECONDS); //FIXME adapt to longer integration times
        if (usbRecvData != null){
            String s = new String(usbRecvData); //interpret as ASCII
            tvAppend(textView, s);
            tvAppend(textView, "\n");
            //parse the string into a numerical array
            String[] strVals = s.split(",");
            int[] intVals = new int[strVals.length];
            for (int i=0; i < strVals.length; i++){
                try{
                    String sv = strVals[i].trim().replaceAll("\n ", ""); //remove spaces and newlines
                    intVals[i] = Integer.parseInt(sv);
                } catch(NumberFormatException e){
                    tvAppend(textView, "readSpec caught ERROR: " + e.toString() + "\n");
                    tvAppend(textView, "\t...substituting zero value.\n");
                    intVals[i] = 0;
                }
            }
            return intVals;
        } else{
            tvAppend(textView, "ERROR: no data on USB queue!\n");
            return null;
        }

    }

    private void specInteg(double seconds) throws InterruptedException {
        //send the command to the device
        String command = "SPEC.INTEG " + seconds + "\n";
        tvAppend(textView, command);
        serialPort.write(command.getBytes());
        //fetch the data from the queue
        byte[] usbRecvData = usbQueue.poll(100, TimeUnit.MILLISECONDS);
        if (usbRecvData != null){
            String s = new String(usbRecvData); //interpret as ASCII
            tvAppend(textView, s);
            tvAppend(textView, "\n");
        }
    }
}
