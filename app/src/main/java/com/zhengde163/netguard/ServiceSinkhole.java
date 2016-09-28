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

import android.annotation.TargetApi;
import android.app.Activity;
import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Typeface;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.TrafficStats;
import android.net.Uri;
import android.net.VpnService;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.Message;
import android.os.ParcelFileDescriptor;
import android.os.PowerManager;
import android.os.Process;
import android.os.SystemClock;
import android.preference.PreferenceManager;
import android.support.v4.app.NotificationCompat;
import android.support.v4.app.NotificationManagerCompat;
import android.support.v4.content.ContextCompat;
import android.support.v4.content.LocalBroadcastManager;
import android.telephony.PhoneStateListener;
import android.telephony.ServiceState;
import android.telephony.SubscriptionManager;
import android.telephony.TelephonyManager;
import android.text.Spannable;
import android.text.SpannableString;
import android.text.TextUtils;
import android.text.style.ForegroundColorSpan;
import android.text.style.StyleSpan;
import android.util.Log;
import android.util.Pair;
import android.util.TypedValue;
import android.widget.RemoteViews;

import com.gridsum.tracker.GridsumWebDissector;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InterfaceAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.net.URL;
import java.net.UnknownHostException;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

public class ServiceSinkhole extends VpnService implements SharedPreferences.OnSharedPreferenceChangeListener {
    private static final String TAG = "NetGuard.Service";
    private static final String UpdateUrl = "http://zhengde-server.uni-analytics.com/download/update.json";
    private String fileName;
    private boolean wifi;
    private boolean registeredInteractiveState = false;
    private boolean registeredPowerSave = false;
    private boolean registeredUser = false;
    private boolean registeredIdleState = false;
    private boolean registeredConnectivityChanged = false;
    private boolean registeredPackageAdded = false;

    private State state = State.none;
    private boolean user_foreground = true;
    private boolean last_connected = false;
    private boolean last_metered = true;
    private boolean last_interactive = false;
    private boolean powersaving = false;
    private boolean phone_state = false;
    private Object subscriptionsChangedListener = null;

    private Builder last_builder = null;
    private ParcelFileDescriptor vpn = null;

    private long last_hosts_modified = 0;
    private Map<String, Boolean> mapHostsBlocked = new HashMap<>();
    private Map<Integer, Boolean> mapUidAllowed = new HashMap<>();
    private Map<Integer, Integer> mapUidKnown = new HashMap<>();
    private Map<Long, Map<InetAddress, Boolean>> mapUidIPFilters = new HashMap<>();
    private Map<Integer, Forward> mapForward = new HashMap<>();
    private Map<Integer, Boolean> mapNoNotify = new HashMap<>();

    private volatile Looper commandLooper;
    private volatile Looper logLooper;
    private volatile Looper statsLooper;
    private volatile CommandHandler commandHandler;
    private volatile LogHandler logHandler;
    private volatile StatsHandler statsHandler;

    private static final int NOTIFY_ENFORCING = 1;
    private static final int NOTIFY_WAITING = 2;
    private static final int NOTIFY_DISABLED = 3;
    private static final int NOTIFY_AUTOSTART = 4;
    private static final int NOTIFY_EXIT = 5;
    private static final int NOTIFY_ERROR = 6;
    private static final int NOTIFY_TRAFFIC = 7;
    private static final int NOTIFY_UPDATE = 8;

    public static final String EXTRA_COMMAND = "Command";
    private static final String EXTRA_REASON = "Reason";
    public static final String EXTRA_NETWORK = "Network";
    public static final String EXTRA_UID = "UID";
    public static final String EXTRA_PACKAGE = "Package";
    public static final String EXTRA_BLOCKED = "Blocked";

    private static final int MSG_SERVICE_INTENT = 0;
    private static final int MSG_STATS_START = 1;
    private static final int MSG_STATS_STOP = 2;
    private static final int MSG_STATS_UPDATE = 3;
    private static final int MSG_PACKET = 4;
    private static final int MSG_USAGE = 5;

    private enum State {none, waiting, enforcing, stats}

    public enum Command {run, start, reload, stop, stats, set, householding}

    private static volatile PowerManager.WakeLock wlInstance = null;

    private static final String ACTION_HOUSE_HOLDING = "com.zhengde163.netguard.HOUSE_HOLDING";
    private static final String ACTION_SCREEN_OFF_DELAYED = "com.zhengde163.netguard.SCREEN_OFF_DELAYED";

    private native void jni_init();

    private native void jni_start(int tun, boolean fwd53, int loglevel);

    private native void jni_stop(int tun, boolean datagram, boolean stream);

    private native int jni_get_mtu();

    private native int[] jni_get_stats();

    private static native void jni_pcap(String name, int record_size, int file_size);

    private native void jni_done();

    public static void setPcap(boolean enabled, Context context) {
        File pcap = (enabled ? new File(context.getCacheDir(), "netguard.pcap") : null);
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        String r = prefs.getString("pcap_record_size", null);
        if (TextUtils.isEmpty(r))
            r = "64";
        String f = prefs.getString("pcap_file_size", null);
        if (TextUtils.isEmpty(f))
            f = "2";
        int record_size = Integer.parseInt(r);
        int file_size = Integer.parseInt(f) * 1024 * 1024;
        jni_pcap(pcap == null ? null : pcap.getAbsolutePath(), record_size, file_size);
    }

    synchronized private static PowerManager.WakeLock getLock(Context context) {
        if (wlInstance == null) {
            PowerManager pm = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
            wlInstance = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, context.getString(R.string.app_name) + " wakelock");
            wlInstance.setReferenceCounted(true);
        }
        return wlInstance;
    }

    private final class CommandHandler extends Handler {
        public int queue = 0;

        public CommandHandler(Looper looper) {
            super(looper);
        }

        private void reportQueueSize() {
            Intent ruleset = new Intent(ActivityMain.ACTION_QUEUE_CHANGED);
            ruleset.putExtra(ActivityMain.EXTRA_SIZE, queue);
            LocalBroadcastManager.getInstance(ServiceSinkhole.this).sendBroadcast(ruleset);
        }

        public void queue(Intent intent) {
            synchronized (this) {
                queue++;
                reportQueueSize();
            }
            Message msg = commandHandler.obtainMessage();
            msg.obj = intent;
            msg.what = MSG_SERVICE_INTENT;
            commandHandler.sendMessage(msg);
        }

        @Override
        public void handleMessage(Message msg) {
            try {
                switch (msg.what) {
                    case MSG_SERVICE_INTENT:
                        handleIntent((Intent) msg.obj);
                        break;
                    default:
                        Log.e(TAG, "Unknown command message=" + msg.what);
                }
            } catch (Throwable ex) {
                Log.e(TAG, ex.toString() + "\n" + Log.getStackTraceString(ex));
                Util.sendCrashReport(ex, ServiceSinkhole.this);
            } finally {
                synchronized (this) {
                    queue--;
                    reportQueueSize();
                }
                try {
                    PowerManager.WakeLock wl = getLock(ServiceSinkhole.this);
                    if (wl.isHeld())
                        wl.release();
                    else
                        Log.w(TAG, "Wakelock under-locked");
                    Log.i(TAG, "Messages=" + hasMessages(0) + " wakelock=" + wlInstance.isHeld());
                } catch (Throwable ex) {
                    Log.e(TAG, ex.toString() + "\n" + Log.getStackTraceString(ex));
                    Util.sendCrashReport(ex, ServiceSinkhole.this);
                }
            }
        }

        private void handleIntent(Intent intent) {
            final SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(ServiceSinkhole.this);

            Command cmd = (Command) intent.getSerializableExtra(EXTRA_COMMAND);
            String reason = intent.getStringExtra(EXTRA_REASON);
            Log.i(TAG, "Executing intent=" + intent + " command=" + cmd + " reason=" + reason +
                    " vpn=" + (vpn != null) + " user=" + (Process.myUid() / 100000));

            // Check if prepared
            if (cmd == Command.start || cmd == Command.reload)
                if (VpnService.prepare(ServiceSinkhole.this) != null) {
                    Log.w(TAG, "VPN not prepared");
                    prefs.edit().putBoolean("enabled", false).apply();
                    showAutoStartNotification();
                    return;
                }

            // Check if foreground
            if (cmd != Command.stop)
                if (!user_foreground) {
                    Log.i(TAG, "Command " + cmd + "ignored for background user");
                    return;
                }

            // Listen for phone state changes
            TelephonyManager tm = (TelephonyManager) getSystemService(Context.TELEPHONY_SERVICE);
            if (tm != null && !phone_state &&
                    Util.hasPhoneStatePermission(ServiceSinkhole.this)) {
                tm.listen(phoneStateListener, PhoneStateListener.LISTEN_DATA_CONNECTION_STATE | PhoneStateListener.LISTEN_SERVICE_STATE);
                phone_state = true;
                Log.i(TAG, "Listening to service state changes");
            }

            // Listen for data SIM changes
            if (subscriptionsChangedListener == null &&
                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP_MR1 &&
                    Util.hasPhoneStatePermission(ServiceSinkhole.this)) {
                SubscriptionManager sm = SubscriptionManager.from(ServiceSinkhole.this);
                subscriptionsChangedListener = new SubscriptionManager.OnSubscriptionsChangedListener() {
                    @Override
                    public void onSubscriptionsChanged() {
                        Log.i(TAG, "Subscriptions changed");
                        if (prefs.getBoolean("national_roaming", false))
                            ServiceSinkhole.reload("Subscriptions changed", ServiceSinkhole.this);
                    }
                };
                sm.addOnSubscriptionsChangedListener((SubscriptionManager.OnSubscriptionsChangedListener) subscriptionsChangedListener);
                Log.i(TAG, "Listening to subscription changes");
            }

            try {
                switch (cmd) {
                    case run:
                        run();
                        break;

                    case start:
                        start();
                        break;

                    case reload:
                        reload();
                        break;

                    case stop:
                        stop();
                        break;

                    case stats:
                        statsHandler.sendEmptyMessage(MSG_STATS_STOP);
                        statsHandler.sendEmptyMessage(MSG_STATS_START);
                        break;

                    case householding:
                        householding(intent);
                        break;

                    default:
                        Log.e(TAG, "Unknown command=" + cmd);
                }

                // Update main view
                Intent ruleset = new Intent(ActivityMain.ACTION_RULES_CHANGED);
                ruleset.putExtra(ActivityMain.EXTRA_CONNECTED, cmd == Command.stop ? false : last_connected);
                ruleset.putExtra(ActivityMain.EXTRA_METERED, cmd == Command.stop ? false : last_metered);
                LocalBroadcastManager.getInstance(ServiceSinkhole.this).sendBroadcast(ruleset);

                // Update widgets
                Widget.updateWidgets(ServiceSinkhole.this);

            } catch (Throwable ex) {
                Log.e(TAG, ex.toString() + "\n" + Log.getStackTraceString(ex));

                if (ex instanceof IllegalStateException)
                    showErrorNotification(4, getString(R.string.msg_start_failed));
                else {
                    // Disable firewall
                    prefs.edit().putBoolean("enabled", false).apply();
                    Widget.updateWidgets(ServiceSinkhole.this);

                    showExitNotification(ex.toString());

                    // Report exception
                    Util.sendCrashReport(ex, ServiceSinkhole.this);
                }
            }
        }

        private void run() {
            if (state == State.none) {
                startForeground(NOTIFY_WAITING, getWaitingNotification());
                state = State.waiting;
                Log.d(TAG, "Start foreground state=" + state.toString());
            }
        }

        private void start() {
            if (vpn == null) {
                if (state != State.none) {
                    Log.d(TAG, "Stop foreground state=" + state.toString());
                    stopForeground(true);
                }
                startForeground(NOTIFY_ENFORCING, getEnforcingNotification(0, 0, 0));
                state = State.enforcing;
                Log.d(TAG, "Start foreground state=" + state.toString());

                List<Rule> listRule = Rule.getRules(true, ServiceSinkhole.this);
                List<Rule> listAllowed = getAllowedRules(listRule);

                last_builder = getBuilder(listAllowed, listRule);
                vpn = startVPN(last_builder);
                if (vpn == null)
                    throw new IllegalStateException("VPN start failed");

                startNative(vpn, listAllowed, listRule);

                removeWarningNotifications();
                updateEnforcingNotification(listAllowed.size(), listRule.size());
            }
        }

        private void reload() {
            SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(ServiceSinkhole.this);

            if (state != State.enforcing) {
                if (state != State.none) {
                    Log.d(TAG, "Stop foreground state=" + state.toString());
                    stopForeground(true);
                }
                startForeground(NOTIFY_ENFORCING, getEnforcingNotification(0, 0, 0));
                state = State.enforcing;
                Log.d(TAG, "Start foreground state=" + state.toString());
            }

            List<Rule> listRule = Rule.getRules(true, ServiceSinkhole.this);
            List<Rule> listAllowed = getAllowedRules(listRule);
            Builder builder = getBuilder(listAllowed, listRule);

            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP_MR1) {
                last_builder = builder;
                Log.i(TAG, "Legacy restart");

                if (vpn != null) {
                    stopNative(vpn, false, false);
                    stopVPN(vpn);
                    vpn = null;
                    try {
                        Thread.sleep(500);
                    } catch (InterruptedException ignored) {
                    }
                }
                vpn = startVPN(last_builder);

            } else {
                if (vpn != null && prefs.getBoolean("filter", false) && builder.equals(last_builder)) {
                    Log.i(TAG, "Native restart");
                    stopNative(vpn, false, false);

                } else {
                    last_builder = builder;
                    Log.i(TAG, "VPN restart");

                    // Attempt seamless handover
                    ParcelFileDescriptor prev = vpn;
                    vpn = startVPN(builder);

                    if (prev != null && vpn == null) {
                        Log.w(TAG, "Handover failed");
                        stopNative(prev, false, false);
                        stopVPN(prev);
                        prev = null;
                        try {
                            Thread.sleep(3000);
                        } catch (InterruptedException ignored) {
                        }
                        vpn = startVPN(last_builder);
                        if (vpn == null)
                            throw new IllegalStateException("Handover failed");
                    }

                    if (prev != null) {
                        stopNative(prev, false, false);
                        stopVPN(prev);
                    }
                }
            }

            if (vpn == null)
                throw new IllegalStateException("VPN start failed");

            startNative(vpn, listAllowed, listRule);

            removeWarningNotifications();
            updateEnforcingNotification(listAllowed.size(), listRule.size());
        }

        private void stop() {
            if (vpn != null) {
                stopNative(vpn, true, true);
                stopVPN(vpn);
                vpn = null;
                unprepare();
            }
            if (state == State.enforcing) {
                Log.d(TAG, "Stop foreground state=" + state.toString());
                stopForeground(true);
                SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(ServiceSinkhole.this);
                if (prefs.getBoolean("show_stats", false)) {
                    startForeground(NOTIFY_WAITING, getWaitingNotification());
                    state = State.waiting;
                    Log.d(TAG, "Start foreground state=" + state.toString());
                } else
                    state = State.none;
            }
        }

        private void householding(Intent intent) {
            // Keep log records for three days
            DatabaseHelper.getInstance(ServiceSinkhole.this).cleanupLog(new Date().getTime() - 3 * 24 * 3600 * 1000L);

            // Keep DNS records for a week
            DatabaseHelper.getInstance(ServiceSinkhole.this).cleanupDns(new Date().getTime() - 7 * 24 * 3600 * 1000L);

            // Check for update
            SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(ServiceSinkhole.this);
            if (!Util.isPlayStoreInstall(ServiceSinkhole.this) && prefs.getBoolean("update_check", true))
                checkUpdate();
        }

        private void checkUpdate() {
            StringBuilder json = new StringBuilder();
            HttpURLConnection urlConnection = null;
            try {
                URL url = new URL(UpdateUrl);
                urlConnection = (HttpURLConnection) url.openConnection();
                BufferedReader br = new BufferedReader(new InputStreamReader(urlConnection.getInputStream()));

                String line;
                while ((line = br.readLine()) != null)
                    json.append(line);

            } catch (Throwable ex) {
                Log.e(TAG, ex.toString() + "\n" + Log.getStackTraceString(ex));
            } finally {
                if (urlConnection != null)
                    urlConnection.disconnect();
            }

            try {
                JSONObject jroot = new JSONObject(json.toString());
                if (jroot.has("tag_name") && jroot.has("assets")) {
                    JSONArray jassets = jroot.getJSONArray("assets");
                    if (jassets.length() > 0) {
                        JSONObject jasset = jassets.getJSONObject(0);
                        if (jasset.has("name") && jasset.has("browser_download_url")) {
                            String version = jroot.getString("tag_name");
                            String name = jasset.getString("name");
                            String url = jasset.getString("browser_download_url");
                            setFileName(url);
                            Log.i(TAG, "Tag " + version + " name " + name + " url " + url);

                            Version current = new Version(Util.getSelfVersionName(ServiceSinkhole.this));
                            Version available = new Version(version);
                            if (current.compareTo(available) < 0) {
                                Log.i(TAG, "Update available from " + current + " to " + available);
                                downLoadFile(url);
                                showUpdateNotification(name, url);
                            } else
                                Log.i(TAG, "Up-to-date current version " + current);
                        }
                    }
                }
            } catch (JSONException ex) {
                Log.e(TAG, ex.toString() + "\n" + Log.getStackTraceString(ex));
            }
        }
    }

    protected void downLoadFile(final String httpUrl) {
        if (wifi) {
            new Thread() {
                public void run() {
                    File tmpFile = new File(Environment.getExternalStorageDirectory(), fileName);
                    if (!tmpFile.exists()) {
                        try {
                            tmpFile.createNewFile();
                        } catch (Exception e) {
                            // Do nothing.
                        }
                    }
                    final File file = new File(Environment.getExternalStorageDirectory(), fileName);
                    try {
                        URL url = new URL(httpUrl);
                        HttpURLConnection urlConnection = (HttpURLConnection) url.openConnection();
                        urlConnection.setConnectTimeout(10000);
                        InputStream is = urlConnection.getInputStream();
                        FileOutputStream fos = new FileOutputStream(file);
                        byte[] buf = new byte[256];
                        urlConnection.connect();
                        if (urlConnection.getResponseCode() == 200) {
                            while (is != null) {
                                int numRead = is.read(buf);
                                if (numRead <= 0) {
                                    break;
                                } else {
                                    fos.write(buf, 0, numRead);
                                }
                            }
                        }
                        urlConnection.disconnect();
                        fos.close();
                        if (is != null) {
                            is.close();
                        }
                    } catch (Exception e) {
                        // Do nothing.
                    }
                }
            }.start();
        }

    }

    private void setFileName(String url) {
        String[] all = url.split("/");
        fileName = all[all.length - 1];
    }

    private final class LogHandler extends Handler {
        public LogHandler(Looper looper) {
            super(looper);
        }

        @Override
        public void handleMessage(Message msg) {
            try {
                if (powersaving && (msg.what == MSG_PACKET || msg.what == MSG_USAGE))
                    return;

                switch (msg.what) {
                    case MSG_PACKET:
                        log((Packet) msg.obj, msg.arg1, msg.arg2 > 0);
                        break;

                    case MSG_USAGE:
                        usage((Usage) msg.obj);
                        break;

                    default:
                        Log.e(TAG, "Unknown log message=" + msg.what);
                }
            } catch (Throwable ex) {
                Log.e(TAG, ex.toString() + "\n" + Log.getStackTraceString(ex));
                Util.sendCrashReport(ex, ServiceSinkhole.this);
            }
        }

        private void log(Packet packet, int connection, boolean interactive) {
            // Get settings
            SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(ServiceSinkhole.this);
            boolean log = prefs.getBoolean("log", false);
            boolean log_app = prefs.getBoolean("log_app", false);

            DatabaseHelper dh = DatabaseHelper.getInstance(ServiceSinkhole.this);
            try {
                Cursor cursor = dh.getStorage();
                int colTime = cursor.getColumnIndex("time");
                int colApp = cursor.getColumnIndex("app");
                int colVersion = cursor.getColumnIndex("version");
                int colProtocol = cursor.getColumnIndex("protocol");
                int colDIP = cursor.getColumnIndex("dIP");
                int colDPort = cursor.getColumnIndex("dPort");
                while (cursor.moveToNext()) {
                    String time = cursor.getString(colTime);
                    String version = cursor.getString(colVersion);
                    String protocol = cursor.getString(colProtocol);
                    String dIP = cursor.getString(colDIP);
                    String dPort = cursor.getString(colDPort);
                    String app = cursor.getString(colApp);
                    if (wifi) {
                        GridsumWebDissector.getInstance().trackEvent(new Activity(), "log", "send log", "network request", 101, getPairs(time, app, version, protocol, dIP, dPort));
                        dh.deleteStorage(time);
                        Log.i("failedmsg", "ok");
                    }
                }
            } catch (Exception e) {
                // Do nothing.
            }
            // Get real name
            String dname = dh.getQName(packet.daddr);
            dh.insertLog(packet, dname, connection, interactive);
            String time = Long.toString(packet.time);
            String app = Util.getApplicationNames(packet.uid, getApplicationContext()) + "";
            String version = packet.version == 4 ? "TCP" : "UDP";
            String protocol = packet.protocol == 6 ? "IPv4" : "IPv6";
            String dIP = packet.daddr;
            String dPort = packet.dport + "";
            if (wifi) {
                GridsumWebDissector.getInstance().trackEvent(new Activity(), "log", "send log", "network request", 101, getPairs(time, app, version, protocol, dIP, dPort));
            } else {
                dh.insertStorage(time, app, version, protocol, dIP, dPort);
            }

            if (log_app && packet.uid >= 0 && !(packet.uid == 0 && packet.protocol == 17 && packet.dport == 53)) {
                if (!(packet.protocol == 6 /* TCP */ || packet.protocol == 17 /* UDP */))
                    packet.dport = 0;
                if (dh.updateAccess(packet, dname, -1))
                    if (mapNoNotify.containsKey(packet.uid) && mapNoNotify.get(packet.uid))
                        showAccessNotification(packet.uid);
            }
        }

        private void usage(Usage usage) {
            if (usage.Uid >= 0 && !(usage.Uid == 0 && usage.Protocol == 17 && usage.DPort == 53)) {
                SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(ServiceSinkhole.this);
                boolean filter = prefs.getBoolean("filter", false);
                boolean log_app = prefs.getBoolean("log_app", false);
                boolean track_usage = prefs.getBoolean("track_usage", false);
                if (filter && log_app && track_usage) {
                    DatabaseHelper dh = DatabaseHelper.getInstance(ServiceSinkhole.this);
                    String dname = dh.getQName(usage.DAddr);
                    Log.i(TAG, "Usage account " + usage + " dname=" + dname);
                    dh.updateUsage(usage, dname);
                }
            }
        }

        private ArrayList<Pair<String, String>> getPairs(String time, String app, String version, String protocol, String dIP, String dPort) {
            ArrayList<Pair<String, String>> pairs = new ArrayList<>();
            pairs.add(new Pair<>("time", time));
            pairs.add(new Pair<>("app", app));
            pairs.add(new Pair<>("version", version));
            pairs.add(new Pair<>("protocol", protocol));
            pairs.add(new Pair<>("dIP", dIP));
            pairs.add(new Pair<>("dPort", dPort));
            return pairs;
        }

    }

    private final class StatsHandler extends Handler {
        private boolean stats = false;
        private long when;

        private long t = -1;
        private long tx = -1;
        private long rx = -1;

        private List<Long> gt = new ArrayList<>();
        private List<Float> gtx = new ArrayList<>();
        private List<Float> grx = new ArrayList<>();

        private HashMap<Integer, Long> mapUidBytes = new HashMap<>();

        public StatsHandler(Looper looper) {
            super(looper);
        }

        @Override
        public void handleMessage(Message msg) {
            try {
                switch (msg.what) {
                    case MSG_STATS_START:
                        startStats();
                        break;

                    case MSG_STATS_STOP:
                        stopStats();
                        break;

                    case MSG_STATS_UPDATE:
                        updateStats();
                        break;

                    default:
                        Log.e(TAG, "Unknown stats message=" + msg.what);
                }
            } catch (Throwable ex) {
                Log.e(TAG, ex.toString() + "\n" + Log.getStackTraceString(ex));
                Util.sendCrashReport(ex, ServiceSinkhole.this);
            }
        }

        private void startStats() {
            SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(ServiceSinkhole.this);
            boolean enabled = (!stats && prefs.getBoolean("show_stats", false));
            Log.i(TAG, "Stats start enabled=" + enabled);
            if (enabled) {
                when = new Date().getTime();
                t = -1;
                tx = -1;
                rx = -1;
                gt.clear();
                gtx.clear();
                grx.clear();
                mapUidBytes.clear();
                stats = true;
                updateStats();
            }
        }

        private void stopStats() {
            Log.i(TAG, "Stats stop");
            stats = false;
            this.removeMessages(MSG_STATS_UPDATE);
            if (state == State.stats) {
                Log.d(TAG, "Stop foreground state=" + state.toString());
                stopForeground(true);
                state = State.none;
            } else
                NotificationManagerCompat.from(ServiceSinkhole.this).cancel(NOTIFY_TRAFFIC);
        }

        private void updateStats() {
            RemoteViews remoteViews = new RemoteViews(getPackageName(), R.layout.traffic);
            SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(ServiceSinkhole.this);
            long frequency = Long.parseLong(prefs.getString("stats_frequency", "1000"));
            long samples = Long.parseLong(prefs.getString("stats_samples", "90"));
            boolean filter = prefs.getBoolean("filter", false);
            boolean show_top = prefs.getBoolean("show_top", false);
            int loglevel = Integer.parseInt(prefs.getString("loglevel", Integer.toString(Log.WARN)));

            // Schedule next update
            this.sendEmptyMessageDelayed(MSG_STATS_UPDATE, frequency);

            long ct = SystemClock.elapsedRealtime();

            // Cleanup
            while (gt.size() > 0 && ct - gt.get(0) > samples * 1000) {
                gt.remove(0);
                gtx.remove(0);
                grx.remove(0);
            }

            // Calculate network speed
            float txsec = 0;
            float rxsec = 0;
            long ttx = TrafficStats.getTotalTxBytes();
            long trx = TrafficStats.getTotalRxBytes();
            if (filter) {
                ttx -= TrafficStats.getUidTxBytes(Process.myUid());
                trx -= TrafficStats.getUidRxBytes(Process.myUid());
                if (ttx < 0)
                    ttx = 0;
                if (trx < 0)
                    trx = 0;
            }
            if (t > 0 && tx > 0 && rx > 0) {
                float dt = (ct - t) / 1000f;
                txsec = (ttx - tx) / dt;
                rxsec = (trx - rx) / dt;
                gt.add(ct);
                gtx.add(txsec);
                grx.add(rxsec);
            }

            // Calculate application speeds
            if (show_top) {
                if (mapUidBytes.size() == 0) {
                    for (ApplicationInfo ainfo : getPackageManager().getInstalledApplications(0))
                        if (ainfo.uid != Process.myUid())
                            mapUidBytes.put(ainfo.uid, TrafficStats.getUidTxBytes(ainfo.uid) + TrafficStats.getUidRxBytes(ainfo.uid));

                } else if (t > 0) {
                    TreeMap<Float, Integer> mapSpeedUid = new TreeMap<>(new Comparator<Float>() {
                        @Override
                        public int compare(Float value, Float other) {
                            return -value.compareTo(other);
                        }
                    });
                    float dt = (ct - t) / 1000f;
                    for (int uid : mapUidBytes.keySet()) {
                        long bytes = TrafficStats.getUidTxBytes(uid) + TrafficStats.getUidRxBytes(uid);
                        float speed = (bytes - mapUidBytes.get(uid)) / dt;
                        if (speed > 0) {
                            mapSpeedUid.put(speed, uid);
                            mapUidBytes.put(uid, bytes);
                        }
                    }

                    StringBuilder sb = new StringBuilder();
                    int i = 0;
                    for (float speed : mapSpeedUid.keySet()) {
                        if (i++ >= 3)
                            break;
                        if (speed < 1000 * 1000)
                            sb.append(getString(R.string.msg_kbsec, speed / 1000));
                        else
                            sb.append(getString(R.string.msg_mbsec, speed / 1000 / 1000));
                        sb.append(' ');
                        List<String> apps = Util.getApplicationNames(mapSpeedUid.get(speed), ServiceSinkhole.this);
                        sb.append(apps.size() > 0 ? apps.get(0) : "?");
                        sb.append("\r\n");
                    }
                    if (sb.length() > 0)
                        sb.setLength(sb.length() - 2);
                    remoteViews.setTextViewText(R.id.tvTop, sb.toString());
                }
            }

            t = ct;
            tx = ttx;
            rx = trx;

            // Create bitmap
            int height = Util.dips2pixels(96, ServiceSinkhole.this);
            int width = Util.dips2pixels(96 * 5, ServiceSinkhole.this);
            Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);

            // Create canvas
            Canvas canvas = new Canvas(bitmap);
            canvas.drawColor(Color.TRANSPARENT);

            // Determine max
            float max = 0;
            long xmax = 0;
            float ymax = 0;
            for (int i = 0; i < gt.size(); i++) {
                long t = gt.get(i);
                float tx = gtx.get(i);
                float rx = grx.get(i);
                if (t > xmax)
                    xmax = t;
                if (tx > max)
                    max = tx;
                if (rx > max)
                    max = rx;
                if (tx > ymax)
                    ymax = tx;
                if (rx > ymax)
                    ymax = rx;
            }

            // Build paths
            Path ptx = new Path();
            Path prx = new Path();
            for (int i = 0; i < gtx.size(); i++) {
                float x = width - width * (xmax - gt.get(i)) / 1000f / samples;
                float ytx = height - height * gtx.get(i) / ymax;
                float yrx = height - height * grx.get(i) / ymax;
                if (i == 0) {
                    ptx.moveTo(x, ytx);
                    prx.moveTo(x, yrx);
                } else {
                    ptx.lineTo(x, ytx);
                    prx.lineTo(x, yrx);
                }
            }

            // Build paint
            Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
            paint.setStyle(Paint.Style.STROKE);

            // Draw scale line
            paint.setStrokeWidth(Util.dips2pixels(1, ServiceSinkhole.this));
            paint.setColor(ContextCompat.getColor(ServiceSinkhole.this, R.color.colorGrayed));
            float y = height / 2;
            canvas.drawLine(0, y, width, y, paint);

            // Draw paths
            paint.setStrokeWidth(Util.dips2pixels(2, ServiceSinkhole.this));
            paint.setColor(ContextCompat.getColor(ServiceSinkhole.this, R.color.colorSend));
            canvas.drawPath(ptx, paint);
            paint.setColor(ContextCompat.getColor(ServiceSinkhole.this, R.color.colorReceive));
            canvas.drawPath(prx, paint);

            // Update remote view
            remoteViews.setImageViewBitmap(R.id.ivTraffic, bitmap);
            if (txsec < 1000 * 1000)
                remoteViews.setTextViewText(R.id.tvTx, getString(R.string.msg_kbsec, txsec / 1000));
            else
                remoteViews.setTextViewText(R.id.tvTx, getString(R.string.msg_mbsec, txsec / 1000 / 1000));

            if (rxsec < 1000 * 1000)
                remoteViews.setTextViewText(R.id.tvRx, getString(R.string.msg_kbsec, rxsec / 1000));
            else
                remoteViews.setTextViewText(R.id.tvRx, getString(R.string.msg_mbsec, rxsec / 1000 / 1000));

            if (max < 1000 * 1000)
                remoteViews.setTextViewText(R.id.tvMax, getString(R.string.msg_kbsec, max / 2 / 1000));
            else
                remoteViews.setTextViewText(R.id.tvMax, getString(R.string.msg_mbsec, max / 2 / 1000 / 1000));

            // Show session/file count
            if (filter && loglevel <= Log.WARN) {
                int[] count = jni_get_stats();
                remoteViews.setTextViewText(R.id.tvSessions, count[0] + "/" + count[1] + "/" + count[2]);
                remoteViews.setTextViewText(R.id.tvFiles, count[3] + "/" + count[4]);
            } else {
                remoteViews.setTextViewText(R.id.tvSessions, "");
                remoteViews.setTextViewText(R.id.tvFiles, "");
            }

            // Show notification
            Intent main = new Intent(ServiceSinkhole.this, ActivityMain.class);
            PendingIntent pi = PendingIntent.getActivity(ServiceSinkhole.this, 0, main, PendingIntent.FLAG_UPDATE_CURRENT);

            TypedValue tv = new TypedValue();
            getTheme().resolveAttribute(R.attr.colorPrimary, tv, true);
            NotificationCompat.Builder builder = new NotificationCompat.Builder(ServiceSinkhole.this)
                    .setWhen(when)
                    .setSmallIcon(R.drawable.ic_equalizer_white_24dp)
                    .setContent(remoteViews)
                    .setContentIntent(pi)
                    .setColor(tv.data)
                    .setOngoing(true)
                    .setAutoCancel(false);

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                builder.setCategory(Notification.CATEGORY_STATUS)
                        .setVisibility(Notification.VISIBILITY_PUBLIC);
            }

            if (state == State.none || state == State.waiting) {
                if (state != State.none) {
                    Log.d(TAG, "Stop foreground state=" + state.toString());
                    stopForeground(true);
                }
                startForeground(NOTIFY_TRAFFIC, builder.build());
                state = State.stats;
                Log.d(TAG, "Start foreground state=" + state.toString());
            } else
                NotificationManagerCompat.from(ServiceSinkhole.this).notify(NOTIFY_TRAFFIC, builder.build());
        }
    }

    public static List<InetAddress> getDns(Context context) {
        List<InetAddress> listDns = new ArrayList<>();

        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        List<String> sysDns = Util.getDefaultDNS(context);
        String vpnDns = prefs.getString("dns", null);
        Log.i(TAG, "DNS system=" + TextUtils.join(",", sysDns) + " VPN=" + vpnDns);

        if (vpnDns != null)
            try {
                InetAddress dns = InetAddress.getByName(vpnDns);
                if (!(dns.isLoopbackAddress() || dns.isAnyLocalAddress()))
                    listDns.add(dns);
            } catch (Throwable ignored) {
            }

        for (String def_dns : sysDns)
            try {
                InetAddress ddns = InetAddress.getByName(def_dns);
                if (!listDns.contains(ddns) &&
                        !(ddns.isLoopbackAddress() || ddns.isAnyLocalAddress()))
                    listDns.add(ddns);
            } catch (Throwable ignored) {
            }

        return listDns;
    }

    @TargetApi(Build.VERSION_CODES.LOLLIPOP)
    private ParcelFileDescriptor startVPN(Builder builder) throws SecurityException {
        try {
            return builder.establish();
        } catch (SecurityException ex) {
            throw ex;
        } catch (Throwable ex) {
            Log.e(TAG, ex.toString() + "\n" + Log.getStackTraceString(ex));
            return null;
        }
    }

    private Builder getBuilder(List<Rule> listAllowed, List<Rule> listRule) {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
        boolean subnet = prefs.getBoolean("subnet", false);
        boolean tethering = prefs.getBoolean("tethering", false);
        boolean lan = prefs.getBoolean("lan", false);
        boolean ip6 = prefs.getBoolean("ip6", true);
        boolean filter = prefs.getBoolean("filter", false);
        boolean system = prefs.getBoolean("manage_system", false);

        // Build VPN service
        Builder builder = new Builder();
        builder.setSession(getString(R.string.app_name));

        // VPN address
        String vpn4 = prefs.getString("vpn4", "10.1.10.1");
        Log.i(TAG, "vpn4=" + vpn4);
        builder.addAddress(vpn4, 32);
        if (ip6) {
            String vpn6 = prefs.getString("vpn6", "fd00:1:fd00:1:fd00:1:fd00:1");
            Log.i(TAG, "vpn6=" + vpn6);
            builder.addAddress(vpn6, 128);
        }

        // DNS address
        if (filter)
            for (InetAddress dns : getDns(ServiceSinkhole.this)) {
                if (ip6 || dns instanceof Inet4Address) {
                    Log.i(TAG, "dns=" + dns);
                    builder.addDnsServer(dns);
                }
            }

        // Subnet routing
        if (subnet) {
            // Exclude IP ranges
            List<IPUtil.CIDR> listExclude = new ArrayList<>();
            listExclude.add(new IPUtil.CIDR("127.0.0.0", 8)); // localhost

            if (tethering) {
                // USB Tethering 192.168.42.x
                // Wi-Fi Tethering 192.168.43.x
                listExclude.add(new IPUtil.CIDR("192.168.42.0", 23));
            }

            if (lan) {
                try {
                    Enumeration<NetworkInterface> nis = NetworkInterface.getNetworkInterfaces();
                    while (nis.hasMoreElements()) {
                        NetworkInterface ni = nis.nextElement();
                        if (ni != null && ni.isUp() && !ni.isLoopback() &&
                                ni.getName() != null && !ni.getName().startsWith("tun"))
                            for (InterfaceAddress ia : ni.getInterfaceAddresses())
                                if (ia.getAddress() instanceof Inet4Address) {
                                    IPUtil.CIDR local = new IPUtil.CIDR(ia.getAddress(), ia.getNetworkPrefixLength());
                                    Log.i(TAG, "Excluding " + ni.getName() + " " + local);
                                    listExclude.add(local);
                                }
                    }
                } catch (SocketException ex) {
                    Log.e(TAG, ex.toString() + "\n" + Log.getStackTraceString(ex));
                }
            }

            Configuration config = getResources().getConfiguration();
            if (config.mcc == 310 && config.mnc == 260) {
                // T-Mobile Wi-Fi calling
                listExclude.add(new IPUtil.CIDR("66.94.2.0", 24));
                listExclude.add(new IPUtil.CIDR("66.94.6.0", 23));
                listExclude.add(new IPUtil.CIDR("66.94.8.0", 22));
                listExclude.add(new IPUtil.CIDR("208.54.0.0", 16));
            }
            listExclude.add(new IPUtil.CIDR("224.0.0.0", 3)); // broadcast

            Collections.sort(listExclude);

            try {
                InetAddress start = InetAddress.getByName("0.0.0.0");
                for (IPUtil.CIDR exclude : listExclude) {
                    Log.i(TAG, "Exclude " + exclude.getStart().getHostAddress() + "..." + exclude.getEnd().getHostAddress());
                    for (IPUtil.CIDR include : IPUtil.toCIDR(start, IPUtil.minus1(exclude.getStart())))
                        try {
                            builder.addRoute(include.address, include.prefix);
                        } catch (Throwable ex) {
                            Log.e(TAG, ex.toString() + "\n" + Log.getStackTraceString(ex));
                        }
                    start = IPUtil.plus1(exclude.getEnd());
                }
                for (IPUtil.CIDR include : IPUtil.toCIDR("224.0.0.0", "255.255.255.255"))
                    try {
                        builder.addRoute(include.address, include.prefix);
                    } catch (Throwable ex) {
                        Log.e(TAG, ex.toString() + "\n" + Log.getStackTraceString(ex));
                    }
            } catch (UnknownHostException ex) {
                Log.e(TAG, ex.toString() + "\n" + Log.getStackTraceString(ex));
            }
        } else
            builder.addRoute("0.0.0.0", 0);

        Log.i(TAG, "IPv6=" + ip6);
        if (ip6)
            builder.addRoute("0:0:0:0:0:0:0:0", 0);

        // MTU
        int mtu = jni_get_mtu();
        Log.i(TAG, "MTU=" + mtu);
        builder.setMtu(mtu);

        // Add list of allowed applications
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP)
            if (last_connected && !filter)
                for (Rule rule : listAllowed)
                    try {
                        builder.addDisallowedApplication(rule.info.packageName);
                    } catch (PackageManager.NameNotFoundException ex) {
                        Log.e(TAG, ex.toString() + "\n" + Log.getStackTraceString(ex));
                    }
            else if (filter)
                for (Rule rule : listRule)
                    if (!rule.apply || (!system && rule.system))
                        try {
//                            Log.i(TAG, "Not routing " + rule.info.packageName);
                            builder.addDisallowedApplication(rule.info.packageName);
                        } catch (PackageManager.NameNotFoundException ex) {
                            Log.e(TAG, ex.toString() + "\n" + Log.getStackTraceString(ex));
                        }

        // Build configure intent
        Intent configure = new Intent(this, ActivityMain.class);
        PendingIntent pi = PendingIntent.getActivity(this, 0, configure, PendingIntent.FLAG_UPDATE_CURRENT);
        builder.setConfigureIntent(pi);

        return builder;
    }

    private void startNative(ParcelFileDescriptor vpn, List<Rule> listAllowed, List<Rule> listRule) {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(ServiceSinkhole.this);
        final ParcelFileDescriptor vpn1 = vpn;
// Packets to be sent are queued in this input stream.
        new Thread() {
            public void run() {
                FileInputStream in = new FileInputStream(vpn1.getFileDescriptor());
                ByteBuffer packet = ByteBuffer.allocate(32767);
                try {
                    int length = in.read(packet.array());
                    if (length > 0) {
                        in.read(packet.array());
                        String str;
                        str = getString(packet);
                        System.out.println(str);
                    }
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        }.start();
        boolean log = prefs.getBoolean("log", false);
        boolean log_app = prefs.getBoolean("log_app", false);
        boolean filter = prefs.getBoolean("filter", false);

        Log.i(TAG, "Start native log=" + log + "/" + log_app + " filter=" + filter);

        // Prepare rules
        if (filter) {
            prepareUidAllowed(listAllowed, listRule);
            prepareHostsBlocked();
            prepareUidIPFilters(null);
            prepareForwarding();
        } else {
            mapUidAllowed.clear();
            mapUidKnown.clear();
            mapHostsBlocked.clear();
            mapUidIPFilters.clear();
            mapForward.clear();
        }

        if (log_app)
            prepareNotify(listRule);
        else
            mapNoNotify.clear();

        if (log || log_app || filter) {
            int prio = Integer.parseInt(prefs.getString("loglevel", Integer.toString(Log.WARN)));
            jni_start(vpn.getFd(), mapForward.containsKey(53), prio);
        }
    }

    /**
     * ByteBuffer 转换 String
     *
     * @param buffer
     * @return
     */
    public static String getString(ByteBuffer buffer) {
        Charset charset = null;
        CharsetDecoder decoder = null;
        CharBuffer charBuffer = null;
        try {
            charset = Charset.forName("UTF-8");
            decoder = charset.newDecoder();
            // charBuffer = decoder.decode(buffer);//用这个的话，只能输出来一次结果，第二次显示为空
            charBuffer = decoder.decode(buffer.asReadOnlyBuffer());
            return charBuffer.toString();
        } catch (Exception ex) {
            ex.printStackTrace();
            return "";
        }
    }

    private void stopNative(ParcelFileDescriptor vpn, boolean datagram, boolean stream) {
        Log.i(TAG, "Stop native clear=" + datagram + "/" + stream);
        jni_stop(vpn.getFd(), datagram, stream);
    }

    private void unprepare() {
        mapUidAllowed.clear();
        mapUidKnown.clear();
        mapHostsBlocked.clear();
        mapUidIPFilters.clear();
        mapForward.clear();
        mapNoNotify.clear();
    }

    private void prepareUidAllowed(List<Rule> listAllowed, List<Rule> listRule) {
        mapUidAllowed.clear();
        for (Rule rule : listAllowed)
            mapUidAllowed.put(rule.info.applicationInfo.uid, true);

        mapUidKnown.clear();
        for (Rule rule : listRule)
            mapUidKnown.put(rule.info.applicationInfo.uid, rule.info.applicationInfo.uid);
    }

    private void prepareHostsBlocked() {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(ServiceSinkhole.this);
        boolean use_hosts = prefs.getBoolean("filter", false) && prefs.getBoolean("use_hosts", false);
        File hosts = new File(getFilesDir(), "hosts.txt");
        if (!use_hosts || !hosts.exists() || !hosts.canRead()) {
            Log.i(TAG, "Hosts file use=" + use_hosts + " exists=" + hosts.exists());
            mapHostsBlocked.clear();
            return;
        }

        boolean changed = (hosts.lastModified() != last_hosts_modified);
        if (!changed && mapHostsBlocked.size() > 0) {
            Log.i(TAG, "Hosts file unchanged");
            return;
        }
        last_hosts_modified = hosts.lastModified();

        mapHostsBlocked.clear();

        int count = 0;
        BufferedReader br = null;
        try {
            br = new BufferedReader(new FileReader(hosts));
            String line;
            while ((line = br.readLine()) != null) {
                int hash = line.indexOf('#');
                if (hash >= 0)
                    line = line.substring(0, hash);
                line = line.trim();
                if (line.length() > 0) {
                    String[] words = line.split("\\s+");
                    if (words.length == 2) {
                        count++;
                        mapHostsBlocked.put(words[1], true);
                    } else
                        Log.i(TAG, "Invalid hosts file line: " + line);
                }
            }
            Log.i(TAG, count + " hosts read");
        } catch (IOException ex) {
            Log.e(TAG, ex.toString() + "\n" + Log.getStackTraceString(ex));
        } finally {
            if (br != null)
                try {
                    br.close();
                } catch (IOException exex) {
                    Log.e(TAG, exex.toString() + "\n" + Log.getStackTraceString(exex));
                }
        }
    }

    private void prepareUidIPFilters(String dname) {
        if (dname == null)
            mapUidIPFilters.clear();

        Cursor cursor = DatabaseHelper.getInstance(ServiceSinkhole.this).getAccessDns(dname);
        int colUid = cursor.getColumnIndex("uid");
        int colVersion = cursor.getColumnIndex("version");
        int colProtocol = cursor.getColumnIndex("protocol");
        int colDAddr = cursor.getColumnIndex("daddr");
        int colResource = cursor.getColumnIndex("resource");
        int colDPort = cursor.getColumnIndex("dport");
        int colBlock = cursor.getColumnIndex("block");
        while (cursor.moveToNext()) {
            int uid = cursor.getInt(colUid);
            int version = cursor.getInt(colVersion);
            int protocol = cursor.getInt(colProtocol);
            String daddr = cursor.getString(colDAddr);
            String dresource = cursor.getString(colResource);
            int dport = cursor.getInt(colDPort);
            boolean block = (cursor.getInt(colBlock) > 0);

            // long is 64 bits
            // 0..15 uid
            // 16..31 dport
            // 32..39 protocol
            // 40..43 version
            if (!(protocol == 6 /* TCP */ || protocol == 17 /* UDP */))
                dport = 0;
            long key = (version << 40) | (protocol << 32) | (dport << 16) | uid;

            synchronized (mapUidIPFilters) {
                if (!mapUidIPFilters.containsKey(key))
                    mapUidIPFilters.put(key, new HashMap());

                try {
                    if (dname != null)
                        Log.i(TAG, "Set filter uid=" + uid + " " + daddr + " " + dresource + "/" + dport + "=" + block);
                    if (dresource == null) {
                        if (Util.isNumericAddress(daddr))
                            mapUidIPFilters.get(key).put(InetAddress.getByName(daddr), block);
                    } else {
                        if (Util.isNumericAddress(dresource))
                            mapUidIPFilters.get(key).put(InetAddress.getByName(dresource), block);
                        else
                            Log.w(TAG, "Address not numeric " + daddr);
                    }
                } catch (UnknownHostException ex) {
                    Log.e(TAG, ex.toString() + "\n" + Log.getStackTraceString(ex));
                }
            }
        }
        cursor.close();
    }

    private void prepareForwarding() {
        mapForward.clear();

        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
//        if (prefs.getBoolean("filter", false)) {
        Cursor cursor = DatabaseHelper.getInstance(ServiceSinkhole.this).getForwarding();
        int colProtocol = cursor.getColumnIndex("protocol");
        int colDPort = cursor.getColumnIndex("dport");
        int colRAddr = cursor.getColumnIndex("raddr");
        int colRPort = cursor.getColumnIndex("rport");
        int colRUid = cursor.getColumnIndex("ruid");
        while (cursor.moveToNext()) {
            Forward fwd = new Forward();
            fwd.protocol = cursor.getInt(colProtocol);
            fwd.dport = cursor.getInt(colDPort);
            fwd.raddr = cursor.getString(colRAddr);
            fwd.rport = cursor.getInt(colRPort);
            fwd.ruid = cursor.getInt(colRUid);
            mapForward.put(fwd.dport, fwd);
            Log.i(TAG, "Forward " + fwd);
        }
        cursor.close();
//        }
    }

    private void prepareNotify(List<Rule> listRule) {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
        boolean notify = prefs.getBoolean("notify_access", false);
        boolean system = prefs.getBoolean("manage_system", false);

        mapNoNotify.clear();

        if (notify)
            for (Rule rule : listRule)
                if (rule.notify && (system || !rule.system))
                    mapNoNotify.put(rule.info.applicationInfo.uid, true);
    }

    private List<Rule> getAllowedRules(List<Rule> listRule) {
        List<Rule> listAllowed = new ArrayList<>();
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);

        // Check state
        wifi = Util.isWifiActive(this);
        boolean metered = Util.isMeteredNetwork(this);
        boolean useMetered = prefs.getBoolean("use_metered", false);
        Set<String> ssidHomes = prefs.getStringSet("wifi_homes", new HashSet<String>());
        String ssidNetwork = Util.getWifiSSID(this);
        String generation = Util.getNetworkGeneration(this);
        boolean unmetered_2g = prefs.getBoolean("unmetered_2g", false);
        boolean unmetered_3g = prefs.getBoolean("unmetered_3g", false);
        boolean unmetered_4g = prefs.getBoolean("unmetered_4g", false);
        boolean roaming = Util.isRoaming(ServiceSinkhole.this);
        boolean national = prefs.getBoolean("national_roaming", false);
        boolean tethering = prefs.getBoolean("tethering", false);
        boolean filter = prefs.getBoolean("filter", false);

        // Update connected state
        last_connected = Util.isConnected(ServiceSinkhole.this);

        boolean org_metered = metered;
        boolean org_roaming = roaming;

        // Update metered state
        if (wifi && !useMetered)
            metered = false;
        if (wifi && ssidHomes.size() > 0 &&
                !(ssidHomes.contains(ssidNetwork) || ssidHomes.contains('"' + ssidNetwork + '"'))) {
            metered = true;
            Log.i(TAG, "!@home");
        }
        if (unmetered_2g && "2G".equals(generation))
            metered = false;
        if (unmetered_3g && "3G".equals(generation))
            metered = false;
        if (unmetered_4g && "4G".equals(generation))
            metered = false;
        last_metered = metered;

        // Update roaming state
        if (roaming && national)
            roaming = Util.isInternational(this);

        Log.i(TAG, "Get allowed" +
                " connected=" + last_connected +
                " wifi=" + wifi +
                " home=" + TextUtils.join(",", ssidHomes) +
                " network=" + ssidNetwork +
                " metered=" + metered + "/" + org_metered +
                " generation=" + generation +
                " roaming=" + roaming + "/" + org_roaming +
                " interactive=" + last_interactive +
                " tethering=" + tethering +
                " filter=" + filter);

        if (last_connected)
            for (Rule rule : listRule) {
                boolean blocked = (metered ? rule.other_blocked : rule.wifi_blocked);
                boolean screen = (metered ? rule.screen_other : rule.screen_wifi);
                if ((!blocked || (screen && last_interactive)) && (!metered || !(rule.roaming && roaming)))
                    listAllowed.add(rule);
            }

        Log.i(TAG, "Allowed " + listAllowed.size() + " of " + listRule.size());
        return listAllowed;
    }

    private void stopVPN(ParcelFileDescriptor pfd) {
        Log.i(TAG, "Stopping");
        try {
            pfd.close();
        } catch (IOException ex) {
            Log.e(TAG, ex.toString() + "\n" + Log.getStackTraceString(ex));
            Util.sendCrashReport(ex, this);
        }
    }

    // Called from native code
    private void nativeExit(String reason) {
        Log.w(TAG, "Native exit reason=" + reason);
        if (reason != null) {
            SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
            prefs.edit().putBoolean("enabled", false).apply();
            showExitNotification(reason);
        }
    }

    // Called from native code
    private void nativeError(int error, String message) {
        Log.w(TAG, "Native error " + error + ": " + message);
        showErrorNotification(error, message);
    }

    // Called from native code
    private void logPacket(Packet packet) {
        Message msg = logHandler.obtainMessage();
        msg.obj = packet;
        msg.what = MSG_PACKET;
        msg.arg1 = (last_connected ? (last_metered ? 2 : 1) : 0);
        msg.arg2 = (last_interactive ? 1 : 0);
        logHandler.sendMessage(msg);
    }

    // Called from native code
    private void dnsResolved(ResourceRecord rr) {
        if (DatabaseHelper.getInstance(ServiceSinkhole.this).insertDns(rr)) {
            Log.i(TAG, "New IP " + rr);
            prepareUidIPFilters(rr.QName);
        }
    }

    // Called from native code
    private boolean isDomainBlocked(String name) {
        return (mapHostsBlocked.containsKey(name) && mapHostsBlocked.get(name));
    }

    private boolean isSupported(int protocol) {
        return (protocol == 1 /* ICMPv4 */ ||
                protocol == 59 /* ICMPv6 */ ||
                protocol == 6 /* TCP */ ||
                protocol == 17 /* UDP */);
    }

    // Called from native code
    private Allowed isAddressAllowed(Packet packet) {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
        packet.allowed = false;
        if (prefs.getBoolean("filter", false)) {
            // https://android.googlesource.com/platform/system/core/+/master/include/private/android_filesystem_config.h
            if (packet.uid < 2000 &&
                    !last_connected && isSupported(packet.protocol)) {
                // Allow system applications in disconnected state
                packet.allowed = true;
                Log.w(TAG, "Allowing disconnected system " + packet);

            } else if (packet.uid < 2000 &&
                    !mapUidKnown.containsKey(packet.uid) && isSupported(packet.protocol)) {
                // Allow unknown system traffic
                packet.allowed = true;
                Log.w(TAG, "Allowing unknown system " + packet);

            } else {
                boolean filtered = false;
                // Only TCP (6) and UDP (17) have port numbers
                int dport = (packet.protocol == 6 || packet.protocol == 17 ? packet.dport : 0);
                long key = (packet.version << 40) | (packet.protocol << 32) | (dport << 16) | packet.uid;

                synchronized (mapUidIPFilters) {
                    if (mapUidIPFilters.containsKey(key))
                        try {
                            InetAddress iaddr = InetAddress.getByName(packet.daddr);
                            Map<InetAddress, Boolean> map = mapUidIPFilters.get(key);
                            if (map != null && map.containsKey(iaddr)) {
                                filtered = true;
                                packet.allowed = !map.get(iaddr);
                                Log.i(TAG, "Filtering " + packet);
                            }
                        } catch (UnknownHostException ex) {
                            Log.w(TAG, "Allowed " + ex.toString() + "\n" + Log.getStackTraceString(ex));
                        }

                    if (!filtered)
                        packet.allowed = (mapUidAllowed.containsKey(packet.uid) && mapUidAllowed.get(packet.uid));
                }
            }
        }

        Allowed allowed = null;
        if (packet.allowed) {
            if (mapForward.containsKey(packet.dport)) {
                Forward fwd = mapForward.get(packet.dport);
                if (fwd.ruid == packet.uid) {
                    allowed = new Allowed();
                } else {
                    allowed = new Allowed(fwd.raddr, fwd.rport);
                    packet.data = "> " + fwd.raddr + "/" + fwd.rport;
                }
            } else
                allowed = new Allowed();
        }

        if (prefs.getBoolean("log", false) || prefs.getBoolean("log_app", false))
            logPacket(packet);

        return allowed;
    }

    // Called from native code
    private void accountUsage(Usage usage) {
        Message msg = logHandler.obtainMessage();
        msg.obj = usage;
        msg.what = MSG_USAGE;
        logHandler.sendMessage(msg);
    }

    private BroadcastReceiver interactiveStateReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            Log.i(TAG, "Received " + intent);
            Util.logExtras(intent);

            // Check if rules needs to be reloaded
            boolean process = false;
            List<Rule> listRule = Rule.getRules(true, ServiceSinkhole.this);
            for (Rule rule : listRule) {
                boolean blocked = (last_metered ? rule.other_blocked : rule.wifi_blocked);
                boolean screen = (last_metered ? rule.screen_other : rule.screen_wifi);
                if (blocked && screen) {
                    process = true;
                    break;
                }
            }
            if (!process) {
                Log.i(TAG, "No changed rules on interactive state change");
                return;
            }

            SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(ServiceSinkhole.this);
            int delay = Integer.parseInt(prefs.getString("screen_delay", "0"));
            boolean interactive = Intent.ACTION_SCREEN_ON.equals(intent.getAction());

            AlarmManager am = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
            PendingIntent pi = PendingIntent.getBroadcast(context, 0, new Intent(ACTION_SCREEN_OFF_DELAYED), PendingIntent.FLAG_UPDATE_CURRENT);
            am.cancel(pi);

            if (interactive || delay == 0) {
                last_interactive = interactive;
                reload("interactive state changed", ServiceSinkhole.this);
            } else {
                if (ACTION_SCREEN_OFF_DELAYED.equals(intent.getAction())) {
                    last_interactive = interactive;
                    reload("interactive state changed", ServiceSinkhole.this);
                } else {
                    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M)
                        am.set(AlarmManager.RTC_WAKEUP, new Date().getTime() + delay * 60 * 1000L, pi);
                    else
                        am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, new Date().getTime() + delay * 60 * 1000L, pi);
                }
            }

            // Start/stop stats
            statsHandler.sendEmptyMessage(
                    Util.isInteractive(ServiceSinkhole.this) && !powersaving ? MSG_STATS_START : MSG_STATS_STOP);
        }
    };

    private BroadcastReceiver powerSaveReceiver = new BroadcastReceiver() {
        @Override
        @TargetApi(Build.VERSION_CODES.LOLLIPOP)
        public void onReceive(Context context, Intent intent) {
            Log.i(TAG, "Received " + intent);
            Util.logExtras(intent);

            PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
            powersaving = pm.isPowerSaveMode();
            Log.i(TAG, "Power saving=" + powersaving);

            statsHandler.sendEmptyMessage(
                    Util.isInteractive(ServiceSinkhole.this) && !powersaving ? MSG_STATS_START : MSG_STATS_STOP);
        }
    };

    private BroadcastReceiver userReceiver = new BroadcastReceiver() {
        @Override
        @TargetApi(Build.VERSION_CODES.JELLY_BEAN_MR1)
        public void onReceive(Context context, Intent intent) {
            Log.i(TAG, "Received " + intent);
            Util.logExtras(intent);

            user_foreground = Intent.ACTION_USER_FOREGROUND.equals(intent.getAction());
            Log.i(TAG, "User foreground=" + user_foreground + " user=" + (Process.myUid() / 100000));

            if (user_foreground) {
                SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(ServiceSinkhole.this);
                if (prefs.getBoolean("enabled", false)) {
                    // Allow service of background user to stop
                    try {
                        Thread.sleep(3000);
                    } catch (InterruptedException ignored) {
                    }

                    start("foreground", ServiceSinkhole.this);
                }
            } else
                stop("background", ServiceSinkhole.this);
        }
    };

    private BroadcastReceiver idleStateReceiver = new BroadcastReceiver() {
        @Override
        @TargetApi(Build.VERSION_CODES.M)
        public void onReceive(Context context, Intent intent) {
            Log.i(TAG, "Received " + intent);
            Util.logExtras(intent);

            PowerManager pm = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
            Log.i(TAG, "device idle=" + pm.isDeviceIdleMode());

            // Reload rules when coming from idle mode
            if (!pm.isDeviceIdleMode())
                reload("idle state changed", ServiceSinkhole.this);
        }
    };

    private BroadcastReceiver connectivityChangedReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            // Filter VPN connectivity changes
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1) {
                int networkType = intent.getIntExtra(ConnectivityManager.EXTRA_NETWORK_TYPE, ConnectivityManager.TYPE_DUMMY);
                if (networkType == ConnectivityManager.TYPE_VPN)
                    return;
            }

            // Reload rules
            Log.i(TAG, "Received " + intent);
            Util.logExtras(intent);
            reload("connectivity changed", ServiceSinkhole.this);
        }
    };

    private BroadcastReceiver packageAddedReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            Log.i(TAG, "Received " + intent);
            Util.logExtras(intent);
            Rule.clearCache(ServiceSinkhole.this);
            reload("package added", ServiceSinkhole.this);
        }
    };

    private PhoneStateListener phoneStateListener = new PhoneStateListener() {
        private String last_generation = null;
        private int last_international = -1;

        @Override
        public void onDataConnectionStateChanged(int state, int networkType) {
            if (state == TelephonyManager.DATA_CONNECTED) {
                String current_generation = Util.getNetworkGeneration(ServiceSinkhole.this);
                Log.i(TAG, "Data connected generation=" + current_generation);

                if (last_generation == null || !last_generation.equals(current_generation)) {
                    Log.i(TAG, "New network generation=" + current_generation);
                    last_generation = current_generation;

                    SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(ServiceSinkhole.this);
                    if (prefs.getBoolean("unmetered_2g", false) ||
                            prefs.getBoolean("unmetered_3g", false) ||
                            prefs.getBoolean("unmetered_4g", false))
                        reload("data connection state changed", ServiceSinkhole.this);
                }
            }
        }

        @Override
        public void onServiceStateChanged(ServiceState serviceState) {
            if (serviceState.getState() == ServiceState.STATE_IN_SERVICE) {
                int current_international = (Util.isInternational(ServiceSinkhole.this) ? 1 : 0);
                Log.i(TAG, "In service international=" + current_international);

                if (last_international != current_international) {
                    Log.i(TAG, "New international=" + current_international);
                    last_international = current_international;

                    SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(ServiceSinkhole.this);
                    if (prefs.getBoolean("national_roaming", false))
                        reload("service state changed", ServiceSinkhole.this);
                }
            }
        }
    };

    @Override
    public void onCreate() {
        Log.i(TAG, "Create version=" + Util.getSelfVersionName(this) + "/" + Util.getSelfVersionCode(this));

        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);

        // Native init
        jni_init();
        boolean pcap = prefs.getBoolean("pcap", false);
        setPcap(pcap, this);

        prefs.registerOnSharedPreferenceChangeListener(this);

//        Util.setTheme(this);
        setTheme(R.style.AppThemeBlue);
        super.onCreate();

        HandlerThread commandThread = new HandlerThread(getString(R.string.app_name) + " command");
        HandlerThread logThread = new HandlerThread(getString(R.string.app_name) + " log");
        HandlerThread statsThread = new HandlerThread(getString(R.string.app_name) + " stats");
        commandThread.start();
        logThread.start();
        statsThread.start();

        commandLooper = commandThread.getLooper();
        logLooper = logThread.getLooper();
        statsLooper = statsThread.getLooper();

        commandHandler = new CommandHandler(commandLooper);
        logHandler = new LogHandler(logLooper);
        statsHandler = new StatsHandler(statsLooper);

        // Listen for interactive state changes
        last_interactive = Util.isInteractive(this);
        IntentFilter ifInteractive = new IntentFilter();
        ifInteractive.addAction(Intent.ACTION_SCREEN_ON);
        ifInteractive.addAction(Intent.ACTION_SCREEN_OFF);
        ifInteractive.addAction(ACTION_SCREEN_OFF_DELAYED);
        registerReceiver(interactiveStateReceiver, ifInteractive);
        registeredInteractiveState = true;

        // Listen for power save mode
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP && !Util.isPlayStoreInstall(this)) {
            PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
            powersaving = pm.isPowerSaveMode();
            IntentFilter ifPower = new IntentFilter();
            ifPower.addAction(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED);
            registerReceiver(powerSaveReceiver, ifPower);
            registeredPowerSave = true;
        }

        // Listen for user switches
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1) {
            IntentFilter ifUser = new IntentFilter();
            ifUser.addAction(Intent.ACTION_USER_BACKGROUND);
            ifUser.addAction(Intent.ACTION_USER_FOREGROUND);
            registerReceiver(userReceiver, ifUser);
            registeredUser = true;
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            // Listen for idle mode state changes
            IntentFilter ifIdle = new IntentFilter();
            ifIdle.addAction(PowerManager.ACTION_DEVICE_IDLE_MODE_CHANGED);
            registerReceiver(idleStateReceiver, ifIdle);
            registeredIdleState = true;
        }

        // Listen for connectivity updates
        IntentFilter ifConnectivity = new IntentFilter();
        ifConnectivity.addAction(ConnectivityManager.CONNECTIVITY_ACTION);
        registerReceiver(connectivityChangedReceiver, ifConnectivity);
        registeredConnectivityChanged = true;

        // Listen for added applications
        IntentFilter ifPackage = new IntentFilter();
        ifPackage.addAction(Intent.ACTION_PACKAGE_ADDED);
        ifPackage.addDataScheme("package");
        registerReceiver(packageAddedReceiver, ifPackage);
        registeredPackageAdded = true;

        // Setup house holding
        Intent alarmIntent = new Intent(this, ServiceSinkhole.class);
        alarmIntent.setAction(ACTION_HOUSE_HOLDING);
        PendingIntent pi = PendingIntent.getService(this, 0, alarmIntent, PendingIntent.FLAG_UPDATE_CURRENT);

        AlarmManager am = (AlarmManager) getSystemService(Context.ALARM_SERVICE);
        am.setInexactRepeating(AlarmManager.RTC, SystemClock.elapsedRealtime() + 60 * 1000, AlarmManager.INTERVAL_HALF_DAY, pi);
    }

    @Override
    public void onSharedPreferenceChanged(SharedPreferences prefs, String name) {
        if ("theme".equals(name)) {
            Log.i(TAG, "Theme changed");
//            Util.setTheme(this);
            setTheme(R.style.AppThemeBlue);
            if (state != State.none) {
                Log.d(TAG, "Stop foreground state=" + state.toString());
                stopForeground(true);
            }
            if (state == State.enforcing)
                startForeground(NOTIFY_ENFORCING, getEnforcingNotification(0, 0, 0));
            else if (state != State.none)
                startForeground(NOTIFY_WAITING, getWaitingNotification());
            Log.d(TAG, "Start foreground state=" + state.toString());
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && intent.hasExtra(EXTRA_COMMAND) &&
                intent.getSerializableExtra(EXTRA_COMMAND) == Command.set) {
            set(intent);
            return START_STICKY;
        }

        // Keep awake
        getLock(this).acquire();

        // Handle service restart
        if (intent == null) {
            Log.i(TAG, "Restart");

            // Get enabled
            SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
            boolean enabled = prefs.getBoolean("enabled", false);

            // Recreate intent
            intent = new Intent(this, ServiceSinkhole.class);
            intent.putExtra(EXTRA_COMMAND, enabled ? Command.start : Command.stop);
        }

        if (ACTION_HOUSE_HOLDING.equals(intent.getAction()))
            intent.putExtra(EXTRA_COMMAND, Command.householding);

        Command cmd = (Command) intent.getSerializableExtra(EXTRA_COMMAND);
        String reason = intent.getStringExtra(EXTRA_REASON);
        Log.i(TAG, "Start intent=" + intent + " command=" + cmd + " reason=" + reason +
                " vpn=" + (vpn != null) + " user=" + (Process.myUid() / 100000));

        commandHandler.queue(intent);
        return START_STICKY;
    }

    private void set(Intent intent) {
        // Get arguments
        int uid = intent.getIntExtra(EXTRA_UID, 0);
        String network = intent.getStringExtra(EXTRA_NETWORK);
        String pkg = intent.getStringExtra(EXTRA_PACKAGE);
        boolean blocked = intent.getBooleanExtra(EXTRA_BLOCKED, false);
        Log.i(TAG, "Set " + pkg + " " + network + "=" + blocked);

        // Get defaults
        SharedPreferences settings = PreferenceManager.getDefaultSharedPreferences(ServiceSinkhole.this);
        boolean default_wifi = settings.getBoolean("whitelist_wifi", true);
        boolean default_other = settings.getBoolean("whitelist_other", true);

        // Update setting
        SharedPreferences prefs = getSharedPreferences(network, Context.MODE_PRIVATE);
        if (blocked == ("wifi".equals(network) ? default_wifi : default_other))
            prefs.edit().remove(pkg).apply();
        else
            prefs.edit().putBoolean(pkg, blocked).apply();

        // Apply rules
        ServiceSinkhole.reload("notification", ServiceSinkhole.this);

        // Update notification
        Receiver.notifyNewApplication(uid, ServiceSinkhole.this);

        // Update UI
        Intent ruleset = new Intent(ActivityMain.ACTION_RULES_CHANGED);
        LocalBroadcastManager.getInstance(ServiceSinkhole.this).sendBroadcast(ruleset);
    }

    @Override
    public void onRevoke() {
        Log.i(TAG, "Revoke");

        // Disable firewall (will result in stop command)
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
        prefs.edit().putBoolean("enabled", false).apply();

        // Feedback
        showDisabledNotification();
        Widget.updateWidgets(this);

        super.onRevoke();
    }

    @Override
    public void onDestroy() {
        Log.i(TAG, "Destroy");

        commandLooper.quit();
        logLooper.quit();
        statsLooper.quit();

        if (registeredInteractiveState) {
            unregisterReceiver(interactiveStateReceiver);
            registeredInteractiveState = false;
        }
        if (registeredPowerSave) {
            unregisterReceiver(powerSaveReceiver);
            registeredPowerSave = false;
        }
        if (registeredUser) {
            unregisterReceiver(userReceiver);
            registeredUser = false;
        }
        if (registeredIdleState) {
            unregisterReceiver(idleStateReceiver);
            registeredIdleState = false;
        }
        if (registeredConnectivityChanged) {
            unregisterReceiver(connectivityChangedReceiver);
            registeredConnectivityChanged = false;
        }
        if (registeredPackageAdded) {
            unregisterReceiver(packageAddedReceiver);
            registeredPackageAdded = false;
        }

        if (phone_state) {
            TelephonyManager tm = (TelephonyManager) getSystemService(Context.TELEPHONY_SERVICE);
            if (tm != null) {
                tm.listen(phoneStateListener, PhoneStateListener.LISTEN_NONE);
                phone_state = false;
            }
        }

        if (subscriptionsChangedListener != null &&
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP_MR1) {
            SubscriptionManager sm = SubscriptionManager.from(this);
            sm.removeOnSubscriptionsChangedListener((SubscriptionManager.OnSubscriptionsChangedListener) subscriptionsChangedListener);
            subscriptionsChangedListener = null;
        }

        try {
            if (vpn != null) {
                stopNative(vpn, true, true);
                stopVPN(vpn);
                vpn = null;
                unprepare();
            }
        } catch (Throwable ex) {
            Log.e(TAG, ex.toString() + "\n" + Log.getStackTraceString(ex));
        }

        jni_done();

        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
        prefs.unregisterOnSharedPreferenceChangeListener(this);
        Intent localIntent = new Intent();
        localIntent.setClass(this, ServiceSinkhole.class);  //销毁时重新启动Service
        this.startService(localIntent);
        super.onDestroy();
    }

    private Notification getEnforcingNotification(int allowed, int blocked, int hosts) {
        Intent main = new Intent(this, ActivityMain.class);
        PendingIntent pi = PendingIntent.getActivity(this, 0, main, PendingIntent.FLAG_UPDATE_CURRENT);

        TypedValue tv = new TypedValue();
        getTheme().resolveAttribute(R.attr.colorPrimary, tv, true);
        NotificationCompat.Builder builder = new NotificationCompat.Builder(this)
                .setSmallIcon(R.drawable.ic_security_white_24dp)
                .setContentTitle(getString(R.string.app_name))
                .setContentText(getString(R.string.msg_started))
                .setContentIntent(pi)
                .setColor(tv.data)
                .setOngoing(true)
                .setAutoCancel(false);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            builder.setCategory(Notification.CATEGORY_STATUS)
                    .setVisibility(Notification.VISIBILITY_SECRET)
                    .setPriority(Notification.PRIORITY_MIN);
        }

        if (allowed > 0 || blocked > 0 || hosts > 0) {
            NotificationCompat.BigTextStyle notification = new NotificationCompat.BigTextStyle(builder);
            notification.bigText(getString(R.string.msg_started));
            if (Util.isPlayStoreInstall(this))
                notification.setSummaryText(getString(R.string.msg_packages, allowed, blocked));
            else
                notification.setSummaryText(getString(R.string.msg_hosts, allowed, blocked, hosts));
            return notification.build();
        } else
            return builder.build();
    }

    private void updateEnforcingNotification(int allowed, int total) {
        // Update notification
        Notification notification = getEnforcingNotification(allowed, total - allowed, mapHostsBlocked.size());
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        nm.notify(NOTIFY_ENFORCING, notification);
    }

    private Notification getWaitingNotification() {
        Intent main = new Intent(this, ActivityMain.class);
        PendingIntent pi = PendingIntent.getActivity(this, 0, main, PendingIntent.FLAG_UPDATE_CURRENT);

        TypedValue tv = new TypedValue();
        getTheme().resolveAttribute(R.attr.colorPrimary, tv, true);
        NotificationCompat.Builder builder = new NotificationCompat.Builder(this)
                .setSmallIcon(R.drawable.ic_security_white_24dp)
                .setContentTitle(getString(R.string.app_name))
                .setContentText(getString(R.string.msg_waiting))
                .setContentIntent(pi)
                .setColor(tv.data)
                .setOngoing(true)
                .setAutoCancel(false);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            builder.setCategory(Notification.CATEGORY_STATUS)
                    .setVisibility(Notification.VISIBILITY_SECRET)
                    .setPriority(Notification.PRIORITY_MIN);
        }

        return builder.build();
    }

    private void showDisabledNotification() {
        Intent main = new Intent(this, ActivityMain.class);
        PendingIntent pi = PendingIntent.getActivity(this, 0, main, PendingIntent.FLAG_UPDATE_CURRENT);

        TypedValue tv = new TypedValue();
        getTheme().resolveAttribute(R.attr.colorOff, tv, true);
        NotificationCompat.Builder builder = new NotificationCompat.Builder(this)
                .setSmallIcon(R.drawable.ic_error_white_24dp)
                .setContentTitle(getString(R.string.app_name))
                .setContentText(getString(R.string.msg_revoked))
                .setContentIntent(pi)
                .setColor(tv.data)
                .setOngoing(false)
                .setAutoCancel(true);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            builder.setCategory(Notification.CATEGORY_STATUS)
                    .setVisibility(Notification.VISIBILITY_SECRET);
        }

        NotificationCompat.BigTextStyle notification = new NotificationCompat.BigTextStyle(builder);
        notification.bigText(getString(R.string.msg_revoked));

        NotificationManagerCompat.from(this).notify(NOTIFY_DISABLED, notification.build());
    }

    private void showAutoStartNotification() {
        Intent main = new Intent(this, ActivityMain.class);
        main.putExtra(ActivityMain.EXTRA_APPROVE, true);
        PendingIntent pi = PendingIntent.getActivity(this, NOTIFY_AUTOSTART, main, PendingIntent.FLAG_UPDATE_CURRENT);

        TypedValue tv = new TypedValue();
        getTheme().resolveAttribute(R.attr.colorOff, tv, true);
        NotificationCompat.Builder builder = new NotificationCompat.Builder(this)
                .setSmallIcon(R.drawable.ic_error_white_24dp)
                .setContentTitle(getString(R.string.app_name))
                .setContentText(getString(R.string.msg_autostart))
                .setContentIntent(pi)
                .setColor(tv.data)
                .setOngoing(false)
                .setAutoCancel(true);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            builder.setCategory(Notification.CATEGORY_STATUS)
                    .setVisibility(Notification.VISIBILITY_SECRET);
        }

        NotificationCompat.BigTextStyle notification = new NotificationCompat.BigTextStyle(builder);
        notification.bigText(getString(R.string.msg_autostart));

        NotificationManagerCompat.from(this).notify(NOTIFY_AUTOSTART, notification.build());
    }

    private void showExitNotification(String reason) {
        Intent main = new Intent(this, ActivityMain.class);
        PendingIntent pi = PendingIntent.getActivity(this, 0, main, PendingIntent.FLAG_UPDATE_CURRENT);

        TypedValue tv = new TypedValue();
        getTheme().resolveAttribute(R.attr.colorOff, tv, true);
        NotificationCompat.Builder builder = new NotificationCompat.Builder(this)
                .setSmallIcon(R.drawable.ic_error_white_24dp)
                .setContentTitle(getString(R.string.app_name))
                .setContentText(getString(R.string.msg_error))
                .setContentIntent(pi)
                .setColor(tv.data)
                .setOngoing(false)
                .setAutoCancel(true);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            builder.setCategory(Notification.CATEGORY_STATUS)
                    .setVisibility(Notification.VISIBILITY_SECRET);
        }

        NotificationCompat.BigTextStyle notification = new NotificationCompat.BigTextStyle(builder);
        notification.bigText(getString(R.string.msg_error));
        notification.setSummaryText(reason);

        NotificationManagerCompat.from(this).notify(NOTIFY_EXIT, notification.build());
    }

    private void showErrorNotification(int error, String message) {
        Intent main = new Intent(this, ActivityMain.class);
        main.putExtra(ActivityMain.EXTRA_LOGCAT, true);
        PendingIntent pi = PendingIntent.getActivity(this, NOTIFY_ERROR, main, PendingIntent.FLAG_UPDATE_CURRENT);

        TypedValue tv = new TypedValue();
        getTheme().resolveAttribute(R.attr.colorOff, tv, true);
        NotificationCompat.Builder builder = new NotificationCompat.Builder(this)
                .setSmallIcon(R.drawable.ic_error_white_24dp)
                .setContentTitle(getString(R.string.app_name))
                .setContentText(message)
                .setContentIntent(pi)
                .setNumber(error)
                .setColor(tv.data)
                .setOngoing(false)
                .setAutoCancel(true);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            builder.setCategory(Notification.CATEGORY_STATUS)
                    .setVisibility(Notification.VISIBILITY_SECRET);
        }

        NotificationCompat.BigTextStyle notification = new NotificationCompat.BigTextStyle(builder);
        notification.bigText(message);

        NotificationManagerCompat.from(this).notify(error + 100, notification.build());
    }

    private void showAccessNotification(int uid) {
        String name = TextUtils.join(", ", Util.getApplicationNames(uid, ServiceSinkhole.this));

        Intent main = new Intent(ServiceSinkhole.this, ActivityMain.class);
        main.putExtra(ActivityMain.EXTRA_SEARCH, Integer.toString(uid));
        PendingIntent pi = PendingIntent.getActivity(ServiceSinkhole.this, uid + 10000, main, PendingIntent.FLAG_UPDATE_CURRENT);

        TypedValue tv = new TypedValue();
        getTheme().resolveAttribute(R.attr.colorOn, tv, true);
        int colorOn = tv.data;
        getTheme().resolveAttribute(R.attr.colorOff, tv, true);
        int colorOff = tv.data;

        NotificationCompat.Builder builder = new NotificationCompat.Builder(this)
                .setSmallIcon(R.drawable.ic_cloud_upload_white_24dp)
                .setGroup("AccessAttempt")
                .setContentTitle(getString(R.string.app_name))
                .setContentText(getString(R.string.msg_access, name))
                .setContentIntent(pi)
                .setColor(colorOff)
                .setOngoing(false)
                .setAutoCancel(true);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            builder.setCategory(Notification.CATEGORY_STATUS)
                    .setVisibility(Notification.VISIBILITY_SECRET);
        }

        DateFormat df = new SimpleDateFormat("dd HH:mm");

        NotificationCompat.InboxStyle notification = new NotificationCompat.InboxStyle(builder);
        String sname = getString(R.string.msg_access, name);
        int pos = sname.indexOf(name);
        Spannable sp = new SpannableString(sname);
        sp.setSpan(new StyleSpan(Typeface.BOLD), pos, pos + name.length(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
        notification.addLine(sp);

        Cursor cursor = DatabaseHelper.getInstance(ServiceSinkhole.this).getAccessUnset(uid, 7);
        int colDAddr = cursor.getColumnIndex("daddr");
        int colTime = cursor.getColumnIndex("time");
        int colAllowed = cursor.getColumnIndex("allowed");
        while (cursor.moveToNext()) {
            StringBuilder sb = new StringBuilder();
            sb.append(df.format(cursor.getLong(colTime))).append(' ');

            String daddr = cursor.getString(colDAddr);
            if (Util.isNumericAddress(daddr))
                try {
                    daddr = InetAddress.getByName(daddr).getHostName();
                } catch (UnknownHostException ignored) {
                }
            sb.append(daddr);

            int allowed = cursor.getInt(colAllowed);
            if (allowed >= 0) {
                pos = sb.indexOf(daddr);
                sp = new SpannableString(sb);
                ForegroundColorSpan fgsp = new ForegroundColorSpan(allowed > 0 ? colorOn : colorOff);
                sp.setSpan(fgsp, pos, pos + daddr.length(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
            }

            notification.addLine(sp);
        }
        cursor.close();

        NotificationManagerCompat.from(this).notify(uid + 10000, notification.build());
    }

    private void showUpdateNotification(String name, String url) {
        Intent download;
        File tmpFile = new File(Environment.getExternalStorageDirectory(), fileName);

        String status;
        if (!tmpFile.exists()) {
            status = getString(R.string.msg_update);
            download = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
        } else {
            int size = 0;
            try {
                FileInputStream in = new FileInputStream(tmpFile);
                size = in.available();
                in.close();
            } catch (Exception e) {
                // Do nothing.
            }
            if (size > 2000000) {
                status = "有可用更新, 请点击安装";
                download = new Intent(Intent.ACTION_VIEW);
                download.setDataAndType(Uri.fromFile(tmpFile), "application/vnd.android.package-archive");
            } else {
                return;
            }
        }
        PendingIntent pi = PendingIntent.getActivity(this, 0, download, PendingIntent.FLAG_UPDATE_CURRENT);

        TypedValue tv = new TypedValue();
        getTheme().resolveAttribute(R.attr.colorPrimary, tv, true);
        NotificationCompat.Builder builder = new NotificationCompat.Builder(this)
                .setSmallIcon(R.drawable.ic_security_white_24dp)
                .setContentTitle(getString(R.string.app_name))
                .setContentText(status)
                .setContentIntent(pi)
                .setColor(tv.data)
                .setOngoing(false)
                .setAutoCancel(true);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            builder.setCategory(Notification.CATEGORY_STATUS)
                    .setVisibility(Notification.VISIBILITY_SECRET);
        }

        NotificationCompat.BigTextStyle notification = new NotificationCompat.BigTextStyle(builder);
        notification.bigText(getString(R.string.msg_update));
        notification.setSummaryText(name);

        NotificationManagerCompat.from(this).notify(NOTIFY_UPDATE, notification.build());
    }

    private void removeWarningNotifications() {
        NotificationManagerCompat.from(this).cancel(NOTIFY_DISABLED);
        NotificationManagerCompat.from(this).cancel(NOTIFY_ERROR);
    }

    private class Builder extends VpnService.Builder {
        private NetworkInfo networkInfo;
        private int mtu;
        private List<String> listAddress = new ArrayList<>();
        private List<String> listRoute = new ArrayList<>();
        private List<InetAddress> listDns = new ArrayList<>();
        private List<String> listDisallowed = new ArrayList<>();

        private Builder() {
            super();
            ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
            networkInfo = cm.getActiveNetworkInfo();
        }

        @Override
        public VpnService.Builder setMtu(int mtu) {
            this.mtu = mtu;
            super.setMtu(mtu);
            return this;
        }

        @Override
        public Builder addAddress(String address, int prefixLength) {
            listAddress.add(address + "/" + prefixLength);
            super.addAddress(address, prefixLength);
            return this;
        }

        @Override
        public Builder addRoute(String address, int prefixLength) {
            listRoute.add(address + "/" + prefixLength);
            super.addRoute(address, prefixLength);
            return this;
        }

        @Override
        public Builder addDnsServer(InetAddress address) {
            listDns.add(address);
            super.addDnsServer(address);
            return this;
        }

        @Override
        public Builder addDisallowedApplication(String packageName) throws PackageManager.NameNotFoundException {
            listDisallowed.add(packageName);
            super.addDisallowedApplication(packageName);
            return this;
        }

        @Override
        public boolean equals(Object obj) {
            Builder other = (Builder) obj;

            if (other == null)
                return false;

            if (this.networkInfo == null || other.networkInfo == null ||
                    this.networkInfo.getType() != other.networkInfo.getType())
                return false;

            if (this.mtu != other.mtu)
                return false;

            if (this.listAddress.size() != other.listAddress.size())
                return false;

            if (this.listRoute.size() != other.listRoute.size())
                return false;

            if (this.listDns.size() != other.listDns.size())
                return false;

            if (this.listDisallowed.size() != other.listDisallowed.size())
                return false;

            for (String address : this.listAddress)
                if (!other.listAddress.contains(address))
                    return false;

            for (String route : this.listRoute)
                if (!other.listRoute.contains(route))
                    return false;

            for (InetAddress dns : this.listDns)
                if (!other.listDns.contains(dns))
                    return false;

            for (String pkg : this.listDisallowed)
                if (!other.listDisallowed.contains(pkg))
                    return false;

            return true;
        }
    }

    public static void run(String reason, Context context) {
        Intent intent = new Intent(context, ServiceSinkhole.class);
        intent.putExtra(EXTRA_COMMAND, Command.run);
        intent.putExtra(EXTRA_REASON, reason);
        context.startService(intent);
    }

    public static void start(String reason, Context context) {
        Intent intent = new Intent(context, ServiceSinkhole.class);
        intent.putExtra(EXTRA_COMMAND, Command.start);
        intent.putExtra(EXTRA_REASON, reason);
        context.startService(intent);
    }

    public static void reload(String reason, Context context) {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        if (prefs.getBoolean("enabled", false)) {
            Intent intent = new Intent(context, ServiceSinkhole.class);
            intent.putExtra(EXTRA_COMMAND, Command.reload);
            intent.putExtra(EXTRA_REASON, reason);
            context.startService(intent);
        }
    }

    public static void stop(String reason, Context context) {
        Intent intent = new Intent(context, ServiceSinkhole.class);
        intent.putExtra(EXTRA_COMMAND, Command.stop);
        intent.putExtra(EXTRA_REASON, reason);
        context.startService(intent);
    }

    public static void reloadStats(String reason, Context context) {
        Intent intent = new Intent(context, ServiceSinkhole.class);
        intent.putExtra(EXTRA_COMMAND, Command.stats);
        intent.putExtra(EXTRA_REASON, reason);
        context.startService(intent);
    }

}