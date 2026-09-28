package androidx.recyclerview.widget;

/**
 * JVM 单测用的 RecyclerView 占位类。
 *
 * `looksLikeDropdownList` 判的是「直接父类的二进制名等于 `androidx.recyclerview.widget.RecyclerView`」，
 * 单测里要造出这个父类，只能自己放一个同名类进来（宿主里那个由 androidx 提供）。
 * 内嵌的 `LayoutManager` 用来验证「父类是 RecyclerView 的内嵌子类」不算命中。
 */
public class RecyclerView {

    public static class LayoutManager {
    }
}
