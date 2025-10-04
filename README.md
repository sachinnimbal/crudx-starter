# CrudX Framework

**Version:** 0.0.1  
**Author:** Sachin Nimbal  
**Location:** Bangalore, India

## Overview

CrudX is a lightweight, zero-boilerplate Spring Boot framework that automatically generates REST APIs and services for your entities. Simply extend base classes and get full CRUD operations with pagination, batch processing, and validation - no repository or service code needed.

## Key Features

- **Zero Boilerplate**: No need to write repository or service classes
- **Auto-Generated REST APIs**: Complete CRUD endpoints with single controller extension
- **Multi-Database Support**: MySQL, PostgreSQL, and MongoDB
- **Built-in Pagination**: Efficient handling of large datasets
- **Batch Operations**: Create and delete multiple records at once
- **Unique Constraints**: Custom validation annotations
- **Performance Tracking**: Execution time logging for all operations
- **Auto-Auditing**: Created/updated timestamps and user tracking
- **Smart Error Handling**: Global exception handling with detailed responses

## Quick Start

### 1. Add Dependency

**Gradle:**
```gradle
dependencies {
    implementation 'io.github.sachinnimbal:crudx-core:0.0.1'
    
    // Choose ONE database
    runtimeOnly 'com.mysql:mysql-connector-j:8.3.0'
    // OR
    runtimeOnly 'org.postgresql:postgresql:42.7.3'
    // OR
    implementation 'org.springframework.boot:spring-boot-starter-data-mongodb'
}
```

**Maven:**
```xml
<dependencies>
    <dependency>
        <groupId>io.github.sachinnimbal</groupId>
        <artifactId>crudx-core</artifactId>
        <version>0.0.1</version>
    </dependency>
    
    <!-- Choose ONE database -->
    <dependency>
        <groupId>com.mysql</groupId>
        <artifactId>mysql-connector-j</artifactId>
        <version>8.3.0</version>
        <scope>runtime</scope>
    </dependency>
</dependencies>
```

### 2. Enable CrudX

```java
@SpringBootApplication
@CrudX
public class Application {
    public static void main(String[] args) {
        SpringApplication.run(Application.class, args);
    }
}
```

### 3. Configure Database

**For MySQL (application.yml):**
```yaml
spring:
  datasource:
    url: jdbc:mysql://localhost:3306/crudx-db
    username: root
    password: password
    driver-class-name: com.mysql.cj.jdbc.Driver
  jpa:
    hibernate:
      ddl-auto: update
```

**For MongoDB (application.yml):**
```yaml
spring:
  data:
    mongodb:
      uri: mongodb://localhost:27017/crudx-db
```

### 4. Create Entity

**For MySQL/PostgreSQL:**
```java
@Entity
@Table(name = "employees")
@CrudXUniqueConstraint(
    fields = {"email"},
    message = "Employee with this email already exists"
)
public class Employee extends CrudXMySQLEntity<Long> {
    
    @Column(nullable = false)
    private String name;
    
    @Column(nullable = false, unique = true)
    private String email;
    
    private String department;
    
    @Column(name = "phone_number")
    private String phoneNumber;
    
    // Getters and setters
}
```

**For MongoDB:**
```java
@Document(collection = "employees")
@CrudXUniqueConstraint(
    fields = {"email"},
    message = "Employee with this email already exists"
)
public class Employee extends CrudXMongoEntity<String> {
    
    private String name;
    private String email;
    private String department;
    private String phoneNumber;
    
    // Getters and setters
}
```

### 5. Create Controller

```java
@RestController
@RequestMapping("/api/employees")
public class EmployeeController extends CrudXController<Employee, Long> {
    // That's it! All CRUD endpoints are auto-generated
}
```

## Available Endpoints

Your controller automatically provides these REST endpoints:

| Method | Endpoint | Description |
|--------|----------|-------------|
| POST | `/api/employees` | Create single employee |
| POST | `/api/employees/batch` | Create multiple employees |
| GET | `/api/employees/{id}` | Get employee by ID |
| GET | `/api/employees` | Get all employees (auto-paginated for large datasets) |
| GET | `/api/employees/paged` | Get paginated employees |
| GET | `/api/employees/count` | Get total count |
| GET | `/api/employees/exists/{id}` | Check if employee exists |
| PATCH | `/api/employees/{id}` | Update employee |
| DELETE | `/api/employees/{id}` | Delete employee |
| DELETE | `/api/employees/batch` | Delete multiple employees |

## Advantages

### 1. **Rapid Development**
- Build REST APIs in minutes, not hours
- No boilerplate code for repositories or services
- Focus on business logic, not infrastructure

### 2. **Production Ready**
- Built-in error handling and validation
- Performance monitoring and logging
- Database connection management

### 3. **Scalable Architecture**
- Efficient batch operations
- Smart pagination for large datasets
- Memory-optimized data processing

### 4. **Developer Friendly**
- Clean, intuitive API design
- Comprehensive logging
- Clear error messages

### 5. **Flexible**
- Support for multiple databases
- Customizable unique constraints
- Lifecycle hooks for custom logic

### 6. **Type Safe**
- Full Java type safety
- Compile-time error checking
- IDE auto-completion support

## Project Structure

```
src/main/java/com/example/
├── Application.java              # Main application with @CrudX
├── model/
│   └── Employee.java            # Entity extending CrudXMySQLEntity
└── controller/
    └── EmployeeController.java  # Controller extending CrudXController
```

## Requirements

- Java 17 or higher
- Spring Boot 3.x
- One of: MySQL 8.x, PostgreSQL 12+, or MongoDB 4.4+

## Support

For issues, questions, or contributions:
- GitHub: https://github.com/sachinnimbal/crudx-examples
- Documentation: https://github.com/sachinnimbal/crudx-examples/wiki

## License

Apache License 2.0

---

**Start building amazing APIs today!** 🚀