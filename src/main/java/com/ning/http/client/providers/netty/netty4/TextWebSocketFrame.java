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
/*
 * Copyright 2010 Red Hat, Inc.
 *
 * Red Hat licenses this file to you under the Apache License, version 2.0
 * (the "License"); you may not use this file except in compliance with the
 * License.  You may obtain a copy of the License at:
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.  See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */
package com.ning.http.client.providers.netty.netty4;

import org.jboss.netty.buffer.ChannelBuffer;
import org.jboss.netty.buffer.ChannelBuffers;
import org.jboss.netty.util.CharsetUtil;

/**
 * Web Socket text frame with assumed UTF-8 encoding
 * 
 * @author <a href="http://www.veebsbraindump.com/">Vibul Imtarnasan</a>
 * 
 */
public class TextWebSocketFrame extends WebSocketFrame {

    /**
     * Creates a new empty text frame.
     */
    public TextWebSocketFrame() {
        this.setBinaryData(ChannelBuffers.EMPTY_BUFFER);
    }

    /**
     * Creates a new text frame with the specified text string. The final
     * fragment flag is set to true.
     * 
     * @param text
     *            String to put in the frame
     */
    public TextWebSocketFrame(String text) {
        if (text == null || text.equalsIgnoreCase("")) {
            this.setBinaryData(ChannelBuffers.EMPTY_BUFFER);
        } else {
            this.setBinaryData(ChannelBuffers.copiedBuffer(text, CharsetUtil.UTF_8));
        }
    }

    /**
     * Creates a new text frame with the specified binary data. The final
     * fragment flag is set to true.
     * 
     * @param binaryData
     *            the content of the frame. Must be UTF-8 encoded
     */
    public TextWebSocketFrame(ChannelBuffer binaryData) {
        this.setBinaryData(binaryData);
    }

    /**
     * Creates a new text frame with the specified text string. The final
     * fragment flag is set to true.
     * 
     * @param finalFragment
     *            flag indicating if this frame is the final fragment
     * @param rsv
     *            reserved bits used for protocol extensions
     * @param text
     *            String to put in the frame
     */
    public TextWebSocketFrame(boolean finalFragment, int rsv, String text) {
        this.setFinalFragment(finalFragment);
        this.setRsv(rsv);
        if (text == null || text.equalsIgnoreCase("")) {
            this.setBinaryData(ChannelBuffers.EMPTY_BUFFER);
        } else {
            this.setBinaryData(ChannelBuffers.copiedBuffer(text, CharsetUtil.UTF_8));
        }
    }

    /**
     * Creates a new text frame with the specified binary data. The final
     * fragment flag is set to true.
     * 
     * @param finalFragment
     *            flag indicating if this frame is the final fragment
     * @param rsv
     *            reserved bits used for protocol extensions
     * @param binaryData
     *            the content of the frame. Must be UTF-8 encoded
     */
    public TextWebSocketFrame(boolean finalFragment, int rsv, ChannelBuffer binaryData) {
        this.setFinalFragment(finalFragment);
        this.setRsv(rsv);
        this.setBinaryData(binaryData);
    }

    /**
     * Returns the text data in this frame
     */
    public String getText() {
        if (this.getBinaryData() == null) {
            return null;
        }
        return this.getBinaryData().toString(CharsetUtil.UTF_8);
    }

    /**
     * Sets the string for this frame
     * 
     * @param text
     *            text to store
     */
    public void setText(String text) {
        if (text == null) {
            throw new NullPointerException("text");
        }
        this.setBinaryData(ChannelBuffers.copiedBuffer(text, CharsetUtil.UTF_8));
    }

    @Override
    public String toString() {
        return getClass().getSimpleName() + "(text: " + getText() + ')';
    }
}
