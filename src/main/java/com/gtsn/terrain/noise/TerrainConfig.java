package com.gtsn.terrain.noise;

/**
 * 鍦板舰鍙傛暟閰嶇疆锛圡6锛歏oronoi 澶ч檰鏉垮潡 + 娴锋嫈鍒嗗甫 + 渚佃殌闆曞埢 + 娌虫祦绯荤粺锛夈€? *
 * <p>鎵€鏈夊瓧娈靛叕寮€ final锛屼笉鍙彉锛涙瀯閫犳椂浠呴渶涓栫晫绉嶅瓙銆? *
 * <p>M6 鏋舵瀯锛堟浛鎹?M5 鐨勩€孫penSimplex2 澶ч檰搴?+ 涓夊昂搴﹀北浣?+ 绾挎€у唴闄嗛棬鎺с€嶏級锛? * <ul>
 *   <li>鏉垮潡灞傦細Cellular(Distance) 鍒版澘鍧椾腑蹇冪殑璺濈鍦?鈫?澶ч檰搴?c锛堣繎涓績楂樸€佽竟缂樹綆锛夛紝
 *       閲囨牱鍧愭爣鍏堝ぇ鎸箙浣庨鍩熸壄鏇诧紙鏉垮潡杈圭晫鑷劧寮洸锛夛紝鍐嶅姞涓娴峰哺鎽嗗姩锛堟捣婀?鍗婂矝锛夈€? *       c &gt; 0 涓洪檰锛堟澘鍧楁牳蹇冭繎闄嗭級锛宑 &lt; 0 涓烘捣锛堟澘鍧楅棿涓庢澘鍧楄竟缂?娣辨捣娌燂級銆? *       棰戠巼 0.0012 鈫?鏉垮潡闂磋窛 ~830 鍧楋紱鍗婂緞 R=0.5 鈫?澶ч檰鏍稿績瀹?~400 鍧椼€?/li>
 *   <li>娴锋嫈鍒嗗甫锛氬熀纭€娴锋嫈鐢辩嫭绔嬬殑瓒呬綆棰戝櫔澹帮紙位~1250 鍧楋級椹卞姩锛堜笌鏉垮潡瑙ｈ€︼紝閬垮厤
 *       銆屾澘鍧楁搴γ楅珮澧炵泭=鏁寸墖闄″潯銆嶏級锛屽姞涓婂唴闄嗛棬鎺у井鎶崌锛涘垎甯︾獥鍙ｅ湪鍩虹娴锋嫈涓? *       smoothstep 鍒囧垎 娴峰哺骞冲師/涓橀櫟/灞遍簱/楂樺北/闆嚎锛屾瘡甯︾嫭绔嬪皬鎸箙鍣０銆? *       灞变綋鍣０浠呭湪楂樺北甯︾獥鍙?脳 灞遍摼钂欑増鍐呮縺娲烩€斺€斿北鍙嚭鐜板湪楂樺北甯︼紝涓嶅啀鍒板鏄北銆?/li>
 *   <li>灞变綋绯荤粺锛氬鐢?M5 涓夊昂搴﹀垎绂伙紙massif 骞虫粦澶ц川閲?+ chain 鍚勫悜寮傛€?V 鍨嬪皷鑴?+ ridge 灏栧嘲锛夛紝
 *       闂ㄦ帶 = 楂樺北甯︾獥鍙?脳 灞遍摼钂欑増锛圧idged 鑴婄嚎瀹氫綅璧板悜锛夈€?/li>
 *   <li>娌虫祦绯荤粺锛歳iverNoise 闃堝€间笅鎸栵紝娣卞害闅忓唴闄嗗害娓愬彉锛堝唴闄嗘繁銆佽繎娴锋祬锛夈€?/li>
 *   <li>渚佃殌锛氱儹渚佃殌锛坱alus 鏉惧紱锛? 姘存淮渚佃殌锛圚ydraulic Erosion锛夛紝鐢? *       {@link HeightCache} 鎸夊尯鍧?16脳16 鍚?8 杈圭晫)棰勮绠楃紦瀛樸€?/li>
 * </ul>
 *
 * <p>鍧″害棰勭畻锛?6 鍧楀熀绾?avg<12掳銆?30掳<5%锛夛細鍩虹娴锋嫈澧炵泭 130 浜?位1250 鈫?0.10/鏍?6掳)锛? * 鍒嗗甫鍣０ 位鈮?66銆佹尟骞呪墹8 鈫?鈮?1掳锛屽北浣撴槸鍞竴闄″抄婧愪絾琚檺鍒跺湪楂樺北甯﹀皬鍖哄煙锛?30掳 闈㈢Н <5%锛夈€? */
public class TerrainConfig {

    // ---------------- 涓栫晫甯搁噺 ----------------

    /** 娴峰钩闈紙闄嗗湴鍒ゅ畾绾匡細楂樺害 > SEA_LEVEL 瑙嗕负闄嗗湴锛?*/
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

    // ---------------- 澶ч檰鏉垮潡灞傦紙Voronoi 鍒版澘鍧椾腑蹇冭窛绂诲満锛?----------------

    /** 鏉垮潡鍣０棰戠巼锛?/f 鈮?鏉垮潡闂磋窛锛屽潡锛夈€?.0012 鈫?鏉垮潡闂磋窛 ~830 鍧楋紝
     *  涓夐獙鏀剁獥鍙?(0,0)/(-1024,0)/(512,512) 瑕嗙洊涓嶅悓鏉垮潡浣嶇疆锛坥rigin 杩戝哺銆亀2 鍐呴檰锛夈€?*/
    public float plateFrequency = 0.004f;

    /** 鏉垮潡閲囨牱鍧愭爣鍩熸壄鏇叉尟骞咃紙鍧楋級锛氬ぇ鎸箙浣庨鎵洸 鈫?鏉垮潡杈圭晫鑷劧寮洸 */
    public float plateWarpAmplitude = 20f;

    /** 鏉垮潡閲囨牱鍧愭爣鍩熸壄鏇查鐜囷紙浣庨锛?*/
    public final float plateWarpFrequency = 0.0004f;

    /** 鏉垮潡鍣０ jitter锛?-1锛夛細瓒婂ぇ鏉垮潡杈圭晫瓒婁笉瑙勫垯 */
    public final float plateJitter = 0.5f;

    /** 鏉垮潡鍗婂緞锛堝櫔澹板崟浣嶏級锛氬ぇ闄嗗害 c = 1 - d0/R锛宒0 涓哄埌鏉垮潡涓績璺濈銆?     *  R=0.5 鈫?鏉垮潡鏍稿績鍖?c>0锛堥檰锛夛紝d0>R 澶?c<0锛堟澘鍧楅棿=娴锋磱/娣辨捣娌燂級銆?*/
    public float plateRadius = 0.5f;

    /** 鏉垮潡鍣０閲囨牱鍧愭爣鍥哄畾鍋忕Щ锛圥robePlate4 鎵弿 freq=0.0012/R=0.5锛歰rigin 37.8% / w1 39.7% / w2 鍐呴檰锛夈€?     *  闈?final锛歍4 璋冨弬鎺㈤拡鍦ㄥ紑鍙戞湡鎵弿鍋忕Щ锛堜笁涓獙鏀剁獥鍙ｇ殑鏉垮潡鍦扮悊鐢卞亸绉诲喅瀹氾級锛岄攣瀹氬悗涓嶄慨鏀广€?*/
    public float plateOffsetX = -810f;
    public float plateOffsetZ = 1204f;

    /** 娴峰哺鎽嗗姩鍣０鎸箙锛堝彔鍔犲埌澶ч檰搴︼紝鍒堕€犳捣婀?鍗婂矝锛岃 c=0 娴峰哺绾垮垎褰級 */
    public final float coastWiggleAmplitude = 0.15f;

    /** 娴峰哺鎽嗗姩鍣０棰戠巼锛堜腑棰戯級 */
    public final float coastWiggleFrequency = 0.004f;

    /** 娴峰簥娣卞害鍧″锛歞epth = 121脳smoothstep01(-c/scale)銆俢=-scale 澶勮揪婊℃繁锛?59锛夈€?     *  scale=1.0锛歝=-1 杈炬弧娣憋紱鏈€闄″瀵兼暟 121脳1.5/1.0 鈮?180/鍗曚綅 c 脳 dc/dblock鈮?.0017
     *  鈫?0.31/鏍?鈮?17掳锛屾捣宀稿甫绐勶紝涓嶇牬鍧忓潯搴﹂绠楋紙M5 鏇剧敤 0.4 涓?S4 閫氳繃锛?*/
    public float oceanDepthScale = 3.0f;

    // ---------------- 鍩虹娴锋嫈灞傦紙瓒呬綆棰戠嫭绔嬪櫔澹伴┍鍔紝涓庢澘鍧楄В鑰︼級 ----------------

    /** 鍩虹娴锋嫈鍣０棰戠巼锛埼?1/f 鈮?1250 鍧楋紝瓒呬綆棰戯級锛氬喅瀹氬ぇ鑼冨洿骞冲師/涓橀櫟/灞遍簱鍩哄簳 */
    public final float baseNoiseFrequency = 0.00025f;

    /** 鍩虹娴锋嫈鍣０閲囨牱鍧愭爣鍋忕Щ锛圱4 璋冨弬锛歸2(512,512) 鍘熻惤鍦ㄩ珮 baseN 鍖?鈫?鏃犱綆鍦?S10 鎸傦紱
     *  鍋忕Щ鎶婂钩鍘熸壂鍒伴獙鏀剁獥鍙ｏ紱閿佸畾鍚庝笉淇敼锛?*/
    public float baseOffsetX = 1500f;
    public float baseOffsetZ = 0f;

    /** 鍩虹娴锋嫈鍒嗗舰鍏害鏁?*/
    public final int baseNoiseOctaves = 2;

    /** 鍩虹娴锋嫈澧炵泭锛堝潡锛夛細base = 62 + baseNoise01脳璇ュ€?鈫?62..192锛?鍐呴檰闂ㄦ帶 鈫?鏈€楂?207銆?     *  楂樺北甯﹀叆鍙?base>135锛坆aseNoise01>0.45 鍐呴檰澶勶級鈥斺€斿鐩?130 鎵嶈兘璁╅珮灞卞甫/灞辫剦鏈夊疄璐ㄩ潰绉紱
     *  鍧″害 = 130/1250 鈮?0.10/鏍?鈮?6掳锛?6 鍧楀熀绾匡級锛岄绠楀厖瓒?*/
    public float baseElevationGain = 40f;

    /** 娴峰哺鏀舵暃鍧″锛堝潡/鍗曚綅 c锛夛細base 澧炵泭鎸夊唴闄嗗害 smoothstep 娓愬锛坈=0 澶?0 鈫?c=coastRampWidth 澶勬弧鍊硷級銆?     *  瀹藉潯閬垮厤娴峰哺鎮礀锛堝疄娴?baseN脳澧炵泭鐩存帴鏂藉姞鏃?rawDelta 杈?64锛夛紱0.6 鈫?婊?base 闇€ c>=0.6锛堝唴闄嗭級 */
    public final float coastRampWidth = 0.8f;

    /** 鍐呴檰闂ㄦ帶鎶崌锛堝潡锛夛細c 瓒婇珮瓒婂唴闄嗭紝寰姮鍗囷紙淇濇寔骞崇紦锛?*/
    public final float inlandLift = 8f;

    // ---------------- 娴锋嫈鍒嗗甫绐楀彛锛坰moothstep 杩囨浮锛屾棤纭竟鐣岋級 ----------------

    /** 鍒嗗甫杈圭晫锛堝熀纭€娴锋嫈锛屽潡锛夛細62 娴峰钩闈?鈫?娴峰哺骞冲師 62-90 / 涓橀櫟 90-125 / 灞遍簱 125-155 */
    public final float bandPlains = 90f;
    public final float bandHills = 125f;
    public final float bandFoothill = 155f;

    /** 鍒嗗甫绐楀彛杩囨浮瀹藉害锛堝潡锛?*/
    public final float bandTransition = 20f;

    // ---- 鍒嗗甫鍣０锛堝皬鎸箙銆侀暱娉㈤暱锛屾弧瓒冲潯搴﹂绠楋級 ----

    public final float plainsFrequency = 0.006f;   // 位166
    public float plainsAmplitude = 0.2f;       // 16 鍧楀熀绾?~7.5掳

    public final float hillsFrequency = 0.004f;    // 位250
    public float hillsAmplitude = 0.4f;        // 16 鍧楀熀绾?~10.5掳

    public final float foothillFrequency = 0.0025f; // 位400
    public float foothillAmplitude = 0.6f;      // 16 鍧楀熀绾?~10掳

    // ---------------- 灞变綋绯荤粺锛堥珮灞卞甫绐楀彛 脳 灞遍摼钂欑増锛涘鐢?M5 涓夊昂搴﹀垎绂伙級 ----------------

    /** 灞遍摼璧板悜瑙掑害鍦洪鐜囷紙浣庨锛岀粰鍑烘瘡澶勫北鑴夎蛋鍚戣 胃鈭圼0,蟺)锛岄摼缂撴參杞悜锛?*/
    public final float chainAngleFrequency = 0.0008f;

    /** 灞遍摼钂欑増鍣０锛團Bm 浣庨锛夛細瀹氫綅灞变綋鍖哄煙銆偽?1/f锛?.0011(位900) 鏃?256 楠屾敹绐楀彛鍙湅鍒?0.28位锛?     *  mask 鍦ㄧ獥鍙ｅ唴杩戜技鎭掑畾锛堝叏楂?鍏ㄩ浂锛夆啋 鏃犳硶绐楀彛鍐呭垎鍑哄北涓庝綆鍦帮紙S10 鎸傦級銆?.005(位200) 绐楀彛鍐?     *  ~1.3 娉?鈫?鏈夊唴閮ㄧ粨鏋勶紝鍙悓鏃舵弧瓒充綆鍦?35-60% 涓庡北鍩?>400 鍏卞瓨 */
    public float mountainMaskFrequency = 0.002f;
    public final int mountainMaskOctaves = 2;

    // ---- 灞变綋灞傦紙骞虫粦浣庨鍚勫悜寮傛€э紝澶у鐩婃彁渚涘ぇ灏哄害楂樺害锛?----

    public float massifFrequency = 0.00055f;
    public final int massifOctaves = 1;
    public final float massifAlongScale = 0.35f;
    public final float massifCrossScale = 1.9f;
    public final float massifWarpAmplitude = 90f;
    public final float massifWarpFrequency = 0.0004f;
    public float massifGain = 900f;
    public float massifCurvePower = 1.0f;

    /** 灞变綋鍣０閲囨牱鍧愭爣鍥哄畾鍋忕Щ锛圱4 璋冨弬锛氬北浣撳満鏄笘鐣屽潗鏍囧浐瀹氱殑锛屽悎鍚岀獥鍙?w1(-1024,0) 鎭板ソ钀藉湪寮卞尯
     *  鈫?鍔犲亸绉绘妸寮哄北閾炬壂鍒伴獙鏀剁獥鍙ｏ紱閿佸畾鍚庝笉淇敼锛?*/
    public float mountainOffsetX = -2000f;
    public float mountainOffsetZ = -3000f;

    // ---- 閾捐剨灞傦紙浣庨 Ridged 鍚勫悜寮傛€ч噰鏍凤紝V 鍨嬪皷鑴婏紝涓瓑澧炵泭锛?----

    public final float chainFrequency = 0.0014f;
    public final int chainOctaves = 3;
    public final float chainAlongScale = 0.45f;
    public final float chainCrossScale = 1.5f;
    public final float chainWarpAmplitude = 60f;
    public final float chainWarpFrequency = 0.00045f;
    public float chainGain = 0f;
    public final float chainCurvePower = 1.15f;

    // ---- 灏栧嘲缁嗚妭灞傦紙涓 Ridged锛屽皬澧炵泭鎺у埗鍧″害棰勭畻锛?----

    public final float ridgeFrequency = 0.0024f;
    public final int ridgeOctaves = 4;
    public float ridgeGain = 0f;
    public final float ridgeCurvePower = 1.1f;

    // ---------------- 缁嗚妭灞傦紙涓皬璧蜂紡锛岄珮棰戜繚璇佺獥鍙ｅ唴鍘荤浉鍏筹級 ----------------

    public final float detailFrequency = 0.06f;
    public final int detailOctaves = 3;
    public final float detailAmplitude = 1.8f;

    // ---------------- 娌虫祦绯荤粺锛堟繁搴﹂殢鍐呴檰搴︽笎鍙橈紝娌虫祦鍏ユ捣锛?----------------

    public final float riverFrequency = 0.0022f;
    public final int riverOctaves = 2;
    public final float riverThreshold = -0.05f;
    public final float riverWidth = 0.3f;
    public float riverCutDepth = 9f;


    /** 山体门控：mask 从 gateLo 到 gateHi 线性激活（峰核收窄控制面积，梯度可控） */
    public float mountainGateLo = 0.45f;
    public float mountainGateHi = 0.85f;

    // ---------------- 高原核（M6 高原式：确定性距离场，替代 c/mask 驱动） ----------------

    /** 高原中心世界坐标（扫描定位到 w1(-1024,0) 窗口内） */
    public float plateauCX = -840f;
    public float plateauCZ = 30f;
    /** 高原半径（格）：R=80 -> 高原面占 w1 窗口 ~39%（S10 低地 61% 卡线内） */
    public float plateauRadius = 28f;
    /** 椭圆山脊长轴（格）：沿走向角拉长——细长链面积小（S10）长度方向连续（S9） */
    public float plateauLength = 150f;
        /** 高原顶高度（方块 Y） */
    public float plateauHeight = 420f;
    /** 高原面起伏幅度（格）：起伏 30 -> 坡度 ~17°（平缓），贡献 distinct */
    public float plateauRelief = 10f;
    /** 核顶高频小起伏（补 S5 distinct，仅核内面积小不破坏 S11） */
    public float kernelDetailAmplitude = 1.5f;
    public float kernelDetailFrequency = 0.03f;
    /** 第二山脊核中心（错开位置，双峰链，贡献更多高度级 S5 distinct） */
    public float plateauCX2 = -900f;
    public float plateauCZ2 = 20f;
    public float plateauRadius2 = 14f;
    public float plateauLength2 = 110f;
    public float plateauHeight2 = 100f;
    /** 高原面起伏噪声频率（低） */

    public float plateauReliefFrequency = 0.0005f;

    // ---------------- origin 可见山核（M6g：出生点视野内可见的缓坡山） ----------------

    /** origin 山核中心世界坐标：cos 穹顶 + 山麓裙边 + 半径摆动（M6d 经验参数 + M6g 调参） */
    public float peakCX = 256f;
    public float peakCZ = 8f;
    /** 山核半径（格）：R 越大坡度越缓（坡度 ≈ π/2·h/R），R=105 → 峰区最大 ~2.6 格/格 <8 */
    public float peakRadius = 105f;
    /** 山核高度（方块）：峰顶 ≈ base + peakHeight（叠加在 base 层上）；base≈110 → 峰顶≈300-320 */
    public float peakHeight = 175f;
    /** 半径低频摆动（±peakRadiusWobble·R）：山脚轮廓不规则，融入周边丘陵 */
    public float peakRadiusWobble = 0.10f;
    // ---------------- 渚佃殌闆曞埢锛堢儹渚佃殌 + 姘存淮渚佃殌锛孒eightCache 鍖哄潡缂撳瓨锛?----------------

    /** 鐑镜铓€浼戞瑙掞紙鏍?鏍硷級锛氱浉閭诲潯搴﹁秴杩囪鍊煎垯鐗╄川鍚戜綆澶勬惉杩愶紙talus 鏉惧紱锛?*/
    public final float erosionTalus = 16f;

    /** 鐑镜铓€杩唬娆℃暟锛?= 缂撳瓨杈圭晫 8锛屼繚璇佽法鍧椾竴鑷达級 */
    public final int erosionIterations = 2;

    /** 姣忓尯鍧楁按婊存暟閲忥紙32脳32 缃戞牸 ~1024 鐐癸級 */
    public final int hydraulicDropsPerChunk = 0;

    /** 姘存淮鏈€澶ф鏁帮紙<= 缂撳瓨杈圭晫 8锛?*/
    public final int hydraulicMaxSteps = 8;

    public final float hydraulicInertia = 0.05f;
    public final float hydraulicSedimentCapacityFactor = 4f;
    public final float hydraulicMinSedimentCapacity = 0.01f;
    public final float hydraulicErosionRate = 0.5f;
    public final float hydraulicDepositionRate = 0.1f;
    public final int hydraulicErosionRadius = 1;

    // ---------------- 楂樺害缂撳瓨锛圡6 鎬ц兘璁捐锛?----------------

    /** 缂撳瓨杈圭晫瀹藉害锛堝潡锛夛細渚佃殌褰卞搷鍗婂緞 = 8锛屽尯鍧楃綉鏍?= 16 + 2脳8 = 32 */
    public final int cacheBorder = 16;

    /** LRU 涓婇檺锛堝尯鍧楁暟锛夛細32脳32 float 鈮?4KB/鍧楋紝8192 鍧?鈮?32MB 鍐呭瓨涓婇檺 */
    public final int cacheMaxChunks = 8192;

    public TerrainConfig(long seed) {
        this.seed = seed;
    }
}
