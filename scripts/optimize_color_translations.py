#!/usr/bin/env python3
"""
Final comprehensive translation optimization for meodai color names.
Strategy:
1. Word-level translation for simple compound names (all words known)
2. Exact fixes for specific problematic entries
3. Component fixes for systematic patterns in existing translations
"""
import re
from pathlib import Path

TSV_PATH = Path(__file__).parent.parent / "app/src/main/assets/color_names/meodai_color_names_14_38_0.tsv"

# Load comprehensive word map
from word_map_data import WORD_MAP

# ============================================================
# EXACT FIXES
# ============================================================
EXACT_FIXES = {
    # ---- A ----
    "100 Mph": "时速百里",
    "20000 Leagues Under the Sea": "海底两万里",
    "24 Carrot": "24K胡萝卜",
    "24 Karat": "24K金",
    "3AM Breakup": "凌晨三点分手",
    "8 Bit Eggplant": "8位像素茄",
    "99 Years Blue": "九十九年蓝",
    "A Brand New Day": "崭新的一天",
    "A Certain Shade of Green": "某种绿调",
    "A Dime a Dozen": "平淡无奇",
    "A Frond in Need": "待援之叶",
    "À l'Orange": "橙香",
    "A la Mode": "流行风尚",
    "A Lot of Love": "满满的爱",
    "A Month of Sundays": "漫长时光",
    "A Pair of Brown Eyes": "一双棕眸",
    "A Plum Job": "美差",
    "A Stitch in Time": "及时一针",
    "Above Board": "光明正大",
    "Abra Cadabra": "咒语",
    "Absence of Light": "无光之境",
    "Acanthus": "茛苕",
    "Acanthus Leaf": "茛苕叶",
    "Accent Green Blue": "强调绿蓝",
    "Accent Orange": "强调橙",
    "Accessible Beige": "亲和米色",
    "Acid Blond": "酸金发",
    "Acid Sleazebag": "酸痞绿",
    "Acoustic Brown": "声学棕",
    "Acoustic White": "声学白",
    "Active Turquoise": "活力青",
    "Aggressive Aqua": "烈性水色",
    "Aggressive Baby Blue": "烈性淡蓝",
    "Aggressive Salmon": "烈性鲑粉",
    "Bare Mintimum": "极简薄荷",
    "Bare Pink": "素粉",
    "Barely Aqua": "微泛水色",
    "Barely Berry": "微泛莓色",
    "Barely Bloomed": "含苞初放",
    "Barely Blue": "微蓝",
    "Barely Brown": "微棕",
    "Barely Butter": "微泛黄油",
    "Barely Mauve": "微泛淡紫",
    "Barely Peach": "微泛桃色",
    "Barely Pear": "微泛梨色",
    "Barely Pink": "微粉",
    "Barely Ripe Apricot": "将熟杏子",
    "Barely Rose": "微玫",
    "Barely White": "微白",
    "Barest Celadon": "极淡青瓷",
    "Barest Hint of Blue": "一丝蓝意",
    "Barest Hush": "万籁俱寂",
    "Bastard Amber": "混色琥珀",
    "Be Mine": "属于我",
    "Be My Valentine": "做我的情人",
    "Be Spontaneous": "顺其自然",
    "Be Yourself": "做你自己",
    "Blood Orange": "血橙",
    "Blue Screen of Death": "蓝屏死机",
    "Bored Accent Green": "无聊强调绿",
    "Caught Red-Handed": "当场抓获",
    "Cautious Blue": "谨慎蓝",
    "Cautious Grey": "谨慎灰",
    "Cautious Jade": "谨慎玉",
    "Delicious Mandarin": "美味柑橘",
    "Delighted Chimp": "快乐猩猩",
    "Delirious Donkey": "疯驴灰",
    "Dépaysement": "异域感",
    "Descent Into the Catacombs": "坠入墓穴",
    "Detailed Devil": "细节恶魔",
    "Devil's Advocate": "魔鬼代言人",
    "Dew Not Disturb": "露水勿扰",
    "Fickle Pickle": "善变腌瓜",
    "Fiddle-Leaf Fig": "琴叶榕",
    "Fight the Sunrise": "对抗日出",
    "Fine China": "精致瓷器",
    "Fine Gold": "纯金",
    "Fine Grain": "细纹理",
    "Fine Greige": "精致灰米",
    "Fine Linen": "精致亚麻",
    "Fine Pine": "精致松",
    "Fine Porcelain": "精致瓷器",
    "Fine Purple": "精致紫",
    "Fine Sand": "细沙",
    "Fine Tuned Blue": "微调蓝",
    "Fine White": "精致白",
    "Fine Wine": "美酒",
    "Finesse": "精妙",
    "Fioletowy Beige": "紫米色",
    "Fioletowy Purple": "紫紫",
    "First Blush": "第一抹绯红",
    "First Colours of Spring": "春天第一抹色彩",
    "Fish Boy": "鱼男孩",
    "Fish Ceviche": "酸橘汁腌鱼",
    "Grape Expectations": "远大葡程",
    "Great Tit Eggs": "大山雀卵",
    "Great Void": "巨大虚空",
    "Greasy Green Beans": "油腻青豆",
    "Greasy Greens": "油腻绿蔬",
    "Hot Pink": "艳粉",
    "Hot Chocolate": "热巧克力",
    "La la Lavender": "啦啦薰衣草",
    "La la Love": "啦啦爱",
    "La Minute": "这一刻",
    "La Paz Siesta": "拉巴斯午睡",
    "La Vida": "缤纷生活",
    "La Vie en Rose": "玫瑰人生",
    "Lacquered Liquorice": "漆光甘草",
    "Match Strike": "火柴擦燃",
    "Matcha Mecha": "抹茶机甲",
    "Matcha Picchu": "抹茶比丘",
    "Mattar Paneer": "豌豆奶酪咖喱",
    "Yum Raw Spam": "美味午餐肉",
    "Zoodles": "西葫芦面",
}

# ============================================================
# COMPONENT FIXES — applied to existing translations
# ============================================================
COMPONENT_FIXES = [
    # Overly literal words → natural Chinese
    ("口音", "强调"),
    ("老鼠属", "茛苕"),
    ("老鼠叶", "茛苕叶"),
    ("报警", "警报"),
    ("摘要", "抽象"),
    ("白化病", "泛白"),
    ("丰度", "丰盈"),
    ("一门", "一线"),
    ("阿拉摩德", "流行风尚"),
    ("阿卡普尔科太阳报", "阿卡普尔科艳阳"),
    ("阿格雷兰地球", "阿格里兰大地"),
    ("格雷大律师", "大律师灰"),
    ("大山雀蛋", "大山雀卵"),
    ("伟大虚空", "巨大虚空"),
    ("付款", "异域感"),
    ("百胜原始垃圾邮件", "美味午餐肉"),
    ("柚子子庄", "柚子胡椒"),
    ("青苹果苹果", "青苹果"),
    ("被烧毁的地球", "烧土"),
    ("深入地下墓穴", "坠入墓穴"),
    ("混蛋琥珀", "混色琥珀"),
    ("详细恶魔", "细节恶魔"),

    # Awkward phrasing → natural
    ("勉强阿夸", "微泛水色"),
    ("勉强蓝色", "微蓝"),
    ("勉强桃子", "微桃"),
    ("勉强梨子", "微梨"),
    ("勉强玫瑰", "微玫"),
    ("勉强白", "微白"),
    ("几乎没有浆果", "微泛莓色"),
    ("几乎没有黄油", "微泛黄油"),
    ("略带紫红色", "微泛淡紫"),
    ("裸赤的必需品", "基本所需"),
    ("很多的爱", "满满的爱"),
    ("一份梅花工作", "美差"),
    ("需要帮助的叶子", "待援之叶"),
    ("一毛钱一打", "平淡无奇"),
    ("一个月的星期日", "漫长时光"),
    ("正大光明", "光明正大"),
    ("一双棕色的眼睛", "一双棕眸"),
    ("某种绿色", "某种绿调"),
    ("刚熟的杏子", "将熟杏子"),
    ("淡淡的蓝色", "一抹蓝意"),
    ("绝对安静", "万籁俱寂"),
    ("一篮子金子", "满篮黄金"),

    # Fix time-related awkwardness
    ("烧伤后", "灼痕"),
    ("天黑后", "入夜"),
    ("晚餐后薄荷", "餐后薄荷"),
    ("八次灌装后", "八点后夹心"),
    ("震惊之后", "余震"),
    ("下班后蓝色", "下班蓝"),
    ("派对后粉色", "派对余兴粉"),

    # Matt → 哑光 (matte finish)
    ("马特·布莱克", "哑光黑"),
    ("马特蓝", "哑光蓝"),
    ("马特·格林", "哑光绿"),
    ("马特·粉红", "哑光粉"),
    ("马特紫", "哑光紫"),
    ("马特·塞奇", "哑光鼠尾草"),
    ("马特·怀特", "哑光白"),
    ("马特恶魔", "哑光恶魔红"),

    # Mauve consistency
    ("莫维特", "淡紫小调"),
    ("莫维裸体", "淡紫裸色"),
    ("莫维", "淡紫"),

    # Color compound cleanup
    ("绿色 黄色", "绿黄"),
    ("蓝色 黄色", "蓝黄"),
    ("红色 黄色", "红黄"),
    ("绿色 蓝色", "绿蓝"),
    ("口音 绿色 蓝色", "强调绿蓝"),

    # Trim redundant color suffixes
    (" 蓝色", "蓝"),
    (" 绿色", "绿"),
    (" 红色", "红"),
    (" 黄色", "黄"),
    (" 紫色", "紫"),
    (" 白色", "白"),
    (" 黑色", "黑"),
    (" 棕色", "棕"),
    (" 灰色", "灰"),
    (" 粉色", "粉"),
    (" 橙色", "橙"),

    # Double spaces
    # (handled in post-processing)
]


STOPWORDS = {
    'of', 'the', 'in', 'and', 'a', 'an', 'to', 'on', 'at', 'by', 'for',
    'is', 'are', 'be', 'it', 'or', 'as', 'de', 'la', 'le', 'du', 'des',
    'el', 'il', 'da', 'di', 'von', 'van', 'del', 'dos', 'las', 'los',
    'en', 'et', 'sur', 'avec', 'con', 'per', 'au', 'aux', 'no', 'sans',
    'san', 'y', 'e', 'o', 'und', 'der', 'die', 'das', 'den', 'dem',
    'not', 'so', 'up', 'go', 'me', 'my', 'we', 'no', 'oh', 'all', 'just',
    'one', 'two', 'out', 'off', 'over', 'into', 'with', 'from', 'very',
    'well', 'still', 'back', 'down', 'away', 'much', 'only', 'some',
    'been', 'had', 'has', 'was', 'were', 'will', 'would', 'could',
    'i', 'you', 'he', 'she', 'they', 'we', 'us', 'him', 'her', 'them',
    '&',
}

def translate_name(en_name):
    """Word-level translation: apply when all content words are known."""
    tokens = en_name.split(' ')
    result_parts = []
    all_known = True

    for token in tokens:
        if not token:
            continue
        # Strip punctuation but keep apostrophe for possessives
        token_clean = token.strip("(),;:\"!?.")
        if not token_clean:
            continue
        token_lower = token_clean.lower()

        # Skip stopwords and digits
        if token_lower in STOPWORDS or token_lower.isdigit():
            continue

        # Handle possessives: "Dragon's" → "Dragon"
        if token_lower.endswith("'s") or token_lower.endswith("s'"):
            token_lower = token_lower.rstrip("'s").rstrip("s'")

        if token_lower in WORD_MAP:
            result_parts.append(WORD_MAP[token_lower])
        else:
            all_known = False
            break

    if all_known and result_parts:
        return ''.join(result_parts)
    return None


def translate_line(en_name, current_zh):
    """Translate with fallback strategy."""
    result = None

    # 1. Exact fix?
    if en_name in EXACT_FIXES:
        result = EXACT_FIXES[en_name]
    else:
        # 2. Word-level translation (only if all words known)
        result = translate_name(en_name)

    # 3. Fall back to existing translation
    if result is None:
        result = current_zh

    # 4. Apply component fixes
    for pattern, replacement in COMPONENT_FIXES:
        result = result.replace(pattern, replacement)

    # 5. Post-process
    result = re.sub(r' {2,}', ' ', result)
    result = result.strip()
    if not result:
        result = current_zh

    return result


def main():
    with open(TSV_PATH, 'r', encoding='utf-8') as f:
        lines = f.readlines()

    print(f"Read {len(lines)} lines")
    print(f"WORD_MAP: {len(WORD_MAP)} entries")
    print(f"EXACT_FIXES: {len(EXACT_FIXES)} entries")

    modified_count = 0
    output_lines = []

    for i, line in enumerate(lines):
        if i == 0:
            output_lines.append(line.rstrip('\n').rstrip('\r'))
            continue

        line_stripped = line.rstrip('\n').rstrip('\r')
        if not line_stripped.strip():
            output_lines.append(line_stripped)
            continue

        parts = line_stripped.split('\t')
        if len(parts) < 3:
            output_lines.append(line_stripped)
            continue

        en_name = parts[1]
        current_zh = parts[2]
        new_zh = translate_line(en_name, current_zh)

        if new_zh != current_zh:
            modified_count += 1
            if modified_count % 1000 == 0:
                print(f"  {modified_count}: '{en_name}' → '{new_zh}'")

        parts[2] = new_zh
        output_lines.append('\t'.join(parts))

    print(f"Total modifications: {modified_count}")

    with open(TSV_PATH, 'w', encoding='utf-8', newline='') as f:
        for line in output_lines:
            f.write(line + '\n')

    print(f"Written {len(output_lines)} lines")
    print("Done!")


if __name__ == '__main__':
    main()
