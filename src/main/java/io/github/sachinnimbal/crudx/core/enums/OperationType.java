package io.github.sachinnimbal.crudx.core.enums;

public enum OperationType {
    // CREATE operations
    CREATE,              // POST /api/{entity}
    BATCH_CREATE,        // POST /api/{entity}/batch

    // READ operations
    GET_BY_ID,          // GET /api/{entity}/{id}
    GET_ALL,            // GET /api/{entity}
    GET_PAGED,          // GET /api/{entity}/paged

    // UPDATE operations
    UPDATE,             // PATCH /api/{entity}/{id}
    DELETE
}
