package de.kai_morich.simple_usb_terminal;

import android.content.Context;
import android.content.SharedPreferences;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;
import com.google.gson.Gson;
import java.lang.reflect.Type;
import java.util.List;

public class ContactsAdapter extends RecyclerView.Adapter<ContactsAdapter.ContactViewHolder> {

    private List<Contact> contactList;
    private OnContactClickListener onContactClickListener;
    private Context context;

    public interface OnContactClickListener {
        void onContactClick(Contact contact);
    }

    public ContactsAdapter(List<Contact> contactList, OnContactClickListener onContactClickListener) {
        this.contactList = contactList;
        this.onContactClickListener = onContactClickListener;
    }

    @NonNull
    @Override
    public ContactViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        context = parent.getContext();
        View view = LayoutInflater.from(context).inflate(R.layout.item_contact, parent, false);
        return new ContactViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ContactViewHolder holder, int position) {
        Contact contact = contactList.get(position);
        holder.nameTextView.setText(contact.getName());
        holder.addressTextView.setText(String.format("ADDR_H: 0x%02X, ADDR_L: 0x%02X", contact.getHighByte(), contact.getLowByte()));

        // Обработка нажатия на элемент списка
        holder.itemView.setOnClickListener(v -> {
            if (onContactClickListener != null) {
                onContactClickListener.onContactClick(contact);
            }
        });

        // Обработка нажатия на кнопку удаления
        holder.deleteButton.setOnClickListener(v -> {
            removeContact(position);
        });
    }

    @Override
    public int getItemCount() {
        return contactList.size();
    }

    // Метод для удаления контакта
    private void removeContact(int position) {
        if (position >= 0 && position < contactList.size()) {
            contactList.remove(position);
            notifyItemRemoved(position);
            saveContactsToSharedPreferences();
            Toast.makeText(context, "Контакт удален", Toast.LENGTH_SHORT).show();
        }
    }

    // Метод для сохранения списка контактов в SharedPreferences
    private void saveContactsToSharedPreferences() {
        SharedPreferences prefs = context.getSharedPreferences("contacts", Context.MODE_PRIVATE);
        SharedPreferences.Editor editor = prefs.edit();

        Gson gson = new Gson();
        String json = gson.toJson(contactList);
        editor.putString("contact_list", json);
        editor.apply();
    }

    static class ContactViewHolder extends RecyclerView.ViewHolder {
        TextView nameTextView;
        TextView addressTextView;
        Button deleteButton;

        public ContactViewHolder(@NonNull View itemView) {
            super(itemView);
            nameTextView = itemView.findViewById(R.id.nameTextView);
            addressTextView = itemView.findViewById(R.id.addressTextView);
            deleteButton = itemView.findViewById(R.id.deleteButton);
        }
    }
}