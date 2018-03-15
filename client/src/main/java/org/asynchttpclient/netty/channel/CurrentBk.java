package org.asynchttpclient.netty.channel;

public class CurrentBk {

    private static int bkId = 0;

    public static void setBkId(int id) {
        bkId = id;
    }

    public static int getBkId() {
        return bkId;
    }
}
