# Rotação da chave AES de PII

**Quando:** rotina (ex.: anual), suspeita de vazamento da chave, saída de pessoa com acesso. **Escopo:** chave **AES** (dado cifrado em repouso: documentos, valor da chave Pix). A chave **HMAC** (índice cego) **não** tem rotação — veja o fim.

## Como funciona
O ciphertext leva a versão: `v1:<base64>`. O app tem um **chaveiro**: cifra sempre com a versão ATUAL (`PIX_CRYPTO_KEY_VERSION`) e decifra qualquer versão presente em `pix.crypto.previous-aes-keys`. Um `ApplicationRunner` (`PiiReencryptionRunner`) migra as linhas antigas para a versão atual, em lotes, de forma idempotente. Cobre as 7 colunas `*_enc`: `account_holder.document_enc`, `pix_key.key_value_enc`, `pix_charge.debtor_document_enc`, `pix_received.payer_document_enc`, `payment_order.dest_document_enc`, `scheduled_payment.dest_document_enc`, `pix_automatic_authorization.receiver_document_enc`.

## Passo a passo
1. **Gere e guarde** a chave nova no cofre/KMS: `openssl rand -base64 32` (≠ da HMAC e ≠ das antigas). Faça backup **das duas** (a antiga ainda é necessária até o passo 5).
2. **Deploy 1 — o app passa a ler as duas, escrever com a nova** (todas as réplicas):
   ```
   PIX_CRYPTO_AES_KEY=<nova>           PIX_CRYPTO_KEY_VERSION=v2
   PIX_CRYPTO_PREVIOUS_AES_KEYS_V1=<antiga>
   ```
   (O `application.yml` aceita chaves antigas das versões `v1`, `v2` e `v3`; para mais versões, acrescente entradas em `pix.crypto.previous-aes-keys`.) Valide: leituras antigas continuam funcionando; gravações novas saem `v2:`.
3. **Re-criptografe** — em **uma única** instância (ou job), adicione `PIX_CRYPTO_REENCRYPT=true` (lote: `PIX_CRYPTO_REENCRYPT_BATCH`, padrão 500). Aguarde o log `re-criptografia concluída para a versão v2: {…}`. Linhas ilegíveis aparecem como `….falhas` e erro no log (chave antiga faltando?).
4. **Confira** que não sobrou nada na versão antiga:
   ```sql
   select 'account_holder', count(*) from account_holder where document_enc not like 'v2:%'
   union all select 'pix_key', count(*) from pix_key where key_value_enc not like 'v2:%'
   union all select 'pix_charge', count(*) from pix_charge where debtor_document_enc is not null and debtor_document_enc not like 'v2:%'
   union all select 'pix_received', count(*) from pix_received where payer_document_enc is not null and payer_document_enc not like 'v2:%'
   union all select 'payment_order', count(*) from payment_order where dest_document_enc is not null and dest_document_enc not like 'v2:%';
   ```
   Todos devem ser 0 (inclua `scheduled_payment` e `pix_automatic_authorization` quando os módulos existirem).
5. **Deploy 2 — desligue a flag** (`PIX_CRYPTO_REENCRYPT` removido) e, só depois de **backups do banco anteriores à rotação** terem expirado ou sido re-cifrados, remova `PIX_CRYPTO_PREVIOUS_AES_KEYS_V1`. Backups antigos do banco só abrem com a chave antiga: mantenha-a arquivada no cofre pelo tempo de retenção dos backups.

## Reversão
Antes do passo 5, basta voltar o deploy: a chave antiga ainda decifra tudo. Dados já migrados para `v2` precisam da chave nova — mantenha as duas no chaveiro até estabilizar. Há o caminho inverso testado (`PiiReencryptionIT` migra v1→v2 e volta v2→v1).

## Limites conhecidos
- **HMAC (índice cego) sem rotação.** `document_hash`/`key_hash` são HMAC determinísticos usados em busca por igualdade; trocar a chave HMAC invalida todos. Uma rotação exigiria coluna de índice nova, escrita dupla, backfill (precisa decifrar e recalcular) e leitura por ambos os índices — **não implementado**.
- A re-criptografia roda no start do app (não há endpoint/CLI). Em tabelas muito grandes, rode fora do horário de pico; cada lote é uma transação curta e a atualização é condicionada ao valor antigo (segura com tráfego).
- Sem auditoria da rotação além dos logs.
