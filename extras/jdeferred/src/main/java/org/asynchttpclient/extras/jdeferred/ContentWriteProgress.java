/*
 * Copyright 2013 Ray Tsang
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.asynchttpclient.extras.jdeferred;

public class ContentWriteProgress implements HttpProgress {
  private final long amount;
  private final long current;
  private final long total;

  public ContentWriteProgress(long amount, long current, long total) {
    this.amount = amount;
    this.current = current;
    this.total = total;
  }

  public long getAmount() {
    return amount;
  }

  public long getCurrent() {
    return current;
  }

  public long getTotal() {
    return total;
  }

  @Override
  public String toString() {
    return "ContentWriteProgress [amount=" + amount + ", current=" + current + ", total=" + total + "]";
  }
}
