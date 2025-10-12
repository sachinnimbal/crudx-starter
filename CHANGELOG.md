# Changelog

All notable changes to the CrudX Framework will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [1.0.1] - Oct 12, 2025

### Added
- **Smart Field Validation** - Zero-configuration validation system
    - Auto-protects sensitive fields (`id`, `createdAt`, `createdBy`) from updates
    - New `@CrudXImmutable` annotation for marking fields as immutable
    - Automatic Jakarta Bean Validation integration (`@Email`, `@Size`, `@NotNull`, etc.)
    - Unique constraint validation on updates
    - Detailed validation error messages
- **Massive Batch Processing Enhancements**
    - Increased batch size limit from 1,000 to 100,000 records (1 Lakh)
    - Intelligent auto-chunking (500 records/chunk) for optimal memory usage
    - Real-time progress tracking with elapsed/estimated time logs
    - Performance metrics showing records/second statistics
    - Explicit GC hints and chunk cleanup for stable processing
- **Enhanced Performance Dashboard**
    - Thread-accurate memory profiling using ThreadMXBean
    - Sanity checks filtering unrealistic memory values (>3GB)
    - Separate memory call counting for accurate averages
    - New endpoint: `GET /crudx/performance/dashboard-data` - Combined dashboard data
    - New endpoint: `GET /crudx/performance/api-docs` - Interactive API documentation
    - New endpoint: `GET /crudx/performance/endpoints` - Visual endpoint reference
- **Swagger/OpenAPI Integration**
    - Auto-configured OpenAPI documentation (opt-in via `crudx.swagger.enabled=true`)
    - Accessible at `/swagger-ui.html` when enabled
    - Full API specification generation

### Changed
- **Service Bean Naming Convention** - Services now named `{entity}Service{database}` for multi-database support
    - Example: `employeeServicemysql`, `employeeServicemongodb`
    - Enables running same entity on multiple databases simultaneously
    - Backward compatible - autowiring still works
- **Memory Tracking** - Switched from heap-based to thread-local allocation measurement
    - Now shows realistic values: 50-500KB for typical requests
    - Large batches (10K): 100-200MB instead of unrealistic 2-5GB
- **Error Messages** - Improved clarity and helpfulness throughout framework
    - Better validation failure messages
    - Enhanced startup error messages with actionable solutions
    - Clearer database initialization error guidance

### Fixed
- **Memory Tracking Accuracy** - Fixed unrealistic memory measurements
    - Was measuring heap allocation, now measures per-thread allocation
    - Filters measurement errors (>3GB indicates faulty reading)
- **Batch Processing** - Fixed silent failures for large datasets
    - Now handles up to 100K records reliably
    - Proper error reporting per chunk
- **Service Bean Conflicts** - Fixed naming conflicts in multi-database setups
    - Database suffix prevents bean name collisions
- **Client Disconnect Handling** - Fixed broken pipe errors cluttering logs
    - Graceful handling of client disconnects (AsyncRequestNotUsableException, ClientAbortException)
    - Debug-level logging instead of error logs
- **Media Type Negotiation** - Fixed 406 Not Acceptable errors
    - Better handling of HttpMediaTypeNotAcceptableException
    - Returns JSON with proper error message
- **EndpointStats NullPointerException** - Fixed memory calculation crash
    - Proper null checks for memory statistics
    - Separate tracking for metrics with/without memory data

### Performance
- **Batch Processing**: 44% memory reduction (800MB → 450MB for 100K records)
- **Processing Speed**: 10% faster (2,000/sec → 2,200/sec)
- **Memory Accuracy**: Thread-local measurement now shows realistic values
- **Chunking**: Automatic optimization - zero configuration required

## [1.0.0] - Oct 06, 2025

### Added
- **Initial Release** - Zero-boilerplate CRUD framework for Spring Boot
- **Multi-Database Support**
    - MySQL with auto-increment ID generation
    - PostgreSQL with sequence-based IDs
    - MongoDB with flexible document storage
- **Auto-Service Generation** - Automatic service bean creation at startup
- **15+ Auto-Generated REST Endpoints** per controller
    - Create single/batch entities
    - Read with pagination and sorting
    - Update with partial field support
    - Delete single/batch with tracking
    - Count and existence checks
- **Smart Auto-Pagination** - Automatic pagination for large datasets (>1000 records)
- **Memory-Optimized Streaming** - Efficient handling of datasets >5000 records
- **Unique Constraints** - `@CrudXUniqueConstraint` annotation for data integrity
- **Automatic Audit Trail** - createdAt, updatedAt, createdBy, updatedBy tracking
- **Comprehensive Error Handling** - Meaningful error messages for all scenarios
- **Performance Monitoring Dashboard**
    - Real-time request tracking
    - Response time distribution
    - Per-endpoint statistics
    - Memory usage profiling
- **Lifecycle Hooks** - 12+ hooks for custom business logic
    - beforeCreate/afterCreate
    - beforeUpdate/afterUpdate
    - beforeDelete/afterDelete
    - beforeCreateBatch/afterCreateBatch
    - beforeDeleteBatch/afterDeleteBatch
    - afterFindById/afterFindAll/afterFindPaged
- **Type-Safe Generics** - Full Java generics support
- **Spring Boot 3.x** compatibility
- **Batch Operations** with duplicate handling
- **Automatic Database Creation** - Auto-creates databases if not exists (MySQL/PostgreSQL)

### Core Features
- **Entity Base Classes**
    - `CrudXMySQLEntity<ID>` - MySQL entities
    - `CrudXPostgreSQLEntity<ID>` - PostgreSQL entities
    - `CrudXMongoEntity<ID>` - MongoDB entities
- **Controller Base Class**
    - `CrudXController<T, ID>` - Generic REST controller
- **Service Interfaces**
    - `CrudXService<T, ID>` - Common CRUD operations
    - `CrudXSQLService<T, ID>` - SQL-specific operations
    - `CrudXMongoService<T, ID>` - MongoDB-specific operations
- **Response Wrappers**
    - `ApiResponse<T>` - Standardized API responses
    - `BatchResult<T>` - Batch operation results
    - `PageResponse<T>` - Pagination metadata
- **Exception Handling**
    - `EntityNotFoundException` - Resource not found errors
    - `DuplicateEntityException` - Unique constraint violations
    - Global exception handler with proper HTTP status codes

### Performance
- **90% less code** compared to traditional Spring Boot CRUD
- **Sub-100ms** response times for most operations
- **450ms** batch insert for 1000 records
- **Memory-efficient** cursor-based streaming for large datasets

### Documentation
- Comprehensive README with quick start guide
- Detailed API documentation
- Help guide with troubleshooting
- Example projects repository

---

## Release Notes Format

### Version Format
- **Major.Minor.Patch** (e.g., 1.0.1)
- **Major**: Breaking changes, major new features
- **Minor**: New features, backward compatible
- **Patch**: Bug fixes, performance improvements

### Categories
- **Added**: New features
- **Changed**: Changes in existing functionality
- **Deprecated**: Soon-to-be removed features
- **Removed**: Removed features
- **Fixed**: Bug fixes
- **Security**: Security vulnerability fixes
- **Performance**: Performance improvements

---

## Upcoming Releases

### [1.1.0] - Planned Q1 2025
- DTO Support with automatic Entity ↔ DTO mapping
- Oracle Database support
- MariaDB support
- Comprehensive test utilities
- Query Builder API

### [1.2.0] - Planned Q2 2026
- Built-in Authentication & Authorization
- Data Export (CSV, Excel, PDF)
- Advanced Search & Filtering
- GraphQL Support
- Advanced Analytics

### Future Roadmap
- Event Sourcing Support
- Caching Layer (Redis/Hazelcast)
- WebSocket Support
- Multi-tenancy Support

---

## Links
- [GitHub Repository](https://github.com/sachinnimbal/crudx-starter)
- [Maven Central](https://search.maven.org/artifact/io.github.sachinnimbal/crudx-starter)
- [Documentation](https://github.com/sachinnimbal/crudx-starter/blob/main/README.md)
- [Examples](https://github.com/sachinnimbal/crudx-examples)

## Support
- Report issues: [GitHub Issues](https://github.com/sachinnimbal/crudx-starter/issues)
- Email: sachinnimbal9@gmail.com