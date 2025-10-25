package io.github.sachinnimbal.crudx.core.dto.mapper;

import io.github.sachinnimbal.crudx.core.dto.annotations.CrudXField;
import io.github.sachinnimbal.crudx.core.dto.annotations.CrudXNested;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.stream.Collectors;


@Slf4j
@Component
public class CrudXMapperGenerator {

    private static final MethodHandles.Lookup LOOKUP = MethodHandles.lookup();
    private static final int MAX_DEPTH = 15;
    private static final int PARALLEL_THRESHOLD = 100;

    // Caches
    private final Map<String, MappingPlan> mappingPlanCache = new ConcurrentHashMap<>(512);
    private final Map<String, DateTimeFormatter> formatters = new ConcurrentHashMap<>(32);
    private final Map<Class<?>, Constructor<?>> constructorCache = new ConcurrentHashMap<>(256);
    private final Map<String, FieldAccessor> accessorCache = new ConcurrentHashMap<>(2048);
    private final Map<Class<?>, Field[]> fieldCache = new ConcurrentHashMap<>(256);
    private final Map<Class<?>, TypeConverter> typeConverterCache = new ConcurrentHashMap<>(128);
    private final Map<String, Object> defaultValueCache = new ConcurrentHashMap<>(256);

    // Nested mapping depth tracker (thread-safe)
    private final ThreadLocal<Map<Object, Integer>> depthTracker =
            ThreadLocal.withInitial(WeakHashMap::new);

    // ==================== PUBLIC API ====================

    public <E, R> E toEntity(R request, Class<E> entityClass) {
        if (request == null) return null;

        long startNano = System.nanoTime();
        try {
            String planKey = request.getClass().getName() + "->" + entityClass.getName();
            MappingPlan plan = mappingPlanCache.computeIfAbsent(planKey,
                    k -> createMappingPlan(request.getClass(), entityClass, true));

            E entity = instantiateFast(entityClass);
            executeMappingPlan(request, entity, plan, 0);

            if (log.isTraceEnabled()) {
                long elapsedMicros = (System.nanoTime() - startNano) / 1000;
                log.trace("✓ Mapped {} → {} in {} μs",
                        request.getClass().getSimpleName(),
                        entityClass.getSimpleName(),
                        elapsedMicros);
            }

            return entity;
        } catch (Throwable e) {
            log.error("Fast mapping failed: {}", e.getMessage(), e);
            throw new RuntimeException("DTO mapping failed: " + e.getMessage(), e);
        } finally {
            depthTracker.get().clear();
        }
    }

    public <E, R> void updateEntity(R request, E entity) {
        if (request == null || entity == null) return;

        try {
            String planKey = request.getClass().getName() + "->" + entity.getClass().getName() + ":UPDATE";
            MappingPlan plan = mappingPlanCache.computeIfAbsent(planKey,
                    k -> createMappingPlan(request.getClass(), entity.getClass(), true));

            executeMappingPlan(request, entity, plan, 0);
        } catch (Throwable e) {
            log.error("Fast update failed: {}", e.getMessage(), e);
            throw new RuntimeException("Update failed: " + e.getMessage(), e);
        } finally {
            depthTracker.get().clear();
        }
    }

    public <E, S> S toResponse(E entity, Class<S> responseClass) {
        if (entity == null) return null;

        long startNano = System.nanoTime();
        try {
            String planKey = entity.getClass().getName() + "->" + responseClass.getName();
            MappingPlan plan = mappingPlanCache.computeIfAbsent(planKey,
                    k -> createMappingPlan(entity.getClass(), responseClass, false));

            S response = instantiateFast(responseClass);
            executeMappingPlan(entity, response, plan, 0);

            if (log.isTraceEnabled()) {
                long elapsedMicros = (System.nanoTime() - startNano) / 1000;
                log.trace("✓ Mapped {} → {} in {} μs",
                        entity.getClass().getSimpleName(),
                        responseClass.getSimpleName(),
                        elapsedMicros);
            }

            return response;
        } catch (Throwable e) {
            log.error("Fast response mapping failed: {}", e.getMessage(), e);
            throw new RuntimeException("Response mapping failed: " + e.getMessage(), e);
        } finally {
            depthTracker.get().clear();
        }
    }

    public <E, S> List<S> toResponseList(List<E> entities, Class<S> responseClass) {
        if (entities == null || entities.isEmpty()) return Collections.emptyList();

        long startNano = System.nanoTime();

        List<S> result = entities.size() > PARALLEL_THRESHOLD
                ? entities.parallelStream().map(e -> toResponse(e, responseClass)).collect(Collectors.toList())
                : entities.stream().map(e -> toResponse(e, responseClass)).collect(Collectors.toList());

        if (log.isDebugEnabled()) {
            long elapsedMs = (System.nanoTime() - startNano) / 1_000_000;
            double throughput = (entities.size() * 1000.0) / Math.max(elapsedMs, 1);
            log.debug("✓ Mapped {} entities in {} ms ({} entities/sec)",
                    entities.size(), elapsedMs, (int) throughput);
        }

        return result;
    }

    public <E> Map<String, Object> toResponseMap(E entity, Class<?> responseClass) {
        if (entity == null) return null;

        try {
            Object response = toResponse(entity, responseClass);
            return convertToMapFast(response);
        } catch (Throwable e) {
            log.error("Fast map conversion failed: {}", e.getMessage());
            return Collections.emptyMap();
        }
    }

    public <E> List<Map<String, Object>> toResponseMapList(List<E> entities, Class<?> responseClass) {
        if (entities == null) return null;

        return entities.size() > PARALLEL_THRESHOLD
                ? entities.parallelStream().map(e -> toResponseMap(e, responseClass)).collect(Collectors.toList())
                : entities.stream().map(e -> toResponseMap(e, responseClass)).collect(Collectors.toList());
    }

    // ==================== CORE ENGINE ====================

    private MappingPlan createMappingPlan(Class<?> sourceClass, Class<?> targetClass,
                                          boolean isDTOToEntity) {
        long startNano = System.nanoTime();

        MappingPlan plan = new MappingPlan();
        plan.sourceClass = sourceClass;
        plan.targetClass = targetClass;
        plan.isDTOToEntity = isDTOToEntity;
        plan.fieldMappings = new ArrayList<>();

        Class<?> dtoClass = isDTOToEntity ? sourceClass : targetClass;
        Class<?> entityClass = isDTOToEntity ? targetClass : sourceClass;

        for (Field dtoField : getFieldsFast(dtoClass)) {
            if (shouldSkipField(dtoField)) continue;

            try {
                FieldMapping mapping = createFieldMapping(dtoField, entityClass, isDTOToEntity);
                if (mapping != null) {
                    plan.fieldMappings.add(mapping);
                }
            } catch (Exception e) {
                log.debug("Skipping field {}.{}: {}",
                        dtoClass.getSimpleName(), dtoField.getName(), e.getMessage());
            }
        }

        long elapsedMicros = (System.nanoTime() - startNano) / 1000;
        log.info("✓ Compiled mapping plan: {} → {} ({} fields, {} μs)",
                sourceClass.getSimpleName(), targetClass.getSimpleName(),
                plan.fieldMappings.size(), elapsedMicros);

        return plan;
    }

    private FieldMapping createFieldMapping(Field dtoField, Class<?> entityClass,
                                            boolean isDTOToEntity) throws Exception {
        String dtoFieldName = dtoField.getName();
        CrudXField fieldAnnotation = dtoField.getAnnotation(CrudXField.class);
        CrudXNested nestedAnnotation = dtoField.getAnnotation(CrudXNested.class);

        // Check if field should be ignored
        if (fieldAnnotation != null && fieldAnnotation.ignore()) {
            return null;
        }

        // Resolve entity field name
        String entityFieldName = fieldAnnotation != null && !fieldAnnotation.source().isEmpty()
                ? fieldAnnotation.source()
                : dtoFieldName;

        Field entityField = findFieldFast(entityClass, entityFieldName);
        if (entityField == null) {
            log.debug("Entity field '{}' not found in {}",
                    entityFieldName, entityClass.getSimpleName());
            return null;
        }

        FieldMapping mapping = new FieldMapping();

        if (isDTOToEntity) {
            mapping.sourceAccessor = createFastGetter(dtoField, dtoField.getDeclaringClass());
            mapping.targetAccessor = createFastSetter(entityField, entityClass);
            mapping.sourceField = dtoField;
            mapping.targetField = entityField;
        } else {
            mapping.sourceAccessor = createFastGetter(entityField, entityClass);
            mapping.targetAccessor = createFastSetter(dtoField, dtoField.getDeclaringClass());
            mapping.sourceField = entityField;
            mapping.targetField = dtoField;
        }

        mapping.needsConversion = !dtoField.getType().equals(entityField.getType());
        mapping.fieldAnnotation = fieldAnnotation;
        mapping.nestedAnnotation = nestedAnnotation;
        mapping.isNested = nestedAnnotation != null || isComplexType(dtoField.getType());

        return mapping;
    }

    private void executeMappingPlan(Object source, Object target, MappingPlan plan, int currentDepth)
            throws Throwable {

        Map<Object, Integer> depths = depthTracker.get();
        if (depths.containsKey(source) && depths.get(source) >= currentDepth) {
            log.trace("Circular reference detected, skipping");
            return;
        }
        depths.put(source, currentDepth);

        for (FieldMapping mapping : plan.fieldMappings) {
            try {
                Object value = mapping.sourceAccessor.get(source);

                if (value == null && mapping.fieldAnnotation != null) {
                    value = getDefaultValue(mapping.fieldAnnotation, mapping.targetField.getType());
                }

                if (value == null && mapping.fieldAnnotation != null && mapping.fieldAnnotation.required()) {
                    throw new IllegalArgumentException(
                            "Required field '" + mapping.sourceField.getName() + "' is null");
                }

                if (value == null) {
                    handleNullStrategy(mapping, target);
                    continue;
                }

                if (mapping.fieldAnnotation != null && !mapping.fieldAnnotation.transformer().isEmpty()) {
                    value = applyTransformer(value, mapping.fieldAnnotation.transformer(), source);
                }

                if (mapping.isNested && mapping.nestedAnnotation != null) {
                    value = handleNestedMapping(value, mapping, currentDepth);
                } else if (mapping.needsConversion) {
                    value = convertTypeFast(value,
                            mapping.sourceField.getType(),
                            mapping.targetField.getType(),
                            mapping.fieldAnnotation);
                }

                if (value != null) {
                    mapping.targetAccessor.set(target, value);
                }
            } catch (Exception e) {
                if (log.isTraceEnabled()) {
                    log.trace("Field mapping skipped: {} -> {}: {}",
                            mapping.sourceField.getName(),
                            mapping.targetField.getName(),
                            e.getMessage());
                }
            }
        }
    }

    private Object getDefaultValue(CrudXField annotation, Class<?> targetType) {
        String defaultValue = annotation.defaultValue();
        if (defaultValue == null || defaultValue.isEmpty()) return null;

        String cacheKey = defaultValue + ":" + targetType.getName();
        return defaultValueCache.computeIfAbsent(cacheKey, k -> {
            try {
                return parseDefaultValue(defaultValue, targetType);
            } catch (Exception e) {
                log.warn("Failed to parse default value '{}' for type {}",
                        defaultValue, targetType.getSimpleName());
                return null;
            }
        });
    }

    private Object parseDefaultValue(String value, Class<?> targetType) {
        if (value == null || value.isEmpty()) return null;

        if (targetType == String.class) return value;

        // Primitives and wrappers
        if (targetType == int.class || targetType == Integer.class) return Integer.parseInt(value);
        if (targetType == long.class || targetType == Long.class) return Long.parseLong(value);
        if (targetType == double.class || targetType == Double.class) return Double.parseDouble(value);
        if (targetType == float.class || targetType == Float.class) return Float.parseFloat(value);
        if (targetType == boolean.class || targetType == Boolean.class) return Boolean.parseBoolean(value);
        if (targetType == short.class || targetType == Short.class) return Short.parseShort(value);
        if (targetType == byte.class || targetType == Byte.class) return Byte.parseByte(value);
        if (targetType == char.class || targetType == Character.class) return value.charAt(0);

        // BigDecimal/BigInteger
        if (targetType == BigDecimal.class) return new BigDecimal(value);
        if (targetType == BigInteger.class) return new BigInteger(value);

        // Dates (ISO by default)
        try {
            if (targetType == LocalDate.class) return LocalDate.parse(value);
            if (targetType == LocalDateTime.class) return LocalDateTime.parse(value);
            if (targetType == LocalTime.class) return LocalTime.parse(value);
            if (targetType == Instant.class) return Instant.parse(value);
        } catch (DateTimeParseException ignored) {
            // fallthrough to return the raw string if parsing fails
        }

        // Enum
        if (targetType.isEnum()) {
            @SuppressWarnings({"unchecked", "rawtypes"})
            Object enumValue = Enum.valueOf((Class<Enum>) targetType, value);
            return enumValue;
        }

        return value;
    }

    private <T> T instantiateFast(Class<T> clazz) throws Throwable {
        Constructor<?> constructor = constructorCache.computeIfAbsent(clazz, c -> {
            try {
                Constructor<?> ctor = c.getDeclaredConstructor();
                ctor.setAccessible(true);
                return ctor;
            } catch (NoSuchMethodException e) {
                throw new RuntimeException("No no-arg constructor for " + c.getSimpleName(), e);
            }
        });

        @SuppressWarnings("unchecked")
        T instance = (T) constructor.newInstance();
        return instance;
    }

    private Field findFieldFast(Class<?> clazz, String fieldName) {
        for (Field field : getFieldsFast(clazz)) {
            if (field.getName().equals(fieldName)) {
                return field;
            }
        }
        return null;
    }

    private Field[] getFieldsFast(Class<?> clazz) {
        return fieldCache.computeIfAbsent(clazz, c -> {
            List<Field> fields = new ArrayList<>();
            Class<?> current = c;
            while (current != null && current != Object.class) {
                fields.addAll(Arrays.asList(current.getDeclaredFields()));
                current = current.getSuperclass();
            }
            return fields.toArray(new Field[0]);
        });
    }

    private Map<String, Object> convertToMapFast(Object obj) throws Throwable {
        Map<String, Object> map = new LinkedHashMap<>();

        for (Field field : getFieldsFast(obj.getClass())) {
            if (Modifier.isStatic(field.getModifiers()) || Modifier.isFinal(field.getModifiers())) {
                continue;
            }

            try {
                FieldAccessor accessor = createFastGetter(field, obj.getClass());
                Object value = accessor.get(obj);
                if (value != null) {
                    map.put(field.getName(), value);
                }
            } catch (Exception e) {
                if (log.isTraceEnabled()) {
                    log.trace("Could not read field {}: {}", field.getName(), e.getMessage());
                }
            }
        }

        return map;
    }

    private boolean shouldSkipField(Field field) {
        int modifiers = field.getModifiers();
        if (Modifier.isStatic(modifiers) || Modifier.isFinal(modifiers)) {
            return true;
        }

        CrudXField annotation = field.getAnnotation(CrudXField.class);
        return annotation != null && annotation.ignore();
    }

    private boolean isComplexType(Class<?> type) {
        if (type.isPrimitive() || type.isArray()) return false;

        String typeName = type.getName();
        return !typeName.startsWith("java.lang.")
                && !typeName.startsWith("java.time.")
                && !typeName.startsWith("java.util.")
                && !typeName.startsWith("java.math.")
                && !type.isEnum();
    }

    @SuppressWarnings("unchecked")
    private FieldAccessor createFastGetter(Field field, Class<?> clazz) throws Exception {
        String cacheKey = clazz.getName() + "#GET#" + field.getName();

        return accessorCache.computeIfAbsent(cacheKey, k -> {
            try {
                String methodName = "get" + capitalize(field.getName());
                try {
                    MethodHandle handle = LOOKUP.findVirtual(clazz, methodName,
                            MethodType.methodType(field.getType()));

                    return new FieldAccessor(
                            obj -> {
                                try {
                                    return handle.invoke(obj);
                                } catch (Throwable e) {
                                    return null;
                                }
                            },
                            null
                    );
                } catch (NoSuchMethodException e) {
                    if (field.getType() == boolean.class || field.getType() == Boolean.class) {
                        methodName = "is" + capitalize(field.getName());
                        try {
                            MethodHandle handle = LOOKUP.findVirtual(clazz, methodName,
                                    MethodType.methodType(field.getType()));
                            return new FieldAccessor(
                                    obj -> {
                                        try {
                                            return handle.invoke(obj);
                                        } catch (Throwable ex) {
                                            return null;
                                        }
                                    },
                                    null
                            );
                        } catch (NoSuchMethodException ignored) {
                        }
                    }
                }

                field.setAccessible(true);
                MethodHandle handle = LOOKUP.unreflectGetter(field);
                return new FieldAccessor(
                        obj -> {
                            try {
                                return handle.invoke(obj);
                            } catch (Throwable e) {
                                return null;
                            }
                        },
                        null
                );

            } catch (Exception e) {
                throw new RuntimeException("Cannot create getter for " + field.getName(), e);
            }
        });
    }

    private FieldAccessor createFastSetter(Field field, Class<?> clazz) throws Exception {
        String cacheKey = clazz.getName() + "#SET#" + field.getName();

        return accessorCache.computeIfAbsent(cacheKey, k -> {
            try {
                String methodName = "set" + capitalize(field.getName());
                try {
                    MethodHandle handle = LOOKUP.findVirtual(clazz, methodName,
                            MethodType.methodType(void.class, field.getType()));

                    return new FieldAccessor(
                            null,
                            (obj, value) -> {
                                try {
                                    handle.invoke(obj, value);
                                } catch (Throwable e) {
                                    // ignore
                                }
                            }
                    );
                } catch (NoSuchMethodException ignored) {
                }

                field.setAccessible(true);
                MethodHandle handle = LOOKUP.unreflectSetter(field);
                return new FieldAccessor(
                        null,
                        (obj, value) -> {
                            try {
                                handle.invoke(obj, value);
                            } catch (Throwable e) {
                                // ignore
                            }
                        }
                );

            } catch (Exception e) {
                throw new RuntimeException("Cannot create setter for " + field.getName(), e);
            }
        });
    }

    private String capitalize(String str) {
        if (str == null || str.isEmpty()) return str;
        return Character.toUpperCase(str.charAt(0)) + str.substring(1);
    }

    public void clearCaches() {
        mappingPlanCache.clear();
        accessorCache.clear();
        fieldCache.clear();
        constructorCache.clear();
        formatters.clear();
        typeConverterCache.clear();
        defaultValueCache.clear();
        log.info("✓ All caches cleared");
    }

    public Map<String, Integer> getCacheStats() {
        Map<String, Integer> stats = new LinkedHashMap<>();
        stats.put("mappingPlans", mappingPlanCache.size());
        stats.put("accessors", accessorCache.size());
        stats.put("fields", fieldCache.size());
        stats.put("constructors", constructorCache.size());
        stats.put("formatters", formatters.size());
        stats.put("typeConverters", typeConverterCache.size());
        stats.put("defaultValues", defaultValueCache.size());
        return stats;
    }

    // ==================== INTERNAL CLASSES ====================

    private static class MappingPlan {
        Class<?> sourceClass;
        Class<?> targetClass;
        boolean isDTOToEntity;
        List<FieldMapping> fieldMappings;
    }

    private static class FieldMapping {
        FieldAccessor sourceAccessor;
        FieldAccessor targetAccessor;
        Field sourceField;
        Field targetField;
        boolean needsConversion;
        boolean isNested;
        CrudXField fieldAnnotation;
        CrudXNested nestedAnnotation;
    }

    private static class FieldAccessor {
        final Function<Object, Object> getter;
        final BiConsumer<Object, Object> setter;

        FieldAccessor(Function<Object, Object> getter, BiConsumer<Object, Object> setter) {
            this.getter = getter;
            this.setter = setter;
        }

        Object get(Object obj) {
            return getter != null ? getter.apply(obj) : null;
        }

        void set(Object obj, Object value) {
            if (setter != null) {
                setter.accept(obj, value);
            }
        }
    }

    private interface TypeConverter {
        Object convert(Object value, Class<?> sourceType, Class<?> targetType, CrudXField annotation);
    }

    // ==================== TYPE CONVERSION & NESTED HANDLING ====================

    private void handleNullStrategy(FieldMapping mapping, Object target) throws Throwable {
        if (mapping.nestedAnnotation == null) return;

        CrudXNested.NullStrategy strategy = mapping.nestedAnnotation.nullStrategy();

        switch (strategy) {
            case INCLUDE_NULL:
                mapping.targetAccessor.set(target, null);
                break;
            case EXCLUDE_NULL:
                // don't set anything
                break;
            case EMPTY_COLLECTION:
                if (isCollectionType(mapping.targetField.getType())) {
                    Object emptyCollection = createEmptyCollection(mapping.targetField.getType());
                    mapping.targetAccessor.set(target, emptyCollection);
                }
                break;
            default:
                break;
        }
    }

    private Object handleNestedMapping(Object value, FieldMapping mapping, int currentDepth) throws Throwable {
        CrudXNested nested = mapping.nestedAnnotation;

        int maxDepth = nested != null ? nested.maxDepth() : 3;
        if (currentDepth >= maxDepth) {
            log.trace("Max depth {} reached, skipping nested mapping", maxDepth);
            return null;
        }

        if (isCollectionType(value.getClass())) {
            return mapNestedCollection(value, mapping, currentDepth + 1);
        }

        Class<?> targetDtoClass = nested != null && nested.dtoClass() != void.class
                ? nested.dtoClass()
                : mapping.targetField.getType();

        return toResponse(value, targetDtoClass);
    }

    @SuppressWarnings("unchecked")
    private Object mapNestedCollection(Object collection, FieldMapping mapping, int depth) throws Throwable {
        if (!(collection instanceof Collection)) return collection;

        Collection<?> sourceCollection = (Collection<?>) collection;
        if (sourceCollection.isEmpty()) return sourceCollection;

        Class<?> itemDtoClass = getCollectionItemType(mapping.targetField);
        if (itemDtoClass == null || itemDtoClass == Object.class) {
            return collection;
        }

        List<Object> mappedItems = new ArrayList<>(sourceCollection.size());
        for (Object item : sourceCollection) {
            Object mappedItem = toResponse(item, itemDtoClass);
            if (mappedItem != null) mappedItems.add(mappedItem);
        }

        if (Set.class.isAssignableFrom(mapping.targetField.getType())) {
            return new LinkedHashSet<>(mappedItems);
        }
        return mappedItems;
    }

    private Object convertTypeFast(Object value, Class<?> sourceType, Class<?> targetType,
                                   CrudXField annotation) {
        if (value == null || targetType.isInstance(value)) return value;

        TypeConverter converter = typeConverterCache.computeIfAbsent(targetType, this::createTypeConverter);
        return converter.convert(value, sourceType, targetType, annotation);
    }

    private TypeConverter createTypeConverter(Class<?> targetType) {
        return (value, sourceType, tType, annotation) -> {
            if (value == null) return null;

            if (tType == String.class) return value.toString();

            String format = annotation != null ? annotation.format() : "";

            if (!format.isEmpty()) {
                if (value instanceof String) {
                    return parseWithFormat((String) value, tType, format);
                } else if (isTemporalType(value.getClass())) {
                    return formatTemporal(value, format);
                }
            }

            if (value instanceof Number) return convertNumber((Number) value, tType);

            if (value instanceof String) return parseString((String) value, tType);

            if (tType.isEnum() && value instanceof String) {
                @SuppressWarnings({"unchecked", "rawtypes"})
                Object enumValue = Enum.valueOf((Class<Enum>) tType, (String) value);
                return enumValue;
            }

            if ((tType == Boolean.class || tType == boolean.class)) return convertToBoolean(value);

            if (isCollectionType(tType) && isCollectionType(value.getClass())) return convertCollection(value, tType);

            return value;
        };
    }

    private Object parseWithFormat(String value, Class<?> targetType, String format) {
        try {
            DateTimeFormatter formatter = formatters.computeIfAbsent(format, DateTimeFormatter::ofPattern);

            if (targetType == LocalDateTime.class) return LocalDateTime.parse(value, formatter);
            if (targetType == LocalDate.class) return LocalDate.parse(value, formatter);
            if (targetType == LocalTime.class) return LocalTime.parse(value, formatter);
            if (targetType == ZonedDateTime.class) return ZonedDateTime.parse(value, formatter);
        } catch (DateTimeParseException e) {
            log.debug("Failed to parse '{}' with format '{}': {}", value, format, e.getMessage());
        }
        return value;
    }

    private String formatTemporal(Object value, String format) {
        DateTimeFormatter formatter = formatters.computeIfAbsent(format, DateTimeFormatter::ofPattern);

        if (value instanceof LocalDateTime) return ((LocalDateTime) value).format(formatter);
        if (value instanceof LocalDate) return ((LocalDate) value).format(formatter);
        if (value instanceof LocalTime) return ((LocalTime) value).format(formatter);
        if (value instanceof ZonedDateTime) return ((ZonedDateTime) value).format(formatter);
        return value.toString();
    }

    private Object convertNumber(Number num, Class<?> targetType) {
        if (targetType == long.class || targetType == Long.class) return num.longValue();
        if (targetType == int.class || targetType == Integer.class) return num.intValue();
        if (targetType == double.class || targetType == Double.class) return num.doubleValue();
        if (targetType == float.class || targetType == Float.class) return num.floatValue();
        if (targetType == short.class || targetType == Short.class) return num.shortValue();
        if (targetType == byte.class || targetType == Byte.class) return num.byteValue();
        if (targetType == BigDecimal.class) return new BigDecimal(num.toString());
        if (targetType == BigInteger.class) return BigInteger.valueOf(num.longValue());
        if (targetType == AtomicInteger.class) return new AtomicInteger(num.intValue());
        if (targetType == AtomicLong.class) return new AtomicLong(num.longValue());
        return num;
    }

    private Object parseString(String str, Class<?> targetType) {
        try {
            if (targetType == Long.class || targetType == long.class) return Long.parseLong(str);
            if (targetType == Integer.class || targetType == int.class) return Integer.parseInt(str);
            if (targetType == Double.class || targetType == double.class) return Double.parseDouble(str);
            if (targetType == Float.class || targetType == float.class) return Float.parseFloat(str);
            if (targetType == Short.class || targetType == short.class) return Short.parseShort(str);
            if (targetType == Byte.class || targetType == byte.class) return Byte.parseByte(str);
            if (targetType == Boolean.class || targetType == boolean.class) return Boolean.parseBoolean(str);
            if (targetType == Character.class || targetType == char.class) return str.charAt(0);
            if (targetType == BigDecimal.class) return new BigDecimal(str);
            if (targetType == BigInteger.class) return new BigInteger(str);
            if (targetType == LocalDateTime.class) return LocalDateTime.parse(str);
            if (targetType == LocalDate.class) return LocalDate.parse(str);
            if (targetType == LocalTime.class) return LocalTime.parse(str);
            if (targetType == Instant.class) return Instant.parse(str);
            if (targetType == ZonedDateTime.class) return ZonedDateTime.parse(str);
            if (targetType == OffsetDateTime.class) return OffsetDateTime.parse(str);
            if (targetType == Duration.class) return Duration.parse(str);
            if (targetType == Period.class) return Period.parse(str);
            if (targetType == UUID.class) return UUID.fromString(str);
        } catch (Exception e) {
            log.debug("Type conversion failed: {} -> {}", str, targetType.getSimpleName());
        }
        return str;
    }

    private Boolean convertToBoolean(Object value) {
        if (value instanceof String) {
            String str = ((String) value).toLowerCase();
            return "true".equals(str) || "yes".equals(str) || "1".equals(str) || "on".equals(str);
        }
        if (value instanceof Number) {
            return ((Number) value).intValue() != 0;
        }
        return Boolean.FALSE;
    }

    @SuppressWarnings("unchecked")
    private Object convertCollection(Object value, Class<?> targetType) {
        Collection<?> source = (Collection<?>) value;
        if (Set.class.isAssignableFrom(targetType)) return new LinkedHashSet<>(source);
        if (List.class.isAssignableFrom(targetType)) return new ArrayList<>(source);
        return value;
    }

    private boolean isTemporalType(Class<?> type) {
        return LocalDateTime.class.isAssignableFrom(type)
                || LocalDate.class.isAssignableFrom(type)
                || LocalTime.class.isAssignableFrom(type)
                || ZonedDateTime.class.isAssignableFrom(type)
                || OffsetDateTime.class.isAssignableFrom(type)
                || Instant.class.isAssignableFrom(type);
    }

    private boolean isCollectionType(Class<?> type) {
        return Collection.class.isAssignableFrom(type) || List.class.isAssignableFrom(type) || Set.class.isAssignableFrom(type);
    }

    private Object createEmptyCollection(Class<?> type) {
        if (Set.class.isAssignableFrom(type)) return new LinkedHashSet<>();
        return new ArrayList<>();
    }

    private Class<?> getCollectionItemType(Field field) {
        if (field.getGenericType() instanceof ParameterizedType) {
            ParameterizedType paramType = (ParameterizedType) field.getGenericType();
            if (paramType.getActualTypeArguments().length > 0) {
                return (Class<?>) paramType.getActualTypeArguments()[0];
            }
        }
        return null;
    }

    private Object applyTransformer(Object value, String transformerName, Object sourceObject) {
        try {
            return switch (transformerName) {
                case "toUpperCase" -> value.toString().toUpperCase();
                case "toLowerCase" -> value.toString().toLowerCase();
                case "trim" -> value.toString().trim();
                default -> value;
            };
        } catch (Exception e) {
            return value;
        }
    }
}
