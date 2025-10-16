package io.github.sachinnimbal.crudx.core.annotations.dto;

import io.github.sachinnimbal.crudx.core.enums.FetchStrategy;

import java.lang.annotation.*;

/**
 * Map nested DTO to entity relationship
 */
@Target(ElementType.FIELD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface CrudXNested {
    /**
     * DTO class for nested object
     */
    Class<?> dto();

    /**
     * Fetch strategy for nested data
     */
    FetchStrategy fetch() default FetchStrategy.EAGER;
}