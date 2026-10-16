-- Dados de DESENVOLVIMENTO para a collection do Postman (titular + conta + chave EVP ativa).
-- O ms não cadastra conta/titular (a fonte é o core, decisão A2); aqui entram direto no banco.
-- PII cifrada com as chaves de DEV do .env.example (PIX_CRYPTO_AES_KEY/HMAC_KEY): se trocá-las, regenere este arquivo.
-- Idempotente (ON CONFLICT DO NOTHING). Gerado por docker/seed/generate-dev-seed.js.
INSERT INTO account_holder (id, holder_type, document_enc, document_hash, document_masked, name, status, created_by, updated_by)
VALUES ('11111111-1111-4111-8111-111111111111', 'CPF', 'v1:8ZvKYPCEou06oFVEyeByVJBbMZTXfCiyO6TNDZsw8p2k0v0ZOIU/', 'b10f2a19181743fcede7fbe1376dd1edca78237f2fe155ecfb21d97ee964d08a', '***.***.***-25', 'Fulano de Tal', 'ACTIVE', 'seed', 'seed')
ON CONFLICT DO NOTHING;
INSERT INTO payment_account (id, holder_id, ispb, branch, account_number, account_type, status, opened_at, created_by, updated_by)
VALUES ('00000000-0000-0000-0000-000000000001', '11111111-1111-4111-8111-111111111111', '12345678', '0001', '100001', 'CACC', 'ACTIVE', '2020-01-01T00:00:00Z', 'seed', 'seed')
ON CONFLICT DO NOTHING;
INSERT INTO pix_key (id, account_id, key_type, key_value_enc, key_hash, key_masked, status, dict_cid, dict_registered_at, created_by, updated_by)
VALUES ('22222222-2222-4222-8222-222222222222', '00000000-0000-0000-0000-000000000001', 'EVP', 'v1:FgzbOT83Hg2W8FpXNpXsZ35lp1DsEV2WEMTTTxATvNSVnAu09C2r1Kzn6V1e1X8XyL+yXnZLn4tG2uh3QVqSXA==', 'f1f69b08716bd606810eda79f6aff7136d6cd8923392ce9c10878f16d34fd602', '123e****-****-****-****-********0000', 'ACTIVE', 'cid-seed', now(), 'seed', 'seed')
ON CONFLICT DO NOTHING;
