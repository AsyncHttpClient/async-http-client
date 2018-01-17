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
package org.asynchttpclient;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * A pair of (name, value) String
 *
 * @author slandelle
 */
public class Param {

  private final String name;
  private final String value;

  public Param(String name, String value) {
    this.name = name;
    this.value = value;
  }

  public static List<Param> map2ParamList(Map<String, List<String>> map) {
    if (map == null)
      return null;

    List<Param> params = new ArrayList<>(map.size());
    for (Map.Entry<String, List<String>> entries : map.entrySet()) {
      String name = entries.getKey();
      for (String value : entries.getValue())
        params.add(new Param(name, value));
    }
    return params;
  }

  public String getName() {
    return name;
  }

  public String getValue() {
    return value;
  }

  public int hashCode() {
    final int prime = 31;
    int result = 1;
    result = prime * result + ((name == null) ? 0 : name.hashCode());
    result = prime * result + ((value == null) ? 0 : value.hashCode());
    return result;
  }

  public boolean equals(Object obj) {
    if (this == obj)
      return true;
    if (obj == null)
      return false;
    if (!(obj instanceof Param))
      return false;
    Param other = (Param) obj;
    if (name == null) {
      if (other.name != null)
        return false;
    } else if (!name.equals(other.name))
      return false;
    if (value == null) {
      if (other.value != null)
        return false;
    } else if (!value.equals(other.value))
      return false;
    return true;
  }
}
