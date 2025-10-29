package io.github.sachinnimbal.crudx.core.enums;

public enum MapperMode {
    NONE,           // No DTO mapping
    COMPILED,       // Using compile-time generated mapper (FASTEST)
    RUNTIME         // Using runtime mapper generator (FALLBACK)
}