package br.com.pixmanager.api.rest.pixkey;

import br.com.pixmanager.shared.mappers.PixKeyMapper;

import br.com.pixmanager.api.rest.pixkey.dto.request.DeletePixKeyRequest;
import br.com.pixmanager.api.rest.pixkey.dto.request.LookupPixKeyRequest;
import br.com.pixmanager.api.rest.pixkey.dto.response.PixKeyLookupResponse;
import br.com.pixmanager.api.rest.pixkey.dto.response.PixKeyResponse;
import br.com.pixmanager.api.rest.pixkey.dto.request.RegisterPixKeyRequest;
import br.com.pixmanager.core.port.in.pixkey.LookupExternalKeyUseCase;
import br.com.pixmanager.core.port.in.pixkey.ManagePixKeyUseCase;
import br.com.pixmanager.core.port.in.pixkey.OwnershipProofUseCase;
import br.com.pixmanager.api.rest.pixkey.dto.request.VerifyOwnershipRequest;
import br.com.pixmanager.api.rest.pixkey.dto.response.OwnershipChallengeResponse;
import br.com.pixmanager.core.port.in.pixkey.RegisterPixKeyUseCase;
import br.com.pixmanager.core.domain.entity.PixKey;
import br.com.pixmanager.core.domain.enums.PixKeyStatus;
import br.com.pixmanager.shared.constant.ErrorCodes;
import br.com.pixmanager.shared.util.ETags;
import br.com.pixmanager.shared.exception.NotFoundException;
import br.com.pixmanager.shared.idempotency.IdempotentExecutor;
import br.com.pixmanager.shared.model.dto.PageQuery;
import br.com.pixmanager.shared.model.dto.PageResult;
import br.com.pixmanager.core.domain.entity.Caller;
import br.com.pixmanager.api.rest.security.CallerContext;
import br.com.pixmanager.shared.model.ExpectedVersion;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.net.URI;
import java.util.Optional;
import java.util.UUID;

@RestController
public class PixKeyControllerImpl implements PixKeyController {

    private final RegisterPixKeyUseCase register;
    private final ManagePixKeyUseCase manage;
    private final LookupExternalKeyUseCase lookup;
    private final OwnershipProofUseCase ownershipProof;
    private final IdempotentExecutor idempotency;
    private final ObjectMapper json;

    PixKeyControllerImpl(RegisterPixKeyUseCase register, ManagePixKeyUseCase manage, LookupExternalKeyUseCase lookup,
                         OwnershipProofUseCase ownershipProof, IdempotentExecutor idempotency, ObjectMapper json) {
        this.register = register;
        this.manage = manage;
        this.lookup = lookup;
        this.ownershipProof = ownershipProof;
        this.idempotency = idempotency;
        this.json = json;
    }

    @Override
    public ResponseEntity<OwnershipChallengeResponse> requestOwnershipProof(UUID id) {
        OwnershipProofUseCase.Issued issued = ownershipProof.issue(CallerContext.current(), id);
        return ResponseEntity.status(HttpStatus.ACCEPTED).cacheControl(CacheControl.noStore())
                .body(new OwnershipChallengeResponse(issued.challengeId(), issued.channel(), issued.destinationMasked(), issued.expiresAt()));
    }

    @Override
    public ResponseEntity<PixKeyResponse> verifyOwnershipProof(UUID id, VerifyOwnershipRequest request) {
        PixKey proven = ownershipProof.verify(CallerContext.current(), id, request.code());
        return ResponseEntity.ok().eTag(ETags.of(proven.version())).cacheControl(CacheControl.noStore()).body(PixKeyMapper.toResponse(proven));
    }

    @Override
    public ResponseEntity<PixKeyResponse> register(RegisterPixKeyRequest request, String idempotencyKey) {
        Caller caller = CallerContext.current();
        java.util.function.Supplier<PixKeyResponse> action = () -> PixKeyMapper.toResponse(
                register.register(caller, request.accountId(), request.pixKeyType(), request.pixKey()));
        PixKeyResponse body;
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            body = action.get();
        } else {
            var result = idempotency.execute(caller.clientId(), "POST /v1/pix-keys", idempotencyKey, request,
                    () -> new IdempotentExecutor.Response(HttpStatus.CREATED.value(), action.get()));
            body = json.convertValue(result.body(), PixKeyResponse.class);
        }
        URI location = ServletUriComponentsBuilder.fromCurrentRequest().path("/{id}").buildAndExpand(body.id()).toUri();
        return ResponseEntity.created(location).cacheControl(CacheControl.noStore()).body(body);
    }

    @Override
    public ResponseEntity<PixKeyResponse> get(UUID id, String ifNoneMatch) {
        PixKey key = manage.get(CallerContext.current(), id);
        String etag = ETags.of(key.version());
        if (ETags.noneMatch(ifNoneMatch, key.version())) {
            return ResponseEntity.status(HttpStatus.NOT_MODIFIED).eTag(etag).build();
        }
        return ResponseEntity.ok().eTag(etag).cacheControl(CacheControl.noStore()).body(PixKeyMapper.toResponse(key));
    }

    @Override
    public ResponseEntity<PageResult<PixKeyResponse>> list(UUID accountId, PixKeyStatus status, int page, int size) {
        PageResult<PixKey> result = manage.list(CallerContext.current(), accountId, Optional.ofNullable(status),
                PageQuery.of(page, size));
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(result.map(PixKeyMapper::toResponse));
    }

    @Override
    public ResponseEntity<PixKeyResponse> delete(UUID id, String ifMatch, DeletePixKeyRequest request) {
        ExpectedVersion expected = ExpectedVersion.parse(ifMatch);
        PixKey deleted = manage.delete(CallerContext.current(), id, request.reason(), expected);
        return ResponseEntity.ok().eTag("\"" + deleted.version() + "\"").cacheControl(CacheControl.noStore())
                .body(PixKeyMapper.toResponse(deleted));
    }

    @Override
    public ResponseEntity<PixKeyLookupResponse> lookup(LookupPixKeyRequest request) {
        Caller caller = CallerContext.current();
        var entry = lookup.lookup(caller, request.payerAccountId(), request.pixKeyType(), request.pixKey())
                .orElseThrow(() -> new NotFoundException(ErrorCodes.NOT_FOUND, "Chave não encontrada."));
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(PixKeyMapper.toResponse(entry, caller.fullDocument()));
    }
}
