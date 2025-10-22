package io.github.sachinnimbal.crudx.dto.annotations;

import io.github.sachinnimbal.crudx.dto.enums.CrudOperation;

import java.lang.annotation.*; /**
 * Marks a class as a CrudX Response DTO.
 * Generates compile-time mapping code from Entity to DTO.
 *
 * <p>Usage Examples:</p>
 * <pre>
 * // For all query operations
 * {@code @CrudXResponse(User.class)}
 * public class UserResponse { }
 *
 * // For specific operations
 * {@code @CrudXResponse(value = User.class, operations = {GET_ID, GET_ALL})}
 * public class UserDetailResponse { }
 *
 * // For mutation responses (CREATE, UPDATE)
 * {@code @CrudXResponse(value = User.class, operations = {CREATE, UPDATE})}
 * public class UserMutationResponse { }
 * </pre>
 *
 * @author CrudX Framework
 * @since 1.0.0
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.SOURCE)
@Documented
public @interface CrudXResponse {

    /**
     * The entity class this DTO maps from.
     * @return entity class
     */
    Class<?> value();

    /**
     * Operations this DTO applies to.
     * Empty array = applies to all response operations (GET_ID, GET_ALL, GET_PAGED, CREATE, UPDATE, DELETE)
     *
     * @return array of operations
     */
    CrudOperation[] operations() default {};

    /**
     * Whether to include nested objects.
     * Default: true (follows @CrudXNested annotations)
     *
     * @return true to include nested objects
     */
    boolean includeNested() default true;

    /**
     * Whether to include audit fields (createdAt, updatedAt, etc.).
     * Default: true
     *
     * @return true to include audit fields
     */
    boolean includeAuditFields() default true;
}
