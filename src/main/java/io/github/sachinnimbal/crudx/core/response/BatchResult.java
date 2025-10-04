package io.github.sachinnimbal.crudx.core.response;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;


/**
 * @author Sachin Nimbal
 * @version 1.0.0
 * @since 2025
 * @Contact: <a href="mailto:sachinnimbal9@gmail.com">sachinnimbal9@gmail.com</a>
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