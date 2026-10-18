package org.ivanivorontsov.tiho.ext

object ScreenSize {
    const val WIDTH = 360
    const val HEIGHT = 800

    val COMMON = listOf(360 to 800, 384 to 854, 393 to 873, 412 to 915, 430 to 932)

    fun pick(width: Int, height: Int): Pair<Int, Int>? = COMMON.firstOrNull { it.first >= width && it.second >= height }

    fun js(): String {
        val list = COMMON.joinToString(",") { "[${it.first},${it.second}]" }
        return """
(function(){
const w=window.wrappedJSObject;if(!w||!w.Screen)return;
const sp=w.Screen.prototype;
const g=(n)=>{const d=Object.getOwnPropertyDescriptor(sp,n);return d&&d.get?d:null;};
const dw=g("width"),dh=g("height");if(!dw||!dh)return;
const C=[$list];
const pick=()=>{const rw=dw.get.call(w.screen),rh=dh.get.call(w.screen);for(const c of C){if(c[0]>=rw&&c[1]>=rh)return c;}return [rw,rh];};
const fix=(o,n,f)=>{if(!o)return;const d=Object.getOwnPropertyDescriptor(o,n);if(!d||!d.get)return;
Object.defineProperty(o,n,{configurable:true,enumerable:d.enumerable,get:exportFunction(function(){return f();},w),set:d.set});};
fix(sp,"width",()=>pick()[0]);fix(sp,"height",()=>pick()[1]);fix(sp,"availWidth",()=>pick()[0]);fix(sp,"availHeight",()=>pick()[1]);
fix(sp,"availLeft",()=>0);fix(sp,"availTop",()=>0);
const own=(n)=>Object.getOwnPropertyDescriptor(w,n)?w:(w.Window&&w.Window.prototype);
fix(own("outerWidth"),"outerWidth",()=>pick()[0]);fix(own("outerHeight"),"outerHeight",()=>pick()[1]);
})();
""".trim()
    }
}
