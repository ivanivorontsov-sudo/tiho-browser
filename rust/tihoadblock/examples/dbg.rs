use adblock::resources::*;
fn main() {
    let res = std::fs::read_to_string("/workspace/privbrowser/lists/resources-full.json").unwrap();
    let v: Vec<Resource> = serde_json::from_str(&res).unwrap();
    let mut s = InMemoryResourceStorage::default();
    let mut errs = 0;
    for r in v { let n = r.name.clone(); if let Err(e) = s.add_resource(r) { errs += 1; if errs < 8 { println!("{} -> {:?}", n, e); } } }
    println!("errors {}", errs);
    let st = ResourceStorage::from_backend(s);
    println!("{:?}", st.get_scriptlet_resources([("set-constant, x, true", PermissionMask::from_bits(0xFF))]).len());
}
