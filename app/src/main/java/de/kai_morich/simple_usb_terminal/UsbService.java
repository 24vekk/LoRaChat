package de.kai_morich.simple_usb_terminal;

import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbEndpoint;
import android.hardware.usb.UsbInterface;
import android.hardware.usb.UsbManager;
import android.util.Log;
import java.io.IOException;

public class UsbService {

    private static final String TAG = "UsbService";
    private UsbManager usbManager;
    private UsbDevice usbDevice;
    private UsbDeviceConnection usbConnection;
    private UsbEndpoint endpointIn;
    private UsbEndpoint endpointOut;

    public UsbService(UsbManager usbManager, UsbDevice usbDevice) {
        this.usbManager = usbManager;
        this.usbDevice = usbDevice;
        initialize();
    }

    private void initialize() {
        UsbInterface usbInterface = usbDevice.getInterface(0);
        usbConnection = usbManager.openDevice(usbDevice);

        if (usbConnection != null) {
            usbConnection.claimInterface(usbInterface, true);
            endpointIn = usbInterface.getEndpoint(0);
            endpointOut = usbInterface.getEndpoint(1);
        }
    }

    public void write(byte[] data) {
        if (usbConnection != null && endpointOut != null) {
            usbConnection.bulkTransfer(endpointOut, data, data.length, 0);
        }
    }

    public byte[] read() {
        byte[] buffer = new byte[1024];
        if (usbConnection != null && endpointIn != null) {
            int length = usbConnection.bulkTransfer(endpointIn, buffer, buffer.length, 0);
            if (length > 0) {
                byte[] result = new byte[length];
                System.arraycopy(buffer, 0, result, 0, length);
                return result;
            }
        }
        return null;
    }

    public void close() {
        if (usbConnection != null) {
            usbConnection.close();
        }
    }
}