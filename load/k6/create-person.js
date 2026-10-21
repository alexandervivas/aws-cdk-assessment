// Load test for POST /person on a deployed stage.
//
// Run it only against the dedicated Dev account (docs/adr/0014-account-level-apigateway-logging-role.md): every
// request creates a person, writes two DynamoDB items and publishes a PersonCreated event.
//
//   eval "$(aws configure export-credentials --format env)"   # k6 reads AWS_ACCESS_KEY_ID, AWS_SECRET_ACCESS_KEY,
//   export AWS_REGION=eu-west-1                               # AWS_SESSION_TOKEN and AWS_REGION
//   k6 run -e API_URL=https://<api-id>.execute-api.eu-west-1.amazonaws.com/dev/ \
//          -e RATE=8 -e DURATION=2m load/k6/create-person.js
//
// RATE is the target in requests per second (default 8, below the dev throttle of 10/s), reached over a 30 s ramp and then held for DURATION
// (default 1m). Requests are signed with SigV4 for execute-api by the k6 jslib AWS signature module.

import http from 'k6/http';
import { check } from 'k6';
import { AWSConfig, Endpoint, SignatureV4 } from 'https://jslib.k6.io/aws/0.12.3/signature.js';
import { uuidv4 } from 'https://jslib.k6.io/k6-utils/1.4.0/index.js';

const API_URL = required('API_URL');
const RATE = Number(__ENV.RATE || 8);
const DURATION = __ENV.DURATION || '1m';

const [, ORIGIN, STAGE_PATH] = API_URL.match(/^(https:\/\/[^/]+)(\/[^/?][^?]*?)\/?$/) || [];
if (!ORIGIN || !STAGE_PATH) {
    throw new Error('API_URL must look like https://<api-id>.execute-api.<region>.amazonaws.com/<stage>/');
}

const aws = AWSConfig.fromEnvironment();
const signer = new SignatureV4({
    service: 'execute-api',
    region: aws.region,
    credentials: {
        accessKeyId: aws.accessKeyId,
        secretAccessKey: aws.secretAccessKey,
        sessionToken: aws.sessionToken,
    },
    uriEscapePath: true,
    applyChecksum: true,
});
const endpoint = new Endpoint(ORIGIN);

// Every request should create a person, so anything other than 201 counts as a failure.
http.setResponseCallback(http.expectedStatuses(201));

export const options = {
    scenarios: {
        createPerson: {
            executor: 'ramping-arrival-rate',
            startRate: 1,
            timeUnit: '1s',
            preAllocatedVUs: Math.max(10, RATE),
            maxVUs: Math.max(50, RATE * 5),
            stages: [
                { target: RATE, duration: '30s' },
                { target: RATE, duration: DURATION },
            ],
        },
    },
    thresholds: {
        http_req_duration: ['p(95)<1000'],
        http_req_failed: ['rate<0.01'],
    },
};

export default function () {
    const body = JSON.stringify(fictionalPerson());
    const signed = signer.sign({
        method: 'POST',
        endpoint: endpoint,
        path: `${STAGE_PATH}/person`,
        headers: {
            'Content-Type': 'application/json',
            'Idempotency-Key': uuidv4(),
        },
        body: body,
    });

    const response = http.post(signed.url, body, { headers: signed.headers, tags: { name: 'POST /person' } });

    check(response, {
        'status is 201': (r) => r.status === 201,
        'has Location': (r) => (r.headers['Location'] || '').startsWith('/person/'),
    });
}

// Fictional data. The varying house number only spreads the requests; the unique key already makes each one new.
function fictionalPerson() {
    return {
        firstName: 'Fenna',
        lastName: 'van Oosterhout',
        phoneNumber: '+31 6 12345678',
        address: {
            street: 'Keizersgracht',
            houseNumber: String(1 + Math.floor(Math.random() * 999)),
            postalCode: '1015 CJ',
            city: 'Amsterdam',
            countryCode: 'NL',
        },
    };
}

function required(name) {
    const value = __ENV[name];
    if (!value) {
        throw new Error(`Set ${name}, for example with -e ${name}=...`);
    }
    return value;
}
