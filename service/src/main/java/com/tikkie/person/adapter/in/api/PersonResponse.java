package com.tikkie.person.adapter.in.api;

import com.tikkie.person.application.CreatePersonResult;

record PersonResponse(String personId, String createdAt) {

    static PersonResponse from(CreatePersonResult result) {
        return new PersonResponse(result.personId().toString(), result.createdAt().toString());
    }
}
