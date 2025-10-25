package io.github.sachinnimbal.crudx.core.dto.annotations;

import java.lang.annotation.*;

@Target(ElementType.FIELD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface CrudXField {

    String source() default "";

    String format() default "";

    boolean ignore() default false;

    String transformer() default "";

    String defaultValue() default "";

    boolean required() default false;
}
