package com.chulabhaya.indoorambienttemperaturepredictor;

import android.annotation.SuppressLint;
import android.app.ActivityManager;
import android.app.Service;
import android.app.usage.NetworkStats;
import android.app.usage.NetworkStatsManager;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.AssetManager;
import android.net.ConnectivityManager;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.Process;
import android.os.RemoteException;
import android.support.annotation.RequiresApi;
import android.support.v4.content.LocalBroadcastManager;
import android.telephony.TelephonyManager;
import android.util.Log;
import android.widget.Toast;

import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.text.DateFormat;
import java.text.DecimalFormat;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;

import weka.classifiers.trees.RandomForest;
import weka.core.Attribute;
import weka.core.DenseInstance;
import weka.core.Instances;
import weka.core.SerializationHelper;

/**
 * Created by Chulabhaya Wijesundara on 5/22/2018.
 */
public class TemperaturePredictorService extends Service {
    private Context context;
    private boolean isRunning = false;
    private TemperaturePredictorServiceHandler TemperaturePredictorServiceHandler;
    public static final String ACTION_PREDICTION_BROADCAST = TemperaturePredictorService.class.getName() + "PredictionBroadcast";
    public static final String EXTRA_PREDICTION = "extra_prediction";

    /* Machine learning model initialization. */
    private RandomForest modelRandomForest = null;

    /* Date and time formatting related things. */
    DecimalFormat decimalFormatTwoDecimals = new DecimalFormat("0.00");
    DecimalFormat decimalFormatFourDecimals = new DecimalFormat("0.0000");
    @SuppressLint("SimpleDateFormat")
    DateFormat dateFormat = new SimpleDateFormat("MM/dd/yyyy HH:mm:ss");

    @Override
    public void onCreate(){
        HandlerThread handlerThread = new HandlerThread("TemperaturePredictorThread", Process.THREAD_PRIORITY_BACKGROUND);
        handlerThread.start();
        Looper looper = handlerThread.getLooper();
        TemperaturePredictorServiceHandler = new TemperaturePredictorServiceHandler(looper);
        isRunning = true;
        context = getApplicationContext();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId){
        loadModel();
        Message message = TemperaturePredictorServiceHandler.obtainMessage();
        message.arg1 = startId;
        TemperaturePredictorServiceHandler.sendMessage(message);
        Toast.makeText(this, "Ambient temperature prediction started.", Toast.LENGTH_SHORT).show();

        // If service is killed while starting, it restarts
        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent){
        return null;
    }

    @Override
    public void onDestroy(){
        isRunning = false;
        Toast.makeText(this, "Ambient temperature prediction stopped.", Toast.LENGTH_SHORT).show();
    }

    /* Calculates and returns battery temperature. */
    public double getBatteryTemperature(){
        Intent intent = context.registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        assert intent != null;
        double celsius = ((double)intent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE,0)) / 10;
        return Double.valueOf(decimalFormatTwoDecimals.format(((celsius * 9) / 5) + 32));
    }

    /* Returns battery level. */
    private double getBatteryLevel(){
        Intent intent = context.registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        assert intent != null;
        return (double)intent.getIntExtra(BatteryManager.EXTRA_LEVEL,0);
    }

    /* Returns battery voltage. */
    private double getBatteryVoltage(){
        Intent intent = context.registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        assert intent != null;
        double batteryVoltage = (double)intent.getIntExtra(BatteryManager.EXTRA_VOLTAGE,0);
        batteryVoltage = Double.valueOf(decimalFormatFourDecimals.format(batteryVoltage / 1000));     /* Convert from millivolts to volts. */
        return batteryVoltage;
    }

    /* Returns instantaneous battery current. */
    private double getBatteryCurrent(){
        BatteryManager batteryManager = (BatteryManager) getSystemService(BATTERY_SERVICE);
        assert batteryManager != null;
        double batteryCurrent = (double)batteryManager.getLongProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW);
        batteryCurrent = Double.valueOf(decimalFormatFourDecimals.format(batteryCurrent*Math.pow(10, -6)));   /* Convert from microamps to amps. */
        return batteryCurrent;
    }

    /* Returns available memory as a percent value. */
    private double getAvailMemory(){
        ActivityManager.MemoryInfo memoryInfo = new ActivityManager.MemoryInfo();
        ActivityManager activityManager = (ActivityManager) getSystemService(ACTIVITY_SERVICE);
        assert activityManager != null;
        activityManager.getMemoryInfo(memoryInfo);
        double availPercent;
        availPercent = Double.valueOf(decimalFormatFourDecimals.format(memoryInfo.availMem / (double)memoryInfo.totalMem * 100.0));
        return availPercent;
    }

    /* Get network usage for WiFi. */
    private long getWiFiUsage(long previousUsage){
        long startTime = 0;
        long endTime = System.currentTimeMillis();
        NetworkStatsManager networkStatsManager = (NetworkStatsManager) getSystemService(NETWORK_STATS_SERVICE);
        NetworkStats.Bucket bucket;
        try{
            assert networkStatsManager != null;
            bucket = networkStatsManager.querySummaryForDevice(ConnectivityManager.TYPE_WIFI, "", startTime, endTime);
        }catch (RemoteException e){
            return -1;
        }
        long currentUsage = bucket.getRxPackets() + bucket.getTxPackets();
        return currentUsage - previousUsage;
    }

    /* Functions related to getting network usage for mobile data. */
    private long getDataUsage(long previousUsage){
        long startTime = 0;
        long endTime = System.currentTimeMillis();
        NetworkStatsManager networkStatsManager = (NetworkStatsManager) getSystemService(NETWORK_STATS_SERVICE);
        NetworkStats.Bucket bucket;
        try{
            assert networkStatsManager != null;
            bucket = networkStatsManager.querySummaryForDevice(ConnectivityManager.TYPE_MOBILE, getSubscriberId(ConnectivityManager.TYPE_MOBILE), startTime, endTime);
        }catch (RemoteException e){
            return -1;
        }
        long currentUsage = bucket.getRxPackets() + bucket.getTxPackets();
        return currentUsage - previousUsage;
    }

    @SuppressLint("MissingPermission")
    private String getSubscriberId(int networkType){
        if (ConnectivityManager.TYPE_MOBILE == networkType){
            TelephonyManager telephonyManager = (TelephonyManager) context.getSystemService(Context.TELEPHONY_SERVICE);
            assert telephonyManager != null;
            return telephonyManager.getSubscriberId();
        }
        return "";
    }

    /* Calculates and returns the CPU usage. */
    private double getCPULoad() {
        try {
            RandomAccessFile reader = new RandomAccessFile("/proc/stat", "r");
            String load = reader.readLine();

            String[] toks = load.split(" +");  // Split on one or more spaces

            long idle1 = Long.parseLong(toks[4]);
            long cpu1 = Long.parseLong(toks[2]) + Long.parseLong(toks[3]) + Long.parseLong(toks[5])
                    + Long.parseLong(toks[6]) + Long.parseLong(toks[7]) + Long.parseLong(toks[8]);

            try {
                Thread.sleep(400);
            } catch (Exception e) {
                e.printStackTrace();
            }

            reader.seek(0);
            load = reader.readLine();
            reader.close();

            toks = load.split(" +");

            long idle2 = Long.parseLong(toks[4]);
            long cpu2 = Long.parseLong(toks[2]) + Long.parseLong(toks[3]) + Long.parseLong(toks[5])
                    + Long.parseLong(toks[6]) + Long.parseLong(toks[7]) + Long.parseLong(toks[8]);

            double raw_load = (double)(Math.abs(cpu2 - cpu1)) / Math.abs((cpu2 + idle2) - (cpu1 + idle1));
            if (Double.isNaN(raw_load)){
                raw_load = 0.0;
            }
            raw_load = Double.valueOf(decimalFormatFourDecimals.format(raw_load*100));
            if (raw_load > 100){
                raw_load = 100.0;
            }
            return raw_load;

        } catch (IOException e) {
            e.printStackTrace();
        }

        return 0;
    }


    /* Methods associated with predicting ambient temperature. */
    private void loadModel(){
        try {
            AssetManager assetManager = getAssets();
            InputStream input = assetManager.open("random_forest_381.model");
            modelRandomForest = (RandomForest) SerializationHelper.read(input);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private double getPrediction(final double temp, final double level, final double voltage,
                                final double current, final double memory, final double cpu, final long wifi, final long data){
        double prediction = 0;

        if (modelRandomForest == null){
            Log.d("Model status", "Model not loaded!");
            return 0;
        }

        // List of attributes in data.
        final Attribute attributeBatteryTemp = new Attribute("Battery Temperature");
        final Attribute attributeBatteryLevel = new Attribute("Battery Level");
        final Attribute attributeBatteryVoltage = new Attribute("Voltage");
        final Attribute attributeBatteryCurrent = new Attribute("Current");
        final Attribute attributeBatteryMemory = new Attribute("Available Memory");
        final Attribute attributeCPUUsage = new Attribute("CPU");
        final Attribute attributeWiFi = new Attribute("WiFi");
        final Attribute attributeData = new Attribute("Data");
        final Attribute attributeAmbientTemp = new Attribute("Ambient Temperature");

        ArrayList<Attribute> attributeList = new ArrayList<Attribute>(2){
            {
                add(attributeBatteryTemp);
                add(attributeBatteryLevel);
                add(attributeBatteryVoltage);
                add(attributeBatteryCurrent);
                add(attributeBatteryMemory);
                add(attributeCPUUsage);
                add(attributeWiFi);
                add(attributeData);
                add(attributeAmbientTemp);
            }
        };

        Instances dataUnpredicted = new Instances("TestInstances", attributeList, 1);
        dataUnpredicted.setClassIndex(dataUnpredicted.numAttributes() - 1);

        // Create new instance based on input variables.
        DenseInstance newInstance = new DenseInstance(dataUnpredicted.numAttributes()){
            {
                setValue(attributeBatteryTemp, temp);
                setValue(attributeBatteryLevel , level);
                setValue(attributeBatteryVoltage, voltage);
                setValue(attributeBatteryCurrent, current);
                setValue(attributeBatteryMemory, memory);
                setValue(attributeCPUUsage, cpu);
                setValue(attributeWiFi, wifi);
                setValue(attributeData, data);
            }
        };

        newInstance.setDataset(dataUnpredicted);

        // Use instance to predict the ambient temperature.
        try{
            prediction = Double.valueOf(decimalFormatFourDecimals.format((modelRandomForest.classifyInstance(newInstance))));
        } catch (Exception e){
            e.printStackTrace();
        }
        return prediction;
    }

    private void sendBroadcastMessage(double prediction){
        if (prediction != 0){
            Intent intent = new Intent(ACTION_PREDICTION_BROADCAST);
            intent.putExtra(EXTRA_PREDICTION, prediction);
            LocalBroadcastManager.getInstance(this).sendBroadcast(intent);
        }
    }

    private final class TemperaturePredictorServiceHandler extends Handler {
        TemperaturePredictorServiceHandler(Looper looper){
            super(looper);
        }

        @RequiresApi(api = Build.VERSION_CODES.LOLLIPOP)
        @Override
        public void handleMessage(Message message){
            synchronized (this){
                while(isRunning){
                    try{
                        Date currentTime = Calendar.getInstance().getTime();
                        String currentTimeString = dateFormat.format(currentTime);
                        double battery_temp = getBatteryTemperature();
                        double battery_level = getBatteryLevel();
                        double battery_voltage = getBatteryVoltage();
                        double battery_current = getBatteryCurrent();
                        double available_memory = getAvailMemory();
                        long wifiUsagePrevious = getWiFiUsage(0);
                        long dataUsagePrevious = getDataUsage(0);
                        double cpu_load = getCPULoad();
                        Thread.sleep(500);
                        long wifiUsageCurrent = getWiFiUsage(wifiUsagePrevious);
                        long dataUsageCurrent = getDataUsage(dataUsagePrevious);
                        double prediction = getPrediction(battery_temp, battery_level, battery_voltage,
                                battery_current, available_memory, cpu_load, wifiUsageCurrent, dataUsageCurrent);
                        sendBroadcastMessage(prediction);
                        Log.i("ModelPrediction", "At " +currentTimeString+ ", the ambient temperature is: "+prediction);
                    }
                    catch (Exception e){
                        Log.i("TempPredService", e.getMessage());
                    }
                }
            }
            // Stops the service for the start Id
            stopSelfResult(message.arg1);
        }
    }
}
