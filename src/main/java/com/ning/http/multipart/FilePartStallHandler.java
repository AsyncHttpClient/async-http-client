/**
 * Copyright (c) 2000-2012 Liferay, Inc. All rights reserved.
 *
 * This library is free software; you can redistribute it and/or modify it under
 * the terms of the GNU Lesser General Public License as published by the Free
 * Software Foundation; either version 2.1 of the License, or (at your option)
 * any later version.
 *
 * This library is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS
 * FOR A PARTICULAR PURPOSE. See the GNU Lesser General Public License for more
 * details.
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
