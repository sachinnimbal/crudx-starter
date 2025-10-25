package io.github.sachinnimbal.crudx.core.dto.annotations;

import io.github.sachinnimbal.crudx.core.enums.CrudXOperation;

import java.lang.annotation.*;

@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface CrudXRequest {

    Class<?> value();

    CrudXOperation[] operations() default {};

    boolean strict() default false;

    boolean excludeImmutable() default true;

    boolean excludeAudit() default true;
}
