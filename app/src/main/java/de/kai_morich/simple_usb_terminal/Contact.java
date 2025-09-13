package de.kai_morich.simple_usb_terminal;

import android.os.Parcel;
import android.os.Parcelable;

public class Contact implements Parcelable {
    private String name;
    private int highByte;
    private int lowByte;

    public Contact(String name, int highByte, int lowByte) {
        this.name = name;
        this.highByte = highByte;
        this.lowByte = lowByte;
    }

    protected Contact(Parcel in) {
        name = in.readString();
        highByte = in.readInt();
        lowByte = in.readInt();
    }

    public static final Creator<Contact> CREATOR = new Creator<Contact>() {
        @Override
        public Contact createFromParcel(Parcel in) {
            return new Contact(in);
        }

        @Override
        public Contact[] newArray(int size) {
            return new Contact[size];
        }
    };

    public String getName() {
        return name;
    }

    public int getHighByte() {
        return highByte;
    }

    public int getLowByte() {
        return lowByte;
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeString(name);
        dest.writeInt(highByte);
        dest.writeInt(lowByte);
    }
}