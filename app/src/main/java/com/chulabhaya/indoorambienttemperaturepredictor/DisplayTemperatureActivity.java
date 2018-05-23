package com.chulabhaya.indoorambienttemperaturepredictor;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.support.v4.content.LocalBroadcastManager;
import android.support.v7.app.AppCompatActivity;
import android.os.Bundle;
import android.util.Log;
import android.widget.TextView;

public class DisplayTemperatureActivity extends AppCompatActivity implements SensorEventListener{
    private SensorManager mSensorManager;
    private Sensor mLight;
    private Sensor mProximity;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_display_temperature);

        /* Update TextView to display the temperature predictions. */
        final TextView textView = findViewById(R.id.textView_predTemperature);
        LocalBroadcastManager.getInstance(this).registerReceiver(
                new BroadcastReceiver() {
                    @Override
                    public void onReceive(Context context, Intent intent) {
                        double temperature = intent.getDoubleExtra(TemperaturePredictorService.EXTRA_PREDICTION, 0);
                        textView.setText("Predicted Temperature: " + temperature + " \u2109");
                    }
                }, new IntentFilter(TemperaturePredictorService.ACTION_PREDICTION_BROADCAST)
        );

        /* Deal with light and proximity sensors. */
        mSensorManager = (SensorManager) getSystemService(Context.SENSOR_SERVICE);

        /* Check if light and proximity sensors are available. */
        if (mSensorManager.getDefaultSensor(Sensor.TYPE_LIGHT) != null){
            mLight = mSensorManager.getDefaultSensor(Sensor.TYPE_LIGHT);
            Log.i("SensorUpdate", "Light sensor available");
        }
        else{
            Log.i("SensorUpdate", "Light sensor not available");
        }
        if (mSensorManager.getDefaultSensor(Sensor.TYPE_PROXIMITY) != null){
            mProximity = mSensorManager.getDefaultSensor(Sensor.TYPE_PROXIMITY);
            Log.i("SensorUpdate", "Proximity sensor available");
        }
        else{
            Log.i("SensorUpdate", "Proximity sensor not available");
        }
    }

    @Override
    public final void onAccuracyChanged(Sensor sensor, int accuracy){
        // Do nothing, sensor accuracy changing isn't taken into account
    }

    @Override
    public final void onSensorChanged(SensorEvent event){
        Sensor sensor = event.sensor;
        if (sensor.getType() == Sensor.TYPE_LIGHT){
            float lux = event.values[0];
            Log.i("SensorUpdate", "Light value: " + lux);
        }
        else if (sensor.getType() == Sensor.TYPE_PROXIMITY){
            float proximity = event.values[0];
            Log.i("SensorUpdate", "Proximity value: " + proximity);
        }
    }

    @Override
    protected void onResume(){
        super.onResume();
        mSensorManager.registerListener(this, mLight, SensorManager.SENSOR_DELAY_NORMAL);
        mSensorManager.registerListener(this, mProximity, SensorManager.SENSOR_DELAY_NORMAL);
    }
}
