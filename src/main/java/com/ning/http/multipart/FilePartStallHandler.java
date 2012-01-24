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
package com.ning.http.multipart;

import java.util.Timer;
import java.util.TimerTask;

/**
 * @author Gail Hernandez
 */
public class FilePartStallHandler extends TimerTask {
	public FilePartStallHandler(long waitTime, FilePart filePart) {
		_waitTime = waitTime;
		_failed = false;
		_written = false;
	}
	
	public void completed() {
		if(_waitTime > 0) {
			_timer.cancel();
		}
	}

	public boolean isFailed() {
		return _failed;
	}

	public void run() {
		if(!_written) {
			_failed = true;
			_timer.cancel();
		}
		_written = false;
	}

	public void start() {
		if(_waitTime > 0) {
			_timer = new Timer();
			_timer.scheduleAtFixedRate(this, _waitTime, _waitTime);
		}
	}
	
	public void writeHappened() {
		_written = true;
	}

	private long _waitTime;
	private Timer _timer;
	private boolean _failed;
	private boolean _written;
}
