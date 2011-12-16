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

/**
 * Base class for web socket frames
 * 
 * @author <a href="http://www.jboss.org/netty/">The Netty Project</a>
 */
public abstract class WebSocketFrame {

    /**
     * Flag to indicate if this frame is the final fragment in a message. The
     * first fragment (frame) may also be the final fragment.
     */
    private boolean finalFragment = true;

    /**
     * RSV1, RSV2, RSV3 used for extensions
     */
    private int rsv = 0;

    /**
     * Contents of this frame
     */
    private ChannelBuffer binaryData;

    /**
     * Returns binary data
     */
    public ChannelBuffer getBinaryData() {
        return binaryData;
    }

    /**
     * Sets the binary data for this frame
     */
    public void setBinaryData(ChannelBuffer binaryData) {
        this.binaryData = binaryData;
    }

    /**
     * Flag to indicate if this frame is the final fragment in a message. The
     * first fragment (frame) may also be the final fragment.
     */
    public boolean isFinalFragment() {
        return finalFragment;
    }

    public void setFinalFragment(boolean finalFragment) {
        this.finalFragment = finalFragment;
    }

    /**
     * Bits used for extensions to the standard.
     */
    public int getRsv() {
        return rsv;
    }

    public void setRsv(int rsv) {
        this.rsv = rsv;
    }

}
