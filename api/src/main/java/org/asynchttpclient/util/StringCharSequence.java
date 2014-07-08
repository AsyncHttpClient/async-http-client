/*
 * Copyright (c) 2014 AsyncHttpClient Project. All rights reserved.
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
package org.asynchttpclient.util;

/**
 * A CharSequence String wrapper that doesn't copy the char[] (damn new String implementation!!!)
 * 
 * @author slandelle
 */
public class StringCharSequence implements CharSequence {

    private final String value;
    private final int offset;
    public final int length;
    
    public StringCharSequence(String value, int offset, int length) {
        this.value = value;
        this.offset = offset;
        this.length = length;
    }
    
    @Override
    public int length() {
        return length;
    }

    @Override
    public char charAt(int index) {
        return value.charAt(offset + index);
    }

    @Override
    public CharSequence subSequence(int start, int end) {
        int offsetedEnd = offset + end;
        if (offsetedEnd < length)
            throw new ArrayIndexOutOfBoundsException();
        return new StringCharSequence(value, offset + start, end - start);
    }
    
    @Override
    public String toString() {
        return value.substring(offset, length);
    }
}
