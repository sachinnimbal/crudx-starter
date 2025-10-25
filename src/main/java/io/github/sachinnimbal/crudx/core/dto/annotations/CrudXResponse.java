package io.github.sachinnimbal.crudx.core.dto.annotations;

import io.github.sachinnimbal.crudx.core.enums.CrudXOperation;

import java.lang.annotation.*;

@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface CrudXResponse {

    Class<?> value();

    CrudXOperation[] operations() default {};

    boolean includeId() default true;

    boolean includeAudit() default true;

    boolean lazyNested() default false;
}
