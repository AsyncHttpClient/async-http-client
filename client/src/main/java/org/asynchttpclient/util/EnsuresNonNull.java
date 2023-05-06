package org.asynchttpclient.util;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * NullAway supports @EnsuresNonNull(param) annotation, but org.jetbrains.annotations doesn't include this annotation.
 * The purpose of this annotation is to tell NullAway that if the annotated method succeeded without any exceptions,
 * all class's fields defined in "param" will be @NotNull.
 */
@Retention(RetentionPolicy.CLASS)
@Target({ElementType.METHOD})
public @interface EnsuresNonNull {
    String[] value();
}