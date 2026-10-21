package com.tikkie.person.application;

import com.tikkie.person.domain.Person;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

final class InMemoryPersonRepository implements PersonRepository {

    record Write(Person person, RequestHash hash, String correlationId) {
    }

    private record ScopedKey(CallerId caller, IdempotencyKey key) {
    }

    private record StoredKey(RequestHash hash, Person person) {
    }

    private final Map<ScopedKey, StoredKey> keys = new HashMap<>();
    private final List<Write> writes = new ArrayList<>();
    private int calls;

    @Override
    public synchronized CreateOutcome create(
            Person person, CallerId caller, IdempotencyKey key, RequestHash hash, String correlationId) {
        calls++;
        ScopedKey scopedKey = new ScopedKey(caller, key);
        StoredKey existing = keys.get(scopedKey);
        if (existing == null) {
            keys.put(scopedKey, new StoredKey(hash, person));
            writes.add(new Write(person, hash, correlationId));
            return new CreateOutcome.Inserted();
        }
        if (existing.hash().equals(hash)) {
            return new CreateOutcome.ReplayOf(existing.person().id(), existing.person().createdAt());
        }
        return new CreateOutcome.Conflict();
    }

    synchronized List<Write> writes() {
        return List.copyOf(writes);
    }

    synchronized int calls() {
        return calls;
    }
}
