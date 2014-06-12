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
package org.asynchttpclient.util;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.Collection;
import java.util.Map;
import java.util.Properties;



public class MiscUtil {
	
	public final static Logger logger = LoggerFactory.getLogger(MiscUtil.class);

    private MiscUtil() {
    }

    public static boolean isNonEmpty(String string) {
        return string != null && !string.isEmpty();
    }

    public static boolean isNonEmpty(Object[] array) {
        return array != null && array.length != 0;
    }

    public static boolean isNonEmpty(byte[] array) {
        return array != null && array.length != 0;
    }

    public static boolean isNonEmpty(Collection<?> collection) {
        return collection != null && !collection.isEmpty();
    }

    public static boolean isNonEmpty(Map<?, ?> map) {
        return map != null && !map.isEmpty();
    }

   /* public static boolean getBoolean(String systemPropName, boolean defaultValue) {
        String systemPropValue = System.getProperty(systemPropName);
        return systemPropValue != null ? systemPropValue.equalsIgnoreCase("true") : defaultValue;
    }*/

	public static Integer getIntValue(String property,int defaultValue){
		//Read system property and if not null return that.
		Integer value = Integer.getInteger(property);
		if(value != null)
			return value;
		Properties asyncHttpClientConfigProperties = AsyncImplHelper.getAsyncImplProperties();
		if(asyncHttpClientConfigProperties!=null){
			String valueString=asyncHttpClientConfigProperties.getProperty(property);
			try{
				//If property is present and is non null parse it.
				if(valueString != null)
					return Integer.parseInt(valueString);
			}catch(NumberFormatException e){
				//If property couldn't be parsed log the error message and return default value.
				logger.error("Property : " + property + " has value = " + valueString + 
						" which couldn't be parsed to an int value. Returning default value: " + defaultValue,e); 
			}        		
		}
		return defaultValue;
	}

	public static Boolean getBooleanValue(String property,boolean defaultValue){
		String value = System.getProperty(property);
		Properties asyncHttpClientConfigProperties = AsyncImplHelper.getAsyncImplProperties();
		//If system property is invalid and property file is present then read value
		//from property file
		if(!MiscUtil.isValidBooleanValue(value) && asyncHttpClientConfigProperties!=null)
			value=asyncHttpClientConfigProperties.getProperty(property);
		//If valid value has been found return that value
		if(MiscUtil.isValidBooleanValue(value))
	        return Boolean.parseBoolean(value); 
		//If a value has been specified but can't be parsed into a boolean log a message
		//stating that value is unparseable and default values are being used.
	    if(value != null)
	        logger.error("Property : " + property + " has value = " + value + 
	                " which couldn't be parsed to an boolean value. Returning default value: " + defaultValue);
	    return defaultValue;            
	}

	private static boolean isValidBooleanValue(String value){
	    return value != null && ("true".equalsIgnoreCase(value)||"false".equalsIgnoreCase(value));
	}
}
