package io.github.sachinnimbal.crudx.core.dto.annotations;

import io.github.sachinnimbal.crudx.core.enums.CrudXOperation;

import java.lang.annotation.*;

/**
 * Marks a class as a CrudX Response DTO with automatic entity mapping.
 *
 * <p><b>Usage Examples:</b></p>
 * <pre>
 * // Basic usage
 * {@literal @}CrudXResponse(User.class)
 * public class UserResponse {
 *     private Long id;
 *     private String username;
 *     private String email;
 * }
 *
 * // With nested objects
 * {@literal @}CrudXResponse(User.class)
 * public class UserDetailResponse {
 *     private Long id;
 *     private String username;
 *
 *     {@literal @}CrudXNested
 *     private List&lt;OrderResponse&gt; orders;
 * }
 *
 * // Operation-specific
 * {@literal @}CrudXResponse(value = User.class, operations = {GET_ID, GET_ALL})
 * public class UserPublicResponse {
 *     private String username;
 *     // excludes sensitive fields
 * }
 * </pre>
 *
 * @author Sachin Nimbal
 * @since 1.0.2
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface CrudXResponse {

    /**
     * The entity class this DTO maps from (required).
     */
    Class<?> value();

    /**
     * Operations this DTO is valid for.
     * Empty array means all operations.
     */
    CrudXOperation[] operations() default {};

    /**
     * Custom mapper bean name (optional).
     */
    String mapper() default "";

    /**
     * Include ID field automatically.
     */
    boolean includeId() default true;

    /**
     * Include audit fields (createdAt, updatedAt, etc.).
     */
    boolean includeAudit() default true;

    /**
     * Enable lazy loading of nested objects.
     */
    boolean lazyNested() default false;
}