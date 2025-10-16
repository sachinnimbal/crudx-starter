package io.github.sachinnimbal.crudx.core.annotations.dto;

import java.lang.annotation.*;

/**
 * Field-level customization for DTO mapping
 */
@Target(ElementType.FIELD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface CrudXField {
    /**
     * Map to different entity field name
     * Default: uses same field name
     */
    String value() default "";

    /**
     * Ignore this field during mapping
     */
    boolean ignore() default false;

    /**
     * Override inherited required validation
     */
    boolean required() default true;
}
