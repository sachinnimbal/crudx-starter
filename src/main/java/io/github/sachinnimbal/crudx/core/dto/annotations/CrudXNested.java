package io.github.sachinnimbal.crudx.core.dto.annotations;

import java.lang.annotation.*;

/**
 * Marks a field as a nested object requiring recursive DTO mapping.
 *
 * <p><b>Usage Examples:</b></p>
 * <pre>
 * {@literal @}CrudXResponse(User.class)
 * public class UserResponse {
 *     private Long id;
 *     private String username;
 *
 *     // Auto-detect AddressResponse DTO
 *     {@literal @}CrudXNested
 *     private List&lt;AddressResponse&gt; addresses;
 *
 *     // Custom DTO class
 *     {@literal @}CrudXNested(dtoClass = OrderSummaryResponse.class)
 *     private List&lt;Order&gt; orders;
 *
 *     // Limit recursion depth
 *     {@literal @}CrudXNested(maxDepth = 2)
 *     private Company company; // Prevents circular refs
 * }
 * </pre>
 *
 * @author Sachin Nimbal
 * @since 1.0.2
 */
@Target(ElementType.FIELD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface CrudXNested {

    /**
     * Custom DTO class for nested object.
     * If void.class, auto-detects based on field type.
     */
    Class<?> dtoClass() default void.class;

    /**
     * Maximum recursion depth to prevent infinite loops.
     * 0 = no limit (dangerous with circular refs)
     */
    int maxDepth() default 3;

    /**
     * Fetch strategy for nested data.
     */
    FetchStrategy fetch() default FetchStrategy.EAGER;

    /**
     * Handle null nested objects.
     */
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