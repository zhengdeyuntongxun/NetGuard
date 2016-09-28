package com.zhengde163.netguard;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.preference.PreferenceManager;
import android.text.method.LinkMovementMethod;
import android.view.View;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import com.gridsum.tracker.GridsumWebDissector;

public class LoginActivity extends Activity {
    boolean logined;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.login);
        TextView license = (TextView) findViewById(R.id.license);
        license.setMovementMethod(LinkMovementMethod.getInstance());
    }

    public void sign(View v) {
        EditText phoneNumber = (EditText) findViewById(R.id.phoneNumber);
        String a = phoneNumber.getText().toString();
        if (a.length() == 11) {
            Toast.makeText(this, a, Toast.LENGTH_LONG).show();
            SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
            prefs.edit().putBoolean("logined", true).apply();
            prefs.edit().putString("phonenumber", a).apply();
            logined = true;
            GridsumWebDissector.getInstance().trackJunctionPoint(a, 1);
            prefs.edit().putBoolean("initialized", true).apply();
            startActivity(new Intent(LoginActivity.this, ActivityMain.class));
            finish();
        } else Toast.makeText(this, "手机号不正确", Toast.LENGTH_LONG).show();
    }
}
