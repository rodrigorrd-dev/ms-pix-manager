package br.com.pixmanager.infrastructure.persistence.adapter;

import br.com.pixmanager.core.domain.entity.ReceivedPix;
import br.com.pixmanager.core.domain.entity.ValueComponent;
import br.com.pixmanager.core.domain.enums.ComponentType;
import br.com.pixmanager.core.port.in.receivedpix.FindReceivedPixUseCase.ReceivedPixSearch;
import br.com.pixmanager.core.port.out.persistence.ReceivedPixPersistencePortOut;
import br.com.pixmanager.infrastructure.persistence.adapter.mapper.ReceivedPixEntityMapper;
import br.com.pixmanager.infrastructure.persistence.entity.ReceivedPixEntity;
import br.com.pixmanager.infrastructure.persistence.repository.ReceivedPixRepository;
import br.com.pixmanager.infrastructure.persistence.repository.ReceivedPixSpecifications;
import br.com.pixmanager.shared.constant.ErrorCodes;
import br.com.pixmanager.shared.exception.ConflictException;
import br.com.pixmanager.shared.model.dto.PageQuery;
import br.com.pixmanager.shared.model.dto.PageResult;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

@Component
public class ReceivedPixPersistenceAdapter implements ReceivedPixPersistencePortOut {

    private final ReceivedPixRepository repository;
    private final ReceivedPixEntityMapper mapper;
    private final JdbcClient jdbc;
    private final JdbcTemplate jdbcTemplate;

    public ReceivedPixPersistenceAdapter(ReceivedPixRepository repository, ReceivedPixEntityMapper mapper, JdbcClient jdbc, JdbcTemplate jdbcTemplate) {
        this.repository = repository;
        this.mapper = mapper;
        this.jdbc = jdbc;
        this.jdbcTemplate = jdbcTemplate;
    }

    /** Só INSERT: um Pix liquidado não é alterado depois (devolução mexe em {@code refunded_total} por UPDATE atômico). */
    @Override
    @Transactional
    public ReceivedPix save(ReceivedPix pix) {
        ReceivedPixEntity entity = new ReceivedPixEntity(pix.id());
        mapper.apply(pix, entity);
        try {
            entity = repository.saveAndFlush(entity);
        } catch (DataIntegrityViolationException exception) {
            // corrida entre dois consumidores com o mesmo endToEndId: o índice único decide (BR-PIX-01)
            throw new ConflictException(ErrorCodes.CONFLICT, "Pix já registrado para este endToEndId.");
        }
        if (!pix.components().isEmpty()) {
            jdbcTemplate.batchUpdate("""
                    INSERT INTO pix_received_component (received_id, component_type, amount, agent_modality, withdrawal_provider_ispb)
                    VALUES (?, ?, ?, ?, ?)""", pix.components(), pix.components().size(), (statement, valueComponent) -> {
                statement.setObject(1, pix.id());
                statement.setString(2, valueComponent.type().name());
                statement.setBigDecimal(3, valueComponent.amount().value());
                statement.setString(4, valueComponent.agentModality());
                statement.setString(5, valueComponent.withdrawalProviderIspb());
            });
        }
        return mapper.toDomain(entity, pix.components());
    }

    @Override
    @Transactional
    public boolean reserveRefundable(UUID receivedId, br.com.pixmanager.core.domain.entity.Money amount) {
        return jdbc.sql("""
                UPDATE pix_received SET refunded_total = refunded_total + :v
                 WHERE id = :id AND refunded_total + :v <= refundable_amount
                """).param("v", amount.value()).param("id", receivedId).update() == 1;
    }

    @Override
    @Transactional
    public void releaseRefundable(UUID receivedId, br.com.pixmanager.core.domain.entity.Money amount) {
        jdbc.sql("UPDATE pix_received SET refunded_total = GREATEST(refunded_total - :v, 0) WHERE id = :id")
                .param("v", amount.value()).param("id", receivedId).update();
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<ReceivedPix> findByEndToEndId(String endToEndId) {
        return repository.findByEndToEndId(endToEndId).map(entity -> mapper.toDomain(entity, components(entity.getId())));
    }

    @Override
    @Transactional(readOnly = true)
    public PageResult<ReceivedPix> search(Set<UUID> accounts, boolean allAccounts, ReceivedPixSearch search, PageQuery page) {
        Specification<ReceivedPixEntity> spec = Specification.allOf(
                ReceivedPixSpecifications.accessibleTo(accounts, allAccounts),
                ReceivedPixSpecifications.settledBetween(search.from(), search.to()),
                ReceivedPixSpecifications.hasTxid(search.txid().orElse(null)),
                ReceivedPixSpecifications.hasPayerHash(search.payer().map(mapper::hashOf).orElse(null)));
        Page<ReceivedPixEntity> result = repository.findAll(spec,
                PageRequest.of(page.page(), page.size(), Sort.by(Sort.Direction.DESC, "settledAt")));
        Map<UUID, List<ValueComponent>> componentsById = components(result.getContent().stream().map(ReceivedPixEntity::getId).toList());
        return new PageResult<>(result.getContent().stream()
                .map(entity -> mapper.toDomain(entity, componentsById.getOrDefault(entity.getId(), List.of()))).toList(),
                page.page(), page.size(), result.getTotalElements());
    }

    /** Componentes da página INTEIRA em 1 query (em vez de 1 por Pix). */
    private Map<UUID, List<ValueComponent>> components(List<UUID> receivedIds) {
        Map<UUID, List<ValueComponent>> byId = new HashMap<>();
        if (receivedIds.isEmpty()) {
            return byId;
        }
        jdbc.sql("SELECT received_id, component_type, amount, agent_modality, withdrawal_provider_ispb FROM pix_received_component WHERE received_id IN (:ids)")
                .param("ids", receivedIds)
                .query((resultSet, rowNumber) -> {
                    byId.computeIfAbsent(resultSet.getObject("received_id", UUID.class), receivedId -> new ArrayList<>())
                            .add(new ValueComponent(ComponentType.valueOf(resultSet.getString("component_type")),
                                    br.com.pixmanager.core.domain.entity.Money.of(resultSet.getBigDecimal("amount")), resultSet.getString("agent_modality"),
                                    resultSet.getString("withdrawal_provider_ispb")));
                    return null;
                }).list();
        return byId;
    }

    private List<ValueComponent> components(UUID receivedId) {
        return jdbc.sql("SELECT component_type, amount, agent_modality, withdrawal_provider_ispb FROM pix_received_component WHERE received_id = :id")
                .param("id", receivedId)
                .query((resultSet, rowNumber) -> new ValueComponent(ComponentType.valueOf(resultSet.getString("component_type")),
                        br.com.pixmanager.core.domain.entity.Money.of(resultSet.getBigDecimal("amount")), resultSet.getString("agent_modality"),
                        resultSet.getString("withdrawal_provider_ispb"))).list();
    }
}
