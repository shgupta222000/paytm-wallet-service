# Paytm Wallet & P2P Transfer Service

An enterprise-grade, concurrent-safe, fully observable Wallet and Peer-to-Peer (P2P) Transfer backend service built with **Java 21** and **Spring Boot 3**.

---

## Key Invariants Guaranteed

1. **Conservation**: $\sum \text{Wallet Balances}$ remains constant across all transfers. Zero paise created or lost under concurrent bidirectional load.
2. **No Overdraft**: Balances are strictly stored in integer paise (`balance_paise >= 0`). Decrements that exceed balance are cleanly declined.
3. **Exactly-Once & Idempotency**:
   - Resending the same `idempotency_key` with the same payload returns the cached result without double-debiting.
   - Reusing a key with a different payload returns `409 Conflict`.
4. **Race-Free Get-or-Create**: Concurrent `POST /wallets` for the same user yields exactly one wallet.
5. **R3 Reversal Ready**: Pre-built atomic `POST /transfers/{id}/reverse` with overdraft hold protection.

---

## Architecture Highlights

- **Deadlock Avoidance**: Canonical sorted row-locking (`SELECT ... FOR UPDATE` ordered by `min(id, id) -> max(id, id)`).
- **Observability**:
  - Structured JSON logs with correlation IDs (`X-Request-ID` via SLF4J MDC).
  - Live log streaming endpoint at `/logs/stream` (SSE) and `/logs/recent`.
  - Prometheus metrics at `/actuator/prometheus`.
- **Docker**: Multi-stage, non-root Alpine container with `HEALTHCHECK`.

---

## Quick Start (Local)

### Option 1: One-Command Docker Compose (App + PostgreSQL)
```bash
docker compose up --build
```
The service will start on port `8080` with PostgreSQL on port `5432`.

### Option 2: Running with Maven
```bash
# If running against local Postgres:
mvn spring-boot:run

# Or with custom DB credentials:
SPRING_DATASOURCE_URL=jdbc:postgresql://localhost:5432/wallet_db \
SPRING_DATASOURCE_USERNAME=wallet_user \
SPRING_DATASOURCE_PASSWORD=wallet_password \
mvn spring-boot:run
```

---

## Live Concurrency Burst Verification

We provide a one-command burst script that executes all live evaluation probes:
```bash
./burst.sh http://localhost:8080
```

### Probes Executed:
1. **Concurrent Get-or-Create**: 20 concurrent threads for the same user $\implies$ exactly 1 wallet returned.
2. **Idempotent Retry Storm**: 30 simultaneous transfers with the same idempotency key $\implies$ exactly 1 debit of 2,000 paise, zero 5xx, identical responses, and 409 on payload tampering.
3. **Conservation Under Contention**: 60 random bidirectional transfers across 4 wallets $\implies$ total sum of money is 100% conserved and no balance is negative.
4. **R3 Live Probe (Reversal)**: Reverses a completed transfer and blocks duplicate reversals.

---

## API Reference

### 1. Wallets
- **Get or Create Wallet**:
  ```bash
  POST /wallets
  Content-Type: application/json

  {
    "user_id": "user_101",
    "initial_balance_paise": 10000
  }
  ```
- **Get Wallet Balance**:
  ```bash
  GET /wallets/{id}
  ```
- **Inspect Total System Balance**:
  ```bash
  GET /wallets/system/conservation
  ```

### 2. Transfers
- **Execute P2P Transfer**:
  ```bash
  POST /transfers
  Content-Type: application/json

  {
    "from": "<WALLET_A_UUID>",
    "to": "<WALLET_B_UUID>",
    "amount_paise": 2000,
    "idempotency_key": "txn-abc-123"
  }
  ```
- **Get Transfer Status**:
  ```bash
  GET /transfers/{id}
  ```
- **Reverse Transfer (R3 Extension)**:
  ```bash
  POST /transfers/{id}/reverse
  Content-Type: application/json

  {
    "idempotency_key": "rev-txn-123"
  }
  ```

### 3. Observability Endpoints
- **Healthcheck**: `GET /health` or `GET /actuator/health`
- **Prometheus Metrics**: `GET /actuator/prometheus`
- **Live Logs Stream (SSE)**: `GET /logs/stream`
- **Recent Logs (JSON)**: `GET /logs/recent`

---

## Free-Tier Deployment Guide (₹0)

1. **Database (Neon.tech)**:
   - Create a free serverless PostgreSQL instance on [neon.tech](https://neon.tech).
   - Copy the JDBC connection string.
2. **Web Service (Render.com / Koyeb)**:
   - Connect this GitHub repository.
   - Select Docker environment.
   - Set Environment Variables:
     - `SPRING_DATASOURCE_URL`: `jdbc:postgresql://<neon-host>/neondb?sslmode=require`
     - `SPRING_DATASOURCE_USERNAME`: `<neon-user>`
     - `SPRING_DATASOURCE_PASSWORD`: `<neon-password>`
     - `PORT`: `8080`
   - Deploy!
3. **Verify Deployment (Live Endpoint)**:
   - **Live Service URL**: [https://paytm-wallet-service.onrender.com](https://paytm-wallet-service.onrender.com)
   - Run concurrency evaluation probes against the live deployment:
   ```bash
   ./burst.sh https://paytm-wallet-service.onrender.com
   ```
