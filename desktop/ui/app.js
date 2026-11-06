(function () {
  "use strict";
  const core=window.TihoCore, $=id=>document.getElementById(id);
  const keys={tiles:"tiho.tiles.v1",engine:"tiho.engine.v1"};
  let tiles=core.DEFAULTS.map(x=>x.slice()),storageAvailable=true;
  function notice(s){$("notice").textContent=s||""}
  function read(k){try{return localStorage.getItem(k)}catch(_){storageAvailable=false;return null}}
  function write(k,v){try{localStorage.setItem(k,v);return true}catch(_){storageAvailable=false;notice("Не удалось сохранить настройки. Проверьте хранилище Firefox.");return false}}
  try{const saved=read(keys.tiles);if(saved)tiles=core.sanitizeTiles(JSON.parse(saved))}catch(_){tiles=core.DEFAULTS.map(x=>x.slice());notice("Список быстрых ссылок повреждён. Восстановлен стандартный список.")}
  const engine=read(keys.engine);$("engine").value=Object.prototype.hasOwnProperty.call(core.ENGINES,engine)?engine:"ddg";
  $("engine").addEventListener("change",()=>write(keys.engine,$("engine").value));
  $("search").addEventListener("submit",e=>{e.preventDefault();const url=core.searchUrl($("engine").value,$("query").value);if(url)location.assign(url)});
  function render(){
    const target=$("tiles");target.replaceChildren();
    tiles.forEach((item,index)=>{
      const [name,url,letter]=item,a=document.createElement("a");
      a.className="tile";a.href=url;a.rel="noopener noreferrer";
      const icon=document.createElement("span");icon.className="tile-icon";icon.textContent=letter||name.charAt(0).toUpperCase();
      const title=document.createElement("span");title.textContent=name;
      const remove=document.createElement("button");remove.className="remove";remove.type="button";remove.textContent="×";remove.title="Удалить "+name;remove.setAttribute("aria-label","Удалить "+name);
      remove.addEventListener("click",e=>{e.preventDefault();e.stopPropagation();tiles.splice(index,1);persist()});
      a.append(icon,title,remove);target.append(a);
    });
  }
  function persist(){if(write(keys.tiles,JSON.stringify(tiles)))notice("");render()}
  $("add").addEventListener("click",()=>{$("tile-form").reset();$("editor").showModal();$("tile-title").focus()});
  $("cancel").addEventListener("click",()=>$("editor").close());
  $("tile-form").addEventListener("submit",e=>{
    e.preventDefault();const name=$("tile-title").value.trim();if(!name){notice("Введите название сайта.");return}
    let url;try{url=core.normalizeHttpUrl($("tile-url").value)}catch(error){notice(error.message);return}
    if(tiles.some(x=>x[1]===url)){notice("Этот сайт уже есть в быстром доступе.");return}
    if(tiles.length>=24){notice("Можно добавить не больше 24 быстрых ссылок.");return}
    tiles.push([name.slice(0,32),url,name.charAt(0).toUpperCase()]);persist();$("editor").close();
  });
  render();if(!storageAvailable)notice("Хранилище недоступно: быстрые ссылки могут не сохраниться.");
})();
