package io.github.sachinnimbal.crudx.web.components;

import io.github.sachinnimbal.crudx.core.enums.DatabaseType;
import io.github.sachinnimbal.crudx.core.model.CrudXBaseEntity;
import io.github.sachinnimbal.crudx.core.model.CrudXMongoEntity;
import io.github.sachinnimbal.crudx.core.model.CrudXMySQLEntity;
import io.github.sachinnimbal.crudx.core.model.CrudXPostgreSQLEntity;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;

import java.io.Serializable;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;

@Slf4j
public class CrudXControllerHelper<T extends CrudXBaseEntity<ID>, ID extends Serializable> {

    @Getter
    private final Class<T> entityClass;
    @Getter
    private final Class<ID> idClass;
    private final Class<?> controllerClass;

    public CrudXControllerHelper(Class<?> controllerClass) {
        this.controllerClass = controllerClass;
        GenericTypeInfo<T, ID> typeInfo = resolveGenericTypes();
        this.entityClass = typeInfo.entityClass;
        this.idClass = typeInfo.idClass;
    }

    public DatabaseType getDatabaseType() {
        if (CrudXMongoEntity.class.isAssignableFrom(entityClass)) {
            return DatabaseType.MONGODB;
        } else if (CrudXPostgreSQLEntity.class.isAssignableFrom(entityClass)) {
            return DatabaseType.POSTGRESQL;
        } else if (CrudXMySQLEntity.class.isAssignableFrom(entityClass)) {
            return DatabaseType.MYSQL;
        }
        throw new IllegalStateException("Unknown database type for: " + entityClass.getSimpleName());
    }

    public String getServiceBeanName() {
        DatabaseType databaseType = getDatabaseType();
        return Character.toLowerCase(entityClass.getSimpleName().charAt(0)) +
                entityClass.getSimpleName().substring(1) + "Service" +
                databaseType.name().toLowerCase();
    }

    public String getMapperBeanName() {
        return Character.toLowerCase(entityClass.getSimpleName().charAt(0)) +
                entityClass.getSimpleName().substring(1) + "MapperCrudX";
    }

    @SuppressWarnings("unchecked")
    public T cloneEntity(T entity) {
        try {
            return (T) org.springframework.beans.BeanUtils.instantiateClass(entityClass);
        } catch (Exception e) {
            log.warn("Entity cloning failed", e);
            return null;
        }
    }

    public HttpStatus determineBatchStatus(int successCount, int totalCount) {
        if (successCount == 0) {
            return HttpStatus.BAD_REQUEST;
        }
        if (successCount < totalCount) {
            return HttpStatus.PARTIAL_CONTENT;
        }
        return HttpStatus.CREATED;
    }

    // Batch message with duplicate breakdown
    public String formatBatchMessage(int successCount, int skipCount, String operation) {
        return formatBatchMessageDetailed(successCount, skipCount, null, null, operation);
    }

    // Detailed batch message with categorization
    public String formatBatchMessageDetailed(int successCount, int skipCount,
                                             Integer duplicates, Integer validationFails,
                                             String operation) {
        if (skipCount == 0) {
            return String.format("Batch %s: %d entities %s successfully",
                    operation, successCount,
                    operation.equals("deletion") ? "deleted" : "processed");
        }

        StringBuilder msg = new StringBuilder();
        msg.append(String.format("Batch %s: %d %s, %d skipped",
                operation, successCount,
                operation.equals("deletion") ? "deleted" : "processed",
                skipCount));

        // Add breakdown if available
        if ((duplicates != null && duplicates > 0) || (validationFails != null && validationFails > 0)) {
            msg.append(" (");
            boolean first = true;

            if (duplicates != null && duplicates > 0) {
                msg.append(String.format("%d duplicates", duplicates));
                first = false;
            }

            if (validationFails != null && validationFails > 0) {
                if (!first) msg.append(", ");
                msg.append(String.format("%d validation errors", validationFails));
            }

            msg.append(")");
        }

        return msg.toString();
    }

    public String formatCountMessage(long count) {
        return String.format("Total count: %d", count);
    }

    public String formatExistsMessage(ID id, boolean exists) {
        return String.format("Entity %s", exists ? "exists" : "does not exist");
    }

    public String formatPageMessage(int page, int size, long totalElements) {
        return String.format("Retrieved page %d with %d elements (total: %d)",
                page, size, totalElements);
    }

    public String formatListMessage(int count) {
        return String.format("Retrieved %d entities", count);
    }

    public String formatLargeDatasetWarning(long totalCount, int returnedSize) {
        return String.format("Large dataset (%d records). Returning first %d. Use /paged for more.",
                totalCount, returnedSize);
    }

    // Format success rate message
    public String formatSuccessRateMessage(int success, int total) {
        if (total == 0) return "No records processed";

        double rate = (success * 100.0) / total;
        return String.format("Success rate: %.2f%% (%d/%d)", rate, success, total);
    }

    // Format duplicate skip message
    public String formatDuplicateMessage(int duplicateCount, int totalSkipped) {
        if (duplicateCount == 0) return "No duplicates detected";

        if (duplicateCount == totalSkipped) {
            return String.format("All %d skipped records were duplicates", duplicateCount);
        }

        return String.format("%d out of %d skipped records were duplicates",
                duplicateCount, totalSkipped);
    }

    @SuppressWarnings("unchecked")
    private GenericTypeInfo<T, ID> resolveGenericTypes() {
        try {
            Type genericSuperclass = controllerClass.getGenericSuperclass();
            if (genericSuperclass instanceof ParameterizedType paramType) {
                Type[] typeArgs = paramType.getActualTypeArguments();
                if (typeArgs.length >= 2) {
                    Class<T> entity = (Class<T>) typeArgs[0];
                    Class<ID> id = (Class<ID>) typeArgs[1];

                    log.debug("Resolved types - Entity: {}, ID: {}",
                            entity.getSimpleName(), id.getSimpleName());

                    return new GenericTypeInfo<>(entity, id);
                }
            }
        } catch (Exception e) {
            log.error("Generic type resolution failed", e);
        }

        throw new IllegalStateException(
                "Could not resolve entity class for controller: " + controllerClass.getSimpleName()
        );
    }

    private record GenericTypeInfo<T extends CrudXBaseEntity<ID>, ID extends Serializable>(
            Class<T> entityClass,
            Class<ID> idClass
    ) {
    }
}