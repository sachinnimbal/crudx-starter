package io.github.sachinnimbal.crudx.web.components;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.github.sachinnimbal.crudx.core.dto.annotations.CrudXResponse;
import io.github.sachinnimbal.crudx.core.dto.mapper.CrudXMapper;
import io.github.sachinnimbal.crudx.core.dto.mapper.CrudXMapperGenerator;
import io.github.sachinnimbal.crudx.core.dto.mapper.CrudXMapperRegistry;
import io.github.sachinnimbal.crudx.core.enums.CrudXOperation;
import io.github.sachinnimbal.crudx.core.enums.MapperMode;
import io.github.sachinnimbal.crudx.core.model.CrudXBaseEntity;
import io.github.sachinnimbal.crudx.core.response.BatchResult;
import io.github.sachinnimbal.crudx.core.response.PageResponse;
import jakarta.servlet.http.HttpServletRequest;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.io.Serializable;
import java.lang.reflect.Field;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Handles all DTO conversion logic for CrudXController
 * Supports both COMPILED and RUNTIME mapper modes
 */
@Slf4j
public class CrudXDTOConverter<T extends CrudXBaseEntity<ID>, ID extends Serializable> {

    private final Class<T> entityClass;
    private final CrudXMapperRegistry dtoRegistry;
    private final CrudXMapperGenerator mapperGenerator;
    private final ObjectMapper objectMapper;

    @Getter
    private MapperMode mapperMode = MapperMode.NONE;

    private CrudXMapper<T, Object, Object> compiledMapper;

    // DTO class caches
    private final Map<CrudXOperation, Class<?>> requestDtoCache = new ConcurrentHashMap<>(8);
    private final Map<CrudXOperation, Class<?>> responseDtoCache = new ConcurrentHashMap<>(8);

    // Entity field caches
    private final Map<String, Field> entityFieldsCache = new ConcurrentHashMap<>();

    public CrudXDTOConverter(
            Class<T> entityClass,
            CrudXMapperRegistry dtoRegistry,
            CrudXMapperGenerator mapperGenerator) {

        this.entityClass = entityClass;
        this.dtoRegistry = dtoRegistry;
        this.mapperGenerator = mapperGenerator;

        this.objectMapper = JsonMapper.builder()
                .addModule(new JavaTimeModule())
                .enable(MapperFeature.ACCEPT_CASE_INSENSITIVE_ENUMS)
                .disable(MapperFeature.USE_GETTERS_AS_SETTERS)
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .build();

        cacheEntityFields();
    }

    /**
     * Initialize DTO mapping with compiled or runtime mapper
     */
    @SuppressWarnings("unchecked")
    public void initializeMapper(Object mapperBean, boolean dtoEnabled) {
        if (!dtoEnabled) {
            mapperMode = MapperMode.NONE;
            log.warn("⚠️  DTO Feature is DISABLED (crudx.dto.enabled=false)");
            return;
        }

        if (dtoRegistry == null || !dtoRegistry.hasDTOMapping(entityClass)) {
            mapperMode = MapperMode.NONE;
            log.info("ℹ️  No DTO mappings for {} - using entity directly", entityClass.getSimpleName());
            return;
        }

        if (mapperBean == null) {
            handleRuntimeMapperFallback();
            return;
        }

        String mapperClassName = mapperBean.getClass().getName();
        boolean isRuntimeMapper = mapperClassName.contains("RuntimeGeneratedMapper");

        if (isRuntimeMapper) {
            handleRuntimeMapper();
        } else {
            handleCompiledMapper((CrudXMapper<T, Object, Object>) mapperBean);
        }

        preCacheDTOClasses();
    }

    private void handleCompiledMapper(CrudXMapper<T, Object, Object> mapper) {
        this.compiledMapper = mapper;
        this.mapperMode = MapperMode.COMPILED;

        log.info("🚀 COMPILED mapper initialized for {}", entityClass.getSimpleName());
        log.info("   ✓ Performance: ZERO runtime overhead, ~100x faster than runtime");
    }

    private void handleRuntimeMapper() {
        mapperMode = MapperMode.RUNTIME;
        compiledMapper = null;

        if (mapperGenerator != null) {
            clearRuntimeMapperCaches();
        }

        logRuntimeMapperWarning();
    }

    private void handleRuntimeMapperFallback() {
        if (mapperGenerator != null) {
            clearRuntimeMapperCaches();
            mapperMode = MapperMode.RUNTIME;
            logRuntimeMapperWarning();
        } else {
            mapperMode = MapperMode.NONE;
            log.error("❌ No mapper available for {}", entityClass.getSimpleName());
        }
    }

    private void logRuntimeMapperWarning() {
        log.warn("════════════════════════════════════════════════════════════════");
        log.warn("⚠️  RUNTIME MAPPER ACTIVE FOR {}", entityClass.getSimpleName());
        log.warn("════════════════════════════════════════════════════════════════");
        log.warn("📊 PERFORMANCE IMPACT: 10-100x SLOWER than compiled mappers");
        log.warn("");
        log.warn("🔧 TO ENABLE FAST COMPILED MAPPERS:");
        log.warn("   GRADLE: annotationProcessor 'io.github.sachinnimbal:crudx-starter:1.2.1'");
        log.warn("   MAVEN: Add to <annotationProcessorPaths>");
        log.warn("════════════════════════════════════════════════════════════════");
    }

    /**
     * Convert Map to Entity (used in POST, PATCH)
     */
    @SuppressWarnings("unchecked")
    public T convertMapToEntity(Map<String, Object> map, CrudXOperation operation) {
        if (mapperMode == MapperMode.NONE) {
            return convertMapToEntityDirectly(map);
        }

        Class<?> requestDtoClass = requestDtoCache.get(operation);
        if (requestDtoClass == null) {
            return convertMapToEntityDirectly(map);
        }

        updateDtoTypeInRequest();

        try {
            Object requestDto = objectMapper.convertValue(map, requestDtoClass);
            long start = System.nanoTime();

            T entity;
            if (mapperMode == MapperMode.COMPILED) {
                if (compiledMapper == null) {
                    throw new IllegalStateException("Compiled mapper is null despite COMPILED mode");
                }
                entity = compiledMapper.toEntity(requestDto);
            } else {
                entity = mapperGenerator.toEntity(requestDto, entityClass);
            }

            trackDtoConversion(start, true);
            return entity;

        } catch (Exception e) {
            log.error("DTO mapping failed for {}: {}, falling back", operation, e.getMessage());
            return convertMapToEntityDirectly(map);
        }
    }

    /**
     * Convert Entity to Response DTO (used in GET by ID, POST response)
     */
    @SuppressWarnings("unchecked")
    public Object convertEntityToResponse(T entity, CrudXOperation operation) {
        if (entity == null || mapperMode == MapperMode.NONE) {
            return entity;
        }

        Class<?> responseDtoClass = responseDtoCache.get(operation);
        if (responseDtoClass == null) {
            return entity;
        }

        updateDtoTypeInRequest();

        try {
            CrudXResponse annotation = responseDtoClass.getAnnotation(CrudXResponse.class);
            long start = System.nanoTime();

            Object response;
            if (mapperMode == MapperMode.COMPILED) {
                response = handleCompiledResponse(entity, responseDtoClass, annotation);
            } else {
                response = handleRuntimeResponse(entity, responseDtoClass, annotation);
            }

            trackDtoConversion(start, true);
            return response;

        } catch (Exception e) {
            log.error("Response mapping failed: {}", e.getMessage());
            return entity;
        }
    }

    /**
     * Convert list of entities to response DTOs (used in GET all)
     */
    @SuppressWarnings("unchecked")
    public List<?> convertEntitiesToResponse(List<T> entities, CrudXOperation operation) {
        if (entities == null || entities.isEmpty() || mapperMode == MapperMode.NONE) {
            return entities;
        }

        Class<?> responseDtoClass = responseDtoCache.get(operation);
        if (responseDtoClass == null) {
            return entities;
        }

        updateDtoTypeInRequest();

        try {
            CrudXResponse annotation = responseDtoClass.getAnnotation(CrudXResponse.class);
            long start = System.nanoTime();

            List<?> responses;
            if (mapperMode == MapperMode.COMPILED) {
                responses = handleCompiledResponseList(entities, responseDtoClass, annotation);
            } else {
                responses = handleRuntimeResponseList(entities, responseDtoClass, annotation);
            }

            trackDtoConversion(start, true);
            return responses;

        } catch (Exception e) {
            log.error("Batch response mapping failed: {}", e.getMessage());
            return entities;
        }
    }

    /**
     * Convert BatchResult to DTO
     */
    @SuppressWarnings("unchecked")
    public Object convertBatchResultToResponse(BatchResult<T> entityResult, CrudXOperation operation) {
        if (mapperMode == MapperMode.NONE) {
            return entityResult;
        }

        try {
            List<?> responseDtos = convertEntitiesToResponse(entityResult.getCreatedEntities(), operation);

            BatchResult<Object> dtoResult = new BatchResult<>();
            dtoResult.setCreatedEntities((List<Object>) responseDtos);
            dtoResult.setSkippedCount(entityResult.getSkippedCount());
            dtoResult.setSkippedReasons(entityResult.getSkippedReasons());

            return dtoResult;

        } catch (Exception e) {
            log.error("BatchResult conversion failed: {}", e.getMessage());
            return entityResult;
        }
    }

    /**
     * Convert PageResponse to DTO
     */
    @SuppressWarnings("unchecked")
    public Object convertPageResponseToDTO(PageResponse<T> entityPage, CrudXOperation operation) {
        if (mapperMode == MapperMode.NONE || entityPage.getContent().isEmpty()) {
            return entityPage;
        }

        try {
            List<?> dtoContent = convertEntitiesToResponse(entityPage.getContent(), operation);

            return PageResponse.builder()
                    .content((List<Object>) dtoContent)
                    .currentPage(entityPage.getCurrentPage())
                    .pageSize(entityPage.getPageSize())
                    .totalElements(entityPage.getTotalElements())
                    .totalPages(entityPage.getTotalPages())
                    .first(entityPage.isFirst())
                    .last(entityPage.isLast())
                    .empty(entityPage.isEmpty())
                    .build();

        } catch (Exception e) {
            log.error("PageResponse conversion failed: {}", e.getMessage());
            return entityPage;
        }
    }

    /**
     * Convert Map to DTO for validation
     */
    public Object convertMapToDTO(Map<String, Object> map, CrudXOperation operation) {
        if (mapperMode == MapperMode.NONE) {
            return null;
        }

        Class<?> requestDtoClass = requestDtoCache.get(operation);
        if (requestDtoClass == null) {
            return null;
        }

        try {
            return objectMapper.convertValue(map, requestDtoClass);
        } catch (Exception e) {
            log.debug("Map→DTO conversion failed: {}", e.getMessage());
            return null;
        }
    }

    // ==================== PRIVATE HELPER METHODS ====================

    private Object handleCompiledResponse(T entity, Class<?> responseDtoClass, CrudXResponse annotation) {
        if (annotation != null && (annotation.includeId() || annotation.includeAudit())) {
            try {
                return mapperGenerator != null
                        ? mapperGenerator.toResponseMap(entity, responseDtoClass)
                        : compiledMapper.toResponse(entity);
            } catch (Exception e) {
                return compiledMapper.toResponse(entity);
            }
        }
        return compiledMapper.toResponse(entity);
    }

    private Object handleRuntimeResponse(T entity, Class<?> responseDtoClass, CrudXResponse annotation) {
        if (annotation != null && (annotation.includeId() || annotation.includeAudit())) {
            return mapperGenerator.toResponseMap(entity, responseDtoClass);
        }
        return mapperGenerator.toResponse(entity, responseDtoClass);
    }

    private List<?> handleCompiledResponseList(List<T> entities, Class<?> responseDtoClass, CrudXResponse annotation) {
        if (annotation != null && (annotation.includeId() || annotation.includeAudit())) {
            return entities.stream()
                    .map(entity -> {
                        try {
                            return mapperGenerator != null
                                    ? mapperGenerator.toResponseMap(entity, responseDtoClass)
                                    : compiledMapper.toResponse(entity);
                        } catch (Exception e) {
                            return compiledMapper.toResponse(entity);
                        }
                    })
                    .collect(Collectors.toList());
        }
        return compiledMapper.toResponseList(entities);
    }

    private List<?> handleRuntimeResponseList(List<T> entities, Class<?> responseDtoClass, CrudXResponse annotation) {
        if (annotation != null && (annotation.includeId() || annotation.includeAudit())) {
            return mapperGenerator.toResponseMapList(entities, responseDtoClass);
        }
        return mapperGenerator.toResponseList(entities, responseDtoClass);
    }

    private T convertMapToEntityDirectly(Map<String, Object> map) {
        try {
            Map<String, Object> processedMap = preprocessEnumFields(map);
            return objectMapper.convertValue(processedMap, entityClass);
        } catch (IllegalArgumentException e) {
            if (e.getMessage() != null && e.getMessage().contains("not one of the values accepted")) {
                throw new IllegalArgumentException("Invalid enum value: " + e.getMessage(), e);
            }
            throw new IllegalArgumentException("Invalid request body format", e);
        }
    }

    private Map<String, Object> preprocessEnumFields(Map<String, Object> map) {
        Map<String, Object> processedMap = new LinkedHashMap<>(map);

        for (Field field : entityFieldsCache.values()) {
            if (field.getType().isEnum()) {
                Object value = processedMap.get(field.getName());
                if (value instanceof String) {
                    Object enumValue = findEnumConstant(field.getType(), (String) value);
                    if (enumValue != null) {
                        processedMap.put(field.getName(), ((Enum<?>) enumValue).name());
                    }
                }
            }
        }

        return processedMap;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private Object findEnumConstant(Class<?> enumClass, String value) {
        if (value == null) return null;

        try {
            return Enum.valueOf((Class<Enum>) enumClass, value);
        } catch (IllegalArgumentException e) {
            for (Object constant : enumClass.getEnumConstants()) {
                if (((Enum) constant).name().equalsIgnoreCase(value)) {
                    return constant;
                }
            }
            return null;
        }
    }

    private void preCacheDTOClasses() {
        if (dtoRegistry == null) return;

        for (CrudXOperation op : CrudXOperation.values()) {
            dtoRegistry.getRequestDTO(entityClass, op)
                    .ifPresent(dtoClass -> requestDtoCache.put(op, dtoClass));
            dtoRegistry.getResponseDTO(entityClass, op)
                    .ifPresent(dtoClass -> responseDtoCache.put(op, dtoClass));
        }

        log.debug("✓ Pre-cached {} request DTOs, {} response DTOs",
                requestDtoCache.size(), responseDtoCache.size());
    }

    private void cacheEntityFields() {
        try {
            for (Field field : getAllFields(entityClass)) {
                entityFieldsCache.put(field.getName(), field);
            }
        } catch (Exception e) {
            log.warn("Failed to cache entity fields: {}", e.getMessage());
        }
    }

    private Field[] getAllFields(Class<?> clazz) {
        List<Field> fields = new ArrayList<>();
        Class<?> current = clazz;

        while (current != null && current != Object.class) {
            Collections.addAll(fields, current.getDeclaredFields());
            current = current.getSuperclass();
        }

        return fields.toArray(new Field[0]);
    }

    private void clearRuntimeMapperCaches() {
        if (mapperGenerator != null) {
            try {
                mapperGenerator.clearCaches();
                log.info("✓ Cleared runtime mapper caches");
            } catch (Exception e) {
                log.warn("Failed to clear caches: {}", e.getMessage());
            }
        }
    }

    private void updateDtoTypeInRequest() {
        try {
            ServletRequestAttributes attrs = (ServletRequestAttributes)
                    RequestContextHolder.getRequestAttributes();
            if (attrs != null) {
                HttpServletRequest request = attrs.getRequest();
                request.setAttribute("dtoType", mapperMode.name());
                request.setAttribute("dtoUsed", mapperMode != MapperMode.NONE);
            }
        } catch (Exception e) {
            log.trace("Failed to update DTO type: {}", e.getMessage());
        }
    }

    private void trackDtoConversion(long startNanos, boolean used) {
        long durationMs = (System.nanoTime() - startNanos) / 1_000_000;

        try {
            ServletRequestAttributes attrs = (ServletRequestAttributes)
                    RequestContextHolder.getRequestAttributes();

            if (attrs != null) {
                HttpServletRequest request = attrs.getRequest();

                Long existingTime = (Long) request.getAttribute("dtoConversionTime");
                long totalTime = (existingTime != null ? existingTime : 0L) + durationMs;

                Integer existingCount = (Integer) request.getAttribute("dtoConversionCount");
                int totalCount = (existingCount != null ? existingCount : 0) + 1;

                request.setAttribute("dtoConversionTime", totalTime);
                request.setAttribute("dtoConversionCount", totalCount);

                if (mapperMode == MapperMode.RUNTIME && durationMs > 50) {
                    log.warn("⚠️  Slow DTO conversion: {} ms (Runtime mode)", durationMs);
                }
            }
        } catch (Exception e) {
            log.trace("DTO tracking failed: {}", e.getMessage());
        }
    }

    // ==================== PUBLIC ACCESSORS ====================

    public boolean isDTOEnabled() {
        return mapperMode != MapperMode.NONE;
    }

    public boolean isUsingCompiledMapper() {
        return mapperMode == MapperMode.COMPILED;
    }
}