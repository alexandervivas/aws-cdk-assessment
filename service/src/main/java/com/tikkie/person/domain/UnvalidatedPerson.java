package com.tikkie.person.domain;

public record UnvalidatedPerson(String firstName, String lastName, String phoneNumber, UnvalidatedAddress address) {

    public record UnvalidatedAddress(
            String street,
            String houseNumber,
            String houseNumberAddition,
            String postalCode,
            String city,
            String countryCode) {

        static final UnvalidatedAddress EMPTY = new UnvalidatedAddress(null, null, null, null, null, null);

        @Override
        public String toString() {
            return "UnvalidatedAddress[***]";
        }
    }

    @Override
    public String toString() {
        return "UnvalidatedPerson[***]";
    }
}
