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
 * Container annotation for multiple {@link CrudXUniqueConstraint} annotations.
 *
 * <p><b>Usage Example:</b></p>
 *
 * <pre>
 * // Correct way to define multiple unique constraints
 * {@literal @}CrudXUniqueConstraints({
 *     {@literal @}CrudXUniqueConstraint(
 *         fields = {"email"},
 *         message = "Email already registered"
 *     ),
 *     {@literal @}CrudXUniqueConstraint(
 *         fields = {"username"},
 *         message = "Username already taken"
 *     ),
 *     {@literal @}CrudXUniqueConstraint(
 *         fields = {"phoneNumber"},
 *         message = "Phone number already registered"
 *     )
 * })
 * public class User extends CrudXMongoEntity&lt;String&gt; {
 *     private String email;
 *     private String username;
 *     private String phoneNumber;
 * }
 * </pre>
 *
 * <p><b>Compile-Time Validation:</b></p>
 * If you try to use multiple {@code @CrudXUniqueConstraint} annotations without this container:
 * <pre>
 * // ❌ WRONG - Will cause compile error
 * {@literal @}CrudXUniqueConstraint(fields = {"email"})
 * {@literal @}CrudXUniqueConstraint(fields = {"username"})
 * public class User extends CrudXMongoEntity&lt;String&gt; {
 *     // Compiler will suggest using @CrudXUniqueConstraints
 * }
 * </pre>
 *
 * @author Sachin Nimbal
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface CrudXUniqueConstraints {
    CrudXUniqueConstraint[] value();
}
