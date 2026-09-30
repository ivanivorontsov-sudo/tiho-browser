//! JNI bridge between the Тихо browser (Kotlin) and Brave's adblock-rust engine.
//!
//! All entry points are panic-safe (`catch_unwind`) and never throw into the JVM:
//! on error they return a neutral value (0 / null / empty).

use adblock::lists::{FilterSet, ParseOptions};
use adblock::resources::{PermissionMask, Resource};
use adblock::request::Request;
use adblock::Engine;
use jni::objects::{JByteArray, JClass, JObjectArray, JString};
use jni::sys::{jboolean, jbyteArray, jlong, jstring, JNI_FALSE, JNI_TRUE};
use jni::JNIEnv;
use std::collections::HashSet;
use std::panic::{catch_unwind, AssertUnwindSafe};

// The engine is shared between WebView IO threads; make sure adblock-rust was built thread-safe
// (i.e. without the `single-thread` feature).
#[allow(dead_code)]
fn assert_sync<T: Sync + Send>() {}
#[allow(dead_code)]
fn _engine_is_sync() {
    assert_sync::<Engine>();
}

/// Permission granted to our bundled (trusted) lists, e.g. uBlock filters that use `trusted-*` scriptlets.
const TRUSTED: PermissionMask = PermissionMask::from_bits(0xFF);

// ---------------------------------------------------------------- pure Rust API (unit-testable)

pub fn build_engine(lists: &[(String, bool)]) -> Engine {
    let mut set = FilterSet::new(false);
    for (text, trusted) in lists {
        let mut opts = ParseOptions::default();
        if *trusted {
            opts.permissions = TRUSTED;
        }
        set.add_filter_list(text.clone(), opts);
    }
    Engine::new_with_filter_set(set)
}

pub fn load_resources(engine: &mut Engine, json: &str) -> bool {
    match serde_json::from_str::<Vec<Resource>>(json) {
        Ok(res) => {
            engine.use_resources(res);
            true
        }
        Err(_) => false,
    }
}

/// Result encoding (kept as a tiny string to minimise JNI object churn):
///  `None`        – nothing matched, load normally
///  `"B"`         – block
///  `"R" + data`  – replace with redirect resource (a `data:` URL)
///  `"W" + url`   – allowed, but URL rewritten by `$removeparam`
///  `"E"`         – an exception rule matched (explicitly allowed)
pub fn check(engine: &Engine, url: &str, source: &str, rtype: &str, method: &str) -> Option<String> {
    let req = Request::new(url, source, rtype, method).ok()?;
    let r = engine.check_network_request(&req);
    if r.should_block() {
        if let Some(redirect) = r.redirect {
            return Some(format!("R{}", redirect));
        }
        return Some("B".to_string());
    }
    if let Some(u) = r.rewritten_url {
        return Some(format!("W{}", u));
    }
    if r.exception.is_some() {
        return Some("E".to_string());
    }
    None
}

pub fn cosmetic_json(engine: &Engine, url: &str) -> String {
    let r = engine.url_cosmetic_resources(url);
    serde_json::json!({
        "hide": r.hide_selectors,
        "proc": r.procedural_actions,
        "exc": r.exceptions,
        "js": r.injected_script,
        "gh": r.generichide,
    })
    .to_string()
}

pub fn hidden_ids(engine: &Engine, classes: &[String], ids: &[String], exceptions: &[String]) -> Vec<String> {
    let exc: HashSet<String> = exceptions.iter().cloned().collect();
    engine.hidden_class_id_selectors(classes, ids, &exc)
}

// ---------------------------------------------------------------- JNI helpers

fn engine_ref<'a>(ptr: jlong) -> Option<&'a Engine> {
    if ptr == 0 {
        None
    } else {
        Some(unsafe { &*(ptr as *const Engine) })
    }
}

fn jstr(env: &mut JNIEnv, s: &JString) -> String {
    if s.is_null() {
        return String::new();
    }
    env.get_string(s).map(|v| v.into()).unwrap_or_default()
}

fn jstr_array(env: &mut JNIEnv, arr: &JObjectArray) -> Vec<String> {
    let mut out = Vec::new();
    if arr.is_null() {
        return out;
    }
    let n = env.get_array_length(arr).unwrap_or(0);
    for i in 0..n {
        if let Ok(o) = env.get_object_array_element(arr, i) {
            let js = JString::from(o);
            out.push(jstr(env, &js));
            let _ = env.delete_local_ref(js);
        }
    }
    out
}

fn to_jstring(env: &mut JNIEnv, s: Option<String>) -> jstring {
    match s {
        Some(v) => env.new_string(v).map(|j| j.into_raw()).unwrap_or(std::ptr::null_mut()),
        None => std::ptr::null_mut(),
    }
}

// ---------------------------------------------------------------- JNI entry points
// Kotlin: object org.ivanivorontsov.tiho.adblock.AdblockNative { @JvmStatic external fun ... }

#[no_mangle]
pub extern "system" fn Java_org_ivanivorontsov_tiho_adblock_AdblockNative_create(
    mut env: JNIEnv,
    _c: JClass,
    lists: JObjectArray,
    trusted_mask: jlong,
) -> jlong {
    let texts = jstr_array(&mut env, &lists);
    catch_unwind(AssertUnwindSafe(|| {
        let lists: Vec<(String, bool)> = texts
            .into_iter()
            .enumerate()
            .map(|(i, t)| (t, i < 63 && (trusted_mask >> i) & 1 == 1))
            .collect();
        Box::into_raw(Box::new(build_engine(&lists))) as jlong
    }))
    .unwrap_or(0)
}

#[no_mangle]
pub extern "system" fn Java_org_ivanivorontsov_tiho_adblock_AdblockNative_deserialize(
    env: JNIEnv,
    _c: JClass,
    data: JByteArray,
) -> jlong {
    let bytes = match env.convert_byte_array(&data) {
        Ok(b) => b,
        Err(_) => return 0,
    };
    catch_unwind(AssertUnwindSafe(|| {
        let mut e = Engine::default();
        match e.deserialize(&bytes) {
            Ok(()) => Box::into_raw(Box::new(e)) as jlong,
            Err(_) => 0,
        }
    }))
    .unwrap_or(0)
}

#[no_mangle]
pub extern "system" fn Java_org_ivanivorontsov_tiho_adblock_AdblockNative_serialize(
    env: JNIEnv,
    _c: JClass,
    ptr: jlong,
) -> jbyteArray {
    let Some(e) = engine_ref(ptr) else { return std::ptr::null_mut() };
    let bytes = catch_unwind(AssertUnwindSafe(|| e.serialize())).unwrap_or_default();
    env.byte_array_from_slice(&bytes).map(|a| a.into_raw()).unwrap_or(std::ptr::null_mut())
}

#[no_mangle]
pub extern "system" fn Java_org_ivanivorontsov_tiho_adblock_AdblockNative_useResources(
    mut env: JNIEnv,
    _c: JClass,
    ptr: jlong,
    json: JString,
) -> jboolean {
    if ptr == 0 {
        return JNI_FALSE;
    }
    let json = jstr(&mut env, &json);
    // Only called before the engine is published to other threads.
    let e = unsafe { &mut *(ptr as *mut Engine) };
    let ok = catch_unwind(AssertUnwindSafe(|| load_resources(e, &json))).unwrap_or(false);
    if ok { JNI_TRUE } else { JNI_FALSE }
}

#[no_mangle]
pub extern "system" fn Java_org_ivanivorontsov_tiho_adblock_AdblockNative_check(
    mut env: JNIEnv,
    _c: JClass,
    ptr: jlong,
    url: JString,
    source: JString,
    rtype: JString,
    method: JString,
) -> jstring {
    let Some(e) = engine_ref(ptr) else { return std::ptr::null_mut() };
    let url = jstr(&mut env, &url);
    let source = jstr(&mut env, &source);
    let rtype = jstr(&mut env, &rtype);
    let method = jstr(&mut env, &method);
    let r = catch_unwind(AssertUnwindSafe(|| check(e, &url, &source, &rtype, &method))).unwrap_or(None);
    to_jstring(&mut env, r)
}

#[no_mangle]
pub extern "system" fn Java_org_ivanivorontsov_tiho_adblock_AdblockNative_cosmetic(
    mut env: JNIEnv,
    _c: JClass,
    ptr: jlong,
    url: JString,
) -> jstring {
    let Some(e) = engine_ref(ptr) else { return std::ptr::null_mut() };
    let url = jstr(&mut env, &url);
    let r = catch_unwind(AssertUnwindSafe(|| cosmetic_json(e, &url))).ok();
    to_jstring(&mut env, r)
}

#[no_mangle]
pub extern "system" fn Java_org_ivanivorontsov_tiho_adblock_AdblockNative_hiddenClassIdSelectors(
    mut env: JNIEnv,
    _c: JClass,
    ptr: jlong,
    classes: JObjectArray,
    ids: JObjectArray,
    exceptions: JObjectArray,
) -> jstring {
    let Some(e) = engine_ref(ptr) else { return std::ptr::null_mut() };
    let classes = jstr_array(&mut env, &classes);
    let ids = jstr_array(&mut env, &ids);
    let exceptions = jstr_array(&mut env, &exceptions);
    let r = catch_unwind(AssertUnwindSafe(|| {
        let v = hidden_ids(e, &classes, &ids, &exceptions);
        if v.is_empty() { None } else { Some(v.join("\n")) }
    }))
    .unwrap_or(None);
    to_jstring(&mut env, r)
}

#[no_mangle]
pub extern "system" fn Java_org_ivanivorontsov_tiho_adblock_AdblockNative_free(
    _env: JNIEnv,
    _c: JClass,
    ptr: jlong,
) {
    if ptr != 0 {
        unsafe { drop(Box::from_raw(ptr as *mut Engine)) };
    }
}

// ---------------------------------------------------------------- tests

#[cfg(test)]
mod tests {
    use super::*;

    fn engine(rules: &str) -> Engine {
        build_engine(&[(rules.to_string(), true)])
    }

    #[test]
    fn blocks_third_party_domain_anchor() {
        let e = engine("||doubleclick.net^\n||ads.example.com^$third-party");
        assert_eq!(check(&e, "https://ad.doubleclick.net/x.js", "https://news.ru/", "script", "GET"), Some("B".into()));
        assert_eq!(check(&e, "https://ads.example.com/a.png", "https://other.org/", "image", "GET"), Some("B".into()));
        // first-party request is not blocked by $third-party rule
        assert_eq!(check(&e, "https://ads.example.com/a.png", "https://www.example.com/", "image", "GET"), None);
    }

    #[test]
    fn exception_overrides_block() {
        let e = engine("||cdn.example.com/ads/*\n@@||cdn.example.com/ads/allowed.js$script");
        assert_eq!(check(&e, "https://cdn.example.com/ads/banner.js", "https://a.ru/", "script", "GET"), Some("B".into()));
        assert_eq!(check(&e, "https://cdn.example.com/ads/allowed.js", "https://a.ru/", "script", "GET"), Some("E".into()));
    }

    #[test]
    fn domain_option_and_cosmetic() {
        let e = engine("/banner/*$domain=site.ru\nsite.ru##.ad-box\n##.adsbygoogle\nexample.org#@#.adsbygoogle");
        assert_eq!(check(&e, "https://img.cdn.net/banner/1.png", "https://site.ru/", "image", "GET"), Some("B".into()));
        assert_eq!(check(&e, "https://img.cdn.net/banner/1.png", "https://other.ru/", "image", "GET"), None);
        let c = cosmetic_json(&e, "https://site.ru/page");
        assert!(c.contains(".ad-box"), "{}", c);
        let hidden = hidden_ids(&e, &["adsbygoogle".into()], &[], &[]);
        assert_eq!(hidden, vec![".adsbygoogle".to_string()]);
    }

    #[test]
    fn serialization_roundtrip() {
        let e = engine("||tracker.io^");
        let bytes = e.serialize();
        let mut e2 = Engine::default();
        e2.deserialize(&bytes).unwrap();
        assert_eq!(check(&e2, "https://tracker.io/p", "https://a.ru/", "xmlhttprequest", "GET"), Some("B".into()));
    }

    #[test]
    fn removeparam_rewrites() {
        let e = engine("$removeparam=utm_source");
        let r = check(&e, "https://shop.ru/item?id=1&utm_source=vk", "https://shop.ru/", "xmlhttprequest", "GET");
        assert_eq!(r, Some("Whttps://shop.ru/item?id=1".into()));
    }
}
