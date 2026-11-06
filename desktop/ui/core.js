(function (root, factory) {
  const api = factory();
  if (typeof module === "object" && module.exports) module.exports = api;
  if (root) root.TihoCore = api;
})(typeof globalThis !== "undefined" ? globalThis : this, function () {
  "use strict";
  const DEFAULTS = [["DuckDuckGo","https://duckduckgo.com/","D"],["Wikipedia","https://ru.wikipedia.org/","W"],["YouTube","https://www.youtube.com/","▶"],["GitHub","https://github.com/","⌘"]];
  const ENGINES = Object.freeze({
    ddg:q=>"https://duckduckgo.com/?q="+encodeURIComponent(q)+"&kl=ru-ru",
    brave:q=>"https://search.brave.com/search?q="+encodeURIComponent(q),
    startpage:q=>"https://www.startpage.com/do/search?q="+encodeURIComponent(q),
    yandex:q=>"https://yandex.ru/search/?text="+encodeURIComponent(q),
    google:q=>"https://www.google.com/search?q="+encodeURIComponent(q)
  });
  function normalizeHttpUrl(value) {
    const raw=String(value==null?"":value).trim();
    if(!raw||raw.length>2048) throw new Error("Укажите корректный адрес сайта");
    const candidate=/^[a-z][a-z0-9+.-]*:/i.test(raw)?raw:"https://"+raw;
    let url; try{url=new URL(candidate)}catch(_){throw new Error("Не удалось разобрать адрес сайта")}
    if(url.protocol!=="http:"&&url.protocol!=="https:") throw new Error("Разрешены только адреса http и https");
    if(!url.hostname||url.username||url.password) throw new Error("Адрес сайта содержит недопустимые данные");
    return url.href;
  }
  function sanitizeTiles(input) {
    if(!Array.isArray(input)) return DEFAULTS.map(x=>x.slice());
    const output=[],seen=new Set();
    for(const item of input.slice(0,40)){
      if(!Array.isArray(item)||item.length<2) continue;
      const name=String(item[0]||"").trim().slice(0,32); if(!name) continue;
      let url; try{url=normalizeHttpUrl(item[1])}catch(_){continue}
      if(seen.has(url)) continue; seen.add(url);
      output.push([name,url,String(item[2]||name.charAt(0)).slice(0,2)]);
      if(output.length>=24) break;
    }
    return output;
  }
  function searchUrl(engine,query){const q=String(query==null?"":query).trim();return q?(ENGINES[engine]||ENGINES.ddg)(q):null}
  return Object.freeze({DEFAULTS,ENGINES,normalizeHttpUrl,sanitizeTiles,searchUrl});
});
