package de.kai_morich.simple_usb_terminal;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.AlertDialog;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanResult;
import android.content.Context;
import android.os.Bundle;
import android.os.Handler;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.Button;
import android.widget.Toast;
import android.content.pm.PackageManager;

import androidx.annotation.NonNull;
import androidx.core.app.ActivityCompat;
import androidx.recyclerview.widget.DividerItemDecoration;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

public class BleScannerDialog extends AlertDialog {
    private static final long SCAN_PERIOD = 10000;
    private final BluetoothAdapter bluetoothAdapter;
    private BluetoothLeScanner bluetoothLeScanner;
    private final Handler handler = new Handler();
    private boolean scanning;
    private BleDeviceAdapter deviceAdapter;
    private Button scanButton;

    public interface OnDeviceSelectedListener {
        void onDeviceSelected(BluetoothDevice device);
    }

    private OnDeviceSelectedListener listener;

    public BleScannerDialog(@NonNull Context context) {
        super(context);
        BluetoothManager bluetoothManager = (BluetoothManager) context.getSystemService(Context.BLUETOOTH_SERVICE);
        bluetoothAdapter = bluetoothManager != null ? bluetoothManager.getAdapter() : null;
    }

    public void setOnDeviceSelectedListener(OnDeviceSelectedListener listener) {
        this.listener = listener;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        View view = LayoutInflater.from(getContext()).inflate(R.layout.dialog_ble_scanner, null);
        setView(view);

        RecyclerView recyclerView = view.findViewById(R.id.bleDevicesRecyclerView);
        recyclerView.setLayoutManager(new LinearLayoutManager(getContext()));
        recyclerView.addItemDecoration(new DividerItemDecoration(getContext(), DividerItemDecoration.VERTICAL));

        deviceAdapter = new BleDeviceAdapter();
        deviceAdapter.setOnDeviceClickListener(device -> {
            if (listener != null) {
                listener.onDeviceSelected(device);
            }
            dismiss();
        });
        recyclerView.setAdapter(deviceAdapter);

        scanButton = view.findViewById(R.id.scanButton);
        scanButton.setOnClickListener(v -> {
            if (scanning) {
                stopScan();
                scanButton.setText("Сканировать");
            } else {
                startScan();
                scanButton.setText("Остановить");
            }
        });

        setOnDismissListener(dialog -> stopScan());
        super.onCreate(savedInstanceState);
    }

    @SuppressLint("MissingPermission")
    private void startScan() {
        if (bluetoothAdapter == null || !bluetoothAdapter.isEnabled()) {
            Toast.makeText(getContext(), "Bluetooth выключен", Toast.LENGTH_SHORT).show();
            return;
        }

        if (ActivityCompat.checkSelfPermission(getContext(), Manifest.permission.BLUETOOTH_SCAN)
                != PackageManager.PERMISSION_GRANTED) {
            return;
        }

        if (bluetoothLeScanner == null) {
            bluetoothLeScanner = bluetoothAdapter.getBluetoothLeScanner();
        }

        deviceAdapter.clearDevices();
        scanning = true;
        handler.postDelayed(this::stopScan, SCAN_PERIOD);
        bluetoothLeScanner.startScan(scanCallback);
    }

    @SuppressLint("MissingPermission")
    private void stopScan() {
        if (!scanning || bluetoothLeScanner == null) {
            return;
        }
        scanning = false;
        bluetoothLeScanner.stopScan(scanCallback);
    }

    private final ScanCallback scanCallback = new ScanCallback() {
        @Override
        public void onScanResult(int callbackType, ScanResult result) {
            super.onScanResult(callbackType, result);
            if (result.getDevice() != null) {
                deviceAdapter.addDevice(result.getDevice());
            }
        }

        @Override
        public void onScanFailed(int errorCode) {
            super.onScanFailed(errorCode);
            scanning = false;
            Toast.makeText(getContext(),
                    "Ошибка сканирования: " + errorCode, Toast.LENGTH_SHORT).show();
        }
    };
}