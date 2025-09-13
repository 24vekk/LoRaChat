package de.kai_morich.simple_usb_terminal;

import android.content.Context;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.Spinner;
import android.widget.Toast;
import androidx.fragment.app.Fragment;

import de.kai_morich.simple_usb_terminal.UsbService;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

public class ChatFragment extends Fragment {

    private UsbService usbService;
    private ArrayAdapter<String> chatAdapter;
    private List<String> chatMessages;
    private UsbManager usbManager;
    private UsbDevice usbDevice;

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_chat, container, false);

        // Инициализация элементов интерфейса
        Spinner portSpinner = view.findViewById(R.id.portSpinner);
        EditText usernameEditText = view.findViewById(R.id.usernameEditText);
        ListView chatListView = view.findViewById(R.id.chatListView);
        EditText messageEditText = view.findViewById(R.id.messageEditText);
        Button sendButton = view.findViewById(R.id.sendButton);

        // Инициализация списка сообщений
        chatMessages = new ArrayList<>();
        chatAdapter = new ArrayAdapter<>(getContext(), android.R.layout.simple_list_item_1, chatMessages);
        chatListView.setAdapter(chatAdapter);

        // Инициализация UsbManager
        usbManager = (UsbManager) requireActivity().getSystemService(Context.USB_SERVICE);

        // Получение списка доступных портов
        HashMap<String, UsbDevice> deviceList = usbManager.getDeviceList();
        List<String> portList = new ArrayList<>(deviceList.keySet());

        ArrayAdapter<String> portAdapter = new ArrayAdapter<>(getContext(), android.R.layout.simple_spinner_item, portList);
        portAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        portSpinner.setAdapter(portAdapter);

        // Обработка выбора порта
        portSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                String selectedPort = portList.get(position);
                usbDevice = deviceList.get(selectedPort);
                if (usbDevice != null) {
                    usbService = new UsbService(usbManager, usbDevice);
                    Toast.makeText(getContext(), "Устройство подключено: " + usbDevice.getDeviceName(), Toast.LENGTH_SHORT).show();
                }
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
                // Ничего не выбрано
            }
        });

        // Обработка нажатия кнопки отправки
        sendButton.setOnClickListener(v -> {
            String message = messageEditText.getText().toString();
            String username = usernameEditText.getText().toString();
            String formattedMessage = username + ": " + message;

            // Отправка сообщения через USB
            if (usbService != null) {
                String broadcastMessage = "BROADCAST;MESSAGE:" + formattedMessage + ";";
                usbService.write(broadcastMessage.getBytes());
            } else {
                Toast.makeText(getContext(), "USB-устройство не подключено", Toast.LENGTH_SHORT).show();
            }

            // Добавление сообщения в историю
            addMessageToChat(formattedMessage);
            messageEditText.setText("");
        });

        return view;
    }

    private void addMessageToChat(String message) {
        chatMessages.add(message);
        chatAdapter.notifyDataSetChanged();
    }
}