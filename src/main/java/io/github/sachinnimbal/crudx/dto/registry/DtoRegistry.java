package io.github.sachinnimbal.crudx.dto.registry;

import io.github.sachinnimbal.crudx.core.annotations.dto.*;
import io.github.sachinnimbal.crudx.core.enums.Direction;
import io.github.sachinnimbal.crudx.core.enums.OperationType;
import io.github.sachinnimbal.crudx.dto.metadata.DtoMetadata;
import io.github.sachinnimbal.crudx.dto.metadata.EndpointDescriptor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * REFACTORED: Simplified registry - only tracks DTO-Entity relationships
 * Actual mapping is now handled by generated mappers
 */
@Slf4j
@Component
public class DtoRegistry {

    // Track which DTOs exist for which endpoints
    private final Map<EndpointDescriptor, Class<?>> dtoMappings = new ConcurrentHashMap<>();
    private final Set<Class<?>> scannedDtos = ConcurrentHashMap.newKeySet();

    /**
     * Find DTO class for an operation
     * Returns null if no DTO configured (means use entity directly)
     */
    public DtoMetadata findDto(Class<?> entityClass, OperationType operation, Direction direction) {
        EndpointDescriptor descriptor = EndpointDescriptor.builder()
                .entityClass(entityClass)
                .operation(operation)
                .direction(direction)
                .build();

        Class<?> dtoClass = dtoMappings.get(descriptor);

        if (dtoClass == null) {
            return null; // No DTO configured
        }

        return DtoMetadata.builder()
                .dtoClass(dtoClass)
                .entityClass(entityClass)
                .operation(operation)
                .direction(direction)
                .build();
    }

    /**
     * Register a DTO class
     */
    public synchronized void registerDto(Class<?> dtoClass) {
        if (scannedDtos.contains(dtoClass)) {
            return; // Already registered
        }

        try {
            if (dtoClass.isAnnotationPresent(CrudXCreateRequestDto.class)) {
                registerCreateDto(dtoClass);
            } else if (dtoClass.isAnnotationPresent(CrudXUpdateRequestDto.class)) {
                registerUpdateDto(dtoClass);
            } else if (dtoClass.isAnnotationPresent(CrudXBatchCreateRequestDto.class)) {
                registerBatchCreateDto(dtoClass);
            } else if (dtoClass.isAnnotationPresent(CrudXResponseDto.class)) {
                registerResponseDto(dtoClass);
            } else if (dtoClass.isAnnotationPresent(CrudXDto.class)) {
                registerUniversalDto(dtoClass);
            }

            scannedDtos.add(dtoClass);
            log.debug("✓ Registered DTO: {}", dtoClass.getSimpleName());

        } catch (Exception e) {
            log.error("Failed to register DTO: {}", dtoClass.getName(), e);
        }
    }

    private void registerCreateDto(Class<?> dtoClass) {
        CrudXCreateRequestDto annotation = dtoClass.getAnnotation(CrudXCreateRequestDto.class);
        Class<?> entityClass = annotation.entity();

        EndpointDescriptor descriptor = EndpointDescriptor.builder()
                .entityClass(entityClass)
                .operation(OperationType.CREATE)
                .direction(Direction.REQUEST)
                .build();

        dtoMappings.put(descriptor, dtoClass);
    }

    private void registerUpdateDto(Class<?> dtoClass) {
        CrudXUpdateRequestDto annotation = dtoClass.getAnnotation(CrudXUpdateRequestDto.class);
        Class<?> entityClass = annotation.entity();

        EndpointDescriptor descriptor = EndpointDescriptor.builder()
                .entityClass(entityClass)
                .operation(OperationType.UPDATE)
                .direction(Direction.REQUEST)
                .build();

        dtoMappings.put(descriptor, dtoClass);
    }

    private void registerBatchCreateDto(Class<?> dtoClass) {
        CrudXBatchCreateRequestDto annotation = dtoClass.getAnnotation(CrudXBatchCreateRequestDto.class);
        Class<?> entityClass = annotation.entity();

        EndpointDescriptor descriptor = EndpointDescriptor.builder()
                .entityClass(entityClass)
                .operation(OperationType.BATCH_CREATE)
                .direction(Direction.REQUEST)
                .build();

        dtoMappings.put(descriptor, dtoClass);
    }

    private void registerResponseDto(Class<?> dtoClass) {
        CrudXResponseDto annotation = dtoClass.getAnnotation(CrudXResponseDto.class);
        Class<?> entityClass = annotation.entity();
        OperationType[] operations = annotation.operations();

        // Default to all read operations if not specified
        if (operations.length == 0) {
            operations = new OperationType[]{
                    OperationType.GET_BY_ID,
                    OperationType.GET_ALL,
                    OperationType.GET_PAGED
            };
        }

        for (OperationType op : operations) {
            EndpointDescriptor descriptor = EndpointDescriptor.builder()
                    .entityClass(entityClass)
                    .operation(op)
                    .direction(Direction.RESPONSE)
                    .build();

            dtoMappings.put(descriptor, dtoClass);
        }
    }

    private void registerUniversalDto(Class<?> dtoClass) {
        CrudXDto annotation = dtoClass.getAnnotation(CrudXDto.class);
        Class<?> entityClass = annotation.entity();
        OperationType[] operations = annotation.operations();

        for (OperationType op : operations) {
            Direction direction = isReadOperation(op) ? Direction.RESPONSE : Direction.REQUEST;

            EndpointDescriptor descriptor = EndpointDescriptor.builder()
                    .entityClass(entityClass)
                    .operation(op)
                    .direction(direction)
                    .build();

            dtoMappings.put(descriptor, dtoClass);
        }
    }

    private boolean isReadOperation(OperationType op) {
        return op == OperationType.GET_BY_ID ||
                op == OperationType.GET_ALL ||
                op == OperationType.GET_PAGED ||
                op == OperationType.COUNT ||
                op == OperationType.EXISTS;
    }

    /**
     * Get all registered DTO classes
     */
    public Set<Class<?>> getAllDtos() {
        return Set.copyOf(scannedDtos);
    }

    /**
     * Check if DTO is registered for operation
     */
    public boolean hasDtoForOperation(Class<?> entityClass, OperationType operation, Direction direction) {
        EndpointDescriptor descriptor = EndpointDescriptor.builder()
                .entityClass(entityClass)
                .operation(operation)
                .direction(direction)
                .build();

        return dtoMappings.containsKey(descriptor);
    }
}