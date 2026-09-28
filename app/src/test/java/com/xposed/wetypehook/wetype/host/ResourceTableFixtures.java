package com.xposed.wetypehook.wetype.host;

/**
 * 宿主 R 类的真实长相：纯 Java 产物，清一色 {@code public static final int}，没有别的静态字段。
 *
 * 用 Java 写是因为 Kotlin 造不出这种类 —— 任何 Kotlin 构造都会额外多出一个非 int 的静态字段
 * （companion 的 {@code Companion}、object 的 {@code INSTANCE}），把形状判据顶掉。
 */
final class ResourceTableFixtures {

    private ResourceTableFixtures() {
    }

    static final class AttrTable {
        public static final int Alphabet_text_color = 0x7f010001;
        public static final int S10_text_color = 0x7f010002;
        public static final int S11_img_type_icon_color_normal = 0x7f010003;
        public static final int S8_emoji_exception_text_color = 0x7f010004;
    }

    /** 同族的另一张表：只和 {@link AttrTable} 共用一个名字。 */
    static final class ColorTable {
        public static final int S10_text_color = 0x7f020001;
        public static final int ime_about_title_color = 0x7f020002;
        public static final int ime_app_panel_text_color_normal = 0x7f020003;
    }

    static final class DimenTable {
        public static final int S10_index_radius = 0x7f030001;
        public static final int S10_index_margin_start = 0x7f030002;
        public static final int S10_button_icon_width = 0x7f030003;
    }

    /** 声明了锚名，但不是资源表。 */
    static final class NotATable {
        public static final String Alphabet_text_color = "nope";
    }

    /** 形状对，字段太少。 */
    static final class TinyTable {
        public static final int S10_index_radius = 0x7f040001;
    }
}
