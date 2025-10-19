package io.github.sachinnimbal.crudx.core.dto.annotations;

import io.github.sachinnimbal.crudx.core.enums.CrudXOperation;

import java.lang.annotation.*;

/**
 * Marks a class as a CrudX Request DTO with automatic entity mapping.
 *
 * <p><b>Usage Examples:</b></p>
 * <pre>
 * // Basic usage - works for all operations
 * {@literal @}CrudXRequest(User.class)
 * public class UserRequest {
 *     private String username;
 *     private String email;
 * }
 *
 * // Operation-specific
 * {@literal @}CrudXRequest(value = User.class, operations = {CREATE, BATCH_CREATE})
 * public class UserCreateRequest {
 *     private String username;
 *     private String email;
 *     private String password;
 * }
 *
 * // Custom mapper name
 * {@literal @}CrudXRequest(value = User.class, mapper = "userRegistrationMapper")
 * public class UserRegistrationRequest { }
 * </pre>
 *
 * @author Sachin Nimbal
 * @since 1.0.2
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME) // Changed to RUNTIME for discovery
@Documented
public @interface CrudXRequest {

    /**
     * The entity class this DTO maps to (required).
     */
    Class<?> value();

    /**
     * Operations this DTO is valid for.
     * Empty array means all operations.
     */
    CrudXOperation[] operations() default {};

    /**
     * Custom mapper bean name (optional).
     * If not specified, uses: "{entity}MapperCrudX"
     */
    String mapper() default "";

    /**
     * Enable strict mode - fails if DTO has fields not in entity.
     */
    boolean strict() default false;

    /**
     * Auto-exclude fields marked with @CrudXImmutable in entity.
     */
    boolean excludeImmutable() default true;

    /**
     * Auto-exclude audit fields (createdAt, createdBy, etc.).
     */
    boolean excludeAudit() default true;
}