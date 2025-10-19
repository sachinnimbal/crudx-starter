package io.github.sachinnimbal.crudx.core.enums;

/**
 * CRUD operations for DTO mapping.
 *
 * @author Sachin Nimbal
 * @since 1.0.2
 */
public enum CrudXOperation {
    CREATE,
    BATCH_CREATE,
    UPDATE,
    BATCH_UPDATE,
    DELETE,
    BATCH_DELETE,
    GET_ID,
    GET_ALL,
    GET_PAGED,
    COUNT,
    EXISTS;

    /**
     * Check if operation is a mutation (write).
     */
    public boolean isMutation() {
        return this == CREATE || this == BATCH_CREATE ||
                this == UPDATE || this == BATCH_UPDATE ||
                this == DELETE || this == BATCH_DELETE;
    }

    /**
     * Check if operation is a query (read).
     */
    public boolean isQuery() {
        return this == GET_ID || this == GET_ALL ||
                this == GET_PAGED || this == COUNT || this == EXISTS;
    }

    /**
     * Check if operation is batch.
     */
    public boolean isBatch() {
        return this == BATCH_CREATE || this == BATCH_UPDATE || this == BATCH_DELETE;
    }
}