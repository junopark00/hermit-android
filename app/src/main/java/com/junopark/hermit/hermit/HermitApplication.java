package com.junopark.hermit.hermit;

import android.app.Application;

/**
 * Registered by the nonRoot manifest: installs the crash reporter before any activity starts,
 * and lets notices follow the screen that shows.
 */
public class HermitApplication extends Application {
    @Override
    public void onCreate() {
        super.onCreate();
        CrashReporter.install(this);
        HermitNotice.install(this);
    }
}
