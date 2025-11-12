package io.github.sachinnimbal.crudx.core.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class BatchResult<T> {
    private List<T> createdEntities = new ArrayList<>();
    private int successCount = 0;
    private int skippedCount = 0;
    private List<String> skippedReasons = new ArrayList<>();

    // Categorized skip counts
    private Integer duplicateSkipCount;
    private Integer validationSkipCount;

    // Detailed statistics
    private BatchStatistics statistics;

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

    // Add categorized skip reasons
    public void addDuplicateReason(String reason) {
        this.skippedReasons.add("DUPLICATE: " + reason);
        if (duplicateSkipCount == null) duplicateSkipCount = 0;
        duplicateSkipCount++;
    }

    public void addValidationReason(String reason) {
        this.skippedReasons.add("VALIDATION: " + reason);
        if (validationSkipCount == null) validationSkipCount = 0;
        validationSkipCount++;
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

    // Calculate success rate
    public double getSuccessRate() {
        int total = getTotalProcessed();
        return total > 0 ? (successCount * 100.0) / total : 0.0;
    }

    // Get formatted summary message
    public String getSummaryMessage() {
        if (skippedCount == 0) {
            return String.format("All %d records processed successfully", successCount);
        }

        StringBuilder msg = new StringBuilder();
        msg.append(String.format("%d successful, %d skipped", successCount, skippedCount));

        if (duplicateSkipCount != null && duplicateSkipCount > 0) {
            msg.append(String.format(" (%d duplicates", duplicateSkipCount));
            if (validationSkipCount != null && validationSkipCount > 0) {
                msg.append(String.format(", %d validation errors", validationSkipCount));
            }
            msg.append(")");
        } else if (validationSkipCount != null && validationSkipCount > 0) {
            msg.append(String.format(" (%d validation errors)", validationSkipCount));
        }

        return msg.toString();
    }

    // Inner class for detailed statistics
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class BatchStatistics {
        private int totalRecords;
        private int successCount;
        private int skippedCount;
        private int duplicateCount;
        private int validationFailCount;
        private double successRate;
        private Long processingTimeMs;
        private Integer recordsPerSecond;
    }

    // Builder for statistics
    public void setStatistics(int totalRecords, long processingTimeMs) {
        this.statistics = new BatchStatistics();
        this.statistics.totalRecords = totalRecords;
        this.statistics.successCount = this.successCount;
        this.statistics.skippedCount = this.skippedCount;
        this.statistics.duplicateCount = this.duplicateSkipCount != null ? this.duplicateSkipCount : 0;
        this.statistics.validationFailCount = this.validationSkipCount != null ? this.validationSkipCount : 0;
        this.statistics.successRate = getSuccessRate();
        this.statistics.processingTimeMs = processingTimeMs;

        if (processingTimeMs > 0) {
            this.statistics.recordsPerSecond = (int) ((this.successCount * 1000.0) / processingTimeMs);
        }
    }
}