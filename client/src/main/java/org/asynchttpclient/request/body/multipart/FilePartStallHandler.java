/*
 * Copyright (c) 2010-2012 Sonatype, Inc. All rights reserved.
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
package org.asynchttpclient.request.body.multipart;

import java.util.Timer;
import java.util.TimerTask;

/**
 * @author Gail Hernandez
 */
public class FilePartStallHandler extends TimerTask {

    private final long waitTime;
    private Timer timer;
    private volatile boolean failed;
    private volatile boolean written;

    public FilePartStallHandler(long waitTime, AbstractFilePart filePart) {
        this.waitTime = waitTime;
        failed = false;
        written = false;
    }

    public void completed() {
        if (waitTime > 0) {
            timer.cancel();
        }
    }

    public boolean isFailed() {
        return failed;
    }

    public void run() {
        if (!written) {
            failed = true;
            timer.cancel();
        }
        written = false;
    }

    public void start() {
        if (waitTime > 0) {
            timer = new Timer();
            timer.scheduleAtFixedRate(this, waitTime, waitTime);
        }
    }

    public void writeHappened() {
        written = true;
    }
}
