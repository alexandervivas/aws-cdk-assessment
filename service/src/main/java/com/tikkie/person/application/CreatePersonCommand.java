package com.tikkie.person.application;

import com.tikkie.person.domain.UnvalidatedPerson;
import java.util.Objects;

public record CreatePersonCommand(
        CallerId caller, IdempotencyKey idempotencyKey, UnvalidatedPerson person, String correlationId) {

    public CreatePersonCommand {
        Objects.requireNonNull(caller, "caller");
        Objects.requireNonNull(idempotencyKey, "idempotencyKey");
        Objects.requireNonNull(person, "person");
        Objects.requireNonNull(correlationId, "correlationId");
    }

    @Override
    public String toString() {
        return "CreatePersonCommand[idempotencyKey=" + idempotencyKey + ", person=***, correlationId="
                + correlationId + "]";
    }
}
