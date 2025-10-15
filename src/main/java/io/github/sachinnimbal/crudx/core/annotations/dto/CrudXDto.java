package io.github.sachinnimbal.crudx.core.annotations.dto;


import io.github.sachinnimbal.crudx.core.enums.OperationType;

import java.lang.annotation.*;

@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface CrudXDto {
    Class<?> entity();

    OperationType[] operations();

    boolean inheritValidations() default true;

    boolean inheritConstraints() default true;
}
