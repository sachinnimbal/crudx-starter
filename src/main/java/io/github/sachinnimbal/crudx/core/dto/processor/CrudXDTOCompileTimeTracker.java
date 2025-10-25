package io.github.sachinnimbal.crudx.core.dto.processor;

import com.google.auto.service.AutoService;
import io.github.sachinnimbal.crudx.core.dto.annotations.CrudXRequest;
import io.github.sachinnimbal.crudx.core.dto.annotations.CrudXResponse;

import javax.annotation.processing.*;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.Element;
import javax.lang.model.element.TypeElement;
import javax.tools.Diagnostic;
import javax.tools.FileObject;
import javax.tools.StandardLocation;
import java.io.IOException;
import java.io.Writer;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Annotation processor that generates a compile-time DTO usage report
 * This runs during compilation and creates a metadata file for runtime tracking
 */
@AutoService(Processor.class)
@SupportedAnnotationTypes({
        "io.github.sachinnimbal.crudx.core.dto.annotations.CrudXRequest",
        "io.github.sachinnimbal.crudx.core.dto.annotations.CrudXResponse"
})
@SupportedSourceVersion(SourceVersion.RELEASE_21)
public class CrudXDTOCompileTimeTracker extends AbstractProcessor {

    private final Map<String, DTOMetadata> dtoMetadata = new LinkedHashMap<>();
    private boolean processed = false;

    @Override
    public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {
        if (processed || annotations.isEmpty()) {
            return false;
        }

        // Collect @CrudXRequest DTOs
        for (Element element : roundEnv.getElementsAnnotatedWith(CrudXRequest.class)) {
            if (element instanceof TypeElement dtoElement) {
                CrudXRequest annotation = element.getAnnotation(CrudXRequest.class);
                String entityTypeName = extractEntityTypeName(annotation);

                DTOMetadata metadata = dtoMetadata.computeIfAbsent(
                        entityTypeName,
                        k -> new DTOMetadata(entityTypeName)
                );

                metadata.requestDTOs.add(dtoElement.getQualifiedName().toString());
                metadata.requestDTOSimpleNames.add(dtoElement.getSimpleName().toString());
            }
        }

        // Collect @CrudXResponse DTOs
        for (Element element : roundEnv.getElementsAnnotatedWith(CrudXResponse.class)) {
            if (element instanceof TypeElement dtoElement) {
                CrudXResponse annotation = element.getAnnotation(CrudXResponse.class);
                String entityTypeName = extractEntityTypeName(annotation);

                DTOMetadata metadata = dtoMetadata.computeIfAbsent(
                        entityTypeName,
                        k -> new DTOMetadata(entityTypeName)
                );

                metadata.responseDTOs.add(dtoElement.getQualifiedName().toString());
                metadata.responseDTOSimpleNames.add(dtoElement.getSimpleName().toString());
            }
        }

        // Generate metadata file
        if (!dtoMetadata.isEmpty()) {
            generateMetadataFile();
            processed = true;
        }

        return false;
    }

    private String extractEntityTypeName(CrudXRequest annotation) {
        try {
            annotation.value(); // This will throw MirroredTypeException
        } catch (javax.lang.model.type.MirroredTypeException mte) {
            return mte.getTypeMirror().toString();
        }
        return "Unknown";
    }

    private String extractEntityTypeName(CrudXResponse annotation) {
        try {
            annotation.value(); // This will throw MirroredTypeException
        } catch (javax.lang.model.type.MirroredTypeException mte) {
            return mte.getTypeMirror().toString();
        }
        return "Unknown";
    }

    private void generateMetadataFile() {
        try {
            // Generate properties file in META-INF
            FileObject resource = processingEnv.getFiler().createResource(
                    StandardLocation.CLASS_OUTPUT,
                    "",
                    "META-INF/crudx-dto-metadata.properties"
            );

            try (Writer writer = resource.openWriter()) {
                writer.write("# CrudX DTO Compile-Time Metadata\n");
                writer.write("# Generated at: " + java.time.Instant.now() + "\n");
                writer.write("# Total entities with DTOs: " + dtoMetadata.size() + "\n\n");

                for (Map.Entry<String, DTOMetadata> entry : dtoMetadata.entrySet()) {
                    String entityName = entry.getKey();
                    DTOMetadata metadata = entry.getValue();

                    writer.write("# Entity: " + entityName + "\n");

                    if (!metadata.requestDTOs.isEmpty()) {
                        writer.write(entityName + ".request.count=" + metadata.requestDTOs.size() + "\n");
                        writer.write(entityName + ".request.dtos=" + String.join(",", metadata.requestDTOs) + "\n");
                        writer.write(entityName + ".request.names=" + String.join(",", metadata.requestDTOSimpleNames) + "\n");
                    }

                    if (!metadata.responseDTOs.isEmpty()) {
                        writer.write(entityName + ".response.count=" + metadata.responseDTOs.size() + "\n");
                        writer.write(entityName + ".response.dtos=" + String.join(",", metadata.responseDTOs) + "\n");
                        writer.write(entityName + ".response.names=" + String.join(",", metadata.responseDTOSimpleNames) + "\n");
                    }

                    writer.write("\n");
                }
            }

            processingEnv.getMessager().printMessage(
                    Diagnostic.Kind.NOTE,
                    "✓ Generated CrudX DTO metadata for " + dtoMetadata.size() + " entities"
            );

        } catch (IOException e) {
            processingEnv.getMessager().printMessage(
                    Diagnostic.Kind.ERROR,
                    "Failed to generate DTO metadata: " + e.getMessage()
            );
        }
    }

    private static class DTOMetadata {
        final String entityName;
        final java.util.List<String> requestDTOs = new java.util.ArrayList<>();
        final java.util.List<String> requestDTOSimpleNames = new java.util.ArrayList<>();
        final java.util.List<String> responseDTOs = new java.util.ArrayList<>();
        final java.util.List<String> responseDTOSimpleNames = new java.util.ArrayList<>();

        DTOMetadata(String entityName) {
            this.entityName = entityName;
        }
    }
}