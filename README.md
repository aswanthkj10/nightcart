# NightCart — Simple Four-Database Shopping Cart

A student-friendly Java 17 + Spring Boot + Thymeleaf shopping cart with a simple dark UI. Each database has a distinct job, and the Database Activity page records which database ran each operation, its result, duration, details, and any error message.

## Database responsibilities

- **Redis** — active cart quantities in a Hash, unique customers in a Set, recent cart events in a List, and checkout totals in a Sorted Set.
- **MongoDB** — product documents, active-cart snapshots, orders, and persistent `activity_logs` documents.
- **MonetDB** — relational `sales_items` rows and SQL sales aggregates (`SUM(quantity)`, revenue by product).
- **Neo4j** — Product/Order nodes and `RELATED_TO`, `BOUGHT_WITH`, and `CONTAINS` relationships; recommendations are returned with Cypher.

## Requirements

- Java 17 or newer
- Maven 3.9+
- Docker Desktop running on Windows
- Internet access for the first Maven dependency download and Docker image pull

## Start from a clean install

Open PowerShell in this folder.

1. Start the four database containers:

   ```powershell
   docker compose up -d
   docker compose ps -a
   ```

2. Give MonetDB a little time to initialize, then check its logs:

   ```powershell
   docker compose logs --tail=80 monetdb
   Test-NetConnection localhost -Port 50000
   ```

   `TcpTestSucceeded: True` confirms the port is reachable, not by itself that SQL queries work. The app runs a real `SELECT 1` check and displays the result on Database overview.

3. Build and run the app. To use all four local databases, explicitly enable MonetDB before starting Spring Boot:

   ```powershell
   $env:MONETDB_ENABLED="true"
   mvn -U clean package
   mvn spring-boot:run
   ```

   For free-hosting mode, leave `MONETDB_ENABLED` unset or set it to `false`; the app calculates analytics from non-cancelled MongoDB orders instead.

4. Open <http://localhost:8080>.

## Suggested demo sequence

1. Open **Database overview**. It runs live ping/query checks against all four databases.
2. Open **Products**, add a product to the cart, and visit **Shopping cart**.
3. Place a demo order. MongoDB stores the order; Redis updates its activity and checkout ranking; MonetDB stores relational sales rows; Neo4j stores the order/product graph.
4. Open **Orders**, **Sales analytics**, and **Recommendations**.
5. Open **Database activity** and filter by Redis, MongoDB, MonetDB, or Neo4j. Each event shows database, operation, success/failure, duration, details, and error message.

## MonetDB configuration

The Compose service sets `MDB_DB_ADMIN_PASS=monetdb` and `MDB_CREATE_DBS=demo`. When `MONETDB_ENABLED=true`, the app can use the local Compose database. For free-hosting fallback, leave MonetDB disabled and the app uses MongoDB for analytics. The local Compose credentials are:

- URL: `jdbc:monetdb://localhost:50000/demo`
- Username: `monetdb`
- Password: `monetdb`

If you change the container credentials, set the corresponding environment variables before launching Spring Boot (`MONETDB_URL`, `MONETDB_USERNAME`, `MONETDB_PASSWORD`).

## Troubleshooting

- **Port 8080 already in use:** stop the old Spring Boot process, or run ` $env:PORT=8081; mvn spring-boot:run ` and visit port 8081.
- **MonetDB says Offline:** check `docker compose ps -a`, `docker compose logs --tail=100 monetdb`, and `Test-NetConnection localhost -Port 50000`. The dashboard only reports Online when the JDBC `SELECT 1` query succeeds.
- **Old project containers/volumes:** this project uses the same `nightcart-*` container names and Docker volume names as the earlier version. Stop the older Compose project before starting this one. If you have data you want to keep, do not run `docker compose down -v`.
- **Maven dependency download:** the MonetDB JDBC artifact is resolved from Clojars, which is configured in `pom.xml`. Use `mvn -U clean package` after a dependency-resolution failure.

## Notes on activity logging

The app retains the latest 250 activity events in memory for fast viewing and also writes activity documents to MongoDB's `activity_logs` collection when MongoDB is available. Each database operation records success or failure independently. A failed optional MonetDB/Neo4j write does not erase an order already saved in MongoDB; its failure is visible in the log. When `MONETDB_ENABLED=true`, cancellation requires MonetDB to be reachable first, so the app will not mark an order cancelled if it cannot remove its sales rows. If MongoDB fails after MonetDB rows are removed, the app attempts to restore the rows and records the result. When MonetDB is disabled, cancellation updates the MongoDB order and analytics exclude cancelled orders.

This is a demo application: checkout does not collect real payments, and the default credentials are for local development only.
