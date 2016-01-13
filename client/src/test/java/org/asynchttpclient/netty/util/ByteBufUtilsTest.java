package org.asynchttpclient.netty.util;

import static org.testng.Assert.assertEquals;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;

import java.util.Collections;
import java.util.LinkedList;
import java.util.List;

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

    @Test
    public void testByteBufs2BytesEmptyList() {
        byte[] output = ByteBufUtils.byteBufs2Bytes(Collections.emptyList());
        assertEquals(output, ByteBufUtils.EMPTY_BYTE_ARRAY,
                "When an empty list is passed to byteBufs2Bytes, an empty byte array should be returned");
    }

    @Test
    public void testByteBufs2BytesSize1List() {
        byte[] inputBytes = "testdata".getBytes();
        ByteBuf inputBuf = Unpooled.copiedBuffer(inputBytes);
        byte[] output = ByteBufUtils.byteBufs2Bytes(Collections.singletonList(inputBuf));
        assertEquals(output, inputBytes, "When a list of a single ByteBuf element is passed to byteBufs2Bytes,"
                + " the returned byte array should contain the bytes in that ByteBUf");
    }

    @Test
    public void testByteBufs2Bytes() {
        byte[] input1 = "testdata".getBytes();
        byte[] input2 = "testdata2".getBytes();
        byte[] input3 = "testdata3333".getBytes();

        List<ByteBuf> byteBufList = new LinkedList<>();
        byteBufList.add(Unpooled.copiedBuffer(input1));
        byteBufList.add(Unpooled.copiedBuffer(input2));
        byteBufList.add(Unpooled.copiedBuffer(input3));

        byte[] output = ByteBufUtils.byteBufs2Bytes(byteBufList);
        assertEquals(output.length, input1.length + input2.length + input3.length,
                "Returned bytes length should equal the sum of the parts");
    }

}
