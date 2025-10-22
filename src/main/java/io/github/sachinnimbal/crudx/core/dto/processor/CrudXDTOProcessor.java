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
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.Elements;
import javax.lang.model.util.Types;
import javax.tools.Diagnostic;
import javax.tools.JavaFileObject;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.*;

/**
 * 🚀 Ultra-Fast CrudX DTO Processor - Zero Runtime Overhead
 * Fixed nested mapping generation with correct direction handling
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

    @Override
    public synchronized void init(ProcessingEnvironment processingEnv) {
        super.init(processingEnv);
        this.messager = processingEnv.getMessager();
        this.filer = processingEnv.getFiler();
        this.elementUtils = processingEnv.getElementUtils();
        this.typeUtils = processingEnv.getTypeUtils();

        logInfo("🚀 CrudX DTO Processor - Production Mode");
    }

    @Override
    public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {
        if (roundEnv.processingOver()) {
            if (!entityMappers.isEmpty()) {
                generateAllMappers();
            }
            return false;
        }

        collectRequestDTOs(roundEnv);
        collectResponseDTOs(roundEnv);
        analyzeNestedDTOs();

        return false;
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

    /**
     * FIXED: Scan for nested fields with proper entity context tracking
     */
    private void scanForNestedFields(TypeElement dtoElement, EntityMapperContext context, boolean isRequest, Set<String> visited) {
        String dtoFqn = dtoElement.getQualifiedName().toString();
        if (visited.contains(dtoFqn)) return;
        visited.add(dtoFqn);

        for (Element element : dtoElement.getEnclosedElements()) {
            if (element.getKind() != ElementKind.FIELD) continue;

            VariableElement dtoField = (VariableElement) element;
            CrudXNested nested = dtoField.getAnnotation(CrudXNested.class);

            if (nested != null) {
                String fieldName = dtoField.getSimpleName().toString();

                // Find matching entity field starting from the main entity
                VariableElement entityField = findFieldInEntityHierarchy(context.entityElement, fieldName);

                if (entityField != null) {
                    registerNestedMappingPair(dtoField, entityField, context, isRequest);
                } else {
                    logWarn("No matching entity field for nested DTO field: " + fieldName);
                }

                // Recursively scan the nested DTO type
                String nestedDtoTypeFqn = extractTypeName(dtoField.asType());
                if (nestedDtoTypeFqn != null) {
                    TypeElement nestedDtoElement = elementUtils.getTypeElement(nestedDtoTypeFqn);
                    if (nestedDtoElement != null) {
                        scanForNestedFields(nestedDtoElement, context, isRequest, visited);
                    }
                }
            }
        }
    }

    /**
     * NEW: Register a nested mapping between DTO field and Entity field
     */
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

    /**
     * NEW: Find a field in the entity hierarchy (including inner classes)
     */
    private VariableElement findFieldInEntityHierarchy(TypeElement entityElement, String fieldName) {
        // First check the entity itself
        VariableElement field = findField(entityElement, fieldName);
        if (field != null) {
            return field;
        }

        // Then check all inner classes recursively
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

    private String extractTypeName(TypeMirror type) {
        if (isCollection(type) && type instanceof DeclaredType) {
            DeclaredType declaredType = (DeclaredType) type;
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

    private boolean isCollection(TypeMirror type) {
        if (!(type instanceof DeclaredType)) return false;
        TypeElement element = (TypeElement) ((DeclaredType) type).asElement();
        String name = element.getQualifiedName().toString();
        return name.equals("java.util.List") || name.equals("java.util.Set") || name.equals("java.util.Collection");
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

    private void generateAllMappers() {
        int successCount = 0;

        for (EntityMapperContext context : entityMappers.values()) {
            try {
                generateMapperClass(context);
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
                    writer, context, generatedPackage, mapperClassName, elementUtils, typeUtils
            );
            mapperWriter.write();
        }

        logInfo("✓ Generated: " + mapperClassName + " (" + context.nestedMappings.size() + " nested mappers)");
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

    private void logWarn(String message) {
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

    private static class MapperWriter {
        private final PrintWriter out;
        private final EntityMapperContext context;
        private final String packageName;
        private final String className;
        private final Elements elementUtils;
        private final Types typeUtils;

        MapperWriter(PrintWriter out, EntityMapperContext context, String packageName,
                     String className, Elements elementUtils, Types typeUtils) {
            this.out = out;
            this.context = context;
            this.packageName = packageName;
            this.className = className;
            this.elementUtils = elementUtils;
            this.typeUtils = typeUtils;
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

                if (!isInnerClassOf(dtoFqn, context.requestDTOs.keySet()) &&
                        !isInnerClassOf(dtoFqn, context.responseDTOs.keySet())) {
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
                    return true;
                }
            }
            return false;
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
                    copyFieldsSimple(dtoElement, entityElement, "dto", "entity");
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
                    copyFieldsSimple(entityElement, dtoElement, "entity", "dto");
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

        private void copyFieldsSimple(TypeElement sourceElement, TypeElement targetElement,
                                      String srcVar, String tgtVar) {
            for (Element element : sourceElement.getEnclosedElements()) {
                if (element.getKind() != ElementKind.FIELD) continue;

                VariableElement sourceField = (VariableElement) element;
                if (sourceField.getModifiers().contains(Modifier.STATIC) ||
                        sourceField.getModifiers().contains(Modifier.FINAL)) {
                    continue;
                }

                String fieldName = sourceField.getSimpleName().toString();
                VariableElement targetField = findFieldInType(targetElement, fieldName);
                if (targetField == null) continue;

                CrudXNested nested = sourceField.getAnnotation(CrudXNested.class);
                boolean isNested = nested != null || targetField.getAnnotation(CrudXNested.class) != null;

                String getter = srcVar + ".get" + capitalize(fieldName) + "()";
                String setter = tgtVar + ".set" + capitalize(fieldName);

                out.println("        if (" + getter + " != null) {");

                if (isNested) {
                    String sourceType = extractTypeName(sourceField.asType());
                    String targetType = extractTypeName(targetField.asType());

                    if (sourceType != null && targetType != null) {
                        boolean isCollection = isCollectionType(sourceField.asType());
                        String sourceSimple = extractSimpleName(sourceType);
                        String targetSimple = extractSimpleName(targetType);

                        if (isCollection) {
                            out.println("            " + setter + "(map" + sourceSimple + "ListTo" + targetSimple + "List(" + getter + "));");
                        } else {
                            out.println("            " + setter + "(map" + sourceSimple + "To" + targetSimple + "(" + getter + "));");
                        }
                    } else {
                        out.println("            " + setter + "(" + getter + ");");
                    }
                } else {
                    // Handle type conversion for enums and other types
                    TypeMirror sourceType = sourceField.asType();
                    TypeMirror targetType = targetField.asType();

                    if (needsTypeConversion(sourceType, targetType)) {
                        String conversion = generateTypeConversion(getter, sourceType, targetType);
                        out.println("            " + setter + "(" + conversion + ");");
                    } else {
                        out.println("            " + setter + "(" + getter + ");");
                    }
                }

                out.println("        }");
            }
        }

        private boolean needsTypeConversion(TypeMirror sourceType, TypeMirror targetType) {
            if (typeUtils.isSameType(sourceType, targetType)) {
                return false;
            }

            // Check if both are enums with the same name
            if (isEnumType(sourceType) && isEnumType(targetType)) {
                String sourceName = extractSimpleName(sourceType.toString());
                String targetName = extractSimpleName(targetType.toString());
                return sourceName.equals(targetName);
            }

            return false;
        }

        private boolean isEnumType(TypeMirror type) {
            if (!(type instanceof DeclaredType)) return false;
            TypeElement element = (TypeElement) ((DeclaredType) type).asElement();
            return element.getKind() == ElementKind.ENUM;
        }

        private String generateTypeConversion(String getter, TypeMirror sourceType, TypeMirror targetType) {
            // Handle enum to enum conversion (same enum name but different packages/contexts)
            if (isEnumType(sourceType) && isEnumType(targetType)) {
                String targetTypeName = extractFullTypeName(targetType);
                return targetTypeName + ".valueOf(" + getter + ".name())";
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

        private VariableElement findFieldInType(TypeElement typeElement, String fieldName) {
            for (Element element : typeElement.getEnclosedElements()) {
                if (element.getKind() == ElementKind.FIELD &&
                        element.getSimpleName().toString().equals(fieldName)) {
                    return (VariableElement) element;
                }
            }
            return null;
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

            List<FieldMapping> mappings = analyzeFieldMappings(dtoElement, true);
            for (FieldMapping mapping : mappings) {
                if (mapping.isValid()) {
                    writeFieldMapping(mapping, "dto", "entity");
                }
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

            List<FieldMapping> mappings = analyzeFieldMappings(dtoElement, true);
            for (FieldMapping mapping : mappings) {
                if (mapping.isValid() && !mapping.isIdField) {
                    writeFieldMapping(mapping, "dto", "entity");
                }
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

        private void writeToResponseMethod(TypeElement dtoElement) {
            String dtoName = dtoElement.getSimpleName().toString();
            String entityName = context.entitySimpleName;

            out.println("    public " + dtoName + " to" + dtoName +
                    "(" + entityName + " entity) {");
            out.println("        if (entity == null) return null;");
            out.println("        " + dtoName + " dto = new " + dtoName + "();");

            List<FieldMapping> mappings = analyzeFieldMappings(dtoElement, false);
            for (FieldMapping mapping : mappings) {
                if (mapping.isValid()) {
                    writeFieldMapping(mapping, "entity", "dto");
                }
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

        private void writeFieldMapping(FieldMapping mapping, String srcVar, String tgtVar) {
            if (mapping.ignore) return;

            String getter = srcVar + ".get" + capitalize(mapping.sourceFieldName) + "()";
            String setter = tgtVar + ".set" + capitalize(mapping.targetFieldName);

            out.println("        if (" + getter + " != null) {");

            String value = getter;

            if (mapping.isNested) {
                String sourceType = extractSimpleName(mapping.sourceType);
                String targetType = extractSimpleName(mapping.targetType);

                if (mapping.isCollection) {
                    value = "map" + sourceType + "ListTo" + targetType + "List(" + getter + ")";
                } else {
                    value = "map" + sourceType + "To" + targetType + "(" + getter + ")";
                }
            } else if (mapping.needsConversion) {
                value = mapping.conversionCode.replace("VALUE", getter);
            }

            out.println("            " + setter + "(" + value + ");");
            out.println("        }");
        }

        private List<FieldMapping> analyzeFieldMappings(TypeElement dtoElement, boolean isRequest) {
            List<FieldMapping> mappings = new ArrayList<>();

            for (Element element : dtoElement.getEnclosedElements()) {
                if (element.getKind() != ElementKind.FIELD) continue;

                VariableElement dtoField = (VariableElement) element;
                CrudXField fieldAnnotation = dtoField.getAnnotation(CrudXField.class);
                CrudXNested nestedAnnotation = dtoField.getAnnotation(CrudXNested.class);

                if (fieldAnnotation != null && fieldAnnotation.ignore()) continue;

                FieldMapping mapping = new FieldMapping();
                mapping.sourceFieldName = dtoField.getSimpleName().toString();
                mapping.targetFieldName = (fieldAnnotation != null && !fieldAnnotation.source().isEmpty())
                        ? fieldAnnotation.source() : mapping.sourceFieldName;
                mapping.isNested = (nestedAnnotation != null);

                VariableElement entityField = findField(context.entityElement, mapping.targetFieldName);
                if (entityField != null) {
                    TypeMirror dtoFieldType = dtoField.asType();
                    TypeMirror entityFieldType = entityField.asType();

                    mapping.isCollection = isCollectionType(dtoFieldType) || isCollectionType(entityFieldType);

                    if (mapping.isNested) {
                        if (isRequest) {
                            mapping.sourceType = extractTypeName(dtoFieldType);
                            mapping.targetType = extractTypeName(entityFieldType);
                        } else {
                            mapping.sourceType = extractTypeName(entityFieldType);
                            mapping.targetType = extractTypeName(dtoFieldType);
                        }
                    } else {
                        mapping.sourceType = dtoFieldType.toString();
                        mapping.targetType = entityFieldType.toString();

                        // Check if type conversion is needed
                        if (!typeUtils.isSameType(dtoFieldType, entityFieldType)) {
                            if (isEnumType(dtoFieldType) && isEnumType(entityFieldType)) {
                                String sourceName = extractSimpleName(dtoFieldType.toString());
                                String targetName = extractSimpleName(entityFieldType.toString());
                                if (sourceName.equals(targetName)) {
                                    mapping.needsConversion = true;
                                    String targetTypeFull = extractFullTypeName(entityFieldType);
                                    mapping.conversionCode = targetTypeFull + ".valueOf(VALUE.name())";
                                }
                            }
                        }
                    }

                    mapping.isIdField = mapping.targetFieldName.equals("id");
                } else {
                    mapping.sourceType = "UNKNOWN";
                    mapping.targetType = "UNKNOWN";
                }

                if (fieldAnnotation != null) {
                    mapping.ignore = fieldAnnotation.ignore();
                    mapping.required = fieldAnnotation.required();
                }

                mappings.add(mapping);
            }

            return mappings;
        }

        private String extractTypeName(TypeMirror type) {
            if (isCollectionType(type) && type instanceof DeclaredType) {
                DeclaredType declaredType = (DeclaredType) type;
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

        private boolean isCollectionType(TypeMirror type) {
            if (!(type instanceof DeclaredType)) return false;
            TypeElement element = (TypeElement) ((DeclaredType) type).asElement();
            String name = element.getQualifiedName().toString();
            return name.equals("java.util.List") || name.equals("java.util.Set") || name.equals("java.util.Collection");
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

        private String extractSimpleName(String fqn) {
            if (fqn == null) return "Unknown";
            int lastDot = fqn.lastIndexOf('.');
            String name = lastDot > 0 ? fqn.substring(lastDot + 1) : fqn;
            int genericStart = name.indexOf('<');
            return genericStart > 0 ? name.substring(0, genericStart) : name;
        }
    }

    private static class FieldMapping {
        String sourceFieldName;
        String targetFieldName;
        String sourceType;
        String targetType;
        boolean isIdField;
        boolean isNested;
        boolean isCollection;
        boolean required;
        boolean ignore;
        boolean needsConversion;
        String conversionCode;

        boolean isValid() {
            return sourceType != null &&
                    targetType != null &&
                    !sourceType.equals("UNKNOWN") &&
                    !targetType.equals("UNKNOWN");
        }
    }
}