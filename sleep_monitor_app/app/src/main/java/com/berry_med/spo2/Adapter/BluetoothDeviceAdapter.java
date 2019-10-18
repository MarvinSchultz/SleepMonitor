package com.berry_med.spo2.Adapter;

import android.bluetooth.BluetoothDevice;
import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.LinearLayout;
import android.widget.TextView;
//import com.berry_med.oxycare.R;
import java.util.ArrayList;
import java.util.HashMap;

public class BluetoothDeviceAdapter extends BaseAdapter {
    private ArrayList<BluetoothDevice> mDevices;
    private LayoutInflater mInflater;
    private HashMap<String, Integer> mRssiMap;

    public BluetoothDeviceAdapter(Context context, ArrayList<BluetoothDevice> devices, HashMap<String, Integer> rssimap) {
        this.mInflater = LayoutInflater.from(context);
        this.mDevices = devices;
        this.mRssiMap = rssimap;
    }

    public int getCount() {
        return this.mDevices.size();
    }

    public Object getItem(int position) {
        return this.mDevices.get(position);
    }

    public long getItemId(int position) {
        return 0;
    }

    public View getView(int position, View convertView, ViewGroup parent) {
        return (LinearLayout) convertView;
        /*

        BluetoothDevice dev = (BluetoothDevice) this.mDevices.get(position);
        if (convertView != null) {
            llItem = (LinearLayout) convertView;
        } else {
            llItem = (LinearLayout) this.mInflater.inflate(R.layout.bluetooth_item, null);
        }
        TextView tvAddr = (TextView) llItem.findViewById(R.id.tvBtItemAddr);
        ((TextView) llItem.findViewById(R.id.tvBtItemName)).setText(dev.getName());
        tvAddr.setText("MAC: " + dev.getAddress() + "      RSSI: " + this.mRssiMap.get(dev.getAddress()));
        return llItem;*/
    }
}
