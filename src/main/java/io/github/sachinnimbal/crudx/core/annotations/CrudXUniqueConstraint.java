package io.github.sachinnimbal.crudx.core.annotations;

import java.lang.annotation.*;

@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@Repeatable(CrudXUniqueConstraints.class)
public @interface CrudXUniqueConstraint {
    String[] fields();

    String name() default "";

    String message() default "Duplicate entry found for unique constraint";
}
