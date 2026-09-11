#!/usr/bin/env bash
# ==============================================================================
# Paytm PML R2 — Wallet Service Live Concurrency Burst Probes
# Tests:
#   1. Concurrent Get-or-Create (Race-free wallet creation)
#   2. Idempotent Retry Storm (Same idempotency key fired concurrently)
#   3. Conservation Under Contention (Bidirectional money transfers)
#   4. R3 Live Follow-up: Reversal / Refund Transfer
# ==============================================================================

set -euo pipefail

BASE_URL="${1:-http://localhost:8080}"
echo "======================================================================"
echo " Starting Paytm Wallet Concurrency Probes against: ${BASE_URL}"
echo "======================================================================"

# Colors for output
GREEN='\033[0;32m'
RED='\033[0;31m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m'

# Check health
echo -e "\n${BLUE}[0/4] Checking service health...${NC}"
HEALTH=$(curl -s "${BASE_URL}/health" || echo "")
if [[ "$HEALTH" != *"UP"* ]]; then
  echo -e "${RED}Service is not reachable or healthy at ${BASE_URL}!${NC}"
  exit 1
fi
echo -e "${GREEN}✓ Service is UP${NC}"

# ------------------------------------------------------------------------------
# PROBE 1: Concurrent get-or-create for same user
# ------------------------------------------------------------------------------
echo -e "\n${BLUE}[1/4] PROBE 1: Concurrent Get-or-Create (N=20 calls for same new user)${NC}"
RAND_USER="user_burst_$(date +%s)_$RANDOM"
TEMP_DIR=$(mktemp -d)
PIDS=()

for i in {1..20}; do
  curl -s -X POST "${BASE_URL}/wallets" \
    -H "Content-Type: application/json" \
    -H "X-Request-ID: burst-probe1-$i" \
    -d "{\"user_id\":\"${RAND_USER}\", \"initial_balance_paise\":10000}" > "${TEMP_DIR}/create_$i.json" &
  PIDS+=($!)
done

# Wait for all requests to finish
for pid in "${PIDS[@]}"; do
  wait "$pid"
done

# Extract wallet IDs
WALLET_IDS=()
for f in "${TEMP_DIR}"/create_*.json; do
  WID=$(grep -o '"id":"[^"]*' "$f" | cut -d'"' -f4 || echo "")
  if [ -n "$WID" ]; then
    WALLET_IDS+=("$WID")
  fi
done

UNIQUE_COUNT=$(printf "%s\n" "${WALLET_IDS[@]}" | sort -u | wc -l | tr -d ' ')
if [ "$UNIQUE_COUNT" -eq 1 ]; then
  CHOSEN_WALLET_ID="${WALLET_IDS[0]}"
  echo -e "${GREEN}✓ PROBE 1 PASSED: 20 concurrent calls yielded exactly 1 wallet (${CHOSEN_WALLET_ID})${NC}"
else
  echo -e "${RED}✗ PROBE 1 FAILED: Expected 1 unique wallet, found ${UNIQUE_COUNT}${NC}"
  exit 1
fi
rm -rf "${TEMP_DIR}"

# ------------------------------------------------------------------------------
# PROBE 2: Idempotent Retry Storm
# ------------------------------------------------------------------------------
echo -e "\n${BLUE}[2/4] PROBE 2: Idempotent Retry Storm (K=30 parallel transfers with same key)${NC}"
# Create recipient wallet B
RECIPIENT_RESP=$(curl -s -X POST "${BASE_URL}/wallets" \
  -H "Content-Type: application/json" \
  -d "{\"user_id\":\"recipient_$(date +%s)_$RANDOM\", \"initial_balance_paise\":0}")
WALLET_B_ID=$(echo "$RECIPIENT_RESP" | grep -o '"id":"[^"]*' | cut -d'"' -f4)

SHARED_KEY="storm_key_$(date +%s)_$RANDOM"
TEMP_DIR=$(mktemp -d)
PIDS=()

for i in {1..30}; do
  curl -s -w "\nHTTP_STATUS:%{http_code}" -X POST "${BASE_URL}/transfers" \
    -H "Content-Type: application/json" \
    -H "X-Request-ID: retry-storm-$i" \
    -d "{\"from\":\"${CHOSEN_WALLET_ID}\", \"to\":\"${WALLET_B_ID}\", \"amount_paise\":2000, \"idempotency_key\":\"${SHARED_KEY}\"}" \
    > "${TEMP_DIR}/storm_$i.txt" &
  PIDS+=($!)
done

for pid in "${PIDS[@]}"; do
  wait "$pid"
done

SUCCESS_RESPONSES=0
TRANSFER_IDS=()
for f in "${TEMP_DIR}"/storm_*.txt; do
  STATUS=$(grep "HTTP_STATUS" "$f" | cut -d':' -f2)
  if [ "$STATUS" -eq 201 ] || [ "$STATUS" -eq 200 ]; then
    SUCCESS_RESPONSES=$((SUCCESS_RESPONSES + 1))
    TID=$(grep -o '"id":"[^"]*' "$f" | head -n 1 | cut -d'"' -f4 || echo "")
    if [ -n "$TID" ]; then
      TRANSFER_IDS+=("$TID")
    fi
  fi
done

UNIQUE_TRANSFERS=$(printf "%s\n" "${TRANSFER_IDS[@]}" | sort -u | wc -l | tr -d ' ')

# Verify final balances
BAL_A=$(curl -s "${BASE_URL}/wallets/${CHOSEN_WALLET_ID}" | grep -o '"balance_paise":[0-9]*' | cut -d':' -f2)
BAL_B=$(curl -s "${BASE_URL}/wallets/${WALLET_B_ID}" | grep -o '"balance_paise":[0-9]*' | cut -d':' -f2)

if [ "$SUCCESS_RESPONSES" -eq 30 ] && [ "$UNIQUE_TRANSFERS" -eq 1 ] && [ "$BAL_A" -eq 8000 ] && [ "$BAL_B" -eq 2000 ]; then
  echo -e "${GREEN}✓ PROBE 2 PASSED: 30 retries applied exactly once! Balances: A=${BAL_A}p, B=${BAL_B}p (Zero double debits)${NC}"
else
  echo -e "${RED}✗ PROBE 2 FAILED: Successes=${SUCCESS_RESPONSES}/30, UniqueTransfers=${UNIQUE_TRANSFERS}, A=${BAL_A}, B=${BAL_B}${NC}"
  exit 1
fi
rm -rf "${TEMP_DIR}"

# Test 409 Conflict when same key is reused with different payload
CONFLICT_RESP=$(curl -s -w "\nHTTP_STATUS:%{http_code}" -X POST "${BASE_URL}/transfers" \
  -H "Content-Type: application/json" \
  -d "{\"from\":\"${CHOSEN_WALLET_ID}\", \"to\":\"${WALLET_B_ID}\", \"amount_paise\":5000, \"idempotency_key\":\"${SHARED_KEY}\"}")
CONFLICT_STATUS=$(echo "$CONFLICT_RESP" | grep "HTTP_STATUS" | cut -d':' -f2)

if [ "$CONFLICT_STATUS" -eq 409 ]; then
  echo -e "${GREEN}✓ PROBE 2 BONUS PASSED: Same key with different payload returned 409 Conflict${NC}"
else
  echo -e "${RED}✗ PROBE 2 BONUS FAILED: Expected 409 Conflict, got ${CONFLICT_STATUS}${NC}"
fi

# ------------------------------------------------------------------------------
# PROBE 3: Conservation Under Contention
# ------------------------------------------------------------------------------
echo -e "\n${BLUE}[3/4] PROBE 3: Conservation Under Heavy Contention (Pool of 4 wallets, bidirectional bursts)${NC}"
W1=$(curl -s -X POST "${BASE_URL}/wallets" -H "Content-Type: application/json" -d "{\"user_id\":\"pool1_$(date +%s)\", \"initial_balance_paise\":10000}" | grep -o '"id":"[^"]*' | cut -d'"' -f4)
W2=$(curl -s -X POST "${BASE_URL}/wallets" -H "Content-Type: application/json" -d "{\"user_id\":\"pool2_$(date +%s)\", \"initial_balance_paise\":10000}" | grep -o '"id":"[^"]*' | cut -d'"' -f4)
W3=$(curl -s -X POST "${BASE_URL}/wallets" -H "Content-Type: application/json" -d "{\"user_id\":\"pool3_$(date +%s)\", \"initial_balance_paise\":10000}" | grep -o '"id":"[^"]*' | cut -d'"' -f4)
W4=$(curl -s -X POST "${BASE_URL}/wallets" -H "Content-Type: application/json" -d "{\"user_id\":\"pool4_$(date +%s)\", \"initial_balance_paise\":10000}" | grep -o '"id":"[^"]*' | cut -d'"' -f4)

INITIAL_TOTAL=40000
POOL=("$W1" "$W2" "$W3" "$W4")
echo "Initialized 4 wallets with 10,000 paise each. Initial Sum = ${INITIAL_TOTAL} paise."

TEMP_DIR=$(mktemp -d)
PIDS=()

for i in {1..60}; do
  FROM_IDX=$((RANDOM % 4))
  TO_IDX=$(( (FROM_IDX + 1 + (RANDOM % 3)) % 4 ))
  FROM_W="${POOL[$FROM_IDX]}"
  TO_W="${POOL[$TO_IDX]}"
  AMT=$(( 500 + (RANDOM % 1500) ))
  KEY="contention_${i}_$(date +%s)_$RANDOM"

  curl -s -X POST "${BASE_URL}/transfers" \
    -H "Content-Type: application/json" \
    -H "X-Request-ID: contention-$i" \
    -d "{\"from\":\"${FROM_W}\", \"to\":\"${TO_W}\", \"amount_paise\":${AMT}, \"idempotency_key\":\"${KEY}\"}" \
    > "${TEMP_DIR}/res_$i.json" &
  PIDS+=($!)
done

for pid in "${PIDS[@]}"; do
  wait "$pid"
done

# Read back all balances and assert no negative balance and strict sum conservation
B1=$(curl -s "${BASE_URL}/wallets/${W1}" | grep -o '"balance_paise":[0-9]*' | cut -d':' -f2)
B2=$(curl -s "${BASE_URL}/wallets/${W2}" | grep -o '"balance_paise":[0-9]*' | cut -d':' -f2)
B3=$(curl -s "${BASE_URL}/wallets/${W3}" | grep -o '"balance_paise":[0-9]*' | cut -d':' -f2)
B4=$(curl -s "${BASE_URL}/wallets/${W4}" | grep -o '"balance_paise":[0-9]*' | cut -d':' -f2)

FINAL_TOTAL=$(( B1 + B2 + B3 + B4 ))

echo "Final Balances: W1=${B1}p, W2=${B2}p, W3=${B3}p, W4=${B4}p. Final Sum = ${FINAL_TOTAL} paise."

if [ "$FINAL_TOTAL" -eq "$INITIAL_TOTAL" ] && [ "$B1" -ge 0 ] && [ "$B2" -ge 0 ] && [ "$B3" -ge 0 ] && [ "$B4" -ge 0 ]; then
  echo -e "${GREEN}✓ PROBE 3 PASSED: Conservation Invariant strictly maintained (${FINAL_TOTAL}p == ${INITIAL_TOTAL}p, no overdraft)${NC}"
else
  echo -e "${RED}✗ PROBE 3 FAILED: Money was created or destroyed! Initial=${INITIAL_TOTAL}, Final=${FINAL_TOTAL}${NC}"
  exit 1
fi
rm -rf "${TEMP_DIR}"

# ------------------------------------------------------------------------------
# PROBE 4: R3 Live Follow-up — Reversal / Refund Transfer
# ------------------------------------------------------------------------------
echo -e "\n${BLUE}[4/4] PROBE 4: R3 Live Follow-up Reversal (POST /transfers/{id}/reverse)${NC}"
# Execute a clean transfer of 3000 paise from W1 to W2
REV_KEY="orig_transfer_$(date +%s)"
TRANSFER_RES=$(curl -s -X POST "${BASE_URL}/transfers" \
  -H "Content-Type: application/json" \
  -d "{\"from\":\"${W1}\", \"to\":\"${W2}\", \"amount_paise\":3000, \"idempotency_key\":\"${REV_KEY}\"}")
ORIGINAL_TID=$(echo "$TRANSFER_RES" | grep -o '"id":"[^"]*' | head -n 1 | cut -d'"' -f4)

# Execute Reversal
REVERSE_KEY="rev_key_$(date +%s)"
REVERSE_RES=$(curl -s -X POST "${BASE_URL}/transfers/${ORIGINAL_TID}/reverse" \
  -H "Content-Type: application/json" \
  -d "{\"idempotency_key\":\"${REVERSE_KEY}\"}")
REV_STATUS=$(echo "$REVERSE_RES" | grep -o '"status":"[^"]*' | cut -d'"' -f4)

# Attempting duplicate reversal must be rejected
DUP_REV=$(curl -s -w "\nHTTP_STATUS:%{http_code}" -X POST "${BASE_URL}/transfers/${ORIGINAL_TID}/reverse" \
  -H "Content-Type: application/json" \
  -d "{\"idempotency_key\":\"rev_dup_$(date +%s)\"}")
DUP_STATUS=$(echo "$DUP_REV" | grep "HTTP_STATUS" | cut -d':' -f2)

if [ "$REV_STATUS" == "REVERSED" ] && [ "$DUP_STATUS" -eq 409 ]; then
  echo -e "${GREEN}✓ PROBE 4 (R3 LIVE) PASSED: Reversal succeeded, duplicate reversal cleanly blocked with 409${NC}"
else
  echo -e "${RED}✗ PROBE 4 FAILED: RevStatus=${REV_STATUS}, DupStatus=${DUP_STATUS}${NC}"
  exit 1
fi

echo -e "\n======================================================================"
echo -e "${GREEN} ALL 4 LIVE CONCURRENCY PROBES PASSED SUCCESSFULLY!${NC}"
echo -e "======================================================================"
