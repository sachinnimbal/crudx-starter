package io.github.sachinnimbal.crudx.core.annotations.dto;

import io.github.sachinnimbal.crudx.core.enums.OperationType;

import java.lang.annotation.*;

@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface CrudXRequestDto {

    /**
     * The entity class this DTO maps to
     */
    Class<?> entity();

    /**
     * Operations this REQUEST DTO is used for
     * Example: {OperationType.CREATE, OperationType.UPDATE}
     */
    OperationType[] operations();

    /**
     * Inherit validation annotations from entity fields
     */
    boolean inheritValidations() default true;

    /**
     * Inherit database constraints (unique, not-null)
     */
    boolean inheritConstraints() default true;
}