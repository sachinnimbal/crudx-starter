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
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFilePermission;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

@Slf4j
@Component
public class CrudXMapperGenerator {

    private static final String GENERATED_PACKAGE = "io.github.sachinnimbal.crudx.generated.mappers";
    private static final String MAPPER_SUFFIX = "EntityMapper";
    private static final String LOCK_COMMENT = "// ⚠️ AUTO-GENERATED - DO NOT MODIFY - Changes will be overwritten\n";
    private static final DateTimeFormatter DATE_TIME_FORMATTER = DateTimeFormatter.ofPattern("MMMM dd, yyyy 'at' hh:mm:ss a");
    private static final String FRAMEWORK_VERSION = getFrameworkVersion();

    private static final String APACHE_LICENSE_HEADER =
            """
                    /*
                     * Copyright 2025 Sachin Nimbal
                     *
                     * Licensed under the Apache License, Version 2.0 (the "License");
                     * you may not use this file except in compliance with the License.
                     * You may obtain a copy of the License at
                     *
                     *     http://www.apache.org/licenses/LICENSE-2.0
                     *
                     * Unless required by applicable law or agreed to in writing, software
                     * distributed under the License is distributed on an "AS IS" BASIS,
                     * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
                     * See the License for the specific language governing permissions and
                     * limitations under the License.
                     */
                    
                    """;

    private final Path outputDirectory;
    private final JavaCompiler compiler;
    private final Map<Class<?>, CrudXEntityMapper<?, ?>> entityMappers = new HashMap<>();

    public CrudXMapperGenerator() {
        this.compiler = ToolProvider.getSystemJavaCompiler();
        this.outputDirectory = createOutputDirectory();
        cleanGeneratedFiles();
    }

    private static String getFrameworkVersion() {
        String version = System.getProperty("crudx.version");
        if (version != null && !version.isEmpty()) {
            return version;
        }

        Package pkg = CrudXMapperGenerator.class.getPackage();
        if (pkg != null && pkg.getImplementationVersion() != null) {
            return pkg.getImplementationVersion();
        }

        try {
            Properties props = new Properties();
            var versionStream = CrudXMapperGenerator.class.getClassLoader()
                    .getResourceAsStream("crudx-version.properties");
            if (versionStream != null) {
                try (versionStream) {
                    props.load(versionStream);
                    version = props.getProperty("version");
                    if (version != null && !version.isEmpty()) {
                        log.debug("Loaded CrudX version from properties: {}", version);
                        return version;
                    }
                }
            }
        } catch (Exception e) {
            log.debug("Could not read version from properties: {}", e.getMessage());
        }

        log.debug("Using fallback version: 2.0.0");
        return "2.0.0";
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
        log.info("Analyzing entity: {} with {} DTOs", entityClass.getSimpleName(), dtoClasses.size());

        for (Class<?> dtoClass : dtoClasses) {
            DtoAnalysis dtoAnalysis = new DtoAnalysis();
            dtoAnalysis.dtoClass = dtoClass;
            dtoAnalysis.dtoFields = getAllFields(dtoClass);
            log.debug("Analyzing DTO: {} with {} fields", dtoClass.getSimpleName(), dtoAnalysis.dtoFields.size());

            for (Field dtoField : dtoAnalysis.dtoFields) {
                FieldMapping mapping = analyzeFieldMapping(dtoField, entityClass);
                if (mapping != null) {
                    dtoAnalysis.fieldMappings.add(mapping);
                    log.debug("  Field: {} -> {} (nested={}, collection={})",
                            mapping.dtoFieldName,
                            mapping.entityFieldName,
                            mapping.nested,
                            mapping.collection);
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
            } else if (isNestedObject(dtoField.getType())) {
                mapping.nested = true;
                mapping.nestedDtoClass = null;
                log.debug("Auto-detected nested object: {} in {}",
                        dtoField.getName(), dtoField.getDeclaringClass().getSimpleName());
            }

            if (dtoField.isAnnotationPresent(CrudXCollection.class)) {
                mapping.collection = true;
                mapping.collectionElementType = dtoField.getAnnotation(CrudXCollection.class).elementDto();
            } else if (Collection.class.isAssignableFrom(dtoField.getType())) {
                mapping.collection = true;
                mapping.collectionElementType = getCollectionElementType(dtoField);
                log.debug("Auto-detected collection: {} in {}",
                        dtoField.getName(), dtoField.getDeclaringClass().getSimpleName());
            }

            return mapping;

        } catch (NoSuchFieldException e) {
            log.debug("Field {} not found in entity {}", entityFieldName, entityClass.getSimpleName());
            return null;
        }
    }

    private Class<?> getCollectionElementType(Field field) {
        try {
            Type genericType = field.getGenericType();
            if (genericType instanceof ParameterizedType paramType) {
                Type[] typeArgs = paramType.getActualTypeArguments();
                if (typeArgs.length > 0 && typeArgs[0] instanceof Class) {
                    return (Class<?>) typeArgs[0];
                }
            }
        } catch (Exception e) {
            log.debug("Could not determine collection element type for {}", field.getName());
        }
        return Object.class;
    }

    private String generateCrudXMapperSource(Class<?> entityClass, MapperAnalysis analysis) {
        StringBuilder code = new StringBuilder();
        String className = getMapperClassName(entityClass);
        String simpleClassName = className.substring(className.lastIndexOf('.') + 1);
        LocalDateTime now = LocalDateTime.now();
        String formattedDateTime = now.format(DATE_TIME_FORMATTER);

        code.append(APACHE_LICENSE_HEADER);
        code.append(LOCK_COMMENT);

        code.append("/*\n");
        code.append(" * CrudX Entity Mapper - Auto-generated by CrudX Framework\n");
        code.append(" *\n");
        code.append(" * Entity: ").append(entityClass.getSimpleName()).append("\n");
        code.append(" * Mapper: ").append(simpleClassName).append("\n");
        code.append(" * DTOs Supported: ").append(analysis.dtoAnalyses.size()).append("\n");
        code.append(" *\n");

        for (DtoAnalysis dto : analysis.dtoAnalyses) {
            code.append(" *   - ").append(dto.dtoClass.getSimpleName()).append("\n");
        }

        code.append(" *\n");
        code.append(" * Generated on: ").append(formattedDateTime).append("\n");
        code.append(" * CrudX Framework Version: ").append(FRAMEWORK_VERSION).append("\n");
        code.append(" *\n");
        code.append(" * WARNING: Do not modify this file manually.\n");
        code.append(" *          Changes will be overwritten on next build.\n");
        code.append(" */\n\n");

        code.append("package ").append(GENERATED_PACKAGE).append(";\n\n");

        Set<String> imports = new LinkedHashSet<>();
        imports.add("import io.github.sachinnimbal.crudx.dto.mapper.CrudXEntityMapper;");
        imports.add(generateImportStatement(entityClass));

        for (DtoAnalysis dtoAnalysis : analysis.dtoAnalyses) {
            imports.add(generateImportStatement(dtoAnalysis.dtoClass));
        }

        for (String imp : imports) {
            code.append(imp).append("\n");
        }

        code.append("import java.util.*;\n");
        code.append("import java.time.*;\n");
        code.append("import java.lang.reflect.*;\n");
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
        code.append(" * @generated by CrudX Framework v").append(FRAMEWORK_VERSION).append("\n");
        code.append(" * @author Sachin Nimbal\n");
        code.append(" */\n");

        code.append("@Generated(\n");
        code.append("    value = \"io.github.sachinnimbal.crudx.dto.generator.CrudXMapperGenerator\",\n");
        code.append("    date = \"").append(formattedDateTime).append("\",\n");
        code.append("    comments = \"CRUDX | The Next-Gen Multi-Database CRUD Framework for Spring Boot.\"\n");
        code.append(")\n");

        code.append("public class ").append(simpleClassName);
        code.append(" implements CrudXEntityMapper<");
        code.append(getCodeReference(entityClass)).append(", Object> {\n\n");

        code.append(generateGenericToEntityMethod(entityClass, analysis));
        code.append(generateGenericToDtoMethod(entityClass, analysis));
        code.append(generateUpdateEntityMethod(entityClass, analysis));
        code.append(generateBatchMethods(entityClass));
        code.append(generateDeepCopyHelpers(analysis));
        code.append(generateMetadataMethods(entityClass, analysis));

        code.append("}\n");

        return code.toString();
    }

    private String generateImportStatement(Class<?> clazz) {
        if (clazz.getEnclosingClass() != null) {
            return "import " + clazz.getEnclosingClass().getName().replace('$', '.') + ";";
        }
        return "import " + clazz.getName().replace('$', '.') + ";";
    }

    private String getCodeReference(Class<?> clazz) {
        if (clazz.getEnclosingClass() != null) {
            return getCodeReference(clazz.getEnclosingClass()) + "." + clazz.getSimpleName();
        }
        return clazz.getSimpleName();
    }

    /**
     * Creates a unique method name using fully qualified class names to avoid collisions
     */
    private String getUniqueMethodName(Class<?> sourceType, Class<?> targetType) {
        String sourceFqn = sourceType.getName().replace('.', '_').replace('$', '_');
        String targetFqn = targetType.getName().replace('.', '_').replace('$', '_');
        return "map_" + sourceFqn + "_to_" + targetFqn;
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

                if (mapping.nested) {
                    // Use unique method name based on fully qualified types
                    String conversionMethod = getUniqueMethodName(mapping.dtoFieldType, mapping.entityFieldType);
                    code.append("            ").append(setter).append("(")
                            .append(conversionMethod).append("(").append(getter).append("));\n");
                } else if (mapping.collection) {
                    Class<?> entityElementType = getEntityElementTypeFromField(mapping.entityFieldName, entityClass);
                    String conversionMethod = getUniqueMethodName(mapping.collectionElementType, entityElementType) + "_List";
                    code.append("            ").append(setter).append("(")
                            .append(conversionMethod).append("(").append(getter).append("));\n");
                } else {
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

                if (mapping.nested) {
                    String conversionMethod = getUniqueMethodName(mapping.entityFieldType, mapping.dtoFieldType);
                    code.append("            ").append(setter).append("(")
                            .append(conversionMethod).append("(").append(getter).append("));\n");
                } else if (mapping.collection) {
                    Class<?> entityElementType = getEntityElementTypeFromField(mapping.entityFieldName, entityClass);
                    String conversionMethod = getUniqueMethodName(entityElementType, mapping.collectionElementType) + "_List";
                    code.append("            ").append(setter).append("(")
                            .append(conversionMethod).append("(").append(getter).append("));\n");
                } else {
                    code.append("            ").append(setter).append("(").append(getter).append(");\n");
                }
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

                if (mapping.nested) {
                    String conversionMethod = getUniqueMethodName(mapping.dtoFieldType, mapping.entityFieldType);
                    code.append("                ").append(setter).append("(")
                            .append(conversionMethod).append("(").append(getter).append("));\n");
                } else if (mapping.collection) {
                    Class<?> entityElementType = getEntityElementTypeFromField(mapping.entityFieldName, entityClass);
                    String conversionMethod = getUniqueMethodName(mapping.collectionElementType, entityElementType) + "_List";
                    code.append("                ").append(setter).append("(")
                            .append(conversionMethod).append("(").append(getter).append("));\n");
                } else {
                    code.append("                ").append(setter).append("(").append(getter).append(");\n");
                }

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

            if (!javaFile.setReadOnly()) {
                log.warn("Failed to set read-only flag for: {}", file.getFileName());
            }

            if (file.getFileSystem().supportedFileAttributeViews().contains("posix")) {
                try {
                    Set<PosixFilePermission> perms = new HashSet<>();
                    perms.add(PosixFilePermission.OWNER_READ);
                    perms.add(PosixFilePermission.GROUP_READ);
                    perms.add(PosixFilePermission.OTHERS_READ);
                    Files.setPosixFilePermissions(file, perms);
                    log.debug("🔒 Set POSIX read-only permissions for: {}", file.getFileName());
                } catch (Exception e) {
                    log.debug("Could not set POSIX permissions: {}", e.getMessage());
                }
            }

            log.info("🔒 Locked generated file (read-only): {}", file.getFileName());

        } catch (Exception e) {
            log.warn("Could not fully lock file {}: {}", file.getFileName(), e.getMessage());
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

    private Class<?> getEntityElementTypeFromField(String fieldName, Class<?> entityClass) {
        try {
            Field field = getFieldFromClass(entityClass, fieldName);
            Type genericType = field.getGenericType();
            if (genericType instanceof ParameterizedType paramType) {
                Type[] typeArgs = paramType.getActualTypeArguments();
                if (typeArgs.length > 0 && typeArgs[0] instanceof Class) {
                    return (Class<?>) typeArgs[0];
                }
            }
        } catch (Exception e) {
            log.debug("Could not determine entity element type for field: {}", fieldName);
        }
        return Object.class;
    }

    private static class NestedMapping {
        String methodName;
        Class<?> sourceType;
        Class<?> targetType;
        List<FieldMapping> fieldMappings = new ArrayList<>();
    }

    private String generateDeepCopyHelpers(MapperAnalysis analysis) {
        StringBuilder code = new StringBuilder();
        // Use fully qualified names as keys to avoid collisions
        Map<String, NestedMapping> nestedMappings = new LinkedHashMap<>();

        // First pass: Collect all nested mappings
        for (DtoAnalysis dtoAnalysis : analysis.dtoAnalyses) {
            for (FieldMapping mapping : dtoAnalysis.fieldMappings) {

                // Handle nested objects
                if (mapping.nested) {
                    // Use fully qualified names to create unique keys
                    String key = mapping.dtoFieldType.getName() + "_to_" + mapping.entityFieldType.getName();

                    if (!nestedMappings.containsKey(key)) {
                        NestedMapping nestedMap = new NestedMapping();
                        nestedMap.methodName = getUniqueMethodName(mapping.dtoFieldType, mapping.entityFieldType);
                        nestedMap.sourceType = mapping.dtoFieldType;
                        nestedMap.targetType = mapping.entityFieldType;
                        nestedMap.fieldMappings = analyzeNestedFieldMappings(
                                mapping.dtoFieldType,
                                mapping.entityFieldType
                        );

                        nestedMappings.put(key, nestedMap);
                    }

                    // Reverse mapping (Entity → DTO)
                    String reverseKey = mapping.entityFieldType.getName() + "_to_" + mapping.dtoFieldType.getName();

                    if (!nestedMappings.containsKey(reverseKey)) {
                        NestedMapping reverseMap = new NestedMapping();
                        reverseMap.methodName = getUniqueMethodName(mapping.entityFieldType, mapping.dtoFieldType);
                        reverseMap.sourceType = mapping.entityFieldType;
                        reverseMap.targetType = mapping.dtoFieldType;
                        reverseMap.fieldMappings = analyzeNestedFieldMappings(
                                mapping.entityFieldType,
                                mapping.dtoFieldType
                        );

                        nestedMappings.put(reverseKey, reverseMap);
                    }
                }

                // Handle collections of nested objects
                if (mapping.collection && mapping.collectionElementType != null) {
                    Class<?> dtoElementType = mapping.collectionElementType;
                    Class<?> entityElementType = getEntityElementTypeFromField(mapping.entityFieldName, analysis.entityClass);

                    if (entityElementType != null && !dtoElementType.equals(entityElementType)) {

                        String elementKey = dtoElementType.getName() + "_to_" + entityElementType.getName();

                        if (!nestedMappings.containsKey(elementKey)) {
                            NestedMapping nestedMap = new NestedMapping();
                            nestedMap.methodName = getUniqueMethodName(dtoElementType, entityElementType);
                            nestedMap.sourceType = dtoElementType;
                            nestedMap.targetType = entityElementType;
                            nestedMap.fieldMappings = analyzeNestedFieldMappings(
                                    dtoElementType,
                                    entityElementType
                            );

                            nestedMappings.put(elementKey, nestedMap);
                        }

                        // Reverse mapping for collections
                        String reverseElementKey = entityElementType.getName() + "_to_" + dtoElementType.getName();

                        if (!nestedMappings.containsKey(reverseElementKey)) {
                            NestedMapping reverseMap = new NestedMapping();
                            reverseMap.methodName = getUniqueMethodName(entityElementType, dtoElementType);
                            reverseMap.sourceType = entityElementType;
                            reverseMap.targetType = dtoElementType;
                            reverseMap.fieldMappings = analyzeNestedFieldMappings(
                                    entityElementType,
                                    dtoElementType
                            );

                            nestedMappings.put(reverseElementKey, reverseMap);
                        }
                    }
                }
            }
        }

        // Second pass: Generate conversion methods
        for (NestedMapping nestedMap : nestedMappings.values()) {
            code.append(generateNestedConversionMethod(nestedMap));
        }

        // Third pass: Generate collection conversion methods
        Set<String> listMethodsGenerated = new HashSet<>();

        for (DtoAnalysis dtoAnalysis : analysis.dtoAnalyses) {
            for (FieldMapping mapping : dtoAnalysis.fieldMappings) {
                if (mapping.collection && mapping.collectionElementType != null) {
                    Class<?> dtoElementType = mapping.collectionElementType;
                    Class<?> entityElementType = getEntityElementTypeFromField(mapping.entityFieldName, analysis.entityClass);

                    if (entityElementType != null) {
                        // DTO → Entity list conversion
                        String listKey = dtoElementType.getName() + "_to_" + entityElementType.getName() + "_List";

                        if (!listMethodsGenerated.contains(listKey)) {
                            listMethodsGenerated.add(listKey);

                            String conversionMethod = getUniqueMethodName(dtoElementType, entityElementType);
                            code.append(generateCollectionConversionMethod(
                                    dtoElementType,
                                    entityElementType,
                                    conversionMethod
                            ));
                        }

                        // Entity → DTO list conversion (for responses)
                        String reverseDtoListKey = entityElementType.getName() + "_to_" + dtoElementType.getName() + "_List";

                        if (!listMethodsGenerated.contains(reverseDtoListKey)) {
                            listMethodsGenerated.add(reverseDtoListKey);

                            String reverseConversionMethod = getUniqueMethodName(entityElementType, dtoElementType);
                            code.append(generateCollectionConversionMethod(
                                    entityElementType,
                                    dtoElementType,
                                    reverseConversionMethod
                            ));
                        }
                    }
                }
            }
        }

        return code.toString();
    }

    private List<FieldMapping> analyzeNestedFieldMappings(Class<?> sourceClass, Class<?> targetClass) {
        List<FieldMapping> mappings = new ArrayList<>();

        List<Field> sourceFields = getAllFields(sourceClass);

        for (Field sourceField : sourceFields) {
            if (Modifier.isStatic(sourceField.getModifiers()) ||
                    Modifier.isTransient(sourceField.getModifiers())) {
                continue;
            }

            CrudXField fieldAnnotation = sourceField.getAnnotation(CrudXField.class);

            if (fieldAnnotation != null && fieldAnnotation.ignore()) {
                continue;
            }

            String targetFieldName = fieldAnnotation != null && !fieldAnnotation.value().isEmpty()
                    ? fieldAnnotation.value()
                    : sourceField.getName();

            try {
                Field targetField = getFieldFromClass(targetClass, targetFieldName);

                FieldMapping mapping = new FieldMapping();
                mapping.dtoFieldName = sourceField.getName();
                mapping.entityFieldName = targetField.getName();
                mapping.dtoFieldType = sourceField.getType();
                mapping.entityFieldType = targetField.getType();
                mapping.needsConversion = !sourceField.getType().equals(targetField.getType());

                if (sourceField.isAnnotationPresent(CrudXNested.class)) {
                    mapping.nested = true;
                    mapping.nestedDtoClass = sourceField.getAnnotation(CrudXNested.class).dto();
                } else if (isNestedObject(sourceField.getType())) {
                    mapping.nested = true;
                }

                if (sourceField.isAnnotationPresent(CrudXCollection.class)) {
                    mapping.collection = true;
                    mapping.collectionElementType = sourceField.getAnnotation(CrudXCollection.class).elementDto();
                } else if (Collection.class.isAssignableFrom(sourceField.getType())) {
                    mapping.collection = true;
                    mapping.collectionElementType = getCollectionElementType(sourceField);
                }

                mappings.add(mapping);

            } catch (NoSuchFieldException e) {
                log.debug("Field {} not found in target {} (nested mapping)",
                        targetFieldName, targetClass.getSimpleName());
            }
        }

        return mappings;
    }

    private String generateNestedConversionMethod(NestedMapping nestedMap) {
        StringBuilder code = new StringBuilder();
        String sourceRef = getCodeReference(nestedMap.sourceType);
        String targetRef = getCodeReference(nestedMap.targetType);

        code.append("    /**\n");
        code.append("     * Converts ").append(sourceRef).append(" to ").append(targetRef).append("\n");
        code.append("     * @param source Source object of type ").append(sourceRef).append("\n");
        code.append("     * @return Converted object of type ").append(targetRef).append("\n");
        code.append("     */\n");
        code.append("    private ").append(targetRef).append(" ")
                .append(nestedMap.methodName).append("(").append(sourceRef).append(" source) {\n");
        code.append("        if (source == null) return null;\n\n");
        code.append("        ").append(targetRef).append(" target = new ").append(targetRef).append("();\n");

        for (FieldMapping mapping : nestedMap.fieldMappings) {
            String getter = "source.get" + capitalize(mapping.dtoFieldName) + "()";
            String setter = "target.set" + capitalize(mapping.entityFieldName);

            // Handle nested objects within nested objects
            if (mapping.nested) {
                String nestedConversionMethod = getUniqueMethodName(mapping.dtoFieldType, mapping.entityFieldType);
                code.append("        ").append(setter).append("(")
                        .append(nestedConversionMethod).append("(").append(getter).append("));\n");
            } else if (mapping.collection) {
                String collectionConversionMethod = getUniqueMethodName(
                        mapping.collectionElementType,
                        getCollectionElementType(mapping.entityFieldType)
                ) + "_List";
                code.append("        ").append(setter).append("(")
                        .append(collectionConversionMethod).append("(").append(getter).append("));\n");
            } else {
                code.append("        ").append(setter).append("(").append(getter).append(");\n");
            }
        }

        code.append("        return target;\n");
        code.append("    }\n\n");

        return code.toString();
    }

    private Class<?> getCollectionElementType(Class<?> fieldType) {
        // Fallback for when we can't determine generic type
        return Object.class;
    }

    private String generateCollectionConversionMethod(
            Class<?> sourceElementType,
            Class<?> targetElementType,
            String elementConversionMethod) {

        StringBuilder code = new StringBuilder();

        String sourceRef = getCodeReference(sourceElementType);
        String targetRef = getCodeReference(targetElementType);

        String methodName = getUniqueMethodName(sourceElementType, targetElementType) + "_List";

        code.append("    /**\n");
        code.append("     * Converts List<").append(sourceRef).append("> to List<")
                .append(targetRef).append(">\n");
        code.append("     * @param source Source list\n");
        code.append("     * @return Converted list\n");
        code.append("     */\n");
        code.append("    private List<").append(targetRef).append("> ")
                .append(methodName).append("(List<").append(sourceRef).append("> source) {\n");
        code.append("        if (source == null) return null;\n\n");
        code.append("        List<").append(targetRef).append("> target = new ArrayList<>(source.size());\n");
        code.append("        for (").append(sourceRef).append(" item : source) {\n");

        if (sourceElementType.equals(targetElementType)) {
            code.append("            target.add(item);\n");
        } else {
            code.append("            target.add(").append(elementConversionMethod).append("(item));\n");
        }

        code.append("        }\n");
        code.append("        return target;\n");
        code.append("    }\n\n");

        return code.toString();
    }

    private boolean isNestedObject(Class<?> type) {
        if (type == null) return false;
        return !type.isPrimitive()
                && !type.getName().startsWith("java.lang")
                && !type.getName().startsWith("java.time")
                && !type.getName().startsWith("java.util")
                && !type.getName().startsWith("java.math")
                && !type.isEnum();
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