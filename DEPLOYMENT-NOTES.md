# NightCart deployment notes — Render Free

## Current deployment strategy
- Render hosts the Spring Boot app in the root `Dockerfile`.
- MongoDB Atlas, Redis Cloud, and Neo4j AuraDB are external managed databases.
- MonetDB remains supported by the application but is disabled by default until a reachable MonetDB host is available. Set `MONETDB_ENABLED=true` only after configuring `MONETDB_URL`, `MONETDB_USERNAME`, and `MONETDB_PASSWORD`.
- While MonetDB is disabled, analytics are calculated from non-cancelled orders in MongoDB and order cancellation does not require MonetDB.

## Render service settings
- Repository: `aswanthkj10/nightcart`
- Branch: `main`
- Runtime: Docker
- Root Directory: blank
- Dockerfile Path: `./Dockerfile`
- Instance type: Free
- Do not set `PORT` manually; Render provides it and `application.properties` uses `${PORT:8080}`.

## Environment variables
Set the values from `.env.example` in the Render dashboard, not in GitHub. Required for remote Redis are `REDIS_HOST`, `REDIS_PORT`, `REDIS_USERNAME`, `REDIS_PASSWORD`, and `REDIS_SSL=true` when the Redis provider requires TLS. For Atlas, use the full `MONGODB_URI`. For AuraDB, use the Aura-provided `neo4j+s://...` URI and credentials.

Never commit real credentials. Do not expose database ports publicly unless required; use provider-supported TLS and strong passwords.

## Free-tier caveats
- Render Free web services spin down after 15 minutes without inbound traffic and can take about a minute to wake. The filesystem is ephemeral, so the app must not store persistent database data locally.
- Free database plans have small quotas and provider-specific inactivity, backup, connection, and availability limits. They are suitable for a student demo, not production guarantees.
- MonetDB does not have an equivalent always-free managed database plan established here. To keep all four databases active at ₹0/month, a separate free VM or other free host must be found and validated. Otherwise, the app can run in fallback mode with MonetDB disabled while the other three databases remain live.

## Build verification
A Maven build has not yet been run in this environment. Use the included GitHub Actions workflow or the Render build logs to confirm dependency resolution and compilation before considering deployment complete.


## Account login and roles

- Customer accounts are created from `/register`; public registration always assigns the `CUSTOMER` role. Passwords are stored using BCrypt hashes.
- Set `ADMIN_USERNAME` and `ADMIN_PASSWORD` as private environment variables in Render to bootstrap the administrator account. The password must be at least 12 characters. Do not put real credentials in GitHub or `.env.example`.
- The admin account is created/updated at application startup from those environment variables. The MongoDB `users` collection stores account records.
- Customers can view and cancel only orders whose stored customer name matches their username. Existing orders created by the old name-switching demo are left in place for administrator review and are not automatically assigned to new accounts.
- Spring Security CSRF protection remains enabled. The app requires a MongoDB connection for account registration and login.
