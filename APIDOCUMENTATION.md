# CrudX Framework - API Documentation

**Version:** 0.0.1  
**Author:** Sachin Nimbal  
**Location:** Bangalore, India

## Table of Contents
1. [Introduction](#introduction)
2. [Response Format](#response-format)
3. [API Endpoints](#api-endpoints)
4. [Request Examples](#request-examples)
5. [Error Handling](#error-handling)
6. [Query Parameters](#query-parameters)

---

## Introduction

CrudX automatically generates RESTful APIs for your entities. This documentation uses an Employee management system as an example.

**Base URL:** `http://localhost:8080/api/employees`

### Entity Example

```java
@Entity
@Table(name = "employees")
@CrudXUniqueConstraint(
    fields = {"email"},
    message = "Employee with this email already exists"
)
public class Employee extends CrudXMySQLEntity<Long> {
    private String name;
    private String email;
    private String department;
    private String phoneNumber;
    private Double salary;
}
```

---

## Response Format

All API responses follow a consistent format:

### Success Response
```json
{
  "success": true,
  "message": "Operation successful",
  "statusCode": 200,
  "status": "OK",
  "data": { /* response data */ },
  "timestamp": "2025-01-15T10:30:45",
  "executionTime": "45 ms"
}
```

### Error Response
```json
{
  "success": false,
  "message": "Error message",
  "statusCode": 400,
  "status": "BAD_REQUEST",
  "error": {
    "code": "ERROR_CODE",
    "details": "Detailed error information"
  },
  "timestamp": "2025-01-15T10:30:45"
}
```

---

## API Endpoints

### 1. Create Single Record

**Endpoint:** `POST /api/employees`

**Description:** Creates a new employee record

**Request Body:**
```json
{
  "name": "Rahul Sharma",
  "email": "rahul.sharma@company.com",
  "department": "Engineering",
  "phoneNumber": "+91-9876543210",
  "salary": 75000.00
}
```

**Success Response (201 Created):**
```json
{
  "success": true,
  "message": "Entity created successfully",
  "statusCode": 201,
  "status": "CREATED",
  "data": {
    "id": 1,
    "name": "Rahul Sharma",
    "email": "rahul.sharma@company.com",
    "department": "Engineering",
    "phoneNumber": "+91-9876543210",
    "salary": 75000.00,
    "audit": {
      "createdAt": "2025-01-15T10:30:45",
      "createdBy": null,
      "updatedAt": "2025-01-15T10:30:45",
      "updatedBy": null
    }
  },
  "executionTime": "87 ms",
  "timestamp": "2025-01-15T10:30:45"
}
```

**cURL Example:**
```bash
curl -X POST http://localhost:8080/api/employees \
  -H "Content-Type: application/json" \
  -d '{
    "name": "Rahul Sharma",
    "email": "rahul.sharma@company.com",
    "department": "Engineering",
    "phoneNumber": "+91-9876543210",
    "salary": 75000.00
  }'
```

---

### 2. Create Batch Records

**Endpoint:** `POST /api/employees/batch?skipDuplicates=true`

**Description:** Creates multiple employee records at once

**Query Parameters:**
- `skipDuplicates` (optional, default: true) - Skip duplicate entries instead of failing

**Request Body:**
```json
[
  {
    "name": "Priya P",
    "email": "priya.p@company.com",
    "department": "Marketing",
    "phoneNumber": "+91-9876543211",
    "salary": 65000.00
  },
  {
    "name": "Amit Kumar",
    "email": "amit.kumar@company.com",
    "department": "Sales",
    "phoneNumber": "+91-9876543212",
    "salary": 70000.00
  },
  {
    "name": "Sneha Singh",
    "email": "sneha.singh@company.com",
    "department": "HR",
    "phoneNumber": "+91-9876543213",
    "salary": 60000.00
  }
]
```

**Success Response (201 Created):**
```json
{
  "success": true,
  "message": "3 entities created successfully",
  "statusCode": 201,
  "status": "CREATED",
  "data": {
    "createdEntities": [
      {
        "id": 2,
        "name": "Priya P",
        "email": "priya.p@company.com",
        "department": "Marketing",
        "phoneNumber": "+91-9876543211",
        "salary": 65000.00
      },
      {
        "id": 3,
        "name": "Amit Kumar",
        "email": "amit.kumar@company.com",
        "department": "Sales",
        "phoneNumber": "+91-9876543212",
        "salary": 70000.00
      },
      {
        "id": 4,
        "name": "Sneha Singh",
        "email": "sneha.singh@company.com",
        "department": "HR",
        "phoneNumber": "+91-9876543213",
        "salary": 60000.00
      }
    ],
    "skippedCount": 0,
    "skippedReasons": [],
    "totalProcessed": 3
  },
  "executionTime": "234 ms",
  "timestamp": "2025-01-15T10:35:20"
}
```

**Response with Skipped Records:**
```json
{
  "success": true,
  "message": "Batch creation completed: 2 created, 1 skipped (duplicates/errors)",
  "statusCode": 201,
  "status": "CREATED",
  "data": {
    "createdEntities": [
      {
        "id": 5,
        "name": "Vikram Reddy",
        "email": "vikram.reddy@company.com",
        "department": "Finance",
        "salary": 80000.00
      },
      {
        "id": 6,
        "name": "Anjali Verma",
        "email": "anjali.verma@company.com",
        "department": "Operations",
        "salary": 68000.00
      }
    ],
    "skippedCount": 1,
    "skippedReasons": [
      "Entity at index 2 skipped - Employee with this email already exists"
    ],
    "totalProcessed": 3
  },
  "executionTime": "198 ms",
  "timestamp": "2025-01-15T10:40:15"
}
```

**cURL Example:**
```bash
curl -X POST "http://localhost:8080/api/employees/batch?skipDuplicates=true" \
  -H "Content-Type: application/json" \
  -d '[
    {
      "name": "Priya P",
      "email": "priya.p@company.com",
      "department": "Marketing",
      "salary": 65000.00
    },
    {
      "name": "Amit Kumar",
      "email": "amit.kumar@company.com",
      "department": "Sales",
      "salary": 70000.00
    }
  ]'
```

---

### 3. Get Record by ID

**Endpoint:** `GET /api/employees/{id}`

**Description:** Retrieves a single employee by ID

**Path Parameters:**
- `id` (required) - Employee ID

**Success Response (200 OK):**
```json
{
  "success": true,
  "message": "Entity retrieved successfully",
  "statusCode": 200,
  "status": "OK",
  "data": {
    "id": 1,
    "name": "Rahul Sharma",
    "email": "rahul.sharma@company.com",
    "department": "Engineering",
    "phoneNumber": "+91-9876543210",
    "salary": 75000.00,
    "audit": {
      "createdAt": "2025-01-15T10:30:45",
      "createdBy": null,
      "updatedAt": "2025-01-15T10:30:45",
      "updatedBy": null
    }
  },
  "executionTime": "23 ms",
  "timestamp": "2025-01-15T10:45:30"
}
```

**Error Response (404 Not Found):**
```json
{
  "success": false,
  "message": "Employee not found with id: 999",
  "statusCode": 404,
  "status": "NOT_FOUND",
  "error": {
    "code": "ENTITY_NOT_FOUND",
    "details": "Employee not found with id: 999"
  },
  "timestamp": "2025-01-15T10:46:00"
}
```

**cURL Example:**
```bash
curl -X GET http://localhost:8080/api/employees/1
```

---

### 4. Get All Records

**Endpoint:** `GET /api/employees`

**Description:** Retrieves all employees (auto-paginates for large datasets)

**Query Parameters:**
- `sortBy` (optional) - Field to sort by (e.g., name, salary)
- `sortDirection` (optional, default: ASC) - Sort direction (ASC or DESC)

**Success Response (200 OK) - Small Dataset:**
```json
{
  "success": true,
  "message": "Retrieved 4 entities",
  "statusCode": 200,
  "status": "OK",
  "data": [
    {
      "id": 1,
      "name": "Rahul Sharma",
      "email": "rahul.sharma@company.com",
      "department": "Engineering",
      "salary": 75000.00
    },
    {
      "id": 2,
      "name": "Priya P",
      "email": "priya.p@company.com",
      "department": "Marketing",
      "salary": 65000.00
    },
    {
      "id": 3,
      "name": "Amit Kumar",
      "email": "amit.kumar@company.com",
      "department": "Sales",
      "salary": 70000.00
    },
    {
      "id": 4,
      "name": "Sneha Singh",
      "email": "sneha.singh@company.com",
      "department": "HR",
      "salary": 60000.00
    }
  ],
  "executionTime": "67 ms",
  "timestamp": "2025-01-15T10:50:00"
}
```

**Success Response (200 OK) - Large Dataset (Auto-Paginated):**
```json
{
  "success": true,
  "message": "Large dataset detected (5000 total records). Returning first 1000 records. Use /paged endpoint with page parameter for more data.",
  "statusCode": 200,
  "status": "OK",
  "data": {
    "content": [ /* first 1000 records */ ],
    "currentPage": 0,
    "pageSize": 1000,
    "totalElements": 5000,
    "totalPages": 5,
    "first": true,
    "last": false,
    "empty": false
  },
  "executionTime": "456 ms",
  "timestamp": "2025-01-15T10:52:00"
}
```

**cURL Examples:**
```bash
# Get all employees
curl -X GET http://localhost:8080/api/employees

# Get all sorted by name
curl -X GET "http://localhost:8080/api/employees?sortBy=name&sortDirection=ASC"

# Get all sorted by salary (descending)
curl -X GET "http://localhost:8080/api/employees?sortBy=salary&sortDirection=DESC"
```

---

### 5. Get Paginated Records

**Endpoint:** `GET /api/employees/paged`

**Description:** Retrieves employees with pagination

**Query Parameters:**
- `page` (optional, default: 0) - Page number (0-indexed)
- `size` (optional, default: 10) - Page size (max: 100000)
- `sortBy` (optional) - Field to sort by
- `sortDirection` (optional, default: ASC) - Sort direction

**Success Response (200 OK):**
```json
{
  "success": true,
  "message": "Retrieved page 0 with 10 elements (total: 50)",
  "statusCode": 200,
  "status": "OK",
  "data": {
    "content": [
      {
        "id": 1,
        "name": "Rahul Sharma",
        "email": "rahul.sharma@company.com",
        "department": "Engineering",
        "salary": 75000.00
      },
      {
        "id": 2,
        "name": "Priya P",
        "email": "priya.p@company.com",
        "department": "Marketing",
        "salary": 65000.00
      }
      // ... 8 more records
    ],
    "currentPage": 0,
    "pageSize": 10,
    "totalElements": 50,
    "totalPages": 5,
    "first": true,
    "last": false,
    "empty": false
  },
  "executionTime": "45 ms",
  "timestamp": "2025-01-15T11:00:00"
}
```

**cURL Examples:**
```bash
# First page with 10 records
curl -X GET "http://localhost:8080/api/employees/paged?page=0&size=10"

# Second page with 20 records, sorted by salary
curl -X GET "http://localhost:8080/api/employees/paged?page=1&size=20&sortBy=salary&sortDirection=DESC"

# Third page with 50 records, sorted by name
curl -X GET "http://localhost:8080/api/employees/paged?page=2&size=50&sortBy=name&sortDirection=ASC"
```

---

### 6. Update Record

**Endpoint:** `PATCH /api/employees/{id}`

**Description:** Updates specific fields of an employee (partial update)

**Path Parameters:**
- `id` (required) - Employee ID

**Request Body:**
```json
{
  "department": "Senior Engineering",
  "salary": 85000.00,
  "phoneNumber": "+91-9876543299"
}
```

**Success Response (200 OK):**
```json
{
  "success": true,
  "message": "Entity updated successfully",
  "statusCode": 200,
  "status": "OK",
  "data": {
    "id": 1,
    "name": "Rahul Sharma",
    "email": "rahul.sharma@company.com",
    "department": "Senior Engineering",
    "phoneNumber": "+91-9876543299",
    "salary": 85000.00,
    "audit": {
      "createdAt": "2025-01-15T10:30:45",
      "createdBy": null,
      "updatedAt": "2025-01-15T11:15:30",
      "updatedBy": null
    }
  },
  "executionTime": "54 ms",
  "timestamp": "2025-01-15T11:15:30"
}
```

**cURL Example:**
```bash
curl -X PATCH http://localhost:8080/api/employees/1 \
  -H "Content-Type: application/json" \
  -d '{
    "department": "Senior Engineering",
    "salary": 85000.00
  }'
```

---

### 7. Delete Record

**Endpoint:** `DELETE /api/employees/{id}`

**Description:** Deletes a single employee by ID

**Path Parameters:**
- `id` (required) - Employee ID

**Success Response (200 OK):**
```json
{
  "success": true,
  "message": "Entity deleted successfully",
  "statusCode": 200,
  "status": "OK",
  "data": null,
  "executionTime": "38 ms",
  "timestamp": "2025-01-15T11:20:00"
}
```

**Error Response (404 Not Found):**
```json
{
  "success": false,
  "message": "Employee not found with id: 999",
  "statusCode": 404,
  "status": "NOT_FOUND",
  "error": {
    "code": "ENTITY_NOT_FOUND",
    "details": "Employee not found with id: 999"
  },
  "timestamp": "2025-01-15T11:21:00"
}
```

**cURL Example:**
```bash
curl -X DELETE http://localhost:8080/api/employees/1
```

---

### 8. Delete Batch Records

**Endpoint:** `DELETE /api/employees/batch`

**Description:** Deletes multiple employees by IDs (skips non-existent IDs)

**Request Body:**
```json
[1, 2, 3, 4, 5]
```

**Success Response (200 OK):**
```json
{
  "success": true,
  "message": "Batch deletion completed: 4 deleted, 1 skipped (not found)",
  "statusCode": 200,
  "status": "OK",
  "data": {
    "createdEntities": [1, 2, 3, 4],
    "skippedCount": 1,
    "skippedReasons": [
      "ID 5 not found"
    ],
    "totalProcessed": 5
  },
  "executionTime": "112 ms",
  "timestamp": "2025-01-15T11:25:00"
}
```

**cURL Example:**
```bash
curl -X DELETE http://localhost:8080/api/employees/batch \
  -H "Content-Type: application/json" \
  -d '[1, 2, 3, 4, 5]'
```

---

### 9. Get Total Count

**Endpoint:** `GET /api/employees/count`

**Description:** Returns total count of employees

**Success Response (200 OK):**
```json
{
  "success": true,
  "message": "Total count: 50",
  "statusCode": 200,
  "status": "OK",
  "data": 50,
  "executionTime": "12 ms",
  "timestamp": "2025-01-15T11:30:00"
}
```

**cURL Example:**
```bash
curl -X GET http://localhost:8080/api/employees/count
```

---

### 10. Check If Record Exists

**Endpoint:** `GET /api/employees/exists/{id}`

**Description:** Checks if an employee exists by ID

**Path Parameters:**
- `id` (required) - Employee ID

**Success Response (200 OK):**
```json
{
  "success": true,
  "message": "Entity exists",
  "statusCode": 200,
  "status": "OK",
  "data": true,
  "executionTime": "18 ms",
  "timestamp": "2025-01-15T11:35:00"
}
```

**Success Response (Not Found):**
```json
{
  "success": true,
  "message": "Entity does not exist",
  "statusCode": 200,
  "status": "OK",
  "data": false,
  "executionTime": "16 ms",
  "timestamp": "2025-01-15T11:36:00"
}
```

**cURL Example:**
```bash
curl -X GET http://localhost:8080/api/employees/exists/1
```

---

## Complete Request Examples

### Example 1: Create Employee with Full Details

**Request:**
```bash
curl -X POST http://localhost:8080/api/employees \
  -H "Content-Type: application/json" \
  -d '{
    "name": "Karthik Menon",
    "email": "karthik.menon@company.com",
    "department": "Product Management",
    "phoneNumber": "+91-9876543220",
    "salary": 95000.00
  }'
```

**Response:**
```json
{
  "success": true,
  "message": "Entity created successfully",
  "statusCode": 201,
  "status": "CREATED",
  "data": {
    "id": 7,
    "name": "Karthik Menon",
    "email": "karthik.menon@company.com",
    "department": "Product Management",
    "phoneNumber": "+91-9876543220",
    "salary": 95000.00,
    "audit": {
      "createdAt": "2025-01-15T12:00:00",
      "updatedAt": "2025-01-15T12:00:00"
    }
  },
  "executionTime": "76 ms"
}
```

---

### Example 2: Bulk Import Employees

**Request:**
```bash
curl -X POST "http://localhost:8080/api/employees/batch?skipDuplicates=true" \
  -H "Content-Type: application/json" \
  -d '[
    {
      "name": "Deepa Nair",
      "email": "deepa.nair@company.com",
      "department": "Quality Assurance",
      "phoneNumber": "+91-9876543230",
      "salary": 62000.00
    },
    {
      "name": "Rohan Joshi",
      "email": "rohan.joshi@company.com",
      "department": "DevOps",
      "phoneNumber": "+91-9876543231",
      "salary": 78000.00
    },
    {
      "name": "Meera Iyer",
      "email": "meera.iyer@company.com",
      "department": "Design",
      "phoneNumber": "+91-9876543232",
      "salary": 72000.00
    },
    {
      "name": "Arjun Kapoor",
      "email": "arjun.kapoor@company.com",
      "department": "Customer Success",
      "phoneNumber": "+91-9876543233",
      "salary": 58000.00
    }
  ]'
```

---

### Example 3: Search and Filter (Custom Endpoint)

If you need filtering, add a custom endpoint in your controller:

```java
@GetMapping("/search")
public ResponseEntity<ApiResponse<List<Employee>>> search(
    @RequestParam(required = false) String department,
    @RequestParam(required = false) Double minSalary,
    @RequestParam(required = false) Double maxSalary) {
    
    List<Employee> all = crudService.findAll();
    List<Employee> filtered = all.stream()
        .filter(e -> department == null || department.equals(e.getDepartment()))
        .filter(e -> minSalary == null || e.getSalary() >= minSalary)
        .filter(e -> maxSalary == null || e.getSalary() <= maxSalary)
        .collect(Collectors.toList());
    
    return ResponseEntity.ok(ApiResponse.success(filtered, 
        "Found " + filtered.size() + " employees"));
}
```

**Request:**
```bash
curl -X GET "http://localhost:8080/api/employees/search?department=Engineering&minSalary=70000"
```

---

## Error Handling

### Common Error Responses

#### 400 Bad Request - Invalid Input
```json
{
  "success": false,
  "message": "Validation failed",
  "statusCode": 400,
  "status": "BAD_REQUEST",
  "data": {
    "email": "must be a well-formed email address",
    "salary": "must not be null"
  },
  "error": {
    "code": "VALIDATION_ERROR",
    "details": "Field validation failed"
  },
  "timestamp": "2025-01-15T12:30:00"
}
```

#### 404 Not Found - Entity Not Found
```json
{
  "success": false,
  "message": "Employee not found with id: 999",
  "statusCode": 404,
  "status": "NOT_FOUND",
  "error": {
    "code": "ENTITY_NOT_FOUND",
    "details": "Employee not found with id: 999"
  },
  "timestamp": "2025-01-15T12:31:00"
}
```

#### 409 Conflict - Duplicate Entry
```json
{
  "success": false,
  "message": "Employee with this email already exists",
  "statusCode": 409,
  "status": "CONFLICT",
  "error": {
    "code": "DUPLICATE_ENTITY",
    "details": "Employee with this email already exists"
  },
  "timestamp": "2025-01-15T12:32:00"
}
```

#### 500 Internal Server Error
```json
{
  "success": false,
  "message": "An unexpected error occurred",
  "statusCode": 500,
  "status": "INTERNAL_SERVER_ERROR",
  "error": {
    "code": "INTERNAL_SERVER_ERROR",
    "details": "Database connection failed"
  },
  "timestamp": "2025-01-15T12:33:00"
}
```

---

## Query Parameters Reference

### Pagination Parameters

| Parameter | Type | Default | Description |
|-----------|------|---------|-------------|
| page | Integer | 0 | Page number (0-indexed) |
| size | Integer | 10 | Number of records per page |
| sortBy | String | - | Field name to sort by |
| sortDirection | String | ASC | Sort direction (ASC or DESC) |

### Batch Operation Parameters

| Parameter | Type | Default | Description |
|-----------|------|---------|-------------|
| skipDuplicates | Boolean | true | Skip duplicate entries in batch operations |

---

## Performance Notes

### Execution Time

All responses include `executionTime` field showing how long the operation took:
- `"45 ms"` - Less than 1 second
- `"2.34s (2340 ms)"` - Between 1-60 seconds
- `"2m 15s (135000 ms)"` - Over 1 minute

### Large Dataset Handling

1. **Automatic Pagination:** When dataset exceeds 1000 records, the `/api/employees` endpoint automatically returns paginated response
2. **Batch Limits:** Batch operations are limited to 1000 records per request for safety
3. **Memory Optimization:** CrudX uses streaming for large datasets to minimize memory usage

---

## Best Practices

### 1. Use Pagination for Large Datasets
```bash
# Good: Paginated request
curl -X GET "http://localhost:8080/api/employees/paged?page=0&size=50"

# Avoid: Getting all records when dataset is large
curl -X GET http://localhost:8080/api/employees
```

### 2. Use Batch Operations for Multiple Records
```bash
# Good: Single batch request
curl -X POST "http://localhost:8080/api/employees/batch" \
  -d '[{...}, {...}, {...}]'

# Avoid: Multiple single requests
curl -X POST http://localhost:8080/api/employees -d '{...}'
curl -X POST http://localhost:8080/api/employees -d '{...}'
curl -X POST http://localhost:8080/api/employees -d '{...}'
```

### 3. Use PATCH for Partial Updates
```bash
# Good: Update only specific fields
curl -X PATCH http://localhost:8080/api/employees/1 \
  -d '{"salary": 85000}'

# Avoid: Sending entire object for small updates
```

### 4. Handle Errors Gracefully
Always check the `success` field in responses:
```javascript
const response = await fetch('/api/employees/1');
const data = await response.json();

if (data.success) {
  console.log('Employee:', data.data);
} else {
  console.error('Error:', data.message);
  console.error('Details:', data.error.details);
}
```

---

## Integration Examples

### JavaScript/TypeScript (Fetch API)

```typescript
// Create employee
async function createEmployee(employee) {
  const response = await fetch('http://localhost:8080/api/employees', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(employee)
  });
  return await response.json();
}

// Get paginated employees
async function getEmployees(page = 0, size = 10) {
  const response = await fetch(
    `http://localhost:8080/api/employees/paged?page=${page}&size=${size}`
  );
  return await response.json();
}

// Update employee
async function updateEmployee(id, updates) {
  const response = await fetch(`http://localhost:8080/api/employees/${id}`, {
    method: 'PATCH',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(updates)
  });
  return await response.json();
}
```

### Python (Requests Library)

```python
import requests

BASE_URL = 'http://localhost:8080/api/employees'

# Create employee
def create_employee(employee_data):
    response = requests.post(BASE_URL, json=employee_data)
    return response.json()

# Get employee by ID
def get_employee(employee_id):
    response = requests.get(f'{BASE_URL}/{employee_id}')
    return response.json()

# Update employee
def update_employee(employee_id, updates):
    response = requests.patch(f'{BASE_URL}/{employee_id}', json=updates)
    return response.json()

# Delete employee
def delete_employee(employee_id):
    response = requests.delete(f'{BASE_URL}/{employee_id}')
    return response.json()

# Example usage
employee = {
    'name': 'Sanjay Gupta',
    'email': 'sanjay.gupta@company.com',
    'department': 'IT Support',
    'salary': 55000
}

result = create_employee(employee)
if result['success']:
    print(f"Created employee with ID: {result['data']['id']}")
else:
    print(f"Error: {result['message']}")
```

---

## Support & Contact

**Framework Developer:**
- Sachin Nimbal (Lead Developer)

**Location:** Bangalore, India

**Resources:**
- GitHub: https://github.com/sachinnimbal/crudx-examples
- Documentation: https://github.com/sachinnimbal/crudx-examples/wiki
- Issue Tracker: https://github.com/sachinnimbal/crudx-examples/issues

---

**Last Updated:** January 2025  
**API Version:** 0.0.1