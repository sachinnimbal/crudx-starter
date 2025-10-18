/*
 * Copyright 2025 Sachin Nimbal
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.github.sachinnimbal.crudx.core.annotations;

import java.lang.annotation.*;

/**
 * Annotation to mark a single unique constraint on entity fields.
 *
 * <p><b>Usage Examples:</b></p>
 *
 * <pre>
 * // Example 1: Single field unique constraint
 * {@literal @}CrudXUniqueConstraint(fields = {"email"})
 * public class User extends CrudXMongoEntity&lt;String&gt; {
 *     private String email;
 * }
 *
 * // Example 2: Custom error message
 * {@literal @}CrudXUniqueConstraint(
 *     fields = {"username"},
 *     name = "UK_user_name",
 *     message = "Username already exists. Please choose a different username."
 * )
 * public class User extends CrudXMongoEntity&lt;String&gt; {
 *     private String username;
 * }
 *
 * // Example 3: Composite unique constraint
 * {@literal @}CrudXUniqueConstraint(fields = {"firstName", "lastName", "dateOfBirth"})
 * public class Person extends CrudXMySQLEntity&lt;Long&gt; {
 *     private String firstName;
 *     private String lastName;
 *     private LocalDate dateOfBirth;
 * }
 * </pre>
 *
 * <p><b>Important Notes:</b></p>
 * <ul>
 *   <li>For multiple constraints, use {@link CrudXUniqueConstraints} instead</li>
 *   <li>Constraints are validated before insert/update operations</li>
 *   <li>Throws {@code DuplicateEntityException} if constraint is violated</li>
 * </ul>
 *
 * @author Sachin Nimbal
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@Repeatable(CrudXUniqueConstraints.class)
public @interface CrudXUniqueConstraint {
    String[] fields();

    String name() default "";

    String message() default "Duplicate entry found for unique constraint";
}
