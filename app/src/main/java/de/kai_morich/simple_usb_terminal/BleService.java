package de.kai_morich.simple_usb_terminal;

import android.Manifest;
import android.annotation.SuppressLint;
import android.bluetooth.*;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

public class BleService {
    private static final String TAG = "BLE_SERVICE";

    // UUID сервиса и характеристик (должны совпадать с вашим BLE устройством)
    private static final UUID SERVICE_UUID = UUID.fromString("6e400001-b5a3-f393-e0a9-e50e24dcca9e");
    private static final UUID TX_CHARACTERISTIC_UUID = UUID.fromString("6e400003-b5a3-f393-e0a9-e50e24dcca9e");
    private static final UUID RX_CHARACTERISTIC_UUID = UUID.fromString("6e400002-b5a3-f393-e0a9-e50e24dcca9e");
    private static final UUID DESCRIPTOR_UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");

    private final Context context;
    private BluetoothAdapter bluetoothAdapter;
    private BluetoothGatt bluetoothGatt;
    private BluetoothGattCharacteristic txCharacteristic;
    private BluetoothGattCharacteristic rxCharacteristic;
    private final BleCallback callback;
    private final Handler handler;
    private boolean isConnected = false;
    private boolean isInitialized = false; // Флаг полной инициализации
    private int mtuSize = 23; // Стандартный размер MTU

    public interface BleCallback {
        void onDeviceConnected(String deviceName);
        void onDeviceDisconnected();
        void onDataReceived(String data);
        void onError(String error);
    }

    public BleService(Context context, BleCallback callback) {
        this.context = context;
        this.callback = callback;
        this.handler = new Handler(Looper.getMainLooper());
        initBluetooth();
    }

    private void initBluetooth() {
        try {
            BluetoothManager bluetoothManager = (BluetoothManager) context.getSystemService(Context.BLUETOOTH_SERVICE);
            if (bluetoothManager != null) {
                bluetoothAdapter = bluetoothManager.getAdapter();
            }
        } catch (Exception e) {
            Log.e(TAG, "Bluetooth init error", e);
            callback.onError("Bluetooth initialization failed");
        }
    }

    @SuppressLint("MissingPermission")
    public void connectToDevice(BluetoothDevice device) {
        if (!hasRequiredPermissions()) {
            callback.onError("Bluetooth permissions required");
            return;
        }

        try {
            if (bluetoothAdapter == null || !bluetoothAdapter.isEnabled()) {
                callback.onError("Bluetooth is disabled");
                return;
            }

            // Закрываем предыдущее подключение
            disconnect();

            Log.d(TAG, "Connecting to device: " + device.getAddress());
            bluetoothGatt = device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE);
            isConnected = false;
            isInitialized = false;

            // Таймаут подключения
            handler.postDelayed(() -> {
                if (!isConnected && bluetoothGatt != null) {
                    Log.e(TAG, "Connection timeout");
                    callback.onError("Connection timeout");
                    disconnect();
                }
            }, 10000); // 10 секунд таймаут
        } catch (SecurityException e) {
            Log.e(TAG, "Security exception", e);
            callback.onError("Security error occurred");
        } catch (Exception e) {
            Log.e(TAG, "Connection error", e);
            callback.onError("Connection failed");
        }
    }

    public void disconnect() {
        try {
            if (bluetoothGatt != null) {
                Log.d(TAG, "Disconnecting...");
                if (ActivityCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED) {
                    bluetoothGatt.disconnect();
                }
                bluetoothGatt.close();
                bluetoothGatt = null;
            }
            isConnected = false;
            isInitialized = false;
            txCharacteristic = null;
            rxCharacteristic = null;
        } catch (Exception e) {
            Log.e(TAG, "Disconnect error", e);
        }
    }

    @SuppressLint("MissingPermission")
    public boolean sendData(String data) {
        if (!hasRequiredPermissions()) {
            callback.onError("Bluetooth permissions required");
            return false;
        }

        if (!isConnected || !isInitialized || rxCharacteristic == null || bluetoothGatt == null) {
            Log.e(TAG, "Cannot send data - device not ready. Connected: " + isConnected +
                    ", Initialized: " + isInitialized +
                    ", RX Char: " + (rxCharacteristic != null) +
                    ", GATT: " + (bluetoothGatt != null));
            callback.onError("Device not ready");
            return false;
        }

        try {
            byte[] bytes = data.getBytes(StandardCharsets.UTF_8);
            rxCharacteristic.setValue(bytes);

            // Используем WRITE_TYPE_DEFAULT для надежности
            rxCharacteristic.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT);

            boolean result = bluetoothGatt.writeCharacteristic(rxCharacteristic);
            Log.d(TAG, "Data sent: " + data + ", result: " + result);

            if (!result) {
                callback.onError("Failed to write characteristic");
            }
            return result;
        } catch (Exception e) {
            Log.e(TAG, "Send failed", e);
            callback.onError("Send failed: " + e.getMessage());
            return false;
        }
    }

    public boolean isConnected() {
        return isConnected;
    }

    @SuppressLint("MissingPermission")
    public boolean sendCommand(String command) {
        if (!isConnected || !isInitialized || rxCharacteristic == null || bluetoothGatt == null) {
            Log.e(TAG, "Cannot send command - device not ready");
            return false;
        }

        try {
            // Добавляем символ новой строки в конце команды
            String formattedCommand = command.endsWith(";;") ? command : command + ";;";
            formattedCommand += "\n"; // Добавляем символ новой строки

            byte[] bytes = formattedCommand.getBytes(StandardCharsets.UTF_8);
            rxCharacteristic.setValue(bytes);
            rxCharacteristic.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT);

            boolean result = bluetoothGatt.writeCharacteristic(rxCharacteristic);
            Log.d(TAG, "Command sent: " + formattedCommand.trim() + ", result: " + result);

            return result;
        } catch (Exception e) {
            Log.e(TAG, "Command send failed", e);
            return false;
        }
    }

    public boolean isInitialized() {
        return isInitialized;
    }

    private boolean hasRequiredPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            return ActivityCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED &&
                    ActivityCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED;
        } else {
            return ActivityCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
        }
    }

    private final BluetoothGattCallback gattCallback = new BluetoothGattCallback() {
        @Override
        public void onConnectionStateChange(BluetoothGatt gatt, int status, int newState) {
            Log.d(TAG, "onConnectionStateChange: status=" + status + ", newState=" + newState);

            if (newState == BluetoothProfile.STATE_CONNECTED) {
                isConnected = true;
                isInitialized = false;

                handler.post(() -> {
                    try {
                        if (ActivityCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED) {
                            String deviceName = gatt.getDevice().getName();
                            Log.d(TAG, "Connected to: " + deviceName);
                            callback.onDeviceConnected(deviceName != null ? deviceName : "Unknown");

                            // Запрашиваем увеличенный MTU для лучшей производительности
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                                if (!gatt.requestMtu(247)) {
                                    Log.e(TAG, "Failed to request MTU");
                                    // Если не удалось, продолжаем с обнаружением сервисов
                                    gatt.discoverServices();
                                }
                            } else {
                                gatt.discoverServices();
                            }
                        }
                    } catch (Exception e) {
                        Log.e(TAG, "Connection error", e);
                        callback.onError("Connection error");
                    }
                });
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                isConnected = false;
                isInitialized = false;
                handler.post(() -> {
                    Log.d(TAG, "Disconnected");
                    callback.onDeviceDisconnected();
                    disconnect();
                });
            }
        }

        @Override
        public void onMtuChanged(BluetoothGatt gatt, int mtu, int status) {
            Log.d(TAG, "onMtuChanged: mtu=" + mtu + ", status=" + status);

            try {
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    mtuSize = mtu;
                    Log.d(TAG, "MTU changed to: " + mtu);
                } else {
                    Log.e(TAG, "MTU change failed with status: " + status);
                }

                // Check for Bluetooth permissions before making the call
                if (ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT)
                        != PackageManager.PERMISSION_GRANTED) {
                    // Permission not granted, handle this case
                    callback.onError("Bluetooth permission not granted");
                    return;
                }

                // В любом случае продолжаем с обнаружением сервисов
                if (!gatt.discoverServices()) {
                    callback.onError("Failed to start service discovery");
                }
            } catch (SecurityException e) {
                Log.e(TAG, "SecurityException in onMtuChanged: " + e.getMessage());
                callback.onError("Bluetooth operation not permitted");
            }
        }

        @Override
        public void onServicesDiscovered(BluetoothGatt gatt, int status) {
            Log.d(TAG, "onServicesDiscovered: status=" + status);

            if (status == BluetoothGatt.GATT_SUCCESS) {
                try {
                    BluetoothGattService service = gatt.getService(SERVICE_UUID);
                    if (service != null) {
                        txCharacteristic = service.getCharacteristic(TX_CHARACTERISTIC_UUID);
                        rxCharacteristic = service.getCharacteristic(RX_CHARACTERISTIC_UUID);

                        if (txCharacteristic != null && rxCharacteristic != null) {
                            Log.d(TAG, "Found required characteristics");

                            // Включаем уведомления для TX характеристики
                            enableCharacteristicNotification(gatt, txCharacteristic);

                            // Устанавливаем флаг полной инициализации
                            isInitialized = true;
                            Log.d(TAG, "BLE service initialized successfully");
                        } else {
                            String error = "Missing characteristics: " +
                                    (txCharacteristic == null ? "TX " : "") +
                                    (rxCharacteristic == null ? "RX " : "");
                            Log.e(TAG, error);
                            callback.onError(error);
                        }
                    } else {
                        callback.onError("Service not found");
                    }
                } catch (Exception e) {
                    Log.e(TAG, "Service discovery error", e);
                    callback.onError("Service discovery error");
                }
            } else {
                callback.onError("Service discovery failed");
            }
        }

        @Override
        public void onCharacteristicChanged(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic) {
            try {
                byte[] data = characteristic.getValue();
                if (data != null && data.length > 0) {
                    String message = new String(data, StandardCharsets.UTF_8);
                    Log.d(TAG, "Received data: " + message);
                    handler.post(() -> callback.onDataReceived(message));
                }
            } catch (Exception e) {
                Log.e(TAG, "Data processing error", e);
            }
        }

        @Override
        public void onCharacteristicWrite(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic, int status) {
            Log.d(TAG, "onCharacteristicWrite: status=" + status);

            if (status != BluetoothGatt.GATT_SUCCESS) {
                handler.post(() -> callback.onError("Write failed with status: " + status));
            }
        }

        @SuppressLint("MissingPermission")
        private void enableCharacteristicNotification(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic) {
            if (!gatt.setCharacteristicNotification(characteristic, true)) {
                callback.onError("Failed to enable notifications");
                return;
            }

            BluetoothGattDescriptor descriptor = characteristic.getDescriptor(DESCRIPTOR_UUID);
            if (descriptor != null) {
                descriptor.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);

                handler.postDelayed(() -> {
                    try {
                        if (ActivityCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT)
                                == PackageManager.PERMISSION_GRANTED) {
                            if (!gatt.writeDescriptor(descriptor)) {
                                callback.onError("Failed to write descriptor");
                            }
                        }
                    } catch (SecurityException e) {
                        callback.onError("Security error: " + e.getMessage());
                    }
                }, 200);
            } else {
                callback.onError("Descriptor not found");
            }
        }
    };
}