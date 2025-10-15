package io.github.sachinnimbal.crudx.core.annotations.dto;

import java.lang.annotation.*;

@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface CrudXCreateRequestDto {
    Class<?> entity();

    boolean inheritValidations() default true;

    boolean inheritConstraints() default true;
}
