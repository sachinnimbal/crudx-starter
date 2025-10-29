package io.github.sachinnimbal.crudx.core.response;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class BatchResult<T> {
    private List<T> createdEntities = new ArrayList<>();
    private int successCount = 0;
    private int skippedCount = 0;
    private List<String> skippedReasons = new ArrayList<>();

    public BatchResult(List<T> createdEntities, int skippedCount) {
        this.createdEntities = createdEntities;
        this.successCount = createdEntities.size();
        this.skippedCount = skippedCount;
    }

    public BatchResult(int successCount, int skippedCount) {
        this.successCount = successCount;
        this.skippedCount = skippedCount;
        this.createdEntities = new ArrayList<>();
    }

    public void addSkippedReason(String reason) {
        this.skippedReasons.add(reason);
    }

    public int getTotalProcessed() {
        return successCount + skippedCount;
    }

    public boolean hasSkipped() {
        return skippedCount > 0;
    }

    public int getSuccessCount() {
        return successCount > 0 ? successCount : createdEntities.size();
    }
}