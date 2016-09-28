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

import android.content.Context;
import android.database.Cursor;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CursorAdapter;
import android.widget.TextView;

import java.text.SimpleDateFormat;

public class AdapterDns extends CursorAdapter {
    private static String TAG = "NetGuard.DNS";

    private int colTime;
    private int colQName;
    private int colAName;
    private int colResource;
    private int colTTL;

    public AdapterDns(Context context, Cursor cursor) {
        super(context, cursor, 0);
        colTime = cursor.getColumnIndex("time");
        colQName = cursor.getColumnIndex("qname");
        colAName = cursor.getColumnIndex("aname");
        colResource = cursor.getColumnIndex("resource");
        colTTL = cursor.getColumnIndex("ttl");
    }

    @Override
    public View newView(Context context, Cursor cursor, ViewGroup parent) {
        return LayoutInflater.from(context).inflate(R.layout.dns, parent, false);
    }

    @Override
    public void bindView(final View view, final Context context, final Cursor cursor) {
        // Get values
        long time = cursor.getLong(colTime);
        String qname = cursor.getString(colQName);
        String aname = cursor.getString(colAName);
        String resource = cursor.getString(colResource);
        int ttl = cursor.getInt(colTTL);

        // Get views
        TextView tvTime = (TextView) view.findViewById(R.id.tvTime);
        TextView tvQName = (TextView) view.findViewById(R.id.tvQName);
        TextView tvAName = (TextView) view.findViewById(R.id.tvAName);
        TextView tvResource = (TextView) view.findViewById(R.id.tvResource);
        TextView tvTTL = (TextView) view.findViewById(R.id.tvTTL);

        // Set values
        tvTime.setText(new SimpleDateFormat("dd HH:mm").format(time));
        tvQName.setText(qname);
        tvAName.setText(aname);
        tvResource.setText(resource);
        tvTTL.setText(Integer.toString(ttl));
    }
}
