package de.kai_morich.simple_usb_terminal;

import android.annotation.SuppressLint;
import android.app.PendingIntent;
import android.bluetooth.BluetoothManager;
import android.bluetooth.le.ScanSettings;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.text.method.ScrollingMovementMethod;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.EditText;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import android.Manifest;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.le.ScanFilter;
import android.os.ParcelUuid;

import com.hoho.android.usbserial.driver.UsbSerialDriver;
import com.hoho.android.usbserial.driver.UsbSerialPort;
import com.hoho.android.usbserial.driver.UsbSerialProber;
import com.hoho.android.usbserial.util.SerialInputOutputManager;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanResult;
import android.widget.ArrayAdapter;
import android.widget.ListView;

public class MainActivity extends AppCompatActivity {

    private static final String ACTION_USB_PERMISSION = "de.kai_morich.simple_usb_terminal.USB_PERMISSION";
    private static final int REQUEST_CODE_SELECT_CONTACT = 1001;


    private BluetoothAdapter bluetoothAdapter;

    private static final int REQUEST_BLE_PERMISSIONS = 1;
    private static final int REQUEST_ENABLE_BT = 2;
    private BluetoothLeScanner bluetoothLeScanner;
    private boolean isScanning = false;
    private BleDeviceAdapter bleDeviceAdapter;
    private final List<BluetoothDevice> bleDevices = new ArrayList<>();

    private boolean isBleScanDialogShown = false; // Добавьте это в начало класса с другими полями
    private RecyclerView chatRecyclerView;
    private ChatAdapter chatAdapter;
    private List<Message> groupChatMessages;
    private Map<Contact, List<Message>> privateChatMessages;
    private String userName;
    private EditText userNameInput;
    private TextView chatStatusTextView;

    private UsbSerialPort usbSerialPort;
    private SerialInputOutputManager usbIoManager;

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private StringBuilder incomingDataBuffer = new StringBuilder();

    private boolean isPrivateChat = false;
    private Contact selectedContact;

    private BleService bleService;
    private boolean isBleConnected = false;

    private ScanCallback scanCallback;
    private static final String[] BLE_PERMISSIONS;
    static {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            BLE_PERMISSIONS = new String[]{
                    Manifest.permission.BLUETOOTH_SCAN,
                    Manifest.permission.BLUETOOTH_CONNECT,
                    Manifest.permission.ACCESS_FINE_LOCATION
            };
        } else {
            BLE_PERMISSIONS = new String[]{
                    Manifest.permission.ACCESS_FINE_LOCATION
            };
        }
    }

    private Map<String, MessageStatus> messageStatusMap = new ConcurrentHashMap<>();
    private Map<String, Long> remoteLoraOffConfirmations = new ConcurrentHashMap<>();
    private static final long REMOTE_LORA_OFF_CONFIRMATION_TIMEOUT = 2000; // 2 секунды

    private final BroadcastReceiver usbPermissionReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (ACTION_USB_PERMISSION.equals(action)) {
                synchronized (this) {
                    UsbDevice usbDevice = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
                    if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) {
                        if (usbDevice != null) {
                            initUsbConnection();
                        }
                    } else {
                        Log.e("zxc", "Разрешение на использование USB-устройства не предоставлено");
                        runOnUiThread(() -> Toast.makeText(MainActivity.this, "Разрешение на использование USB-устройства не предоставлено", Toast.LENGTH_SHORT).show());
                    }
                }
            }
        }
    };

    private final BroadcastReceiver bluetoothStateReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            final String action = intent.getAction();

            if (BluetoothAdapter.ACTION_STATE_CHANGED.equals(action)) {
                final int state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE,
                        BluetoothAdapter.ERROR);
                handleBluetoothStateChange(state);
            }
        }
    };

    private void disconnectBleDevice() {
        if (bleService != null) {
            bleService.disconnect();
            bleService = null;
        }
        isBleConnected = false;
        updateBleConnectionStatus(false);
    }

    private void updateBleConnectionStatus(boolean connected, String status) {
        isBleConnected = connected;
        // Обновить UI (например, TextView или статус в ActionBar)
        if (chatStatusTextView != null) {
            chatStatusTextView.setText("BLE: " + status);
        }
    }

    private void updateBleConnectionStatus(boolean connected) {
        updateBleConnectionStatus(connected, connected ? "Подключено" : "Отключено");
    }

    private void handleBluetoothStateChange(int state) {
        switch (state) {
            case BluetoothAdapter.STATE_OFF:
                // Bluetooth выключен
                stopBleScan();
                disconnectBleDevice();
                runOnUiThread(() -> {
                    Toast.makeText(this, "Bluetooth выключен", Toast.LENGTH_SHORT).show();
                    updateBleConnectionStatus(false);
                });
                break;

            case BluetoothAdapter.STATE_ON:
                // Bluetooth включен
                runOnUiThread(() -> {
                    Toast.makeText(this, "Bluetooth включен", Toast.LENGTH_SHORT).show();
                    // Можно автоматически перезапустить сканирование
                    if (isBleScanDialogShown) {
                        startBleScan();
                    }
                });
                break;

            case BluetoothAdapter.STATE_TURNING_OFF:
            case BluetoothAdapter.STATE_TURNING_ON:
                // Промежуточные состояния
                break;
        }
    }


    private boolean isBluetoothEnabled() {
        BluetoothManager bluetoothManager = (BluetoothManager) getSystemService(Context.BLUETOOTH_SERVICE);
        if (bluetoothManager == null) return false;

        bluetoothAdapter = bluetoothManager.getAdapter();
        return bluetoothAdapter != null && bluetoothAdapter.isEnabled();
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // Проверка поддержки BLE
        if (!getPackageManager().hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE)) {
            Toast.makeText(this, "BLE не поддерживается на этом устройстве", Toast.LENGTH_LONG).show();
            finish();
        }

        // Инициализация Bluetooth (но не запуск сканирования)
        BluetoothManager bluetoothManager = (BluetoothManager) getSystemService(Context.BLUETOOTH_SERVICE);
        if (bluetoothManager != null) {
            bluetoothAdapter = bluetoothManager.getAdapter();
        }

        // Инициализация адаптера
        bleDeviceAdapter = new BleDeviceAdapter();
        bleDeviceAdapter.setOnDeviceClickListener(device -> {
            if (bleService == null) {
                bleService = new BleService(MainActivity.this, bleCallback);
            }
            bleService.connectToDevice(device);
        });

        // Проверка разрешений только при первом запуске
        if (savedInstanceState == null) {
            checkAndRequestPermissions();
        }

        // Сбрасываем флаг проверки пароля при каждом новом запуске
        getSharedPreferences("user", MODE_PRIVATE)
                .edit()
                .putBoolean("password_checked", false)
                .apply();

        chatStatusTextView = findViewById(R.id.chatStatusTextView);
        IntentFilter filter = new IntentFilter(ACTION_USB_PERMISSION);
        registerReceiver(usbPermissionReceiver, filter, Context.RECEIVER_EXPORTED);

        showPasswordDialog();

        userNameInput = findViewById(R.id.userNameInput);
        try {
            userName = getUserNameFromPreferences();
            userNameInput.setText(userName);
        } catch (Exception e) {
            Log.e("zxc", "Ошибка загрузки имени пользователя", e);
            Toast.makeText(this, "Ошибка загрузки имени пользователя", Toast.LENGTH_SHORT).show();
            userName = "User";
        }

        userNameInput.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                userName = s.toString();
                try {
                    getSharedPreferences("user", MODE_PRIVATE)
                            .edit()
                            .putString("nickname", userName)
                            .apply();
                } catch (Exception e) {
                    Log.e("zxc", "Ошибка сохранения имени пользователя", e);
                    Toast.makeText(MainActivity.this, "Ошибка сохранения имени пользователя", Toast.LENGTH_SHORT).show();
                }
            }

            @Override
            public void afterTextChanged(Editable s) {}
        });

        chatRecyclerView = findViewById(R.id.chatRecyclerView);
        chatRecyclerView.setLayoutManager(new LinearLayoutManager(this));

        groupChatMessages = new ArrayList<>();
        privateChatMessages = new HashMap<>();
        chatAdapter = new ChatAdapter(groupChatMessages);
        chatRecyclerView.setAdapter(chatAdapter);

        final EditText messageInput = findViewById(R.id.messageInput);
        Button sendButton = findViewById(R.id.sendButton);

        sendButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                String message = messageInput.getText().toString();
                if (!message.isEmpty()) {
                    if (message.length() <= 86) {
                        sendMessageToChat(userName, message);
                        if (isPrivateChat && selectedContact != null) {
                            sendCommandInBackground("PM;ADDR_H:" + selectedContact.getHighByte() + ";ADDR_L:" + selectedContact.getLowByte() + ";NIC:" + userName + ";MESSAGE:" + message + ";;");
                        } else {
                            sendCommandInBackground("BROADCAST;NIC:" + userName + ";MESSAGE:" + message + ";;");
                        }
                        messageInput.setText("");
                    } else {
                        Toast.makeText(MainActivity.this, "Сообщение слишком длинное (макс. 86 символов)", Toast.LENGTH_SHORT).show();
                    }
                }
            }
        });

        initUsbConnection();
        sendAutoMessage();
        updateChatStatus();
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.menu_main, menu);
        return true;
    }

    private enum MessageStatus {
        SENT, DELIVERED, FAILED
    }

    private void checkPasswordOnConnection(String connectionType, Runnable onSuccess) {
        SharedPreferences prefs = getSharedPreferences("user", MODE_PRIVATE);

        if (prefs.getBoolean("password_checked", false)) {
            if (connectionType.equals("Bluetooth")) {
                onBleConnectedAfterPassword(); // Добавляем вызов для BLE
            }
            onSuccess.run();
            return;
        }

        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("Подключение по " + connectionType);
        builder.setMessage("Для продолжения работы введите пароль");
        builder.setCancelable(false);

        final EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_VARIATION_PASSWORD);
        builder.setView(input);

        builder.setPositiveButton("OK", (dialog, which) -> {
            String enteredPassword = input.getText().toString();
            if (enteredPassword.equals(getPassword())) {
                prefs.edit().putBoolean("password_checked", true).apply();
                if (connectionType.equals("Bluetooth")) {
                    onBleConnectedAfterPassword(); // Добавляем вызов для BLE
                }
                onSuccess.run();
            } else {
                Toast.makeText(this, "Неверный пароль", Toast.LENGTH_SHORT).show();
                if (connectionType.equals("Bluetooth")) {
                    disconnectBle();
                } else {
                    disconnectUsb();
                }
            }
        });

        builder.setNegativeButton("Отмена", (dialog, which) -> {
            if (connectionType.equals("Bluetooth")) {
                disconnectBle();
            } else {
                disconnectUsb();
            }
        });

        builder.show();
    }


    private void disconnectUsb() {
        try {
            if (usbIoManager != null) {
                usbIoManager.stop();
                usbIoManager = null;
            }
            if (usbSerialPort != null) {
                usbSerialPort.close();
                usbSerialPort = null;
            }
            runOnUiThread(() -> Toast.makeText(this, "USB соединение закрыто", Toast.LENGTH_SHORT).show());
        } catch (IOException e) {
            Log.e("zxc", "Ошибка при отключении USB", e);
        }
    }



    private void checkAndRequestPermissions() {
        if (checkBlePermissions()) {
            // Все разрешения уже есть, проверяем Bluetooth
            setupBluetooth();
        } else {
            // Проверяем, нужно ли сначала показать объяснение
            boolean shouldShowRationale = false;
            for (String permission : BLE_PERMISSIONS) {
                if (ActivityCompat.shouldShowRequestPermissionRationale(this, permission)) {
                    shouldShowRationale = true;
                    break;
                }
            }

            if (shouldShowRationale) {
                // Показываем диалог с объяснением
                showPermissionExplanationDialog();
            } else {
                // Запрашиваем разрешения сразу
                ActivityCompat.requestPermissions(
                        this,
                        BLE_PERMISSIONS,
                        REQUEST_BLE_PERMISSIONS
                );
            }
        }
    }


    private void showPermissionExplanationDialog() {
        new AlertDialog.Builder(this)
                .setTitle("Необходимы разрешения")
                .setMessage("Для работы с Bluetooth приложению нужны следующие разрешения:\n\n" +
                        "- Доступ к Bluetooth (для поиска устройств)\n" +
                        "- Доступ к местоположению (требуется для работы Bluetooth на Android 6+)\n\n" +
                        "Мы не используем и не сохраняем ваше местоположение.")
                .setPositiveButton("Продолжить", (dialog, which) -> {
                    // Закрываем текущий диалог перед запросом разрешений
                    dialog.dismiss();
                    // Непосредственно запрашиваем разрешения
                    ActivityCompat.requestPermissions(
                            MainActivity.this,
                            BLE_PERMISSIONS,
                            REQUEST_BLE_PERMISSIONS
                    );
                })
                .setNegativeButton("Отмена", (dialog, which) -> {
                    dialog.dismiss();
                    Toast.makeText(MainActivity.this,
                            "Некоторые функции приложения будут недоступны",
                            Toast.LENGTH_SHORT).show();
                })
                .setCancelable(false)
                .show();
    }


    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        int id = item.getItemId();

        if (id == R.id.action_change_lora_speed) {
            showLoraSpeedDialog();
            return true;
        } else if (id == R.id.action_change_password) {
            showChangePasswordDialog();
            return true;
        } else if (id == R.id.action_switch_chat) {
            isPrivateChat = !isPrivateChat;
            if (isPrivateChat) {
                Intent intent = new Intent(this, ContactsActivity.class);
                startActivityForResult(intent, REQUEST_CODE_SELECT_CONTACT);
                Log.d("zxc", "Переключение в личный чат: запуск ContactsActivity");
            } else {
                selectedContact = null;
                updateChatStatus();
                Toast.makeText(this, "Переключено в групповой чат", Toast.LENGTH_SHORT).show();
                Log.d("zxc", "Переключение в групповой чат: сброс контакта");
            }
            return true;
        } else if (id == R.id.action_add_contact) {
            startActivity(new Intent(this, AddContactActivity.class));
            return true;
        } else if (id == R.id.action_instruction) {
            showInstructionDialog();
            return true;
        } else if (id == R.id.action_ble_connect) {
            showBleScannerDialog();
            return true;
        } else if (id == R.id.action_ble_disconnect) {
            disconnectBle();
            return true;
        }

        return super.onOptionsItemSelected(item);
    }

    @SuppressLint("MissingPermission")
    private void disconnectBle() {
        if (bleService != null) {
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT)
                    == PackageManager.PERMISSION_GRANTED) {
                bleService.disconnect();
                bleService = null;
                isBleConnected = false;
                updateBleConnectionStatus(false);
                Toast.makeText(MainActivity.this, "BLE соединение отключено", Toast.LENGTH_SHORT).show();
            } else {
                requestBlePermissions();
            }
        }
    }

    private boolean hasBlePermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            return ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED &&
                    ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED;
        } else {
            return ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
        }
    }
    private void onBleConnectedAfterPassword() {
        if (isBleConnected && bleService != null) {
            new Handler(Looper.getMainLooper()).postDelayed(() -> {
                sendCommandInBackground("GETCONFIG;;");
                Log.d("BLE", "Запрос конфигурации отправлен после проверки пароля");
            }, 1000);
        }
    }

    private void connectToBleDevice(BluetoothDevice device) {
        if (!hasBlePermissions()) {
            requestBlePermissions();
            return;
        }

        if (bleService != null && bleService.isConnected()) {
            disconnectBle();
        }

        bleService = new BleService(this, new BleService.BleCallback() {
            @Override
            public void onDeviceConnected(String deviceName) {
                runOnUiThread(() -> {
                    isBleConnected = true;
                    updateBleConnectionStatus(true, deviceName);
                    startCommunicationAfterConnect();
                });
            }

            @Override
            public void onDeviceDisconnected() {
                runOnUiThread(() -> {
                    isBleConnected = false;
                    updateBleConnectionStatus(false);
                    invalidateOptionsMenu();
                });
            }

            @Override
            public void onDataReceived(String data) {
                parseIncomingMessage(data);
            }

            @Override
            public void onError(String error) {
                runOnUiThread(() -> {
                    if (!error.contains("Characteristic")) {
                        Toast.makeText(MainActivity.this,
                                "BLE: " + error, Toast.LENGTH_SHORT).show();
                    }

                    if (error.contains("отключ") || error.contains("не готов")) {
                        isBleConnected = false;
                        updateBleConnectionStatus(false);
                        invalidateOptionsMenu();
                    }
                });
            }
        });

        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT)
                == PackageManager.PERMISSION_GRANTED) {
            bleService.connectToDevice(device);
        }
    }

    private void waitAndSendConfig() {
        // Ждем полной инициализации BLE соединения
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            if (isBleConnected && bleService != null) {
                sendCommandInBackground("GETCONFIG;;");
                Log.d("BLE", "GETCONFIG отправлен после подключения");
            } else {
                Log.e("BLE", "Не удалось отправить GETCONFIG - соединение не готово");
            }
        }, 1500); // Увеличиваем задержку для надежности
    }

    private void showPasswordDialogForBle(Runnable onSuccess) {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("Подключение по Bluetooth");
        builder.setMessage("Введите пароль (по умолчанию: 000000)");
        builder.setCancelable(false);

        final EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_VARIATION_PASSWORD);
        builder.setView(input);

        builder.setPositiveButton("OK", (dialog, which) -> {
            String enteredPassword = input.getText().toString();
            if (enteredPassword.isEmpty()) {
                enteredPassword = "000000"; // Пароль по умолчанию
            }

            // Формируем команду согласно протоколу
            String passwordCommand = "PASSWORD;PASS:" + enteredPassword + ";;";

            if (bleService != null && bleService.sendCommand(passwordCommand)) {
                // Ждем ответа от устройства в parseIncomingMessage()
                new Handler(Looper.getMainLooper()).postDelayed(() -> {
                    sendCommandInBackground("GETCONFIG;;");
                    onSuccess.run();
                }, 1500); // Задержка согласно протоколу
            } else {
                Toast.makeText(this, "Ошибка отправки пароля", Toast.LENGTH_SHORT).show();
            }
        });

        builder.setNegativeButton("Отмена", (dialog, which) -> {
            disconnectBle();
        });

        builder.show();
    }

    @Override
    public boolean onPrepareOptionsMenu(Menu menu) {
        MenuItem bleDisconnectItem = menu.findItem(R.id.action_ble_disconnect);
        if (bleDisconnectItem != null) {
            bleDisconnectItem.setVisible(isBleConnected);
        }
        return super.onPrepareOptionsMenu(menu);
    }

    private void showInstructionDialog() {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("Полная инструкция по использованию приложения");

        String instructionText = "1. ОСНОВНЫЕ ВОЗМОЖНОСТИ:\n" +
                "   • Общий чат - сообщения видны всем в сети LoRa\n" +
                "   • Личные сообщения - защищенная переписка с контактами\n" +
                "   • Управление модулем - настройка параметров LoRa\n\n" +

                "2. РАБОТА С ЧАТОМ:\n" +
                "   • Максимальная длина сообщения: 86 символов\n" +
                "   • Переключение между общим/личным чатом через меню\n" +
                "   • В личном чате выберите контакт из списка\n\n" +

                "3. НАСТРОЙКИ LoRa МОДУЛЯ:\n" +
                "   • Скорость передачи: 1200-115200 бит/с\n" +
                "   • Пароль: 6 цифр (по умолчанию 000000)\n" +
                "   • Адрес модуля: отображается в конфигурации\n" +
                "   • Канал и мощность: настраиваются удаленно\n\n" +

                "4. УПРАВЛЕНИЕ КОНТАКТАМИ:\n" +
                "   • Добавление: укажите имя и адрес (H:L)\n" +
                "   • Блокировка: через меню удаленного управления\n" +
                "   • Просмотр: список доступен в разделе контактов\n\n" +

                "5. БЕЗОПАСНОСТЬ:\n" +
                "   • Все сообщения шифруются модулем LoRa\n" +
                "   • Пароль защищает от несанкционированного доступа\n" +
                "   • Для сброса пароля требуется физический доступ к модулю\n\n" +

                "6. АВАРИЙНЫЕ РЕЖИМЫ:\n" +
                "   • Тревожные сообщения: начинаются с ALARM;\n" +
                "   • Удаленное отключение: команда REMOTELORAOFF\n" +
                "   • Принудительный сброс: удерживайте кнопку на модуле 10 сек\n\n" +

                "7. ПРОБЛЕМЫ И РЕШЕНИЯ:\n" +
                "   • Нет связи: проверьте подключение и питание\n" +
                "   • Ошибка пароля: используйте 000000 или сбросьте\n" +
                "   • Не отвечает: перезагрузите модуль и приложение\n\n" +

                "8. ТЕХНИЧЕСКАЯ ИНФОРМАЦИЯ:\n" +
                "   • Протокол: ASCII-команды с разделителями ;;\n" +
                "   • Формат адреса: HEX (например, 1A:2F)\n" +
                "   • Поддержка: работает с модулями RAK4631/RAK3172";

        builder.setMessage(instructionText);
        builder.setPositiveButton("Закрыть", (dialog, which) -> dialog.dismiss());

        // Добавляем кнопку "Подробнее" с ссылкой на документацию
        builder.setNeutralButton("Подробнее", (dialog, which) -> {
            Intent browserIntent = new Intent(Intent.ACTION_VIEW,
                    Uri.parse("https://github.com/KaiMorich/simple-usb-terminal/wiki"));
            startActivity(browserIntent);
        });

        AlertDialog dialog = builder.create();
        dialog.show();

        // Настройка прокрутки для длинного текста
        TextView textView = dialog.findViewById(android.R.id.message);
        if (textView != null) {
            textView.setMovementMethod(new ScrollingMovementMethod());
            textView.setTextSize(14); // Оптимальный размер для чтения
        }

        // Увеличение отступов для лучшей читаемости
        if (dialog.getWindow() != null) {
            dialog.getWindow().setLayout(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    (int)(getResources().getDisplayMetrics().heightPixels * 0.7) // 70% экрана
            );
        }
    }

    private void initializeBle() {
        bluetoothLeScanner = bluetoothAdapter.getBluetoothLeScanner();
        bleDeviceAdapter = new BleDeviceAdapter();
        bleDeviceAdapter.setOnDeviceClickListener(device -> {
            if (bleService == null) {
                bleService = new BleService(MainActivity.this, bleCallback);
            }
            bleService.connectToDevice(device);
        });
    }



    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);


        if (requestCode == REQUEST_ENABLE_BT) {
            if (resultCode == RESULT_OK) {
                // Bluetooth включен пользователем
                initializeBle();
            } else {
                // Пользователь отказался включать Bluetooth
                Toast.makeText(this, "Для работы приложения необходимо включить Bluetooth", Toast.LENGTH_SHORT).show();
            }
        }

        if (requestCode == REQUEST_CODE_SELECT_CONTACT && resultCode == RESULT_OK) {
            if (data != null && data.hasExtra("selected_contact")) {
                selectedContact = data.getParcelableExtra("selected_contact");
                if (selectedContact != null) {
                    isPrivateChat = true;
                    Log.d("zxc", "Контакт выбран: " + selectedContact.getName() + ", режим чата: личный");
                    updateChatStatus();
                    Toast.makeText(this, "Выбран контакт: " + selectedContact.getName(), Toast.LENGTH_SHORT).show();
                } else {
                    Log.e("zxc", "Контакт не выбран или равен null");
                }
            } else {
                Log.e("zxc", "Данные не содержат selected_contact");
            }
        } else {
            Log.e("zxc", "Ошибка при выборе контакта: requestCode=" + requestCode + ", resultCode=" + resultCode);
        }
    }

    private void updateChatStatus() {
        Log.d("zxc", "Обновление подписи: isPrivateChat=" + isPrivateChat + ", selectedContact=" + (selectedContact != null ? selectedContact.getName() : "null"));
        if (isPrivateChat && selectedContact != null) {
            chatStatusTextView.setText("Личный чат: " + selectedContact.getName());
            Log.d("zxc", "Подпись обновлена: Личный чат: " + selectedContact.getName());
            List<Message> messages = privateChatMessages.get(selectedContact);
            if (messages == null) {
                messages = new ArrayList<>();
                privateChatMessages.put(selectedContact, messages);
            }
            chatAdapter = new ChatAdapter(messages);
        } else {
            chatStatusTextView.setText("Общий чат");
            Log.d("zxc", "Подпись обновлена: Общий чат");
            chatAdapter = new ChatAdapter(groupChatMessages);
        }
        chatRecyclerView.setAdapter(chatAdapter);
    }

    private boolean checkBlePermissions() {
        for (String permission : BLE_PERMISSIONS) {
            if (ContextCompat.checkSelfPermission(this, permission)
                    != PackageManager.PERMISSION_GRANTED) {
                return false;
            }
        }
        return true;
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Проверяем, мог ли пользователь изменить разрешения в настройках
        if (!checkBlePermissions() && bluetoothAdapter != null && bluetoothAdapter.isEnabled()) {
            checkAndRequestPermissions();
        }
    }

    private boolean isBleSupported() {
        return getPackageManager().hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE);
    }

    private void showBleScannerDialog() {
        if (!getPackageManager().hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE)) {
            Toast.makeText(this, "BLE не поддерживается", Toast.LENGTH_SHORT).show();
            return;
        }

        if (!checkBlePermissions()) {
            requestBlePermissions();
            return;
        }

        if (bluetoothAdapter == null || !bluetoothAdapter.isEnabled()) {
            Intent enableBtIntent = new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE);
            startActivityForResult(enableBtIntent, REQUEST_ENABLE_BT);
            return;
        }

        // Создаем диалог сканирования, но не показываем его автоматически
        BleScannerDialog dialog = new BleScannerDialog(this);
        dialog.setOnDeviceSelectedListener(device -> {
            if (ActivityCompat.checkSelfPermission(this,
                    Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED) {
                connectToBleDevice(device);
            } else {
                requestBlePermissions();
            }
        });

        // Показываем диалог только при явном вызове из меню
        if (!isFinishing()) {
            dialog.show();
        }
    }

    private void startBleScan() {
        if (isScanning || bluetoothAdapter == null || !bluetoothAdapter.isEnabled()) {
            return;
        }

        if (scanCallback == null) {
            scanCallback = new ScanCallback() {
                @Override
                public void onScanResult(int callbackType, ScanResult result) {
                    super.onScanResult(callbackType, result);
                    runOnUiThread(() -> bleDeviceAdapter.addDevice(result.getDevice()));
                }

                @Override
                public void onScanFailed(int errorCode) {
                    super.onScanFailed(errorCode);
                    isScanning = false;
                    runOnUiThread(() ->
                            Toast.makeText(MainActivity.this,
                                    "Scan failed: " + getScanErrorDescription(errorCode),
                                    Toast.LENGTH_SHORT).show());
                }
            };
        }

        try {
            bluetoothLeScanner.startScan(null,
                    new ScanSettings.Builder()
                            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                            .build(),
                    scanCallback);
            isScanning = true;
        } catch (SecurityException e) {
            Log.e("BLE", "Security exception while starting scan", e);
        }
    }


    private void stopBleScan() {
        if (isScanning && bluetoothLeScanner != null && scanCallback != null) {
            try {
                bluetoothLeScanner.stopScan(scanCallback);
                isScanning = false;
                Toast.makeText(this, "Сканирование остановлено", Toast.LENGTH_SHORT).show();
            } catch (SecurityException e) {
                Log.e("BLE", "Ошибка безопасности", e);
            }
        }
    }

    private String getScanErrorDescription(int errorCode) {
        switch (errorCode) {
            case ScanCallback.SCAN_FAILED_ALREADY_STARTED:
                return "Сканирование уже запущено";
            case ScanCallback.SCAN_FAILED_APPLICATION_REGISTRATION_FAILED:
                return "Ошибка регистрации приложения";
            case ScanCallback.SCAN_FAILED_INTERNAL_ERROR:
                return "Внутренняя ошибка";
            case ScanCallback.SCAN_FAILED_FEATURE_UNSUPPORTED:
                return "Функция не поддерживается";
            default:
                return "Неизвестная ошибка (" + errorCode + ")";
        }
    }



    private void requestBlePermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ActivityCompat.requestPermissions(this,
                    new String[]{
                            Manifest.permission.BLUETOOTH_SCAN,
                            Manifest.permission.BLUETOOTH_CONNECT
                    },
                    REQUEST_BLE_PERMISSIONS);
        } else {
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.ACCESS_FINE_LOCATION},
                    REQUEST_BLE_PERMISSIONS);
        }
    }

    private void showBleScanDialogInternal() {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("Сканирование BLE устройств");

        // Настройка списка устройств
        RecyclerView recyclerView = new RecyclerView(this);
        recyclerView.setLayoutManager(new LinearLayoutManager(this));
        recyclerView.setAdapter(bleDeviceAdapter);

        builder.setView(recyclerView);
        builder.setNegativeButton("Закрыть", null);
        builder.setPositiveButton("Сканировать", (dialog, which) -> {
            startBleScan();
        });

        final AlertDialog dialog = builder.create();
        dialog.show();

        // Начинаем сканирование при открытии
        startBleScan();

        // Останавливаем при закрытии
        dialog.setOnDismissListener(d -> stopBleScan());
    }


    private void setupBluetooth() {
        // Проверяем разрешения перед выполнением операций с Bluetooth
        if (!checkBlePermissions()) {
            Log.e("BLE", "Необходимые разрешения не предоставлены");
            Toast.makeText(this, "Необходимы разрешения для работы с Bluetooth", Toast.LENGTH_SHORT).show();
            return;
        }

        BluetoothManager bluetoothManager = (BluetoothManager) getSystemService(Context.BLUETOOTH_SERVICE);
        if (bluetoothManager != null) {
            try {
                bluetoothAdapter = bluetoothManager.getAdapter();
            } catch (SecurityException e) {
                Log.e("BLE", "SecurityException при получении BluetoothAdapter", e);
                Toast.makeText(this, "Ошибка доступа к Bluetooth", Toast.LENGTH_SHORT).show();
                return;
            }
        }

        if (bluetoothAdapter == null) {
            Toast.makeText(this, "Bluetooth не поддерживается", Toast.LENGTH_SHORT).show();
            return;
        }

        if (!bluetoothAdapter.isEnabled()) {
            // Запрашиваем включение Bluetooth
            Intent enableBtIntent = new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE);
            try {
                startActivityForResult(enableBtIntent, REQUEST_ENABLE_BT);
            } catch (SecurityException e) {
                Log.e("BLE", "SecurityException при запросе включения Bluetooth", e);
                Toast.makeText(this, "Ошибка запроса включения Bluetooth", Toast.LENGTH_SHORT).show();
            }
        } else {
            // Bluetooth включен, можно продолжать
            initializeBle();
        }
    }




    @Override
    public void onRequestPermissionsResult(int requestCode,
                                           @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);

        if (requestCode == REQUEST_BLE_PERMISSIONS) {
            boolean allGranted = true;
            for (int result : grantResults) {
                if (result != PackageManager.PERMISSION_GRANTED) {
                    allGranted = false;
                    break;
                }
            }

            if (allGranted) {
                // Разрешения получены
                setupBluetooth();
            } else {
                // Проверяем, нужно ли показать объяснение
                boolean shouldShowRationale = false;
                for (String permission : permissions) {
                    if (ActivityCompat.shouldShowRequestPermissionRationale(this, permission)) {
                        shouldShowRationale = true;
                        break;
                    }
                }

                if (shouldShowRationale) {
                    // Показываем объяснение снова
                    showPermissionExplanationDialog();
                } else {
                    // Пользователь выбрал "Не спрашивать снова"
                    showPermissionSettingsDialog();
                }
            }
        }
    }

    private void showPermissionSettingsDialog() {
        new AlertDialog.Builder(this)
                .setTitle("Требуются разрешения")
                .setMessage("Вы отклонили разрешения и выбрали 'Не спрашивать снова'. " +
                        "Вы можете предоставить разрешения вручную в настройках приложения.")
                .setPositiveButton("Настройки", (dialog, which) -> {
                    Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
                    Uri uri = Uri.fromParts("package", getPackageName(), null);
                    intent.setData(uri);
                    startActivity(intent);
                })
                .setNegativeButton("Отмена", null)
                .show();
    }
    private final BleService.BleCallback bleCallback = new BleService.BleCallback() {
        @Override
        public void onDeviceConnected(String deviceName) {
            runOnUiThread(() -> {
                isBleConnected = true;
                updateBleConnectionStatus(true, deviceName);
                Toast.makeText(MainActivity.this,
                        "Подключено к " + deviceName, Toast.LENGTH_SHORT).show();
                sendCommandInBackground("GETCONFIG;;");
            });
        }

        @Override
        public void onDeviceDisconnected() {
            runOnUiThread(() -> {
                isBleConnected = false;
                updateBleConnectionStatus(false);
                Toast.makeText(MainActivity.this,
                        "BLE соединение отключено", Toast.LENGTH_SHORT).show();
            });
        }

        @Override
        public void onDataReceived(String data) {
            parseIncomingMessage(data);
        }

        @Override
        public void onError(String error) {
            runOnUiThread(() -> {
                Toast.makeText(MainActivity.this,
                        "BLE ошибка: " + error, Toast.LENGTH_SHORT).show();
                if (error.contains("отключ") || error.contains("не готов")) {
                    isBleConnected = false;
                    updateBleConnectionStatus(false);
                }
            });
        }
    };

    @Override
    protected void onDestroy() {
        super.onDestroy();
        unregisterReceiver(usbPermissionReceiver);

        if (usbSerialPort != null) {
            try {
                usbSerialPort.close();
            } catch (IOException e) {
                Log.e("zxc", "Ошибка закрытия соединения", e);
                Toast.makeText(this, "Ошибка закрытия соединения", Toast.LENGTH_SHORT).show();
            }
        }
        if (usbIoManager != null) {
            usbIoManager.stop();
        }

        if (bleService != null) {
            bleService.disconnect();
        }
        executor.shutdown();

        BluetoothAdapter bluetoothAdapter = BluetoothAdapter.getDefaultAdapter();
        if (bluetoothAdapter != null) {
            BluetoothLeScanner scanner = bluetoothAdapter.getBluetoothLeScanner();
            if (scanner != null && scanCallback != null) {
                try {
                    scanner.stopScan(scanCallback);
                    Log.d("zxc", "Сканирование остановлено в onDestroy");
                } catch (SecurityException e) {
                    Log.e("zxc", "SecurityException when stopping scan in onDestroy", e);
                }
            }
        }
    }

    private void showLoraSpeedDialog() {
        final String[] speeds = {"1200", "2400", "4800", "9600", "19200", "38400", "57600", "115200"};
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("Выберите скорость LoRa");
        builder.setItems(speeds, new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {
                int selectedSpeed = Integer.parseInt(speeds[which]);
                saveLoraSpeed(selectedSpeed);
                setLoraSpeed(selectedSpeed);
                Toast.makeText(MainActivity.this, "Скорость LoRa изменена на " + selectedSpeed, Toast.LENGTH_SHORT).show();
            }
        });
        builder.show();
    }

    private void saveLoraSpeed(int speed) {
        SharedPreferences prefs = getSharedPreferences("user", MODE_PRIVATE);
        prefs.edit().putInt("lora_speed", speed).apply();
    }

    private int getLoraSpeed() {
        SharedPreferences prefs = getSharedPreferences("user", MODE_PRIVATE);
        return prefs.getInt("lora_speed", 115200);
    }

    private void setLoraSpeed(int speed) {
        if (usbSerialPort != null) {
            try {
                usbSerialPort.setParameters(speed, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE);
                Log.d("zxc", "Скорость LoRa установлена на " + speed);
            } catch (IOException e) {
                Log.e("zxc", "Ошибка установки скорости LoRa", e);
                Toast.makeText(this, "Ошибка установки скорости LoRa", Toast.LENGTH_SHORT).show();
            }
        } else {
            Log.e("zxc", "USB-порт не инициализирован");
            Toast.makeText(this, "USB-порт не инициализирован", Toast.LENGTH_SHORT).show();
        }
    }

    private void showChangePasswordDialog() {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("Изменить пароль");

        final EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_VARIATION_PASSWORD);
        builder.setView(input);

        builder.setPositiveButton("Сохранить", new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {
                String newPassword = input.getText().toString();
                if (newPassword.length() == 6) {
                    savePassword(newPassword);
                    // Сбрасываем флаг проверки пароля, чтобы запросить его снова
                    getSharedPreferences("user", MODE_PRIVATE)
                            .edit()
                            .putBoolean("password_checked", false)
                            .apply();

                    sendCommandInBackground("PASSWORD;NEWPASS:" + newPassword + ";;");
                    Toast.makeText(MainActivity.this, "Пароль изменен", Toast.LENGTH_SHORT).show();
                } else {
                    Toast.makeText(MainActivity.this, "Пароль должен состоять из 6 цифр", Toast.LENGTH_SHORT).show();
                }
            }
        });

        builder.setNegativeButton("Отмена", null);
        builder.show();
    }

    private void showPasswordDialog() {
        SharedPreferences prefs = getSharedPreferences("user", MODE_PRIVATE);

        // Показываем только если пароль еще не проверялся
        if (prefs.getBoolean("password_checked", false) || isBleConnected) {
            return;
        }

        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("Введите пароль");
        builder.setMessage("Пароль по умолчанию: 000000");
        builder.setCancelable(false);

        final EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_VARIATION_PASSWORD);
        builder.setView(input);

        builder.setPositiveButton("ОК", (dialog, which) -> {
            String enteredPassword = input.getText().toString();
            // Если поле пустое, используем пароль по умолчанию
            if (enteredPassword.isEmpty()) {
                enteredPassword = "000000";
            }

            // Проверяем пароль
            if (enteredPassword.equals(getPassword())) {
                prefs.edit().putBoolean("password_checked", true).apply();

                // Формируем и отправляем команду с паролем на LoRa модуль
                String passwordCommand = "PASSWORD;PASS:" + enteredPassword + ";;";
                sendCommandInBackground(passwordCommand);

                // После отправки пароля запрашиваем конфигурацию
                new Handler(Looper.getMainLooper()).postDelayed(() -> {
                    sendCommandInBackground("GETCONFIG;;");
                }, 1000); // Задержка 1 секунда

            } else {
                Toast.makeText(this, "Неверный пароль", Toast.LENGTH_SHORT).show();
                finish();
            }
        });

        builder.setNegativeButton("Отмена", (dialog, which) -> {
            // Закрываем приложение при отмене
            finish();
        });

        AlertDialog dialog = builder.create();
        dialog.show();
    }

    private void savePassword(String password) {
        SharedPreferences prefs = getSharedPreferences("user", MODE_PRIVATE);
        prefs.edit().putString("password", password).apply();
    }

    private String getPassword() {
        SharedPreferences prefs = getSharedPreferences("user", MODE_PRIVATE);
        return prefs.getString("password", "000000");
    }

    private void initUsbConnection() {
        UsbManager usbManager = (UsbManager) getSystemService(Context.USB_SERVICE);
        if (usbManager == null) {
            Log.e("zxc", "UsbManager не доступен");
            runOnUiThread(() -> Toast.makeText(this, "Ошибка: UsbManager не доступен", Toast.LENGTH_SHORT).show());
            return;
        }

        List<UsbSerialDriver> availableDrivers = UsbSerialProber.getDefaultProber().findAllDrivers(usbManager);
        if (availableDrivers.isEmpty()) {
            Log.e("zxc", "Нет доступных USB-устройств");
            runOnUiThread(() -> Toast.makeText(this, "Нет доступных USB-устройств", Toast.LENGTH_SHORT).show());
            return;
        }

        UsbSerialDriver usbSerialDriver = availableDrivers.get(0);
        usbSerialPort = usbSerialDriver.getPorts().get(0);
        UsbDevice usbDevice = usbSerialDriver.getDevice();

        if (!usbManager.hasPermission(usbDevice)) {
            PendingIntent permissionIntent = PendingIntent.getBroadcast(
                    this,
                    0,
                    new Intent(ACTION_USB_PERMISSION),
                    PendingIntent.FLAG_IMMUTABLE
            );
            usbManager.requestPermission(usbDevice, permissionIntent);
            return;
        }

        try {
            usbSerialPort.open(usbManager.openDevice(usbDevice));
            int loraSpeed = getLoraSpeed();
            usbSerialPort.setParameters(loraSpeed, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE);
            Log.d("zxc", "Скорость LoRa установлена на " + loraSpeed);

            // Удаляем проверку пароля здесь, так как она уже выполняется в showPasswordDialog()
            sendCommandInBackground("GETCONFIG;;");

            usbIoManager = new SerialInputOutputManager(usbSerialPort, new SerialInputOutputManager.Listener() {
                @Override
                public void onNewData(byte[] data) {
                    String receivedData = new String(data);
                    Log.d("zxc", "Получены данные: " + receivedData);

                    incomingDataBuffer.append(receivedData);
                    String bufferContent = incomingDataBuffer.toString();
                    int endOfMessageIndex = bufferContent.indexOf(";;");

                    while (endOfMessageIndex != -1) {
                        String fullMessage = bufferContent.substring(0, endOfMessageIndex + 2);
                        Log.d("zxc", "Полное сообщение: " + fullMessage);

                        parseIncomingMessage(fullMessage);
                        incomingDataBuffer.delete(0, endOfMessageIndex + 2);
                        bufferContent = incomingDataBuffer.toString();
                        endOfMessageIndex = bufferContent.indexOf(";;");
                    }
                }

                @Override
                public void onRunError(Exception e) {
                    Log.e("zxc", "Ошибка чтения данных", e);
                    runOnUiThread(() -> Toast.makeText(MainActivity.this, "Ошибка чтения данных", Toast.LENGTH_SHORT).show());
                }
            });
            usbIoManager.start();

            Log.d("zxc", "USB-устройство подключено: " + usbDevice.getDeviceName());

            // Автоматически запрашиваем конфигурацию после подключения
            sendCommandInBackground("GETCONFIG;;");

            // Отправляем сообщение о подключении
            runOnUiThread(() -> {
                sendMessageToChat("Система", "Устройство подключено");
                Toast.makeText(MainActivity.this, "USB устройство подключено", Toast.LENGTH_SHORT).show();
            });

        } catch (IOException e) {
            Log.e("zxc", "Ошибка подключения к USB-устройству", e);
            runOnUiThread(() -> Toast.makeText(this, "Ошибка подключения к USB-устройству", Toast.LENGTH_SHORT).show());
        } catch (Exception e) {
            Log.e("zxc", "Неизвестная ошибка при подключении к USB", e);
            runOnUiThread(() -> Toast.makeText(this, "Неизвестная ошибка при подключении к USB", Toast.LENGTH_SHORT).show());
        }
    }

    private String getUserNameFromPreferences() {
        SharedPreferences prefs = getSharedPreferences("user", MODE_PRIVATE);
        return prefs.getString("nickname", "User");
    }

    private void sendMessageToChat(String sender, String message) {
        try {
            Message newMessage = new Message(sender, message);
            if (isPrivateChat && selectedContact != null) {
                List<Message> messages = privateChatMessages.get(selectedContact);
                if (messages == null) {
                    messages = new ArrayList<>();
                    privateChatMessages.put(selectedContact, messages);
                }
                messages.add(newMessage);
            } else {
                groupChatMessages.add(newMessage);
            }
            chatAdapter.notifyItemInserted(chatAdapter.getItemCount() - 1);
            chatRecyclerView.scrollToPosition(chatAdapter.getItemCount() - 1);

            Log.d("zxc", "Сообщение отправлено: " + sender + ": " + message);
        } catch (Exception e) {
            Log.e("zxc", "Ошибка при добавлении сообщения в чат", e);
            Toast.makeText(this, "Ошибка при добавлении сообщения в чат", Toast.LENGTH_SHORT).show();
        }
    }

    private void sendPasswordToLora(String password) {
        String command = "PASSWORD;PASS:" + password + ";;";
        sendCommandInBackground(command);
    }

    private void sendCommandInBackground(final String command) {
        executor.execute(() -> {
            try {
                String commandWithUID = command;

                // Генерируем UID для команд, требующих подтверждения
                if (command.startsWith("PM;") || command.startsWith("REMOTECONFIG;") ||
                        command.startsWith("REMOTEBLOCK;") || command.startsWith("REMOTEUNBLOCK;") ||
                        command.startsWith("REMOTELORAOFF;")) {

                    String uid = generateUID();
                    commandWithUID = command.replaceFirst(";", ";UID:" + uid + ";");
                    messageStatusMap.put(uid, MessageStatus.SENT);

                    // Особый случай для REMOTELORAOFF
                    if (command.startsWith("REMOTELORAOFF;")) {
                        remoteLoraOffConfirmations.put(uid, System.currentTimeMillis());
                    }
                }

                // Форматируем команду согласно протоколу
                String formattedCommand = commandWithUID.endsWith(";;") ? commandWithUID : commandWithUID + ";;";

                // Отправка через USB
                if (usbSerialPort != null) {
                    sendToTerminal(formattedCommand);
                    Log.d("USB_TX", "Sent: " + formattedCommand);
                }
                // Отправка через BLE
                else if (isBleConnected && bleService != null) {
                    if (!bleService.sendCommand(formattedCommand)) {
                        Log.e("BLE_TX", "Failed to send: " + formattedCommand);

                        // Повторная попытка через 1 секунду
                        new Handler(Looper.getMainLooper()).postDelayed(() -> {
                            if (isBleConnected && bleService != null) {
                                bleService.sendCommand(formattedCommand);
                            }
                        }, 1000);
                    } else {
                        Log.d("BLE_TX", "Sent: " + formattedCommand);
                    }
                }
                // Нет активного соединения
                else {
                    runOnUiThread(() -> {
                        Toast.makeText(MainActivity.this,
                                "Нет активного соединения",
                                Toast.LENGTH_SHORT).show();
                        Log.e("TX", "No active connection");
                    });
                }
            } catch (Exception e) {
                Log.e("TX", "Command send error: " + command, e);
                runOnUiThread(() -> Toast.makeText(MainActivity.this,
                        "Ошибка отправки: " + e.getMessage(),
                        Toast.LENGTH_SHORT).show());
            }
        });
    }

    private void sendAutoMessage() {
        // Отправляем только если пароль проверен
        SharedPreferences prefs = getSharedPreferences("user", MODE_PRIVATE);
        if (prefs.getBoolean("password_checked", false)) {
            String message = "BROADCAST;NIC:" + userName + ";MESSAGE:в сети;;";
            sendCommandInBackground(message);
        }
    }

    private void sendToTerminal(String command) {
        if (usbSerialPort == null) {
            Log.e("USB", "USB порт не инициализирован");
            runOnUiThread(() -> Toast.makeText(MainActivity.this,
                    "USB устройство не подключено", Toast.LENGTH_SHORT).show());
            return;
        }

        try {
            byte[] data = command.getBytes(StandardCharsets.UTF_8);
            usbSerialPort.write(data, 5000);
            Log.d("USB", "Команда отправлена: " + command);
        } catch (IOException e) {
            Log.e("USB", "Ошибка отправки данных", e);
            runOnUiThread(() -> Toast.makeText(MainActivity.this,
                    "USB ошибка отправки", Toast.LENGTH_SHORT).show());
        } catch (Exception e) {
            Log.e("USB", "Общая ошибка", e);
            runOnUiThread(() -> Toast.makeText(MainActivity.this,
                    "Ошибка USB соединения", Toast.LENGTH_SHORT).show());
        }
    }

    private void startCommunicationAfterConnect() {
        if (!isBleConnected || bleService == null) return;

        // 1. Проверяем пароль (если нужно)
        SharedPreferences prefs = getSharedPreferences("user", MODE_PRIVATE);
        if (!prefs.getBoolean("password_checked", false)) {
            showPasswordDialogForBle(() -> {
                // После успешного ввода пароля отправляем ONLINE сообщение
                sendOnlineMessage();
            });
            return;
        }

        // 2. Если пароль уже проверен
        sendOnlineMessage();
    }

    private void sendOnlineMessage() {
        String userName = getUserNameFromPreferences();
        String onlineMessage = "ONLINE;NIC:" + userName + ";;";
        sendCommandInBackground(onlineMessage);

        // Затем запрашиваем конфигурацию
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            sendCommandInBackground("GETCONFIG;;");
        }, 1000);
    }

    private void parseIncomingMessage(String message) {
        if (message == null || message.isEmpty()) {
            Log.e("BLE", "Пустое сообщение");
            return;
        }

        // Очистка от лишних символов
        message = message.trim();
        Log.d("BLE", "Received: " + message);

        if (message.startsWith("PASSWORD;PASS:-1;;")) {
            runOnUiThread(() -> {
                Toast.makeText(this, "Неверный пароль", Toast.LENGTH_SHORT).show();
                disconnectBle();
            });
        }
        else if (message.startsWith("CONFIG;")) {
            // Обработка конфигурации
            runOnUiThread(() -> {
                Toast.makeText(this, "Конфигурация получена", Toast.LENGTH_SHORT).show();
                // Парсим и отображаем параметры конфигурации
            });
        }

        try {
            String[] parts = message.split(";");
            String type = parts[0];

            // Объявляем переменные как final
            final String[] parsedNic = {""};
            final String[] parsedMessageText = {""};
            final String[] parsedAddrH = {""};
            final String[] parsedAddrL = {""};
            final String[] parsedRssi = {""};
            final String[] parsedRet = {""};
            final String[] parsedUid = {""};
            final String[] parsedStatus = {""};
            final String[] parsedPass = {""};
            final String[] parsedNewPass = {""};
            final String[] parsedChannel = {""};
            final String[] parsedPower = {""};
            final String[] parsedPassValid = {""};
            final String[] parsedFwVer = {""};

            // Парсинг всех параметров сообщения
            for (String part : parts) {
                if (part.startsWith("NIC:")) parsedNic[0] = part.substring(4);
                else if (part.startsWith("MESSAGE:")) parsedMessageText[0] = part.substring(8);
                else if (part.startsWith("ADDR_H:")) parsedAddrH[0] = part.substring(7);
                else if (part.startsWith("ADDR_L:")) parsedAddrL[0] = part.substring(7);
                else if (part.startsWith("RSSI:")) parsedRssi[0] = part.substring(5);
                else if (part.startsWith("RET:")) parsedRet[0] = part.substring(4);
                else if (part.startsWith("UID:")) parsedUid[0] = part.substring(4);
                else if (part.startsWith("STATUS:")) parsedStatus[0] = part.substring(7);
                else if (part.startsWith("PASS:")) parsedPass[0] = part.substring(5);
                else if (part.startsWith("NEWPASS:")) parsedNewPass[0] = part.substring(8);
                else if (part.startsWith("CHANNEL:")) parsedChannel[0] = part.substring(8);
                else if (part.startsWith("POWER:")) parsedPower[0] = part.substring(6);
                else if (part.startsWith("PASSVALID:")) parsedPassValid[0] = part.substring(10);
                else if (part.startsWith("FWVER:")) parsedFwVer[0] = part.substring(6);
            }

            switch (type) {
                case "PM":
                    if (!parsedUid[0].isEmpty() && !parsedStatus[0].isEmpty()) {
                        handleMessageStatus(parsedUid[0], parsedStatus[0], parsedRssi[0], parsedRet[0]);
                    } else if (!parsedNic[0].isEmpty() && !parsedMessageText[0].isEmpty()) {
                        final String nic = parsedNic[0];
                        final String messageText = parsedMessageText[0];
                        runOnUiThread(() -> sendMessageToChat(nic, messageText));
                    }
                    break;

                case "BROADCAST":
                    if (!parsedNic[0].isEmpty() && !parsedMessageText[0].isEmpty()) {
                        final String nic = parsedNic[0];
                        final String messageText = parsedMessageText[0];
                        runOnUiThread(() -> sendMessageToChat(nic, messageText));
                    }
                    break;

                case "ALARM":
                    if (!parsedNic[0].isEmpty() && !parsedMessageText[0].isEmpty()) {
                        final String nic = parsedNic[0];
                        final String messageText = parsedMessageText[0];
                        runOnUiThread(() -> sendMessageToChat(nic, messageText));
                    }
                    break;

                case "PASSWORD":
                    if (!parsedPass[0].isEmpty()) {
                        final String passStatus = parsedPass[0].equals("-1") ?
                                "Неверный пароль" : "Пароль изменен";
                        runOnUiThread(() -> Toast.makeText(this, passStatus, Toast.LENGTH_SHORT).show());
                    }
                    break;

                case "CONFIG":
                    if (!parsedChannel[0].isEmpty() && !parsedPower[0].isEmpty() &&
                            !parsedRet[0].isEmpty() && !parsedAddrH[0].isEmpty() &&
                            !parsedAddrL[0].isEmpty() && !parsedPassValid[0].isEmpty() &&
                            !parsedFwVer[0].isEmpty()) {

                        final String configMessage = String.format(
                                "Конфигурация модуля:\nАдрес: %s:%s\nКанал: %s\nМощность: %s%%\nРетрансляция: %s\nПароль: %s\nВерсия ПО: %s",
                                parsedAddrH[0], parsedAddrL[0], parsedChannel[0], parsedPower[0], parsedRet[0],
                                parsedPassValid[0].equals("true") ? "установлен" : "по умолчанию", parsedFwVer[0]
                        );

                        runOnUiThread(() -> {
                            sendMessageToChat("Система", configMessage);
                            Log.d("BLE", "Конфигурация получена и отображена");
                        });
                    }
                    break;

                case "REMOTECONFIG":
                    if (!parsedUid[0].isEmpty() && !parsedStatus[0].isEmpty()) {
                        handleMessageStatus(parsedUid[0], parsedStatus[0], parsedRssi[0], parsedRet[0]);
                    } else if (!parsedAddrH[0].isEmpty() && !parsedAddrL[0].isEmpty()) {
                        runOnUiThread(() -> Toast.makeText(this,
                                "Удаленная конфигурация изменена", Toast.LENGTH_SHORT).show());
                    }
                    break;

                case "REMOTEBLOCK":
                case "REMOTEUNBLOCK":
                case "REMOTELORAOFF":
                    if (!parsedUid[0].isEmpty() && !parsedStatus[0].isEmpty()) {
                        if (type.equals("REMOTELORAOFF")) {
                            handleRemoteLoraOffStatus(parsedUid[0], parsedStatus[0]);
                        } else {
                            handleMessageStatus(parsedUid[0], parsedStatus[0], parsedRssi[0], parsedRet[0]);
                        }
                    } else if (!parsedAddrH[0].isEmpty() && !parsedAddrL[0].isEmpty()) {
                        final String action = type.equals("REMOTEBLOCK") ? "заблокирован" :
                                type.equals("REMOTEUNBLOCK") ? "разблокирован" : "отключен";
                        runOnUiThread(() -> Toast.makeText(this,
                                "Удаленный модуль " + action, Toast.LENGTH_SHORT).show());
                    }
                    break;

                default:
                    Log.w("BLE", "Неизвестный тип сообщения: " + type);
                    break;
            }
        } catch (Exception e) {
            Log.e("BLE", "Ошибка разбора сообщения", e);
            runOnUiThread(() -> Toast.makeText(this,
                    "Ошибка обработки сообщения", Toast.LENGTH_SHORT).show());
        }
    }

    private String hashPassword(String password) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(password.getBytes());
            return bytesToHex(hash);
        } catch (NoSuchAlgorithmException e) {
            Log.e("zxc", "Ошибка хеширования пароля", e);
            return null;
        }
    }

    public void sendRemoteLoraOffCommand(String addrH, String addrL) {
        String command = String.format("REMOTELORAOFF;ADDR_H:%s;ADDR_L:%s;;", addrH, addrL);
        sendCommandInBackground(command);
    }

    private void handleRemoteLoraOffStatus(String uid, String status) {
        Long firstConfirmationTime = remoteLoraOffConfirmations.get(uid);

        if (firstConfirmationTime == null) {
            // Первое подтверждение
            remoteLoraOffConfirmations.put(uid, System.currentTimeMillis());
            String statusMessage = "Подтверждение 1/2 для REMOTELORAOFF " + uid + ": " + status;
            runOnUiThread(() -> {
                Toast.makeText(MainActivity.this, statusMessage, Toast.LENGTH_SHORT).show();
                sendMessageToChat("Система", statusMessage);
            });
        } else {
            // Второе подтверждение
            remoteLoraOffConfirmations.remove(uid);
            String statusMessage = "Подтверждение 2/2 для REMOTELORAOFF " + uid + ": " + status;
            runOnUiThread(() -> {
                Toast.makeText(MainActivity.this, statusMessage, Toast.LENGTH_SHORT).show();
                sendMessageToChat("Система", statusMessage);
            });
        }
    }

    private void handleMessageStatus(String uid, String status, String rssi, String ret) {
        MessageStatus msgStatus = status.equals("delivered") ? MessageStatus.DELIVERED : MessageStatus.SENT;
        messageStatusMap.put(uid, msgStatus);

        String statusMessage = "Сообщение " + uid + ": " +
                (status.equals("delivered") ? "доставлено" : "отправлено") +
                (rssi.isEmpty() ? "" : " (RSSI: " + rssi + " dB)") +
                (ret.isEmpty() ? "" : " (ретрансляция: " + ret + ")");

        runOnUiThread(() -> {
            Toast.makeText(MainActivity.this, statusMessage, Toast.LENGTH_SHORT).show();
            sendMessageToChat("Система", statusMessage);
        });
    }

    // Добавляем метод генерации UID
    private String generateUID() {
        return String.format("%08x", System.currentTimeMillis() & 0xFFFFFFFF);
    }



    private String bytesToHex(byte[] bytes) {
        StringBuilder result = new StringBuilder();
        for (byte b : bytes) {
            result.append(String.format("%02x", b));
        }
        return result.toString();
    }
}