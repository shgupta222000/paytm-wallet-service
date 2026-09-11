# Paytm PML — R2 Architectural Defense & Design Decisions

**Candidate**: Shubham Gupta (Senior Backend Engineer) | shgupta222000@gmail.com  
**Project**: Wallet & P2P Transfer Service  
**Total Cloud Cost**: ₹0 (Free Tier Deployment on Render / Koyeb + Neon PostgreSQL)

---

## 1. Data Model & Integrity Constraints

The data model is designed around three relational tables in PostgreSQL:

1. **`wallets`**:
   - `id UUID PRIMARY KEY`, `user_id VARCHAR(64) UNIQUE NOT NULL`
   - `balance_paise BIGINT NOT NULL CHECK (balance_paise >= 0)`
   - `created_at`, `updated_at`, `version BIGINT`
   - **Constraint Defense**: Money is stored exclusively as **integer paise** (1 INR = 100 paise). We never use floats or decimals in storage, calculation, or network transit to eliminate IEEE-754 floating-point rounding errors. The `CHECK (balance_paise >= 0)` constraint guarantees that even in the presence of unexpected software bugs, the datastore will abort any transaction attempting an overdraft.

2. **`idempotency_keys`**:
   - `key VARCHAR(255) PRIMARY KEY`
   - `request_hash VARCHAR(64) NOT NULL` (SHA-256 hex digest of `from|to|amount_paise`)
   - `status VARCHAR(32) NOT NULL` (`PROCESSING`, `COMPLETED`)
   - `response_status INT`, `response_body VARCHAR(4096)`
   - `created_at`, `updated_at`

3. **`transfers`**:
   - `id UUID PRIMARY KEY`, `idempotency_key VARCHAR(255) UNIQUE NOT NULL`
   - `from_wallet_id UUID NOT NULL REFERENCES wallets(id)`
   - `to_wallet_id UUID NOT NULL REFERENCES wallets(id)`
   - `amount_paise BIGINT NOT NULL CHECK (amount_paise > 0)`
   - `status VARCHAR(32) NOT NULL` (`SUCCESS`, `DECLINED_INSUFFICIENT_FUNDS`, `REVERSED`)
   - `decline_reason TEXT`, `created_at TIMESTAMPTZ`

4. **`ledger_entries`** (Double-entry accounting):
   - For every transfer, two ledger entries are written: a negative delta for the sender and a positive delta for the recipient. Sum of deltas per transfer is identically zero, establishing mathematical proof of money conservation.

---

## 2. The Simplest-Correct Mechanism for Conservation + No-Overdraft

### The Chosen Approach: Canonical Sorted Row Locking (`SELECT ... FOR UPDATE`)
All balance transfers execute inside an atomic transaction (`@Transactional(isolation = Isolation.READ_COMMITTED)`).

```java
// Sort wallet IDs lexicographically to enforce canonical acquisition order
UUID firstLockId  = fromId.compareTo(toId) < 0 ? fromId : toId;
UUID secondLockId = fromId.compareTo(toId) < 0 ? toId : fromId;

// Acquire exclusive row-level write locks in deterministic order
Wallet firstWallet  = walletRepository.findByIdForUpdate(firstLockId);
Wallet secondWallet = walletRepository.findByIdForUpdate(secondLockId);
```

### Deadlock Elimination Proof
- **The Problem**: If User 1 transfers from $A \to B$ while User 2 simultaneously transfers from $B \to A$:
  - Without sorting: Tx1 locks $A$ and requests $B$; Tx2 locks $B$ and requests $A$. This creates a circular wait $\implies$ PostgreSQL Deadlock (`40P01`).
- **The Solution**: By enforcing that every transaction acquires row locks in deterministic ascending order (`min(A, B)` then `max(A, B)`), both Tx1 and Tx2 will contest the exact same first lock (e.g., $A$). The winner proceeds to lock $B$, while the loser waits on $A$. Circular wait is mathematically impossible.

### Heavier Alternatives Rejected and Why:
1. **Distributed Locks (Redis / Redlock)**:
   - *Rejected*: Introduces an external dependency, additional network hops, lease expiry races (clock skew / GC pauses releasing the lock prematurely while the DB transaction is still writing), and split-brain scenarios. PostgreSQL already provides native, crash-safe ACID row locks tied directly to transaction lifetime.
2. **Serializable Isolation Level**:
   - *Rejected*: In PostgreSQL, SSI uses `SIREAD` predicate locks. Under concurrent bursts touching hot wallets, SSI throws serialization failures (`40001`), requiring retry loops in application code that spike CPU, latency, and connection pools.
3. **Event Sourcing / Saga**:
   - *Rejected*: Over-engineered for a single-database P2P transfer. Introduces eventual consistency where balances might temporarily be out of sync.

---

## 3. Where Idempotency Lives & Collision Handling

1. **Uniqueness Enforcement**:
   - Idempotency is enforced by the primary key on `idempotency_keys(key)` and foreign key on `transfers(idempotency_key)`.
   - The idempotency record is locked/updated within the **exact same database transaction** as the wallet balance decrements and increments. A database rollback rolls back the debit, the credit, and the idempotency state simultaneously.

2. **Same-Key / Different-Body Replay (409 Conflict)**:
   - Before executing, the service computes `SHA-256(from + "|" + to + "|" + amountPaise)`.
   - If the key exists and the incoming payload hash does not match the stored hash, the request is immediately rejected with **`409 Conflict`** (`IdempotencyConflictException`). The ledger is never touched.

3. **Same-Key Concurrent Race (Retry Storm)**:
   - The first request to arrive acquires the lock or inserts the key with status `PROCESSING`.
   - Racing concurrent requests with the identical key either wait for the transaction to complete or safely catch the duplicate and replay the stored `COMPLETED` response. Exactly one debit occurs; all callers receive identical 201/200 responses.

---

## 4. Consistency vs. Availability for a Money Workload

In financial ledgers, **Consistency (and Partition Tolerance) strictly dominates Availability** (CAP Theorem: CP over AP):
- **What was chosen**: Strict Linearizable Consistency. A balance inquiry or transfer must always reflect the absolute, undeniable state of reality. Double spending, money loss, or ghost credits are unacceptable.
- **What was consciously given up**: Unbounded availability under network partitions or uncoordinated multi-master writes. If two conflicting transfers arrive for the same funds, one must wait or fail cleanly with `422 Unprocessable Entity`. We do not permit offline/asynchronous balance decrements.

---

## 5. R3 Live Follow-up: Reversal / Refund Transfer

The endpoint `POST /transfers/{id}/reverse` is implemented using the exact same atomic ledger primitive:
- Requires its own client-supplied `idempotency_key` to avoid double-refunding.
- Swaps the roles: debits the original recipient and credits the original sender.
- Acquires locks in canonical sorted order (`min(from, to) -> max(from, to)`).
- **Overdraft Guard**: If the recipient already withdrew or transferred the funds, the reversal cleanly declines (`422 Insufficient Balance`) without forcing the recipient wallet into negative balance.
- Guards against reversing an already reversed transfer (`409 Conflict`).

---

## 6. AI: Directed vs. Decided Disclosure

In compliance with Paytm PML disclosure guidelines:
- **Directed by Developer**:
  - Selection of canonical sorted row-locking (`min(id, id) -> max(id, id)`) to eliminate circular wait deadlocks.
  - Decision to store all monetary amounts strictly in integer paise.
  - Decision to use SHA-256 payload hashing for 409 Conflict validation on idempotency key reuse.
  - Architecture of the in-memory log buffer and SSE live streaming endpoint (`/logs/stream`).
  - Architecture of the R3 reversal handling for the recipient-drained case.
- **Decided by AI Assistant**:
  - Typing out boilerplate DTOs, Jackson annotations, and SLF4J MDC filter wiring.
  - Syntax formatting for the multi-stage Dockerfile and bash burst scripts.
  - Defaulting Prometheus metric naming conventions to Spring Boot Actuator standards.

---

## 7. Operational & Free-Tier Cost Note

- **Cloud Platform**: Render.com Web Service (Free Tier) or Koyeb (Free Nano Instance)
- **Database**: Neon Serverless PostgreSQL (Free Tier: 0.5 GB storage, shared compute)
- **Container Sizing**: The application runs on Alpine Linux with OpenJDK 21 configured with `-XX:MaxRAMPercentage=75.0 -XX:+UseSerialGC`, consuming ~150MB of RSS RAM.
- **Total Monthly Cost**: **₹0.00** (no credit card required).
