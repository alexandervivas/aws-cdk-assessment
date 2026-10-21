package com.tikkie.person.adapter.in.api;

import com.tikkie.person.domain.UnvalidatedPerson;
import com.tikkie.person.domain.UnvalidatedPerson.UnvalidatedAddress;

record CreatePersonRequest(String firstName, String lastName, String phoneNumber, AddressRequest address) {

    record AddressRequest(
            String street,
            String houseNumber,
            String houseNumberAddition,
            String postalCode,
            String city,
            String countryCode) {

        UnvalidatedAddress toUnvalidated() {
            return new UnvalidatedAddress(street, houseNumber, houseNumberAddition, postalCode, city, countryCode);
        }

        @Override
        public String toString() {
            return "AddressRequest[***]";
        }
    }

    UnvalidatedPerson toUnvalidated() {
        return new UnvalidatedPerson(firstName, lastName, phoneNumber,
                address == null ? null : address.toUnvalidated());
    }

    @Override
    public String toString() {
        return "CreatePersonRequest[***]";
    }
}
