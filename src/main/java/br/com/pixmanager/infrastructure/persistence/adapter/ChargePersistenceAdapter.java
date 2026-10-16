package br.com.pixmanager.infrastructure.persistence.adapter;

import br.com.pixmanager.infrastructure.persistence.entity.ChargeEntity;
import br.com.pixmanager.infrastructure.persistence.adapter.mapper.ChargeEntityMapper;
import br.com.pixmanager.infrastructure.persistence.repository.ChargeRepository;
import br.com.pixmanager.infrastructure.persistence.repository.ChargeSpecifications;

import br.com.pixmanager.core.port.in.charge.FindChargeUseCase.ChargeSearch;
import br.com.pixmanager.core.port.out.persistence.ChargePersistencePortOut;
import br.com.pixmanager.core.port.out.persistence.ChargeQueryPortOut;
import br.com.pixmanager.core.domain.entity.AdditionalInfo;
import br.com.pixmanager.core.domain.entity.Charge;
import br.com.pixmanager.core.domain.enums.ChargeType;
import br.com.pixmanager.core.domain.entity.PixLocation;
import br.com.pixmanager.core.domain.entity.TxId;
import br.com.pixmanager.shared.exception.ConflictException;
import br.com.pixmanager.shared.constant.ErrorCodes;
import br.com.pixmanager.shared.exception.PreconditionFailedException;
import br.com.pixmanager.shared.model.dto.PageQuery;
import br.com.pixmanager.shared.model.dto.PageResult;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Escrita (porta de persistência) e leitura (porta de consulta, CQRS-lite) do módulo. Transação aqui só nas leituras e como
 * REQUIRED na escrita (junta-se à do caso de uso). {@code saveAndFlush} faz o conflito otimista/unicidade aparecer dentro do try.
 */
@Component
public class ChargePersistenceAdapter implements ChargePersistencePortOut, ChargeQueryPortOut {

    private final ChargeRepository repository;
    private final ChargeEntityMapper mapper;
    private final JdbcClient jdbc;

    private final com.fasterxml.jackson.databind.ObjectMapper json;

    public ChargePersistenceAdapter(ChargeRepository repository, ChargeEntityMapper mapper, JdbcClient jdbc,
                                    com.fasterxml.jackson.databind.ObjectMapper json) {
        this.json = json;
        this.repository = repository;
        this.mapper = mapper;
        this.jdbc = jdbc;
    }

    @Override
    @Transactional
    public Charge save(Charge charge) {
        Optional<ChargeEntity> existing = repository.findById(charge.id());
        boolean isNew = existing.isEmpty();
        ChargeEntity entity = existing.orElseGet(() -> new ChargeEntity(charge.id()));
        if (!isNew && entity.getVersion() != charge.version()) {
            throw new PreconditionFailedException("A cobrança foi alterada por outra requisição (versão desatualizada).");
        }
        int previousRevision = entity.getRevision();
        mapper.apply(charge, entity, isNew);
        ChargeEntity saved;
        try {
            saved = repository.saveAndFlush(entity);
        } catch (OptimisticLockingFailureException e) {
            throw new PreconditionFailedException("A cobrança foi alterada por outra requisição (versão desatualizada).");
        } catch (DataIntegrityViolationException e) {
            // corrida entre dois PUT do mesmo txid: o índice único por (documento do recebedor, txid) decide
            throw new ConflictException(ErrorCodes.TXID_CONFLICT, "Já existe cobrança com este txid para o recebedor.");
        }
        if (isNew) {
            replaceAdditionalInfo(saved.getId(), charge.additionalInfo());
        } else if (charge.revision() != previousRevision) {
            snapshotRevision(charge);
        }
        return load(saved, charge.additionalInfo(), charge.location());
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<Charge> findByReceiverHashAndTxid(String receiverDocumentHash, TxId txid) {
        return repository.findByReceiverDocumentHashAndTxid(receiverDocumentHash, txid.value()).map(this::load);
    }

    @Override
    @Transactional(readOnly = true)
    public List<Charge> findByTxid(TxId txid) {
        return loadAll(repository.findByTxid(txid.value()));
    }

    @Override
    @Transactional(readOnly = true)
    public PageResult<Charge> search(Set<UUID> accounts, boolean allAccounts, ChargeSearch search, PageQuery page) {
        Specification<ChargeEntity> spec = Specification.allOf(
                ChargeSpecifications.accessibleTo(accounts, allAccounts),
                ChargeSpecifications.createdBetween(search.from(), search.to()),
                ChargeSpecifications.hasStatus(search.status().map(Enum::name).orElse(null)),
                ChargeSpecifications.hasDebtorHash(search.debtor().map(mapper::hashOf).orElse(null)),
                ChargeSpecifications.locationPresent(search.locationPresent().orElse(null)));
        Page<ChargeEntity> result = repository.findAll(spec,
                PageRequest.of(page.page(), page.size(), Sort.by(Sort.Direction.DESC, "createdAt")));
        return new PageResult<>(loadAll(result.getContent()), page.page(), page.size(), result.getTotalElements());
    }

    private Charge load(ChargeEntity e) {
        return load(e, readAdditionalInfo(e.getId()), readLocation(e.getLocationId()).orElse(null));
    }

    /** Carrega os filhos da lista INTEIRA em 2 queries (info adicional + localizações), em vez de 2 por cobrança. */
    private List<Charge> loadAll(List<ChargeEntity> entities) {
        if (entities.isEmpty()) {
            return List.of();
        }
        List<UUID> chargeIds = entities.stream().map(ChargeEntity::getId).toList();
        List<UUID> locationIds = entities.stream().map(ChargeEntity::getLocationId).filter(Objects::nonNull).distinct().toList();
        Map<UUID, List<AdditionalInfo>> infoByCharge = readAdditionalInfo(chargeIds);
        Map<UUID, PixLocation> locations = readLocations(locationIds);
        return entities.stream()
                .map(e -> mapper.toDomain(e, infoByCharge.getOrDefault(e.getId(), List.of()), locations.get(e.getLocationId())))
                .toList();
    }

    private Charge load(ChargeEntity e, List<AdditionalInfo> info, PixLocation location) {
        PixLocation effective = location != null && location.id().equals(e.getLocationId()) ? location
                : readLocation(e.getLocationId()).orElse(null);
        return mapper.toDomain(e, info, effective);
    }

    private void replaceAdditionalInfo(UUID chargeId, List<AdditionalInfo> info) {
        // só roda para cobrança NOVA (sem filhos): não há o que apagar
        int position = 1;
        for (AdditionalInfo item : info) {
            jdbc.sql("INSERT INTO pix_charge_additional_info (charge_id, position, name, value) VALUES (:id, :pos, :name, :value)")
                    .param("id", chargeId).param("pos", (short) position++).param("name", item.name()).param("value", item.value())
                    .update();
        }
    }

    private Map<UUID, List<AdditionalInfo>> readAdditionalInfo(List<UUID> chargeIds) {
        Map<UUID, List<AdditionalInfo>> byCharge = new HashMap<>();
        jdbc.sql("SELECT charge_id, name, value FROM pix_charge_additional_info WHERE charge_id IN (:ids) ORDER BY charge_id, position")
                .param("ids", chargeIds)
                .query((rs, n) -> {
                    byCharge.computeIfAbsent(rs.getObject("charge_id", UUID.class), k -> new ArrayList<>())
                            .add(new AdditionalInfo(rs.getString("name"), rs.getString("value")));
                    return null;
                }).list();
        return byCharge;
    }

    private Map<UUID, PixLocation> readLocations(List<UUID> locationIds) {
        if (locationIds.isEmpty()) {
            return Map.of();
        }
        Map<UUID, PixLocation> byId = new HashMap<>();
        jdbc.sql("SELECT id, location_id, location_type, url_path FROM pix_location WHERE id IN (:ids)")
                .param("ids", locationIds)
                .query((rs, n) -> {
                    PixLocation location = new PixLocation(rs.getObject("id", UUID.class), rs.getLong("location_id"),
                            "COB".equals(rs.getString("location_type")) ? ChargeType.IMMEDIATE : ChargeType.DUE, rs.getString("url_path"));
                    byId.put(location.id(), location);
                    return null;
                }).list();
        return byId;
    }

    private List<AdditionalInfo> readAdditionalInfo(UUID chargeId) {
        return jdbc.sql("SELECT name, value FROM pix_charge_additional_info WHERE charge_id = :id ORDER BY position")
                .param("id", chargeId)
                .query((rs, n) -> new AdditionalInfo(rs.getString("name"), rs.getString("value"))).list();
    }

    /** Snapshot SEM PII (sem devedor): quem/quando/o quê mudou, para auditoria da revisão (BR-COB-08). */
    private void snapshotRevision(Charge c) {
        java.util.Map<String, Object> snapshot = new java.util.LinkedHashMap<>();       // ObjectMapper escapa todo caractere de controle
        snapshot.put("status", c.status().name());
        snapshot.put("originalAmount", c.originalAmount().toBcb());
        snapshot.put("expiresAt", c.expiresAt() == null ? null : c.expiresAt().toString());
        snapshot.put("payerRequest", c.payerRequest());
        String snapshotJson;
        try {
            snapshotJson = json.writeValueAsString(snapshot);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException("Falha ao serializar o snapshot da revisão", e);
        }
        jdbc.sql("""
                INSERT INTO pix_charge_revision (charge_id, revision, snapshot, changed_by, changed_at)
                VALUES (:id, :rev, CAST(:snap AS jsonb), :by, now())
                """)
                .param("id", c.id()).param("rev", c.revision()).param("snap", snapshotJson)
                .param("by", br.com.pixmanager.shared.util.AuthenticatedClient.clientId()).update();
    }

    private Optional<PixLocation> readLocation(UUID locationId) {
        if (locationId == null) {
            return Optional.empty();
        }
        return jdbc.sql("SELECT id, location_id, location_type, url_path FROM pix_location WHERE id = :id")
                .param("id", locationId)
                .query((rs, n) -> new PixLocation(rs.getObject("id", UUID.class), rs.getLong("location_id"),
                        "COB".equals(rs.getString("location_type")) ? ChargeType.IMMEDIATE : ChargeType.DUE, rs.getString("url_path")))
                .optional();
    }
}
