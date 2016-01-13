/*
 * Copyright 2015 The Netty Project
 *
 * The Netty Project licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */
package io.netty.handler.codec.dns;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.handler.codec.UnsupportedMessageTypeException;
import io.netty.util.internal.StringUtil;

/**
 * The default {@link DnsRecordEncoder} implementation.
 *
 * @see DefaultDnsRecordDecoder
 */
public class DefaultDnsRecordEncoder implements DnsRecordEncoder {

    /**
     * Creates a new instance.
     */
    protected DefaultDnsRecordEncoder() { }

    @Override
    public final void encodeQuestion(DnsQuestion question, ByteBuf out) throws Exception {
        encodeName(question.name(), out);
        out.writeShort(question.type().intValue());
        out.writeShort(question.dnsClass());
    }

    @Override
    public void encodeRecord(DnsRecord record, ByteBuf out) throws Exception {
        if (record instanceof DnsQuestion) {
            encodeQuestion((DnsQuestion) record, out);
        } else if (record instanceof DnsRawRecord) {
            encodeRawRecord((DnsRawRecord) record, out);
        } else {
            throw new UnsupportedMessageTypeException(StringUtil.simpleClassName(record));
        }
    }

    private void encodeRawRecord(DnsRawRecord record, ByteBuf out) throws Exception {
        encodeName(record.name(), out);

        out.writeShort(record.type().intValue());
        out.writeShort(record.dnsClass());
        out.writeInt((int) record.timeToLive());

        ByteBuf content = record.content();
        int contentLen = content.readableBytes();

        out.writeShort(contentLen);
        out.writeBytes(content, content.readerIndex(), contentLen);
    }

    protected void encodeName(String name, ByteBuf buf) throws Exception {
        String[] parts = StringUtil.split(name, '.');
        for (String part: parts) {
            final int partLen = part.length();
            if (partLen == 0) {
                continue;
            }
            buf.writeByte(partLen);
            ByteBufUtil.writeAscii(buf, part);
        }
        buf.writeByte(0); // marks end of name field
    }
}
