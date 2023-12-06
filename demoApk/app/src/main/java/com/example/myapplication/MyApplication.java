package com.example.myapplication;

import android.app.Application;
import android.content.Context;
import android.util.Log;

/**
 * 自定义Application
 */
public class MyApplication extends Application {

    @Override
    protected void attachBaseContext(Context base) {
        super.attachBaseContext(base);
        Log.e("MyApplication", "source attachBaseContext");
    }

    @Override
    public void onCreate() {
        super.onCreate();
        Log.e("MyApplication", "source onCreate");
    }

}
