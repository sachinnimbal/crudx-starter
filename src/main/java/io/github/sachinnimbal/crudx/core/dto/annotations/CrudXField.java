package io.github.sachinnimbal.crudx.core.dto.annotations;

import java.lang.annotation.*;

/**
 * Customizes field mapping behavior in DTOs.
 *
 * <p><b>Usage Examples:</b></p>
 * <pre>
 * {@literal @}CrudXResponse(User.class)
 * public class UserResponse {
 *
 *     // Map from different entity field
 *     {@literal @}CrudXField(source = "username")
 *     private String displayName;
 *
 *     // Custom date format
 *     {@literal @}CrudXField(format = "yyyy-MM-dd")
 *     private LocalDateTime createdAt;
 *
 *     // Ignore this field (don't map)
 *     {@literal @}CrudXField(ignore = true)
 *     private String internalField;
 *
 *     // Transform during mapping
 *     {@literal @}CrudXField(transformer = "toUpperCase")
 *     private String code;
 * }
 * </pre>
 *
 * @author Sachin Nimbal
 * @since 1.0.2
 */
@Target(ElementType.FIELD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface CrudXField {

    /**
     * Source field name in the entity.
     * If empty, uses the DTO field name.
     */
    String source() default "";

    /**
     * Date/number format pattern.
     * Examples: "yyyy-MM-dd", "#,##0.00"
     */
    String format() default "";

    /**
     * Exclude this field from mapping.
     */
    boolean ignore() default false;

    /**
     * Transformer method name (optional).
     * Method signature: String transform(Object value)
     */
    String transformer() default "";

    /**
     * Default value if source is null.
     */
    String defaultValue() default "";

    /**
     * Make field required (validation).
     */
    boolean required() default false;
}