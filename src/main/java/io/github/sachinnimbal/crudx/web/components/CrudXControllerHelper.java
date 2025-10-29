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

/**
 * General utility methods for CrudXController
 */
@Slf4j
public class CrudXControllerHelper<T extends CrudXBaseEntity<ID>, ID extends Serializable> {

    /**
     * -- GETTER --
     * Get entity class
     */
    @Getter
    private final Class<T> entityClass;
    /**
     * -- GETTER --
     * Get ID class
     */
    @Getter
    private final Class<ID> idClass;
    private final Class<?> controllerClass;

    public CrudXControllerHelper(Class<?> controllerClass) {
        this.controllerClass = controllerClass;
        GenericTypeInfo<T, ID> typeInfo = resolveGenericTypes();
        this.entityClass = typeInfo.entityClass;
        this.idClass = typeInfo.idClass;
    }

    /**
     * Determine database type from entity class
     */
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

    /**
     * Generate service bean name based on entity and database type
     */
    public String getServiceBeanName() {
        DatabaseType databaseType = getDatabaseType();
        return Character.toLowerCase(entityClass.getSimpleName().charAt(0)) +
                entityClass.getSimpleName().substring(1) + "Service" +
                databaseType.name().toLowerCase();
    }

    /**
     * Generate mapper bean name
     */
    public String getMapperBeanName() {
        return Character.toLowerCase(entityClass.getSimpleName().charAt(0)) +
                entityClass.getSimpleName().substring(1) + "MapperCrudX";
    }

    /**
     * Clone entity (basic implementation)
     */
    @SuppressWarnings("unchecked")
    public T cloneEntity(T entity) {
        try {
            return (T) org.springframework.beans.BeanUtils.instantiateClass(entityClass);
        } catch (Exception e) {
            log.warn("Entity cloning failed", e);
            return null;
        }
    }

    /**
     * Determine HTTP status for batch operation results
     */
    public HttpStatus determineBatchStatus(int successCount, int totalCount) {
        if (successCount == 0) {
            return HttpStatus.BAD_REQUEST;
        }
        if (successCount < totalCount) {
            return HttpStatus.PARTIAL_CONTENT;
        }
        return HttpStatus.CREATED;
    }

    /**
     * Format batch response message
     */
    public String formatBatchMessage(int successCount, int skipCount, String operation) {
        if (skipCount > 0) {
            return String.format("Batch %s: %d %s, %d skipped",
                    operation, successCount,
                    operation.equals("deletion") ? "deleted" : "processed",
                    skipCount);
        }
        return String.format("Batch %s: %d entities %s",
                operation, successCount,
                operation.equals("deletion") ? "deleted" : "processed");
    }

    /**
     * Format count message
     */
    public String formatCountMessage(long count) {
        return String.format("Total count: %d", count);
    }

    /**
     * Format exists message
     */
    public String formatExistsMessage(ID id, boolean exists) {
        return String.format("Entity %s", exists ? "exists" : "does not exist");
    }

    /**
     * Format page message
     */
    public String formatPageMessage(int page, int size, long totalElements) {
        return String.format("Retrieved page %d with %d elements (total: %d)",
                page, size, totalElements);
    }

    /**
     * Format list message
     */
    public String formatListMessage(int count) {
        return String.format("Retrieved %d entities", count);
    }

    /**
     * Format large dataset warning
     */
    public String formatLargeDatasetWarning(long totalCount, int returnedSize) {
        return String.format("Large dataset (%d records). Returning first %d. Use /paged for more.",
                totalCount, returnedSize);
    }

    // ==================== PRIVATE METHODS ====================

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

    // ==================== INNER CLASSES ====================

    private record GenericTypeInfo<T extends CrudXBaseEntity<ID>, ID extends Serializable>(
            Class<T> entityClass,
            Class<ID> idClass
    ) {
    }
}