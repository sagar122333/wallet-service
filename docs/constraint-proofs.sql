-- ---------------------------------------------------------------------------
-- Constraint proofs.
--
-- Run against a database that has had the Flyway migrations applied:
--
--     mysql -u root --force --table wallet < docs/constraint-proofs.sql
--
-- EVERY "ERROR 1062" / "ERROR 3819" PRINTED BY THIS SCRIPT IS THE EXPECTED
-- RESULT. Each block deliberately attempts something the schema must refuse.
-- --force makes the client carry on after each rejection so the whole script
-- runs. The blocks that print PASS/FAIL are the ones asserting that something
-- is ALLOWED.
--
-- Verified on MySQL-compatible 10.11 (MariaDB) and written for MySQL 8.
-- ---------------------------------------------------------------------------


SET @w1 = 'wal-1', @w2 = 'wal-2';

INSERT INTO wallets (wallet_id, user_id, currency, balance_minor, version, status, created_at, updated_at)
VALUES (@w1, 'user-asha', 'INR', 0, 0, 'ACTIVE', NOW(6), NOW(6)),
       (@w2, 'user-bilal', 'INR', 0, 0, 'ACTIVE', NOW(6), NOW(6));

-- ---------------------------------------------------------------- 1. one wallet per user+currency
SELECT '--- 1. uq_wallets_user_currency' AS test;
INSERT INTO wallets (wallet_id, user_id, currency, balance_minor, version, status, created_at, updated_at)
VALUES ('wal-dup', 'user-asha', 'INR', 0, 0, 'ACTIVE', NOW(6), NOW(6));

-- ---------------------------------------------------------------- 2. balance cannot go negative
SELECT '--- 2. ck_wallets_balance_non_negative' AS test;
UPDATE wallets SET balance_minor = -1 WHERE wallet_id = @w1;

-- ---------------------------------------------------------------- 3. the idempotency constraint
SELECT '--- 3. same request_id on the SAME wallet is rejected' AS test;
INSERT INTO ledger_entries (entry_id, wallet_id, entry_type, amount_minor, currency,
                            balance_after_minor, request_id, transfer_id, reversal_of, reason,
                            initiator_type, initiated_by, created_at)
VALUES ('e1', @w1, 'CREDIT', 10000, 'INR', 10000, 'key-1', NULL, NULL, NULL, 'USER', 'user-asha', NOW(6));
INSERT INTO ledger_entries (entry_id, wallet_id, entry_type, amount_minor, currency,
                            balance_after_minor, request_id, transfer_id, reversal_of, reason,
                            initiator_type, initiated_by, created_at)
VALUES ('e2', @w1, 'CREDIT', 10000, 'INR', 20000, 'key-1', NULL, NULL, NULL, 'USER', 'user-asha', NOW(6));

SELECT '--- 4. the SAME request_id on a DIFFERENT wallet is allowed (both transfer legs)' AS test;
INSERT INTO ledger_entries (entry_id, wallet_id, entry_type, amount_minor, currency,
                            balance_after_minor, request_id, transfer_id, reversal_of, reason,
                            initiator_type, initiated_by, created_at)
VALUES ('e3', @w2, 'CREDIT', 10000, 'INR', 10000, 'key-1', 'trf-1', NULL, NULL, 'USER', 'user-asha', NOW(6));
SELECT IF(COUNT(*) = 2, 'PASS: one key, two wallets, two rows', 'FAIL') AS result
FROM ledger_entries WHERE request_id = 'key-1';

-- ---------------------------------------------------------------- 5. reversed at most once
SELECT '--- 5. uq_ledger_reversal_of' AS test;
INSERT INTO ledger_entries (entry_id, wallet_id, entry_type, amount_minor, currency,
                            balance_after_minor, request_id, transfer_id, reversal_of, reason,
                            initiator_type, initiated_by, created_at)
VALUES ('r1', @w1, 'DEBIT', 10000, 'INR', 0, 'rev-1', NULL, 'e1', 'duplicate charge', 'OPS', 'ops-1', NOW(6));
-- a SECOND reversal of e1, under a different key, must be rejected by the constraint
INSERT INTO ledger_entries (entry_id, wallet_id, entry_type, amount_minor, currency,
                            balance_after_minor, request_id, transfer_id, reversal_of, reason,
                            initiator_type, initiated_by, created_at)
VALUES ('r2', @w1, 'DEBIT', 10000, 'INR', 0, 'rev-2', NULL, 'e1', 'again', 'OPS', 'ops-1', NOW(6));

SELECT '--- 6. many NULL reversal_of rows coexist under the same unique index' AS test;
SELECT IF(COUNT(*) >= 2, 'PASS: NULLs do not collide in a unique index', 'FAIL') AS result
FROM ledger_entries WHERE reversal_of IS NULL;

-- ---------------------------------------------------------------- 7. a reason only on a reversal
SELECT '--- 7. ck_ledger_reason_iff_reversal' AS test;
INSERT INTO ledger_entries (entry_id, wallet_id, entry_type, amount_minor, currency,
                            balance_after_minor, request_id, transfer_id, reversal_of, reason,
                            initiator_type, initiated_by, created_at)
VALUES ('e4', @w1, 'CREDIT', 100, 'INR', 100, 'key-4', NULL, NULL, 'why is this here', 'USER', 'user-asha', NOW(6));

-- ---------------------------------------------------------------- 8. amount must be positive
SELECT '--- 8. ck_ledger_amount_positive' AS test;
INSERT INTO ledger_entries (entry_id, wallet_id, entry_type, amount_minor, currency,
                            balance_after_minor, request_id, transfer_id, reversal_of, reason,
                            initiator_type, initiated_by, created_at)
VALUES ('e5', @w1, 'DEBIT', -100, 'INR', 0, 'key-5', NULL, NULL, NULL, 'USER', 'user-asha', NOW(6));

-- ---------------------------------------------------------------- 9. keyset pagination
SELECT '--- 9. keyset pagination walks every row exactly once' AS test;
INSERT INTO ledger_entries (entry_id, wallet_id, entry_type, amount_minor, currency,
                            balance_after_minor, request_id, transfer_id, reversal_of, reason,
                            initiator_type, initiated_by, created_at)
VALUES ('p1', @w2, 'CREDIT', 1, 'INR', 1, 'p-1', NULL, NULL, NULL, 'USER', 'u', '2026-01-01 10:00:00.000000'),
       ('p2', @w2, 'CREDIT', 1, 'INR', 2, 'p-2', NULL, NULL, NULL, 'USER', 'u', '2026-01-01 10:00:00.000000'),
       ('p3', @w2, 'CREDIT', 1, 'INR', 3, 'p-3', NULL, NULL, NULL, 'USER', 'u', '2026-01-01 10:00:00.000000'),
       ('p4', @w2, 'CREDIT', 1, 'INR', 4, 'p-4', NULL, NULL, NULL, 'USER', 'u', '2026-01-01 10:00:01.000000');

-- page 1, limit 2. NOTE all of p1..p3 share one microsecond timestamp - exactly the tie the
-- entry_id tie-breaker exists for.
SELECT 'page 1' AS page, entry_id FROM ledger_entries
WHERE wallet_id = @w2 AND (NULL IS NULL)
ORDER BY created_at DESC, entry_id DESC LIMIT 2;

-- page 2, strictly older than (10:00:01, p4) ... continuing from the last row of page 1
SELECT 'page 2' AS page, entry_id FROM ledger_entries
WHERE wallet_id = @w2
  AND (created_at < '2026-01-01 10:00:00.000000'
       OR (created_at = '2026-01-01 10:00:00.000000' AND entry_id < 'p3'))
ORDER BY created_at DESC, entry_id DESC LIMIT 2;

SELECT '--- 10. the index is actually used (no filesort)' AS test;
EXPLAIN SELECT * FROM ledger_entries
WHERE wallet_id = @w2 AND (created_at < NOW(6) OR (created_at = NOW(6) AND entry_id < 'zzz'))
ORDER BY created_at DESC, entry_id DESC LIMIT 5;

-- ---------------------------------------------------------------- 11. reconciliation views
SELECT '--- 11. reconciliation views' AS test;
UPDATE wallets SET balance_minor = (
    SELECT COALESCE(SUM(CASE WHEN entry_type = 'CREDIT' THEN amount_minor ELSE -amount_minor END), 0)
    FROM ledger_entries WHERE wallet_id = @w2) WHERE wallet_id = @w2;
SELECT wallet_id, cached_balance_minor, ledger_balance_minor, entry_count, in_sync
FROM wallet_reconciliation ORDER BY wallet_id;
SELECT transfer_id, leg_count, net_minor, balanced FROM transfer_reconciliation;
