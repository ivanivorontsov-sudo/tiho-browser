const test=require("node:test"),assert=require("node:assert/strict"),core=require("./core.js");
test("search URLs encode queries and fall back safely",()=>{
 assert.equal(core.searchUrl("ddg","кот и пёс"),"https://duckduckgo.com/?q=%D0%BA%D0%BE%D1%82%20%D0%B8%20%D0%BF%D1%91%D1%81&kl=ru-ru");
 assert.match(core.searchUrl("unknown","hello"),/^https:\/\/duckduckgo\.com\//);assert.equal(core.searchUrl("ddg"," "),null);
});
test("only HTTP and HTTPS quick links are accepted",()=>{
 assert.equal(core.normalizeHttpUrl("example.com"),"https://example.com/");
 assert.equal(core.normalizeHttpUrl("http://example.com/a"),"http://example.com/a");
 for(const v of ["javascript:alert(1)","data:text/html,hi","file:///etc/passwd","https://user:pass@example.com"]) assert.throws(()=>core.normalizeHttpUrl(v));
});
test("saved quick links are validated and deduplicated",()=>{
 assert.deepEqual(core.sanitizeTiles([["Safe","https://example.com","S"],["Duplicate","https://example.com/","D"],["Bad","javascript:alert(1)","X"],["Also safe","site.test","A"]]),[["Safe","https://example.com/","S"],["Also safe","https://site.test/","A"]]);
});
test("malformed storage falls back to defaults",()=>{assert.deepEqual(core.sanitizeTiles(null),core.DEFAULTS);assert.deepEqual(core.sanitizeTiles({}),core.DEFAULTS)});
