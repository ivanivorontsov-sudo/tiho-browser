use std::time::Instant;
fn main() {
    let dir = std::env::args().nth(1).unwrap();
    let names = ["easylist.txt","easyprivacy.txt","ruadlist.txt","peterlowe.txt","ublock-filters.txt","ublock-privacy.txt","ublock-unbreak.txt","ublock-quickfixes.txt","ublock-badware.txt","adguard-urltracking.txt","easylist-cookie.txt","ublock-cookies.txt"];
    let lists: Vec<(String,bool)> = names.iter().map(|n| (std::fs::read_to_string(format!("{}/{}", dir, n)).unwrap(), true)).collect();
    let t = Instant::now();
    let mut e = tihoadblock::build_engine(&lists);
    println!("build {:?}", t.elapsed());
    let res = std::fs::read_to_string(format!("{}/resources-full.json", dir)).unwrap();
    println!("resources ok={}", tihoadblock::load_resources(&mut e, &res));
    let t = Instant::now(); let s = e.serialize(); println!("serialize {:?} size {}", t.elapsed(), s.len());
    let t = Instant::now(); let mut e2 = adblock::Engine::default(); e2.deserialize(&s).unwrap(); println!("deserialize {:?}", t.elapsed());
    for (u,src,ty) in [("https://mc.yandex.ru/metrika/tag.js","https://lenta.ru/","script"),("https://www.googletagmanager.com/gtag/js?id=G-1","https://ria.ru/","script"),("https://an.yandex.ru/system/context.js","https://lenta.ru/","script"),("https://pagead2.googlesyndication.com/pagead/js/adsbygoogle.js","https://example.com/","script"),("https://lenta.ru/news/","https://lenta.ru/","document"),("https://www.youtube.com/api/stats/ads?x","https://www.youtube.com/","xmlhttprequest")] {
        let t = Instant::now(); let r = tihoadblock::check(&e, u, src, ty, "GET"); println!("{:?} {} in {:?}", r.map(|x| x.chars().take(40).collect::<String>()), u, t.elapsed());
    }
    let t = Instant::now(); let c = tihoadblock::cosmetic_json(&e, "https://www.youtube.com/watch?v=x"); println!("yt cosmetic {} bytes in {:?}; js has json-prune: {}", c.len(), t.elapsed(), c.contains("prune"));
    let c = tihoadblock::cosmetic_json(&e, "https://lenta.ru/"); println!("lenta cosmetic {} bytes", c.len()); let y = tihoadblock::cosmetic_json(&e, "https://www.youtube.com/watch?v=x"); let v: serde_json::Value = serde_json::from_str(&y).unwrap(); let js = v["js"].as_str().unwrap(); println!("yt js len {} trusted-replace {}", js.len(), js.contains("trustedReplace")); std::fs::write("/tmp/yt.js", js).unwrap();
}
