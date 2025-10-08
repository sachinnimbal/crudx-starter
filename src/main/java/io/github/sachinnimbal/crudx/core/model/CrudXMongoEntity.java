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

import lombok.Data;
import lombok.EqualsAndHashCode;
import org.springframework.data.annotation.Id;

import java.io.Serializable;

/**
 * @author Sachin Nimbal
 * @see <a href="https://www.linkedin.com/in/sachin-nimbal/">LinkedIn Profile</a>
 */
@Data
@EqualsAndHashCode(callSuper = false)
public abstract class CrudXMongoEntity<ID extends Serializable> extends CrudXBaseEntity<ID> {

    @Id
    private ID id;

    private CrudXAudit audit = new CrudXAudit();

    public void onCreate() {
        if (audit == null) {
            audit = new CrudXAudit();
        }
        audit.onCreate();
    }

    public void onUpdate() {
        if (audit == null) {
            audit = new CrudXAudit();
        }
        audit.onUpdate();
    }
}
