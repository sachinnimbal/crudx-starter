package io.github.sachinnimbal.crudx.dto.annotations;

import io.github.sachinnimbal.crudx.dto.enums.CrudOperation;

import java.lang.annotation.*;

/**
 * Marks a class as a CrudX Request DTO.
 * Generates compile-time mapping code from DTO to Entity.
 *
 * <p>Usage Examples:</p>
 * <pre>
 * // For all CREATE operations (create, batch create)
 * {@code @CrudXRequest(User.class)}
 * public class UserCreateRequest { }
 *
 * // For specific operations only
 * {@code @CrudXRequest(value = User.class, operations = {CREATE, BATCH_CREATE})}
 * public class UserRegisterRequest { }
 *
 * // For UPDATE operations
 * {@code @CrudXRequest(value = User.class, operations = {UPDATE})}
 * public class UserUpdateRequest { }
 * </pre>
 *
 * @author CrudX Framework
 * @since 1.0.0
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.SOURCE)
@Documented
public @interface CrudXRequest {

    /**
     * The entity class this DTO maps to.
     *
     * @return entity class
     */
    Class<?> value();

    /**
     * Operations this DTO applies to.
     * Empty array = applies to all request operations (CREATE, UPDATE, BATCH_CREATE)
     *
     * @return array of operations
     */
    CrudOperation[] operations() default {};

    /**
     * Whether to inherit validations from entity fields.
     * Default: true
     *
     * @return true to inherit validations
     */
    boolean inheritValidations() default true;

    /**
     * Whether to auto-exclude immutable fields (@CrudXImmutable).
     * Default: true
     *
     * @return true to exclude immutable fields
     */
    boolean excludeImmutable() default true;

    /**
     * Whether to auto-exclude audit fields (createdAt, createdBy, etc.).
     * Default: true
     *
     * @return true to exclude audit fields
     */
    boolean excludeAuditFields() default true;
}
