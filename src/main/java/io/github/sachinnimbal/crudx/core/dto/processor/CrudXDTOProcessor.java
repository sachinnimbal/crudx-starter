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
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

@AutoService(Processor.class)
@SupportedAnnotationTypes({
        "io.github.sachinnimbal.crudx.core.dto.annotations.CrudXRequest",
        "io.github.sachinnimbal.crudx.core.dto.annotations.CrudXResponse"
})
@SupportedSourceVersion(SourceVersion.RELEASE_21)
@SupportedOptions("crudx.dto.enabled")
public class CrudXDTOProcessor extends AbstractProcessor {

    private boolean dtoEnabled = true;
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

        String dtoEnabledOption = processingEnv.getOptions().get("crudx.dto.enabled");
        if (dtoEnabledOption != null) {
            dtoEnabled = Boolean.parseBoolean(dtoEnabledOption);
        }

        if (!dtoEnabled) {
            messager.printMessage(Diagnostic.Kind.NOTE,
                    "⚠️  CrudX DTO feature is DISABLED (crudx.dto.enabled=false) - Skipping mapper generation");
        }
    }

    @Override
    public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {
        if (!dtoEnabled) {
            return false;
        }

        if (hasProcessed || annotations.isEmpty()) {
            return false;
        }

        int dtoCount = roundEnv.getElementsAnnotatedWith(CrudXRequest.class).size() +
                roundEnv.getElementsAnnotatedWith(CrudXResponse.class).size();
        if (dtoCount == 0) {
            return false;
        }
        if (dtoCount > 0 && !dtoEnabled) {
            messager.printMessage(Diagnostic.Kind.WARNING,
                    String.format("⚠️  Found %d DTO annotations but crudx.dto.enabled=false - Ignoring all DTOs", dtoCount));
            return false;
        }
        logInfo("🚀 CrudX DTO Processor - Processing " + dtoCount + " DTOs");
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

    private void validateNestedAnnotations(TypeElement dtoElement) {
        for (Element element : dtoElement.getEnclosedElements()) {
            if (element.getKind() != ElementKind.FIELD) continue;

            VariableElement field = (VariableElement) element;
            CrudXNested annotation = field.getAnnotation(CrudXNested.class);

            if (annotation != null) {
                if (annotation.maxDepth() < 0) {
                    error("@CrudXNested maxDepth must be >= 0 for field: " + field.getSimpleName(), field);
                }

                // ✅ Only validate dtoClass if it's NOT void.class
                try {
                    annotation.dtoClass();
                } catch (javax.lang.model.type.MirroredTypeException mte) {
                    TypeMirror dtoClassType = mte.getTypeMirror();

                    if (dtoClassType.getKind() != javax.lang.model.type.TypeKind.VOID) {
                        validateNestedDtoClass(field, dtoClassType);
                    }
                }

                if (annotation.nullStrategy() == CrudXNested.NullStrategy.EMPTY_COLLECTION) {
                    if (!isCollectionType(field.asType())) {
                        logWarn("Field " + field.getSimpleName() +
                                " uses EMPTY_COLLECTION nullStrategy but is not a Collection type");
                    }
                }
            }
        }
    }


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

    private void validateFormatString(VariableElement field, String format) {
        try {
            // Try to create formatter to validate syntax
            java.time.format.DateTimeFormatter.ofPattern(format);
        } catch (Exception e) {
            error("Invalid format pattern '" + format + "' for field " +
                    field.getSimpleName() + ": " + e.getMessage(), field);
        }
    }

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
            CrudXField fieldAnnotation = dtoField.getAnnotation(CrudXField.class);

            // Skip ignored fields
            if (fieldAnnotation != null && fieldAnnotation.ignore()) {
                continue;
            }

            String dtoFieldName = dtoField.getSimpleName().toString();
            String entityFieldName = fieldAnnotation != null && !fieldAnnotation.source().isEmpty()
                    ? fieldAnnotation.source() : dtoFieldName;

            VariableElement entityField = findFieldInEntityHierarchy(context.entityElement, entityFieldName);

            if (entityField == null) {
                continue;
            }

            TypeMirror dtoFieldType = dtoField.asType();
            TypeMirror entityFieldType = entityField.asType();

            // ✅ Check if this needs nested mapping (with or without @CrudXNested)
            if (nested != null || needsNestedMapper(dtoFieldType, entityFieldType)) {
                registerNestedMappingPair(dtoField, entityField, context, isRequest, nested);
                processNestedLevel(dtoField, entityField, context, isRequest, visited);

                // Recursively scan the nested DTO
                String nestedDtoTypeFqn = extractTypeName(dtoFieldType);
                if (nestedDtoTypeFqn != null) {
                    TypeElement nestedDtoElement = elementUtils.getTypeElement(nestedDtoTypeFqn);
                    if (nestedDtoElement != null) {
                        scanForNestedFields(nestedDtoElement, context, isRequest, visited);
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
                // ✅ Check if user specified explicit dtoClass
                String targetDtoFqn = dtoTypeFqn;
                if (annotation != null) {
                    try {
                        annotation.dtoClass();
                    } catch (javax.lang.model.type.MirroredTypeException mte) {
                        TypeMirror explicitDtoClass = mte.getTypeMirror();

                        if (explicitDtoClass.getKind() != javax.lang.model.type.TypeKind.VOID) {
                            TypeElement explicitElement = (TypeElement) typeUtils.asElement(explicitDtoClass);
                            if (explicitElement != null) {
                                targetDtoFqn = explicitElement.getQualifiedName().toString();
                            }
                        }
                    }
                }

                String mappingKey = targetDtoFqn + "|" + entityTypeFqn;
                if (!context.nestedMappings.containsKey(mappingKey)) {
                    NestedMapping mapping = new NestedMapping(targetDtoFqn, entityTypeFqn, isRequest, annotation);
                    context.nestedMappings.put(mappingKey, mapping);

                    // ✅ Debug log
                    logInfo("✓ Registered nested mapping: " + mappingKey +
                            " (direction: " + (isRequest ? "Request→Entity" : "Entity→Response") + ")");
                }
            }
        } catch (Exception e) {
            logWarn("Error registering nested field " + dtoField.getSimpleName() + ": " + e.getMessage());
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

    private class MapperWriter {
        private final PrintWriter out;
        private final EntityMapperContext context;
        private final String packageName;
        private final String className;
        private final Elements elementUtils;
        private final Types typeUtils;
        private final CrudXDTOProcessor processor;
        private final Map<String, String> nestedMethodRegistry = new LinkedHashMap<>();
        private final String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
        private int indentLevel = 0;

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
            writeFileHeader();
            writePackageAndImports();
            writeClassDeclaration();
            indent();
            writeConstants();
            writeNestedMappers();
            writeRequestMappers();
            writeResponseMappers();
            writeUtilityMethods();
            writeOverrideMethods();
            outdent();
            writeln("}");
        }

        // ==================== HEADER & STRUCTURE ====================

        private void writeFileHeader() {
            writeln("/*");
            writeln(" * ═══════════════════════════════════════════════════════════════════════════════");
            writeln(" * AUTO-GENERATED MAPPER - DO NOT MODIFY MANUALLY");
            writeln(" * ═══════════════════════════════════════════════════════════════════════════════");
            writeln(" *");
            writeln(" * Generator     : CrudX DTO Annotation Processor");
            writeln(" * Generated At  : " + timestamp);
            writeln(" * Entity Class  : " + context.entityFqn);
            writeln(" * Mapper Class  : " + className);
            writeln(" *");
            writeln(" * This mapper was automatically generated by the CrudX annotation processor.");
            writeln(" * Manual modifications to this file will be overwritten during recompilation.");
            writeln(" *");
            writeln(" * ═══════════════════════════════════════════════════════════════════════════════");
            writeln(" */");
            writeln();
        }

        private void writePackageAndImports() {
            writeln("package " + packageName + ";");
            writeln();

            Set<String> imports = collectAllImports();

            // Group 1: Framework imports
            writeln("// Framework Imports");
            imports.stream()
                    .filter(imp -> imp.contains("crudx") || imp.contains("springframework"))
                    .sorted()
                    .forEach(this::writeln);
            writeln();

            // Group 2: Java standard library
            writeln("// Java Standard Library");
            imports.stream()
                    .filter(imp -> imp.startsWith("import java."))
                    .sorted()
                    .forEach(this::writeln);
            writeln();

            // Group 3: Domain classes
            writeln("// Domain Classes");
            imports.stream()
                    .filter(imp -> !imp.contains("crudx") &&
                            !imp.contains("springframework") &&
                            !imp.startsWith("import java."))
                    .sorted()
                    .forEach(this::writeln);
            writeln();
        }

        private Set<String> collectAllImports() {
            Set<String> imports = new LinkedHashSet<>();

            imports.add("import io.github.sachinnimbal.crudx.core.dto.mapper.CrudXMapper;");
            imports.add("import org.springframework.stereotype.Component;");
            imports.add("import java.math.BigDecimal;");
            imports.add("import java.time.Instant;");
            imports.add("import java.time.LocalDate;");
            imports.add("import java.time.LocalDateTime;");
            imports.add("import java.time.LocalTime;");
            imports.add("import java.time.ZonedDateTime;");
            imports.add("import java.time.format.DateTimeFormatter;");
            imports.add("import java.util.ArrayList;");
            imports.add("import java.util.List;");
            imports.add("import java.util.stream.Collectors;");

            imports.add("import " + context.entityFqn + ";");

            context.requestDTOs.keySet().forEach(dto ->
                    imports.add("import " + dto.getQualifiedName() + ";"));
            context.responseDTOs.keySet().forEach(dto ->
                    imports.add("import " + dto.getQualifiedName() + ";"));

            collectEnumImports(imports);
            collectNestedImports(imports);

            return imports;
        }

        private void collectEnumImports(Set<String> imports) {
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
        }

        private void collectNestedImports(Set<String> imports) {
            for (NestedMapping mapping : context.nestedMappings.values()) {
                if (!isInnerClass(mapping.dtoType)) {
                    imports.add("import " + mapping.dtoType + ";");
                }
                if (!mapping.entityType.equals(context.entityFqn) && !isInnerClass(mapping.entityType)) {
                    imports.add("import " + mapping.entityType + ";");
                }
            }
        }

        private void writeClassDeclaration() {
            writeln("/**");
            writeln(" * Enterprise-grade mapper for {@link " + context.entitySimpleName + "} entity.");
            writeln(" *");
            writeln(" * <p>This mapper provides comprehensive bidirectional mapping capabilities between");
            writeln(" * entity and DTO layers with full support for:</p>");
            writeln(" *");
            writeln(" * <ul>");
            writeln(" *   <li>Request DTO to Entity conversion</li>");
            writeln(" *   <li>Entity to Response DTO conversion</li>");
            writeln(" *   <li>Deep nested object mapping</li>");
            writeln(" *   <li>Collection mapping (List, Set)</li>");
            writeln(" *   <li>Type conversion and transformation</li>");
            writeln(" *   <li>Enum mapping with case-insensitive matching</li>");
            writeln(" *   <li>Date/time formatting</li>");
            writeln(" *   <li>Default value handling</li>");
            writeln(" * </ul>");
            writeln(" *");

            if (!context.requestDTOs.isEmpty()) {
                writeln(" * <p><b>Supported Request DTOs:</b></p>");
                writeln(" * <ul>");
                context.requestDTOs.keySet().forEach(dto ->
                        writeln(" *   <li>{@link " + dto.getSimpleName() + "}</li>"));
                writeln(" * </ul>");
            }

            if (!context.responseDTOs.isEmpty()) {
                writeln(" * <p><b>Supported Response DTOs:</b></p>");
                writeln(" * <ul>");
                context.responseDTOs.keySet().forEach(dto ->
                        writeln(" *   <li>{@link " + dto.getSimpleName() + "}</li>"));
                writeln(" * </ul>");
            }

            writeln(" *");
            writeln(" * @author CrudX DTO Processor");
            writeln(" * @see CrudXMapper");
            writeln(" * @see " + context.entitySimpleName);
            writeln(" * @generatedAt " + timestamp);
            writeln(" */");
            writeln("@Component");
            writeln("public class " + className + " implements CrudXMapper<" +
                    context.entitySimpleName + ", Object, Object> {");
            writeln();
        }

        // ==================== CONSTANTS ====================

        private void writeConstants() {
            writeln("// ═══════════════════════════════════════════════════════════════════════════════");
            writeln("// CONSTANTS");
            writeln("// ═══════════════════════════════════════════════════════════════════════════════");
            writeln();

            writeln("private static final Class<" + context.entitySimpleName +
                    "> ENTITY_CLASS = " + context.entitySimpleName + ".class;");

            Set<String> formats = collectFormatPatterns();
            if (!formats.isEmpty()) {
                writeln();
                writeln("// Date/Time Format Patterns");
                for (String format : formats) {
                    String fieldName = "FORMATTER_" + sanitizeFormatName(format);
                    writeln("private static final DateTimeFormatter " + fieldName +
                            " = DateTimeFormatter.ofPattern(\"" + format + "\");");
                }
            }
            writeln();
        }

        // ==================== NESTED MAPPERS ====================

        private void writeNestedMappers() {
            if (context.nestedMappings.isEmpty()) return;

            writeln("// ═══════════════════════════════════════════════════════════════════════════════");
            writeln("// NESTED OBJECT MAPPERS");
            writeln("// ═══════════════════════════════════════════════════════════════════════════════");
            writeln();

            // Pre-register method names
            preRegisterNestedMethods();

            Set<String> generated = new HashSet<>();
            for (NestedMapping mapping : context.nestedMappings.values()) {
                generateNestedMapper(mapping, generated);
            }
        }

        private void preRegisterNestedMethods() {
            for (NestedMapping mapping : context.nestedMappings.values()) {
                String dtoSimple = extractSimpleName(mapping.dtoType);
                String entitySimple = extractSimpleName(mapping.entityType);
                String methodName = mapping.isRequest
                        ? dtoSimple + "To" + entitySimple
                        : entitySimple + "To" + dtoSimple;
                nestedMethodRegistry.put(mapping.dtoType + "|" + mapping.entityType, methodName);
            }
        }

        private void generateNestedMapper(NestedMapping mapping, Set<String> generated) {
            TypeElement dtoElement = elementUtils.getTypeElement(mapping.dtoType);
            TypeElement entityElement = elementUtils.getTypeElement(mapping.entityType);

            if (dtoElement == null || entityElement == null) return;

            String dtoSimple = getClassReference(mapping.dtoType);
            String entitySimple = getClassReference(mapping.entityType);
            String methodName = nestedMethodRegistry.get(mapping.dtoType + "|" + mapping.entityType);

            // Single object mapper
            String singleKey = mapping.dtoType + "|" + mapping.entityType + "|single";
            if (!generated.contains(singleKey)) {
                if (mapping.isRequest) {
                    writeNestedRequestMapper(methodName, dtoSimple, entitySimple, dtoElement, entityElement);
                } else {
                    writeNestedResponseMapper(methodName, entitySimple, dtoSimple, dtoElement, entityElement);
                }
                generated.add(singleKey);
            }

            // List mapper
            String listKey = mapping.dtoType + "|" + mapping.entityType + "|list";
            if (!generated.contains(listKey)) {
                writeNestedListMapper(methodName, dtoSimple, entitySimple, mapping.isRequest);
                generated.add(listKey);
            }
        }

        private void writeNestedRequestMapper(String methodName, String dtoType, String entityType,
                                              TypeElement dtoElement, TypeElement entityElement) {
            writeln("/**");
            writeln(" * Converts {@link " + dtoType + "} to {@link " + entityType + "}.");
            writeln(" *");
            writeln(" * @param dto the source DTO object");
            writeln(" * @return the mapped entity object, or null if input is null");
            writeln(" */");
            writeln("private " + entityType + " " + methodName + "(" + dtoType + " dto) {");
            indent();
            writeln("if (dto == null) {");
            indent();
            writeln("return null;");
            outdent();
            writeln("}");
            writeln();
            writeln(entityType + " entity = new " + entityType + "();");
            copyFields(dtoElement, entityElement, "dto", "entity", true);
            writeln("return entity;");
            outdent();
            writeln("}");
            writeln();
        }

        private void writeNestedResponseMapper(String methodName, String entityType, String dtoType,
                                               TypeElement dtoElement, TypeElement entityElement) {
            writeln("/**");
            writeln(" * Converts {@link " + entityType + "} to {@link " + dtoType + "}.");
            writeln(" *");
            writeln(" * @param entity the source entity object");
            writeln(" * @return the mapped DTO object, or null if input is null");
            writeln(" */");
            writeln("private " + dtoType + " " + methodName + "(" + entityType + " entity) {");
            indent();
            writeln("if (entity == null) {");
            indent();
            writeln("return null;");
            outdent();
            writeln("}");
            writeln();
            writeln(dtoType + " dto = new " + dtoType + "();");
            copyFields(dtoElement, entityElement, "entity", "dto", false);
            writeln("return dto;");
            outdent();
            writeln("}");
            writeln();
        }

        private void writeNestedListMapper(String methodName, String dtoType, String entityType, boolean isRequest) {
            if (isRequest) {
                writeln("/**");
                writeln(" * Converts a list of {@link " + dtoType + "} to a list of {@link " + entityType + "}.");
                writeln(" *");
                writeln(" * @param dtos the source DTO list");
                writeln(" * @return the mapped entity list, or null if input is null");
                writeln(" */");
                writeln("private List<" + entityType + "> " + methodName + "List(List<" + dtoType + "> dtos) {");
                indent();
                writeln("if (dtos == null) {");
                indent();
                writeln("return null;");
                outdent();
                writeln("}");
                writeln("return dtos.stream()");
                indent();
                writeln(".map(this::" + methodName + ")");
                writeln(".collect(Collectors.toList());");
                outdent();
                outdent();
                writeln("}");
            } else {
                writeln("/**");
                writeln(" * Converts a list of {@link " + entityType + "} to a list of {@link " + dtoType + "}.");
                writeln(" *");
                writeln(" * @param entities the source entity list");
                writeln(" * @return the mapped DTO list, or null if input is null");
                writeln(" */");
                writeln("private List<" + dtoType + "> " + methodName + "List(List<" + entityType + "> entities) {");
                indent();
                writeln("if (entities == null) {");
                indent();
                writeln("return null;");
                outdent();
                writeln("}");
                writeln("return entities.stream()");
                indent();
                writeln(".map(this::" + methodName + ")");
                writeln(".collect(Collectors.toList());");
                outdent();
                outdent();
                writeln("}");
            }
            writeln();
        }

        // ==================== REQUEST MAPPERS ====================

        private void writeRequestMappers() {
            if (context.requestDTOs.isEmpty()) return;

            writeln("// ═══════════════════════════════════════════════════════════════════════════════");
            writeln("// REQUEST DTO → ENTITY MAPPERS");
            writeln("// ═══════════════════════════════════════════════════════════════════════════════");
            writeln();

            context.requestDTOs.forEach((dtoElement, annotation) -> {
                writeRequestToEntityMapper(dtoElement);
                writeRequestUpdateMapper(dtoElement);
            });
        }

        private void writeRequestToEntityMapper(TypeElement dtoElement) {
            String dtoName = dtoElement.getSimpleName().toString();
            String entityName = context.entitySimpleName;

            writeln("/**");
            writeln(" * Creates a new {@link " + entityName + "} from {@link " + dtoName + "}.");
            writeln(" *");
            writeln(" * @param dto the request DTO containing data for entity creation");
            writeln(" * @return a new entity instance populated with DTO data, or null if input is null");
            writeln(" */");
            writeln("public " + entityName + " toEntityFrom" + dtoName + "(" + dtoName + " dto) {");
            indent();
            writeln("if (dto == null) {");
            indent();
            writeln("return null;");
            outdent();
            writeln("}");
            writeln();
            writeln(entityName + " entity = new " + entityName + "();");
            copyFields(dtoElement, context.entityElement, "dto", "entity", true);
            writeln("return entity;");
            outdent();
            writeln("}");
            writeln();
        }

        private void writeRequestUpdateMapper(TypeElement dtoElement) {
            String dtoName = dtoElement.getSimpleName().toString();
            String entityName = context.entitySimpleName;

            writeln("/**");
            writeln(" * Updates an existing {@link " + entityName + "} with data from {@link " + dtoName + "}.");
            writeln(" *");
            writeln(" * @param dto    the request DTO containing updated data");
            writeln(" * @param entity the existing entity to be updated");
            writeln(" */");
            writeln("public void updateEntityFrom" + dtoName + "(" + dtoName + " dto, " + entityName + " entity) {");
            indent();
            writeln("if (dto == null || entity == null) {");
            indent();
            writeln("return;");
            outdent();
            writeln("}");
            writeln();
            copyFields(dtoElement, context.entityElement, "dto", "entity", true);
            outdent();
            writeln("}");
            writeln();
        }

        // ==================== RESPONSE MAPPERS ====================

        private void writeResponseMappers() {
            if (context.responseDTOs.isEmpty()) return;

            writeln("// ═══════════════════════════════════════════════════════════════════════════════");
            writeln("// ENTITY → RESPONSE DTO MAPPERS");
            writeln("// ═══════════════════════════════════════════════════════════════════════════════");
            writeln();

            context.responseDTOs.forEach((dtoElement, annotation) -> {
                writeEntityToResponseMapper(dtoElement);
                writeEntityToResponseListMapper(dtoElement);
            });
        }

        private void writeEntityToResponseMapper(TypeElement dtoElement) {
            String dtoName = dtoElement.getSimpleName().toString();
            String entityName = context.entitySimpleName;

            writeln("/**");
            writeln(" * Converts {@link " + entityName + "} to {@link " + dtoName + "}.");
            writeln(" *");
            writeln(" * @param entity the source entity");
            writeln(" * @return the response DTO, or null if input is null");
            writeln(" */");
            writeln("public " + dtoName + " to" + dtoName + "(" + entityName + " entity) {");
            indent();
            writeln("if (entity == null) {");
            indent();
            writeln("return null;");
            outdent();
            writeln("}");
            writeln();
            writeln(dtoName + " dto = new " + dtoName + "();");
            copyFields(dtoElement, context.entityElement, "entity", "dto", false);
            writeln("return dto;");
            outdent();
            writeln("}");
            writeln();
        }

        private void writeEntityToResponseListMapper(TypeElement dtoElement) {
            String dtoName = dtoElement.getSimpleName().toString();
            String entityName = context.entitySimpleName;

            writeln("/**");
            writeln(" * Converts a list of {@link " + entityName + "} to a list of {@link " + dtoName + "}.");
            writeln(" *");
            writeln(" * @param entities the source entity list");
            writeln(" * @return the response DTO list, or null if input is null");
            writeln(" */");
            writeln("public List<" + dtoName + "> to" + dtoName + "List(List<" + entityName + "> entities) {");
            indent();
            writeln("if (entities == null) {");
            indent();
            writeln("return null;");
            outdent();
            writeln("}");
            writeln("return entities.stream()");
            indent();
            writeln(".map(this::to" + dtoName + ")");
            writeln(".collect(Collectors.toList());");
            outdent();
            outdent();
            writeln("}");
            writeln();
        }

        // ==================== UTILITY METHODS ====================

        private void writeUtilityMethods() {
            writeln("// ═══════════════════════════════════════════════════════════════════════════════");
            writeln("// UTILITY METHODS");
            writeln("// ═══════════════════════════════════════════════════════════════════════════════");
            writeln();

            writeln("/**");
            writeln(" * Parses a string value to an enum with flexible matching strategies.");
            writeln(" *");
            writeln(" * <p>This method attempts to match the string value to an enum constant using:</p>");
            writeln(" * <ol>");
            writeln(" *   <li>Exact match</li>");
            writeln(" *   <li>Case-insensitive match</li>");
            writeln(" *   <li>Uppercase conversion match</li>");
            writeln(" * </ol>");
            writeln(" *");
            writeln(" * @param value     the string value to parse");
            writeln(" * @param enumClass the target enum class");
            writeln(" * @param <E>       the enum type");
            writeln(" * @return the matching enum constant, or null if value is null/empty");
            writeln(" * @throws IllegalArgumentException if no matching enum constant is found");
            writeln(" */");
            writeln("@SuppressWarnings({\"unchecked\", \"rawtypes\"})");
            writeln("private <E extends Enum<E>> E parseEnum(String value, Class<E> enumClass) {");
            indent();
            writeln("if (value == null || value.isEmpty()) {");
            indent();
            writeln("return null;");
            outdent();
            writeln("}");
            writeln();
            writeln("try {");
            indent();
            writeln("return Enum.valueOf(enumClass, value);");
            outdent();
            writeln("} catch (IllegalArgumentException e) {");
            indent();
            writeln("// Attempt case-insensitive matching");
            writeln("for (E constant : enumClass.getEnumConstants()) {");
            indent();
            writeln("if (constant.name().equalsIgnoreCase(value)) {");
            indent();
            writeln("return constant;");
            outdent();
            writeln("}");
            outdent();
            writeln("}");
            writeln();
            writeln("// Final attempt: uppercase conversion");
            writeln("try {");
            indent();
            writeln("return Enum.valueOf(enumClass, value.toUpperCase());");
            outdent();
            writeln("} catch (IllegalArgumentException e2) {");
            indent();
            writeln("throw new IllegalArgumentException(");
            indent();
            writeln("String.format(\"Invalid enum value '%s' for type %s\", value, enumClass.getSimpleName()),");
            writeln("e2");
            outdent();
            writeln(");");
            outdent();
            writeln("}");
            outdent();
            writeln("}");
            outdent();
            writeln("}");
            writeln();
        }

        // ==================== OVERRIDE METHODS ====================

        private void writeOverrideMethods() {
            writeln("// ═══════════════════════════════════════════════════════════════════════════════");
            writeln("// CRUDX MAPPER INTERFACE IMPLEMENTATIONS");
            writeln("// ═══════════════════════════════════════════════════════════════════════════════");
            writeln();

            writeToEntityOverride();
            writeUpdateEntityOverride();
            writeToResponseOverride();
            writeToResponseListOverride();
            writeGetterOverrides();
        }

        private void writeToEntityOverride() {
            writeln("/**");
            writeln(" * {@inheritDoc}");
            writeln(" */");
            writeln("@Override");
            writeln("public " + context.entitySimpleName + " toEntity(Object request) {");
            indent();
            writeln("if (request == null) {");
            indent();
            writeln("return null;");
            outdent();
            writeln("}");
            writeln();

            if (!context.requestDTOs.isEmpty()) {
                context.requestDTOs.keySet().forEach(dto -> {
                    String dtoName = dto.getSimpleName().toString();
                    writeln("if (request instanceof " + dtoName + " dto) {");
                    indent();
                    writeln("return toEntityFrom" + dtoName + "(dto);");
                    outdent();
                    writeln("}");
                });
                writeln();
                writeln("throw new IllegalArgumentException(");
                indent();
                writeln("\"Unsupported request DTO type: \" + request.getClass().getName()");
                outdent();
                writeln(");");
            } else {
                writeln("return (" + context.entitySimpleName + ") request;");
            }

            outdent();
            writeln("}");
            writeln();
        }

        private void writeUpdateEntityOverride() {
            writeln("/**");
            writeln(" * {@inheritDoc}");
            writeln(" */");
            writeln("@Override");
            writeln("public void updateEntity(Object request, " + context.entitySimpleName + " entity) {");
            indent();
            writeln("if (request == null || entity == null) {");
            indent();
            writeln("return;");
            outdent();
            writeln("}");
            writeln();

            if (!context.requestDTOs.isEmpty()) {
                context.requestDTOs.keySet().forEach(dto -> {
                    String dtoName = dto.getSimpleName().toString();
                    writeln("if (request instanceof " + dtoName + " dto) {");
                    indent();
                    writeln("updateEntityFrom" + dtoName + "(dto, entity);");
                    writeln("return;");
                    outdent();
                    writeln("}");
                });
                writeln();
                writeln("throw new IllegalArgumentException(");
                indent();
                writeln("\"Unsupported request DTO type: \" + request.getClass().getName()");
                outdent();
                writeln(");");
            }

            outdent();
            writeln("}");
            writeln();
        }

        private void writeToResponseOverride() {
            writeln("/**");
            writeln(" * {@inheritDoc}");
            writeln(" */");
            writeln("@Override");
            writeln("public Object toResponse(" + context.entitySimpleName + " entity) {");
            indent();
            writeln("if (entity == null) {");
            indent();
            writeln("return null;");
            outdent();
            writeln("}");

            if (!context.responseDTOs.isEmpty()) {
                TypeElement firstDto = context.responseDTOs.keySet().iterator().next();
                String dtoName = firstDto.getSimpleName().toString();
                writeln("return to" + dtoName + "(entity);");
            } else {
                writeln("return entity;");
            }

            outdent();
            writeln("}");
            writeln();
        }

        private void writeToResponseListOverride() {
            writeln("/**");
            writeln(" * {@inheritDoc}");
            writeln(" */");
            writeln("@Override");
            writeln("public List<Object> toResponseList(List<" + context.entitySimpleName + "> entities) {");
            indent();
            writeln("if (entities == null) {");
            indent();
            writeln("return null;");
            outdent();
            writeln("}");
            writeln("return entities.stream()");
            indent();
            writeln(".map(this::toResponse)");
            writeln(".collect(Collectors.toList());");
            outdent();
            outdent();
            writeln("}");
            writeln();
        }

        private void writeGetterOverrides() {
            writeln("/**");
            writeln(" * {@inheritDoc}");
            writeln(" */");
            writeln("@Override");
            writeln("public Class<" + context.entitySimpleName + "> getEntityClass() {");
            indent();
            writeln("return ENTITY_CLASS;");
            outdent();
            writeln("}");
            writeln();

            writeln("/**");
            writeln(" * {@inheritDoc}");
            writeln(" */");
            writeln("@Override");
            writeln("public Class<Object> getRequestClass() {");
            indent();
            writeln("return Object.class;");
            outdent();
            writeln("}");
            writeln();

            writeln("/**");
            writeln(" * {@inheritDoc}");
            writeln(" */");
            writeln("@Override");
            writeln("public Class<Object> getResponseClass() {");
            indent();
            writeln("return Object.class;");
            outdent();
            writeln("}");
            writeln();
        }

        // ==================== FIELD COPYING ====================

        private void copyFields(TypeElement dtoElement, TypeElement entityElement,
                                String srcVar, String tgtVar, boolean dtoToEntity) {
            List<FieldMapping> mappings = new ArrayList<>();

            // Collect all field mappings first
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

                TypeMirror sourceType = dtoToEntity ? dtoFieldType : entityFieldType;
                TypeMirror targetType = dtoToEntity ? entityFieldType : dtoFieldType;

                String sourceFieldNameFinal = dtoToEntity ? dtoFieldName : sourceFieldName;
                String targetFieldNameFinal = dtoToEntity ? sourceFieldName : dtoFieldName;

                TypeElement sourceOwner = dtoToEntity ? dtoElement : entityElement;
                String getter = generateGetter(srcVar, sourceFieldNameFinal, sourceType, sourceOwner);
                String setter = tgtVar + ".set" + capitalize(targetFieldNameFinal);

                boolean needsNullCheck = !isPrimitiveType(sourceType);
                String conversion = generateFieldMapping(getter, sourceType, targetType, fieldAnnotation, dtoToEntity);

                String defaultValue = null;
                if (dtoToEntity && fieldAnnotation != null && !fieldAnnotation.defaultValue().isEmpty()) {
                    defaultValue = generateDefaultValueLiteral(fieldAnnotation.defaultValue(), entityFieldType);
                }

                mappings.add(new FieldMapping(
                        getter, setter, conversion, needsNullCheck, defaultValue, sourceFieldNameFinal
                ));
            }

            // Group mappings: primitives first, then simple fields, then complex/nested
            List<FieldMapping> primitiveFields = new ArrayList<>();
            List<FieldMapping> simpleFields = new ArrayList<>();
            List<FieldMapping> complexFields = new ArrayList<>();

            for (FieldMapping mapping : mappings) {
                if (!mapping.needsNullCheck) {
                    primitiveFields.add(mapping);
                } else if (mapping.conversion.contains("List") || mapping.conversion.contains("To")) {
                    complexFields.add(mapping);
                } else {
                    simpleFields.add(mapping);
                }
            }

            // Write primitive fields (no null checks)
            if (!primitiveFields.isEmpty()) {
                writeln("// Primitive fields");
                for (FieldMapping mapping : primitiveFields) {
                    if (mapping.defaultValue != null) {
                        writeln(mapping.setter + "(" + mapping.getter + " != null ? " +
                                mapping.conversion + " : " + mapping.defaultValue + ");");
                    } else {
                        writeln(mapping.setter + "(" + mapping.conversion + ");");
                    }
                }
                if (!simpleFields.isEmpty() || !complexFields.isEmpty()) {
                    writeln();
                }
            }

            // Write simple fields with compact null checks
            if (!simpleFields.isEmpty()) {
                writeln("// Simple field mappings");
                for (FieldMapping mapping : simpleFields) {
                    if (mapping.defaultValue != null) {
                        writeln(mapping.setter + "(" + mapping.getter + " != null ? " +
                                mapping.conversion + " : " + mapping.defaultValue + ");");
                    } else {
                        // Compact single-line for simple assignments
                        if (mapping.conversion.equals(mapping.getter)) {
                            writeln("if (" + mapping.getter + " != null) " +
                                    mapping.setter + "(" + mapping.conversion + ");");
                        } else {
                            writeln("if (" + mapping.getter + " != null) {");
                            indent();
                            writeln(mapping.setter + "(" + mapping.conversion + ");");
                            outdent();
                            writeln("}");
                        }
                    }
                }
                if (!complexFields.isEmpty()) {
                    writeln();
                }
            }

            // Write complex/nested fields
            if (!complexFields.isEmpty()) {
                writeln("// Nested object mappings");
                for (FieldMapping mapping : complexFields) {
                    writeln("if (" + mapping.getter + " != null) {");
                    indent();
                    writeln(mapping.setter + "(" + mapping.conversion + ");");
                    outdent();
                    writeln("}");
                }
            }
        }

        // Inner class to hold field mapping information
        private static class FieldMapping {
            final String getter;
            final String setter;
            final String conversion;
            final boolean needsNullCheck;
            final String defaultValue;
            final String fieldName;

            FieldMapping(String getter, String setter, String conversion,
                         boolean needsNullCheck, String defaultValue, String fieldName) {
                this.getter = getter;
                this.setter = setter;
                this.conversion = conversion;
                this.needsNullCheck = needsNullCheck;
                this.defaultValue = defaultValue;
                this.fieldName = fieldName;
            }
        }

        private String generateFieldMapping(String getter, TypeMirror sourceType, TypeMirror targetType,
                                            CrudXField annotation, boolean dtoToEntity) {
            // Check nested mapping
            if (processor.needsNestedMapper(sourceType, targetType)) {
                String sourceTypeFqn = processor.extractTypeName(sourceType);
                String targetTypeFqn = processor.extractTypeName(targetType);

                if (sourceTypeFqn != null && targetTypeFqn != null) {
                    boolean isCollection = processor.isCollection(sourceType) || processor.isCollection(targetType);

                    String dtoTypeFqn = dtoToEntity ? sourceTypeFqn : targetTypeFqn;
                    String entityTypeFqn = dtoToEntity ? targetTypeFqn : sourceTypeFqn;
                    String registryKey = dtoTypeFqn + "|" + entityTypeFqn;

                    String methodName = nestedMethodRegistry.get(registryKey);
                    if (methodName == null) {
                        String sourceSimple = extractSimpleName(sourceTypeFqn);
                        String targetSimple = extractSimpleName(targetTypeFqn);
                        methodName = sourceSimple + "To" + targetSimple;
                    }

                    return isCollection ? methodName + "List(" + getter + ")" : methodName + "(" + getter + ")";
                }
            }

            // Type conversion
            if (!typeUtils.isSameType(sourceType, targetType)) {
                return generateTypeConversion(getter, sourceType, targetType, annotation);
            }

            return annotation != null && !annotation.transformer().isEmpty()
                    ? applyTransformer(getter, annotation) : getter;
        }

        private String generateTypeConversion(String getter, TypeMirror sourceType,
                                              TypeMirror targetType, CrudXField annotation) {
            if (typeUtils.isSameType(sourceType, targetType)) {
                return applyTransformer(getter, annotation);
            }

            String sourceTypeName = sourceType.toString();
            String targetTypeName = targetType.toString();

            // String to Enum
            if (sourceTypeName.equals("java.lang.String") && isEnumType(targetType)) {
                String enumClassName = getEnumClassName(targetType);
                return "parseEnum(" + getter + ", " + enumClassName + ".class)";
            }

            // Enum to String
            if (isEnumType(sourceType) && targetTypeName.equals("java.lang.String")) {
                return applyTransformer(getter + ".name()", annotation);
            }

            // Temporal formatting
            if (annotation != null && !annotation.format().isEmpty()) {
                String formatterField = "FORMATTER_" + sanitizeFormatName(annotation.format());

                if (isTemporalType(targetTypeName)) {
                    return applyTransformer(
                            targetTypeName.substring(targetTypeName.lastIndexOf('.') + 1) +
                                    ".parse(" + getter + ", " + formatterField + ")",
                            annotation);
                } else if (isTemporalType(sourceTypeName)) {
                    return applyTransformer(formatterField + ".format(" + getter + ")", annotation);
                }
            }

            // Enum to Enum
            if (isEnumType(sourceType) && isEnumType(targetType)) {
                String sourceEnumFqn = getFullyQualifiedName(sourceType);
                String targetEnumFqn = getFullyQualifiedName(targetType);

                if (sourceEnumFqn != null && sourceEnumFqn.equals(targetEnumFqn)) {
                    return applyTransformer(getter, annotation);
                }

                String targetEnumClassName = getEnumClassName(targetType);
                return applyTransformer("parseEnum(" + getter + ".name(), " + targetEnumClassName + ".class)", annotation);
            }

            return applyTransformer(getter, annotation);
        }

        // ==================== HELPER METHODS ====================

        private void indent() {
            indentLevel++;
        }

        private void outdent() {
            if (indentLevel > 0) indentLevel--;
        }

        private void writeln(String line) {
            if (line.isEmpty()) {
                out.println();
            } else {
                out.println("    ".repeat(indentLevel) + line);
            }
        }

        private void writeln() {
            out.println();
        }

        private String capitalize(String str) {
            if (str == null || str.isEmpty()) return str;
            return Character.toUpperCase(str.charAt(0)) + str.substring(1);
        }

        private String generateGetter(String varName, String fieldName, TypeMirror fieldType, TypeElement ownerClass) {
            String typeName = fieldType.toString();
            boolean isBooleanType = typeName.equals("boolean") || typeName.equals("java.lang.Boolean");

            if (isBooleanType) {
                if (typeName.equals("java.lang.Boolean")) {
                    return varName + ".get" + capitalize(fieldName) + "()";
                } else {
                    if (fieldName.startsWith("is") && fieldName.length() > 2 &&
                            Character.isUpperCase(fieldName.charAt(2))) {
                        return varName + ".get" + capitalize(fieldName) + "()";
                    }
                    return varName + ".is" + capitalize(fieldName) + "()";
                }
            }

            return varName + ".get" + capitalize(fieldName) + "()";
        }

        private String generateDefaultValueLiteral(String defaultValue, TypeMirror targetType) {
            String typeName = targetType.toString();

            return switch (typeName) {
                case "java.lang.String" -> "\"" + defaultValue + "\"";
                case "int", "java.lang.Integer" -> defaultValue;
                case "long", "java.lang.Long" -> defaultValue + "L";
                case "double", "java.lang.Double" -> defaultValue + "D";
                case "float", "java.lang.Float" -> defaultValue + "F";
                case "boolean", "java.lang.Boolean" -> defaultValue;
                default -> "\"" + defaultValue + "\"";
            };
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

        private boolean isPrimitiveType(TypeMirror type) {
            return type.getKind().isPrimitive();
        }

        private String getEnumClassName(TypeMirror enumType) {
            TypeElement enumElement = (TypeElement) ((DeclaredType) enumType).asElement();
            String fqn = enumElement.getQualifiedName().toString();

            String entityFqn = context.entityElement.getQualifiedName().toString();
            if (fqn.startsWith(entityFqn + "$") || fqn.startsWith(entityFqn + ".")) {
                String innerPart = fqn.substring(entityFqn.length() + 1);
                return context.entitySimpleName + "." + innerPart.replace("$", ".");
            }

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

            return enumElement.getSimpleName().toString();
        }

        private String getFullyQualifiedName(TypeMirror type) {
            if (type instanceof DeclaredType) {
                TypeElement element = (TypeElement) ((DeclaredType) type).asElement();
                return element.getQualifiedName().toString();
            }
            return null;
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

        private boolean isInnerClass(String fqn) {
            if (fqn.contains("$")) {
                return true;
            }

            String entityFqn = context.entityElement.getQualifiedName().toString();
            if (fqn.startsWith(entityFqn + ".") && !fqn.equals(entityFqn)) {
                return true;
            }

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

        private String extractSimpleName(String fqn) {
            if (fqn == null) return "Unknown";
            int lastDot = fqn.lastIndexOf('.');
            String name = lastDot > 0 ? fqn.substring(lastDot + 1) : fqn;
            int genericStart = name.indexOf('<');
            return genericStart > 0 ? name.substring(0, genericStart) : name;
        }
    }
}
