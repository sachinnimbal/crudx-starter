package io.github.sachinnimbal.crudx.core.processor;

import io.github.sachinnimbal.crudx.core.annotations.dto.CrudXField;
import io.github.sachinnimbal.crudx.core.annotations.dto.CrudXRequestDto;
import io.github.sachinnimbal.crudx.core.annotations.dto.CrudXResponseDto;

import javax.annotation.processing.*;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.Element;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.MirroredTypeException;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.Elements;
import javax.lang.model.util.Types;
import javax.tools.Diagnostic;
import java.util.HashSet;
import java.util.Set;

@SupportedAnnotationTypes({
        "io.github.sachinnimbal.crudx.core.annotations.dto.CrudXRequestDto",
        "io.github.sachinnimbal.crudx.core.annotations.dto.CrudXResponseDto"
})
@SupportedSourceVersion(SourceVersion.RELEASE_17)
public class CrudXDtoProcessor extends AbstractProcessor {

    private Messager messager;
    private Elements elementUtils;
    private Types typeUtils;

    @Override
    public synchronized void init(ProcessingEnvironment processingEnv) {
        super.init(processingEnv);
        this.messager = processingEnv.getMessager();
        this.elementUtils = processingEnv.getElementUtils();
        this.typeUtils = processingEnv.getTypeUtils();
    }

    @Override
    public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {
        // Validate @CrudXResponseDto
        for (Element element : roundEnv.getElementsAnnotatedWith(CrudXResponseDto.class)) {
            if (element instanceof TypeElement typeElement) {
                validateResponseDto(typeElement);
            }
        }

        // Validate @CrudXRequestDto
        for (Element element : roundEnv.getElementsAnnotatedWith(CrudXRequestDto.class)) {
            if (element instanceof TypeElement typeElement) {
                validateRequestDto(typeElement);
            }
        }

        return true;
    }

    private void validateResponseDto(TypeElement dtoElement) {
        CrudXResponseDto annotation = dtoElement.getAnnotation(CrudXResponseDto.class);
        TypeMirror entityTypeMirror = null;

        // Extract entity class using MirroredTypeException
        try {
            annotation.entity();
        } catch (MirroredTypeException mte) {
            entityTypeMirror = mte.getTypeMirror();
        }

        if (entityTypeMirror == null) {
            error(dtoElement, "Cannot resolve entity class in @CrudXResponseDto");
            return;
        }

        TypeElement entityElement = (TypeElement) typeUtils.asElement(entityTypeMirror);
        if (entityElement == null) {
            error(dtoElement, "Cannot resolve entity TypeElement in @CrudXResponseDto");
            return;
        }

        // Get all DTO fields
        Set<String> dtoFieldNames = new HashSet<>();
        for (Element enclosedElement : dtoElement.getEnclosedElements()) {
            if (enclosedElement instanceof VariableElement varElement) {
                if (!varElement.getModifiers().contains(Modifier.STATIC)) {
                    String fieldName = varElement.getSimpleName().toString();

                    // Check for @CrudXField mapping
                    CrudXField fieldAnnotation = varElement.getAnnotation(CrudXField.class);
                    if (fieldAnnotation != null && !fieldAnnotation.ignore() &&
                            !fieldAnnotation.value().isEmpty()) {
                        dtoFieldNames.add(fieldAnnotation.value());
                    } else if (fieldAnnotation == null || !fieldAnnotation.ignore()) {
                        dtoFieldNames.add(fieldName);
                    }
                }
            }
        }

        // Get all entity fields
        Set<String> entityFieldNames = getEntityFieldNames(entityElement);

        // Validate: all DTO fields must exist in entity
        for (String dtoField : dtoFieldNames) {
            if (!entityFieldNames.contains(dtoField) && !isComputedField(dtoElement, dtoField)) {
                error(dtoElement,
                        String.format("Field '%s' in @CrudXResponseDto '%s' does not exist in entity '%s'. " +
                                        "Available fields: %s",
                                dtoField, dtoElement.getSimpleName(), entityElement.getSimpleName(),
                                entityFieldNames));
            }
        }

        // Warn about unused entity fields (optional but helpful)
        for (String entityField : entityFieldNames) {
            if (!dtoFieldNames.contains(entityField) && !isPrimitiveId(entityField)) {
                warn(dtoElement,
                        String.format("Entity field '%s' is not mapped in @CrudXResponseDto '%s'",
                                entityField, dtoElement.getSimpleName()));
            }
        }
    }

    private void validateRequestDto(TypeElement dtoElement) {
        CrudXRequestDto annotation = dtoElement.getAnnotation(CrudXRequestDto.class);
        TypeMirror entityTypeMirror = null;

        try {
            annotation.entity();
        } catch (MirroredTypeException mte) {
            entityTypeMirror = mte.getTypeMirror();
        }

        if (entityTypeMirror == null) {
            error(dtoElement, "Cannot resolve entity class in @CrudXRequestDto");
            return;
        }

        TypeElement entityElement = (TypeElement) typeUtils.asElement(entityTypeMirror);
        if (entityElement == null) {
            error(dtoElement, "Cannot resolve entity TypeElement in @CrudXRequestDto");
            return;
        }

        // Similar validation as ResponseDto
        Set<String> dtoFieldNames = new HashSet<>();
        for (Element enclosedElement : dtoElement.getEnclosedElements()) {
            if (enclosedElement instanceof VariableElement varElement) {
                if (!varElement.getModifiers().contains(Modifier.STATIC)) {
                    String fieldName = varElement.getSimpleName().toString();

                    CrudXField fieldAnnotation = varElement.getAnnotation(CrudXField.class);
                    if (fieldAnnotation != null && !fieldAnnotation.ignore() &&
                            !fieldAnnotation.value().isEmpty()) {
                        dtoFieldNames.add(fieldAnnotation.value());
                    } else if (fieldAnnotation == null || !fieldAnnotation.ignore()) {
                        dtoFieldNames.add(fieldName);
                    }
                }
            }
        }

        Set<String> entityFieldNames = getEntityFieldNames(entityElement);

        for (String dtoField : dtoFieldNames) {
            if (!entityFieldNames.contains(dtoField)) {
                error(dtoElement,
                        String.format("Field '%s' in @CrudXRequestDto '%s' does not exist in entity '%s'",
                                dtoField, dtoElement.getSimpleName(), entityElement.getSimpleName()));
            }
        }
    }

    private Set<String> getEntityFieldNames(TypeElement entityElement) {
        Set<String> fieldNames = new HashSet<>();

        // Get fields from this class and all superclasses
        TypeElement currentElement = entityElement;
        while (currentElement != null) {
            for (Element enclosedElement : currentElement.getEnclosedElements()) {
                if (enclosedElement instanceof VariableElement varElement) {
                    if (!varElement.getModifiers().contains(Modifier.STATIC)) {
                        fieldNames.add(varElement.getSimpleName().toString());
                    }
                }
            }

            // Move to superclass
            TypeMirror superclass = currentElement.getSuperclass();
            if (superclass instanceof DeclaredType) {
                currentElement = (TypeElement) typeUtils.asElement(superclass);
            } else {
                currentElement = null;
            }
        }

        return fieldNames;
    }

    private boolean isComputedField(TypeElement dtoElement, String fieldName) {
        for (Element enclosedElement : dtoElement.getEnclosedElements()) {
            if (enclosedElement instanceof VariableElement varElement) {
                if (varElement.getSimpleName().toString().equals(fieldName)) {
                    return varElement.getAnnotation(
                            io.github.sachinnimbal.crudx.core.annotations.dto.CrudXComputed.class) != null;
                }
            }
        }
        return false;
    }

    private boolean isPrimitiveId(String fieldName) {
        return fieldName.equals("id") || fieldName.equals("createdAt") ||
                fieldName.equals("updatedAt") || fieldName.equals("version");
    }

    private void error(Element element, String message) {
        messager.printMessage(Diagnostic.Kind.ERROR, message, element);
    }

    private void warn(Element element, String message) {
        messager.printMessage(Diagnostic.Kind.WARNING, message, element);
    }
}