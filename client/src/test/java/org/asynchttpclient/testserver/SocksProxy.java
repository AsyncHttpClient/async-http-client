/*
 * SOCKS Proxy in JAVA
 * By Gareth Owen
 * drgowen@gmail.com
 * MIT Licence
 */

package org.asynchttpclient.testserver;

// NOTES : LISTENS ON PORT 8000

import java.io.*;
import java.net.*;
import java.nio.ByteBuffer;
import java.nio.channels.*;
import java.util.ArrayList;
import java.util.Set;

public class SocksProxy {

  private static ArrayList<SocksClient> clients = new ArrayList<>();

  public SocksProxy(int runningTime) throws IOException {
    ServerSocketChannel socks = ServerSocketChannel.open();
    socks.socket().bind(new InetSocketAddress(8000));
    socks.configureBlocking(false);
    Selector select = Selector.open();
    socks.register(select, SelectionKey.OP_ACCEPT);

    int lastClients = clients.size();
    // select loop
    for (long end = System.currentTimeMillis() + runningTime; System.currentTimeMillis() < end; ) {
      select.select(5000);

      Set<SelectionKey> keys = select.selectedKeys();
      for (SelectionKey k : keys) {

        if (!k.isValid())
          continue;

        // new connection?
        if (k.isAcceptable() && k.channel() == socks) {
          // server socket
          SocketChannel csock = socks.accept();
          if (csock == null)
            continue;
          addClient(csock);
          csock.register(select, SelectionKey.OP_READ);
        } else if (k.isReadable()) {
          // new data on a client/remote socket
          for (int i = 0; i < clients.size(); i++) {
            SocksClient cl = clients.get(i);
            try {
              if (k.channel() == cl.client) // from client (e.g. socks client)
                cl.newClientData(select);
              else if (k.channel() == cl.remote) {  // from server client is connected to (e.g. website)
                cl.newRemoteData();
              }
            } catch (IOException e) { // error occurred - remove client
              cl.client.close();
              if (cl.remote != null)
                cl.remote.close();
              k.cancel();
              clients.remove(cl);
            }

          }
        }
      }

      // client timeout check
      for (int i = 0; i < clients.size(); i++) {
        SocksClient cl = clients.get(i);
        if ((System.currentTimeMillis() - cl.lastData) > 30000L) {
          cl.client.close();
          if (cl.remote != null)
            cl.remote.close();
          clients.remove(cl);
        }
      }
      if (clients.size() != lastClients) {
        System.out.println(clients.size());
        lastClients = clients.size();
      }
    }
  }

  // utility function
  private void addClient(SocketChannel s) {
    SocksClient cl;
    try {
      cl = new SocksClient(s);
    } catch (IOException e) {
      e.printStackTrace();
      return;
    }
    clients.add(cl);
  }

  // socks client class - one per client connection
  class SocksClient {
    SocketChannel client, remote;
    boolean connected;
    long lastData;

    SocksClient(SocketChannel c) throws IOException {
      client = c;
      client.configureBlocking(false);
      lastData = System.currentTimeMillis();
    }

    void newRemoteData() throws IOException {
      ByteBuffer buf = ByteBuffer.allocate(1024);
      if (remote.read(buf) == -1)
        throw new IOException("disconnected");
      lastData = System.currentTimeMillis();
      buf.flip();
      client.write(buf);
    }

    void newClientData(Selector selector) throws IOException {
      if (!connected) {
        ByteBuffer inbuf = ByteBuffer.allocate(512);
        if (client.read(inbuf) < 1)
          return;
        inbuf.flip();

        // read socks header
        int ver = inbuf.get();
        if (ver != 4) {
          throw new IOException("incorrect version" + ver);
        }
        int cmd = inbuf.get();

        // check supported command
        if (cmd != 1) {
          throw new IOException("incorrect version");
        }

        final int port = inbuf.getShort() & 0xffff;

        final byte ip[] = new byte[4];
        // fetch IP
        inbuf.get(ip);

        InetAddress remoteAddr = InetAddress.getByAddress(ip);

        while ((inbuf.get()) != 0); // username

        // hostname provided, not IP
        if (ip[0] == 0 && ip[1] == 0 && ip[2] == 0 && ip[3] != 0) { // host provided
          StringBuilder host = new StringBuilder();
          byte b;
          while ((b = inbuf.get()) != 0) {
            host.append(b);
          }
          remoteAddr = InetAddress.getByName(host.toString());
          System.out.println(host.toString() + remoteAddr);
        }

        remote = SocketChannel.open(new InetSocketAddress(remoteAddr, port));

        ByteBuffer out = ByteBuffer.allocate(20);
        out.put((byte) 0);
        out.put((byte) (remote.isConnected() ? 0x5a : 0x5b));
        out.putShort((short) port);
        out.put(remoteAddr.getAddress());
        out.flip();
        client.write(out);

        if (!remote.isConnected())
          throw new IOException("connect failed");

        remote.configureBlocking(false);
        remote.register(selector, SelectionKey.OP_READ);

        connected = true;
      } else {
        ByteBuffer buf = ByteBuffer.allocate(1024);
        if (client.read(buf) == -1)
          throw new IOException("disconnected");
        lastData = System.currentTimeMillis();
        buf.flip();
        remote.write(buf);
      }
    }
  }
}
