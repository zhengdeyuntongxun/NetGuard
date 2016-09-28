package com.zhengde163.netguard;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.preference.PreferenceManager;
import android.telephony.TelephonyManager;
import android.view.View;
import android.widget.EditText;
import android.widget.Toast;

import com.gridsum.tracker.GridsumWebDissector;

import java.security.MessageDigest;

public class LoginAgain extends Activity {

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.loginagain);
    }

    public void login(View view) {
        EditText phoneNumber = (EditText) findViewById(R.id.phoneNumber);
        String a = phoneNumber.getText().toString();
        if (a.length() == 11) {
            Toast.makeText(this, a, Toast.LENGTH_LONG).show();
            SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
            prefs.edit().remove("phonenumber");
            prefs.edit().putString("phonenumber", a).apply();
            GridsumWebDissector.getInstance().trackJunctionPoint(a, 1);
            finish();
        } else Toast.makeText(this, "手机号不正确", Toast.LENGTH_LONG).show();
    }

}
