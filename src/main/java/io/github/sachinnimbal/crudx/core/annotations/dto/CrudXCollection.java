package io.github.sachinnimbal.crudx.core.annotations.dto;

import java.lang.annotation.*;

@Target(ElementType.FIELD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface CrudXCollection {
    Class<?> elementDto();

    int maxSize() default 1000;
}
