package com.tikkie.person.infra.constructs;

import software.amazon.awscdk.Acknowledgment;
import software.amazon.awscdk.Validations;
import software.constructs.IConstruct;

// cdk-nag 3 reads CDK acknowledgements. A finding id such as AwsSolutions-IAM5[Resource::*] acknowledges that one
// finding on this construct and its children, nothing broader.
public final class NagAcknowledgements {

    private NagAcknowledgements() {
    }

    public static void acknowledge(IConstruct construct, String findingId, String reason) {
        Validations.of(construct).acknowledge(Acknowledgment.builder().id(findingId).reason(reason).build());
    }

    // ADR 0013: KMS grants emit these prefix actions, always on the one stage key. The acknowledgement is per role, not
    // per key, so it would also cover them on another key. The stack IAM walk (IamWildcards) pins them to the stage key.
    static void acknowledgeStageKeyPrefixActions(IConstruct role, String reason) {
        acknowledge(role, "AwsSolutions-IAM5[Action::kms:GenerateDataKey*]", reason);
        acknowledge(role, "AwsSolutions-IAM5[Action::kms:ReEncrypt*]", reason);
    }
}
