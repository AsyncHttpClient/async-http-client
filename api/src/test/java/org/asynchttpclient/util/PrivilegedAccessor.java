package org.asynchttpclient.util;


/*
 * Copyright 2004-2012 Sebastian Dietrich (Sebastian.Dietrich@e-movimento.com)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 *     http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */


import java.lang.reflect.Array;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.security.InvalidParameterException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.StringTokenizer;

/**
 * This class is used to access a method or field of an object no matter what the access modifier of the method or field. The syntax
 * for accessing fields and methods is out of the ordinary because this class uses reflection to peel away protection.
 * <p>
 * a.k.a. The "ObjectMolester"
 * <p>
 * Here is an example of using this to access a private member: <br>
 * <code>myObject</code> is an object of type <code>MyClass</code>. <code>setName(String)</code> is a private method of
 * <code>MyClass</code>.
 * 
 * <pre>
 * PrivilegedAccessor.invokeMethod(myObject, &quot;setName(java.lang.String)&quot;, &quot;newName&quot;);
 * </pre>
 * 
 * @author Charlie Hubbard (chubbard@iss.net)
 * @author Prashant Dhokte (pdhokte@iss.net)
 * @author Sebastian Dietrich (sebastian.dietrich@e-movimento.com)
 * 
 * @deprecated use PA instead. PA improves the functionality of PrivilegedAccessor by introducing support for varargs and removal of
 *             the necessity to catch exceptions.
 */
@Deprecated
public final class PrivilegedAccessor {
   /**
    * Private constructor to make it impossible to instantiate this class.
    */
   private PrivilegedAccessor() {
      assert false : "You mustn't instantiate PrivilegedAccessor, use its methods statically";
   }

   /**
    * Returns a string representation of the given object. The string has the following format: "<classname> {<attributes and values>}"
    * whereas <attributes and values> is a comma separated list with <attributeName>=<attributeValue> <atributes and values> includes
    * all attributes of the objects class followed by the attributes of its superclass (if any) and so on.
    * 
    * @param instanceOrClass the object or class to get a string representation of
    * @return a string representation of the given object
    */
   public static String toString(final Object instanceOrClass) {
      Collection<String> fields = getFieldNames(instanceOrClass);

      if (fields.isEmpty()) return getClass(instanceOrClass).getName();

      StringBuffer stringBuffer = new StringBuffer();

      stringBuffer.append(getClass(instanceOrClass).getName() + " {");

      for (String fieldName : fields) {
         try {
            stringBuffer.append(fieldName + "=" + getValue(instanceOrClass, fieldName) + ", ");
         } catch (NoSuchFieldException e) {
            assert false : "It should always be possible to get a field that was just here";
         }
      }

      stringBuffer.replace(stringBuffer.lastIndexOf(", "), stringBuffer.length(), "}");
      return stringBuffer.toString();
   }

   /**
    * Gets the name of all fields (public, private, protected, default) of the given instance or class. This includes as well all
    * fields (public, private, protected, default) of all its super classes.
    * 
    * @param instanceOrClass the instance or class to get the fields of
    * @return the collection of field names of the given instance or class
    */
   public static Collection<String> getFieldNames(final Object instanceOrClass) {
      if (instanceOrClass == null) return Collections.EMPTY_LIST;

      Class<?> clazz = getClass(instanceOrClass);
      Field[] fields = clazz.getDeclaredFields();
      Collection<String> fieldNames = new ArrayList<String>(fields.length);

      for (Field field : fields) {
         fieldNames.add(field.getName());
      }
      fieldNames.addAll(getFieldNames(clazz.getSuperclass()));

      return fieldNames;
   }

   /**
    * Gets the signatures of all methods (public, private, protected, default) of the given instance or class. This includes as well
    * all methods (public, private, protected, default) of all its super classes. This does not include constructors.
    * 
    * @param instanceOrClass the instance or class to get the method signatures of
    * @return the collection of method signatures of the given instance or class
    */
   public static Collection<String> getMethodSignatures(final Object instanceOrClass) {
      if (instanceOrClass == null) return Collections.EMPTY_LIST;

      Class<?> clazz = getClass(instanceOrClass);
      Method[] methods = clazz.getDeclaredMethods();
      Collection<String> methodSignatures = new ArrayList<String>(methods.length + Object.class.getDeclaredMethods().length);

      for (Method method : methods) {
         methodSignatures.add(method.getName() + "(" + getParameterTypesAsString(method.getParameterTypes()) + ")");
      }
      methodSignatures.addAll(getMethodSignatures(clazz.getSuperclass()));

      return methodSignatures;
   }

   /**
    * Gets the value of the named field and returns it as an object. If instanceOrClass is a class then a static field is returned.
    * 
    * @param instanceOrClass the instance or class to get the field from
    * @param fieldName the name of the field
    * @return an object representing the value of the field
    * @throws NoSuchFieldException if the field does not exist
    */
   public static Object getValue(final Object instanceOrClass, final String fieldName) throws NoSuchFieldException {
      Field field = getField(instanceOrClass, fieldName);
      try {
         return field.get(instanceOrClass);
      } catch (IllegalAccessException e) {
         assert false : "getField() should have setAccessible(true), so an IllegalAccessException should not occur in this place";
         return null;
      }
   }

   /**
    * Instantiates an object of the given class with the given arguments. If you want to instantiate a member class, you must provide
    * the object it is a member of as first argument.
    * 
    * @param fromClass the class to instantiate an object from
    * @param args the arguments to pass to the constructor
    * @return an object of the given type
    * @throws IllegalArgumentException if the number of actual and formal parameters differ; if an unwrapping conversion for primitive
    *            arguments fails; or if, after possible unwrapping, a parameter value cannot be converted to the corresponding formal
    *            parameter type by a method invocation conversion.
    * @throws IllegalAccessException if this Constructor object enforces Java language access control and the underlying constructor is
    *            inaccessible.
    * @throws InvocationTargetException if the underlying constructor throws an exception.
    * @throws NoSuchMethodException if the constructor could not be found
    * @throws InstantiationException if the class that declares the underlying constructor represents an abstract class.
    * 
    * @see PrivilegedAccessor#instantiate(Class,Class[],Object[])
    */
   public static <T> T instantiate(final Class<? extends T> fromClass, final Object[] args) throws IllegalArgumentException,
      InstantiationException, IllegalAccessException, InvocationTargetException, NoSuchMethodException {
      return instantiate(fromClass, getParameterTypes(args), args);
   }

   /**
    * Instantiates an object of the given class with the given arguments and the given argument types. If you want to instantiate a
    * member class, you must provide the object it is a member of as first argument.
    * 
    * 
    * @param fromClass the class to instantiate an object from
    * @param args the arguments to pass to the constructor
    * @param argumentTypes the fully qualified types of the arguments of the constructor
    * @return an object of the given type
    * @throws IllegalArgumentException if the number of actual and formal parameters differ; if an unwrapping conversion for primitive
    *            arguments fails; or if, after possible unwrapping, a parameter value cannot be converted to the corresponding formal
    *            parameter type by a method invocation conversion.
    * @throws IllegalAccessException if this Constructor object enforces Java language access control and the underlying constructor is
    *            inaccessible.
    * @throws InvocationTargetException if the underlying constructor throws an exception.
    * @throws NoSuchMethodException if the constructor could not be found
    * @throws InstantiationException if the class that declares the underlying constructor represents an abstract class.
    * 
    * @see PrivilegedAccessor#instantiate(Class,Object[])
    */
   public static <T> T instantiate(final Class<? extends T> fromClass, final Class<?>[] argumentTypes, final Object[] args)
      throws IllegalArgumentException, InstantiationException, IllegalAccessException, InvocationTargetException,
      NoSuchMethodException {
      return getConstructor(fromClass, argumentTypes).newInstance(args);
   }

   /**
    * Calls a method on the given object instance with the given arguments. Arguments can be object types or representations for
    * primitives.
    * 
    * @param instanceOrClass the instance or class to invoke the method on
    * @param methodSignature the name of the method and the parameters <br>
    *           (e.g. "myMethod(java.lang.String, com.company.project.MyObject)")
    * @param arguments an array of objects to pass as arguments
    * @return the return value of this method or null if void
    * @throws IllegalAccessException if the method is inaccessible
    * @throws InvocationTargetException if the underlying method throws an exception.
    * @throws NoSuchMethodException if no method with the given <code>methodSignature</code> could be found
    * @throws IllegalArgumentException if an argument couldn't be converted to match the expected type
    */
   public static Object invokeMethod(final Object instanceOrClass, final String methodSignature, final Object[] arguments)
      throws IllegalArgumentException, IllegalAccessException, InvocationTargetException, NoSuchMethodException {
      if ((methodSignature.indexOf('(') == -1) || (methodSignature.indexOf('(') >= methodSignature.indexOf(')')))
         throw new NoSuchMethodException(methodSignature);
      Class<?>[] parameterTypes = getParameterTypes(methodSignature);
      return getMethod(instanceOrClass, getMethodName(methodSignature), parameterTypes).invoke(instanceOrClass,
         getCorrectedArguments(parameterTypes, arguments));
   }

   /**
    * Gets the given arguments corrected to match the given methodSignature. Correction is necessary for array arguments not to be
    * mistaken by varargs.
    * 
    * @param parameterTypes the method signatue the given arguments should match
    * @param arguments the arguments that should be corrected
    * @return the corrected arguments
    */
   private static Object[] getCorrectedArguments(Class<?>[] parameterTypes, Object[] arguments) {
      if (arguments == null) return arguments;
      if (parameterTypes.length > arguments.length) return arguments;
      if (parameterTypes.length < arguments.length) return getCorrectedArguments(parameterTypes, new Object[] {arguments});

      Object[] correctedArguments = new Object[arguments.length];
      int currentArgument = 0;
      for (Class<?> parameterType : parameterTypes) {
         correctedArguments[currentArgument] = getCorrectedArgument(parameterType, arguments[currentArgument]);
         currentArgument++;
      }
      return correctedArguments;
   }

   /**
    * Gets the given argument corrected to match the given parameterType. Correction is necessary for array arguments not to be
    * mistaken by varargs.
    * 
    * @param parameterType the type to match the given argument upon
    * @param argument the argument to match the given parameterType
    * @return the corrected argument
    */
   private static Object getCorrectedArgument(Class<?> parameterType, Object argument) {
      if (!parameterType.isArray() || (argument == null)) {
         return argument; // normal argument for normal parameterType
      }

      if (!argument.getClass().isArray()) {
         return new Object[] {argument};
      }

      if (parameterType.equals(argument.getClass())) return argument; // no need to cast

      // (typed) array argument for (object) array parameterType, elements need to be casted
      Object correctedArrayArgument = Array.newInstance(parameterType.getComponentType(), Array.getLength(argument));
      for (int index = 0; index < Array.getLength(argument); index++) {
         if (parameterType.getComponentType().isPrimitive()) { // rely on autoboxing
            Array.set(correctedArrayArgument, index, Array.get(argument, index));
         } else { // cast to expected type
            try {
               Array.set(correctedArrayArgument, index, parameterType.getComponentType().cast(Array.get(argument, index)));
            } catch (ClassCastException e) {
               throw new IllegalArgumentException("Argument " + argument + " of type " + argument.getClass()
                  + " does not match expected argument type " + parameterType + ".");
            }
         }
      }
      return correctedArrayArgument;
   }

   /**
    * Sets the value of the named field. If fieldName denotes a static field, provide a class, otherwise provide an instance. If the
    * fieldName denotes a final field, this method could fail with an IllegalAccessException, since setting the value of final fields
    * at other times than instantiation can have unpredictable effects.<br/>
    * <br/>
    * Example:<br/>
    * <br/>
    * <code>
    * String myString = "Test"; <br/>
    * <br/>
    * //setting the private field value<br/>
    * PrivilegedAccessor.setValue(myString, "value", new char[] {'T', 'e', 's', 't'});<br/>
    * <br/>
    * //setting the static final field serialVersionUID - MIGHT FAIL<br/>
    * PrivilegedAccessor.setValue(myString.getClass(), "serialVersionUID", 1);<br/>
    * <br/>
    * </code>
    * 
    * @param instanceOrClass the instance or class to set the field
    * @param fieldName the name of the field
    * @param value the new value of the field
    * @throws NoSuchFieldException if no field with the given <code>fieldName</code> can be found
    * @throws IllegalAccessException possibly if the field was final
    */
   public static void setValue(final Object instanceOrClass, final String fieldName, final Object value) throws NoSuchFieldException,
      IllegalAccessException {
      Field field = getField(instanceOrClass, fieldName);
      if (Modifier.isFinal(field.getModifiers())) {
         PrivilegedAccessor.setValue(field, "modifiers", field.getModifiers() ^ Modifier.FINAL);
      }
      field.set(instanceOrClass, value);
   }

   /**
    * Gets the class with the given className.
    * 
    * @param className the name of the class to get
    * @return the class for the given className
    * @throws ClassNotFoundException if the class could not be found
    */
   private static Class<?> getClassForName(final String className) throws ClassNotFoundException {
      if (className.indexOf('[') > -1) {
         Class<?> clazz = getClassForName(className.substring(0, className.indexOf('[')));
         return Array.newInstance(clazz, 0).getClass();
      }

      if (className.indexOf("...") > -1) {
         Class<?> clazz = getClassForName(className.substring(0, className.indexOf("...")));
         return Array.newInstance(clazz, 0).getClass();
      }

      try {
         return Class.forName(className, false, Thread.currentThread().getContextClassLoader());
      } catch (ClassNotFoundException e) {
         return getSpecialClassForName(className);
      }
   }

   /**
    * Maps string representation of primitives to their corresponding classes.
    */
   private static final Map<String, Class<?>> PRIMITIVE_MAPPER = new HashMap<String, Class<?>>(8);

   /**
    * Fills the map with all java primitives and their corresponding classes.
    */
   static {
      PRIMITIVE_MAPPER.put("int", Integer.TYPE);
      PRIMITIVE_MAPPER.put("float", Float.TYPE);
      PRIMITIVE_MAPPER.put("double", Double.TYPE);
      PRIMITIVE_MAPPER.put("short", Short.TYPE);
      PRIMITIVE_MAPPER.put("long", Long.TYPE);
      PRIMITIVE_MAPPER.put("byte", Byte.TYPE);
      PRIMITIVE_MAPPER.put("char", Character.TYPE);
      PRIMITIVE_MAPPER.put("boolean", Boolean.TYPE);
   }

   /**
    * Gets special classes for the given className. Special classes are primitives and "standard" Java types (like String)
    * 
    * @param className the name of the class to get
    * @return the class for the given className
    * @throws ClassNotFoundException if the class could not be found
    */
   private static Class<?> getSpecialClassForName(final String className) throws ClassNotFoundException {
      if (PRIMITIVE_MAPPER.containsKey(className)) return PRIMITIVE_MAPPER.get(className);

      if (missesPackageName(className)) return getStandardClassForName(className);

      throw new ClassNotFoundException(className);
   }

   /**
    * Gets a 'standard' java class for the given className.
    * 
    * @param className the className
    * @return the class for the given className (if any)
    * @throws ClassNotFoundException of no 'standard' java class was found for the given className
    */
   private static Class<?> getStandardClassForName(String className) throws ClassNotFoundException {
      try {
         return Class.forName("java.lang." + className, false, Thread.currentThread().getContextClassLoader());
      } catch (ClassNotFoundException e) {
         try {
            return Class.forName("java.util." + className, false, Thread.currentThread().getContextClassLoader());
         } catch (ClassNotFoundException e1) {
            throw new ClassNotFoundException(className);
         }
      }
   }

   /**
    * Tests if the given className possibly misses its package name.
    * 
    * @param className the className
    * @return true if the className might miss its package name, otherwise false
    */
   private static boolean missesPackageName(String className) {
      if (className.contains(".")) return false;
      if (className.startsWith(className.substring(0, 1).toUpperCase())) return true;
      return false;
   }

   /**
    * Gets the constructor for a given class with the given parameters.
    * 
    * @param type the class to instantiate
    * @param parameterTypes the types of the parameters
    * @return the constructor
    * @throws NoSuchMethodException if the method could not be found
    */
   private static <T> Constructor<T> getConstructor(final Class<T> type, final Class<?>[] parameterTypes) throws NoSuchMethodException {
      Constructor<T> constructor = type.getDeclaredConstructor(parameterTypes);
      constructor.setAccessible(true);
      return constructor;
   }

   /**
    * Return the named field from the given instance or class. Returns a static field if instanceOrClass is a class.
    * 
    * @param instanceOrClass the instance or class to get the field from
    * @param fieldName the name of the field to get
    * @return the field
    * @throws NoSuchFieldException if no such field can be found
    * @throws InvalidParameterException if instanceOrClass was null
    */
   private static Field getField(final Object instanceOrClass, final String fieldName) throws NoSuchFieldException,
      InvalidParameterException {
      if (instanceOrClass == null) throw new InvalidParameterException("Can't get field on null object/class");

      Class<?> type = getClass(instanceOrClass);

      try {
         Field field = type.getDeclaredField(fieldName);
         field.setAccessible(true);
         return field;
      } catch (NoSuchFieldException e) {
         if (type.getSuperclass() == null) throw e;
         return getField(type.getSuperclass(), fieldName);
      }
   }

   /**
    * Gets the class of the given parameter. If the parameter is a class, it is returned, if it is an object, its class is returned
    * 
    * @param instanceOrClass the instance or class to get the class of
    * @return the class of the given parameter
    */
   private static Class<?> getClass(final Object instanceOrClass) {
      if (instanceOrClass instanceof Class) return (Class<?>) instanceOrClass;

      return instanceOrClass.getClass();
   }

   /**
    * Return the named method with a method signature matching classTypes from the given class.
    * 
    * @param type the class to get the method from
    * @param methodName the name of the method to get
    * @param parameterTypes the parameter-types of the method to get
    * @return the method
    * @throws NoSuchMethodException if the method could not be found
    */
   private static Method getMethod(final Class<?> type, final String methodName, final Class<?>[] parameterTypes)
      throws NoSuchMethodException {
      try {
         return type.getDeclaredMethod(methodName, parameterTypes);
      } catch (NoSuchMethodException e) {
         if (type.getSuperclass() == null) throw e;
         return getMethod(type.getSuperclass(), methodName, parameterTypes);
      }
   }

   /**
    * Gets the method with the given name and parameters from the given instance or class. If instanceOrClass is a class, then we get a
    * static method.
    * 
    * @param instanceOrClass the instance or class to get the method of
    * @param methodName the name of the method
    * @param parameterTypes the parameter-types of the method to get
    * @return the method
    * @throws NoSuchMethodException if the method could not be found
    */
   private static Method getMethod(final Object instanceOrClass, final String methodName, final Class<?>[] parameterTypes)
      throws NoSuchMethodException {
      Class<?> type;

      type = getClass(instanceOrClass);

      Method accessMethod = getMethod(type, methodName, parameterTypes);
      accessMethod.setAccessible(true);
      return accessMethod;
   }

   /**
    * Gets the name of a method.
    * 
    * @param methodSignature the signature of the method
    * @return the name of the method
    */
   private static String getMethodName(final String methodSignature) {
      try {
         return methodSignature.substring(0, methodSignature.indexOf('(')).trim();
      } catch (StringIndexOutOfBoundsException e) {
         assert false : "Signature must have been checked before this method was called";
         return null;
      }
   }

   /**
    * Gets the types of the parameters.
    * 
    * @param parameters the parameters
    * @return the class-types of the arguments
    */
   private static Class<?>[] getParameterTypes(final Object[] parameters) {
      if (parameters == null) return new Class[0];

      Class<?>[] typesOfParameters = new Class[parameters.length];

      for (int i = 0; i < parameters.length; i++) {
         typesOfParameters[i] = parameters[i].getClass();
      }
      return typesOfParameters;
   }

   /**
    * Gets the types of the given parameters. If the parameters don't match the given methodSignature an IllegalArgumentException is
    * thrown.
    * 
    * @param methodSignature the signature of the method
    * @return the parameter types as class[]
    * @throws NoSuchMethodException if the method could not be found
    * @throws IllegalArgumentException if one of the given parameters doesn't math the given methodSignature
    */
   private static Class<?>[] getParameterTypes(final String methodSignature) throws NoSuchMethodException, IllegalArgumentException {
      String signature = getSignatureWithoutBraces(methodSignature);

      StringTokenizer tokenizer = new StringTokenizer(signature, ", *");
      Class<?>[] typesInSignature = new Class[tokenizer.countTokens()];

      for (int x = 0; tokenizer.hasMoreTokens(); x++) {
         String className = tokenizer.nextToken();
         try {
            typesInSignature[x] = getClassForName(className);
         } catch (ClassNotFoundException e) {
            NoSuchMethodException noSuchMethodException = new NoSuchMethodException(methodSignature);
            noSuchMethodException.initCause(e);
            throw noSuchMethodException;
         }
      }
      return typesInSignature;
   }

   /**
    * Gets the parameter types as a string.
    * 
    * @param classTypes the types to get as names.
    * @return the parameter types as a string
    * 
    * @see java.lang.Class#argumentTypesToString(Class[])
    */
   private static String getParameterTypesAsString(final Class<?>[] classTypes) {
      assert classTypes != null : "getParameterTypes() should have been called before this method and should have provided not-null classTypes";
      if (classTypes.length == 0) return "";

      StringBuilder parameterTypes = new StringBuilder();
      for (Class<?> clazz : classTypes) {
         assert clazz != null : "getParameterTypes() should have been called before this method and should have provided not-null classTypes";
         parameterTypes.append(clazz.getName()).append(", ");
      }

      return parameterTypes.substring(0, parameterTypes.length() - 2);
   }

   /**
    * Removes the braces around the methods signature.
    * 
    * @param methodSignature the signature with braces
    * @return the signature without braces
    */
   private static String getSignatureWithoutBraces(final String methodSignature) {
      try {
         return methodSignature.substring(methodSignature.indexOf('(') + 1, methodSignature.indexOf(')'));
      } catch (IndexOutOfBoundsException e) {
         assert false : "signature must have been checked before this method";
         return null;
      }
   }

}



