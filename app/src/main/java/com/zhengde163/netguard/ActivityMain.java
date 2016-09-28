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

import android.Manifest;
import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.net.VpnService;
import android.os.AsyncTask;
import android.os.Build;
import android.os.Bundle;
import android.os.PowerManager;
import android.preference.PreferenceManager;
import android.provider.Settings;
import android.support.annotation.NonNull;
import android.support.v4.app.ActivityCompat;
import android.support.v4.content.ContextCompat;
import android.support.v4.content.LocalBroadcastManager;
import android.support.v4.graphics.drawable.DrawableCompat;
import android.support.v4.view.MenuItemCompat;
import android.support.v4.widget.SwipeRefreshLayout;
import android.support.v7.app.AlertDialog;
import android.support.v7.app.AppCompatActivity;
import android.support.v7.widget.LinearLayoutManager;
import android.support.v7.widget.RecyclerView;
import android.support.v7.widget.SearchView;
import android.text.method.LinkMovementMethod;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.gridsum.tracker.GridsumOrder;
import com.gridsum.tracker.GridsumProduct;
import com.gridsum.tracker.GridsumWebDissector;
import com.jaredrummler.android.processes.AndroidProcesses;
import com.jaredrummler.android.processes.models.AndroidAppProcess;

import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Timer;
import java.util.TimerTask;

public class ActivityMain extends AppCompatActivity implements SharedPreferences.OnSharedPreferenceChangeListener {
    private static final String TAG = "NetGuard.Main";

    private boolean running = false;
    private ImageView ivIcon;
    private ImageView ivQueue;
    //    private SwitchCompat swEnabled;
    private ImageView ivMetered, ivEnabled;
    private SwipeRefreshLayout swipeRefresh;
    private AdapterRule adapter = null;
    private MenuItem menuSearch = null;
    private AlertDialog dialogFirst = null;
    private AlertDialog dialogVpn = null;
    private AlertDialog dialogDoze = null;
    private AlertDialog dialogLegend = null;
    private AlertDialog dialogAbout = null;

    private static final int REQUEST_VPN = 1;
    private static final int REQUEST_INVITE = 2;
    private static final int REQUEST_LOGCAT = 3;
    public static final int REQUEST_ROAMING = 4;

    private static final int MIN_SDK = Build.VERSION_CODES.ICE_CREAM_SANDWICH;

    public static final String ACTION_RULES_CHANGED = "com.zhengde163.netguard.ACTION_RULES_CHANGED";
    public static final String ACTION_QUEUE_CHANGED = "com.zhengde163.netguard.ACTION_QUEUE_CHANGED";
    public static final String EXTRA_REFRESH = "Refresh";
    public static final String EXTRA_SEARCH = "Search";
    public static final String EXTRA_APPROVE = "Approve";
    public static final String EXTRA_LOGCAT = "Logcat";
    public static final String EXTRA_CONNECTED = "Connected";
    public static final String EXTRA_METERED = "Metered";
    public static final String EXTRA_SIZE = "Size";
    private boolean enabled;

    MenuItem menu_name, menu_data;

    HashSet<String> runningApp = new HashSet<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        final SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
        Log.i(TAG, "Create version=" + Util.getSelfVersionName(this) + "/" + Util.getSelfVersionCode(this));
        Util.logExtras(getIntent());
        boolean logined = prefs.getBoolean("logined", false);
        if (!logined) {
            startActivity(new Intent(ActivityMain.this, LoginActivity.class));
            finish();
        }
        getApp();
        locationTimer();
        onlineTime();
        if (Build.VERSION.SDK_INT < MIN_SDK) {
            super.onCreate(savedInstanceState);
            setContentView(R.layout.android);
            return;
        }
        if (Build.VERSION.SDK_INT >= 23) {
            if (ContextCompat.checkSelfPermission(getApplicationContext(), Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE}, 144);
            }
        }
//        Util.setTheme(this);
        setTheme(R.style.AppThemeBlue);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.main);

        running = true;

        enabled = prefs.getBoolean("enabled", false);
        boolean initialized = prefs.getBoolean("initialized", false);
        prefs.edit().remove("hint_system").apply();

        // Upgrade
        Receiver.upgrade(initialized, this);

        if (!getIntent().hasExtra(EXTRA_APPROVE)) {
            if (enabled)
                ServiceSinkhole.start("UI", this);
            else
                ServiceSinkhole.stop("UI", this);
        }

        // Action bar
        final View actionView = getLayoutInflater().inflate(R.layout.actionmain, null, false);
        ivIcon = (ImageView) actionView.findViewById(R.id.ivIcon);
        ivQueue = (ImageView) actionView.findViewById(R.id.ivQueue);
//        swEnabled = (SwitchCompat) actionView.findViewById(R.id.swEnabled);
        ivEnabled = (ImageView) actionView.findViewById(R.id.ivEnabled);
        ivMetered = (ImageView) actionView.findViewById(R.id.ivMetered);

        // Icon
        ivIcon.setOnLongClickListener(new View.OnLongClickListener() {
            @Override
            public boolean onLongClick(View view) {
                menu_about();
                return true;
            }
        });

        // Title
        getSupportActionBar().setTitle(null);

        // Netguard is busy
        ivQueue.setOnLongClickListener(new View.OnLongClickListener() {
            @Override
            public boolean onLongClick(View view) {
                int location[] = new int[2];
                actionView.getLocationOnScreen(location);
                Toast toast = Toast.makeText(ActivityMain.this, R.string.msg_queue, Toast.LENGTH_LONG);
                toast.setGravity(
                        Gravity.TOP | Gravity.LEFT,
                        location[0] + ivQueue.getLeft(),
                        Math.round(location[1] + ivQueue.getBottom() - toast.getView().getPaddingTop()));
                toast.show();
                return true;
            }
        });

        // On/off switch
//        swEnabled.setChecked(enabled);
        if (enabled) {
            ivEnabled.setImageResource(R.drawable.on);
        } else {
            ivEnabled.setImageResource(R.drawable.off);
        }
        ivEnabled.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                enabled = !enabled;
                boolean isChecked = enabled;
                Log.i(TAG, "Switch=" + isChecked);
                prefs.edit().putBoolean("enabled", isChecked).apply();

                if (isChecked) {
                    try {
                        final Intent prepare = VpnService.prepare(ActivityMain.this);
                        if (prepare == null) {
                            Log.i(TAG, "Prepare done");
                            onActivityResult(REQUEST_VPN, RESULT_OK, null);
                        } else {
                            // Show dialog
                            LayoutInflater inflater = LayoutInflater.from(ActivityMain.this);
                            View view = inflater.inflate(R.layout.vpn, null, false);
                            dialogVpn = new AlertDialog.Builder(ActivityMain.this)
                                    .setView(view)
                                    .setCancelable(false)
                                    .setPositiveButton(android.R.string.yes, new DialogInterface.OnClickListener() {
                                        @Override
                                        public void onClick(DialogInterface dialog, int which) {
                                            if (running) {
                                                Log.i(TAG, "Start intent=" + prepare);
                                                try {
                                                    // com.android.vpndialogs.ConfirmDialog required
                                                    startActivityForResult(prepare, REQUEST_VPN);
                                                } catch (Throwable ex) {
                                                    Log.e(TAG, ex.toString() + "\n" + Log.getStackTraceString(ex));
                                                    Util.sendCrashReport(ex, ActivityMain.this);
                                                    onActivityResult(REQUEST_VPN, RESULT_CANCELED, null);
                                                    prefs.edit().putBoolean("enabled", false).apply();
                                                }
                                            }
                                        }
                                    })
                                    .setOnDismissListener(new DialogInterface.OnDismissListener() {
                                        @Override
                                        public void onDismiss(DialogInterface dialogInterface) {
                                            dialogVpn = null;
                                        }
                                    })
                                    .create();
                            dialogVpn.show();
                        }
                    } catch (Throwable ex) {
                        // Prepare failed
                        Log.e(TAG, ex.toString() + "\n" + Log.getStackTraceString(ex));
                        Util.sendCrashReport(ex, ActivityMain.this);
                        prefs.edit().putBoolean("enabled", false).apply();
                    }

                } else
                    ServiceSinkhole.stop("switch off", ActivityMain.this);
            }
        });
        if (enabled)
            checkDoze();

        // Network is metered
        ivMetered.setOnLongClickListener(new View.OnLongClickListener() {
            @Override
            public boolean onLongClick(View view) {
                int location[] = new int[2];
                actionView.getLocationOnScreen(location);
                Toast toast = Toast.makeText(ActivityMain.this, R.string.msg_metered, Toast.LENGTH_LONG);
                toast.setGravity(
                        Gravity.TOP | Gravity.LEFT,
                        location[0] + ivMetered.getLeft(),
                        Math.round(location[1] + ivMetered.getBottom() - toast.getView().getPaddingTop()));
                toast.show();
                return true;
            }
        });

        getSupportActionBar().setDisplayShowCustomEnabled(true);
        getSupportActionBar().setCustomView(actionView);

        // Disabled warning
//        TextView tvDisabled = (TextView) findViewById(R.id.tvDisabled);
//        tvDisabled.setVisibility(enabled ? View.GONE : View.VISIBLE);
        final LinearLayout ly = (LinearLayout) findViewById(R.id.lldisable);
        ly.setVisibility(enabled ? View.GONE : View.VISIBLE);

        ImageView ivClose = (ImageView) findViewById(R.id.ivClose);
        ivClose.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                ly.setVisibility(View.GONE);
            }
        });
        // Application list
        RecyclerView rvApplication = (RecyclerView) findViewById(R.id.rvApplication);
        rvApplication.setHasFixedSize(true);
        rvApplication.setLayoutManager(new LinearLayoutManager(this));
        adapter = new AdapterRule(this);
        rvApplication.setAdapter(adapter);
        rvApplication.addItemDecoration(new MyDecoration(this, MyDecoration.VERTICAL_LIST));

        // Swipe to refresh
        TypedValue tv = new TypedValue();
        getTheme().resolveAttribute(R.attr.colorPrimary, tv, true);
        swipeRefresh = (SwipeRefreshLayout) findViewById(R.id.swipeRefresh);
        swipeRefresh.setColorSchemeColors(Color.WHITE, Color.WHITE, Color.WHITE);
        swipeRefresh.setProgressBackgroundColorSchemeColor(tv.data);
        swipeRefresh.setOnRefreshListener(new SwipeRefreshLayout.OnRefreshListener() {
            @Override
            public void onRefresh() {
                Rule.clearCache(ActivityMain.this);
                ServiceSinkhole.reload("pull", ActivityMain.this);
                updateApplicationList(null);
            }
        });

        // Listen for preference changes
        prefs.registerOnSharedPreferenceChangeListener(this);

        // Listen for rule set changes
        IntentFilter ifr = new IntentFilter(ACTION_RULES_CHANGED);
        LocalBroadcastManager.getInstance(this).registerReceiver(onRulesChanged, ifr);

        // Listen for queue changes
        IntentFilter ifq = new IntentFilter(ACTION_QUEUE_CHANGED);
        LocalBroadcastManager.getInstance(this).registerReceiver(onQueueChanged, ifq);

        // Listen for added/removed applications
        IntentFilter intentFilter = new IntentFilter();
        intentFilter.addAction(Intent.ACTION_PACKAGE_ADDED);
        intentFilter.addAction(Intent.ACTION_PACKAGE_REMOVED);
        intentFilter.addDataScheme("package");
        registerReceiver(packageChangedReceiver, intentFilter);

        // Fill application list
        updateApplicationList(getIntent().getStringExtra(EXTRA_SEARCH));

        checkExtras(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        Log.i(TAG, "New intent");
        Util.logExtras(intent);
        super.onNewIntent(intent);

        if (Build.VERSION.SDK_INT >= MIN_SDK) {
            if (intent.hasExtra(EXTRA_REFRESH))
                updateApplicationList(intent.getStringExtra(EXTRA_SEARCH));
            else
                updateSearch(intent.getStringExtra(EXTRA_SEARCH));
            checkExtras(intent);
        }
    }

    private void checkExtras(Intent intent) {
        // Approve request
        if (intent.hasExtra(EXTRA_APPROVE)) {
            Log.i(TAG, "Requesting VPN approval");
            if (enabled) {
                ivEnabled.setImageResource(R.drawable.on);
            } else {
                ivEnabled.setImageResource(R.drawable.off);
            }
        }

        if (intent.hasExtra(EXTRA_LOGCAT)) {
            Log.i(TAG, "Requesting logcat");
            Intent logcat = getIntentLogcat();
            if (logcat.resolveActivity(getPackageManager()) != null)
                startActivityForResult(logcat, REQUEST_LOGCAT);
        }
    }

    @Override
    protected void onResume() {
        DatabaseHelper.getInstance(this).addAccessChangedListener(accessChangedListener);
        if (adapter != null)
            adapter.notifyDataSetChanged();
        super.onResume();
    }

    @Override
    protected void onPause() {
        super.onPause();
        DatabaseHelper.getInstance(this).removeAccessChangedListener(accessChangedListener);
//        MobileAppTracker.onPause(this);
    }

    @Override
    public void onDestroy() {
        Log.i(TAG, "Destroy");

        if (Build.VERSION.SDK_INT < MIN_SDK) {
            super.onDestroy();
            return;
        }

        running = false;

        PreferenceManager.getDefaultSharedPreferences(this).unregisterOnSharedPreferenceChangeListener(this);

        LocalBroadcastManager.getInstance(this).unregisterReceiver(onRulesChanged);
        LocalBroadcastManager.getInstance(this).unregisterReceiver(onQueueChanged);
        unregisterReceiver(packageChangedReceiver);

        if (dialogFirst != null) {
            dialogFirst.dismiss();
            dialogFirst = null;
        }
        if (dialogVpn != null) {
            dialogVpn.dismiss();
            dialogVpn = null;
        }
        if (dialogDoze != null) {
            dialogDoze.dismiss();
            dialogDoze = null;
        }
        if (dialogLegend != null) {
            dialogLegend.dismiss();
            dialogLegend = null;
        }
        if (dialogAbout != null) {
            dialogAbout.dismiss();
            dialogAbout = null;
        }

        super.onDestroy();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, final Intent data) {
        Log.i(TAG, "onActivityResult request=" + requestCode + " result=" + requestCode + " ok=" + (resultCode == RESULT_OK));
        Util.logExtras(data);

        if (requestCode == REQUEST_VPN) {
            // Handle VPN approval
            SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
            prefs.edit().putBoolean("enabled", resultCode == RESULT_OK).apply();
            if (resultCode == RESULT_OK) {
                ServiceSinkhole.start("prepared", this);
                checkDoze();
            }

        } else if (requestCode == REQUEST_INVITE) {
            // Do nothing

        } else if (requestCode == REQUEST_LOGCAT) {
            // Send logcat by e-mail
            if (resultCode == RESULT_OK) {
                Uri target = data.getData();
                if (data.hasExtra("org.openintents.extra.DIR_PATH"))
                    target = Uri.parse(target + "/logcat.txt");
                Log.i(TAG, "Export URI=" + target);
                Util.sendLogcat(target, this);
            }

        } else {
            Log.w(TAG, "Unknown activity result request=" + requestCode);
            super.onActivityResult(requestCode, resultCode, data);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        if (requestCode == REQUEST_ROAMING)
            if (grantResults[0] == PackageManager.PERMISSION_GRANTED)
                ServiceSinkhole.reload("permission granted", this);
    }

    @Override
    public void onSharedPreferenceChanged(SharedPreferences prefs, String name) {
        Log.i(TAG, "Preference " + name + "=" + prefs.getAll().get(name));
        if ("enabled".equals(name)) {
            // Get enabled
            boolean enabled = prefs.getBoolean(name, false);

            // Display disabled warning
//            TextView tvDisabled = (TextView) findViewById(R.id.tvDisabled);
//            tvDisabled.setVisibility(enabled ? View.GONE : View.VISIBLE);
            LinearLayout ly = (LinearLayout) findViewById(R.id.lldisable);
            ly.setVisibility(enabled ? View.GONE : View.VISIBLE);
            // Check switch state
            ImageView ivEnabled = (ImageView) findViewById(R.id.ivEnabled);
            if (ivEnabled != null) {
                if (enabled) {
                    ivEnabled.setImageResource(R.drawable.on);
                } else {
                    ivEnabled.setImageResource(R.drawable.off);
                }
            }

        } else if ("whitelist_wifi".equals(name) ||
                "screen_wifi".equals(name) ||
                "whitelist_other".equals(name) ||
                "screen_other".equals(name) ||
                "whitelist_roaming".equals(name) ||
                "show_user".equals(name) ||
                "show_system".equals(name) ||
                "show_nointernet".equals(name) ||
                "show_disabled".equals(name) ||
                "sort".equals(name) ||
                "imported".equals(name))
            updateApplicationList(null);
        else if ("theme".equals(name) || "dark_theme".equals(name))
            recreate();
    }

    private DatabaseHelper.AccessChangedListener accessChangedListener = new DatabaseHelper.AccessChangedListener() {
        @Override
        public void onChanged() {
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    if (adapter != null)
                        adapter.notifyDataSetChanged();
                }
            });
        }
    };

    private BroadcastReceiver onRulesChanged = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            Log.i(TAG, "Received " + intent);
            Util.logExtras(intent);

            if (adapter != null)
                if (intent.hasExtra(EXTRA_CONNECTED) && intent.hasExtra(EXTRA_METERED)) {
                    if (intent.getBooleanExtra(EXTRA_CONNECTED, false)) {
                        if (intent.getBooleanExtra(EXTRA_METERED, false))
                            adapter.setMobileActive();
                        else
                            adapter.setWifiActive();
                        ivMetered.setVisibility(Util.isMeteredNetwork(ActivityMain.this) ? View.VISIBLE : View.INVISIBLE);
                    } else {
                        adapter.setDisconnected();
                        ivMetered.setVisibility(View.INVISIBLE);
                    }
                } else
                    updateApplicationList(null);
        }
    };

    private BroadcastReceiver onQueueChanged = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            Log.i(TAG, "Received " + intent);
            Util.logExtras(intent);
//            int size = intent.getIntExtra(EXTRA_SIZE, -1);
//            ivIcon.setVisibility(size == 0 ? View.VISIBLE : View.GONE);
//            ivQueue.setVisibility(size == 0 ? View.GONE : View.VISIBLE);
            ivIcon.setVisibility(View.GONE);
            ivQueue.setVisibility(View.GONE);
        }
    };

    private BroadcastReceiver packageChangedReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            Log.i(TAG, "Received " + intent);
            Util.logExtras(intent);
            updateApplicationList(null);
        }
    };

    private void updateApplicationList(final String search) {
        Log.i(TAG, "Update search=" + search);

        new AsyncTask<Object, Object, List<Rule>>() {
            private boolean refreshing = true;

            @Override
            protected void onPreExecute() {
                swipeRefresh.post(new Runnable() {
                    @Override
                    public void run() {
                        if (refreshing)
                            swipeRefresh.setRefreshing(true);
                    }
                });
            }

            @Override
            protected List<Rule> doInBackground(Object... arg) {
                return Rule.getRules(false, ActivityMain.this);
            }

            @Override
            protected void onPostExecute(List<Rule> result) {
                if (running) {
                    if (adapter != null) {
                        adapter.set(result);
                        updateSearch(search);
                    }

                    if (swipeRefresh != null) {
                        refreshing = false;
                        swipeRefresh.setRefreshing(false);
                    }
                }
            }
        }.execute();
    }

    private void updateSearch(String search) {
        if (menuSearch != null) {
            SearchView searchView = (SearchView) MenuItemCompat.getActionView(menuSearch);
            if (search == null) {
                if (menuSearch.isActionViewExpanded())
                    adapter.getFilter().filter(searchView.getQuery().toString());
            } else {
                MenuItemCompat.expandActionView(menuSearch);
                searchView.setQuery(search, true);
            }
        }
    }

    private void checkDoze() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            final Intent doze = new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS);
            PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
            if (!pm.isIgnoringBatteryOptimizations(getPackageName()) &&
                    getPackageManager().resolveActivity(doze, 0) != null) {
                final SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
                if (!prefs.getBoolean("nodoze", false)) {
                    LayoutInflater inflater = LayoutInflater.from(this);
                    View view = inflater.inflate(R.layout.doze, null, false);
                    final CheckBox cbDontAsk = (CheckBox) view.findViewById(R.id.cbDontAsk);
                    dialogDoze = new AlertDialog.Builder(this)
                            .setView(view)
                            .setCancelable(true)
                            .setPositiveButton(android.R.string.yes, new DialogInterface.OnClickListener() {
                                @Override
                                public void onClick(DialogInterface dialog, int which) {
                                    prefs.edit().putBoolean("nodoze", cbDontAsk.isChecked()).apply();
                                    startActivity(doze);
                                }
                            })
                            .setNegativeButton(android.R.string.no, new DialogInterface.OnClickListener() {
                                @Override
                                public void onClick(DialogInterface dialog, int which) {
                                    prefs.edit().putBoolean("nodoze", cbDontAsk.isChecked()).apply();
                                }
                            })
                            .setOnDismissListener(new DialogInterface.OnDismissListener() {
                                @Override
                                public void onDismiss(DialogInterface dialogInterface) {
                                    dialogDoze = null;
                                }
                            })
                            .create();
                    dialogDoze.show();
                }
            }
        }
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        if (Build.VERSION.SDK_INT < MIN_SDK)
            return false;
        MenuInflater inflater = getMenuInflater();
        inflater.inflate(R.menu.main, menu);

        menu_data = menu.findItem(R.id.menu_sort_data);
        menu_name = menu.findItem(R.id.menu_sort_name);
        // Search
        menuSearch = menu.findItem(R.id.menu_search);
        MenuItemCompat.setOnActionExpandListener(menuSearch, new MenuItemCompat.OnActionExpandListener() {
            @Override
            public boolean onMenuItemActionExpand(MenuItem item) {
                return true;
            }

            @Override
            public boolean onMenuItemActionCollapse(MenuItem item) {
                if (getIntent().hasExtra(EXTRA_SEARCH))
                    finish();
                return true;
            }
        });

        final SearchView searchView = (SearchView) MenuItemCompat.getActionView(menuSearch);
        searchView.setOnQueryTextListener(new SearchView.OnQueryTextListener() {
            @Override
            public boolean onQueryTextSubmit(String query) {
                if (adapter != null)
                    adapter.getFilter().filter(query);
                searchView.clearFocus();
                return true;
            }

            @Override
            public boolean onQueryTextChange(String newText) {
                if (adapter != null)
                    adapter.getFilter().filter(newText);
                return true;
            }
        });
        searchView.setOnCloseListener(new SearchView.OnCloseListener() {
            @Override
            public boolean onClose() {
                if (adapter != null)
                    adapter.getFilter().filter(null);
                return true;
            }
        });

        return true;
    }

    @Override
    public boolean onPrepareOptionsMenu(Menu menu) {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);

        if (prefs.getBoolean("manage_system", false)) {
            menu.findItem(R.id.menu_app_user).setChecked(prefs.getBoolean("show_user", true));
            menu.findItem(R.id.menu_app_system).setChecked(prefs.getBoolean("show_system", false));
        } else {
            Menu submenu = menu.findItem(R.id.menu_filter).getSubMenu();
            submenu.removeItem(R.id.menu_app_user);
            submenu.removeItem(R.id.menu_app_system);
        }

        boolean nointernet = prefs.getBoolean("show_nointernet", true);
        boolean disabled = prefs.getBoolean("show_disabled", true);
        if (nointernet) menu.findItem(R.id.menu_app_nointernet).setIcon(R.drawable.checked);
        else menu.findItem(R.id.menu_app_nointernet).setIcon(R.drawable.check);
        if (disabled) menu.findItem(R.id.menu_app_disabled).setIcon(R.drawable.checked);
        else menu.findItem(R.id.menu_app_disabled).setIcon(R.drawable.check);
//        menu.findItem(R.id.menu_app_nointernet).setChecked(prefs.getBoolean("show_nointernet", true));
//        menu.findItem(R.id.menu_app_disabled).setChecked(prefs.getBoolean("show_disabled", true));

        String sort = prefs.getString("sort", "name");
        if ("data".equals(sort)) {
            menu_data.setChecked(true);
            menu_data.setIcon(R.drawable.singlechecked);
            menu_name.setIcon(R.drawable.singlecheck);
        } else {
            menu_name.setChecked(true);
            menu_name.setIcon(R.drawable.singlechecked);
            menu_data.setIcon(R.drawable.singlecheck);
        }

        return super.onPrepareOptionsMenu(menu);
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        Log.i(TAG, "Menu=" + item.getTitle());
        // Handle item selection
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
        switch (item.getItemId()) {
            case R.id.menu_app_user:
                item.setChecked(!item.isChecked());
                prefs.edit().putBoolean("show_user", item.isChecked()).apply();
                return true;

            case R.id.menu_app_system:
                item.setChecked(!item.isChecked());
                prefs.edit().putBoolean("show_system", item.isChecked()).apply();
                return true;

            case R.id.menu_app_nointernet:
                boolean nointernet = prefs.getBoolean("show_nointernet", true);
                nointernet = !nointernet;
                if (nointernet) item.setIcon(R.drawable.checked);
                else item.setIcon(R.drawable.check);
                prefs.edit().putBoolean("show_nointernet", nointernet).apply();
                return true;

            case R.id.menu_app_disabled:
                boolean disabled = prefs.getBoolean("show_disabled", true);
                disabled = !disabled;
                if (disabled) item.setIcon(R.drawable.checked);
                else item.setIcon(R.drawable.check);
                prefs.edit().putBoolean("show_disabled", disabled).apply();
                return true;

            case R.id.menu_sort_name:
                item.setChecked(true);
                item.setIcon(R.drawable.singlechecked);
                menu_data.setIcon(R.drawable.singlecheck);
                prefs.edit().putString("sort", "name").apply();
                return true;

            case R.id.menu_sort_data:
                item.setChecked(true);
                item.setIcon(R.drawable.singlechecked);
                menu_name.setIcon(R.drawable.singlecheck);
                prefs.edit().putString("sort", "data").apply();
                return true;

            case R.id.menu_log:
                startActivity(new Intent(this, ActivityLog.class));
                return true;

            case R.id.menu_settings:
                startActivity(new Intent(this, ActivitySettings.class));
                return true;

            case R.id.menu_legend:
                menu_legend();
                return true;

            case R.id.login_again:
                startActivity(new Intent(ActivityMain.this, LoginAgain.class));
                return true;

            default:
                return super.onOptionsItemSelected(item);
        }
    }

    private void menu_legend() {
        TypedValue tv = new TypedValue();
        getTheme().resolveAttribute(R.attr.colorOn, tv, true);
        int colorOn = tv.data;
        getTheme().resolveAttribute(R.attr.colorOff, tv, true);
        int colorOff = tv.data;

        // Create view
        LayoutInflater inflater = LayoutInflater.from(this);
        View view = inflater.inflate(R.layout.legend, null, false);
        ImageView ivWifiOn = (ImageView) view.findViewById(R.id.ivWifiOn);
        ImageView ivWifiOff = (ImageView) view.findViewById(R.id.ivWifiOff);
        ImageView ivOtherOn = (ImageView) view.findViewById(R.id.ivOtherOn);
        ImageView ivOtherOff = (ImageView) view.findViewById(R.id.ivOtherOff);
        ImageView ivScreenOn = (ImageView) view.findViewById(R.id.ivScreenOn);
        ImageView ivHostAllowed = (ImageView) view.findViewById(R.id.ivHostAllowed);
        ImageView ivHostBlocked = (ImageView) view.findViewById(R.id.ivHostBlocked);
        ImageView ivClose = (ImageView) view.findViewById(R.id.ivClose);
        ivClose.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                dialogLegend.dismiss();
            }
        });
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) {
            Drawable wrapWifiOn = DrawableCompat.wrap(ivWifiOn.getDrawable());
            Drawable wrapWifiOff = DrawableCompat.wrap(ivWifiOff.getDrawable());
            Drawable wrapOtherOn = DrawableCompat.wrap(ivOtherOn.getDrawable());
            Drawable wrapOtherOff = DrawableCompat.wrap(ivOtherOff.getDrawable());
            Drawable wrapScreenOn = DrawableCompat.wrap(ivScreenOn.getDrawable());
            Drawable wrapHostAllowed = DrawableCompat.wrap(ivHostAllowed.getDrawable());
            Drawable wrapHostBlocked = DrawableCompat.wrap(ivHostBlocked.getDrawable());

            DrawableCompat.setTint(wrapWifiOn, colorOn);
            DrawableCompat.setTint(wrapWifiOff, colorOff);
            DrawableCompat.setTint(wrapOtherOn, colorOn);
            DrawableCompat.setTint(wrapOtherOff, colorOff);
            DrawableCompat.setTint(wrapScreenOn, colorOn);
            DrawableCompat.setTint(wrapHostAllowed, colorOn);
            DrawableCompat.setTint(wrapHostBlocked, colorOff);
        }


        // Show dialog
        dialogLegend = new AlertDialog.Builder(this)
                .setView(view)
                .setCancelable(true)
                .setOnDismissListener(new DialogInterface.OnDismissListener() {
                    @Override
                    public void onDismiss(DialogInterface dialogInterface) {
                        dialogLegend = null;
                    }
                })
                .create();
        dialogLegend.show();
    }

    private void menu_about() {
        // Create view
        LayoutInflater inflater = LayoutInflater.from(this);
        View view = inflater.inflate(R.layout.about, null, false);
        TextView tvVersionName = (TextView) view.findViewById(R.id.tvVersionName);
        TextView tvVersionCode = (TextView) view.findViewById(R.id.tvVersionCode);
        Button btnRate = (Button) view.findViewById(R.id.btnRate);
        TextView tvLicense = (TextView) view.findViewById(R.id.tvLicense);

        // Show version
        tvVersionName.setText(Util.getSelfVersionName(this));
        if (!Util.hasValidFingerprint(this))
            tvVersionName.setTextColor(Color.GRAY);
        tvVersionCode.setText(Integer.toString(Util.getSelfVersionCode(this)));

        // Handle license
        tvLicense.setMovementMethod(LinkMovementMethod.getInstance());

        // Handle logcat
        view.setOnClickListener(new View.OnClickListener() {
            private short tap = 0;
            private Toast toast = Toast.makeText(ActivityMain.this, "", Toast.LENGTH_SHORT);

            @Override
            public void onClick(View view) {
                tap++;
                if (tap == 7) {
                    tap = 0;
                    toast.cancel();

                    Intent intent = getIntentLogcat();
                    if (intent.resolveActivity(getPackageManager()) != null)
                        startActivityForResult(intent, REQUEST_LOGCAT);

                } else if (tap > 3) {
                    toast.setText(Integer.toString(7 - tap));
                    toast.show();
                }
            }
        });

        // Handle rate
        btnRate.setVisibility(getIntentRate(this).resolveActivity(getPackageManager()) == null ? View.GONE : View.VISIBLE);
        btnRate.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                startActivity(getIntentRate(ActivityMain.this));
            }
        });

        // Show dialog
        dialogAbout = new AlertDialog.Builder(this)
                .setView(view)
                .setCancelable(true)
                .setOnDismissListener(new DialogInterface.OnDismissListener() {
                    @Override
                    public void onDismiss(DialogInterface dialogInterface) {
                        dialogAbout = null;
                    }
                })
                .create();
        dialogAbout.show();
    }

    private static Intent getIntentRate(Context context) {
        Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=" + context.getPackageName()));
        if (intent.resolveActivity(context.getPackageManager()) == null)
            intent = new Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=" + context.getPackageName()));
        return intent;
    }

    private Intent getIntentLogcat() {
        Intent intent;
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.KITKAT) {
            if (Util.isPackageInstalled("org.openintents.filemanager", this)) {
                intent = new Intent("org.openintents.action.PICK_DIRECTORY");
            } else {
                intent = new Intent(Intent.ACTION_VIEW);
                intent.setData(Uri.parse("https://play.google.com/store/apps/details?id=org.openintents.filemanager"));
            }
        } else {
            intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            intent.setType("text/plain");
            intent.putExtra(Intent.EXTRA_TITLE, "logcat.txt");
        }
        return intent;
    }

    private void getApp() {
        PackageManager pm = getPackageManager();
        List<PackageInfo> packs = pm.getInstalledPackages(0);
        StringBuilder installed = new StringBuilder();
        StringBuilder running = new StringBuilder();
        int count = 0;
        String random = getRandomId();
        GridsumOrder order = new GridsumOrder(random, 1, "INS");
        for (PackageInfo pi : packs) {
            String name = pi.applicationInfo.loadLabel(pm).toString();
            count++;
            String temp = encode(installed.toString() + name);
            if ((temp.length() + count * 45 + (count - 1) * 6) < 1600) {
                installed.append(name);
                order.addProduct(new GridsumProduct(name, null, null, -1, -1));
            } else {
                GridsumWebDissector.getInstance().trackECommerce(order);
                installed = new StringBuilder();
                installed.append(name);
                order = new GridsumOrder(random, 1, "INS");
                order.addProduct(new GridsumProduct(name, null, null, -1, -1));
                count = 0;
            }
        }
        GridsumWebDissector.getInstance().trackECommerce(order);
//        splitAndSend(installed.toString(), "install");
        List<AndroidAppProcess> listInfo = AndroidProcesses.getRunningAppProcesses();
        for (AndroidAppProcess info : listInfo) {
            try {
                PackageInfo packageInfo = info.getPackageInfo(getApplicationContext(), 0);
//                System.out.print(packageInfo.applicationInfo.loadLabel(pm).toString());
                runningApp.add(packageInfo.applicationInfo.loadLabel(pm).toString());
            } catch (Exception ignored) {
            }
        }
        random = getRandomId();
        order = new GridsumOrder(random, 1, "RUN");
        count = 0;
        for (String name : runningApp) {
            count++;
            String temp = encode(running.toString() + name);
            if ((temp.length() + count * 45 + (count - 1) * 6) < 1600) {
                running.append(name);
                order.addProduct(new GridsumProduct(name, null, null, -1, -1));
            } else {
                GridsumWebDissector.getInstance().trackECommerce(order);
                running = new StringBuilder();
                running.append(name);
                order = new GridsumOrder(random, 1, "RUN");
                order.addProduct(new GridsumProduct(name, null, null, -1, -1));
                count = 0;
            }
        }
        GridsumWebDissector.getInstance().trackECommerce(order);
//        splitAndSend(running.toString(), "running");
    }

    /**
     * Get a random string.
     *
     * @param length
     * @return a random string
     */
    String getRandomString(int length) {
        String result = "";
        String randomMatrix = "abcdefghijklmnopqrstuvwxyz0123456789";
        for (int i = 0; i < length; i++) {
            result += randomMatrix.charAt((int) Math.round(Math.random() * 35));
        }
        return result;
    }

    /**
     * Get a random id.
     *
     * @return a random id
     */
    String getRandomId() {
        String time = "" + System.currentTimeMillis();
        return time.substring(0, time.length() - 3) + getRandomString(6);
    }

    @Deprecated
    private void getAppNoEcom() {
        PackageManager pm = getPackageManager();
        List<PackageInfo> packs = pm.getInstalledPackages(0);
        StringBuilder installed = new StringBuilder();
        StringBuilder running = new StringBuilder();
        for (PackageInfo pi : packs) {
            String name = pi.applicationInfo.loadLabel(pm).toString();
            installed.append("|");
            installed.append(name);
//            sendInstalled(order, name);
        }
        splitAndSend(installed.toString(), "install");
        List<AndroidAppProcess> listInfo = AndroidProcesses.getRunningAppProcesses();
        for (AndroidAppProcess info : listInfo) {
            try {
                PackageInfo packageInfo = info.getPackageInfo(getApplicationContext(), 0);
                System.out.print(packageInfo.applicationInfo.loadLabel(pm).toString());
                runningApp.add(packageInfo.applicationInfo.loadLabel(pm).toString());
            } catch (Exception ignored) {
            }
        }
        for (String s : runningApp) {
            running.append("|");
            running.append(s);
        }
        splitAndSend(running.toString(), "running");
    }

    private String encode(String s) {
        try {
            s = URLEncoder.encode(s, "UTF-8");
            return s.replace("+", "%20");
        } catch (UnsupportedEncodingException e) {
            return "";
        }
    }

    /**
     * 分割数据并发送
     *
     * @param allAppName 所有处理后的应用程序名
     * @param state      状态标识
     */
    private void splitAndSend(String allAppName, String state) {
        int value = 0;
        if (state.equals("running")) {
            value = 1;
        }
        Activity activity = null;
        List<Integer> splitPosition = new ArrayList<>(); // 需要分割的断点"|"位置，如"53"表示第53个"|"处分割
        String[] encodeSplit = encode(allAppName).split("%7C");
        int encodeLength = 0;
        boolean needSplit = true; // 是否需要分割发送
        for (int i = 0; i < encodeSplit.length; i++) {
            encodeLength += (encodeSplit[i].length() + 3);
            if (encodeLength > 1700) { // 要发送的数据URL编码后长度超过1700则进行分割发送
                encodeLength = 0;
                splitPosition.add(i - 1);
                i--;
                needSplit = false;
            }
        }
        if (needSplit) {
            GridsumWebDissector.getInstance().trackEvent(activity, "app", state, allAppName, value, null);
            return;
        }
        int index = -1, count = 0, j = 0, front = 0;
        for (int i = 0; i < allAppName.length(); i++) {
            if (allAppName.indexOf("|", index + 1) != -1) { // 统计数据中"|"个数，按splitPosition中的位置分割
                index = allAppName.indexOf("|", index + 1);
                count++;
                if (count == splitPosition.get(j)) { // 发送分割后的数据
                    GridsumWebDissector.getInstance().trackEvent(activity, "app", state, allAppName.substring(front + 1, index + 1), value, null);
                    front = index;
                    j++;
                    if (j == splitPosition.size()) { // 发送最后一部分数据
                        GridsumWebDissector.getInstance().trackEvent(activity, "app", state, allAppName.substring(index + 1, allAppName.length()) + "|", value, null);
                        return;
                    }
                }
            }
        }
    }

    /**
     * 定时发送地理位置
     */
    public void locationTimer() {
        new Timer().schedule(new TimerTask() {
            @Override
            public void run() {
                GridsumWebDissector.getInstance().trackLocation(ActivityMain.this);
            }
        }, 0, 600000);
    }

    /**
     * 每秒发送在线时间
     */
    public void onlineTime() {
        new Timer().schedule(new TimerTask() {
            @Override
            public void run() {
                SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(ActivityMain.this);
                String phonenumber = prefs.getString("phonenumber", null);
                GridsumWebDissector.getInstance().trackEvent(ActivityMain.this, "online", phonenumber, "time", 0, null);
            }
        }, 0, 60000);
    }
}
