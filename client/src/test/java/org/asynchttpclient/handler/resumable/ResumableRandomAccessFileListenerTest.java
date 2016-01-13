package org.asynchttpclient.handler.resumable;

import static org.mockito.Mockito.*;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;

import org.powermock.api.mockito.PowerMockito;
import org.testng.annotations.Test;

public class ResumableRandomAccessFileListenerTest {

    @Test
    public void testOnBytesReceivedBufferHasArray() throws IOException {
        RandomAccessFile file = PowerMockito.mock(RandomAccessFile.class);
        ResumableRandomAccessFileListener listener = new ResumableRandomAccessFileListener(file);
        byte[] array = new byte[] { 1, 2, 23, 33 };
        ByteBuffer buf = ByteBuffer.wrap(array);
        listener.onBytesReceived(buf);
        verify(file).write(array, 0, 4);
    }

    @Test
    public void testOnBytesReceivedBufferHasNoArray() throws IOException {
        RandomAccessFile file = PowerMockito.mock(RandomAccessFile.class);
        ResumableRandomAccessFileListener listener = new ResumableRandomAccessFileListener(file);

        byte[] byteArray = new byte[] { 1, 2, 23, 33 };
        ByteBuffer buf = ByteBuffer.allocateDirect(4);
        buf.put(byteArray);
        buf.flip();
        listener.onBytesReceived(buf);
        verify(file).write(byteArray);
    }

}
