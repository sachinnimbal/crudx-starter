package io.github.sachinnimbal.crudx.dto.annotations;

import java.lang.annotation.*;

/**
 * Customizes field mapping in CrudX DTOs.
 *
 * <p>Usage Examples:</p>
 * <pre>
 * // Map from different entity field
 * {@code @CrudXField(source = "username")}
 * private String displayName;
 *
 * // Format date fields
 * {@code @CrudXField(format = "yyyy-MM-dd HH:mm:ss")}
 * private LocalDateTime registeredAt;
 *
 * // Exclude field from mapping
 * {@code @CrudXField(ignore = true)}
 * private String temporaryField;
 *
 * // Custom expression (future)
 * {@code @CrudXField(expression = "entity.getFirstName() + ' ' + entity.getLastName()")}
 * private String fullName;
 * </pre>
 *
 * @author CrudX Framework
 * @since 1.0.0
 */
@Target(ElementType.FIELD)
@Retention(RetentionPolicy.SOURCE)
@Documented
public @interface CrudXField {

    /**
     * Source field name in entity.
     * If not specified, uses same field name.
     *
     * @return source field name
     */
    String source() default "";

    /**
     * Date/Number format pattern.
     * - For dates: SimpleDateFormat pattern (e.g., "yyyy-MM-dd")
     * - For numbers: DecimalFormat pattern (e.g., "#,##0.00")
     *
     * @return format pattern
     */
    String format() default "";

    /**
     * Whether to ignore this field during mapping.
     * @return true to ignore
     */
    boolean ignore() default false;

    /**
     * Custom mapping expression (future enhancement).
     * Allows complex transformations.
     *
     * @return mapping expression
     */
    String expression() default "";

    /**
     * Default value if source is null (future enhancement).
     * @return default value
     */
    String defaultValue() default "";
}