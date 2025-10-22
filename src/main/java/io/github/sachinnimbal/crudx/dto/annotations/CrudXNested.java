package io.github.sachinnimbal.crudx.dto.annotations;

import io.github.sachinnimbal.crudx.dto.enums.CrudOperation;

import java.lang.annotation.*;

/**
 * Marks a field as a nested object that should be mapped using its own DTO.
 * Prevents infinite recursion with maxDepth.
 *
 * <p>Usage Examples:</p>
 * <pre>
 * // Auto-detect nested DTO (AddressResponse)
 * {@code @CrudXNested}
 * private List<Address> addresses;
 *
 * // Explicit nested DTO class
 * {@code @CrudXNested(dtoClass = AddressBasicResponse.class)}
 * private Address primaryAddress;
 *
 * // Limit recursion depth
 * {@code @CrudXNested(maxDepth = 2)}
 * private List<Comment> comments; // Comment may contain nested User, User may contain nested Comment
 *
 * // Lazy load (future - cache integration)
 * {@code @CrudXNested(lazy = true)}
 * private List<Order> orders;
 * </pre>
 *
 * @author CrudX Framework
 * @since 1.0.0
 */
@Target(ElementType.FIELD)
@Retention(RetentionPolicy.SOURCE)
@Documented
public @interface CrudXNested {

    /**
     * DTO class to use for nested mapping.
     * If not specified, auto-detects based on naming:
     * - Entity "Address" → looks for "AddressResponse"
     * - Entity "Order" → looks for "OrderResponse"
     *
     * @return DTO class, or void.class for auto-detect
     */
    Class<?> dtoClass() default void.class;

    /**
     * Maximum depth for recursive mapping.
     * Prevents infinite loops in circular references.
     * <p>
     * Example: User → Order → User (depth 2 stops recursion)
     *
     * @return max recursion depth
     */
    int maxDepth() default 3;

    /**
     * Whether to use lazy loading (future - cache integration).
     * When true, nested data loaded on-demand.
     *
     * @return true for lazy loading
     */
    boolean lazy() default false;

    /**
     * Whether to fetch nested data (future - optimization).
     * If false, field is null unless explicitly loaded.
     *
     * @return true to fetch
     */
    boolean fetch() default true;
}