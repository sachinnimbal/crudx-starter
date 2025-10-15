package io.github.sachinnimbal.crudx.dto.generator;

import io.github.sachinnimbal.crudx.core.enums.Direction;
import io.github.sachinnimbal.crudx.core.enums.OperationType;
import io.github.sachinnimbal.crudx.dto.registry.GeneratedMapperRegistry;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.lang.reflect.Field;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Simplified incremental generator for unified mappers
 */
@Slf4j
@Component
public class IncrementalMapperGenerator {

    private final DtoMapperGenerator generator;
    private final GeneratedMapperRegistry registry;

    // Track field signatures to detect changes (simplified key)
    private final Map<String, String> fieldSignatures = new ConcurrentHashMap<>();

    public IncrementalMapperGenerator(DtoMapperGenerator generator,
                                      GeneratedMapperRegistry registry) {
        this.generator = generator;
        this.registry = registry;
    }

    /**
     * Smart regeneration: Only regenerates if fields changed
     * Note: Operation/Direction parameters kept for backward compatibility
     */
    public <E, D> RegenerationResult regenerateIfNeeded(
            Class<D> dtoClass,
            Class<E> entityClass,
            OperationType operation,
            Direction direction) {

        String key = getSimpleMapperKey(entityClass, dtoClass);

        // Calculate current signature
        String currentSignature = calculateSignature(dtoClass, entityClass);

        // Check if signature changed
        String previousSignature = fieldSignatures.get(key);

        if (previousSignature != null && previousSignature.equals(currentSignature)) {
            log.debug("No changes detected for {}, skipping regeneration", key);
            return RegenerationResult.builder()
                    .regenerated(false)
                    .reason("No field changes detected")
                    .key(key)
                    .build();
        }

        // Detect what changed
        ChangeAnalysis changes = analyzeChanges(dtoClass, entityClass, previousSignature, currentSignature);

        log.info("Changes detected in {}: {}", key, changes.getSummary());

        // Regenerate unified mapper (operation/direction don't matter)
        try {
            var mapper = generator.generateMapper(dtoClass, entityClass, operation, direction);

            if (mapper != null) {
                registry.register(mapper);
                fieldSignatures.put(key, currentSignature);

                log.info("✓ Successfully regenerated mapper: {}", key);

                return RegenerationResult.builder()
                        .regenerated(true)
                        .reason("Fields changed: " + changes.getSummary())
                        .key(key)
                        .changes(changes)
                        .build();
            } else {
                return RegenerationResult.builder()
                        .regenerated(false)
                        .reason("Mapper generation failed")
                        .key(key)
                        .build();
            }

        } catch (Exception e) {
            log.error("Failed to regenerate mapper: {}", key, e);
            return RegenerationResult.builder()
                    .regenerated(false)
                    .reason("Generation error: " + e.getMessage())
                    .key(key)
                    .error(e)
                    .build();
        }
    }

    /**
     * Calculate signature of DTO and Entity fields
     */
    private String calculateSignature(Class<?> dtoClass, Class<?> entityClass) {
        try {
            StringBuilder signature = new StringBuilder();

            // DTO fields
            signature.append("DTO:").append(dtoClass.getName()).append(";");
            List<Field> dtoFields = getAllFields(dtoClass);
            dtoFields.sort(Comparator.comparing(Field::getName));
            for (Field field : dtoFields) {
                signature.append(field.getName())
                        .append(":")
                        .append(field.getType().getName())
                        .append(";");
            }

            // Entity fields
            signature.append("ENTITY:").append(entityClass.getName()).append(";");
            List<Field> entityFields = getAllFields(entityClass);
            entityFields.sort(Comparator.comparing(Field::getName));
            for (Field field : entityFields) {
                signature.append(field.getName())
                        .append(":")
                        .append(field.getType().getName())
                        .append(";");
            }

            // Generate hash
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] hash = md.digest(signature.toString().getBytes());
            StringBuilder hexString = new StringBuilder();
            for (byte b : hash) {
                hexString.append(String.format("%02x", b));
            }

            return hexString.toString();

        } catch (Exception e) {
            log.error("Error calculating signature", e);
            return UUID.randomUUID().toString(); // Force regeneration on error
        }
    }

    /**
     * Analyze what changed between versions
     */
    private ChangeAnalysis analyzeChanges(Class<?> dtoClass, Class<?> entityClass,
                                          String previousSignature, String currentSignature) {

        ChangeAnalysis analysis = new ChangeAnalysis();

        if (previousSignature == null) {
            analysis.setNewMapper(true);
            analysis.setSummary("New mapper");
            return analysis;
        }

        // Get current fields
        Set<String> currentDtoFields = getFieldNames(dtoClass);
        Set<String> currentEntityFields = getFieldNames(entityClass);

        // For detailed analysis, you'd need to store previous field info
        // For now, just mark as changed
        analysis.setFieldsChanged(true);
        analysis.setSummary("Fields modified (signature changed)");

        return analysis;
    }

    private Set<String> getFieldNames(Class<?> clazz) {
        Set<String> names = new HashSet<>();
        for (Field field : getAllFields(clazz)) {
            names.add(field.getName());
        }
        return names;
    }

    private List<Field> getAllFields(Class<?> clazz) {
        List<Field> fields = new ArrayList<>();
        while (clazz != null && clazz != Object.class) {
            fields.addAll(Arrays.asList(clazz.getDeclaredFields()));
            clazz = clazz.getSuperclass();
        }
        return fields;
    }

    /**
     * Simplified mapper key (no operation/direction)
     */
    private String getSimpleMapperKey(Class<?> entityClass, Class<?> dtoClass) {
        return entityClass.getName() + "_" + dtoClass.getName();
    }

    /**
     * Get current signature for a mapper
     */
    public String getCurrentSignature(Class<?> entityClass, Class<?> dtoClass) {
        String key = getSimpleMapperKey(entityClass, dtoClass);
        return fieldSignatures.get(key);
    }

    /**
     * Clear all signatures (force full regeneration)
     */
    public void clearSignatures() {
        fieldSignatures.clear();
        log.info("Cleared all field signatures");
    }

    /**
     * Get statistics
     */
    public Map<String, Object> getStatistics() {
        Map<String, Object> stats = new HashMap<>();
        stats.put("trackedMappers", fieldSignatures.size());
        stats.put("signatures", new HashMap<>(fieldSignatures));
        return stats;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class RegenerationResult {
        private boolean regenerated;
        private String reason;
        private String key;
        private ChangeAnalysis changes;
        private Exception error;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ChangeAnalysis {
        private boolean newMapper;
        private boolean fieldsChanged;
        private Set<String> addedFields = new HashSet<>();
        private Set<String> removedFields = new HashSet<>();
        private Set<String> modifiedFields = new HashSet<>();
        private String summary;

        public boolean hasChanges() {
            return newMapper || fieldsChanged ||
                    !addedFields.isEmpty() ||
                    !removedFields.isEmpty() ||
                    !modifiedFields.isEmpty();
        }
    }
}