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

/**
 * 🚀 Ultra-Flexible CrudX DTO Processor with Smart Auto-Mapping
 * <p>
 * Key Features:
 * - Zero configuration - works with ANY field structure
 * - Smart field resolution - finds fields in nested objects automatically
 * - Flattened DTO support - maps flat DTOs to nested entities
 * - Type intelligence - auto-detects when mappers are needed
 * - Null-safe navigation for nested paths
 */
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

        logInfo("🚀 CrudX DTO Processor");
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

    // ==================== SMART FIELD RESOLUTION ====================

    /**
     * 🚀 ULTRA-INTELLIGENT FIELD PATH FINDER
     * Handles ALL scenarios:
     * 1. Direct match (name → name)
     * 2. Explicit dotted path (demographics.firstName)
     * 3. Flattened decomposition (customerName → customer.name)
     * 4. Deep nested search (personalInfo.address.city)
     */
    private FieldPath findFieldPath(TypeElement entityElement, String fieldName, int maxDepth) {
        // PRIORITY 1: Explicit dotted path (demographics.firstName)
        if (fieldName.contains(".")) {
            FieldPath explicitPath = resolveExplicitDottedPath(entityElement, fieldName);
            if (explicitPath != null) {
                return explicitPath;
            }
        }

        // PRIORITY 2: Direct field match (name → name)
        VariableElement directField = findFieldInEntityHierarchy(entityElement, fieldName);
        if (directField != null) {
            return new FieldPath(fieldName, directField, null, false);
        }

        // PRIORITY 3: Flattened field decomposition (customerName → customer.name)
        FieldPath flattenedPath = tryFlattenedDecomposition(entityElement, fieldName, maxDepth);
        if (flattenedPath != null) {
            return flattenedPath;
        }

        // PRIORITY 4: Deep nested search (search all nested objects)
        FieldPath nestedPath = searchInNestedObjects(entityElement, fieldName, "", maxDepth, 0);
        if (nestedPath != null) {
            return nestedPath;
        }

        return null;
    }

    /**
     * 🔥 FLATTENED FIELD DECOMPOSITION
     * Examples:
     * - customerName → customer.name
     * - customerEmail → customer.email
     * - shippingCity → shipping.address.city OR shippingDetails.address.city
     * - customerProfileFirstName → customer.profile.firstName
     */
    private FieldPath tryFlattenedDecomposition(TypeElement entityElement, String flattenedName, int maxDepth) {
        // Try all possible prefix lengths
        for (int i = 1; i < flattenedName.length(); i++) {
            if (Character.isUpperCase(flattenedName.charAt(i))) {
                String prefix = flattenedName.substring(0, i);
                String suffix = Character.toLowerCase(flattenedName.charAt(i)) + flattenedName.substring(i + 1);

                // Check if prefix exists as a field in entity
                VariableElement prefixField = findFieldInEntityHierarchy(entityElement, prefix);
                if (prefixField != null && isComplexType(prefixField.asType())) {
                    TypeElement prefixType = getTypeElement(prefixField.asType());
                    if (prefixType != null) {
                        // STRATEGY A: Direct field in nested object (customer.name)
                        VariableElement suffixField = findFieldInEntityHierarchy(prefixType, suffix);
                        if (suffixField != null) {
                            return new FieldPath(
                                    prefix + "." + suffix,
                                    suffixField,
                                    prefix,
                                    true
                            );
                        }

                        // STRATEGY B: Recursive decomposition (shippingCity → shipping.address.city)
                        FieldPath deepPath = tryFlattenedDecomposition(prefixType, suffix, maxDepth - 1);
                        if (deepPath != null) {
                            String fullPath = prefix + "." + deepPath.fullPath;
                            return new FieldPath(
                                    fullPath,
                                    deepPath.field,
                                    prefix + (deepPath.parentPath != null ? "." + deepPath.parentPath : ""),
                                    true
                            );
                        }

                        // STRATEGY C: Search all nested objects in prefixType
                        FieldPath searchPath = searchInNestedObjects(prefixType, suffix, prefix, maxDepth - 1, 0);
                        if (searchPath != null) {
                            return searchPath;
                        }
                    }
                }
            }
        }

        return null;
    }

    /**
     * 🔥 RESOLVE EXPLICIT DOTTED PATHS (demographics.firstName)
     */
    private FieldPath resolveExplicitDottedPath(TypeElement entityElement, String dottedPath) {
        String[] segments = dottedPath.split("\\.");
        TypeElement currentType = entityElement;
        VariableElement currentField = null;
        StringBuilder pathBuilder = new StringBuilder();
        StringBuilder parentPathBuilder = new StringBuilder();

        for (int i = 0; i < segments.length; i++) {
            String segment = segments[i];
            currentField = findFieldInEntityHierarchy(currentType, segment);

            if (currentField == null) {
                return null;
            }

            if (i > 0) {
                pathBuilder.append(".");
            }
            pathBuilder.append(segment);

            if (i < segments.length - 1) {
                if (i > 0) {
                    parentPathBuilder.append(".");
                }
                parentPathBuilder.append(segment);

                // Move to next type
                TypeMirror fieldType = currentField.asType();
                if (!isComplexType(fieldType)) {
                    return null; // Can't navigate further
                }
                currentType = getTypeElement(fieldType);
                if (currentType == null) {
                    return null;
                }
            }
        }

        String parentPath = !parentPathBuilder.isEmpty() ? parentPathBuilder.toString() : null;
        return new FieldPath(pathBuilder.toString(), currentField, parentPath, segments.length > 1);
    }

    /**
     * Recursively search for field in nested objects with visited tracking
     */
    private FieldPath searchInNestedObjects(TypeElement currentClass, String targetFieldName,
                                            String currentPath, int maxDepth, int depth) {
        if (depth >= maxDepth) {
            return null;
        }

        Set<String> visited = new HashSet<>();
        return searchInNestedObjectsWithVisited(currentClass, targetFieldName, currentPath, maxDepth, depth, visited);
    }

    private FieldPath searchInNestedObjectsWithVisited(TypeElement currentClass, String targetFieldName,
                                                       String currentPath, int maxDepth, int depth,
                                                       Set<String> visited) {
        if (depth >= maxDepth) {
            return null;
        }

        String classFqn = currentClass.getQualifiedName().toString();
        if (visited.contains(classFqn)) {
            return null; // Prevent circular references
        }
        visited.add(classFqn);

        for (Element element : currentClass.getEnclosedElements()) {
            if (element.getKind() != ElementKind.FIELD) continue;

            VariableElement field = (VariableElement) element;
            Set<Modifier> modifiers = field.getModifiers();
            if (modifiers.contains(Modifier.STATIC) || modifiers.contains(Modifier.FINAL)) {
                continue;
            }

            // Direct match in current class
            if (field.getSimpleName().toString().equals(targetFieldName)) {
                String fullPath = currentPath.isEmpty() ?
                        targetFieldName :
                        currentPath + "." + targetFieldName;
                return new FieldPath(fullPath, field, currentPath.isEmpty() ? null : currentPath, !currentPath.isEmpty());
            }

            TypeMirror fieldType = field.asType();

            // Recurse into complex types
            if (isComplexType(fieldType)) {
                TypeElement nestedType = getTypeElement(fieldType);
                if (nestedType != null) {
                    // Check if the nested object has our target field
                    VariableElement targetField = findFieldInEntityHierarchy(nestedType, targetFieldName);
                    if (targetField != null) {
                        String path = currentPath.isEmpty() ?
                                field.getSimpleName().toString() :
                                currentPath + "." + field.getSimpleName().toString();
                        return new FieldPath(
                                path + "." + targetFieldName,
                                targetField,
                                path,
                                true
                        );
                    }

                    // Recurse deeper
                    String newPath = currentPath.isEmpty() ?
                            field.getSimpleName().toString() :
                            currentPath + "." + field.getSimpleName().toString();
                    FieldPath result = searchInNestedObjectsWithVisited(nestedType, targetFieldName,
                            newPath, maxDepth, depth + 1, visited);
                    if (result != null) {
                        return result;
                    }
                }
            }
        }

        return null;
    }

    /**
     * Check if type is complex (user-defined class, not primitive/common types)
     */
    private boolean isComplexType(TypeMirror type) {
        if (!(type instanceof DeclaredType)) {
            return false;
        }

        TypeElement element = (TypeElement) ((DeclaredType) type).asElement();
        String fqn = element.getQualifiedName().toString();

        // Exclude primitives, wrappers, common Java types
        return !fqn.startsWith("java.lang.") &&
                !fqn.startsWith("java.util.") &&
                !fqn.startsWith("java.time.") &&
                !fqn.startsWith("java.math.") &&
                !element.getKind().equals(ElementKind.ENUM);
    }

    private TypeElement getTypeElement(TypeMirror type) {
        if (type instanceof DeclaredType) {
            return (TypeElement) ((DeclaredType) type).asElement();
        }
        return null;
    }

    /**
     * Enhanced FieldPath with complete information
     */
    private static class FieldPath {
        String fullPath;        // e.g., "personalInfo.firstName"
        VariableElement field;  // The actual field element
        String parentPath;      // e.g., "personalInfo" (null if direct)
        boolean isNested;       // true if field is in nested object

        FieldPath(String fullPath, VariableElement field, String parentPath, boolean isNested) {
            this.fullPath = fullPath;
            this.field = field;
            this.parentPath = parentPath;
            this.isNested = isNested;
        }
    }

    // ==================== SMART TYPE MATCHING ====================

    /**
     * Check if DTO type and Entity type are compatible for mapping (bidirectional)
     */
    private boolean areTypesCompatible(TypeMirror type1, TypeMirror type2) {
        // Exact match
        if (typeUtils.isSameType(type1, type2)) {
            return true;
        }

        // Both are complex types - check if they're DTO/Entity pairs
        if (isComplexType(type1) && isComplexType(type2)) {
            String type1Name = extractSimpleName(type1.toString());
            String type2Name = extractSimpleName(type2.toString());

            // Remove Request/Response suffixes for comparison
            String type1Base = type1Name.replace("Request", "").replace("Response", "");
            String type2Base = type2Name.replace("Request", "").replace("Response", "");

            // Check bidirectional naming patterns
            // AddressRequest ↔ Address, Address ↔ AddressResponse, etc.
            // Also handles: Post ↔ PostSummaryResponse (recursive)
            if (type1Base.equals(type2Name) ||
                    type1Name.equals(type2Base) ||
                    type2Base.startsWith(type1Base) ||
                    type1Base.startsWith(type2Base)) {
                return true;
            }
        }

        // Collection compatibility
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

    /**
     * Determine if two types need a nested mapper (checks both directions)
     */
    private boolean needsNestedMapper(TypeMirror type1, TypeMirror type2) {
        // Handle collections first
        if (isCollection(type1) && isCollection(type2)) {
            TypeMirror item1Type = getCollectionItemType(type1);
            TypeMirror item2Type = getCollectionItemType(type2);
            if (item1Type != null && item2Type != null) {
                // Check if the item types are complex and need mapping
                if (isComplexType(item1Type) && isComplexType(item2Type)) {
                    if (!typeUtils.isSameType(item1Type, item2Type)) {
                        return areTypesCompatible(item1Type, item2Type);
                    }
                }
            }
            return false;
        }

        // Both must be complex types
        if (!isComplexType(type1) || !isComplexType(type2)) {
            return false;
        }

        // Same type doesn't need mapper
        if (typeUtils.isSameType(type1, type2)) {
            return false;
        }

        // Check if they're DTO/Entity pairs (bidirectional)
        return areTypesCompatible(type1, type2);
    }

    // ==================== DTO COLLECTION ====================

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

                context.addResponseDTO(dtoElement, annotation);
                logInfo("✓ Response: " + dtoElement.getSimpleName() + " ← " + context.entitySimpleName);

            } catch (Exception e) {
                error("Failed to process @CrudXResponse: " + e.getMessage(), element);
            }
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
                    registerNestedMappingPair(dtoField, entityField, context, isRequest);
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
                // Auto-detect nested objects even without @CrudXNested annotation
                TypeMirror dtoFieldType = dtoField.asType();
                if (isComplexType(dtoFieldType)) {
                    String entityFieldName = getEntityFieldName(dtoField);
                    VariableElement entityField = findFieldInEntityHierarchy(context.entityElement, entityFieldName);

                    if (entityField != null) {
                        TypeMirror entityFieldType = entityField.asType();
                        if (needsNestedMapper(dtoFieldType, entityFieldType)) {
                            registerNestedMappingPair(dtoField, entityField, context, isRequest);
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
                    registerNestedMappingPair(dtoField, entityField, mainContext, isRequest);
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
                                           EntityMapperContext context, boolean isRequest) {
        try {
            TypeMirror dtoFieldType = dtoField.asType();
            TypeMirror entityFieldType = entityField.asType();

            String dtoTypeFqn = extractTypeName(dtoFieldType);
            String entityTypeFqn = extractTypeName(entityFieldType);

            if (dtoTypeFqn != null && entityTypeFqn != null) {
                String mappingKey = dtoTypeFqn + "|" + entityTypeFqn;
                if (!context.nestedMappings.containsKey(mappingKey)) {
                    NestedMapping mapping = new NestedMapping(dtoTypeFqn, entityTypeFqn, isRequest);
                    context.nestedMappings.put(mappingKey, mapping);
                    logInfo("✓ Nested: " + extractSimpleName(dtoTypeFqn) + " ↔ " + extractSimpleName(entityTypeFqn));
                }
            }
        } catch (Exception e) {
            logWarn("Error mapping nested field " + dtoField.getSimpleName() + ": " + e.getMessage());
        }
    }

    private String getEntityFieldName(VariableElement dtoField) {
        CrudXField fieldAnnotation = dtoField.getAnnotation(CrudXField.class);
        if (fieldAnnotation != null && !fieldAnnotation.source().isEmpty()) {
            return fieldAnnotation.source();
        }
        return dtoField.getSimpleName().toString();
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

        logInfo("✅ Generated " + successCount + " mapper classes");
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

        NestedMapping(String dtoType, String entityType, boolean isRequest) {
            this.dtoType = dtoType;
            this.entityType = entityType;
            this.isRequest = isRequest;
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

            imports.add("import " + context.entityFqn + ";");

            context.requestDTOs.keySet().forEach(dto ->
                    imports.add("import " + dto.getQualifiedName() + ";"));
            context.responseDTOs.keySet().forEach(dto ->
                    imports.add("import " + dto.getQualifiedName() + ";"));

            for (NestedMapping mapping : context.nestedMappings.values()) {
                String dtoFqn = mapping.dtoType;
                String entityFqn = mapping.entityType;

                if (isInnerClassOf(dtoFqn, context.requestDTOs.keySet()) &&
                        isInnerClassOf(dtoFqn, context.responseDTOs.keySet())) {
                    imports.add("import " + dtoFqn + ";");
                }

                if (!entityFqn.equals(context.entityFqn) &&
                        !isInnerClassOf(entityFqn, context.entityElement)) {
                    imports.add("import " + entityFqn + ";");
                }
            }

            imports.forEach(out::println);
            out.println();
        }

        private boolean isInnerClassOf(String fqn, Set<TypeElement> parentClasses) {
            for (TypeElement parent : parentClasses) {
                String parentFqn = parent.getQualifiedName().toString();
                if (fqn.startsWith(parentFqn + ".") || fqn.startsWith(parentFqn + "$")) {
                    return false;
                }
            }
            return true;
        }

        private boolean isInnerClassOf(String fqn, TypeElement entityElement) {
            String entityFqn = entityElement.getQualifiedName().toString();
            return fqn.startsWith(entityFqn + ".") || fqn.startsWith(entityFqn + "$");
        }

        private void writeClassDeclaration() {
            out.println("/**");
            out.println(" * Generated mapper for " + context.entitySimpleName);
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

                // DTO → Entity (for Request mappings)
                out.println("    private " + entitySimpleName + " map" + extractSimpleName(dtoFqn) + "To" +
                        extractSimpleName(entityFqn) + "(" + dtoSimpleName + " dto) {");
                out.println("        if (dto == null) return null;");
                out.println("        " + entitySimpleName + " entity = new " + entitySimpleName + "();");

                TypeElement dtoElement = elementUtils.getTypeElement(dtoFqn);
                TypeElement entityElement = elementUtils.getTypeElement(entityFqn);

                if (dtoElement != null && entityElement != null) {
                    copyFieldsWithSmartMapping(dtoElement, entityElement, "dto", "entity", true);
                }

                out.println("        return entity;");
                out.println("    }");
                out.println();

                // Entity → DTO (for Response mappings)
                out.println("    private " + dtoSimpleName + " map" + extractSimpleName(entityFqn) + "To" +
                        extractSimpleName(dtoFqn) + "(" + entitySimpleName + " entity) {");
                out.println("        if (entity == null) return null;");
                out.println("        " + dtoSimpleName + " dto = new " + dtoSimpleName + "();");

                if (dtoElement != null && entityElement != null) {
                    copyFieldsWithSmartMapping(dtoElement, entityElement, "entity", "dto", false);
                }

                out.println("        return dto;");
                out.println("    }");
                out.println();

                // List mappers
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
        }

        /**
         * Smart field copying with automatic nested field resolution
         */
        private void copyFieldsWithSmartMapping(TypeElement dtoElement, TypeElement entityElement,
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

                // 🔥 USE SMART PATH RESOLUTION
                FieldPath fieldPath = findFieldPath(entityElement, sourceFieldName, 5);

                if (fieldPath == null) {
                    logWarn("Field '" + dtoFieldName + "' (source: '" + sourceFieldName +
                            "') not found in " + entityElement.getSimpleName());
                    continue;
                }

                TypeMirror dtoFieldType = dtoField.asType();
                TypeMirror entityFieldType = fieldPath.field.asType();

                String getter, setter;
                if (dtoToEntity) {
                    getter = srcVar + ".get" + capitalize(dtoFieldName) + "()";
                    setter = generatePathSetter(tgtVar, fieldPath);
                } else {
                    getter = generatePathGetter(srcVar, fieldPath);
                    setter = tgtVar + ".set" + capitalize(dtoFieldName);
                }

                out.println("        if (" + getter + " != null) {");

                // Handle nested mapping
                if (processor.needsNestedMapper(dtoFieldType, entityFieldType)) {
                    String dtoTypeFqn = processor.extractTypeName(dtoFieldType);
                    String entityTypeFqn = processor.extractTypeName(entityFieldType);

                    if (dtoTypeFqn != null && entityTypeFqn != null) {
                        boolean isCollection = processor.isCollection(dtoFieldType) ||
                                processor.isCollection(entityFieldType);

                        String dtoSimple = extractSimpleName(dtoTypeFqn);
                        String entitySimple = extractSimpleName(entityTypeFqn);

                        if (dtoToEntity) {
                            if (isCollection) {
                                out.println("            " + setter + "(map" + dtoSimple +
                                        "ListTo" + entitySimple + "List(" + getter + "));");
                            } else {
                                out.println("            " + setter + "(map" + dtoSimple +
                                        "To" + entitySimple + "(" + getter + "));");
                            }
                        } else {
                            if (isCollection) {
                                out.println("            " + setter + "(map" + entitySimple +
                                        "ListTo" + dtoSimple + "List(" + getter + "));");
                            } else {
                                out.println("            " + setter + "(map" + entitySimple +
                                        "To" + dtoSimple + "(" + getter + "));");
                            }
                        }
                    } else {
                        out.println("            " + setter + "(" + getter + ");");
                    }
                } else {
                    // Type conversion if needed
                    if (needsTypeConversion(dtoFieldType, entityFieldType)) {
                        String conversion = generateTypeConversion(getter, dtoFieldType, entityFieldType);
                        if (conversion != null) {
                            out.println("            " + setter + "(" + conversion + ");");
                        }
                    } else {
                        out.println("            " + setter + "(" + getter + ");");
                    }
                }

                out.println("        }");
            }
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
            for (TypeElement dto : context.requestDTOs.keySet()) {
                String dtoFqn = dto.getQualifiedName().toString();
                if (fqn.startsWith(dtoFqn + "$") || fqn.startsWith(dtoFqn + ".")) {
                    String innerClassName = fqn.substring(dtoFqn.length() + 1);
                    return dto.getSimpleName() + "." + innerClassName.replace("$", ".");
                }
            }

            for (TypeElement dto : context.responseDTOs.keySet()) {
                String dtoFqn = dto.getQualifiedName().toString();
                if (fqn.startsWith(dtoFqn + "$") || fqn.startsWith(dtoFqn + ".")) {
                    String innerClassName = fqn.substring(dtoFqn.length() + 1);
                    return dto.getSimpleName() + "." + innerClassName.replace("$", ".");
                }
            }

            return extractSimpleName(fqn);
        }

        private boolean needsTypeConversion(TypeMirror sourceType, TypeMirror targetType) {
            if (typeUtils.isSameType(sourceType, targetType)) {
                return false;
            }

            // Only handle enum to enum conversions automatically
            if (isEnumType(sourceType) && isEnumType(targetType)) {
                String sourceName = extractSimpleName(sourceType.toString());
                String targetName = extractSimpleName(targetType.toString());
                return sourceName.equals(targetName);
            }

            // Don't try to auto-convert between numbers and complex types
            return false;
        }

        private boolean isNumberType(TypeMirror type) {
            String typeName = type.toString();
            return typeName.equals("java.lang.Double") ||
                    typeName.equals("double") ||
                    typeName.equals("java.lang.Float") ||
                    typeName.equals("float") ||
                    typeName.equals("java.lang.Integer") ||
                    typeName.equals("int") ||
                    typeName.equals("java.lang.Long") ||
                    typeName.equals("long");
        }

        private boolean isEnumType(TypeMirror type) {
            if (!(type instanceof DeclaredType)) return false;
            TypeElement element = (TypeElement) ((DeclaredType) type).asElement();
            return element.getKind() == ElementKind.ENUM;
        }

        private String generateTypeConversion(String getter, TypeMirror sourceType, TypeMirror targetType) {
            // Enum conversion
            if (isEnumType(sourceType) && isEnumType(targetType)) {
                String targetTypeName = extractFullTypeName(targetType);
                return targetTypeName + ".valueOf(" + getter + ".name())";
            }

            // Number to complex type - DON'T auto-convert, treat as incompatible
            if (isNumberType(sourceType) && isComplexType(targetType)) {
                processor.logWarn("Cannot auto-convert from " + sourceType + " to " + targetType +
                        ". Skipping field mapping. Consider using @CrudXField(ignore=true) or providing custom mapper.");
                return null; // Signal to skip this field
            }

            // Complex type to number - DON'T auto-convert, treat as incompatible
            if (isComplexType(sourceType) && isNumberType(targetType)) {
                processor.logWarn("Cannot auto-convert from " + sourceType + " to " + targetType +
                        ". Skipping field mapping. Consider using @CrudXField(ignore=true) or providing custom mapper.");
                return null; // Signal to skip this field
            }

            return getter;
        }

        private String extractFullTypeName(TypeMirror type) {
            if (type instanceof DeclaredType) {
                TypeElement element = (TypeElement) ((DeclaredType) type).asElement();
                return getClassReference(element.getQualifiedName().toString());
            }
            return type.toString();
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

        /**
         * Write smart toEntity method with auto field resolution
         */
        private void writeToEntityMethod(TypeElement dtoElement) {
            String dtoName = dtoElement.getSimpleName().toString();
            String entityName = context.entitySimpleName;

            out.println("    public " + entityName + " toEntityFrom" + dtoName +
                    "(" + dtoName + " dto) {");
            out.println("        if (dto == null) return null;");
            out.println("        " + entityName + " entity = new " + entityName + "();");

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
                String entityFieldName = processor.getEntityFieldName(dtoField);

                // 🔥 USE SMART PATH RESOLUTION
                FieldPath fieldPath = processor.findFieldPath(context.entityElement, entityFieldName, 5);

                if (fieldPath == null) {
                    processor.logWarn("Field '" + dtoFieldName + "' (source: '" + entityFieldName +
                            "') not found in " + context.entitySimpleName);
                    continue;
                }

                TypeMirror dtoFieldType = dtoField.asType();
                TypeMirror entityFieldType = fieldPath.field.asType();

                String getter = "dto.get" + capitalize(dtoFieldName) + "()";
                String setter = generatePathSetter("entity", fieldPath);

                out.println("        if (" + getter + " != null) {");

                if (processor.needsNestedMapper(dtoFieldType, entityFieldType)) {
                    boolean isCollection = processor.isCollection(dtoFieldType);
                    String dtoTypeName = extractSimpleName(processor.extractTypeName(dtoFieldType));
                    String entityTypeName = extractSimpleName(processor.extractTypeName(entityFieldType));

                    if (isCollection) {
                        out.println("            " + setter + "(map" + dtoTypeName + "ListTo" +
                                entityTypeName + "List(" + getter + "));");
                    } else {
                        out.println("            " + setter + "(map" + dtoTypeName + "To" +
                                entityTypeName + "(" + getter + "));");
                    }
                } else {
                    out.println("            " + setter + "(" + getter + ");");
                }

                out.println("        }");
            }

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
                String entityFieldName = processor.getEntityFieldName(dtoField);

                if (entityFieldName.equals("id")) {
                    continue;
                }

                VariableElement entityField = findFieldInType(context.entityElement, entityFieldName);
                if (entityField == null) continue;

                TypeMirror dtoFieldType = dtoField.asType();
                TypeMirror entityFieldType = entityField.asType();

                String getter = "dto.get" + capitalize(dtoFieldName) + "()";
                String setter = "entity.set" + capitalize(entityFieldName);

                out.println("        if (" + getter + " != null) {");

                if (processor.needsNestedMapper(dtoFieldType, entityFieldType)) {
                    boolean isCollection = processor.isCollection(dtoFieldType);
                    String dtoTypeName = extractSimpleName(processor.extractTypeName(dtoFieldType));
                    String entityTypeName = extractSimpleName(processor.extractTypeName(entityFieldType));

                    if (isCollection) {
                        out.println("            " + setter + "(map" + dtoTypeName + "ListTo" +
                                entityTypeName + "List(" + getter + "));");
                    } else {
                        out.println("            " + setter + "(map" + dtoTypeName + "To" +
                                entityTypeName + "(" + getter + "));");
                    }
                } else {
                    out.println("            " + setter + "(" + getter + ");");
                }

                out.println("        }");
            }

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

        /**
         * Write smart toResponse method with FULL path resolution
         */
        private void writeToResponseMethod(TypeElement dtoElement) {
            String dtoName = dtoElement.getSimpleName().toString();
            String entityName = context.entitySimpleName;

            out.println("    public " + dtoName + " to" + dtoName +
                    "(" + entityName + " entity) {");
            out.println("        if (entity == null) return null;");
            out.println("        " + dtoName + " dto = new " + dtoName + "();");

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
                String entityFieldName = processor.getEntityFieldName(dtoField);

                // 🔥 USE SMART PATH RESOLUTION
                FieldPath fieldPath = processor.findFieldPath(context.entityElement, entityFieldName, 5);

                if (fieldPath == null) {
                    processor.logWarn("Field '" + dtoFieldName + "' (source: '" + entityFieldName +
                            "') not found in " + context.entitySimpleName);
                    continue;
                }

                TypeMirror dtoFieldType = dtoField.asType();
                TypeMirror entityFieldType = fieldPath.field.asType();

                // Generate null-safe getter for nested paths
                String getter = generatePathGetter("entity", fieldPath);
                String setter = "dto.set" + capitalize(dtoFieldName);

                // Generate null-safe navigation
                if (fieldPath.isNested) {
                    String[] segments = fieldPath.fullPath.split("\\.");
                    StringBuilder nullCheck = new StringBuilder();

                    for (int i = 0; i < segments.length; i++) {
                        if (i > 0) nullCheck.append(" && ");
                        nullCheck.append("entity");
                        for (int j = 0; j <= i; j++) {
                            nullCheck.append(".get").append(capitalize(segments[j])).append("()");
                        }
                        nullCheck.append(" != null");
                    }

                    out.println("        if (" + nullCheck + ") {");
                } else {
                    out.println("        if (" + getter + " != null) {");
                }

                if (processor.needsNestedMapper(entityFieldType, dtoFieldType)) {
                    boolean isCollection = processor.isCollection(entityFieldType);
                    String dtoTypeName = extractSimpleName(processor.extractTypeName(dtoFieldType));
                    String entityTypeName = extractSimpleName(processor.extractTypeName(entityFieldType));

                    if (isCollection) {
                        out.println("            " + setter + "(map" + entityTypeName + "ListTo" +
                                dtoTypeName + "List(" + getter + "));");
                    } else {
                        out.println("            " + setter + "(map" + entityTypeName + "To" +
                                dtoTypeName + "(" + getter + "));");
                    }
                } else {
                    // Type conversion if needed
                    if (needsTypeConversion(entityFieldType, dtoFieldType)) {
                        String conversion = generateTypeConversion(getter, entityFieldType, dtoFieldType);
                        if (conversion != null) {
                            out.println("            " + setter + "(" + conversion + ");");
                        }
                    } else {
                        out.println("            " + setter + "(" + getter + ");");
                    }
                }

                out.println("        }");
            }

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


        /**
         * Generate null-safe getter for nested paths
         */
        private String generatePathGetter(String baseVar, FieldPath path) {
            if (!path.isNested) {
                return baseVar + ".get" + capitalize(path.fullPath) + "()";
            }

            // For nested paths: entity.getCustomer().getName()
            String[] segments = path.fullPath.split("\\.");
            StringBuilder getter = new StringBuilder(baseVar);

            for (String segment : segments) {
                getter.append(".get").append(capitalize(segment)).append("()");
            }

            return getter.toString();
        }

        /**
         * Generate null-safe setter for nested paths
         */
        private String generatePathSetter(String baseVar, FieldPath path) {
            if (!path.isNested) {
                return baseVar + ".set" + capitalize(path.fullPath);
            }

            // For nested paths, we need to ensure parent objects exist
            // Generate: if (entity.getCustomer() == null) entity.setCustomer(new Customer());
            //           entity.getCustomer().setName(...)
            String[] segments = path.fullPath.split("\\.");
            StringBuilder result = new StringBuilder();

            // Create intermediate objects if needed
            for (int i = 0; i < segments.length - 1; i++) {
                StringBuilder checkPath = new StringBuilder(baseVar);
                for (int j = 0; j <= i; j++) {
                    checkPath.append(".get").append(capitalize(segments[j])).append("()");
                }

                result.append("if (").append(checkPath).append(" == null) ");

                StringBuilder setterPath = new StringBuilder(baseVar);
                for (int j = 0; j < i; j++) {
                    setterPath.append(".get").append(capitalize(segments[j])).append("()");
                }
                setterPath.append(".set").append(capitalize(segments[i]));

                // You'll need to determine the type - for now use Object
                result.append(setterPath).append("(new ").append(capitalize(segments[i]))
                        .append("()); ");
            }

            // Final setter path
            StringBuilder setterPath = new StringBuilder(baseVar);
            for (int i = 0; i < segments.length - 1; i++) {
                setterPath.append(".get").append(capitalize(segments[i])).append("()");
            }
            setterPath.append(".set").append(capitalize(segments[segments.length - 1]));

            return result.toString() + setterPath;
        }

        private TypeElement getTypeAtPath(String[] segments, int index) {
            // Navigate to find the type at this index
            // Implementation depends on your type system
            return null; // Simplified - you need to implement this
        }

        private String capitalize(String str) {
            if (str == null || str.isEmpty()) return str;
            return Character.toUpperCase(str.charAt(0)) + str.substring(1);
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