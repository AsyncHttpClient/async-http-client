package org.asynchttpclient.netty.util;

import static org.testng.Assert.assertEquals;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;

import org.asynchttpclient.util.ByteBufUtils;
import org.testng.annotations.Test;

public class ByteBufUtilsTest {

    @Test
    public void testByteBuf2BytesHasBackingArray() {
        byte[] input = "testdata".getBytes();
        ByteBuf inputBuf = Unpooled.copiedBuffer(input);
        byte[] output = ByteBufUtils.byteBuf2Bytes(inputBuf);
        assertEquals(output, input, "The bytes returned by byteBuf2Bytes do not match the bytes in the ByteBuf parameter");
    }

    @Test
    public void testByteBuf2BytesNoBackingArray() {
        ByteBuf inputBuf = Unpooled.directBuffer();
        byte[] inputBytes = "testdata".getBytes();
        inputBuf.writeBytes(inputBytes);
        byte[] output = ByteBufUtils.byteBuf2Bytes(inputBuf);
        assertEquals(output, inputBytes, "The bytes returned by byteBuf2Bytes do not match the bytes in the ByteBuf parameter");
    }
}
