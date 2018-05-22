package com.chulabhaya.indoorambienttemperaturepredictor;

import android.app.AppOpsManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.Build;
import android.provider.Settings;
import android.support.v4.app.ActivityCompat;
import android.support.v4.content.ContextCompat;
import android.support.v7.app.AppCompatActivity;
import android.Manifest;
import android.os.Bundle;
import android.view.View;
import android.view.View.OnClickListener;


public class MainActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        /* Obtains necessary permissions. */
        String[] permissions = {Manifest.permission.READ_PHONE_STATE};
        String[] permissionNames = {"READ_PHONE_STATE"};
        int permissionsCode = 1;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (!permissionsGranted(permissionNames)){
                ActivityCompat.requestPermissions(this, permissions, permissionsCode);
            }
        }

        /* Obtains further permissions needed for NetworkStatsManager. */
        if (!usageAccessGranted()){
            Intent intent = new Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS);
            startActivity(intent);
        }

        /* Implements functionality for prediction buttons. */
        OnClickListener listenerPredictionButtons = new OnClickListener(){
            @Override
            public void onClick(View view){
                Intent intent = new Intent(MainActivity.this, TemperaturePredictorService.class);
                switch (view.getId()){
                    case R.id.button_startPrediction:
                        // Start service
                        startService(intent);
                        break;
                    case R.id.button_stopPrediction:
                        stopService(intent);
                        break;
                }
            }
        };
        findViewById(R.id.button_startPrediction).setOnClickListener(listenerPredictionButtons);
        findViewById(R.id.button_stopPrediction).setOnClickListener(listenerPredictionButtons);
    }

    /* Called when user taps 'View Ambient Temperatures' button */
    public void viewTemperature(View view){
        Intent intent = new Intent(this, DisplayTemperatureActivity.class);
        startActivity(intent);
    }

    /* Check to see if usage access permission has been granted by user. */
    private boolean usageAccessGranted(){
        try{
            PackageManager packageManager = getPackageManager();
            ApplicationInfo applicationInfo = packageManager.getApplicationInfo(getPackageName(), 0);
            AppOpsManager appOpsManager = (AppOpsManager) getSystemService(Context.APP_OPS_SERVICE);
            int mode = 0;
            if (Build.VERSION.SDK_INT > Build.VERSION_CODES.KITKAT){
                assert appOpsManager != null;
                mode = appOpsManager.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, applicationInfo.uid, applicationInfo.packageName);
            }
            return (mode == AppOpsManager.MODE_ALLOWED);
        }catch (PackageManager.NameNotFoundException e){
            return false;
        }
    }

    /* Check to see if other permissions have been granted. */
    private boolean permissionsGranted(String[] permissionNames){
        boolean permissionsGranted = true;
        for (String permission: permissionNames){
            permissionsGranted = permissionsGranted && (ContextCompat.checkSelfPermission(getApplicationContext(), permission) == PackageManager.PERMISSION_GRANTED);
        }
        return permissionsGranted;
    }
}
