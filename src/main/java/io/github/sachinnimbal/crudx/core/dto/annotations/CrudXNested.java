package io.github.sachinnimbal.crudx.core.dto.annotations;

import java.lang.annotation.*;

@Target(ElementType.FIELD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface CrudXNested {

    Class<?> dtoClass() default void.class;

    int maxDepth() default 3;

    FetchStrategy fetch() default FetchStrategy.EAGER;

    NullStrategy nullStrategy() default NullStrategy.INCLUDE_NULL;

    enum FetchStrategy {
        EAGER,  // Load immediately
        LAZY    // Load on access (requires proxy)
    }

    enum NullStrategy {
        INCLUDE_NULL,   // Keep null in response
        EXCLUDE_NULL,   // Remove field if null
        EMPTY_COLLECTION // Return empty list/set for collections
    }
}
