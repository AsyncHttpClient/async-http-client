package org.asynchttpclient.proxy;

public enum ProxyType {
    HTTP,
    SOCKS_V4 {
        @Override
        public boolean isSocks() {
            return true;
        }
    },
    SOCKS_V5 {
        @Override
        public boolean isSocks() {
            return true;
        }
    };

    public boolean isSocks() {
        return false;
    }
}
