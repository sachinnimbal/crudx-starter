package io.github.sachinnimbal.crudx.dto.generator;

import io.github.sachinnimbal.crudx.core.annotations.dto.CrudXCollection;
import io.github.sachinnimbal.crudx.core.annotations.dto.CrudXField;
import io.github.sachinnimbal.crudx.core.annotations.dto.CrudXNested;
import io.github.sachinnimbal.crudx.dto.mapper.CrudXEntityMapper;
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
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFilePermission;
import java.util.*;

/**
 * FIXED MAPPER GENERATOR - Correct Inner Class Handling
 */
@Slf4j
@Component
public class CrudXMapperGenerator {

    private static final String GENERATED_PACKAGE = "io.github.sachinnimbal.crudx.generated.mappers";
    private static final String MAPPER_SUFFIX = "EntityMapper";
    private static final String LOCK_COMMENT = "// ⚠️ AUTO-GENERATED - DO NOT MODIFY - Changes will be overwritten\n";

    private final Path outputDirectory;
    private final JavaCompiler compiler;
    private final Map<Class<?>, CrudXEntityMapper<?, ?>> entityMappers = new HashMap<>();

    public CrudXMapperGenerator() {
        this.compiler = ToolProvider.getSystemJavaCompiler();
        this.outputDirectory = createOutputDirectory();
        cleanGeneratedFiles();
    }

    @SuppressWarnings("unchecked")
    public <E> CrudXEntityMapper<E, ?> getOrGenerateMapper(Class<E> entityClass, Set<Class<?>> dtoClasses) {
        if (entityMappers.containsKey(entityClass)) {
            return (CrudXEntityMapper<E, ?>) entityMappers.get(entityClass);
        }

        synchronized (this) {
            if (entityMappers.containsKey(entityClass)) {
                return (CrudXEntityMapper<E, ?>) entityMappers.get(entityClass);
            }

            try {
                log.info("🔧 Generating CrudX mapper for entity: {}", entityClass.getSimpleName());

                MapperAnalysis analysis = analyzeEntityAndDtos(entityClass, dtoClasses);
                String sourceCode = generateCrudXMapperSource(entityClass, analysis);
                String className = getMapperClassName(entityClass);

                Class<?> mapperClass = compileAndLoad(className, sourceCode);
                CrudXEntityMapper<E, ?> mapper = (CrudXEntityMapper<E, ?>) mapperClass
                        .getDeclaredConstructor()
                        .newInstance();

                entityMappers.put(entityClass, mapper);

                log.info("✓ Generated CrudX mapper: {} (handles {} DTOs)",
                        className.substring(className.lastIndexOf('.') + 1), dtoClasses.size());

                return mapper;

            } catch (Exception e) {
                log.error("Failed to generate mapper for {}", entityClass.getSimpleName(), e);
                throw new RuntimeException("Mapper generation failed: " + e.getMessage(), e);
            }
        }
    }

    public void cleanGeneratedFiles() {
        try {
            if (!Files.exists(outputDirectory)) {
                return;
            }

            Path packagePath = outputDirectory.resolve(GENERATED_PACKAGE.replace('.', File.separatorChar));
            if (!Files.exists(packagePath)) {
                return;
            }

            log.info("🧹 Cleaning old generated mapper files from: {}", packagePath);

            Files.walkFileTree(packagePath, new SimpleFileVisitor<Path>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    String fileName = file.getFileName().toString();
                    if (fileName.endsWith(".java") || fileName.endsWith(".class")) {
                        file.toFile().setWritable(true);
                        Files.delete(file);
                    }
                    return FileVisitResult.CONTINUE;
                }
            });

            log.info("✓ Cleanup completed successfully");

        } catch (Exception e) {
            log.warn("Failed to clean generated files: {}", e.getMessage());
        }
    }

    public void clearMapperCache() {
        synchronized (this) {
            entityMappers.clear();
            log.info("Cleared mapper cache");
        }
    }

    private MapperAnalysis analyzeEntityAndDtos(Class<?> entityClass, Set<Class<?>> dtoClasses) {
        MapperAnalysis analysis = new MapperAnalysis();
        analysis.entityClass = entityClass;
        analysis.entityFields = getAllFields(entityClass);

        for (Class<?> dtoClass : dtoClasses) {
            DtoAnalysis dtoAnalysis = new DtoAnalysis();
            dtoAnalysis.dtoClass = dtoClass;
            dtoAnalysis.dtoFields = getAllFields(dtoClass);

            for (Field dtoField : dtoAnalysis.dtoFields) {
                FieldMapping mapping = analyzeFieldMapping(dtoField, entityClass);
                if (mapping != null) {
                    dtoAnalysis.fieldMappings.add(mapping);
                }
            }

            analysis.dtoAnalyses.add(dtoAnalysis);
        }

        return analysis;
    }

    private FieldMapping analyzeFieldMapping(Field dtoField, Class<?> entityClass) {
        CrudXField fieldAnnotation = dtoField.getAnnotation(CrudXField.class);

        if (fieldAnnotation != null && fieldAnnotation.ignore()) {
            return null;
        }

        String entityFieldName = fieldAnnotation != null && !fieldAnnotation.value().isEmpty()
                ? fieldAnnotation.value()
                : dtoField.getName();

        try {
            Field entityField = getFieldFromClass(entityClass, entityFieldName);

            FieldMapping mapping = new FieldMapping();
            mapping.dtoFieldName = dtoField.getName();
            mapping.entityFieldName = entityField.getName();
            mapping.dtoFieldType = dtoField.getType();
            mapping.entityFieldType = entityField.getType();
            mapping.needsConversion = !dtoField.getType().equals(entityField.getType());

            if (dtoField.isAnnotationPresent(CrudXNested.class)) {
                mapping.nested = true;
                mapping.nestedDtoClass = dtoField.getAnnotation(CrudXNested.class).dto();
            }

            if (dtoField.isAnnotationPresent(CrudXCollection.class)) {
                mapping.collection = true;
                mapping.collectionElementType = dtoField.getAnnotation(CrudXCollection.class).elementDto();
            }

            return mapping;

        } catch (NoSuchFieldException e) {
            log.debug("Field {} not found in entity {}", entityFieldName, entityClass.getSimpleName());
            return null;
        }
    }

    private String generateCrudXMapperSource(Class<?> entityClass, MapperAnalysis analysis) {
        StringBuilder code = new StringBuilder();
        String className = getMapperClassName(entityClass);
        String simpleClassName = className.substring(className.lastIndexOf('.') + 1);

        // Lock warning
        code.append(LOCK_COMMENT);

        // ✅ NEW: MapStruct-style header comment
        code.append("/*\n");
        code.append(" * CrudX Entity Mapper - Auto-generated by CrudX Framework\n");
        code.append(" *\n");
        code.append(" * Entity: ").append(entityClass.getSimpleName()).append("\n");
        code.append(" * Mapper: ").append(simpleClassName).append("\n");
        code.append(" * DTOs Supported: ").append(analysis.dtoAnalyses.size()).append("\n");
        code.append(" *\n");

        // List all supported DTOs
        for (DtoAnalysis dto : analysis.dtoAnalyses) {
            code.append(" *   - ").append(dto.dtoClass.getSimpleName()).append("\n");
        }

        code.append(" *\n");
        code.append(" * Generated on: ").append(java.time.LocalDateTime.now()
                .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))).append("\n");
        code.append(" * CrudX Framework Version: 2.0.0\n");
        code.append(" *\n");
        code.append(" * WARNING: Do not modify this file manually.\n");
        code.append(" *          Changes will be overwritten on next build.\n");
        code.append(" */\n\n");

        // Package
        code.append("package ").append(GENERATED_PACKAGE).append(";\n\n");

        // Imports
        code.append("import io.github.sachinnimbal.crudx.dto.mapper.CrudXEntityMapper;\n");
        code.append(generateImportStatement(entityClass)).append("\n");

        for (DtoAnalysis dtoAnalysis : analysis.dtoAnalyses) {
            code.append(generateImportStatement(dtoAnalysis.dtoClass)).append("\n");
        }

        code.append("import java.util.*;\n");
        code.append("import java.time.*;\n");
        code.append("import java.lang.reflect.*;\n\n");

        // ✅ NEW: Add @Generated annotation
        code.append("import javax.annotation.processing.Generated;\n\n");

        code.append("/**\n");
        code.append(" * Unified mapper for {@link ").append(entityClass.getSimpleName()).append("} entity.\n");
        code.append(" * <p>\n");
        code.append(" * This mapper handles all DTO conversions for the entity.\n");
        code.append(" * Supported DTOs:\n");
        code.append(" * <ul>\n");
        for (DtoAnalysis dto : analysis.dtoAnalyses) {
            code.append(" *   <li>{@link ").append(dto.dtoClass.getSimpleName()).append("}</li>\n");
        }
        code.append(" * </ul>\n");
        code.append(" *\n");
        code.append(" * @generated by CrudX Framework v2.0.0\n");
        code.append(" * @author CrudX Mapper Generator\n");
        code.append(" */\n");

        // Add @Generated annotation
        code.append("@Generated(\n");
        code.append("    value = \"io.github.sachinnimbal.crudx.dto.generator.CrudXMapperGenerator\",\n");
        code.append("    date = \"").append(java.time.LocalDateTime.now()
                .format(java.time.format.DateTimeFormatter.ISO_LOCAL_DATE_TIME)).append("\",\n");
        code.append("    comments = \"CrudX Framework - Unified Entity Mapper\"\n");
        code.append(")\n");

        // Class declaration
        code.append("public class ").append(simpleClassName);
        code.append(" implements CrudXEntityMapper<");
        code.append(getCodeReference(entityClass)).append(", Object> {\n\n");

        // Methods
        code.append(generateGenericToEntityMethod(entityClass, analysis));
        code.append(generateGenericToDtoMethod(entityClass, analysis));
        code.append(generateUpdateEntityMethod(entityClass, analysis));
        code.append(generateBatchMethods(entityClass));
        code.append(generateMetadataMethods(entityClass, analysis));

        code.append("}\n");

        return code.toString();
    }

    /**
     * ✅ FIX: Generate correct import for both regular and inner classes
     */
    private String generateImportStatement(Class<?> clazz) {
        // Convert com.example.Controller$Inner to com.example.Controller.Inner
        return "import " + clazz.getName().replace('$', '.') + ";";
    }

    /**
     * Get reference for Javadoc (uses simple name)
     */
    private String getJavadocReference(Class<?> clazz) {
        return clazz.getSimpleName();
    }

    /**
     * ✅ CRITICAL FIX: Get reference for generated code
     * Since we import "com.example.Controller.Inner", we just use "Inner" in code
     */
    private String getCodeReference(Class<?> clazz) {
        return clazz.getSimpleName();
    }

    private String generateGenericToEntityMethod(Class<?> entityClass, MapperAnalysis analysis) {
        StringBuilder code = new StringBuilder();
        String entityRef = getCodeReference(entityClass);

        code.append("    @Override\n");
        code.append("    public ").append(entityRef).append(" toEntity(Object dto) {\n");
        code.append("        if (dto == null) return null;\n\n");
        code.append("        ").append(entityRef).append(" entity = new ");
        code.append(entityRef).append("();\n\n");

        for (DtoAnalysis dtoAnalysis : analysis.dtoAnalyses) {
            String dtoRef = getCodeReference(dtoAnalysis.dtoClass);

            code.append("        if (dto instanceof ").append(dtoRef).append(") {\n");
            code.append("            ").append(dtoRef).append(" typedDto = (");
            code.append(dtoRef).append(") dto;\n");

            for (FieldMapping mapping : dtoAnalysis.fieldMappings) {
                String getter = "typedDto.get" + capitalize(mapping.dtoFieldName) + "()";
                String setter = "entity.set" + capitalize(mapping.entityFieldName);

                if (!mapping.nested && !mapping.collection) {
                    code.append("            ").append(setter).append("(").append(getter).append(");\n");
                }
            }

            code.append("            return entity;\n");
            code.append("        }\n\n");
        }

        code.append("        return mapUsingReflection(dto, entity);\n");
        code.append("    }\n\n");

        return code.toString();
    }

    private String generateGenericToDtoMethod(Class<?> entityClass, MapperAnalysis analysis) {
        StringBuilder code = new StringBuilder();
        String entityRef = getCodeReference(entityClass);

        code.append("    @Override\n");
        code.append("    public <D> D toDto(").append(entityRef).append(" entity, Class<D> dtoClass) {\n");
        code.append("        if (entity == null) return null;\n\n");

        for (DtoAnalysis dtoAnalysis : analysis.dtoAnalyses) {
            String dtoRef = getCodeReference(dtoAnalysis.dtoClass);

            code.append("        if (dtoClass == ").append(dtoRef).append(".class) {\n");
            code.append("            ").append(dtoRef).append(" dto = new ");
            code.append(dtoRef).append("();\n");

            for (FieldMapping mapping : dtoAnalysis.fieldMappings) {
                String getter = "entity.get" + capitalize(mapping.entityFieldName) + "()";
                String setter = "dto.set" + capitalize(mapping.dtoFieldName);
                code.append("            ").append(setter).append("(").append(getter).append(");\n");
            }

            code.append("            return (D) dto;\n");
            code.append("        }\n\n");
        }

        code.append("        return mapToDtoUsingReflection(entity, dtoClass);\n");
        code.append("    }\n\n");

        return code.toString();
    }

    private String generateUpdateEntityMethod(Class<?> entityClass, MapperAnalysis analysis) {
        StringBuilder code = new StringBuilder();
        String entityRef = getCodeReference(entityClass);

        code.append("    @Override\n");
        code.append("    public void updateEntity(Object dto, ").append(entityRef).append(" entity) {\n");
        code.append("        if (dto == null || entity == null) return;\n\n");

        for (DtoAnalysis dtoAnalysis : analysis.dtoAnalyses) {
            String dtoRef = getCodeReference(dtoAnalysis.dtoClass);

            code.append("        if (dto instanceof ").append(dtoRef).append(") {\n");
            code.append("            ").append(dtoRef).append(" typedDto = (");
            code.append(dtoRef).append(") dto;\n");

            for (FieldMapping mapping : dtoAnalysis.fieldMappings) {
                String getter = "typedDto.get" + capitalize(mapping.dtoFieldName) + "()";
                String setter = "entity.set" + capitalize(mapping.entityFieldName);

                code.append("            if (").append(getter).append(" != null) {\n");
                code.append("                ").append(setter).append("(").append(getter).append(");\n");
                code.append("            }\n");
            }

            code.append("            return;\n");
            code.append("        }\n\n");
        }

        code.append("    }\n\n");
        return code.toString();
    }

    private String generateBatchMethods(Class<?> entityClass) {
        StringBuilder code = new StringBuilder();
        String entityRef = getCodeReference(entityClass);

        code.append("    @Override\n");
        code.append("    public <D> List<D> toDtos(List<").append(entityRef);
        code.append("> entities, Class<D> dtoClass) {\n");
        code.append("        if (entities == null || entities.isEmpty()) return new ArrayList<>();\n");
        code.append("        List<D> dtos = new ArrayList<>(entities.size());\n");
        code.append("        for (").append(entityRef).append(" entity : entities) {\n");
        code.append("            dtos.add(toDto(entity, dtoClass));\n");
        code.append("        }\n");
        code.append("        return dtos;\n");
        code.append("    }\n\n");

        code.append("    @Override\n");
        code.append("    public List<").append(entityRef).append("> toEntities(List<Object> dtos) {\n");
        code.append("        if (dtos == null || dtos.isEmpty()) return new ArrayList<>();\n");
        code.append("        List<").append(entityRef).append("> entities = new ArrayList<>(dtos.size());\n");
        code.append("        for (Object dto : dtos) {\n");
        code.append("            ").append(entityRef).append(" entity = toEntity(dto);\n");
        code.append("            if (entity != null) entities.add(entity);\n");
        code.append("        }\n");
        code.append("        return entities;\n");
        code.append("    }\n\n");

        code.append(generateReflectionHelpers(entityClass));
        return code.toString();
    }

    private String generateReflectionHelpers(Class<?> entityClass) {
        String entityRef = getCodeReference(entityClass);
        return "    private " + entityRef + " mapUsingReflection(Object dto, " +
                entityRef + " entity) {\n" +
                "        try {\n" +
                "            for (Field dtoField : dto.getClass().getDeclaredFields()) {\n" +
                "                dtoField.setAccessible(true);\n" +
                "                Object value = dtoField.get(dto);\n" +
                "                if (value != null) {\n" +
                "                    try {\n" +
                "                        Field entityField = entity.getClass().getDeclaredField(dtoField.getName());\n" +
                "                        entityField.setAccessible(true);\n" +
                "                        entityField.set(entity, value);\n" +
                "                    } catch (NoSuchFieldException ignored) {}\n" +
                "                }\n" +
                "            }\n" +
                "        } catch (Exception e) {\n" +
                "            throw new RuntimeException(\"Reflection mapping failed\", e);\n" +
                "        }\n" +
                "        return entity;\n" +
                "    }\n\n" +
                "    private <D> D mapToDtoUsingReflection(" + entityRef + " entity, Class<D> dtoClass) {\n" +
                "        try {\n" +
                "            D dto = dtoClass.getDeclaredConstructor().newInstance();\n" +
                "            for (Field dtoField : dtoClass.getDeclaredFields()) {\n" +
                "                try {\n" +
                "                    Field entityField = entity.getClass().getDeclaredField(dtoField.getName());\n" +
                "                    entityField.setAccessible(true);\n" +
                "                    Object value = entityField.get(entity);\n" +
                "                    if (value != null) {\n" +
                "                        dtoField.setAccessible(true);\n" +
                "                        dtoField.set(dto, value);\n" +
                "                    }\n" +
                "                } catch (NoSuchFieldException ignored) {}\n" +
                "            }\n" +
                "            return dto;\n" +
                "        } catch (Exception e) {\n" +
                "            throw new RuntimeException(\"Reflection mapping failed\", e);\n" +
                "        }\n" +
                "    }\n\n";
    }

    private String generateMetadataMethods(Class<?> entityClass, MapperAnalysis analysis) {
        StringBuilder code = new StringBuilder();
        String entityRef = getCodeReference(entityClass);

        code.append("    @Override\n");
        code.append("    public Class<").append(entityRef).append("> getEntityClass() {\n");
        code.append("        return ").append(entityRef).append(".class;\n");
        code.append("    }\n\n");

        code.append("    @Override\n");
        code.append("    public List<Class<?>> getSupportedDtoClasses() {\n");
        code.append("        return Arrays.asList(\n");
        for (int i = 0; i < analysis.dtoAnalyses.size(); i++) {
            String dtoRef = getCodeReference(analysis.dtoAnalyses.get(i).dtoClass);
            code.append("            ").append(dtoRef).append(".class");
            if (i < analysis.dtoAnalyses.size() - 1) code.append(",");
            code.append("\n");
        }
        code.append("        );\n");
        code.append("    }\n\n");

        return code.toString();
    }

    private Class<?> compileAndLoad(String className, String sourceCode) throws Exception {
        Path sourceFile = writeSourceFile(className, sourceCode);

        int result = compiler.run(null, null, null,
                sourceFile.toString(),
                "-d", outputDirectory.toString());

        if (result != 0) {
            throw new RuntimeException("Compilation failed for " + className);
        }

        lockGeneratedFile(sourceFile);

        URLClassLoader classLoader = new URLClassLoader(
                new URL[]{outputDirectory.toUri().toURL()},
                getClass().getClassLoader()
        );

        return classLoader.loadClass(className);
    }

    private Path writeSourceFile(String className, String sourceCode) throws Exception {
        String relativePath = className.replace('.', File.separatorChar) + ".java";
        Path sourceFile = outputDirectory.resolve(relativePath);

        Files.createDirectories(sourceFile.getParent());

        try (FileWriter writer = new FileWriter(sourceFile.toFile())) {
            writer.write(sourceCode);
        }

        return sourceFile;
    }

    private void lockGeneratedFile(Path file) {
        try {
            File javaFile = file.toFile();
            javaFile.setReadOnly();

            if (file.getFileSystem().supportedFileAttributeViews().contains("posix")) {
                try {
                    Set<PosixFilePermission> perms = new HashSet<>();
                    perms.add(PosixFilePermission.OWNER_READ);
                    perms.add(PosixFilePermission.GROUP_READ);
                    perms.add(PosixFilePermission.OTHERS_READ);
                    Files.setPosixFilePermissions(file, perms);
                } catch (Exception e) {
                    log.debug("Could not set POSIX permissions: {}", e.getMessage());
                }
            }

            log.debug("🔒 Locked generated file: {}", file.getFileName());
        } catch (Exception e) {
            log.warn("Could not lock file {}: {}", file.getFileName(), e.getMessage());
        }
    }

    private Path createOutputDirectory() {
        try {
            Path mavenTarget = Paths.get("target", "generated-sources", "crudx-mappers");
            if (Files.exists(Paths.get("pom.xml"))) {
                Files.createDirectories(mavenTarget);
                return mavenTarget;
            }

            Path gradleBuild = Paths.get("build", "generated", "sources", "crudx-mappers");
            if (Files.exists(Paths.get("build.gradle")) || Files.exists(Paths.get("build.gradle.kts"))) {
                Files.createDirectories(gradleBuild);
                return gradleBuild;
            }

            Path tempDir = Paths.get(System.getProperty("java.io.tmpdir"), "crudx-generated");
            Files.createDirectories(tempDir);
            return tempDir;

        } catch (Exception e) {
            throw new RuntimeException("Failed to create output directory", e);
        }
    }

    private String getMapperClassName(Class<?> entityClass) {
        return GENERATED_PACKAGE + "." + entityClass.getSimpleName() + MAPPER_SUFFIX;
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

    private static class MapperAnalysis {
        Class<?> entityClass;
        List<Field> entityFields;
        List<DtoAnalysis> dtoAnalyses = new ArrayList<>();
    }

    private static class DtoAnalysis {
        Class<?> dtoClass;
        List<Field> dtoFields;
        List<FieldMapping> fieldMappings = new ArrayList<>();
    }

    private static class FieldMapping {
        String dtoFieldName;
        String entityFieldName;
        Class<?> dtoFieldType;
        Class<?> entityFieldType;
        boolean needsConversion;
        boolean nested;
        boolean collection;
        Class<?> nestedDtoClass;
        Class<?> collectionElementType;
    }
}