package com.openeio.microspec;

import android.Manifest;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.pm.PackageManager;
import android.os.Environment;
import android.support.annotation.NonNull;
import android.support.v4.app.ActivityCompat;
import android.support.v4.content.ContextCompat;
import android.support.v4.os.EnvironmentCompat;
import android.support.v7.app.AppCompatActivity;
import android.os.Bundle;
import android.support.v7.widget.Toolbar;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.widget.Toast;

import com.androidplot.xy.LineAndPointFormatter;
import com.androidplot.xy.SimpleXYSeries;
import com.androidplot.xy.XYGraphWidget;
import com.androidplot.xy.XYPlot;
import com.androidplot.xy.XYSeries;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.text.DateFormat;
import java.text.DecimalFormat;
import java.text.FieldPosition;
import java.text.Format;
import java.text.ParsePosition;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Collections;
import java.util.Date;

public class SpectrumPlotActivity extends AppCompatActivity {

    private static final String TAG = "MicroSpec SpectrumPlotActivity";
    private static final int REQUEST_PERMISSION_WRITE = 1001;
    private XYPlot plot;

    private int[] specData = null;
    private double[] wavelengthVals = null;

    private boolean saveFilePermissionGranted;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_spectrum_plot);
        Toolbar toolbar = (Toolbar) findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);

        // initialize our XYPlot reference:
        plot = (XYPlot) findViewById(R.id.plot);

        //fetch the data that was sent with the intent
        Bundle extras = getIntent().getExtras();
        specData       = extras.getIntArray(MainActivity.SPEC_DATA_KEY);
        wavelengthVals = extras.getDoubleArray(MainActivity.WAVELENGTH_VALS_KEY);

        //convert the data into Number array types
        Float[] Y = new Float[specData.length];
        Float max_Y = 0.0f;
        for (int i = 0; i < specData.length; i++) {
            Float y = Float.valueOf(specData[i]);
            Y[i] = y;
            if (y > max_Y){
                max_Y = y;
            }
        }

        final Double[] X = new Double[wavelengthVals.length];
        for (int i = 0; i < wavelengthVals.length; i++) {
            X[i] = wavelengthVals[i];
        }

        //rescale the data
        for (int i = 0; i < Y.length; i++) {
            Y[i] /= max_Y;
        }

        // turn the above arrays into XYSeries':
        // (Y_VALS_ONLY means use the element index as the x value)
        XYSeries series1 = new SimpleXYSeries(Arrays.asList(X),Arrays.asList(Y), "Series1");


        // create formatters to use for drawing a series using LineAndPointRenderer
        // and configure them from xml:
        LineAndPointFormatter series1Format =
                new LineAndPointFormatter(this, R.xml.line_point_formatter_with_labels);

        // add a new series' to the xyplot:
        plot.addSeries(series1, series1Format);

        //pretty format the axes tick labels
        plot.getGraph().getLineLabelStyle(XYGraphWidget.Edge.BOTTOM).setFormat(new Format() {
            @Override
            public StringBuffer format(Object obj, StringBuffer toAppendTo, FieldPosition pos) {
                int x = Math.round(((Number) obj).intValue());
                return toAppendTo.append(x);
            }
            @Override
            public Object parseObject(String source, ParsePosition pos) {
                return null;
            }
        });

        plot.getGraph().getLineLabelStyle(XYGraphWidget.Edge.LEFT).setFormat(new Format() {
            @Override
            public StringBuffer format(Object obj, StringBuffer toAppendTo, FieldPosition pos) {
                DecimalFormat decimalFormat = new DecimalFormat("#.##");
                Float f = Float.valueOf(decimalFormat.format(obj));
                return toAppendTo.append(f);
            }
            @Override
            public Object parseObject(String source, ParsePosition pos) {
                return null;
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

        if (id == R.id.action_copy_clipboard){
            String dataExport = exportDataAsCSV();
            ClipboardManager clipboard = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            ClipData clip = ClipData.newPlainText("CSV Data", dataExport);
            clipboard.setPrimaryClip(clip);
            Toast.makeText(getApplicationContext(), "Data CSV copied to clipboard!", Toast.LENGTH_SHORT).show();
            return true;
        } else if (id == R.id.action_save_CSV_file){
            if(!saveFilePermissionGranted){
                if(!checkPermissions()){
                    return false;
                }
            }
            try {
                String root = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS).toString();
                File myDir = new File(root + "/spectra");
                myDir.mkdirs();
                if (!myDir.exists()) {
                    Toast.makeText(getApplicationContext(),"Directory does not exists: " + myDir, Toast.LENGTH_LONG).show();
                    Log.d(TAG, "Dir does not exist!");
                }
                Calendar c = Calendar.getInstance();
                SimpleDateFormat df = new SimpleDateFormat("yyyy-MM-dd_HH:mm:ss");
                String currentDateTimeString = df.format(c.getTime());
                //String currentDateTimeString = DateFormat.getDateTimeInstance().format(new Date());
                String fname = "MicroSpec-"+ currentDateTimeString + ".csv";
                File file = new File (myDir, fname);
                FileOutputStream out = null;
                try {
                    out = new FileOutputStream(file);
                    out.write(exportDataAsCSV().getBytes());
                } catch (FileNotFoundException e) {
                    e.printStackTrace();
                    Toast.makeText(getApplicationContext(), "Error: " + e.toString(), Toast.LENGTH_LONG).show();
                    return false;
                } catch (IOException e) {
                    e.printStackTrace();
                    Toast.makeText(getApplicationContext(), "Error: " + e.toString(), Toast.LENGTH_LONG).show();
                    return false;
                }finally {
                    try {
                        out.flush();
                        out.close();
                    } catch (IOException e) {
                        e.printStackTrace();
                        Toast.makeText(getApplicationContext(), "Error: " + e.toString(), Toast.LENGTH_LONG).show();
                        return false;
                    }
                }
                Toast.makeText(getApplicationContext(), "Data has been saved to file: " + fname, Toast.LENGTH_SHORT).show();
                return true;
            } catch (Exception e) {
                e.printStackTrace();
                Toast.makeText(getApplicationContext(), "Error: " + e.toString(), Toast.LENGTH_LONG).show();
                return false;
            }
        }
        //noinspection SimplifiableIfStatement
        else if (id == R.id.action_settings) {

            return true;
        }

        return super.onOptionsItemSelected(item);
    }

    public String exportDataAsCSV(){
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < wavelengthVals.length; i++) {
            sb.append(wavelengthVals[i]);
            sb.append(",");
            sb.append(specData[i]);
            sb.append("\r\n");
        }
        return sb.toString();
    }

    /* Checks if external storage is available for read and write */
    public boolean isExternalStorageWritable() {
        String state = Environment.getExternalStorageState();
        return Environment.MEDIA_MOUNTED.equals(state);
    }

    /* Checks if external storage is available to at least read */
    public boolean isExternalStorageReadable() {
        String state = Environment.getExternalStorageState();
        return (Environment.MEDIA_MOUNTED.equals(state) ||
                Environment.MEDIA_MOUNTED_READ_ONLY.equals(state));
    }

    // Initiate request for permissions.
    private boolean checkPermissions() {

        if (!isExternalStorageReadable() || !isExternalStorageWritable()) {
            Toast.makeText(this, "This app only works on devices with usable external storage",
                    Toast.LENGTH_SHORT).show();
            return false;
        }

        int permissionCheck = ContextCompat.checkSelfPermission(this,
                Manifest.permission.WRITE_EXTERNAL_STORAGE);
        if (permissionCheck != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE},
                    REQUEST_PERMISSION_WRITE);
            return false;
        } else {
            saveFilePermissionGranted = true;
            return true;
        }
    }

    // Handle permissions result
    @Override
    public void onRequestPermissionsResult(int requestCode,
                                           @NonNull String permissions[],
                                           @NonNull int[] grantResults) {
        switch (requestCode) {
            case REQUEST_PERMISSION_WRITE:
                if (grantResults.length > 0
                        && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                    saveFilePermissionGranted = true;
                    Toast.makeText(this, "External storage permission granted",
                            Toast.LENGTH_SHORT).show();
                } else {
                    Toast.makeText(this, "You must grant permission!", Toast.LENGTH_SHORT).show();
                }
                break;
        }
    }
}


