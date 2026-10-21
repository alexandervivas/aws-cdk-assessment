package com.tikkie.person.application;

import com.tikkie.person.domain.PersonId;
import java.time.Instant;

public sealed interface CreateOutcome permits CreateOutcome.Inserted, CreateOutcome.ReplayOf, CreateOutcome.Conflict {

    record Inserted() implements CreateOutcome {
    }

    record ReplayOf(PersonId personId, Instant createdAt) implements CreateOutcome {
    }

    record Conflict() implements CreateOutcome {
    }
}
