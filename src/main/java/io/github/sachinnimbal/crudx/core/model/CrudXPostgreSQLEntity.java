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

package io.github.sachinnimbal.crudx.core.model;

import jakarta.persistence.*;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.io.Serializable;

/**
 * @author Sachin Nimbal
 * @see <a href="https://www.linkedin.com/in/sachin-nimbal/">LinkedIn Profile</a>
 */
@Data
@MappedSuperclass
@EqualsAndHashCode(callSuper = false)
public abstract class CrudXPostgreSQLEntity<ID extends Serializable> extends CrudXBaseEntity<ID> {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE)
    private ID id;

    @Embedded
    private CrudXAudit audit = new CrudXAudit();

    @PrePersist
    public void onCreate() {
        if (audit == null) {
            audit = new CrudXAudit();
        }
        audit.onCreate();
    }

    @PreUpdate
    public void onUpdate() {
        if (audit == null) {
            audit = new CrudXAudit();
        }
        audit.onUpdate();
    }
}
