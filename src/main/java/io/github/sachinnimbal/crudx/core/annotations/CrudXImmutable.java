package io.github.sachinnimbal.crudx.core.annotations;

import java.lang.annotation.*;

@Target(ElementType.FIELD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface CrudXImmutable {
    String message() default "This field cannot be updated";
}