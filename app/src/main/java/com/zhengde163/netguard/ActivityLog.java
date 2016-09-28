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

import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Build;
import android.os.Bundle;
import android.preference.PreferenceManager;
import android.support.v4.app.NavUtils;
import android.support.v4.view.MenuItemCompat;
import android.support.v7.app.AppCompatActivity;
import android.support.v7.widget.SearchView;
import android.support.v7.widget.SwitchCompat;
import android.text.TextUtils;
import android.util.Log;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.widget.AdapterView;
import android.widget.CompoundButton;
import android.widget.FilterQueryProvider;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.PopupMenu;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.LineNumberReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.text.SimpleDateFormat;
import java.util.Date;

public class ActivityLog extends AppCompatActivity implements SharedPreferences.OnSharedPreferenceChangeListener {
    private static final String TAG = "NetGuard.Log";

    private boolean running = false;
    private ListView lvLog;
    private AdapterLog adapter;
    private MenuItem menuSearch = null;

    private boolean live = true;
    private boolean resolve;
    private boolean organization;
    private InetAddress vpn4 = null;
    private InetAddress vpn6 = null;
    private boolean log;
    private static final int REQUEST_PCAP = 1;

    private DatabaseHelper.LogChangedListener listener = new DatabaseHelper.LogChangedListener() {
        @Override
        public void onChanged() {
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    updateAdapter();
                }
            });
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
//        Util.setTheme(this);
        setTheme(R.style.AppThemeBlue);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.logging);
        running = true;

        // Action bar
        View actionView = getLayoutInflater().inflate(R.layout.actionlog, null, false);
//        SwitchCompat swEnabled = (SwitchCompat) actionView.findViewById(R.id.swEnabled);
        ImageView ivEnabled = (ImageView) actionView.findViewById(R.id.ivEnabled);

        getSupportActionBar().setDisplayShowCustomEnabled(true);
        getSupportActionBar().setCustomView(actionView);

        getSupportActionBar().setTitle(R.string.menu_log);
        getSupportActionBar().setDisplayHomeAsUpEnabled(true);

        // Get settings
        final SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
        resolve = prefs.getBoolean("resolve", false);
        organization = prefs.getBoolean("organization", false);
        log = prefs.getBoolean("log", false);

        // Show disabled message
//        TextView tvDisabled = (TextView) findViewById(R.id.tvDisabled);
//        tvDisabled.setVisibility(log ? View.GONE : View.VISIBLE);
        final LinearLayout ly = (LinearLayout) findViewById(R.id.lldisable);
        ly.setVisibility(log ? View.GONE : View.VISIBLE);

        ImageView ivClose = (ImageView) findViewById(R.id.ivClose);
        ivClose.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                ly.setVisibility(View.GONE);
            }
        });
        // Set enabled switch
//        swEnabled.setChecked(log);
        if (ivEnabled != null) {
            if (log) {
                ivEnabled.setImageResource(R.drawable.on);
            } else {
                ivEnabled.setImageResource(R.drawable.off);
            }
            ivEnabled.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    log = !log;
                    boolean isChecked = log;
                    prefs.edit().putBoolean("log", isChecked).apply();

                }
            });
        }
//        swEnabled.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
//            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
//                prefs.edit().putBoolean("log", isChecked).apply();
//            }
//        });

        // Listen for preference changes
        prefs.registerOnSharedPreferenceChangeListener(this);

        lvLog = (ListView) findViewById(R.id.lvLog);

        boolean udp = prefs.getBoolean("proto_udp", true);
        boolean tcp = prefs.getBoolean("proto_tcp", true);
        boolean other = prefs.getBoolean("proto_other", true);
        boolean allowed = prefs.getBoolean("traffic_allowed", true);
        boolean blocked = prefs.getBoolean("traffic_blocked", true);

        adapter = new AdapterLog(this, DatabaseHelper.getInstance(this).getLog(udp, tcp, other, allowed, blocked), resolve, organization);
        adapter.setFilterQueryProvider(new FilterQueryProvider() {
            public Cursor runQuery(CharSequence constraint) {
                return DatabaseHelper.getInstance(ActivityLog.this).searchLog(constraint.toString());
            }
        });

        lvLog.setAdapter(adapter);

        try {
            vpn4 = InetAddress.getByName(prefs.getString("vpn4", "10.1.10.1"));
            vpn6 = InetAddress.getByName(prefs.getString("vpn6", "fd00:1:fd00:1:fd00:1:fd00:1"));
        } catch (UnknownHostException ex) {
            Log.e(TAG, ex.toString() + "\n" + Log.getStackTraceString(ex));
        }

        lvLog.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                PackageManager pm = getPackageManager();
                Cursor cursor = (Cursor) adapter.getItem(position);
                long time = cursor.getLong(cursor.getColumnIndex("time"));
                int version = cursor.getInt(cursor.getColumnIndex("version"));
                int protocol = cursor.getInt(cursor.getColumnIndex("protocol"));
                final String saddr = cursor.getString(cursor.getColumnIndex("saddr"));
                final int sport = (cursor.isNull(cursor.getColumnIndex("sport")) ? -1 : cursor.getInt(cursor.getColumnIndex("sport")));
                final String daddr = cursor.getString(cursor.getColumnIndex("daddr"));
                final int dport = (cursor.isNull(cursor.getColumnIndex("dport")) ? -1 : cursor.getInt(cursor.getColumnIndex("dport")));
                final String dname = cursor.getString(cursor.getColumnIndex("dname"));
                final int uid = (cursor.isNull(cursor.getColumnIndex("uid")) ? -1 : cursor.getInt(cursor.getColumnIndex("uid")));
                int allowed = (cursor.isNull(cursor.getColumnIndex("allowed")) ? -1 : cursor.getInt(cursor.getColumnIndex("allowed")));

                // Get external address
                InetAddress addr = null;
                try {
                    addr = InetAddress.getByName(daddr);
                } catch (UnknownHostException ex) {
                    Log.e(TAG, ex.toString() + "\n" + Log.getStackTraceString(ex));
                }

                String ip;
                int port;
                if (addr.equals(vpn4) || addr.equals(vpn6)) {
                    ip = saddr;
                    port = sport;
                } else {
                    ip = daddr;
                    port = dport;
                }

                // Build popup menu
                PopupMenu popup = new PopupMenu(ActivityLog.this, findViewById(R.id.vwPopupAnchor));
                popup.inflate(R.menu.log);

                // Application name
                if (uid >= 0)
                    popup.getMenu().findItem(R.id.menu_application).setTitle(TextUtils.join(", ", Util.getApplicationNames(uid, ActivityLog.this)));
                else
                    popup.getMenu().removeItem(R.id.menu_application);

                // Destination IP
                popup.getMenu().findItem(R.id.menu_protocol).setTitle(Util.getProtocolName(protocol, version, false));

                // Whois
                final Intent lookupIP = new Intent(Intent.ACTION_VIEW, Uri.parse("http://www.tcpiputils.com/whois-lookup/" + ip));
                if (pm.resolveActivity(lookupIP, 0) == null)
                    popup.getMenu().removeItem(R.id.menu_whois);
                else
                    popup.getMenu().findItem(R.id.menu_whois).setTitle(getString(R.string.title_log_whois, ip));

                // Lookup port
                final Intent lookupPort = new Intent(Intent.ACTION_VIEW, Uri.parse("http://www.speedguide.net/port.php?port=" + port));
                if (port <= 0 || pm.resolveActivity(lookupPort, 0) == null)
                    popup.getMenu().removeItem(R.id.menu_port);
                else
                    popup.getMenu().findItem(R.id.menu_port).setTitle(getString(R.string.title_log_port, port));

                if (!prefs.getBoolean("filter", false)) {
                    popup.getMenu().removeItem(R.id.menu_allow);
                    popup.getMenu().removeItem(R.id.menu_block);
                }

                final Packet packet = new Packet();
                packet.version = version;
                packet.protocol = protocol;
                packet.daddr = daddr;
                packet.dport = dport;
                packet.time = time;
                packet.uid = uid;
                packet.allowed = (allowed > 0);

                // Time
                popup.getMenu().findItem(R.id.menu_time).setTitle(SimpleDateFormat.getDateTimeInstance().format(time));

                // Handle click
                popup.setOnMenuItemClickListener(new PopupMenu.OnMenuItemClickListener() {
                    @Override
                    public boolean onMenuItemClick(MenuItem menuItem) {
                        switch (menuItem.getItemId()) {
                            case R.id.menu_application: {
                                Intent main = new Intent(ActivityLog.this, ActivityMain.class);
                                main.putExtra(ActivityMain.EXTRA_SEARCH, Integer.toString(uid));
                                startActivity(main);
                                return true;
                            }

                            case R.id.menu_whois:
                                startActivity(lookupIP);
                                return true;

                            case R.id.menu_port:
                                startActivity(lookupPort);
                                return true;

                            case R.id.menu_allow:
                                DatabaseHelper.getInstance(ActivityLog.this).updateAccess(packet, dname, 0);
                                ServiceSinkhole.reload("allow host", ActivityLog.this);
                                Intent main = new Intent(ActivityLog.this, ActivityMain.class);
                                main.putExtra(ActivityMain.EXTRA_SEARCH, Integer.toString(uid));
                                startActivity(main);
                                return true;

                            case R.id.menu_block:
                                DatabaseHelper.getInstance(ActivityLog.this).updateAccess(packet, dname, 1);
                                ServiceSinkhole.reload("block host", ActivityLog.this);
                                Intent main1 = new Intent(ActivityLog.this, ActivityMain.class);
                                main1.putExtra(ActivityMain.EXTRA_SEARCH, Integer.toString(uid));
                                startActivity(main1);
                                return true;

                            default:
                                return false;
                        }
                    }
                });

                // Show
                popup.show();
            }
        });

        live = true;
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (live) {
            DatabaseHelper.getInstance(this).addLogChangedListener(listener);
            updateAdapter();
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (live)
            DatabaseHelper.getInstance(this).removeLogChangedListener(listener);
    }

    @Override
    protected void onDestroy() {
        running = false;
        PreferenceManager.getDefaultSharedPreferences(this).unregisterOnSharedPreferenceChangeListener(this);
        super.onDestroy();
    }

    @Override
    public void onSharedPreferenceChanged(SharedPreferences prefs, String name) {
        Log.i(TAG, "Preference " + name + "=" + prefs.getAll().get(name));
        if ("log".equals(name)) {
            // Get enabled
            boolean log = prefs.getBoolean(name, false);

            // Display disabled warning
//            TextView tvDisabled = (TextView) findViewById(R.id.tvDisabled);
//            tvDisabled.setVisibility(log ? View.GONE : View.VISIBLE);
            LinearLayout ly = (LinearLayout) findViewById(R.id.lldisable);
            ly.setVisibility(log ? View.GONE : View.VISIBLE);
            // Check switch state
//            SwitchCompat swEnabled = (SwitchCompat) getSupportActionBar().getCustomView().findViewById(R.id.swEnabled);
            ImageView ivEnabled = (ImageView) findViewById(R.id.ivEnabled);
            if (ivEnabled != null) {
                if (log) {
                    ivEnabled.setImageResource(R.drawable.on);
                } else {
                    ivEnabled.setImageResource(R.drawable.off);
                }
            }
//            if (swEnabled.isChecked() != log)
//                swEnabled.setChecked(log);

            ServiceSinkhole.reload("changed " + name, ActivityLog.this);
        }
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        MenuInflater inflater = getMenuInflater();
        inflater.inflate(R.menu.logging, menu);

        menuSearch = menu.findItem(R.id.menu_search);
        SearchView searchView = (SearchView) MenuItemCompat.getActionView(menuSearch);
        searchView.setOnQueryTextListener(new SearchView.OnQueryTextListener() {
            @Override
            public boolean onQueryTextSubmit(String query) {
                if (adapter != null)
                    adapter.getFilter().filter(query);
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

        boolean proto_udp = prefs.getBoolean("proto_udp", true);
        boolean proto_tcp = prefs.getBoolean("proto_tcp", true);
        boolean proto_other = prefs.getBoolean("proto_other", true);
        boolean traffic_allowed = prefs.getBoolean("traffic_allowed", true);
        boolean traffic_blocked = prefs.getBoolean("traffic_blocked", true);
        if (proto_udp) menu.findItem(R.id.menu_protocol_udp).setIcon(R.drawable.checked);
        else menu.findItem(R.id.menu_protocol_udp).setIcon(R.drawable.check);
        if (proto_tcp) menu.findItem(R.id.menu_protocol_tcp).setIcon(R.drawable.checked);
        else menu.findItem(R.id.menu_protocol_tcp).setIcon(R.drawable.check);
        if (proto_other) menu.findItem(R.id.menu_protocol_other).setIcon(R.drawable.checked);
        else menu.findItem(R.id.menu_protocol_other).setIcon(R.drawable.check);
        if (traffic_allowed) menu.findItem(R.id.menu_traffic_allowed).setIcon(R.drawable.checked);
        else menu.findItem(R.id.menu_traffic_allowed).setIcon(R.drawable.check);
        if (traffic_blocked) menu.findItem(R.id.menu_traffic_blocked).setIcon(R.drawable.checked);
        else menu.findItem(R.id.menu_traffic_blocked).setIcon(R.drawable.check);
//        menu.findItem(R.id.menu_traffic_allowed).setEnabled(prefs.getBoolean("filter", false));
//        ServiceSinkhole.reload("changed filter", this);
//        menu.findItem(R.id.menu_protocol_udp).setChecked(prefs.getBoolean("proto_udp", true));
//        menu.findItem(R.id.menu_protocol_tcp).setChecked(prefs.getBoolean("proto_tcp", true));
//        menu.findItem(R.id.menu_protocol_other).setChecked(prefs.getBoolean("proto_other", true));
//        menu.findItem(R.id.menu_traffic_allowed).setEnabled(prefs.getBoolean("filter", false));
//        menu.findItem(R.id.menu_traffic_allowed).setChecked(prefs.getBoolean("traffic_allowed", true));
//        menu.findItem(R.id.menu_traffic_blocked).setChecked(prefs.getBoolean("traffic_blocked", true));

        boolean resolve = prefs.getBoolean("resolve", false);
        boolean organization = prefs.getBoolean("organization", false);
        boolean live = prefs.getBoolean("live", true);
        if (resolve) menu.findItem(R.id.menu_log_resolve).setIcon(R.drawable.checked);
        else menu.findItem(R.id.menu_log_resolve).setIcon(R.drawable.check);
        if (organization) menu.findItem(R.id.menu_log_organization).setIcon(R.drawable.checked);
        else menu.findItem(R.id.menu_log_organization).setIcon(R.drawable.check);
        if (live) menu.findItem(R.id.menu_log_live).setIcon(R.drawable.checked);
        else menu.findItem(R.id.menu_log_live).setIcon(R.drawable.check);
        menu.findItem(R.id.menu_refresh).setEnabled(!live);
//        menu.findItem(R.id.menu_log_resolve).setChecked(prefs.getBoolean("resolve", false));
//        menu.findItem(R.id.menu_log_organization).setChecked(prefs.getBoolean("organization", false));

        return super.onPrepareOptionsMenu(menu);
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        final SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
        final File pcap_file = new File(getCacheDir(), "netguard.pcap");

        switch (item.getItemId()) {
            case android.R.id.home:
                Log.i(TAG, "Up");
                NavUtils.navigateUpFromSameTask(this);
                return true;

            case R.id.menu_protocol_udp:
//                item.setChecked(!item.isChecked());
                boolean protocol_udp = prefs.getBoolean("proto_udp", true);
                protocol_udp = !protocol_udp;
                if (protocol_udp) item.setIcon(R.drawable.checked);
                else item.setIcon(R.drawable.check);
                prefs.edit().putBoolean("proto_udp", protocol_udp).apply();
                updateAdapter();
                return true;

            case R.id.menu_protocol_tcp:
                boolean proto_tcp = prefs.getBoolean("proto_tcp", true);
                proto_tcp = !proto_tcp;
                if (proto_tcp) item.setIcon(R.drawable.checked);
                else item.setIcon(R.drawable.check);
                prefs.edit().putBoolean("proto_tcp", proto_tcp).apply();
                updateAdapter();
                return true;

            case R.id.menu_protocol_other:
                boolean proto_other = prefs.getBoolean("proto_other", true);
                proto_other = !proto_other;
                if (proto_other) item.setIcon(R.drawable.checked);
                else item.setIcon(R.drawable.check);
                prefs.edit().putBoolean("proto_other", proto_other).apply();
                updateAdapter();
                return true;

            case R.id.menu_traffic_allowed:
                boolean traffic_allowed = prefs.getBoolean("traffic_allowed", true);
                traffic_allowed = !traffic_allowed;
                if (traffic_allowed) {
                    item.setIcon(R.drawable.checked);
                } else item.setIcon(R.drawable.check);
                prefs.edit().putBoolean("filter", traffic_allowed).apply();
                prefs.edit().putBoolean("traffic_allowed", traffic_allowed).apply();
                updateAdapter();
                return true;

            case R.id.menu_traffic_blocked:
                boolean traffic_blocked = prefs.getBoolean("traffic_blocked", true);
                traffic_blocked = !traffic_blocked;
                if (traffic_blocked) item.setIcon(R.drawable.checked);
                else item.setIcon(R.drawable.check);
                prefs.edit().putBoolean("traffic_blocked", traffic_blocked).apply();
                updateAdapter();
                return true;

            case R.id.menu_log_live:
                boolean refresh = prefs.getBoolean("live", true);
                refresh = !refresh;
                live = refresh;
                if (refresh) item.setIcon(R.drawable.checked);
                else item.setIcon(R.drawable.check);
                prefs.edit().putBoolean("live", refresh).apply();
                if (live) {
                    DatabaseHelper.getInstance(this).addLogChangedListener(listener);
                    updateAdapter();
                } else
                    DatabaseHelper.getInstance(this).removeLogChangedListener(listener);
                return true;

            case R.id.menu_refresh:
                updateAdapter();
                return true;

            case R.id.menu_log_resolve:
                boolean resolve = prefs.getBoolean("resolve", false);
                resolve = !resolve;
                if (resolve) item.setIcon(R.drawable.checked);
                else item.setIcon(R.drawable.check);
                prefs.edit().putBoolean("resolve", resolve).apply();
                adapter.setResolve(resolve);
                adapter.notifyDataSetChanged();
                return true;

            case R.id.menu_log_organization:
                boolean organization = prefs.getBoolean("organization", false);
                organization = !organization;
                if (organization) item.setIcon(R.drawable.checked);
                else item.setIcon(R.drawable.check);
                prefs.edit().putBoolean("organization", organization).apply();
                adapter.setOrganization(organization);
                adapter.notifyDataSetChanged();
                return true;

            case R.id.menu_log_clear:
                new AsyncTask<Object, Object, Object>() {
                    @Override
                    protected Object doInBackground(Object... objects) {
                        DatabaseHelper.getInstance(ActivityLog.this).clearLog();
                        if (prefs.getBoolean("pcap", false)) {
                            ServiceSinkhole.setPcap(false, ActivityLog.this);
                            if (pcap_file.exists() && !pcap_file.delete())
                                Log.w(TAG, "Delete PCAP failed");
                            ServiceSinkhole.setPcap(true, ActivityLog.this);
                        } else {
                            if (pcap_file.exists() && !pcap_file.delete())
                                Log.w(TAG, "Delete PCAP failed");
                        }
                        return null;
                    }

                    @Override
                    protected void onPostExecute(Object result) {
                        if (running)
                            updateAdapter();
                    }
                }.execute();
                return true;

            default:
                return super.onOptionsItemSelected(item);
        }
    }

    private void updateAdapter() {
        if (adapter != null) {
            SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
            boolean udp = prefs.getBoolean("proto_udp", true);
            boolean tcp = prefs.getBoolean("proto_tcp", true);
            boolean other = prefs.getBoolean("proto_other", true);
            boolean allowed = prefs.getBoolean("traffic_allowed", true);
            boolean blocked = prefs.getBoolean("traffic_blocked", true);
            adapter.changeCursor(DatabaseHelper.getInstance(this).getLog(udp, tcp, other, allowed, blocked));
            if (menuSearch != null && menuSearch.isActionViewExpanded()) {
                SearchView searchView = (SearchView) MenuItemCompat.getActionView(menuSearch);
                adapter.getFilter().filter(searchView.getQuery().toString());
            }
        }
    }

    private Intent getIntentPCAPDocument() {
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
            intent.setType("application/octet-stream");
            intent.putExtra(Intent.EXTRA_TITLE, "netguard_" + new SimpleDateFormat("yyyyMMdd").format(new Date().getTime()) + ".pcap");
        }
        return intent;
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, final Intent data) {
        Log.i(TAG, "onActivityResult request=" + requestCode + " result=" + requestCode + " ok=" + (resultCode == RESULT_OK));

        if (requestCode == REQUEST_PCAP) {
            if (resultCode == RESULT_OK && data != null)
                handleExportPCAP(data);

        } else {
            Log.w(TAG, "Unknown activity result request=" + requestCode);
            super.onActivityResult(requestCode, resultCode, data);
        }
    }

    private void handleExportPCAP(final Intent data) {
        new AsyncTask<Object, Object, Throwable>() {
            @Override
            protected Throwable doInBackground(Object... objects) {
                OutputStream out = null;
                FileInputStream in = null;
                try {
                    // Stop capture
                    ServiceSinkhole.setPcap(false, ActivityLog.this);

                    Uri target = data.getData();
                    if (data.hasExtra("org.openintents.extra.DIR_PATH"))
                        target = Uri.parse(target + "/netguard.pcap");
                    Log.i(TAG, "Export PCAP URI=" + target);
                    out = getContentResolver().openOutputStream(target);

                    File pcap = new File(getCacheDir(), "netguard.pcap");
                    in = new FileInputStream(pcap);

                    int len;
                    long total = 0;
                    byte[] buf = new byte[4096];
                    while ((len = in.read(buf)) > 0) {
                        out.write(buf, 0, len);
                        total += len;
                    }
                    Log.i(TAG, "Copied bytes=" + total);

                    return null;
                } catch (Throwable ex) {
                    Log.e(TAG, ex.toString() + "\n" + Log.getStackTraceString(ex));
                    Util.sendCrashReport(ex, ActivityLog.this);
                    return ex;
                } finally {
                    if (out != null)
                        try {
                            out.close();
                        } catch (IOException ex) {
                            Log.e(TAG, ex.toString() + "\n" + Log.getStackTraceString(ex));
                        }
                    if (in != null)
                        try {
                            in.close();
                        } catch (IOException ex) {
                            Log.e(TAG, ex.toString() + "\n" + Log.getStackTraceString(ex));
                        }

                    // Resume capture
                    SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(ActivityLog.this);
                    if (prefs.getBoolean("pcap", false))
                        ServiceSinkhole.setPcap(true, ActivityLog.this);
                }
            }

            @Override
            protected void onPostExecute(Throwable ex) {
                if (ex == null)
                    Toast.makeText(ActivityLog.this, R.string.msg_completed, Toast.LENGTH_LONG).show();
                else
                    Toast.makeText(ActivityLog.this, ex.toString(), Toast.LENGTH_LONG).show();
            }
        }.execute();
    }
}
