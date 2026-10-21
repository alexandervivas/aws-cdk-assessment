package com.tikkie.person.infra.constructs;

import java.util.List;
import software.amazon.awscdk.services.logs.CustomDataIdentifier;
import software.amazon.awscdk.services.logs.DataIdentifier;
import software.amazon.awscdk.services.logs.DataProtectionPolicy;
import software.amazon.awscdk.services.logs.DataProtectionPolicyProps;

// Defence in depth: the service never logs personal data, so this policy should never match. CloudWatch Logs masks
// what does match before anyone reads it.
//
// Coverage is partial. Phone numbers are covered in E.164, the form the service stores them in. The managed Address
// identifier doesn't cover the Netherlands, so a Dutch address is covered only through its postal code, and street
// names aren't covered. Names aren't masked: the managed Name identifier is left out for its false positives.
final class PersonalDataLogProtection {

    // AWS has no managed identifier for Dutch phone numbers.
    static final String E164_PATTERN = "\\+[1-9]\\d{6,14}";

    // The stored form, such as 1012 LG. It also matches text like 1024 MB, an accepted false positive.
    static final String DUTCH_POSTAL_CODE_PATTERN = "[1-9][0-9]{3} [A-Z]{2}";

    private PersonalDataLogProtection() {
    }

    // No audit destination: a finding means a code defect, and the AWS/Logs LogEventsWithFindings metric reports it
    // without copying anything into another store.
    static DataProtectionPolicy policy() {
        return new DataProtectionPolicy(DataProtectionPolicyProps.builder()
                .description("Masks phone numbers and addresses that reach the Person Service logs")
                .identifiers(List.of(
                        new CustomDataIdentifier("E164PhoneNumber", E164_PATTERN),
                        new CustomDataIdentifier("DutchPostalCode", DUTCH_POSTAL_CODE_PATTERN),
                        DataIdentifier.ADDRESS,
                        DataIdentifier.PHONENUMBER_DE,
                        DataIdentifier.PHONENUMBER_ES,
                        DataIdentifier.PHONENUMBER_FR,
                        DataIdentifier.PHONENUMBER_GB,
                        DataIdentifier.PHONENUMBER_IT))
                .build());
    }
}
