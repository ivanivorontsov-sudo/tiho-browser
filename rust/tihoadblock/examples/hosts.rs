// Checks which hosts of an adblock test page are not blocked by the bundled lists.
fn main() {
    let dir = "../../app/src/main/assets/filters";
    let ids = ["easylist","easyprivacy","ruadlist","peterlowe","ublock-filters","ublock-privacy","ublock-unbreak","ublock-quickfixes","ublock-badware","adguard-urltracking","cname-ads","hagezi-light","easylist-cookie","ublock-cookies"];
    let mut lists: Vec<(String, bool)> = ids.iter().map(|i| (std::fs::read_to_string(format!("{}/{}.txt", dir, i)).unwrap(), true)).collect();
    if let Ok(extra) = std::env::var("EXTRA") { for f in extra.split(',') { lists.push((std::fs::read_to_string(f).unwrap(), true)); } }
    let e = tihoadblock::build_engine(&lists);
    let hosts = std::fs::read_to_string(std::env::args().nth(1).unwrap()).unwrap();
    let src = std::env::args().nth(2).unwrap_or("https://adblock.turtlecute.org/".into());
    let (mut b, mut n) = (0, 0);
    for h in hosts.lines().filter(|l| !l.is_empty()) {
        let u = format!("https://{}/fakepage.html", h);
        let r = tihoadblock::check(&e, &u, &src, "xmlhttprequest", "HEAD");
        if r.is_some() { b += 1 } else { n += 1; println!("NOT {}", h) }
    }
    println!("blocked {} not {}", b, n);
}
