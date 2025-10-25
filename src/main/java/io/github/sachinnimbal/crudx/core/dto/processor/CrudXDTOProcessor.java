package io.github.sachinnimbal.crudx.core.dto.processor;

import com.google.auto.service.AutoService;
import io.github.sachinnimbal.crudx.core.dto.annotations.CrudXField;
import io.github.sachinnimbal.crudx.core.dto.annotations.CrudXNested;
import io.github.sachinnimbal.crudx.core.dto.annotations.CrudXRequest;
import io.github.sachinnimbal.crudx.core.dto.annotations.CrudXResponse;

import javax.annotation.processing.*;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.*;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.Elements;
import javax.lang.model.util.Types;
import javax.tools.Diagnostic;
import javax.tools.JavaFileObject;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.*;

@AutoService(Processor.class)
@SupportedAnnotationTypes({
        "io.github.sachinnimbal.crudx.core.dto.annotations.CrudXRequest",
        "io.github.sachinnimbal.crudx.core.dto.annotations.CrudXResponse"
})
@SupportedSourceVersion(SourceVersion.RELEASE_21)
public class CrudXDTOProcessor extends AbstractProcessor {

    private Messager messager;
    private Filer filer;
    private Elements elementUtils;
    private Types typeUtils;

    private final Map<String, EntityMapperContext> entityMappers = new LinkedHashMap<>();
    private final Set<String> generatedMappers = new HashSet<>();
    private boolean hasProcessed = false;

    @Override
    public synchronized void init(ProcessingEnvironment processingEnv) {
        super.init(processingEnv);
        this.messager = processingEnv.getMessager();
        this.filer = processingEnv.getFiler();
        this.elementUtils = processingEnv.getElementUtils();
        this.typeUtils = processingEnv.getTypeUtils();

        logInfo("🚀 CrudX DTO Processor - Enhanced Edition");
    }

    @Override
    public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {
        if (hasProcessed || annotations.isEmpty()) {
            return false;
        }

        try {
            collectRequestDTOs(roundEnv);
            collectResponseDTOs(roundEnv);
            analyzeNestedDTOs();

            if (!entityMappers.isEmpty()) {
                generateAllMappers();
                hasProcessed = true;
            }
        } catch (Exception e) {
            error("Critical error during processing: " + e.getMessage());
        }

        return false;
    }

    // ==================== COLLECTION METHODS ====================

    private void collectRequestDTOs(RoundEnvironment roundEnv) {
        for (Element element : roundEnv.getElementsAnnotatedWith(CrudXRequest.class)) {
            if (element.getKind() != ElementKind.CLASS) continue;

            TypeElement dtoElement = (TypeElement) element;
            CrudXRequest annotation = dtoElement.getAnnotation(CrudXRequest.class);

            try {
                TypeMirror entityType = extractEntityType(annotation);
                String entityFqn = entityType.toString();

                EntityMapperContext context = entityMappers.computeIfAbsent(
                        entityFqn, k -> new EntityMapperContext(entityType, entityFqn, elementUtils, typeUtils)
                );

                if (annotation.strict()) {
                    validateStrictMode(dtoElement, context.entityElement);
                }

                // ✅ Validate @CrudXField annotations
                validateFieldAnnotations(dtoElement, context.entityElement);

                context.addRequestDTO(dtoElement, annotation);
                logInfo("✓ Request: " + dtoElement.getSimpleName() + " → " + context.entitySimpleName);

            } catch (Exception e) {
                error("Failed to process @CrudXRequest: " + e.getMessage(), element);
            }
        }
    }

    private void collectResponseDTOs(RoundEnvironment roundEnv) {
        for (Element element : roundEnv.getElementsAnnotatedWith(CrudXResponse.class)) {
            if (element.getKind() != ElementKind.CLASS) continue;

            TypeElement dtoElement = (TypeElement) element;
            CrudXResponse annotation = dtoElement.getAnnotation(CrudXResponse.class);

            try {
                TypeMirror entityType = extractEntityType(annotation);
                String entityFqn = entityType.toString();

                EntityMapperContext context = entityMappers.computeIfAbsent(
                        entityFqn, k -> new EntityMapperContext(entityType, entityFqn, elementUtils, typeUtils)
                );

                // ✅ Validate @CrudXField and @CrudXNested annotations
                validateFieldAnnotations(dtoElement, context.entityElement);
                validateNestedAnnotations(dtoElement);

                context.addResponseDTO(dtoElement, annotation);
                logInfo("✓ Response: " + dtoElement.getSimpleName() + " ← " + context.entitySimpleName);

            } catch (Exception e) {
                error("Failed to process @CrudXResponse: " + e.getMessage(), element);
            }
        }
    }

    /**
     * ✅ NEW: Validate @CrudXField annotations
     */
    private void validateFieldAnnotations(TypeElement dtoElement, TypeElement entityElement) {
        for (Element element : dtoElement.getEnclosedElements()) {
            if (element.getKind() != ElementKind.FIELD) continue;

            VariableElement field = (VariableElement) element;
            CrudXField annotation = field.getAnnotation(CrudXField.class);

            if (annotation != null) {
                // Validate defaultValue is parseable
                if (!annotation.defaultValue().isEmpty()) {
                    validateDefaultValue(field, annotation.defaultValue());
                }

                // Validate format string for temporal types
                if (!annotation.format().isEmpty()) {
                    validateFormatString(field, annotation.format());
                }

                // Warn about required fields with defaultValue
                if (annotation.required() && !annotation.defaultValue().isEmpty()) {
                    logWarn("Field " + field.getSimpleName() +
                            " has both required=true and defaultValue - defaultValue will be ignored");
                }
            }
        }
    }

    /**
     * ✅ NEW: Validate @CrudXNested annotations
     */
    private void validateNestedAnnotations(TypeElement dtoElement) {
        for (Element element : dtoElement.getEnclosedElements()) {
            if (element.getKind() != ElementKind.FIELD) continue;

            VariableElement field = (VariableElement) element;
            CrudXNested annotation = field.getAnnotation(CrudXNested.class);

            if (annotation != null) {
                // Validate maxDepth
                if (annotation.maxDepth() < 0) {
                    error("@CrudXNested maxDepth must be >= 0 for field: " + field.getSimpleName(), field);
                }

                // Validate dtoClass if specified
                if (annotation.dtoClass() != void.class) {
                    TypeMirror dtoClassType = extractDtoClass(annotation);
                    validateNestedDtoClass(field, dtoClassType);
                }

                // Validate nullStrategy for collections
                if (annotation.nullStrategy() == CrudXNested.NullStrategy.EMPTY_COLLECTION) {
                    if (!isCollectionType(field.asType())) {
                        logWarn("Field " + field.getSimpleName() +
                                " uses EMPTY_COLLECTION nullStrategy but is not a Collection type");
                    }
                }
            }
        }
    }

    /**
     * ✅ NEW: Validate default value can be parsed
     */
    private void validateDefaultValue(VariableElement field, String defaultValue) {
        Class<?> fieldType = getFieldJavaClass(field.asType());

        try {
            // Basic validation - actual parsing happens at runtime
            if (fieldType == int.class || fieldType == Integer.class) {
                Integer.parseInt(defaultValue);
            } else if (fieldType == long.class || fieldType == Long.class) {
                Long.parseLong(defaultValue);
            } else if (fieldType == double.class || fieldType == Double.class) {
                Double.parseDouble(defaultValue);
            } else if (fieldType == boolean.class || fieldType == Boolean.class) {
                Boolean.parseBoolean(defaultValue);
            }
        } catch (Exception e) {
            logWarn("Default value '" + defaultValue + "' may not be parseable for field " +
                    field.getSimpleName() + " of type " + fieldType.getSimpleName());
        }
    }

    /**
     * ✅ NEW: Validate format string
     */
    private void validateFormatString(VariableElement field, String format) {
        try {
            // Try to create formatter to validate syntax
            java.time.format.DateTimeFormatter.ofPattern(format);
        } catch (Exception e) {
            error("Invalid format pattern '" + format + "' for field " +
                    field.getSimpleName() + ": " + e.getMessage(), field);
        }
    }

    /**
     * ✅ NEW: Validate nested DTO class
     */
    private void validateNestedDtoClass(VariableElement field, TypeMirror dtoClassType) {
        TypeElement dtoElement = (TypeElement) typeUtils.asElement(dtoClassType);

        if (dtoElement == null) {
            error("Invalid dtoClass specified for nested field: " + field.getSimpleName(), field);
            return;
        }

        // Check if DTO class has no-arg constructor
        boolean hasNoArgConstructor = false;
        for (Element element : dtoElement.getEnclosedElements()) {
            if (element.getKind() == ElementKind.CONSTRUCTOR) {
                ExecutableElement constructor = (ExecutableElement) element;
                if (constructor.getParameters().isEmpty()) {
                    hasNoArgConstructor = true;
                    break;
                }
            }
        }

        if (!hasNoArgConstructor) {
            error("Nested DTO class " + dtoElement.getSimpleName() +
                    " must have a no-arg constructor", field);
        }
    }

    private void analyzeNestedDTOs() {
        for (EntityMapperContext context : entityMappers.values()) {
            for (TypeElement requestDTO : context.requestDTOs.keySet()) {
                scanForNestedFields(requestDTO, context, true, new HashSet<>());
            }
            for (TypeElement responseDTO : context.responseDTOs.keySet()) {
                scanForNestedFields(responseDTO, context, false, new HashSet<>());
            }
        }
    }

    private void scanForNestedFields(TypeElement dtoElement, EntityMapperContext context,
                                     boolean isRequest, Set<String> visited) {
        String dtoFqn = dtoElement.getQualifiedName().toString();
        if (visited.contains(dtoFqn)) return;
        visited.add(dtoFqn);

        for (Element element : dtoElement.getEnclosedElements()) {
            if (element.getKind() != ElementKind.FIELD) continue;

            VariableElement dtoField = (VariableElement) element;
            CrudXNested nested = dtoField.getAnnotation(CrudXNested.class);

            if (nested != null) {
                String dtoFieldName = dtoField.getSimpleName().toString();
                String entityFieldName = getEntityFieldName(dtoField);

                VariableElement entityField = findFieldInEntityHierarchy(context.entityElement, entityFieldName);
                if (entityField != null) {
                    registerNestedMappingPair(dtoField, entityField, context, isRequest, nested);
                    processNestedLevel(dtoField, entityField, context, isRequest, visited);
                }

                String nestedDtoTypeFqn = extractTypeName(dtoField.asType());
                if (nestedDtoTypeFqn != null) {
                    TypeElement nestedDtoElement = elementUtils.getTypeElement(nestedDtoTypeFqn);
                    if (nestedDtoElement != null) {
                        scanForNestedFields(nestedDtoElement, context, isRequest, visited);
                    }
                }
            } else {
                // Auto-detect nested objects
                TypeMirror dtoFieldType = dtoField.asType();
                if (isComplexType(dtoFieldType)) {
                    String entityFieldName = getEntityFieldName(dtoField);
                    VariableElement entityField = findFieldInEntityHierarchy(context.entityElement, entityFieldName);

                    if (entityField != null) {
                        TypeMirror entityFieldType = entityField.asType();
                        if (needsNestedMapper(dtoFieldType, entityFieldType)) {
                            registerNestedMappingPair(dtoField, entityField, context, isRequest, null);
                        }
                    }
                }
            }
        }
    }

    private void processNestedLevel(VariableElement dtoField, VariableElement entityField,
                                    EntityMapperContext mainContext, boolean isRequest, Set<String> visited) {
        String entityTypeFqn = extractTypeName(entityField.asType());
        String dtoTypeFqn = extractTypeName(dtoField.asType());

        if (entityTypeFqn != null && dtoTypeFqn != null) {
            TypeElement nestedEntityElement = elementUtils.getTypeElement(entityTypeFqn);
            TypeElement nestedDtoElement = elementUtils.getTypeElement(dtoTypeFqn);

            if (nestedEntityElement != null && nestedDtoElement != null) {
                scanNestedLevel(nestedDtoElement, nestedEntityElement, mainContext, isRequest, visited);
            }
        }
    }

    private void scanNestedLevel(TypeElement nestedDtoElement, TypeElement nestedEntityElement,
                                 EntityMapperContext mainContext, boolean isRequest, Set<String> visited) {
        String dtoFqn = nestedDtoElement.getQualifiedName().toString();
        String contextKey = "nested:" + dtoFqn;
        if (visited.contains(contextKey)) return;
        visited.add(contextKey);

        for (Element element : nestedDtoElement.getEnclosedElements()) {
            if (element.getKind() != ElementKind.FIELD) continue;

            VariableElement dtoField = (VariableElement) element;
            CrudXNested nested = dtoField.getAnnotation(CrudXNested.class);

            if (nested != null) {
                String entityFieldName = getEntityFieldName(dtoField);
                VariableElement entityField = findFieldInEntityHierarchy(nestedEntityElement, entityFieldName);

                if (entityField != null) {
                    registerNestedMappingPair(dtoField, entityField, mainContext, isRequest, nested);
                    processNestedLevel(dtoField, entityField, mainContext, isRequest, visited);
                }

                String nestedDtoTypeFqn = extractTypeName(dtoField.asType());
                if (nestedDtoTypeFqn != null) {
                    TypeElement nestedDto = elementUtils.getTypeElement(nestedDtoTypeFqn);
                    if (nestedDto != null) {
                        scanNestedLevel(nestedDto, nestedEntityElement, mainContext, isRequest, visited);
                    }
                }
            }
        }
    }

    private void registerNestedMappingPair(VariableElement dtoField, VariableElement entityField,
                                           EntityMapperContext context, boolean isRequest,
                                           CrudXNested annotation) {
        try {
            TypeMirror dtoFieldType = dtoField.asType();
            TypeMirror entityFieldType = entityField.asType();

            String dtoTypeFqn = extractTypeName(dtoFieldType);
            String entityTypeFqn = extractTypeName(entityFieldType);

            if (dtoTypeFqn != null && entityTypeFqn != null) {
                String mappingKey = dtoTypeFqn + "|" + entityTypeFqn;
                if (!context.nestedMappings.containsKey(mappingKey)) {
                    NestedMapping mapping = new NestedMapping(dtoTypeFqn, entityTypeFqn, isRequest, annotation);
                    context.nestedMappings.put(mappingKey, mapping);
                    logInfo("✓ Nested: " + extractSimpleName(dtoTypeFqn) + " ↔ " + extractSimpleName(entityTypeFqn) +
                            (annotation != null ? " (maxDepth=" + annotation.maxDepth() + ")" : ""));
                }
            }
        } catch (Exception e) {
            logWarn("Error mapping nested field " + dtoField.getSimpleName() + ": " + e.getMessage());
        }
    }

    // ==================== MAPPER GENERATION ====================

    private void generateAllMappers() {
        int successCount = 0;

        for (EntityMapperContext context : entityMappers.values()) {
            String mapperClassName = context.entitySimpleName + "MapperCrudX";
            String basePackage = elementUtils.getPackageOf(context.entityElement).getQualifiedName().toString();
            String generatedPackage = basePackage + ".generated";
            String mapperFqn = generatedPackage + "." + mapperClassName;

            if (generatedMappers.contains(mapperFqn)) {
                continue;
            }

            try {
                generateMapperClass(context);
                generatedMappers.add(mapperFqn);
                successCount++;
            } catch (IOException e) {
                error("Failed to generate mapper for " + context.entitySimpleName + ": " + e.getMessage());
            }
        }

        logInfo("✅ Generated " + successCount + " mapper classes with enhanced annotation support");
    }

    private void generateMapperClass(EntityMapperContext context) throws IOException {
        String mapperClassName = context.entitySimpleName + "MapperCrudX";
        String basePackage = elementUtils.getPackageOf(context.entityElement).getQualifiedName().toString();
        String generatedPackage = basePackage + ".generated";

        JavaFileObject sourceFile = filer.createSourceFile(generatedPackage + "." + mapperClassName);

        try (PrintWriter writer = new PrintWriter(sourceFile.openWriter())) {
            MapperWriter mapperWriter = new MapperWriter(
                    writer, context, generatedPackage, mapperClassName, elementUtils, typeUtils, this
            );
            mapperWriter.write();
        }

        logInfo("✓ Generated: " + mapperClassName + " (" + context.nestedMappings.size() + " nested mappers)");
    }

    // ==================== HELPER METHODS ====================

    String extractTypeName(TypeMirror type) {
        if (isCollection(type) && type instanceof DeclaredType declaredType) {
            if (!declaredType.getTypeArguments().isEmpty()) {
                TypeMirror itemType = declaredType.getTypeArguments().get(0);
                if (itemType instanceof DeclaredType) {
                    return ((TypeElement) ((DeclaredType) itemType).asElement()).getQualifiedName().toString();
                }
            }
        } else if (type instanceof DeclaredType) {
            return ((TypeElement) ((DeclaredType) type).asElement()).getQualifiedName().toString();
        }
        return null;
    }

    boolean isCollection(TypeMirror type) {
        if (!(type instanceof DeclaredType)) return false;
        TypeElement element = (TypeElement) ((DeclaredType) type).asElement();
        String name = element.getQualifiedName().toString();
        return name.equals("java.util.List") || name.equals("java.util.Set") ||
                name.equals("java.util.Collection");
    }

    private boolean isCollectionType(TypeMirror type) {
        return isCollection(type);
    }

    private boolean isComplexType(TypeMirror type) {
        if (!(type instanceof DeclaredType)) {
            return false;
        }

        TypeElement element = (TypeElement) ((DeclaredType) type).asElement();
        String fqn = element.getQualifiedName().toString();

        return !fqn.startsWith("java.lang.") &&
                !fqn.startsWith("java.util.") &&
                !fqn.startsWith("java.time.") &&
                !fqn.startsWith("java.math.") &&
                !element.getKind().equals(ElementKind.ENUM);
    }

    private boolean needsNestedMapper(TypeMirror type1, TypeMirror type2) {
        if (isCollection(type1) && isCollection(type2)) {
            TypeMirror item1Type = getCollectionItemType(type1);
            TypeMirror item2Type = getCollectionItemType(type2);
            if (item1Type != null && item2Type != null) {
                if (isComplexType(item1Type) && isComplexType(item2Type)) {
                    if (!typeUtils.isSameType(item1Type, item2Type)) {
                        return areTypesCompatible(item1Type, item2Type);
                    }
                }
            }
            return false;
        }

        if (!isComplexType(type1) || !isComplexType(type2)) {
            return false;
        }

        if (typeUtils.isSameType(type1, type2)) {
            return false;
        }

        return areTypesCompatible(type1, type2);
    }

    private boolean areTypesCompatible(TypeMirror type1, TypeMirror type2) {
        if (typeUtils.isSameType(type1, type2)) {
            return true;
        }

        if (isComplexType(type1) && isComplexType(type2)) {
            String type1Name = extractSimpleName(type1.toString());
            String type2Name = extractSimpleName(type2.toString());

            String type1Base = type1Name.replace("Request", "").replace("Response", "");
            String type2Base = type2Name.replace("Request", "").replace("Response", "");

            if (type1Base.equals(type2Name) ||
                    type1Name.equals(type2Base) ||
                    type2Base.startsWith(type1Base) ||
                    type1Base.startsWith(type2Base)) {
                return true;
            }
        }

        if (isCollection(type1) && isCollection(type2)) {
            TypeMirror item1Type = getCollectionItemType(type1);
            TypeMirror item2Type = getCollectionItemType(type2);
            if (item1Type != null && item2Type != null) {
                return areTypesCompatible(item1Type, item2Type);
            }
        }

        return false;
    }

    private TypeMirror getCollectionItemType(TypeMirror type) {
        if (type instanceof DeclaredType) {
            DeclaredType declaredType = (DeclaredType) type;
            if (!declaredType.getTypeArguments().isEmpty()) {
                return declaredType.getTypeArguments().get(0);
            }
        }
        return null;
    }

    private VariableElement findFieldInEntityHierarchy(TypeElement entityElement, String fieldName) {
        VariableElement field = findField(entityElement, fieldName);
        if (field != null) {
            return field;
        }

        for (Element enclosed : entityElement.getEnclosedElements()) {
            if (enclosed.getKind() == ElementKind.CLASS) {
                TypeElement innerClass = (TypeElement) enclosed;
                field = findFieldInEntityHierarchy(innerClass, fieldName);
                if (field != null) {
                    return field;
                }
            }
        }

        return null;
    }

    private VariableElement findField(TypeElement typeElement, String fieldName) {
        for (Element element : typeElement.getEnclosedElements()) {
            if (element.getKind() == ElementKind.FIELD &&
                    element.getSimpleName().toString().equals(fieldName)) {
                return (VariableElement) element;
            }
        }

        TypeMirror superclass = typeElement.getSuperclass();
        if (superclass instanceof DeclaredType) {
            TypeElement superElement = (TypeElement) ((DeclaredType) superclass).asElement();
            return findField(superElement, fieldName);
        }

        return null;
    }

    private String getEntityFieldName(VariableElement dtoField) {
        CrudXField fieldAnnotation = dtoField.getAnnotation(CrudXField.class);
        if (fieldAnnotation != null && !fieldAnnotation.source().isEmpty()) {
            return fieldAnnotation.source();
        }
        return dtoField.getSimpleName().toString();
    }

    private Set<String> getAllFieldNames(TypeElement typeElement) {
        Set<String> fieldNames = new LinkedHashSet<>();
        TypeElement current = typeElement;
        while (current != null && !current.getQualifiedName().toString().equals(Object.class.getCanonicalName())) {
            for (Element enclosed : current.getEnclosedElements()) {
                if (enclosed.getKind() == ElementKind.FIELD) {
                    fieldNames.add(enclosed.getSimpleName().toString());
                }
            }
            TypeMirror superMirror = current.getSuperclass();
            if (superMirror == null || superMirror.getKind() == TypeKind.NONE) {
                break;
            }
            current = (TypeElement) processingEnv.getTypeUtils().asElement(superMirror);
        }
        return fieldNames;
    }

    private void validateStrictMode(TypeElement dtoElement, TypeElement entityElement) {
        Set<String> entityFields = getAllFieldNames(entityElement);

        for (Element element : dtoElement.getEnclosedElements()) {
            if (element.getKind() != ElementKind.FIELD) continue;

            VariableElement dtoField = (VariableElement) element;
            String entityFieldName = getEntityFieldName(dtoField);

            if (!entityFields.contains(entityFieldName)) {
                error("Strict mode: DTO field '" + dtoField.getSimpleName() +
                        "' maps to '" + entityFieldName + "' which is not found in entity " +
                        entityElement.getSimpleName(), element);
            }
        }
    }

    private TypeMirror extractEntityType(CrudXRequest annotation) {
        try {
            annotation.value();
        } catch (javax.lang.model.type.MirroredTypeException mte) {
            return mte.getTypeMirror();
        }
        throw new IllegalStateException("Cannot extract entity type");
    }

    private TypeMirror extractEntityType(CrudXResponse annotation) {
        try {
            annotation.value();
        } catch (javax.lang.model.type.MirroredTypeException mte) {
            return mte.getTypeMirror();
        }
        throw new IllegalStateException("Cannot extract entity type");
    }

    private TypeMirror extractDtoClass(CrudXNested annotation) {
        try {
            annotation.dtoClass();
        } catch (javax.lang.model.type.MirroredTypeException mte) {
            return mte.getTypeMirror();
        }
        throw new IllegalStateException("Cannot extract dtoClass");
    }

    private Class<?> getFieldJavaClass(TypeMirror type) {
        String typeName = type.toString();

        try {
            return switch (typeName) {
                case "int" -> int.class;
                case "long" -> long.class;
                case "double" -> double.class;
                case "float" -> float.class;
                case "boolean" -> boolean.class;
                case "short" -> short.class;
                case "byte" -> byte.class;
                case "char" -> char.class;
                case "java.lang.Integer" -> Integer.class;
                case "java.lang.Long" -> Long.class;
                case "java.lang.Double" -> Double.class;
                case "java.lang.Float" -> Float.class;
                case "java.lang.Boolean" -> Boolean.class;
                case "java.lang.Short" -> Short.class;
                case "java.lang.Byte" -> Byte.class;
                case "java.lang.Character" -> Character.class;
                case "java.lang.String" -> String.class;
                default -> Object.class;
            };
        } catch (Exception e) {
            return Object.class;
        }
    }

    private void logInfo(String message) {
        messager.printMessage(Diagnostic.Kind.NOTE, message);
    }

    void logWarn(String message) {
        messager.printMessage(Diagnostic.Kind.WARNING, message);
    }

    private void error(String message, Element element) {
        messager.printMessage(Diagnostic.Kind.ERROR, message, element);
    }

    private void error(String message) {
        messager.printMessage(Diagnostic.Kind.ERROR, message);
    }

    private String extractSimpleName(String fqn) {
        if (fqn == null) return "Unknown";
        int lastDot = fqn.lastIndexOf('.');
        String name = lastDot > 0 ? fqn.substring(lastDot + 1) : fqn;
        int genericStart = name.indexOf('<');
        return genericStart > 0 ? name.substring(0, genericStart) : name;
    }

    // ==================== CONTEXT CLASSES ====================

    private static class NestedMapping {
        final String dtoType;
        final String entityType;
        final boolean isRequest;
        final CrudXNested annotation;

        NestedMapping(String dtoType, String entityType, boolean isRequest, CrudXNested annotation) {
            this.dtoType = dtoType;
            this.entityType = entityType;
            this.isRequest = isRequest;
            this.annotation = annotation;
        }
    }

    private static class EntityMapperContext {
        final TypeMirror entityType;
        final String entityFqn;
        final String entitySimpleName;
        final TypeElement entityElement;
        final Elements elementUtils;
        final Types typeUtils;
        final Map<TypeElement, CrudXRequest> requestDTOs = new LinkedHashMap<>();
        final Map<TypeElement, CrudXResponse> responseDTOs = new LinkedHashMap<>();
        final Map<String, NestedMapping> nestedMappings = new LinkedHashMap<>();

        EntityMapperContext(TypeMirror entityType, String entityFqn, Elements elementUtils, Types typeUtils) {
            this.entityType = entityType;
            this.entityFqn = entityFqn;
            this.entitySimpleName = extractSimpleName(entityFqn);
            this.entityElement = (TypeElement) ((DeclaredType) entityType).asElement();
            this.elementUtils = elementUtils;
            this.typeUtils = typeUtils;
        }

        void addRequestDTO(TypeElement dto, CrudXRequest annotation) {
            requestDTOs.put(dto, annotation);
        }

        void addResponseDTO(TypeElement dto, CrudXResponse annotation) {
            responseDTOs.put(dto, annotation);
        }

        private String extractSimpleName(String fqn) {
            int lastDot = fqn.lastIndexOf('.');
            return lastDot > 0 ? fqn.substring(lastDot + 1) : fqn;
        }
    }

    // ==================== MAPPER WRITER ====================

    private class MapperWriter {
        private final PrintWriter out;
        private final EntityMapperContext context;
        private final String packageName;
        private final String className;
        private final Elements elementUtils;
        private final Types typeUtils;
        private final CrudXDTOProcessor processor;

        MapperWriter(PrintWriter out, EntityMapperContext context, String packageName,
                     String className, Elements elementUtils, Types typeUtils, CrudXDTOProcessor processor) {
            this.out = out;
            this.context = context;
            this.packageName = packageName;
            this.className = className;
            this.elementUtils = elementUtils;
            this.typeUtils = typeUtils;
            this.processor = processor;
        }

        void write() {
            writeHeader();
            writeImports();
            writeClassDeclaration();
            writeFields();
            writeNestedMapperMethods();
            writeRequestMappings();
            writeResponseMappings();
            writePolymorphicDispatchers();
            writeInterfaceMethods();
            writeClassEnd();
        }

        private void writeHeader() {
            out.println("package " + packageName + ";");
            out.println();
        }

        private void writeImports() {
            Set<String> imports = new LinkedHashSet<>();

            imports.add("import io.github.sachinnimbal.crudx.core.dto.mapper.CrudXMapper;");
            imports.add("import org.springframework.stereotype.Component;");
            imports.add("import java.util.*;");
            imports.add("import java.util.stream.Collectors;");
            imports.add("import java.time.*;");
            imports.add("import java.time.format.DateTimeFormatter;");
            imports.add("import java.math.*;");

            imports.add("import " + context.entityFqn + ";");

            context.requestDTOs.keySet().forEach(dto ->
                    imports.add("import " + dto.getQualifiedName() + ";"));
            context.responseDTOs.keySet().forEach(dto ->
                    imports.add("import " + dto.getQualifiedName() + ";"));

            // ✅ NEW: Collect and import all enum types
            collectEnumImports(imports);

            for (NestedMapping mapping : context.nestedMappings.values()) {
                String dtoFqn = mapping.dtoType;
                String entityFqn = mapping.entityType;

                if (!isInnerClass(dtoFqn)) {
                    imports.add("import " + dtoFqn + ";");
                }

                if (!entityFqn.equals(context.entityFqn) && !isInnerClass(entityFqn)) {
                    imports.add("import " + entityFqn + ";");
                }
            }

            imports.forEach(out::println);
            out.println();
        }

        /**
         * ✅ NEW: Collect all enum types used in DTOs and entity
         */
        private void collectEnumImports(Set<String> imports) {
            // Collect from entity fields
            for (Element element : context.entityElement.getEnclosedElements()) {
                if (element.getKind() == ElementKind.FIELD) {
                    VariableElement field = (VariableElement) element;
                    if (isEnumType(field.asType())) {
                        String enumFqn = getFullyQualifiedName(field.asType());
                        if (enumFqn != null && !isInnerClass(enumFqn)) {
                            imports.add("import " + enumFqn + ";");
                        }
                    }
                }
            }

            // Collect from request DTOs
            for (TypeElement dto : context.requestDTOs.keySet()) {
                collectEnumsFromDTO(dto, imports);
            }

            // Collect from response DTOs
            for (TypeElement dto : context.responseDTOs.keySet()) {
                collectEnumsFromDTO(dto, imports);
            }
        }

        private void collectEnumsFromDTO(TypeElement dto, Set<String> imports) {
            for (Element element : dto.getEnclosedElements()) {
                if (element.getKind() == ElementKind.FIELD) {
                    VariableElement field = (VariableElement) element;
                    if (isEnumType(field.asType())) {
                        String enumFqn = getFullyQualifiedName(field.asType());
                        if (enumFqn != null && !isInnerClass(enumFqn)) {
                            imports.add("import " + enumFqn + ";");
                        }
                    }
                }
            }
        }

        private String getFullyQualifiedName(TypeMirror type) {
            if (type instanceof DeclaredType) {
                TypeElement element = (TypeElement) ((DeclaredType) type).asElement();
                return element.getQualifiedName().toString();
            }
            return null;
        }

        private boolean isInnerClass(String fqn) {
            // Check if it's an inner class (contains $ or is nested under entity/DTO package)
            if (fqn.contains("$")) {
                return true;
            }

            // Check if it's under entity class
            String entityFqn = context.entityElement.getQualifiedName().toString();
            if (fqn.startsWith(entityFqn + ".") && !fqn.equals(entityFqn)) {
                return true;
            }

            // Check if it's under any DTO class
            for (TypeElement dto : context.requestDTOs.keySet()) {
                String dtoFqn = dto.getQualifiedName().toString();
                if (fqn.startsWith(dtoFqn + ".") && !fqn.equals(dtoFqn)) {
                    return true;
                }
            }

            for (TypeElement dto : context.responseDTOs.keySet()) {
                String dtoFqn = dto.getQualifiedName().toString();
                if (fqn.startsWith(dtoFqn + ".") && !fqn.equals(dtoFqn)) {
                    return true;
                }
            }

            return false;
        }

        private void writeClassDeclaration() {
            out.println("/**");
            out.println(" * Generated mapper for " + context.entitySimpleName);
            out.println(" * Enhanced with full @CrudXField and @CrudXNested support");
            out.println(" * @generated by CrudX DTO Processor");
            out.println(" */");
            out.println("@Component");
            out.println("public class " + className + " implements CrudXMapper<");
            out.println("    " + context.entitySimpleName + ",");
            out.println("    Object,");
            out.println("    Object");
            out.println("> {");
            out.println();
        }

        private void writeFields() {
            out.println("    private static final Class<" + context.entitySimpleName +
                    "> ENTITY_CLASS = " + context.entitySimpleName + ".class;");
            out.println();

            // ✅ Add DateTimeFormatter fields for @CrudXField(format)
            Set<String> formats = collectFormatPatterns();
            if (!formats.isEmpty()) {
                out.println("    // Date/Time formatters");
                for (String format : formats) {
                    String fieldName = "FORMATTER_" + sanitizeFormatName(format);
                    out.println("    private static final DateTimeFormatter " + fieldName +
                            " = DateTimeFormatter.ofPattern(\"" + format + "\");");
                }
                out.println();
            }
        }

        private Set<String> collectFormatPatterns() {
            Set<String> formats = new LinkedHashSet<>();

            for (TypeElement dto : context.requestDTOs.keySet()) {
                collectFormatsFromDTO(dto, formats);
            }
            for (TypeElement dto : context.responseDTOs.keySet()) {
                collectFormatsFromDTO(dto, formats);
            }

            return formats;
        }

        private void collectFormatsFromDTO(TypeElement dto, Set<String> formats) {
            for (Element element : dto.getEnclosedElements()) {
                if (element.getKind() != ElementKind.FIELD) continue;

                VariableElement field = (VariableElement) element;
                CrudXField annotation = field.getAnnotation(CrudXField.class);

                if (annotation != null && !annotation.format().isEmpty()) {
                    formats.add(annotation.format());
                }
            }
        }

        private String sanitizeFormatName(String format) {
            return format.replaceAll("[^a-zA-Z0-9]", "_").toUpperCase();
        }

        private void writeNestedMapperMethods() {
            if (context.nestedMappings.isEmpty()) {
                return;
            }

            out.println("    // ==================== NESTED OBJECT MAPPERS ====================");
            out.println();

            Set<String> generatedMappers = new HashSet<>();

            for (NestedMapping mapping : context.nestedMappings.values()) {
                String dtoFqn = mapping.dtoType;
                String entityFqn = mapping.entityType;

                String dtoSimpleName = getClassReference(dtoFqn);
                String entitySimpleName = getClassReference(entityFqn);

                String mapperKey = dtoFqn + "|" + entityFqn;
                if (generatedMappers.contains(mapperKey)) {
                    continue;
                }
                generatedMappers.add(mapperKey);

                // DTO → Entity
                out.println("    private " + entitySimpleName + " map" + extractSimpleName(dtoFqn) + "To" +
                        extractSimpleName(entityFqn) + "(" + dtoSimpleName + " dto) {");
                out.println("        if (dto == null) return null;");
                out.println("        " + entitySimpleName + " entity = new " + entitySimpleName + "();");

                TypeElement dtoElement = elementUtils.getTypeElement(dtoFqn);
                TypeElement entityElement = elementUtils.getTypeElement(entityFqn);

                if (dtoElement != null && entityElement != null) {
                    copyFieldsWithAnnotationSupport(dtoElement, entityElement, "dto", "entity", true);
                }

                out.println("        return entity;");
                out.println("    }");
                out.println();

                // Entity → DTO
                out.println("    private " + dtoSimpleName + " map" + extractSimpleName(entityFqn) + "To" +
                        extractSimpleName(dtoFqn) + "(" + entitySimpleName + " entity) {");
                out.println("        if (entity == null) return null;");
                out.println("        " + dtoSimpleName + " dto = new " + dtoSimpleName + "();");

                if (dtoElement != null && entityElement != null) {
                    copyFieldsWithAnnotationSupport(dtoElement, entityElement, "entity", "dto", false);
                }

                out.println("        return dto;");
                out.println("    }");
                out.println();

                // List mappers
                writeListMappers(dtoSimpleName, entitySimpleName, dtoFqn, entityFqn);
            }
        }

        private void writeListMappers(String dtoSimpleName, String entitySimpleName,
                                      String dtoFqn, String entityFqn) {
            out.println("    private List<" + entitySimpleName + "> map" + extractSimpleName(dtoFqn) +
                    "ListTo" + extractSimpleName(entityFqn) + "List(List<" + dtoSimpleName + "> dtos) {");
            out.println("        if (dtos == null) return null;");
            out.println("        return dtos.stream().map(this::map" + extractSimpleName(dtoFqn) + "To" +
                    extractSimpleName(entityFqn) + ").collect(Collectors.toList());");
            out.println("    }");
            out.println();

            out.println("    private List<" + dtoSimpleName + "> map" + extractSimpleName(entityFqn) +
                    "ListTo" + extractSimpleName(dtoFqn) + "List(List<" + entitySimpleName + "> entities) {");
            out.println("        if (entities == null) return null;");
            out.println("        return entities.stream().map(this::map" + extractSimpleName(entityFqn) + "To" +
                    extractSimpleName(dtoFqn) + ").collect(Collectors.toList());");
            out.println("    }");
            out.println();
        }

        private void copyFieldsWithAnnotationSupport(TypeElement dtoElement, TypeElement entityElement,
                                                     String srcVar, String tgtVar, boolean dtoToEntity) {
            for (Element element : dtoElement.getEnclosedElements()) {
                if (element.getKind() != ElementKind.FIELD) continue;

                VariableElement dtoField = (VariableElement) element;
                Set<Modifier> modifiers = dtoField.getModifiers();
                if (modifiers.contains(Modifier.STATIC) || modifiers.contains(Modifier.FINAL)) {
                    continue;
                }

                CrudXField fieldAnnotation = dtoField.getAnnotation(CrudXField.class);
                if (fieldAnnotation != null && fieldAnnotation.ignore()) {
                    continue;
                }

                String dtoFieldName = dtoField.getSimpleName().toString();
                String sourceFieldName = fieldAnnotation != null && !fieldAnnotation.source().isEmpty()
                        ? fieldAnnotation.source() : dtoFieldName;

                VariableElement entityField = findFieldInType(entityElement, sourceFieldName);
                if (entityField == null) {
                    continue;
                }

                TypeMirror dtoFieldType = dtoField.asType();
                TypeMirror entityFieldType = entityField.asType();

                // ✅ Determine source and target types correctly
                TypeMirror sourceType = dtoToEntity ? dtoFieldType : entityFieldType;
                TypeMirror targetType = dtoToEntity ? entityFieldType : dtoFieldType;

                String sourceFieldNameFinal = dtoToEntity ? dtoFieldName : sourceFieldName;
                String targetFieldNameFinal = dtoToEntity ? sourceFieldName : dtoFieldName;

                // Generate getter/setter
                String getter = generateGetter(srcVar, sourceFieldNameFinal, sourceType);
                String setter = tgtVar + ".set" + capitalize(targetFieldNameFinal);

                // Handle @CrudXField(defaultValue)
                if (dtoToEntity && fieldAnnotation != null && !fieldAnnotation.defaultValue().isEmpty()) {
                    if (isPrimitiveType(dtoFieldType)) {
                        out.println("        {");
                        generateFieldMapping(getter, setter, sourceType, targetType,
                                fieldAnnotation, dtoToEntity, "            ");
                        out.println("        }");
                    } else {
                        out.println("        if (" + getter + " == null) {");
                        out.println("            " + setter + "(" +
                                generateDefaultValueLiteral(fieldAnnotation.defaultValue(), entityFieldType) + ");");
                        out.println("        } else {");
                        generateFieldMapping(getter, setter, sourceType, targetType,
                                fieldAnnotation, dtoToEntity, "            ");
                        out.println("        }");
                    }
                } else {
                    // ✅ For primitives, no null check needed
                    if (isPrimitiveType(sourceType)) {
                        out.println("        {");
                        generateFieldMapping(getter, setter, sourceType, targetType,
                                fieldAnnotation, dtoToEntity, "            ");
                        out.println("        }");
                    } else {
                        out.println("        if (" + getter + " != null) {");
                        generateFieldMapping(getter, setter, sourceType, targetType,
                                fieldAnnotation, dtoToEntity, "            ");
                        out.println("        }");
                    }
                }
            }
        }

        private void generateFieldMapping(String getter, String setter,
                                          TypeMirror sourceType, TypeMirror targetType,
                                          CrudXField annotation, boolean dtoToEntity, String indent) {
            // Check if nested mapping needed
            if (processor.needsNestedMapper(sourceType, targetType)) {
                String sourceTypeFqn = processor.extractTypeName(sourceType);
                String targetTypeFqn = processor.extractTypeName(targetType);

                if (sourceTypeFqn != null && targetTypeFqn != null) {
                    boolean isCollection = processor.isCollection(sourceType) ||
                            processor.isCollection(targetType);

                    String sourceSimple = extractSimpleName(sourceTypeFqn);
                    String targetSimple = extractSimpleName(targetTypeFqn);

                    if (dtoToEntity) {
                        if (isCollection) {
                            out.println(indent + setter + "(map" + sourceSimple +
                                    "ListTo" + targetSimple + "List(" + getter + "));");
                        } else {
                            out.println(indent + setter + "(map" + sourceSimple +
                                    "To" + targetSimple + "(" + getter + "));");
                        }
                    } else {
                        if (isCollection) {
                            out.println(indent + setter + "(map" + targetSimple +
                                    "ListTo" + sourceSimple + "List(" + getter + "));");
                        } else {
                            out.println(indent + setter + "(map" + targetSimple +
                                    "To" + sourceSimple + "(" + getter + "));");
                        }
                    }
                } else {
                    out.println(indent + setter + "(" + getter + ");");
                }
            } else {
                // ✅ CRITICAL FIX: Always check if types are different and apply conversion
                if (!typeUtils.isSameType(sourceType, targetType)) {
                    String conversion = generateTypeConversionCode(getter, sourceType, targetType, annotation);
                    out.println(indent + setter + "(" + conversion + ");");
                } else {
                    // ✅ Types are the same, but still apply transformer if exists
                    String value = annotation != null && !annotation.transformer().isEmpty()
                            ? applyTransformer(getter, annotation)
                            : getter;
                    out.println(indent + setter + "(" + value + ");");
                }
            }
        }

        private String generateDefaultValueLiteral(String defaultValue, TypeMirror targetType) {
            String typeName = targetType.toString();

            if (typeName.equals("java.lang.String")) {
                return "\"" + defaultValue + "\"";
            } else if (typeName.equals("int") || typeName.equals("java.lang.Integer")) {
                return defaultValue;
            } else if (typeName.equals("long") || typeName.equals("java.lang.Long")) {
                return defaultValue + "L";
            } else if (typeName.equals("double") || typeName.equals("java.lang.Double")) {
                return defaultValue + "D";
            } else if (typeName.equals("float") || typeName.equals("java.lang.Float")) {
                return defaultValue + "F";
            } else if (typeName.equals("boolean") || typeName.equals("java.lang.Boolean")) {
                return defaultValue;
            }

            return "\"" + defaultValue + "\"";
        }

        private String generateTypeConversionCode(String getter, TypeMirror sourceType,
                                                  TypeMirror targetType, CrudXField annotation) {
            if (typeUtils.isSameType(sourceType, targetType)) {
                return applyTransformer(getter, annotation);
            }

            String sourceTypeName = sourceType.toString();
            String targetTypeName = targetType.toString();

            // ✅ FIX: Handle String → Enum conversion
            if (sourceTypeName.equals("java.lang.String") && isEnumType(targetType)) {
                String enumClassName = getEnumClassName(targetType);
                return applyTransformer(enumClassName + ".valueOf(" + getter + ")", annotation);
            }

            // ✅ FIX: Handle Enum → String conversion (use .name() method)
            if (isEnumType(sourceType) && targetTypeName.equals("java.lang.String")) {
                return applyTransformer(getter + ".name()", annotation);
            }

            // Handle @CrudXField(format) for date/time
            if (annotation != null && !annotation.format().isEmpty()) {
                String formatterField = "FORMATTER_" + sanitizeFormatName(annotation.format());

                if (isTemporalType(targetTypeName)) {
                    return applyTransformer(
                            targetTypeName.substring(targetTypeName.lastIndexOf('.') + 1) +
                                    ".parse(" + getter + ", " + formatterField + ")",
                            annotation);
                } else if (isTemporalType(sourceTypeName)) {
                    return applyTransformer(
                            formatterField + ".format(" + getter + ")",
                            annotation);
                }
            }

            // ✅ FIX: Enum to Enum conversion (same enum type, just use getter directly)
            if (isEnumType(sourceType) && isEnumType(targetType)) {
                String sourceEnumFqn = getFullyQualifiedName(sourceType);
                String targetEnumFqn = getFullyQualifiedName(targetType);

                // Same enum type, no conversion needed
                if (sourceEnumFqn != null && sourceEnumFqn.equals(targetEnumFqn)) {
                    return applyTransformer(getter, annotation);
                }

                // Different enum types with same values
                String targetEnumClassName = getEnumClassName(targetType);
                return applyTransformer(targetEnumClassName + ".valueOf(" + getter + ".name())", annotation);
            }

            // No conversion needed
            return applyTransformer(getter, annotation);
        }

        /**
         * ✅ NEW: Get proper enum class name (handles inner classes)
         */
        private String getEnumClassName(TypeMirror enumType) {
            TypeElement enumElement = (TypeElement) ((DeclaredType) enumType).asElement();
            String fqn = enumElement.getQualifiedName().toString();

            // Check if it's an inner class of the entity
            String entityFqn = context.entityElement.getQualifiedName().toString();
            if (fqn.startsWith(entityFqn + "$") || fqn.startsWith(entityFqn + ".")) {
                // Inner class: Use EntityName.InnerClassName
                String innerPart = fqn.substring(entityFqn.length() + 1);
                return context.entitySimpleName + "." + innerPart.replace("$", ".");
            }

            // Check if it's an inner class of any DTO
            for (TypeElement dto : context.requestDTOs.keySet()) {
                String dtoFqn = dto.getQualifiedName().toString();
                if (fqn.startsWith(dtoFqn + "$") || fqn.startsWith(dtoFqn + ".")) {
                    String innerPart = fqn.substring(dtoFqn.length() + 1);
                    return dto.getSimpleName() + "." + innerPart.replace("$", ".");
                }
            }

            for (TypeElement dto : context.responseDTOs.keySet()) {
                String dtoFqn = dto.getQualifiedName().toString();
                if (fqn.startsWith(dtoFqn + "$") || fqn.startsWith(dtoFqn + ".")) {
                    String innerPart = fqn.substring(dtoFqn.length() + 1);
                    return dto.getSimpleName() + "." + innerPart.replace("$", ".");
                }
            }

            // Standalone enum: Use simple name (should be imported)
            return enumElement.getSimpleName().toString();
        }

        private String applyTransformer(String value, CrudXField annotation) {
            if (annotation == null || annotation.transformer().isEmpty()) {
                return value;
            }

            return switch (annotation.transformer()) {
                case "toUpperCase" -> "(" + value + ").toUpperCase()";
                case "toLowerCase" -> "(" + value + ").toLowerCase()";
                case "trim" -> "(" + value + ").trim()";
                default -> value;
            };
        }

        private boolean isTemporalType(String typeName) {
            return typeName.contains("LocalDateTime") ||
                    typeName.contains("LocalDate") ||
                    typeName.contains("LocalTime") ||
                    typeName.contains("ZonedDateTime") ||
                    typeName.contains("Instant");
        }

        private boolean isEnumType(TypeMirror type) {
            if (!(type instanceof DeclaredType)) return false;
            TypeElement element = (TypeElement) ((DeclaredType) type).asElement();
            return element.getKind() == ElementKind.ENUM;
        }

        private VariableElement findFieldInType(TypeElement typeElement, String fieldName) {
            for (Element element : typeElement.getEnclosedElements()) {
                if (element.getKind() == ElementKind.FIELD &&
                        element.getSimpleName().toString().equals(fieldName)) {
                    return (VariableElement) element;
                }
            }
            return null;
        }

        private String getClassReference(String fqn) {
            String entityFqn = context.entityElement.getQualifiedName().toString();

            if (fqn.startsWith(entityFqn + "$") || fqn.startsWith(entityFqn + ".")) {
                String innerClassName = fqn.substring(entityFqn.length() + 1);
                return context.entitySimpleName + "." + innerClassName.replace("$", ".");
            }

            return extractSimpleName(fqn);
        }

        private void writeRequestMappings() {
            if (context.requestDTOs.isEmpty()) return;

            out.println("    // ==================== REQUEST MAPPINGS ====================");
            out.println();

            context.requestDTOs.forEach((dtoElement, annotation) -> {
                writeToEntityMethod(dtoElement);
                writeUpdateEntityMethod(dtoElement);
            });
        }

        private void writeToEntityMethod(TypeElement dtoElement) {
            String dtoName = dtoElement.getSimpleName().toString();
            String entityName = context.entitySimpleName;

            out.println("    public " + entityName + " toEntityFrom" + dtoName +
                    "(" + dtoName + " dto) {");
            out.println("        if (dto == null) return null;");
            out.println("        " + entityName + " entity = new " + entityName + "();");

            copyFieldsWithAnnotationSupport(dtoElement, context.entityElement, "dto", "entity", true);

            out.println("        return entity;");
            out.println("    }");
            out.println();
        }

        private void writeUpdateEntityMethod(TypeElement dtoElement) {
            String dtoName = dtoElement.getSimpleName().toString();
            String entityName = context.entitySimpleName;

            out.println("    public void updateEntityFrom" + dtoName +
                    "(" + dtoName + " dto, " + entityName + " entity) {");
            out.println("        if (dto == null || entity == null) return;");

            copyFieldsWithAnnotationSupport(dtoElement, context.entityElement, "dto", "entity", true);

            out.println("    }");
            out.println();
        }

        private void writeResponseMappings() {
            if (context.responseDTOs.isEmpty()) return;

            out.println("    // ==================== RESPONSE MAPPINGS ====================");
            out.println();

            context.responseDTOs.forEach((dtoElement, annotation) -> {
                writeToResponseMethod(dtoElement);
                writeToResponseListMethod(dtoElement);
            });
        }

        private void writeToResponseMethod(TypeElement dtoElement) {
            String dtoName = dtoElement.getSimpleName().toString();
            String entityName = context.entitySimpleName;

            out.println("    public " + dtoName + " to" + dtoName +
                    "(" + entityName + " entity) {");
            out.println("        if (entity == null) return null;");
            out.println("        " + dtoName + " dto = new " + dtoName + "();");

            copyFieldsWithAnnotationSupport(dtoElement, context.entityElement, "entity", "dto", false);

            out.println("        return dto;");
            out.println("    }");
            out.println();
        }

        private void writeToResponseListMethod(TypeElement dtoElement) {
            String dtoName = dtoElement.getSimpleName().toString();
            String entityName = context.entitySimpleName;

            out.println("    public List<" + dtoName + "> to" + dtoName +
                    "List(List<" + entityName + "> entities) {");
            out.println("        if (entities == null) return null;");
            out.println("        return entities.stream().map(this::to" + dtoName +
                    ").collect(Collectors.toList());");
            out.println("    }");
            out.println();
        }

        private void writePolymorphicDispatchers() {
            out.println("    // ==================== DISPATCHERS ====================");
            out.println();

            writeToEntityDispatcher();
            writeUpdateEntityDispatcher();
            writeToResponseDispatcher();
            writeToResponseListDispatcher();
        }

        private void writeToEntityDispatcher() {
            out.println("    @Override");
            out.println("    public " + context.entitySimpleName + " toEntity(Object request) {");
            out.println("        if (request == null) return null;");

            if (!context.requestDTOs.isEmpty()) {
                context.requestDTOs.keySet().forEach(dto -> {
                    String dtoName = dto.getSimpleName().toString();
                    out.println("        if (request instanceof " + dtoName + " dto) {");
                    out.println("            return toEntityFrom" + dtoName + "(dto);");
                    out.println("        }");
                });
                out.println("        throw new IllegalArgumentException(\"Unsupported request DTO: \" + request.getClass().getName());");
            } else {
                out.println("        return (" + context.entitySimpleName + ") request;");
            }

            out.println("    }");
            out.println();
        }

        private void writeUpdateEntityDispatcher() {
            out.println("    @Override");
            out.println("    public void updateEntity(Object request, " + context.entitySimpleName + " entity) {");
            out.println("        if (request == null || entity == null) return;");

            if (!context.requestDTOs.isEmpty()) {
                context.requestDTOs.keySet().forEach(dto -> {
                    String dtoName = dto.getSimpleName().toString();
                    out.println("        if (request instanceof " + dtoName + " dto) {");
                    out.println("            updateEntityFrom" + dtoName + "(dto, entity);");
                    out.println("            return;");
                    out.println("        }");
                });
                out.println("        throw new IllegalArgumentException(\"Unsupported request DTO: \" + request.getClass().getName());");
            }

            out.println("    }");
            out.println();
        }

        private void writeToResponseDispatcher() {
            out.println("    @Override");
            out.println("    public Object toResponse(" + context.entitySimpleName + " entity) {");
            out.println("        if (entity == null) return null;");

            if (!context.responseDTOs.isEmpty()) {
                TypeElement firstDto = context.responseDTOs.keySet().iterator().next();
                String dtoName = firstDto.getSimpleName().toString();
                out.println("        return to" + dtoName + "(entity);");
            } else {
                out.println("        return entity;");
            }

            out.println("    }");
            out.println();
        }

        private void writeToResponseListDispatcher() {
            out.println("    @Override");
            out.println("    public List<Object> toResponseList(List<" + context.entitySimpleName + "> entities) {");
            out.println("        if (entities == null) return null;");
            out.println("        return entities.stream().map(this::toResponse).collect(Collectors.toList());");
            out.println("    }");
            out.println();
        }

        private void writeInterfaceMethods() {
            out.println("    @Override");
            out.println("    public Class<" + context.entitySimpleName + "> getEntityClass() {");
            out.println("        return ENTITY_CLASS;");
            out.println("    }");
            out.println();

            out.println("    @Override");
            out.println("    public Class<Object> getRequestClass() {");
            out.println("        return Object.class;");
            out.println("    }");
            out.println();

            out.println("    @Override");
            out.println("    public Class<Object> getResponseClass() {");
            out.println("        return Object.class;");
            out.println("    }");
        }

        private void writeClassEnd() {
            out.println("}");
        }

        private String capitalize(String str) {
            if (str == null || str.isEmpty()) return str;
            return Character.toUpperCase(str.charAt(0)) + str.substring(1);
        }

        private String generateGetter(String varName, String fieldName, TypeMirror fieldType) {
            String typeName = fieldType.toString();

            // For boolean/Boolean, use isXxx() pattern
            if (typeName.equals("boolean") || typeName.equals("java.lang.Boolean")) {
                return varName + ".is" + capitalize(fieldName) + "()";
            }

            // Standard getXxx() pattern
            return varName + ".get" + capitalize(fieldName) + "()";
        }

        private boolean isPrimitiveType(TypeMirror type) {
            String typeName = type.toString();
            return typeName.equals("boolean") ||
                    typeName.equals("byte") ||
                    typeName.equals("short") ||
                    typeName.equals("int") ||
                    typeName.equals("long") ||
                    typeName.equals("float") ||
                    typeName.equals("double") ||
                    typeName.equals("char");
        }

        private String extractSimpleName(String fqn) {
            if (fqn == null) return "Unknown";
            int lastDot = fqn.lastIndexOf('.');
            String name = lastDot > 0 ? fqn.substring(lastDot + 1) : fqn;
            int genericStart = name.indexOf('<');
            return genericStart > 0 ? name.substring(0, genericStart) : name;
        }
    }
}