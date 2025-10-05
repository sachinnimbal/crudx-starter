package io.github.sachinnimbal.crudx.core.annotations;

import java.lang.annotation.*;


/**
 * @author Sachin Nimbal
 * @version 1.0.0
 * @since 2025
 * @Contact: <a href="mailto:sachinnimbal9@gmail.com">sachinnimbal9@gmail.com</a>
 * @see <a href="https://www.linkedin.com/in/sachin-nimbal/">LinkedIn Profile</a>
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@Repeatable(CrudXUniqueConstraints.class)
public @interface CrudXUniqueConstraint {
    String[] fields();

    String name() default "";

    String message() default "Duplicate entry found for unique constraint";
}