package com.zhengde163.netguard;

/*
    This file is part of NetGuard.

    NetGuard is free software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version.

    NetGuard is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU General Public License for more details.

    You should have received a copy of the GNU General Public License
    along with NetGuard.  If not, see <http://www.gnu.org/licenses/>.

    Copyright 2015-2016 by Marcel Bokhorst (M66B)
*/

import android.app.Application;
import android.content.SharedPreferences;
import android.preference.PreferenceManager;

import com.gridsum.tracker.GridsumWebDissector;

public class ApplicationEx extends Application {
//    private static final String TAG = "NetGuard.App";

//    private Thread.UncaughtExceptionHandler mPrevHandler;

    @Override
    public void onCreate() {
        super.onCreate();
        GridsumWebDissector.getInstance().enableCrashHandler();
        GridsumWebDissector.getInstance().setApplication(this);
        GridsumWebDissector.getInstance().enableActivityTracking(this);
        String[] urls = {"http://zhengde-server.uni-analytics.com/recv/gs.gif"};
        GridsumWebDissector.getInstance().setUrls(urls);
        GridsumWebDissector.getInstance().enableDebug(false);
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
        String phoneNumber = prefs.getString("phonenumber", null);
        GridsumWebDissector.getInstance().trackJunctionPoint(phoneNumber, 1);
//        Log.i(TAG, "Create version=" + Util.getSelfVersionName(this) + "/" + Util.getSelfVersionCode(this));
//
//        mPrevHandler = Thread.getDefaultUncaughtExceptionHandler();
//        Thread.setDefaultUncaughtExceptionHandler(new Thread.UncaughtExceptionHandler() {
//            @Override
//            public void uncaughtException(Thread thread, Throwable ex) {
//                Log.e(TAG, ex.toString() + "\n" + Log.getStackTraceString(ex));
//                Util.sendCrashReport(ex, ApplicationEx.this);
//                if (mPrevHandler != null)
//                    mPrevHandler.uncaughtException(thread, ex);
//            }
//        });
    }
}
