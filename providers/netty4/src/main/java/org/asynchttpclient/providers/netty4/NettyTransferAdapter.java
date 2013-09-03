package org.asynchttpclient.providers.netty4;

import io.netty.buffer.ByteBuf;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicInteger;

import org.asynchttpclient.FluentCaseInsensitiveStringsMap;
import org.asynchttpclient.listener.TransferCompletionHandler;

class NettyTransferAdapter extends TransferCompletionHandler.TransferAdapter {

    private final ByteBuf content;
    private final FileInputStream file;
    private AtomicInteger byteRead = new AtomicInteger(0);

    public NettyTransferAdapter(FluentCaseInsensitiveStringsMap headers, ByteBuf content, File file) throws IOException {
        super(headers);
        this.content = content;
        if (file != null) {
            this.file = new FileInputStream(file);
        } else {
            this.file = null;
        }
    }

    @Override
    public void getBytes(byte[] bytes) {
        if (content.writableBytes() != 0) {
            content.getBytes(byteRead.getAndAdd(bytes.length), bytes);
        } else if (file != null) {
            try {
                byteRead.getAndAdd(file.read(bytes));
            } catch (IOException e) {
                NettyAsyncHttpProvider.LOGGER.error(e.getMessage(), e);
            }
        }
    }
}