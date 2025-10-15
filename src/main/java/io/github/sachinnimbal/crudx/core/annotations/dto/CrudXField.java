package io.github.sachinnimbal.crudx.core.annotations.dto;

import java.lang.annotation.*;

@Target(ElementType.FIELD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface CrudXField {
    String value() default ""; // Field name override

    boolean ignore() default false;

    boolean required() default true; // Override inherited requirement
}
