package io.github.sachinnimbal.crudx.core.enums;

public enum CrudXOperation {
    CREATE,
    BATCH_CREATE,
    UPDATE,
    BATCH_UPDATE,
    DELETE,
    BATCH_DELETE,
    GET_ID,
    GET_ALL,
    GET_PAGED;

    public boolean isMutation() {
        return this == CREATE || this == BATCH_CREATE ||
                this == UPDATE || this == BATCH_UPDATE ||
                this == DELETE || this == BATCH_DELETE;
    }

    public boolean isQuery() {
        return this == GET_ID || this == GET_ALL ||
                this == GET_PAGED;
    }

    public boolean isBatch() {
        return this == BATCH_CREATE || this == BATCH_UPDATE || this == BATCH_DELETE;
    }
}
