# CrudX Framework - Help Guide

## Table of Contents
1. [Getting Started](#getting-started)
2. [Database Configuration](#database-configuration)
3. [Creating Entities](#creating-entities)
4. [Creating Controllers](#creating-controllers)
5. [Advanced Features](#advanced-features)
6. [Troubleshooting](#troubleshooting)
7. [Best Practices](#best-practices)

---

## Getting Started

### Step 1: Add Dependencies

Choose your database and add the appropriate dependency:

**For MySQL:**
```gradle
dependencies {
    implementation 'io.github.sachinnimbal:crudx-core:0.0.1'
    implementation 'org.springframework.boot:spring-boot-starter-data-jpa'
    runtimeOnly 'com.mysql:mysql-connector-j:8.3.0'
}
```

**For PostgreSQL:**
```gradle
dependencies {
    implementation 'io.github.sachinnimbal:crudx-core:0.0.1'
    implementation 'org.springframework.boot:spring-boot-starter-data-jpa'
    runtimeOnly 'org.postgresql:postgresql:42.7.3'
}
```

**For MongoDB:**
```gradle
dependencies {
    implementation 'io.github.sachinnimbal:crudx-core:0.0.1'
    implementation 'org.springframework.boot:spring-boot-starter-data-mongodb'
}
```

### Step 2: Enable CrudX

Add `@CrudX` annotation to your main application class:

```java
package com.company.myapp;

import io.github.sachinnimbal.crudx.core.annotations.CrudX;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
@CrudX
public class MyApplication {
    public static void main(String[] args) {
        SpringApplication.run(MyApplication.class, args);
    }
}
```

---

## Database Configuration

### MySQL Configuration

**application.yml:**
```yaml
spring:
  datasource:
    url: jdbc:mysql://localhost:3306/company_db
    username: root
    password: root123
    driver-class-name: com.mysql.cj.jdbc.Driver
  jpa:
    hibernate:
      ddl-auto: update
    show-sql: true
    properties:
      hibernate:
        format_sql: true
        dialect: org.hibernate.dialect.MySQLDialect
```

### PostgreSQL Configuration

**application.yml:**
```yaml
spring:
  datasource:
    url: jdbc:postgresql://localhost:5432/company_db
    username: postgres
    password: postgres123
    driver-class-name: org.postgresql.Driver
  jpa:
    hibernate:
      ddl-auto: update
    show-sql: true
    properties:
      hibernate:
        format_sql: true
        dialect: org.hibernate.dialect.PostgreSQLDialect
```

### MongoDB Configuration

**application.yml:**
```yaml
spring:
  data:
    mongodb:
      uri: mongodb://localhost:27017/company_db
      # OR separate configuration
      host: localhost
      port: 27017
      database: company_db
      username: admin
      password: admin123
```

### Auto-Create Database

CrudX can automatically create databases if they don't exist:

```yaml
crudx:
  database:
    auto-create: true  # default: true
```

---

## Creating Entities

### MySQL/PostgreSQL Entities

**Example 1: Simple Employee Entity**
```java
package com.company.myapp.model;

import io.github.sachinnimbal.crudx.core.model.CrudXMySQLEntity;
import jakarta.persistence.*;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@Entity
@Table(name = "employees")
public class Employee extends CrudXMySQLEntity<Long> {

    @Column(nullable = false, length = 100)
    private String name;

    @Column(nullable = false, unique = true, length = 100)
    private String email;

    @Column(length = 50)
    private String department;

    @Column(name = "phone_number", length = 15)
    private String phoneNumber;

    private Double salary;
}
```

**Example 2: Product Entity with Unique Constraint**
```java
package com.company.myapp.model;

import io.github.sachinnimbal.crudx.core.annotations.CrudXUniqueConstraint;
import io.github.sachinnimbal.crudx.core.model.CrudXMySQLEntity;
import jakarta.persistence.*;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@Entity
@Table(name = "products")
@CrudXUniqueConstraint(
        fields = {"sku"},
        message = "Product with this SKU already exists"
)
public class Product extends CrudXMySQLEntity<Long> {

    @Column(nullable = false)
    private String name;

    @Column(nullable = false, unique = true)
    private String sku;

    @Column(columnDefinition = "TEXT")
    private String description;

    private Double price;

    private Integer stock;

    @Column(length = 50)
    private String category;
}
```

**Example 3: Customer with Multiple Unique Constraints**
```java
package com.company.myapp.model;

import io.github.sachinnimbal.crudx.core.annotations.CrudXUniqueConstraint;
import io.github.sachinnimbal.crudx.core.annotations.CrudXUniqueConstraints;
import io.github.sachinnimbal.crudx.core.model.CrudXPostgreSQLEntity;
import jakarta.persistence.*;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@Entity
@Table(name = "customers")
@CrudXUniqueConstraints({
        @CrudXUniqueConstraint(
                fields = {"email"},
                message = "Customer with this email already exists"
        ),
        @CrudXUniqueConstraint(
                fields = {"phoneNumber"},
                name = "uk_customer_phone",
                message = "Customer with this phone number already exists"
        ),
        @CrudXUniqueConstraint(
                fields = {"aadharNumber"},
                name = "uk_customer_aadhar",
                message = "Customer with this Aadhar number already exists"
        )
})
public class Customer extends CrudXPostgreSQLEntity<Long> {

    @Column(nullable = false)
    private String fullName;

    @Column(nullable = false, unique = true)
    private String email;

    @Column(name = "phone_number", unique = true)
    private String phoneNumber;

    @Column(name = "aadhar_number", unique = true, length = 12)
    private String aadharNumber;

    private String address;

    private String city;

    @Column(name = "pin_code")
    private String pinCode;
}
```

### MongoDB Entities

**Example 1: Simple User Entity**
```java
package com.company.myapp.model;

import io.github.sachinnimbal.crudx.core.model.CrudXMongoEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.springframework.data.mongodb.core.mapping.Document;

@Data
@EqualsAndHashCode(callSuper = true)
@Document(collection = "users")
public class User extends CrudXMongoEntity<String> {
    
    private String username;
    private String email;
    private String password;
    private String role;
    private Boolean active;
}
```

**Example 2: Order Entity with Nested Objects**
```java
package com.company.myapp.model;

import io.github.sachinnimbal.crudx.core.annotations.CrudXUniqueConstraint;
import io.github.sachinnimbal.crudx.core.model.CrudXMongoEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.springframework.data.mongodb.core.mapping.Document;

import java.util.List;

@Data
@EqualsAndHashCode(callSuper = true)
@Document(collection = "orders")
@CrudXUniqueConstraint(
    fields = {"orderNumber"},
    message = "Order with this number already exists"
)
public class Order extends CrudXMongoEntity<String> {
    
    private String orderNumber;
    private String customerId;
    private List<OrderItem> items;
    private Double totalAmount;
    private String status;
    private String deliveryAddress;
    
    @Data
    public static class OrderItem {
        private String productId;
        private String productName;
        private Integer quantity;
        private Double price;
    }
}
```

---

## Creating Controllers

### Basic Controller

```java
package com.company.myapp.controller;

import com.company.myapp.model.Employee;
import io.github.sachinnimbal.crudx.web.CrudXController;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/employees")
public class EmployeeController extends CrudXController<Employee, Long> {
    // All CRUD endpoints are auto-generated!
}
```

### Controller with Lifecycle Hooks

```java
package com.company.myapp.controller;

import com.company.myapp.model.Product;
import io.github.sachinnimbal.crudx.web.CrudXController;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/products")
public class ProductController extends CrudXController<Product, Long> {
    
    @Override
    protected void beforeCreate(Product product) {
        log.info("Validating product before creation: {}", product.getName());
        // Custom validation logic
        if (product.getPrice() < 0) {
            throw new IllegalArgumentException("Price cannot be negative");
        }
    }
    
    @Override
    protected void afterCreate(Product product) {
        log.info("Product created successfully with ID: {}", product.getId());
        // Send notification, update cache, etc.
    }
    
    @Override
    protected void beforeUpdate(Long id, Map<String, Object> updates, Product existingProduct) {
        log.info("Updating product ID: {}", id);
        // Check business rules
        if (updates.containsKey("stock") && (Integer) updates.get("stock") < 0) {
            throw new IllegalArgumentException("Stock cannot be negative");
        }
    }
    
    @Override
    protected void afterUpdate(Product updatedProduct, Product oldProduct) {
        log.info("Product updated: {} -> {}", oldProduct.getName(), updatedProduct.getName());
        // Sync with inventory system
    }
    
    @Override
    protected void beforeDelete(Long id, Product product) {
        log.warn("Deleting product: {}", product.getName());
        // Check if product has active orders
    }
}
```

---

## Advanced Features

### 1. Unique Constraints

Single field constraint:
```java
@CrudXUniqueConstraint(
    fields = {"email"},
    message = "Email already exists"
)
```

Multiple fields constraint:
```java
@CrudXUniqueConstraint(
    fields = {"firstName", "lastName", "dateOfBirth"},
    name = "uk_person_identity",
    message = "Person with same name and birth date already exists"
)
```

Multiple separate constraints (using @Repeatable):
```java
@CrudXUniqueConstraint(
    fields = {"email"},
    message = "Email already exists"
)
@CrudXUniqueConstraint(
    fields = {"phoneNumber"},
    message = "Phone number already exists"
)
```

Alternative: Using @CrudXUniqueConstraints container:
```java
@CrudXUniqueConstraints({
    @CrudXUniqueConstraint(
        fields = {"email"},
        message = "Email already exists"
    ),
    @CrudXUniqueConstraint(
        fields = {"phoneNumber"},
        message = "Phone number already exists"
    ),
    @CrudXUniqueConstraint(
        fields = {"username"},
        message = "Username already exists"
    )
})
```

**Note:** Both approaches work identically. The `@CrudXUniqueConstraints` (plural) is the container annotation that holds multiple `@CrudXUniqueConstraint` annotations. Use whichever syntax you prefer.

### 2. Audit Fields

All entities automatically include audit fields:
- `createdAt` - Timestamp of creation
- `createdBy` - User who created (can be set manually)
- `updatedAt` - Timestamp of last update
- `updatedBy` - User who last updated

```java
// Access audit information
Employee emp = employeeService.findById(1L);
System.out.println("Created: " + emp.getAudit().getCreatedAt());
System.out.println("Last Updated: " + emp.getAudit().getUpdatedAt());
```

### 3. Batch Operations

CrudX provides efficient batch operations with skip functionality:

```java
// Create multiple employees
List<Employee> employees = Arrays.asList(
    new Employee("Rahul Sharma", "rahul@company.com"),
    new Employee("Priya Patel", "priya@company.com"),
    new Employee("Amit Kumar", "amit@company.com")
);

// Skip duplicates
POST /api/employees/batch?skipDuplicates=true

// Fail on first duplicate
POST /api/employees/batch?skipDuplicates=false
```

### 4. Custom Service Access

If you need direct service access:

```java
@RestController
@RequestMapping("/api/custom")
public class CustomController extends CrudXController<Employee, Long> {
    
    @GetMapping("/department/{dept}")
    public List<Employee> getByDepartment(@PathVariable String dept) {
        // Access the auto-generated service
        List<Employee> allEmployees = crudService.findAll();
        return allEmployees.stream()
            .filter(e -> dept.equals(e.getDepartment()))
            .collect(Collectors.toList());
    }
}
```

---

## Troubleshooting

### Issue 1: Service Bean Not Found

**Error:**
```
Service bean not found: employeeService
```

**Solution:**
- Ensure entity extends `CrudXMySQLEntity`, `CrudXPostgreSQLEntity`, or `CrudXMongoEntity`
- Verify `@CrudX` annotation is on main application class
- Check entity class is in a package scanned by Spring

### Issue 2: Database Not Created

**Error:**
```
Database 'company_db' does not exist
```

**Solution:**
- Enable auto-creation:
```yaml
crudx:
  database:
    auto-create: true
```
- Or manually create database

### Issue 3: Unique Constraint Violation

**Error:**
```
Duplicate entry found for fields: email
```

**Solution:**
- This is expected behavior when creating duplicate entries
- Use `skipDuplicates=true` in batch operations to skip duplicates
- Ensure unique values before creation

### Issue 4: Large Dataset Performance

**Issue:** Slow response for large datasets

**Solution:**
- Use pagination endpoint: `/api/employees/paged?page=0&size=20`
- CrudX auto-paginates when dataset exceeds 1000 records
- Adjust threshold in your controller if needed

---

## Best Practices

### 1. Entity Design

```java
// ✅ Good: Clear naming and proper annotations
@Entity
@Table(name = "employees", indexes = {
    @Index(name = "idx_department", columnList = "department"),
    @Index(name = "idx_email", columnList = "email")
})
public class Employee extends CrudXMySQLEntity<Long> {
    @Column(nullable = false, length = 100)
    private String name;
    
    @Column(nullable = false, unique = true)
    private String email;
}

// ❌ Bad: No constraints, no indexes
public class Employee extends CrudXMySQLEntity<Long> {
    private String name;
    private String email;
}
```

### 2. Controller Organization

```java
// ✅ Good: Clear endpoint structure
@RestController
@RequestMapping("/api/v1/employees")
public class EmployeeController extends CrudXController<Employee, Long> {
}

// ✅ Good: Version your APIs
@RestController
@RequestMapping("/api/v2/employees")
public class EmployeeV2Controller extends CrudXController<Employee, Long> {
}
```

### 3. Error Handling

```java
// ✅ Good: Use lifecycle hooks for validation
@Override
protected void beforeCreate(Employee employee) {
    if (employee.getSalary() != null && employee.getSalary() < 10000) {
        throw new IllegalArgumentException("Minimum salary is 10,000");
    }
}
```

### 4. Pagination Usage

```java
// ✅ Good: Use pagination for large datasets
GET /api/employees/paged?page=0&size=20&sortBy=name&sortDirection=ASC

// ❌ Bad: Fetching all records without pagination
GET /api/employees  // Might return thousands of records
```

### 5. Batch Operations

```java
// ✅ Good: Use skipDuplicates for data imports
POST /api/employees/batch?skipDuplicates=true

// ✅ Good: Limit batch size
List<Employee> batch = employees.subList(0, 1000);
```

---

## Configuration Reference

### CrudX Properties

```yaml
crudx:
  database:
    auto-create: true  # Auto-create database if not exists
  
  # For JPA
  jpa:
    repository:
      packages: com.company.repositories  # Additional repository packages
    entity:
      packages: com.company.domain        # Additional entity packages
  
  # For MongoDB
  mongo:
    repository:
      packages: com.company.repositories  # Additional repository packages
```

### Performance Tuning

```yaml
spring:
  jpa:
    properties:
      hibernate:
        jdbc:
          batch_size: 100               # Batch insert size
          fetch_size: 50                # Fetch size
        order_inserts: true
        order_updates: true
        cache:
          use_second_level_cache: true
```

---

## Support

**Created by:** Sachin Nimbal
**Location:** Bangalore, India

For help:
- Documentation: https://github.com/sachinnimbal/crudx-examples/wiki
- Issues: https://github.com/sachinnimbal/crudx-examples/issues
- Examples: https://github.com/sachinnimbal/crudx-examples