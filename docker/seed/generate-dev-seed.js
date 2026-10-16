const c=require('crypto');
const AES=Buffer.from('AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8=','base64');
const HM=Buffer.from('ICEiIyQlJicoKSorLC0uLzAxMjM0NTY3ODk6Ozw9Pj8=','base64');
const enc=t=>{const iv=c.randomBytes(12);const k=c.createCipheriv('aes-256-gcm',AES,iv);const ct=Buffer.concat([k.update(t,'utf8'),k.final(),k.getAuthTag()]);return 'v1:'+Buffer.concat([iv,ct]).toString('base64');};
const bi=t=>c.createHmac('sha256',HM).update(t).digest('hex');
const H1='11111111-1111-4111-8111-111111111111',A1='00000000-0000-0000-0000-000000000001',K1='22222222-2222-4222-8222-222222222222';
const KEY='123e4567-e89b-42d3-a456-426655440000', DOC='52998224725';
console.log(`-- Dados de DESENVOLVIMENTO para a collection do Postman (titular + conta + chave EVP ativa).
-- O ms não cadastra conta/titular (a fonte é o core, decisão A2); aqui entram direto no banco.
-- PII cifrada com as chaves de DEV do .env.example (PIX_CRYPTO_AES_KEY/HMAC_KEY): se trocá-las, regenere este arquivo.
-- Idempotente (ON CONFLICT DO NOTHING). Gerado por docker/seed/generate-dev-seed.js.
INSERT INTO account_holder (id, holder_type, document_enc, document_hash, document_masked, name, status, created_by, updated_by)
VALUES ('${H1}', 'CPF', '${enc(DOC)}', '${bi(DOC)}', '***.***.***-25', 'Fulano de Tal', 'ACTIVE', 'seed', 'seed')
ON CONFLICT DO NOTHING;
INSERT INTO payment_account (id, holder_id, ispb, branch, account_number, account_type, status, opened_at, created_by, updated_by)
VALUES ('${A1}', '${H1}', '12345678', '0001', '100001', 'CACC', 'ACTIVE', '2020-01-01T00:00:00Z', 'seed', 'seed')
ON CONFLICT DO NOTHING;
INSERT INTO pix_key (id, account_id, key_type, key_value_enc, key_hash, key_masked, status, dict_cid, dict_registered_at, created_by, updated_by)
VALUES ('${K1}', '${A1}', 'EVP', '${enc(KEY)}', '${bi('key:'+KEY)}', '123e****-****-****-****-********0000', 'ACTIVE', 'cid-seed', now(), 'seed', 'seed')
ON CONFLICT DO NOTHING;`);
