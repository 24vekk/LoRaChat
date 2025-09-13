package de.kai_morich.simple_usb_terminal;

import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;

public class AddContactActivity extends AppCompatActivity {

    private EditText nameEditText;
    private EditText addrHEditText;
    private EditText addrLEditText;
    private Button saveButton;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_add_contact);

        nameEditText = findViewById(R.id.nameEditText);
        addrHEditText = findViewById(R.id.addrHEditText);
        addrLEditText = findViewById(R.id.addrLEditText);
        saveButton = findViewById(R.id.saveButton);

        saveButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                String name = nameEditText.getText().toString();
                String addrHStr = addrHEditText.getText().toString();
                String addrLStr = addrLEditText.getText().toString();

                if (name.isEmpty() || addrHStr.isEmpty() || addrLStr.isEmpty()) {
                    Toast.makeText(AddContactActivity.this, "Заполните все поля", Toast.LENGTH_SHORT).show();
                    return;
                }

                try {
                    // Преобразуем введенные данные в числовые значения
                    int addrH = parseByte(addrHStr);
                    int addrL = parseByte(addrLStr);

                    // Проверяем, что значения находятся в допустимом диапазоне (0-255)
                    if (addrH < 0 || addrH > 255 || addrL < 0 || addrL > 255) {
                        Toast.makeText(AddContactActivity.this, "Адрес должен быть в диапазоне 0x00-0xFF", Toast.LENGTH_SHORT).show();
                        return;
                    }

                    // Создаем новый контакт
                    Contact newContact = new Contact(name, addrH, addrL);

                    // Сохраняем контакт в SharedPreferences
                    saveContact(newContact);

                    Toast.makeText(AddContactActivity.this, "Контакт сохранен", Toast.LENGTH_SHORT).show();
                    finish(); // Закрываем активность после сохранения
                } catch (NumberFormatException e) {
                    Toast.makeText(AddContactActivity.this, "Некорректный формат адреса", Toast.LENGTH_SHORT).show();
                }
            }
        });
    }

    // Метод для преобразования строки в байт (HEX или DEC)
    private int parseByte(String input) throws NumberFormatException {
        if (input.startsWith("0x") || input.startsWith("0X")) {
            // Шестнадцатеричный формат
            return Integer.parseInt(input.substring(2), 16);
        } else {
            // Десятичный формат
            return Integer.parseInt(input);
        }
    }

    // Метод для сохранения контакта в SharedPreferences
    private void saveContact(Contact contact) {
        SharedPreferences prefs = getSharedPreferences("contacts", MODE_PRIVATE);
        SharedPreferences.Editor editor = prefs.edit();

        // Загружаем существующие контакты
        List<Contact> contacts = loadContacts();

        // Добавляем новый контакт
        contacts.add(contact);

        // Сохраняем обновленный список контактов
        Gson gson = new Gson();
        String json = gson.toJson(contacts);
        editor.putString("contact_list", json);
        editor.apply();
    }

    // Метод для загрузки контактов из SharedPreferences
    private List<Contact> loadContacts() {
        SharedPreferences prefs = getSharedPreferences("contacts", MODE_PRIVATE);
        String json = prefs.getString("contact_list", null);

        if (json == null) {
            return new ArrayList<>();
        }

        Gson gson = new Gson();
        Type type = new TypeToken<List<Contact>>() {}.getType();
        return gson.fromJson(json, type);
    }
}