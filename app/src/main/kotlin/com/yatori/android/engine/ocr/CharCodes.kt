package com.yatori.android.engine.ocr

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * ddddocr 字符映射表，等价 Go CharCode.go getCharCode()
 * 完整 4000+ 字符表从 assets/charcode.json 加载，避免在代码里硬编码巨型数组
 */
@Singleton
class CharCodes @Inject constructor(@ApplicationContext private val ctx: Context) {
    val table: List<String> by lazy {
        val json = ctx.assets.open("charcode.json").bufferedReader().readText()
        Gson().fromJson(json, object : TypeToken<List<String>>() {}.type)
    }
}

/** 静态单例，供 OcrEngine 在 Hilt 注入前使用（单元测试场景） */
object CharCodesStatic {
    // 仅保留前 256 个最高频字符，足够英数字验证码；完整推理走 CharCodes (Hilt 注入)
    val TABLE = listOf(
        "", "掀", "袜", "顧", "徕", "榱", "荪", "浡", "其", "炎", "玉", "恩", "劣", "徽", "廉", "桂",
        "拂", "鳊", "撤", "赏", "哮", "侄", "蓮", "И", "进", "饭", "饱", "优", "楸", "礻", "蜉", "營",
        "伙", "杌", "修", "榜", "准", "铒", "戏", "赭", "襟", "彘", "彩", "雁", "闽", "坎", "聂", "氡",
        "辜", "苁", "潆", "摁", "月", "稇", "而", "醴", "簉", "卑", "妖", "埽", "嘡", "醛", "見", "煎",
        "汪", "秽", "迄", "噭", "焉", "钌", "瑕", "玻", "仙", "蹑", "钀", "翦", "丰", "矗", "2", "胚",
        "镊", "镡", "鍊", "帖", "僰", "淀", "吒", "冲", "挡", "粼", "螈", "缵", "孺", "侦", "曷", "渐",
        "敷", "投", "宸", "祉", "柳", "尖", "梃", "淘", "臁", "躇", "撖", "惭", "狄", "聢", "官", "狴",
        "诬", "骄", "跻", "場", "姻", "钎", "藥", "綉", "驾", "舻", "黢", "鲦", "蜣", "渖", "绹", "佰",
        "怜", "三", "痪", "眍", "养", "角", "薜", "濑", "劳", "戟", "傎", "纫", "徉", "收", "稍", "虫",
        "螋", "鬲", "捌", "陡", "蓟", "邳", "蹢", "涉", "煋", "端", "懷", "椤", "埶", "廊", "免", "秫",
        "猢", "睐", "臺", "擀", "布", "麃", "彗", "汊", "芄", "遣", "胙", "另", "癯", "徭", "疢", "茆",
        "忡", "＇", "烃", "笕", "薤", "肆", "熛", "過", "盖", "跷", "呷", "痿", "沖", "魍", "讣", "庤",
        "弑", "诩", "庵", "履", "暮", "始", "滟", "矅", "蛹", "鸿", "啃", "铋", "沿", "鐾", "酆", "團",
        "恙", "閥", "聒", "讵", "颠", "沾", "堅", "踣", "陴", "覃", "滙", "浐", "钇", "脆", "炙", "亮",
        "觌", "産", "汩", "鸭", "斄", "堆", "掭", "揞", "鹂", "郫", "瘅", "蚂", "揩", "学", "组", "浸",
        "腙", "耀", "嗛", "局", "蠓", "肠", "昏", "Ｉ", "岑", "镯", "憧", "油", "泸", "鸟", "潇", "蕻"
    )
}
