package de.kai_morich.simple_usb_terminal;

import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.util.Log;

import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;

public class ContactsActivity extends AppCompatActivity {

    private RecyclerView contactsRecyclerView;
    private ContactsAdapter contactsAdapter;
    private List<Contact> contactList;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_contacts);

        // Инициализация RecyclerView
        contactsRecyclerView = findViewById(R.id.contactsRecyclerView);
        contactsRecyclerView.setLayoutManager(new LinearLayoutManager(this));

        // Загрузка списка контактов из SharedPreferences
        contactList = loadContacts();

        // Настройка адаптера
        contactsAdapter = new ContactsAdapter(contactList, contact -> {
            Log.d("zxc", "Контакт выбран: " + contact.getName());
            Intent resultIntent = new Intent();
            resultIntent.putExtra("selected_contact", contact);
            setResult(RESULT_OK, resultIntent);
            finish();
        });
        contactsRecyclerView.setAdapter(contactsAdapter);
    }

    // Метод для загрузки контактов из SharedPreferences
    private List<Contact> loadContacts() {
        SharedPreferences prefs = getSharedPreferences("contacts", MODE_PRIVATE);
        String json = prefs.getString("contact_list", null);

        if (json == null) {
            return new ArrayList<>(); // Возвращаем пустой список, если контактов нет
        }

        Gson gson = new Gson();
        Type type = new TypeToken<List<Contact>>() {}.getType();
        return gson.fromJson(json, type);
    }
}