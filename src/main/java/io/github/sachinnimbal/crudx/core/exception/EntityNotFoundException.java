package io.github.sachinnimbal.crudx.core.exception;

public class EntityNotFoundException extends RuntimeException {
    public EntityNotFoundException(String entityName, Object id) {
        super(String.format("%s not found with id: %s", entityName, id));
    }

    public EntityNotFoundException(String message) {
        super(message);
    }
}
