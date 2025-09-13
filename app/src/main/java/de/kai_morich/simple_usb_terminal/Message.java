package de.kai_morich.simple_usb_terminal;

public class Message {
    private String nickName;
    private String message;

    public Message(String nickName, String message) {
        this.nickName = nickName;
        this.message = message;
    }

    public String getNickName() {
        return nickName;
    }

    public void setNickName(String nickName) {
        this.nickName = nickName;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }
}