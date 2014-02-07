/*
 * Copyright (c) 2010-2014 Sonatype, Inc. All rights reserved.
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
package org.asynchttpclient.date;

/**
 * A placeholder for RFC2616 date elements
 * 
 * @author slandelle
 */
public final class RFC2616Date {

    private final int year;
    // 1 to 12
    private final int month;
    private final int dayOfMonth;
    private final int hour;
    private final int minute;
    private final int second;

    public RFC2616Date(int year, int month, int dayOfMonth, int hour, int minute, int second) {
        this.year = year;
        this.month = month;
        this.dayOfMonth = dayOfMonth;
        this.hour = hour;
        this.minute = minute;
        this.second = second;
    }

    public int year() {
        return year;
    }

    public int month() {
        return month;
    }

    public int dayOfMonth() {
        return dayOfMonth;
    }

    public int hour() {
        return hour;
    }

    public int minute() {
        return minute;
    }

    public int second() {
        return second;
    }

    public static final class Builder {

        private int dayOfMonth;
        private int month;
        private int year;
        private int hour;
        private int minute;
        private int second;

        public void setDayOfMonth(int dayOfMonth) {
            this.dayOfMonth = dayOfMonth;
        }

        public void setJanuary() {
            month = 1;
        }

        public void setFebruary() {
            month = 2;
        }

        public void setMarch() {
            month = 3;
        }

        public void setApril() {
            month = 4;
        }

        public void setMay() {
            month = 5;
        }

        public void setJune() {
            month = 6;
        }

        public void setJuly() {
            month = 7;
        }

        public void setAugust() {
            month = 8;
        }

        public void setSeptember() {
            month = 9;
        }

        public void setOctobre() {
            month = 10;
        }

        public void setNovembre() {
            month = 11;
        }

        public void setDecember() {
            month = 12;
        }

        public void setYear(int year) {
            this.year = year;
        }

        public void setHour(int hour) {
            this.hour = hour;
        }

        public void setMinute(int minute) {
            this.minute = minute;
        }

        public void setSecond(int second) {
            this.second = second;
        }

        public RFC2616Date build() {
            return new RFC2616Date(year, month, dayOfMonth, hour, minute, second);
        }
    }
}
