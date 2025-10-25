package io.github.sachinnimbal.crudx.core.annotations;

import io.github.sachinnimbal.crudx.core.config.CrudXConfiguration;
import org.springframework.context.annotation.Import;

import java.lang.annotation.*;

@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@Import(CrudXConfiguration.class)
public @interface CrudX { }
