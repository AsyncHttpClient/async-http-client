package org.asynchttpclient.channel;

import java.net.InetSocketAddress;

/**
 * this class is a holder for the InetSocketAddress used by request
 *
 * @author wuguangkuo
 * @create 2019-04-16 16:32
 **/
public class RealConnection {

    private InetSocketAddress localAddress;
    private InetSocketAddress remoteAddress;

    public RealConnection(InetSocketAddress localAddress, InetSocketAddress remoteAddress) {
        this.localAddress = localAddress;
        this.remoteAddress = remoteAddress;
    }

    InetSocketAddress getLocalAddress() {
        return localAddress;
    }

    InetSocketAddress getRemoteAddress() {
        return remoteAddress;
    }
}
