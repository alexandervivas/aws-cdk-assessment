package com.tikkie.person.adapter.in.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.tikkie.person.adapter.in.api.CreatePersonRequest.AddressRequest;
import org.junit.jupiter.api.Test;

class CreatePersonRequestTest {

    @Test
    void toStringRevealsNoPersonalData() {
        CreatePersonRequest request = new CreatePersonRequest("Anna", "de Vries", "+31 6 12345678",
                new AddressRequest("Damrak", "1", "III", "1012 LG", "Amsterdam", "NL"));

        assertThat(request.toString()).isEqualTo("CreatePersonRequest[***]");
        assertThat(request.address().toString()).isEqualTo("AddressRequest[***]");
    }
}
