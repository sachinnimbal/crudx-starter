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
 * 🚀 Production-Ready CrudX DTO Processor
 *
 * Automatically generates type-safe mappers with full nested object support
 *
 * @author Sachin Nimbal
 * @since 1.0.2
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

    /**
     * Analyze all DTOs to find @CrudXNested annotations
     */
    private void analyzeNestedDTOs() {
        for (EntityMapperContext context : entityMappers.values()) {
            // Analyze request DTOs
            for (TypeElement requestDTO : context.requestDTOs.keySet()) {
                scanForNestedFields(requestDTO, context);
            }

            // Analyze response DTOs
            for (TypeElement responseDTO : context.responseDTOs.keySet()) {
                scanForNestedFields(responseDTO, context);
            }
        }
    }

    /**
     * Scan DTO for @CrudXNested fields and register mappings
     */
    private void scanForNestedFields(TypeElement dtoElement, EntityMapperContext context) {
        for (Element element : dtoElement.getEnclosedElements()) {
            if (element.getKind() != ElementKind.FIELD) continue;

            VariableElement field = (VariableElement) element;
            CrudXNested nested = field.getAnnotation(CrudXNested.class);

            if (nested != null) {
                registerNestedMapping(field, context);
            }
        }
    }

    /**
     * Register nested DTO ↔ Entity mapping
     */
    private void registerNestedMapping(VariableElement dtoField, EntityMapperContext context) {
        try {
            String fieldName = dtoField.getSimpleName().toString();
            TypeMirror dtoFieldType = dtoField.asType();

            // Find matching entity field
            VariableElement entityField = findField(context.entityElement, fieldName);
            if (entityField == null) {
                logWarn("No matching entity field for: " + fieldName);
                return;
            }

            TypeMirror entityFieldType = entityField.asType();

            // Extract types (handling collections)
            String dtoTypeFqn = extractTypeName(dtoFieldType);
            String entityTypeFqn = extractTypeName(entityFieldType);

            if (dtoTypeFqn != null && entityTypeFqn != null) {
                context.nestedMappings.put(dtoTypeFqn, entityTypeFqn);
                logInfo("✓ Nested: " + extractSimpleName(dtoTypeFqn) + " → " + extractSimpleName(entityTypeFqn));
            }
        } catch (Exception e) {
            logWarn("Error mapping nested field " + dtoField.getSimpleName() + ": " + e.getMessage());
        }
    }

    /**
     * Extract fully qualified type name (handles collections)
     */
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
        int lastDot = fqn.lastIndexOf('.');
        String name = lastDot > 0 ? fqn.substring(lastDot + 1) : fqn;
        int genericStart = name.indexOf('<');
        return genericStart > 0 ? name.substring(0, genericStart) : name;
    }

    // ==================== CONTEXT CLASSES ====================

    private static class EntityMapperContext {
        final TypeMirror entityType;
        final String entityFqn;
        final String entitySimpleName;
        final TypeElement entityElement;
        final Elements elementUtils;
        final Types typeUtils;
        final Map<TypeElement, CrudXRequest> requestDTOs = new LinkedHashMap<>();
        final Map<TypeElement, CrudXResponse> responseDTOs = new LinkedHashMap<>();
        final Map<String, String> nestedMappings = new LinkedHashMap<>();

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
            writeNestedMapperMethods();  // 🎯 CRITICAL: Generate nested mappers FIRST
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

            // Import entity (always needed)
            imports.add("import " + context.entityFqn + ";");

            // Import all DTO classes
            context.requestDTOs.keySet().forEach(dto ->
                    imports.add("import " + dto.getQualifiedName() + ";"));
            context.responseDTOs.keySet().forEach(dto ->
                    imports.add("import " + dto.getQualifiedName() + ";"));

            // 🎯 CRITICAL FIX: Import nested DTO and Entity classes
            for (Map.Entry<String, String> entry : context.nestedMappings.entrySet()) {
                String dtoFqn = entry.getKey();
                String entityFqn = entry.getValue();

                // Only import if not an inner class of already imported classes
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

        /**
         * Check if a class is an inner class of another
         */
        private boolean isInnerClassOf(String fqn, Set<TypeElement> parentClasses) {
            for (TypeElement parent : parentClasses) {
                String parentFqn = parent.getQualifiedName().toString();
                if (fqn.startsWith(parentFqn + ".") || fqn.startsWith(parentFqn + "$")) {
                    return true;
                }
            }
            return false;
        }

        /**
         * Check if a class is an inner class of an entity
         */
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

        /**
         * 🎯 CRITICAL: Generate nested object mapper methods
         */
        private void writeNestedMapperMethods() {
            if (context.nestedMappings.isEmpty()) {
                return;
            }

            out.println("    // ==================== NESTED OBJECT MAPPERS ====================");
            out.println();

            for (Map.Entry<String, String> entry : context.nestedMappings.entrySet()) {
                String dtoFqn = entry.getKey();
                String entityFqn = entry.getValue();

                // Use simple name for inner classes, otherwise use imported name
                String dtoSimpleName = getClassReference(dtoFqn);
                String entitySimpleName = getClassReference(entityFqn);

                // DTO → Entity
                out.println("    private " + entitySimpleName + " map" + extractSimpleName(dtoFqn) + "To" +
                        extractSimpleName(entityFqn) + "(" + dtoSimpleName + " dto) {");
                out.println("        if (dto == null) return null;");
                out.println("        " + entitySimpleName + " entity = new " + entitySimpleName + "();");

                TypeElement dtoElement = elementUtils.getTypeElement(dtoFqn);
                TypeElement entityElement = elementUtils.getTypeElement(entityFqn);

                if (dtoElement != null && entityElement != null) {
                    copyFields(dtoElement, entityElement, "dto", "entity");
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
                    copyFields(entityElement, dtoElement, "entity", "dto");
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
         * Get proper class reference for inner classes vs regular classes
         *
         * For inner classes: Uses simple name (e.g., "Address")
         * For regular classes: Uses simple name if imported, FQN otherwise
         */
        private String getClassReference(String fqn) {
            // Check if it's an inner class of the controller/entity being processed
            String entityPackage = elementUtils.getPackageOf(context.entityElement).getQualifiedName().toString();
            String entitySimpleName = context.entityElement.getSimpleName().toString();
            String entityFqn = context.entityElement.getQualifiedName().toString();

            // If it's an inner class of the controller (e.g., CustomerController.Address)
            if (fqn.startsWith(entityFqn + "$") || fqn.startsWith(entityFqn + ".")) {
                // Return just the inner class name (e.g., "Address")
                String innerClassName = fqn.substring(entityFqn.length() + 1);
                return innerClassName.replace("$", ".");
            }

            // Check if it's an inner class of any DTO we're processing
            for (TypeElement dto : context.requestDTOs.keySet()) {
                String dtoFqn = dto.getQualifiedName().toString();
                if (fqn.startsWith(dtoFqn + "$") || fqn.startsWith(dtoFqn + ".")) {
                    String innerClassName = fqn.substring(dtoFqn.length() + 1);
                    return innerClassName.replace("$", ".");
                }
            }

            for (TypeElement dto : context.responseDTOs.keySet()) {
                String dtoFqn = dto.getQualifiedName().toString();
                if (fqn.startsWith(dtoFqn + "$") || fqn.startsWith(dtoFqn + ".")) {
                    String innerClassName = fqn.substring(dtoFqn.length() + 1);
                    return innerClassName.replace("$", ".");
                }
            }

            // For non-inner classes, return simple name (will be imported)
            return extractSimpleName(fqn);
        }

        /**
         * Copy fields between source and target objects
         */
        private void copyFields(TypeElement sourceElement, TypeElement targetElement,
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

                String getter = srcVar + ".get" + capitalize(fieldName) + "()";
                String setter = tgtVar + ".set" + capitalize(fieldName);

                out.println("        if (" + getter + " != null) {");
                out.println("            " + setter + "(" + getter + ");");
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

        /**
         * 🎯 SMART: Write field mapping with nested object support
         */
        private void writeFieldMapping(FieldMapping mapping, String srcVar, String tgtVar) {
            if (mapping.ignore) return;

            String getter = srcVar + ".get" + capitalize(mapping.sourceFieldName) + "()";
            String setter = tgtVar + ".set" + capitalize(mapping.targetFieldName);

            out.println("        if (" + getter + " != null) {");

            String value = getter;

            // Handle nested objects
            if (mapping.isNested) {
                String dtoType = extractSimpleName(mapping.sourceType);
                String entityType = extractSimpleName(mapping.targetType);

                if (mapping.isCollection) {
                    if (mapping.isDtoToEntity) {
                        value = "map" + dtoType + "ListTo" + entityType + "List(" + getter + ")";
                    } else {
                        value = "map" + entityType + "ListTo" + dtoType + "List(" + getter + ")";
                    }
                } else {
                    if (mapping.isDtoToEntity) {
                        value = "map" + dtoType + "To" + entityType + "(" + getter + ")";
                    } else {
                        value = "map" + entityType + "To" + dtoType + "(" + getter + ")";
                    }
                }
            }

            out.println("            " + setter + "(" + value + ");");
            out.println("        }");
        }

        /**
         * Analyze field mappings
         */
        private List<FieldMapping> analyzeFieldMappings(TypeElement dtoElement, boolean isRequest) {
            List<FieldMapping> mappings = new ArrayList<>();

            for (Element element : dtoElement.getEnclosedElements()) {
                if (element.getKind() != ElementKind.FIELD) continue;

                VariableElement dtoField = (VariableElement) element;
                CrudXField fieldAnnotation = dtoField.getAnnotation(CrudXField.class);
                CrudXNested nestedAnnotation = dtoField.getAnnotation(CrudXNested.class);

                if (fieldAnnotation != null && fieldAnnotation.ignore()) continue;

                FieldMapping mapping = new FieldMapping();
                mapping.targetFieldName = dtoField.getSimpleName().toString();
                mapping.sourceFieldName = (fieldAnnotation != null && !fieldAnnotation.source().isEmpty())
                        ? fieldAnnotation.source() : mapping.targetFieldName;
                mapping.isNested = (nestedAnnotation != null);
                mapping.isDtoToEntity = isRequest;

                VariableElement entityField = findField(context.entityElement, mapping.sourceFieldName);
                if (entityField != null) {
                    TypeMirror dtoFieldType = dtoField.asType();
                    TypeMirror entityFieldType = entityField.asType();

                    mapping.isCollection = isCollectionType(dtoFieldType) || isCollectionType(entityFieldType);

                    if (mapping.isNested) {
                        mapping.sourceType = extractTypeName(isRequest ? dtoFieldType : entityFieldType);
                        mapping.targetType = extractTypeName(isRequest ? entityFieldType : dtoFieldType);
                    } else {
                        mapping.sourceType = entityFieldType.toString();
                        mapping.targetType = dtoFieldType.toString();
                    }

                    mapping.isIdField = mapping.sourceFieldName.equals("id");
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
        boolean isDtoToEntity;
        boolean required;
        boolean ignore;

        boolean isValid() {
            return sourceType != null &&
                    targetType != null &&
                    !sourceType.equals("UNKNOWN") &&
                    !targetType.equals("UNKNOWN");
        }
    }
}