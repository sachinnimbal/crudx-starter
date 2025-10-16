package io.github.sachinnimbal.crudx.core.annotations.dto;

import io.github.sachinnimbal.crudx.core.enums.OperationType;

import java.lang.annotation.*;

@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface CrudXResponseDto {
    /**
     * The entity class this DTO maps to
     */
    Class<?> entity();

    /**
     * Operations this RESPONSE DTO is used for
     */
    OperationType[] operations();
}