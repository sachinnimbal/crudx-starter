package io.github.sachinnimbal.crudx.dto.registry;

import io.github.sachinnimbal.crudx.core.annotations.dto.CrudXRequestDto;
import io.github.sachinnimbal.crudx.core.annotations.dto.CrudXResponseDto;
import io.github.sachinnimbal.crudx.core.enums.OperationType;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Component
public class CrudXDtoRegistry {

    // Entity + Operation -> Request DTO Class
    private final Map<EntityOperationKey, Class<?>> requestDtoMappings = new ConcurrentHashMap<>();

    // Entity + Operation -> Response DTO Class
    private final Map<EntityOperationKey, Class<?>> responseDtoMappings = new ConcurrentHashMap<>();

    // Entity -> All DTOs (for mapper generation)
    private final Map<Class<?>, Set<Class<?>>> entityToDtos = new ConcurrentHashMap<>();

    // All registered DTOs
    private final Set<Class<?>> allDtos = ConcurrentHashMap.newKeySet();

    /**
     * Auto-detect and register DTO
     */
    public void registerDto(Class<?> dtoClass) {
        if (dtoClass.isAnnotationPresent(CrudXRequestDto.class)) {
            registerRequestDto(dtoClass);
        } else if (dtoClass.isAnnotationPresent(CrudXResponseDto.class)) {
            registerResponseDto(dtoClass);
        } else {
            log.warn("Class {} has no @CrudXRequestDto or @CrudXResponseDto annotation",
                    dtoClass.getName());
        }
    }

    /**
     * Register @CrudXRequestDto
     */
    private void registerRequestDto(Class<?> dtoClass) {
        CrudXRequestDto annotation = dtoClass.getAnnotation(CrudXRequestDto.class);
        Class<?> entityClass = annotation.entity();
        OperationType[] operations = annotation.operations();

        for (OperationType operation : operations) {
            EntityOperationKey key = new EntityOperationKey(entityClass, operation);
            requestDtoMappings.put(key, dtoClass);

            log.debug("✓ Registered REQUEST DTO: {} -> {} for {}",
                    entityClass.getSimpleName(),
                    dtoClass.getSimpleName(),
                    operation);
        }

        entityToDtos.computeIfAbsent(entityClass, k -> ConcurrentHashMap.newKeySet()).add(dtoClass);
        allDtos.add(dtoClass);
    }

    /**
     * Register @CrudXResponseDto
     */
    private void registerResponseDto(Class<?> dtoClass) {
        CrudXResponseDto annotation = dtoClass.getAnnotation(CrudXResponseDto.class);
        Class<?> entityClass = annotation.entity();
        OperationType[] operations = annotation.operations();

        for (OperationType operation : operations) {
            EntityOperationKey key = new EntityOperationKey(entityClass, operation);
            responseDtoMappings.put(key, dtoClass);

            log.debug("✓ Registered RESPONSE DTO: {} -> {} for {}",
                    entityClass.getSimpleName(),
                    dtoClass.getSimpleName(),
                    operation);
        }

        entityToDtos.computeIfAbsent(entityClass, k -> ConcurrentHashMap.newKeySet()).add(dtoClass);
        allDtos.add(dtoClass);
    }

    /**
     * Get REQUEST DTO class for entity and operation
     * Returns null if no DTO configured (caller should use entity)
     */
    public Class<?> getRequestDtoClass(Class<?> entityClass, OperationType operation) {
        EntityOperationKey key = new EntityOperationKey(entityClass, operation);
        return requestDtoMappings.get(key);
    }

    /**
     * Get RESPONSE DTO class for entity and operation
     * Returns null if no DTO configured (caller should return entity)
     */
    public Class<?> getResponseDtoClass(Class<?> entityClass, OperationType operation) {
        EntityOperationKey key = new EntityOperationKey(entityClass, operation);
        return responseDtoMappings.get(key);
    }

    /**
     * Check if REQUEST DTO exists for operation
     */
    public boolean hasRequestDto(Class<?> entityClass, OperationType operation) {
        EntityOperationKey key = new EntityOperationKey(entityClass, operation);
        return requestDtoMappings.containsKey(key);
    }

    /**
     * Check if RESPONSE DTO exists for operation
     */
    public boolean hasResponseDto(Class<?> entityClass, OperationType operation) {
        EntityOperationKey key = new EntityOperationKey(entityClass, operation);
        return responseDtoMappings.containsKey(key);
    }

    /**
     * Get all DTOs for entity (for mapper generation)
     */
    public Set<Class<?>> getDtosForEntity(Class<?> entityClass) {
        return entityToDtos.getOrDefault(entityClass, Collections.emptySet());
    }

    /**
     * Get all registered DTOs
     */
    public Set<Class<?>> getAllDtos() {
        return Collections.unmodifiableSet(allDtos);
    }

    /**
     * Get all entities with DTOs
     */
    public Set<Class<?>> getAllEntities() {
        return Collections.unmodifiableSet(entityToDtos.keySet());
    }

    /**
     * Clear all registrations (for testing)
     */
    public void clear() {
        requestDtoMappings.clear();
        responseDtoMappings.clear();
        entityToDtos.clear();
        allDtos.clear();
        log.info("Cleared DTO registry");
    }

    // ===== COMPOSITE KEY =====

    private static class EntityOperationKey {
        private final Class<?> entityClass;
        private final OperationType operation;

        EntityOperationKey(Class<?> entityClass, OperationType operation) {
            this.entityClass = entityClass;
            this.operation = operation;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof EntityOperationKey)) return false;
            EntityOperationKey that = (EntityOperationKey) o;
            return entityClass.equals(that.entityClass) && operation == that.operation;
        }

        @Override
        public int hashCode() {
            return Objects.hash(entityClass, operation);
        }
    }
}