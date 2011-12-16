/*
 * Copyright (c) 2010-2011 Sonatype, Inc. All rights reserved.
 *
 * This program is licensed to you under the Apache License Version 2.0,
 * and you may not use this file except in compliance with the Apache License Version 2.0.
 * You may obtain a copy of the Apache License Version 2.0 at http://www.apache.org/licenses/LICENSE-2.0.
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the Apache License Version 2.0 is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the Apache License Version 2.0 for the specific language governing permissions and limitations there under.
 */
// (BSD License: http://www.opensource.org/licenses/bsd-license)
//
// Copyright (c) 2011, Joe Walnes and contributors
// All rights reserved.
//
// Redistribution and use in source and binary forms, with or
// without modification, are permitted provided that the
// following conditions are met:
//
// * Redistributions of source code must retain the above
// copyright notice, this list of conditions and the
// following disclaimer.
//
// * Redistributions in binary form must reproduce the above
// copyright notice, this list of conditions and the
// following disclaimer in the documentation and/or other
// materials provided with the distribution.
//
// * Neither the name of the Webbit nor the names of
// its contributors may be used to endorse or promote products
// derived from this software without specific prior written
// permission.
//
// THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND
// CONTRIBUTORS "AS IS" AND ANY EXPRESS OR IMPLIED WARRANTIES,
// INCLUDING, BUT NOT LIMITED TO, THE IMPLIED WARRANTIES OF
// MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
// DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR
// CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT,
// INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
// (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE
// GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR
// BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF
// LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
// (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT
// OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE
// POSSIBILITY OF SUCH DAMAGE.

package com.ning.http.client.providers.netty.netty4;

import org.jboss.netty.buffer.ChannelBuffer;
import org.jboss.netty.buffer.ChannelBuffers;
import org.jboss.netty.channel.Channel;
import org.jboss.netty.channel.ChannelFutureListener;
import org.jboss.netty.channel.ChannelHandlerContext;
import org.jboss.netty.handler.codec.frame.CorruptedFrameException;
import org.jboss.netty.handler.codec.frame.TooLongFrameException;
import org.jboss.netty.handler.codec.replay.ReplayingDecoder;
import org.jboss.netty.logging.InternalLogger;
import org.jboss.netty.logging.InternalLoggerFactory;

/**
 * Decodes a web socket frame from wire protocol version 8 format. This code was
 * forked from <a href="https://github.com/joewalnes/webbit">webbit</a> and
 * modified.
 * 
 * @author Aslak Hellesøy
 * @author <a href="http://www.veebsbraindump.com/">Vibul Imtarnasan</a>
 */
public class WebSocket08FrameDecoder extends ReplayingDecoder<WebSocket08FrameDecoder.State> {

    private static final InternalLogger logger = InternalLoggerFactory.getInstance(WebSocket08FrameDecoder.class);

    private static final byte OPCODE_CONT = 0x0;
    private static final byte OPCODE_TEXT = 0x1;
    private static final byte OPCODE_BINARY = 0x2;
    private static final byte OPCODE_CLOSE = 0x8;
    private static final byte OPCODE_PING = 0x9;
    private static final byte OPCODE_PONG = 0xA;

    private UTF8Output fragmentedFramesText = null;
    private int fragmentedFramesCount = 0;

    private boolean frameFinalFlag;
    private int frameRsv;
    private int frameOpcode;
    private long framePayloadLength;
    private ChannelBuffer framePayload = null;
    private int framePayloadBytesRead = 0;
    private ChannelBuffer maskingKey;

    private boolean allowExtensions = false;
    private boolean maskedPayload = false;
    private boolean receivedClosingHandshake = false;

    public enum State {
        FRAME_START, MASKING_KEY, PAYLOAD, CORRUPT
    }

    /**
     * Constructor
     * 
     * @param maskedPayload
     *            Web socket servers must set this to true processed incoming
     *            masked payload. Client implementations must set this to false.
     * @param allowExtensions
     *            Flag to allow reserved extension bits to be used or not
     */
    public WebSocket08FrameDecoder(boolean maskedPayload, boolean allowExtensions) {
        super(State.FRAME_START);
        this.maskedPayload = maskedPayload;
        this.allowExtensions = allowExtensions;
    }

    @Override
    protected Object decode(ChannelHandlerContext ctx, Channel channel, ChannelBuffer buffer, State state) throws Exception {

        // Discard all data received if closing handshake was received before.
        if (receivedClosingHandshake) {
            buffer.skipBytes(actualReadableBytes());
            return null;
        }

        switch (state) {
        case FRAME_START:
            framePayloadBytesRead = 0;
            framePayloadLength = -1;
            framePayload = null;

            // FIN, RSV, OPCODE
            byte b = buffer.readByte();
            frameFinalFlag = (b & 0x80) != 0;
            frameRsv = (b & 0x70) >> 4;
            frameOpcode = (b & 0x0F);

            logger.debug("Decoding WebSocket Frame opCode=" + frameOpcode);

            // MASK, PAYLOAD LEN 1
            b = buffer.readByte();
            boolean frameMasked = (b & 0x80) != 0;
            int framePayloadLen1 = (b & 0x7F);

            if (frameRsv != 0 && !this.allowExtensions) {
                protocolViolation(channel, "RSV != 0 and no extension negotiated, RSV:" + frameRsv);
                return null;
            }

            if (this.maskedPayload && !frameMasked) {
                protocolViolation(channel, "unmasked client to server frame");
                return null;
            }
            if (frameOpcode > 7) { // control frame (have MSB in opcode set)

                // control frames MUST NOT be fragmented
                if (!frameFinalFlag) {
                    protocolViolation(channel, "fragmented control frame");
                    return null;
                }

                // control frames MUST have payload 125 octets or less
                if (framePayloadLen1 > 125) {
                    protocolViolation(channel, "control frame with payload length > 125 octets");
                    return null;
                }

                // check for reserved control frame opcodes
                if (!(frameOpcode == OPCODE_CLOSE || frameOpcode == OPCODE_PING || frameOpcode == OPCODE_PONG)) {
                    protocolViolation(channel, "control frame using reserved opcode " + frameOpcode);
                    return null;
                }

                // close frame : if there is a body, the first two bytes of the
                // body MUST be a 2-byte
                // unsigned integer (in network byte order) representing a
                // status code
                if (frameOpcode == 8 && framePayloadLen1 == 1) {
                    protocolViolation(channel, "received close control frame with payload len 1");
                    return null;
                }
            } else { // data frame
                // check for reserved data frame opcodes
                if (!(frameOpcode == OPCODE_CONT || frameOpcode == OPCODE_TEXT || frameOpcode == OPCODE_BINARY)) {
                    protocolViolation(channel, "data frame using reserved opcode " + frameOpcode);
                    return null;
                }

                // check opcode vs message fragmentation state 1/2
                if (fragmentedFramesCount == 0 && frameOpcode == OPCODE_CONT) {
                    protocolViolation(channel, "received continuation data frame outside fragmented message");
                    return null;
                }

                // check opcode vs message fragmentation state 2/2
                if (fragmentedFramesCount != 0 && frameOpcode != OPCODE_CONT && frameOpcode != OPCODE_PING) {
                    protocolViolation(channel, "received non-continuation data frame while inside fragmented message");
                    return null;
                }
            }

            if (framePayloadLen1 == 126) {
                framePayloadLength = buffer.readUnsignedShort();
                if (framePayloadLength < 126) {
                    protocolViolation(channel, "invalid data frame length (not using minimal length encoding)");
                    return null;
                }
            } else if (framePayloadLen1 == 127) {
                framePayloadLength = buffer.readLong();
                // TODO: check if it's bigger than 0x7FFFFFFFFFFFFFFF, Maybe
                // just check if it's negative?

                if (framePayloadLength < 65536) {
                    protocolViolation(channel, "invalid data frame length (not using minimal length encoding)");
                    return null;
                }
            } else {
                framePayloadLength = framePayloadLen1;
            }

            // logger.debug("Frame length=" + framePayloadLength);
            checkpoint(State.MASKING_KEY);
        case MASKING_KEY:
            if (this.maskedPayload) {
                maskingKey = buffer.readBytes(4);
            }
            checkpoint(State.PAYLOAD);
        case PAYLOAD:
            // Some times, the payload may not be delivered in 1 nice packet
            // We need to accumulate the data until we have it all
            int rbytes = actualReadableBytes();
            ChannelBuffer payloadBuffer = null;

            int willHaveReadByteCount = framePayloadBytesRead + rbytes;
            // logger.debug("Frame rbytes=" + rbytes + " willHaveReadByteCount="
            // + willHaveReadByteCount + " framePayloadLength=" +
            // framePayloadLength);
            if (willHaveReadByteCount == framePayloadLength) {
                // We have all our content so proceed to process
                payloadBuffer = buffer.readBytes(rbytes);
            } else if (willHaveReadByteCount < framePayloadLength) {
                // We don't have all our content so accumulate payload.
                // Returning null means we will get called back
                payloadBuffer = buffer.readBytes(rbytes);
                if (framePayload == null) {
                    framePayload = channel.getConfig().getBufferFactory().getBuffer(toFrameLength(framePayloadLength));
                }
                framePayload.writeBytes(payloadBuffer);
                framePayloadBytesRead = framePayloadBytesRead + rbytes;

                // Return null to wait for more bytes to arrive
                return null;
            } else if (willHaveReadByteCount > framePayloadLength) {
                // We have more than what we need so read up to the end of frame
                // Leave the remainder in the buffer for next frame
                payloadBuffer = buffer.readBytes(toFrameLength(framePayloadLength - framePayloadBytesRead));
            }

            // Now we have all the data, the next checkpoint must be the next
            // frame
            checkpoint(State.FRAME_START);

            // Take the data that we have in this packet
            if (framePayload == null) {
                framePayload = payloadBuffer;
            } else {
                framePayload.writeBytes(payloadBuffer);
            }

            // Unmask data if needed
            if (this.maskedPayload) {
                unmask(framePayload);
            }

            // Processing for fragmented messages
            String aggregatedText = null;
            if (frameFinalFlag) {
                // Final frame of the sequence. Apparently ping frames are
                // allowed in the middle of a fragmented message
                if (frameOpcode != OPCODE_PING) {
                    fragmentedFramesCount = 0;

                    // Check text for UTF8 correctness
                    if (frameOpcode == OPCODE_TEXT || fragmentedFramesText != null) {
                        // Check UTF-8 correctness for this payload
                        checkUTF8String(channel, framePayload.array());

                        // This does a second check to make sure UTF-8
                        // correctness for entire text message
                        aggregatedText = fragmentedFramesText.toString();

                        fragmentedFramesText = null;
                    }
                }
            } else {
                // Not final frame so we can expect more frames in the
                // fragmented sequence
                if (fragmentedFramesCount == 0) {
                    // First text or binary frame for a fragmented set
                    fragmentedFramesText = null;
                    if (frameOpcode == OPCODE_TEXT) {
                        checkUTF8String(channel, framePayload.array());
                    }
                } else {
                    // Subsequent frames - only check if init frame is text
                    if (fragmentedFramesText != null) {
                        checkUTF8String(channel, framePayload.array());
                    }
                }

                // Increment counter
                fragmentedFramesCount++;
            }

            // Return the frame
            if (frameOpcode == OPCODE_TEXT) {
                return new TextWebSocketFrame(frameFinalFlag, frameRsv, framePayload);
            } else if (frameOpcode == OPCODE_BINARY) {
                return new BinaryWebSocketFrame(frameFinalFlag, frameRsv, framePayload);
            } else if (frameOpcode == OPCODE_PING) {
                return new PingWebSocketFrame(frameFinalFlag, frameRsv, framePayload);
            } else if (frameOpcode == OPCODE_PONG) {
                return new PongWebSocketFrame(frameFinalFlag, frameRsv, framePayload);
            } else if (frameOpcode == OPCODE_CONT) {
                return new ContinuationWebSocketFrame(frameFinalFlag, frameRsv, framePayload, aggregatedText);
            } else if (frameOpcode == OPCODE_CLOSE) {
                this.receivedClosingHandshake = true;
                return new CloseWebSocketFrame(frameFinalFlag, frameRsv);
            } else {
                throw new UnsupportedOperationException("Cannot decode web socket frame with opcode: " + frameOpcode);
            }
        case CORRUPT:
            // If we don't keep reading Netty will throw an exception saying
            // we can't return null if no bytes read and state not changed.
            buffer.readByte();
            return null;
        default:
            throw new Error("Shouldn't reach here.");
        }
    }

    private void unmask(ChannelBuffer frame) {
        byte[] bytes = frame.array();
        for (int i = 0; i < bytes.length; i++) {
            frame.setByte(i, frame.getByte(i) ^ maskingKey.getByte(i % 4));
        }
    }

    private void protocolViolation(Channel channel, String reason) throws CorruptedFrameException {
        checkpoint(State.CORRUPT);
        if (channel.isConnected()) {
            channel.write(ChannelBuffers.EMPTY_BUFFER).addListener(ChannelFutureListener.CLOSE);
            channel.close().awaitUninterruptibly();
        }
        throw new CorruptedFrameException(reason);
    }

    private int toFrameLength(long l) throws TooLongFrameException {
        if (l > Integer.MAX_VALUE) {
            throw new TooLongFrameException("Length:" + l);
        } else {
            return (int) l;
        }
    }

    private void checkUTF8String(Channel channel, byte[] bytes) throws CorruptedFrameException {
        try {
            // StringBuilder sb = new StringBuilder("UTF8 " + bytes.length +
            // " bytes: ");
            // for (byte b : bytes) {
            // sb.append(Integer.toHexString(b)).append(" ");
            // }
            // logger.debug(sb.toString());

            if (fragmentedFramesText == null) {
                fragmentedFramesText = new UTF8Output(bytes);
            } else {
                fragmentedFramesText.write(bytes);
            }
        } catch (UTF8Exception ex) {
            protocolViolation(channel, "invalid UTF-8 bytes");
        }
    }
}
