package io.github.sachinnimbal.crudx.dto.generator;

import io.github.sachinnimbal.crudx.core.annotations.dto.CrudXCollection;
import io.github.sachinnimbal.crudx.core.annotations.dto.CrudXField;
import io.github.sachinnimbal.crudx.core.annotations.dto.CrudXNested;
import io.github.sachinnimbal.crudx.core.enums.Direction;
import io.github.sachinnimbal.crudx.core.enums.OperationType;
import io.github.sachinnimbal.crudx.dto.mapper.GeneratedMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.lang.reflect.Field;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;

/**
 * Optimized mapper generator that creates a single mapper class per Entity-DTO pair
 * containing all operation methods (GET_BY_ID, GET_ALL, GET_PAGED, CREATE, UPDATE, etc.)
 */
@Slf4j
@Component
public class DtoMapperGenerator {

    // Changed to use target/generated-sources for better IDE integration
    private static final String GENERATED_PACKAGE = "io.github.sachinnimbal.crudx.generated.mappers";
    private final Path outputDirectory;
    private final JavaCompiler compiler;
    private final Map<String, GeneratedMapper<?, ?>> generatedMappers = new HashMap<>();

    public DtoMapperGenerator() {
        this.compiler = ToolProvider.getSystemJavaCompiler();
        this.outputDirectory = createOutputDirectory();
    }

    /**
     * Generate a single unified mapper for Entity-DTO pair
     * This mapper handles ALL operations (no separate files per operation)
     */
    @SuppressWarnings("unchecked")
    public <E, D> GeneratedMapper<E, D> generateMapper(
            Class<D> dtoClass,
            Class<E> entityClass,
            OperationType operation,
            Direction direction) {

        // Use simplified key (no operation/direction - one mapper per entity-dto pair)
        String mapperKey = getSimplifiedMapperKey(entityClass, dtoClass);

        // Return cached if exists
        if (generatedMappers.containsKey(mapperKey)) {
            return (GeneratedMapper<E, D>) generatedMappers.get(mapperKey);
        }

        try {
            log.info("🔧 Generating unified mapper: {} <-> {}",
                    entityClass.getSimpleName(),
                    dtoClass.getSimpleName());

            // Step 1: Analyze fields
            MapperAnalysis analysis = analyzeFields(dtoClass, entityClass);

            // Step 2: Generate Java source code (single class for all operations)
            String sourceCode = generateUnifiedSourceCode(dtoClass, entityClass, analysis);

            // Step 3: Compile source code
            String className = getUnifiedClassName(dtoClass, entityClass);
            Class<?> mapperClass = compileAndLoad(className, sourceCode);

            // Step 4: Instantiate mapper
            GeneratedMapper<E, D> mapper = (GeneratedMapper<E, D>) mapperClass
                    .getDeclaredConstructor()
                    .newInstance();

            generatedMappers.put(mapperKey, mapper);

            log.info("✓ Generated unified mapper: {}", className);

            return mapper;

        } catch (Exception e) {
            log.error("Failed to generate mapper for {} <-> {}",
                    dtoClass.getSimpleName(), entityClass.getSimpleName(), e);
            return null;
        }
    }

    /**
     * Analyze field mappings between DTO and Entity
     */
    private MapperAnalysis analyzeFields(Class<?> dtoClass, Class<?> entityClass) {
        MapperAnalysis analysis = new MapperAnalysis();

        for (Field dtoField : getAllFields(dtoClass)) {
            CrudXField fieldAnnotation = dtoField.getAnnotation(CrudXField.class);

            if (fieldAnnotation != null && fieldAnnotation.ignore()) {
                analysis.ignoredFields.add(dtoField.getName());
                continue;
            }

            String entityFieldName = fieldAnnotation != null && !fieldAnnotation.value().isEmpty()
                    ? fieldAnnotation.value()
                    : dtoField.getName();

            try {
                Field entityField = getFieldFromClass(entityClass, entityFieldName);

                FieldPair pair = new FieldPair();
                pair.dtoField = dtoField;
                pair.entityField = entityField;
                pair.requiresConversion = !dtoField.getType().equals(entityField.getType());

                // Check for nested mapping
                if (dtoField.isAnnotationPresent(CrudXNested.class)) {
                    pair.nested = true;
                    pair.nestedDtoClass = dtoField.getAnnotation(CrudXNested.class).dto();
                }

                // Check for collection
                if (dtoField.isAnnotationPresent(CrudXCollection.class)) {
                    pair.collection = true;
                    pair.elementDtoClass = dtoField.getAnnotation(CrudXCollection.class).elementDto();
                }

                analysis.fieldPairs.add(pair);

            } catch (NoSuchFieldException e) {
                log.debug("Field {} not found in entity {}", entityFieldName, entityClass.getSimpleName());
            }
        }

        return analysis;
    }

    /**
     * Generate unified mapper source code with all methods in one class
     */
    private String generateUnifiedSourceCode(
            Class<?> dtoClass,
            Class<?> entityClass,
            MapperAnalysis analysis) {

        StringBuilder code = new StringBuilder();
        String className = getUnifiedClassName(dtoClass, entityClass);
        String simpleClassName = className.substring(className.lastIndexOf('.') + 1);

        // Package and imports
        code.append("package ").append(GENERATED_PACKAGE).append(";\n\n");
        code.append("import io.github.sachinnimbal.crudx.dto.mapper.GeneratedMapper;\n");
        code.append("import io.github.sachinnimbal.crudx.dto.metadata.MapperMetadata;\n");
        code.append("import ").append(entityClass.getName()).append(";\n");
        code.append("import ").append(dtoClass.getName()).append(";\n");
        code.append("import java.util.*;\n");
        code.append("import java.time.*;\n\n");

        // Class documentation
        code.append("/**\n");
        code.append(" * Auto-generated unified mapper by CrudX Framework\n");
        code.append(" * Handles all operations: CREATE, UPDATE, GET_BY_ID, GET_ALL, GET_PAGED\n");
        code.append(" * \n");
        code.append(" * Entity: ").append(entityClass.getSimpleName()).append("\n");
        code.append(" * DTO: ").append(dtoClass.getSimpleName()).append("\n");
        code.append(" * Generated: ").append(java.time.LocalDateTime.now()).append("\n");
        code.append(" * \n");
        code.append(" * DO NOT MODIFY - This class is regenerated on application startup\n");
        code.append(" */\n");
        code.append("public class ").append(simpleClassName);
        code.append(" implements GeneratedMapper<");
        code.append(entityClass.getSimpleName()).append(", ");
        code.append(dtoClass.getSimpleName()).append("> {\n\n");

        // toEntity method (for CREATE/UPDATE operations)
        code.append(generateToEntityMethod(dtoClass, entityClass, analysis));

        // toDto method (for GET operations)
        code.append(generateToDtoMethod(dtoClass, entityClass, analysis));

        // updateEntity method (for UPDATE operations)
        code.append(generateUpdateEntityMethod(dtoClass, entityClass, analysis));

        // Batch conversion methods
        code.append(generateBatchMethods(dtoClass, entityClass));

        // Metadata methods
        code.append(generateMetadataMethods(dtoClass, entityClass, className));

        code.append("}\n");

        return code.toString();
    }

    private String generateToEntityMethod(Class<?> dtoClass, Class<?> entityClass, MapperAnalysis analysis) {
        StringBuilder code = new StringBuilder();

        code.append("    /**\n");
        code.append("     * Convert DTO to Entity (used in CREATE/UPDATE operations)\n");
        code.append("     */\n");
        code.append("    @Override\n");
        code.append("    public ").append(entityClass.getSimpleName());
        code.append(" toEntity(").append(dtoClass.getSimpleName()).append(" dto) {\n");
        code.append("        if (dto == null) return null;\n");
        code.append("        ").append(entityClass.getSimpleName()).append(" entity = new ");
        code.append(entityClass.getSimpleName()).append("();\n\n");

        for (FieldPair pair : analysis.fieldPairs) {
            String dtoGetter = "dto.get" + capitalize(pair.dtoField.getName()) + "()";
            String entitySetter = "entity.set" + capitalize(pair.entityField.getName());

            if (pair.nested) {
                code.append("        // TODO: Nested mapping for: ").append(pair.dtoField.getName()).append("\n");
            } else if (pair.collection) {
                code.append("        // TODO: Collection mapping for: ").append(pair.dtoField.getName()).append("\n");
            } else {
                code.append("        ").append(entitySetter).append("(").append(dtoGetter).append(");\n");
            }
        }

        code.append("        return entity;\n");
        code.append("    }\n\n");

        return code.toString();
    }

    private String generateToDtoMethod(Class<?> dtoClass, Class<?> entityClass, MapperAnalysis analysis) {
        StringBuilder code = new StringBuilder();

        code.append("    /**\n");
        code.append("     * Convert Entity to DTO (used in GET operations)\n");
        code.append("     */\n");
        code.append("    @Override\n");
        code.append("    public ").append(dtoClass.getSimpleName());
        code.append(" toDto(").append(entityClass.getSimpleName()).append(" entity) {\n");
        code.append("        if (entity == null) return null;\n");
        code.append("        ").append(dtoClass.getSimpleName()).append(" dto = new ");
        code.append(dtoClass.getSimpleName()).append("();\n\n");

        for (FieldPair pair : analysis.fieldPairs) {
            String entityGetter = "entity.get" + capitalize(pair.entityField.getName()) + "()";
            String dtoSetter = "dto.set" + capitalize(pair.dtoField.getName());

            code.append("        ").append(dtoSetter).append("(").append(entityGetter).append(");\n");
        }

        code.append("        return dto;\n");
        code.append("    }\n\n");

        return code.toString();
    }

    private String generateUpdateEntityMethod(Class<?> dtoClass, Class<?> entityClass, MapperAnalysis analysis) {
        StringBuilder code = new StringBuilder();

        code.append("    /**\n");
        code.append("     * Update existing entity from DTO (partial update)\n");
        code.append("     */\n");
        code.append("    @Override\n");
        code.append("    public void updateEntity(").append(dtoClass.getSimpleName());
        code.append(" dto, ").append(entityClass.getSimpleName()).append(" entity) {\n");
        code.append("        if (dto == null || entity == null) return;\n\n");

        for (FieldPair pair : analysis.fieldPairs) {
            String dtoGetter = "dto.get" + capitalize(pair.dtoField.getName()) + "()";
            String entitySetter = "entity.set" + capitalize(pair.entityField.getName());

            code.append("        if (").append(dtoGetter).append(" != null) {\n");
            code.append("            ").append(entitySetter).append("(").append(dtoGetter).append(");\n");
            code.append("        }\n");
        }

        code.append("    }\n\n");

        return code.toString();
    }

    private String generateBatchMethods(Class<?> dtoClass, Class<?> entityClass) {
        StringBuilder code = new StringBuilder();

        // Batch toDto
        code.append("    /**\n");
        code.append("     * Convert list of entities to DTOs (batch operation)\n");
        code.append("     */\n");
        code.append("    public List<").append(dtoClass.getSimpleName()).append("> toDtos(List<");
        code.append(entityClass.getSimpleName()).append("> entities) {\n");
        code.append("        if (entities == null || entities.isEmpty()) return new ArrayList<>();\n");
        code.append("        List<").append(dtoClass.getSimpleName()).append("> dtos = new ArrayList<>(entities.size());\n");
        code.append("        for (").append(entityClass.getSimpleName()).append(" entity : entities) {\n");
        code.append("            dtos.add(toDto(entity));\n");
        code.append("        }\n");
        code.append("        return dtos;\n");
        code.append("    }\n\n");

        // Batch toEntity
        code.append("    /**\n");
        code.append("     * Convert list of DTOs to entities (batch operation)\n");
        code.append("     */\n");
        code.append("    public List<").append(entityClass.getSimpleName()).append("> toEntities(List<");
        code.append(dtoClass.getSimpleName()).append("> dtos) {\n");
        code.append("        if (dtos == null || dtos.isEmpty()) return new ArrayList<>();\n");
        code.append("        List<").append(entityClass.getSimpleName()).append("> entities = new ArrayList<>(dtos.size());\n");
        code.append("        for (").append(dtoClass.getSimpleName()).append(" dto : dtos) {\n");
        code.append("            entities.add(toEntity(dto));\n");
        code.append("        }\n");
        code.append("        return entities;\n");
        code.append("    }\n\n");

        return code.toString();
    }

    private String generateMetadataMethods(Class<?> dtoClass, Class<?> entityClass, String className) {
        StringBuilder code = new StringBuilder();

        code.append("    @Override\n");
        code.append("    public Class<").append(entityClass.getSimpleName()).append("> getEntityClass() {\n");
        code.append("        return ").append(entityClass.getSimpleName()).append(".class;\n");
        code.append("    }\n\n");

        code.append("    @Override\n");
        code.append("    public Class<").append(dtoClass.getSimpleName()).append("> getDtoClass() {\n");
        code.append("        return ").append(dtoClass.getSimpleName()).append(".class;\n");
        code.append("    }\n\n");

        code.append("    @Override\n");
        code.append("    public String getGeneratedClassName() {\n");
        code.append("        return \"").append(className).append("\";\n");
        code.append("    }\n\n");

        return code.toString();
    }

    /**
     * Compile and load generated class
     */
    private Class<?> compileAndLoad(String className, String sourceCode) throws Exception {
        Path sourceFile = writeSourceFile(className, sourceCode);

        int result = compiler.run(null, null, null,
                sourceFile.toString(),
                "-d", outputDirectory.toString());

        if (result != 0) {
            throw new RuntimeException("Compilation failed for " + className);
        }

        URLClassLoader classLoader = new URLClassLoader(
                new URL[]{outputDirectory.toUri().toURL()},
                getClass().getClassLoader()
        );

        return classLoader.loadClass(className);
    }

    private Path writeSourceFile(String className, String sourceCode) throws IOException {
        String relativePath = className.replace('.', File.separatorChar) + ".java";
        Path sourceFile = outputDirectory.resolve(relativePath);

        Files.createDirectories(sourceFile.getParent());

        try (FileWriter writer = new FileWriter(sourceFile.toFile())) {
            writer.write(sourceCode);
        }

        log.debug("📝 Wrote mapper source: {}", sourceFile);

        return sourceFile;
    }

    /**
     * Create output directory in target/generated-sources for IDE integration
     */
    private Path createOutputDirectory() {
        try {
            // Try Maven structure first
            Path mavenTarget = Paths.get("target", "generated-sources", "crudx-mappers");
            if (Files.exists(Paths.get("pom.xml"))) {
                Files.createDirectories(mavenTarget);
                log.info("📁 Using Maven target directory: {}", mavenTarget.toAbsolutePath());
                return mavenTarget;
            }

            // Try Gradle structure
            Path gradleBuild = Paths.get("build", "generated", "sources", "crudx-mappers");
            if (Files.exists(Paths.get("build.gradle")) || Files.exists(Paths.get("build.gradle.kts"))) {
                Files.createDirectories(gradleBuild);
                log.info("📁 Using Gradle build directory: {}", gradleBuild.toAbsolutePath());
                return gradleBuild;
            }

            // Fallback to temp directory
            Path tempDir = Paths.get(System.getProperty("java.io.tmpdir"), "crudx-generated");
            Files.createDirectories(tempDir);
            log.warn("⚠️ Using temp directory (not IDE-accessible): {}", tempDir.toAbsolutePath());
            return tempDir;

        } catch (IOException e) {
            throw new RuntimeException("Failed to create output directory", e);
        }
    }

    /**
     * Get unified mapper class name (one per entity-dto pair)
     */
    private String getUnifiedClassName(Class<?> dtoClass, Class<?> entityClass) {
        return GENERATED_PACKAGE + "."
                + entityClass.getSimpleName()
                + dtoClass.getSimpleName()
                + "Mapper";
    }

    /**
     * Get simplified mapper key (no operation/direction)
     */
    private String getSimplifiedMapperKey(Class<?> entityClass, Class<?> dtoClass) {
        return entityClass.getName() + "_" + dtoClass.getName();
    }

    private String capitalize(String str) {
        return str.substring(0, 1).toUpperCase() + str.substring(1);
    }

    private List<Field> getAllFields(Class<?> clazz) {
        List<Field> fields = new ArrayList<>();
        while (clazz != null && clazz != Object.class) {
            fields.addAll(Arrays.asList(clazz.getDeclaredFields()));
            clazz = clazz.getSuperclass();
        }
        return fields;
    }

    private Field getFieldFromClass(Class<?> clazz, String fieldName) throws NoSuchFieldException {
        try {
            return clazz.getDeclaredField(fieldName);
        } catch (NoSuchFieldException e) {
            if (clazz.getSuperclass() != null) {
                return getFieldFromClass(clazz.getSuperclass(), fieldName);
            }
            throw e;
        }
    }

    public Map<String, GeneratedMapper<?, ?>> getAllGeneratedMappers() {
        return new HashMap<>(generatedMappers);
    }

    // Analysis helper classes
    private static class MapperAnalysis {
        List<FieldPair> fieldPairs = new ArrayList<>();
        Set<String> ignoredFields = new HashSet<>();
    }

    private static class FieldPair {
        Field dtoField;
        Field entityField;
        boolean requiresConversion;
        boolean nested;
        boolean collection;
        Class<?> nestedDtoClass;
        Class<?> elementDtoClass;
    }
}