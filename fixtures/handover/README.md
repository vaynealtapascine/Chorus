# Handover conformance (D-132)

The normal `{description, fn, args, expect}` format, run by `cargo test -p chorus-core --test
fixtures`. Expected results are hand-written, not blessed.

`handover_view` is the export the clients call (wasm `handoverView`, UniFFI `handover_view`;
`chorus_core::handover::view`). Its input and output are described in
`docs/handoff/d132-clients.md`. Delivery counts the Body front's fronting and co-conscious layers
(`front`, `cocon`): who is there and on which of the two; `present` and custom layers don't count.
Retracted switches are already gone from the folded intervals the caller passes, and a back-dated
switch sits where it is dated, so both resolve the same way on every device.
