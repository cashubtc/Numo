# Issue reporting release configuration

Anonymous issue reporting is compiled into the app but remains unavailable until a valid support
recipient is configured. The recipient is a public key; never place its private key in the app or
repository.

Supply these Gradle properties through the release environment or the developer's untracked
`~/.gradle/gradle.properties` file:

```properties
issueReportRecipientPubkey=<64-character-lowercase-hex-x-only-public-key>
issueReportRelays=wss://relay-one.example,wss://relay-two.example
```

The build accepts one to three unique secure WebSocket relays. At runtime the feature validates
that the recipient is a valid secp256k1 x-only public key and that every relay is an absolute
`wss://` URL. Invalid or missing configuration disables submission without constructing an event.

The support receiver must subscribe to kind-1059 events addressed to the configured recipient on
every configured relay and retain old private keys and relay subscriptions for every supported app
release during key rotation.

Automatic severe-error reporting is enabled by default and can be disabled on the report screen.
An uncaught exception stores one sanitized no-backup handoff without messages or raw logs. On a
later launch, a network-constrained worker constructs and persists only the encrypted signed outer
event for byte-identical retries. The handoff is removed after relay acceptance, five failed worker
attempts, seven days, or when the user disables reporting.
