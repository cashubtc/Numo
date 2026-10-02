The Docker image applies `bark-arkoor-receive.patch` to the public
`cashubtc/cdk-payment-processors` v0.1.0 revision
`c7940dd9add3f2ac399da596d253f582809230b1` before building the Bark processor.

It adds the incoming Arkoor quote/address mapping, durable movement matching,
receipt deduplication, and status/event delivery used by Numo. It also includes
initialization of the Arkoor quote table and the unpaid status of unattempted
outgoing quotes. The patch includes the backend's regression tests.

`bark-nonblocking-events.patch` then prevents unpaid Lightning invoices from
blocking the shared event stream. Bark's `wait=true` drives receives until
completion, which also waits for payment of the alternative Lightning quote.
Using `wait=false` parks pending Lightning actions so each event pass can also
sync and deliver incoming Arkoor receipts. The funded Android socket test covers
this case with a Lightning quote left unpaid throughout the Arkoor payment.

These patches keep the environment reproducible from public sources while upstream
incoming Arkoor support is pending. It does not require another local repository
or a private branch. Replace the patches with a pinned upstream revision when
these changes are included in a processor release. The processor source is
licensed under MIT OR Apache-2.0.
