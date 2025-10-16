package io.github.sachinnimbal.crudx.core.annotations.dto;

import java.lang.annotation.*;

@Target(ElementType.FIELD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface CrudXCollection {
    /**
     * Element DTO class for collection items
     */
    Class<?> elementDto();

    /**
     * Maximum collection size
     */
    int maxSize() default 1000;
}
