package com.openeio.microspec;

import android.support.v7.app.AppCompatActivity;
import android.os.Bundle;

import com.androidplot.xy.LineAndPointFormatter;
import com.androidplot.xy.SimpleXYSeries;
import com.androidplot.xy.XYGraphWidget;
import com.androidplot.xy.XYPlot;
import com.androidplot.xy.XYSeries;

import java.text.DecimalFormat;
import java.text.FieldPosition;
import java.text.Format;
import java.text.ParsePosition;
import java.util.Arrays;
import java.util.Collections;

public class SpectrumPlotActivity extends AppCompatActivity {

    private XYPlot plot;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_spectrum_plot);

        // initialize our XYPlot reference:
        plot = (XYPlot) findViewById(R.id.plot);

        //fetch the data that was sent with the intent
        Bundle extras = getIntent().getExtras();
        int[] specData                = extras.getIntArray(MainActivity.SPEC_DATA_KEY);
        final double[] wavelengthVals = extras.getDoubleArray(MainActivity.WAVELENGTH_VALS_KEY);

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
            X[i] = Double.valueOf(wavelengthVals[i]);
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
}
