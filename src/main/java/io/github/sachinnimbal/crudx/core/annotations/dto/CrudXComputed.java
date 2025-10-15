package io.github.sachinnimbal.crudx.core.annotations.dto;

import java.lang.annotation.*;

@Target(ElementType.FIELD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface CrudXComputed {
    String expression();
}
