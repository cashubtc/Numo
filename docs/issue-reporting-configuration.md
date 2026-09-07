# Issue reporting release configuration

Anonymous issue reporting publishes NIP-29 chat messages to the `numo-reports` channel on the
Buzz relay at `wss://buzz.cashu.space`.

For each report the app generates a fresh temporary Nostr key, connects to Buzz, answers the
relay's NIP-42 challenge, discovers the channel UUID from its kind-39000 metadata, and publishes a
signed kind-9 message with an `h` tag containing that UUID. The relay and channel names are build
constants, and invalid runtime configuration disables submission.

Automatic severe-error reporting is enabled by default and can be disabled on the report screen.
An uncaught exception stores one sanitized no-backup handoff without messages or raw logs. On a
later launch, a network-constrained worker constructs and temporarily persists the sanitized JSON
payload and ephemeral private key so a retry can answer a new NIP-42 challenge. The handoff is
removed after relay acceptance, five failed worker attempts, seven days, or when the user disables
reporting.
