//! Debug helper: `cargo run -p chorus-core --example md -- "<markup>"`
fn main() {
    let src = std::env::args().nth(1).unwrap_or_default();
    let r = chorus_core::text::parse(&src, &chorus_core::text::NoNames);
    println!("{r:?}");
    let m = chorus_core::text::to_markup(&r);
    println!("markup: {m:?}");
    println!("{:?}", chorus_core::text::parse(&m, &chorus_core::text::NoNames));
}
