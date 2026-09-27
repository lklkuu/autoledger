package com.autoledger.feature.classify

import com.autoledger.core.model.ClassifierRule
import com.autoledger.core.model.RuleKind

/**
 * 出厂规则包。
 *
 * 这些只是**初始配置数据**，不是业务逻辑 —— 它们和用户自己在设置里手动加的规则走完全相同的
 * 存储与匹配路径。也因此将来可以做成「规则包在线更新」，不必发版。
 */
object DefaultRulePack {

    /** keyword -> categoryId 的扁平映射，初始化时展开成一张规则表 */
    val KEYWORDS: Map<String, List<String>> = mapOf(
        "cat_food" to listOf(
            "餐饮", "餐厅", "饭店", "外卖", "美团", "饿了么", "星巴克", "瑞幸", "咖啡", "奶茶",
            "火锅", "烧烤", "食堂", "肯德基", "麦当劳", "海底捞", "沙县", "面馆", "面包", "早餐",
        ),
        "cat_transport" to listOf(
            "地铁", "公交", "打车", "滴滴", "高铁", "火车", "机票", "加油", "停车", "12306",
            "出租车", "共享单车", "哈啰", "青桔", "ofo", "车费", "ETC", "网约车",
        ),
        "cat_shopping" to listOf(
            "淘宝", "天猫", "京东", "拼多多", "唯品会", "便利店", "超市", "商场", "屈臣氏",
            "名创优品", "抖音商城", "得物", "闲鱼", "服饰", "鞋帽",
        ),
        "cat_home" to listOf("房租", "物业", "水费", "电费", "燃气", "宽带", "房贷", "中介", "家居", "装修"),
        "cat_fun" to listOf("电影", "影院", "Steam", "演唱会", "KTV", "游戏", "视频会员", "健身", "密室", "展览"),
        "cat_health" to listOf("医院", "药房", "药店", "挂号", "体检", "诊所", "口腔", "医保"),
        "cat_study" to listOf("书店", "课程", "学费", "培训", "图书", "网课", "考试"),
        "cat_social" to listOf("红包", "份子钱", "礼品", "亲友转账"),
        "cat_subscription" to listOf("会员", "订阅", "icloud", "apple music", "spotify", "自动续费"),
    )

    fun rules(): List<ClassifierRule> = KEYWORDS.flatMap { (categoryId, keywords) ->
        keywords.map { keyword ->
            ClassifierRule(
                id = "seed:${categoryId}:${keyword}",
                kind = RuleKind.KEYWORD,
                pattern = keyword,
                categoryId = categoryId,
                priority = keyword.length, // 更具体的词优先命中，例如「星巴克」胜过「餐饮」
                learned = false,
            )
        }
    }
}
