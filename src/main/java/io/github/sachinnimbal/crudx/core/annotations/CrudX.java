package io.github.sachinnimbal.crudx.core.annotations;

import io.github.sachinnimbal.crudx.core.config.CrudXConfiguration;
import org.springframework.context.annotation.Import;

import java.lang.annotation.*;


/**
 * @author Sachin Nimbal
 * @version 1.0.0-SNAPSHOT
 * @since 2025
 * @Contact: <a href="mailto:sachinnimbal9@gmail.com">sachinnimbal9@gmail.com</a>
 * @see <a href="https://www.linkedin.com/in/sachin-nimbal/">LinkedIn Profile</a>
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@Import(CrudXConfiguration.class)
public @interface CrudX { }
