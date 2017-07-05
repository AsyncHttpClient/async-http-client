import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import io.netty.handler.codec.http.DefaultFullHttpRequest;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaderValues;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.util.AsciiString;

import java.util.Iterator;
import java.util.Map;

public class Foo {

    public static void main(String[] args) {
        HttpVersion httpVersion = HttpVersion.HTTP_1_0;
        HttpMethod method = HttpMethod.GET;
        ByteBuf buf = ByteBufAllocator.DEFAULT.buffer();
        ByteBuf content = Unpooled.EMPTY_BUFFER;
        // new DefaultFullHttpRequest(httpVersion,method, urlAddress,content);
        DefaultFullHttpRequest nettyHttpRequest = new DefaultFullHttpRequest(httpVersion, method, "http://localhost/aaa/bbb", content);
        // 头里加入host信息
        nettyHttpRequest.headers().add(HttpHeaderNames.HOST, new AsciiString("192.168.3.52:8081"));
        nettyHttpRequest.headers().add(HttpHeaderNames.USER_AGENT, new AsciiString("Mozilla/5.0 (Macintosh; Intel Mac OS X 10.12; rv:53.0) Gecko/20100101 Firefox/53.0"));
        nettyHttpRequest.headers().add(HttpHeaderNames.CONTENT_TYPE, new AsciiString("text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8"));
        nettyHttpRequest.headers().add(HttpHeaderNames.ACCEPT_LANGUAGE, new AsciiString("zh-CN,zh;q=0.8,en-US;q=0.5,en;q=0.3"));
        nettyHttpRequest.headers().add(HttpHeaderNames.ACCEPT_ENCODING, new AsciiString("gzip, deflate"));
        nettyHttpRequest.headers().add(HttpHeaderNames.CONTENT_LENGTH, HttpHeaderValues.ZERO);
        nettyHttpRequest.headers().add(HttpHeaderNames.CONNECTION, HttpHeaderValues.KEEP_ALIVE);
        try {
            long st = System.nanoTime();
            encodeHeaders(nettyHttpRequest.headers(), buf);
            long et = System.nanoTime();
            System.out.println("cost time :" + ((et - st) / 1_000_000));
        } catch (Exception e) {

        }
    }

    protected static void encodeHeaders(HttpHeaders headers, ByteBuf buf) throws Exception {
        Iterator<Map.Entry<CharSequence, CharSequence>> iter = headers.iteratorCharSequence();
        while (iter.hasNext()) {
            Map.Entry<CharSequence, CharSequence> header = iter.next();
            long st = System.nanoTime();
            encoderHeader(header.getKey(), header.getValue(), buf);
            long et = System.nanoTime();
            System.out.println("encode header " + header.getKey() + " value " + header.getValue() + " cost time :" + ((et - st) / 1_000_000));
        }
    }

    public static void encoderHeader(CharSequence name, CharSequence value, ByteBuf buf) throws Exception {
        final int nameLen = name.length();
        final int valueLen = value.length();
        final int entryLen = nameLen + valueLen + 4;
        buf.ensureWritable(entryLen);
        int offset = buf.writerIndex();
        writeAscii(buf, offset, name, nameLen);
        offset += nameLen;
        buf.setByte(offset++, ':');
        buf.setByte(offset++, ' ');
        writeAscii(buf, offset, value, valueLen);
        offset += valueLen;
        buf.setByte(offset++, '\r');
        buf.setByte(offset++, '\n');
        buf.writerIndex(offset);
    }

    private static void writeAscii(ByteBuf buf, int offset, CharSequence value, int valueLen) {
        if (value instanceof AsciiString) {
            long st = System.nanoTime();
            ByteBufUtil.copy((AsciiString) value, 0, buf, offset, valueLen);
            long et = System.nanoTime();
            // System.out.println("writeAscii header "+value+" value  cost time :"+(et-st));

        } else {

            writeCharSequence(buf, offset, value, valueLen);

        }
    }

    private static void writeCharSequence(ByteBuf buf, int offset, CharSequence value, int valueLen) {
        for (int i = 0; i < valueLen; ++i) {
            buf.setByte(offset++, c2b(value.charAt(i)));
        }
    }
}
