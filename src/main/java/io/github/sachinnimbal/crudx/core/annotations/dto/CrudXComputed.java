package io.github.sachinnimbal.crudx.core.annotations.dto;

import java.lang.annotation.*;

/**
 * Computed field with custom expression
 */
@Target(ElementType.FIELD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface CrudXComputed {
    /**
     * SpEL expression for computed value
     * Example: "entity.firstName + ' ' + entity.lastName"
     */
    String expression();
}
