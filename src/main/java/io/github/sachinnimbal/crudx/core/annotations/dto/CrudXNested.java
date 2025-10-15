package io.github.sachinnimbal.crudx.core.annotations.dto;

import io.github.sachinnimbal.crudx.core.enums.FetchStrategy;

import java.lang.annotation.*;

@Target(ElementType.FIELD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface CrudXNested {
    Class<?> dto();

    FetchStrategy fetch() default FetchStrategy.EAGER;
}
