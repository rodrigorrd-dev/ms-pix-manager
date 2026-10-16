# Backup e recuperação de desastre (DR) — PLANO

> **Status: plano de projeto, NÃO executado.** Nada aqui foi testado em ambiente real. Os alvos de RPO/RTO são **propostas** para aprovação do negócio; um exercício de restauração (§5) é pré-requisito do go-live.

## 1. O que precisa sobreviver
| Ativo | Onde | Se perder |
|---|---|---|
| Dados do ms (cobranças, chaves, ordens, limites, recebidos, devoluções, outbox, inbox, auditoria) | PostgreSQL | perda de dinheiro/estado: **crítico** |
| Chaves AES/HMAC | cofre/KMS | **todo o PII fica ilegível/irrecuperável**, mesmo com o banco restaurado |
| Eventos em trânsito | outbox no Postgres → Kafka | reentrega a partir da outbox (retenção `PIX_OUTBOX_RETENTION`, 7 d) |
| Tópicos Kafka | cluster Kafka | reproduzíveis a partir da outbox dentro da retenção; entradas do SPI precisam do spi-gateway |
| Configuração/IaC, schemas Avro, realm do IdP | repositório / registry / IdP | recriáveis; versionar e fazer backup do registry |

## 2. Alvos propostos
- **RPO** Postgres ≤ 5 min (WAL contínuo). **RTO** ≤ 1 h para restaurar o banco e religar o serviço.
- Kafka: replicação ≥ 3 e `min.insync.replicas=2` em um cluster multi-AZ; DR do Kafka é replicação (MirrorMaker/Cluster Linking) **ou** reconstrução a partir da outbox.

## 3. Backup
- **Postgres:** base backup periódico (diário) + **arquivamento contínuo de WAL** (PITR) em armazenamento fora da zona do banco, criptografado, com retenção alinhada à regulação. Réplica síncrona/assíncrona em outra AZ para failover rápido (tratado pelo provedor/Patroni).
- **Chaves:** KMS/cofre com versionamento e backup; **guarde TODAS as versões de AES e a HMAC** pelo maior prazo de retenção dos backups do banco (veja [rotacao-de-chave.md](rotacao-de-chave.md)). Perder a chave = perder o dado.
- **Schema Registry:** o tópico `_schemas` é a fonte; faça backup/replicação. Contratos também estão no repositório (`src/main/resources/avro`).
- **Kafka Connect:** os tópicos `_connect_*` guardam a configuração; o conector é recriável pelo `docker/debezium/register-connector.sh`.

## 4. Restauração (ordem)
1. Restaurar o Postgres (PITR até o instante desejado) **e conferir** `databasechangelog` (versão do schema).
2. Disponibilizar as chaves no cofre (todas as versões).
3. Subir Kafka, Schema Registry e IdP.
4. **Slot do Debezium:** um banco restaurado **não tem o slot** (`pix_outbox_slot`) nem a posição do WAL. Recriar o conector (`snapshot.mode=no_data`) começa do ponto atual: eventos gravados na `outbox_event` **antes** e ainda não publicados **não** saem sozinhos. Para esses eventos use a **republicação da outbox** (§4.1): copiar as linhas da `outbox_event` com `id` novo faz o Debezium publicá-las de novo. Os consumidores são idempotentes (inbox pelo `eventId` do envelope), então republicar é seguro.
5. Subir o app (readiness `UP`), depois o conector. Rodar a conciliação: ordens `RESERVED`/`SENT_TO_SPI` × status no SPI/core.
6. Liberar tráfego.

### 4.1 Republicação da outbox (testado em dev)
O Debezium só roteia **inserções**. Para republicar, insira cópias das linhas (o `payload` Avro e o `eventId` do envelope não mudam; só o `id` da linha, que é a chave primária):
```sql
-- exemplo: tudo que foi gravado desde o instante da falha (ajuste o filtro; ordene por created_at preserva a ordem por agregado)
insert into outbox_event (id, aggregate_type, aggregate_id, event_type, event_version, payload, trace_parent, created_at)
select gen_random_uuid(), aggregate_type, aggregate_id, event_type, event_version, payload, trace_parent, now()
  from outbox_event where created_at >= '2026-10-16 02:00:00+00' order by created_at;
```
Validado em dev: a cópia de um evento `PixKeyDeleted` apareceu como nova mensagem em `pix.key.v1` (mesma chave, `id` de cabeçalho novo). Cuidados: (1) quem consome precisa deduplicar pelo `eventId` do envelope (o inbox do ms faz; confirme o spi-gateway); (2) a ordem entre republicações e eventos novos não é garantida, então republique com o tráfego parado ou em janela curta; (3) só alcança a retenção da outbox (`PIX_OUTBOX_RETENTION`, 7 d).

## 5. Exercício de DR (obrigatório antes de produção)
Roteiro mínimo, em hml, com cronômetro: (a) restaurar um backup + WAL até um instante conhecido; (b) subir o stack apontando para o banco restaurado; (c) medir RPO (último evento recuperado) e RTO; (d) validar leitura de PII (chave certa) e `scripts/validate-api.py` sem as partes de seed; (e) registrar tempo, falhas e ajustes aqui. Repetir semestralmente.

## 6. Pendências
Automação do restore; ferramenta/endpoint para a republicação da outbox (hoje é SQL manual, §4.1); definição formal de RPO/RTO com o negócio; teste de failover do banco (a ser feito junto do teste de caos, `scripts/chaos-test.py`).
