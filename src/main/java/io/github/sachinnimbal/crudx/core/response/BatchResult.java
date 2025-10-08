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

package io.github.sachinnimbal.crudx.core.response;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

/**
 * @author Sachin Nimbal
 * @see <a href="https://www.linkedin.com/in/sachin-nimbal/">LinkedIn Profile</a>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class BatchResult<T> {
    private List<T> createdEntities = new ArrayList<>();
    private int skippedCount = 0;
    private List<String> skippedReasons = new ArrayList<>();

    public BatchResult(List<T> createdEntities, int skippedCount) {
        this.createdEntities = createdEntities;
        this.skippedCount = skippedCount;
    }

    public void addSkippedReason(String reason) {
        this.skippedReasons.add(reason);
    }

    public int getTotalProcessed() {
        return createdEntities.size() + skippedCount;
    }

    public boolean hasSkipped() {
        return skippedCount > 0;
    }
}
