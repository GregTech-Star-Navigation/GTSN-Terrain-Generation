package com.gtsn.terrain.noise;

/**
 * 鍦板舰鍙傛暟閰嶇疆锛圡2锛?D 楂樺害鍥炬牳蹇冿級銆? *
 * <p>鎵€鏈夊瓧娈靛叕寮€ final锛屼笉鍙彉锛涙瀯閫犳椂浠呴渶涓栫晫绉嶅瓙銆? * 鍙傛暟鍏堟寜璋冪爺鎶ュ憡鍙傝€冨€煎啓姝伙紝鑻?seam 濂戠害涓嶆弧瓒冲垯鍦ㄦ璋冨弬銆?/p>
 */
public class TerrainConfig {

    // ---------------- 涓栫晫甯搁噺 ----------------

    /** 娴峰钩闈紙闄嗗湴鍒ゅ畾绾匡細楂樺害 &gt; SEA_LEVEL 瑙嗕负闄嗗湴锛屾捣骞抽潰 63 浣嗕繚鐣欏哺绾垮甫锛?*/
    public static final int SEA_LEVEL = 62;

    /** 涓栫晫鏈€浣?Y锛?.20 娣辨澘宀╁眰锛?*/
    public static final int MIN_Y = -64;

    /** 鍦板舰鏈€楂樺嘲 */
    public static final int MAX_HEIGHT = 580;

    /** 娴峰簥搴曢儴鍩哄博灞傚帤搴?*/
    public static final int BEDROCK_THICKNESS = 5;

    /** 鍦板舰鍙敓鎴愮殑鏈€浣庢柟鍧?Y锛堝熀宀╁眰椤讹紝-64 + 5 = -59锛?*/
    public static final int MIN_LAND_Y = MIN_Y + BEDROCK_THICKNESS;

    // ---------------- 绉嶅瓙 ----------------

    /** 涓栫晫绉嶅瓙 */
    public final long seed;

    // ---------------- 澶ч檰搴﹀眰锛堝畯瑙傚ぇ闄?娴锋磱锛?----------------

    /** 澶ч檰搴﹀櫔澹伴鐜囷紙浣庨锛?*/
    public final float continentFrequency = 0.002f;

    /** 澶ч檰搴﹀垎褰㈠叓搴︽暟 */
    public final int continentOctaves = 4;

    /**
     * 澶ч檰鏋跺墫闈⑩€斺€旀捣宀哥嚎鍩哄噯浣嶇疆銆?     * 楂樺害浠?s = x + z 涓哄瑙掔嚎鍧愭爣锛歴 == shelfKink 澶勯珮搴︽伆涓烘捣骞抽潰锛?     * s &lt; kink 涓烘捣娲嬶紙缂撳潯 -59 鈫?62锛夛紝s &gt; kink 涓洪檰鍦帮紙闄″潯 62 鈫?宄伴《锛夈€?     * 璇ュ墫闈繚璇佷换鎰?64脳64 缃戞牸鍐呴兘鍖呭惈娴峰簥鍒板嘲椤剁殑瀹屾暣姊害锛堝鏍锋€у绾︼級銆?     */
    public final float shelfKink = 68f;

    /** 娴峰哺绾块殢澶ч檰搴﹀櫔澹版憜鍔ㄧ殑鎸箙锛堟柟鍧楋級 */
    public final float shelfWiggleAmplitude = 2f;

    /** 澶ч檰鏋堕檰鍦颁晶鏂滃潯姊害锛堟柟鍧?鏍硷紝鍙楄繛缁€у绾?鈮?8 绾︽潫锛?*/
    public final float shelfLandSlope = 6.5f;

    /** 澶ч檰鏋舵捣娲嬩晶鎶崌鎬婚噺锛堜粠娴峰簥 MIN_LAND_Y 鍒版捣骞抽潰 SEA_LEVEL锛?*/
    public final float shelfOceanRise = 121f;

    // ---------------- 灞辫剨灞傦紙Ridged 灞辩郴锛?----------------

    /** 灞辫剨鍣０棰戠巼 */
    public final float ridgeFrequency = 0.008f;

    /** 灞辫剨鍒嗗舰鍏害鏁?*/
    public final int ridgeOctaves = 4;

    /** 灞辫剨搴?鈫?灞变綋澧炵泭锛堟柟鍧楋級 */
    public final float ridgeGain = 4f;

    // ---------------- 缁嗚妭灞傦紙涓皬璧蜂紡锛岄珮棰戠巼淇濊瘉缃戞牸鍐呭幓鐩稿叧锛?----------------

    /** 缁嗚妭鍣０棰戠巼 */
    public final float detailFrequency = 0.04f;

    /** 缁嗚妭鍒嗗舰鍏害鏁?*/
    public final int detailOctaves = 3;

    /** 缁嗚妭鎸箙锛堟柟鍧楋級 */
    public final float detailAmplitude = 6f;

    // ---------------- 娌崇綉渚佃殌灞?----------------

    /** 娌崇綉鍣０棰戠巼锛堜綆棰戯紝闃堝€煎垏鍓诧級 */
    public final float riverFrequency = 0.003f;

    /** 娌崇綉鍒嗗舰鍏害鏁?*/
    public final int riverOctaves = 2;

    /** 娌虫祦鍒ゅ畾闃堝€硷細娌崇綉鍣０浣庝簬璇ュ€艰涓烘渤閬?*/
    public final float riverThreshold = 0.0f;

    /** 娌抽亾杩囨浮甯﹀锛堥槇鍊间袱渚у钩婊戣繃娓″搴︼級 */
    public final float riverWidth = 0.35f;

    /** 娌抽亾鏈€澶т笅鎸栨繁搴︼紙鏂瑰潡锛?*/
    public final float riverCutDepth = 5f;

    // ---------------- 鍩熸壄鏇诧紙澶ч檰搴?灞辫剨灞傞噰鏍风敤锛?----------------

    /** 鍩熸壄鏇叉尟骞咃紙鏂瑰潡锛?*/
    public final float domainWarpAmplitude = 10f;

    /** 鍩熸壄鏇查鐜?*/
    public final float domainWarpFrequency = 0.0015f;

    public TerrainConfig(long seed) {
        this.seed = seed;
    }
}
