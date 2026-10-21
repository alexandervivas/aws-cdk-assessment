package com.tikkie.person.application;

public interface RequestFingerprinter {

    RequestHash fingerprint(CanonicalRequest request);
}
