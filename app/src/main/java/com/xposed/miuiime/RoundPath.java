package com.xposed.miuiime;


import android.graphics.Path;
import android.graphics.RectF;

public class RoundPath {

    private static Path mRoundPath = new Path();

    public static float coerceAtMost(float value, float maximumValue) {
        if (value > maximumValue) {
            return maximumValue;
        }
        return value;
    }

    public static float coerceAtLeast(float value, float minimumValue) {
        if (value < minimumValue) {
            return minimumValue;
        }
        return value;
    }


    public static Path getSmoothRoundPath(RectF rectF, float radius) {
        radius = Math.max(radius, 0);

        float left = rectF.left;
        float top = rectF.top;
        float width = rectF.width();
        float height = rectF.height();


        // 开始画圆角的位置对比圆角大小的偏移比例
        float radiusOffsetRatio = 128f / 100f;
        // 靠近圆弧两个端点的点的xy坐标比例
        float endPointRatio = 83f / 100f;
        // 左上角第一条曲线第二个点x坐标的比例(其他三个点通过矩阵转换可以使用同样的比例）
        float firstCSecondPXRatio = 67f / 100f;
        // 左上角第一条曲线第二个点Y坐标的比例（其他三个点通过矩阵转换可以使用同样的比例)
        float firstCSecondPYRatio = 4f / 100f;
        // 左上角第一条曲线第三个点x坐标的比例（其他三个点通过矩阵转换可以使用同样的比例)
        float firstCThirdPXRatio = 51f / 100f;
        // 左上角第一条曲线第三个点Y坐标的比例(其他三个点通过矩阵转换可以使用同样的比例)
        float firstCThirdPYRatio = 13f / 100f;
        // 左上角第二条曲线第一个点X坐标的比例（其他三个点通过矩阵转换可以使用同样的比例)
        float secondCFirstPXRatio = 34f / 100f;
        // 左上角第二条曲线第一个点Y坐标的比例(其他三个点通过矩阵转换可以使用同样的比例)
        float secondCFirstPYRatio = 22f / 100f;

        mRoundPath.reset();

        mRoundPath.moveTo((width / 2.0f) + left, top);//顶部直线和右上角圆角

        // 顶部直线和右上角圆角
        mRoundPath.lineTo(
                coerceAtLeast((width / 2.0f), (width - radius * radiusOffsetRatio) + left),
                top
        );
        mRoundPath.cubicTo(
                left + width - radius * endPointRatio, top,
                left + width - radius * firstCSecondPXRatio,
                top + radius * firstCSecondPYRatio,
                left + width - radius * firstCThirdPXRatio,
                top + radius * firstCThirdPYRatio
        );
        mRoundPath.cubicTo(
                left + width - radius * secondCFirstPXRatio,
                top + radius * secondCFirstPYRatio,
                left + width - radius * secondCFirstPYRatio,
                top + radius * secondCFirstPXRatio,
                left + width - radius * firstCThirdPYRatio,
                top + radius * firstCThirdPXRatio
        );
        mRoundPath.cubicTo(
                left + width - radius * firstCSecondPYRatio,
                top + radius * firstCSecondPXRatio,
                left + width,
                top + radius * endPointRatio,
                left + width,
                top + coerceAtMost((height / 2.0f), radius * radiusOffsetRatio)
        );

        //右边直线和右下角圆角
        mRoundPath.lineTo(left + width, coerceAtLeast((height / 2.0f), height - radius * radiusOffsetRatio) + top);
        mRoundPath.cubicTo(
                left + width,
                top + height - radius * endPointRatio,
                left + width - radius * firstCSecondPYRatio,
                top + height - radius * firstCSecondPXRatio,
                left + width - radius * firstCThirdPYRatio,
                top + height - radius * firstCThirdPXRatio
        );
        mRoundPath.cubicTo(
                left + width - radius * secondCFirstPYRatio,
                top + height - radius * secondCFirstPXRatio,
                left + width - radius * secondCFirstPXRatio,
                top + height - radius * secondCFirstPYRatio,
                left + width - radius * firstCThirdPXRatio,
                top + height - radius * firstCThirdPYRatio
        );

        mRoundPath.cubicTo(
                left + width - radius * firstCSecondPXRatio,
                top + height - radius * firstCSecondPYRatio,
                left + width - radius * endPointRatio,
                top + height,
                left + coerceAtLeast((width / 2.0f), width - radius * radiusOffsetRatio),
                top + height
        );

        // 底部直线和左下角圆角
        mRoundPath.lineTo(coerceAtMost((width / 2.0f), radius * radiusOffsetRatio) + left, top + height);
        mRoundPath.cubicTo(
                left + radius * endPointRatio,
                top + height,
                left + radius * firstCSecondPXRatio,
                top + height - radius * firstCSecondPYRatio,
                left + radius * firstCThirdPXRatio,
                top + height - radius * firstCThirdPYRatio
        );
        mRoundPath.cubicTo(
                left + radius * secondCFirstPXRatio,
                top + height - radius * secondCFirstPYRatio,
                left + radius * secondCFirstPYRatio,
                top + height - radius * secondCFirstPXRatio,
                left + radius * firstCThirdPYRatio,
                top + height - radius * firstCThirdPXRatio
        );
        mRoundPath.cubicTo(
                left + radius * firstCSecondPYRatio,
                top + height - radius * firstCSecondPXRatio,
                left,
                top + height - radius * endPointRatio,
                left,
                top + coerceAtLeast((height / 2.0f), height - radius * radiusOffsetRatio)
        );
        // 左边直线和左上角圆角
        mRoundPath.lineTo(left, coerceAtMost((height / 2.0f), radius * radiusOffsetRatio) + top);
        mRoundPath.cubicTo(
                left,
                top + radius * endPointRatio,
                left + radius * firstCSecondPYRatio,
                top + radius * firstCSecondPXRatio,
                left + radius * firstCThirdPYRatio,
                top + radius * firstCThirdPXRatio
        );
        mRoundPath.cubicTo(
                left + radius * secondCFirstPYRatio,
                top + radius * secondCFirstPXRatio,
                left + radius * secondCFirstPXRatio,
                top + radius * secondCFirstPYRatio,
                left + radius * firstCThirdPXRatio,
                top + radius * firstCThirdPYRatio
        );

        mRoundPath.cubicTo(
                left + radius * firstCSecondPXRatio,
                top + radius * firstCSecondPYRatio,
                left + radius * endPointRatio,
                top,
                left + coerceAtMost((width / 2.0f), radius * radiusOffsetRatio),
                top
        );
        mRoundPath.close();
        return mRoundPath;
    }

    public static Path getSmoothTopRoundPath(RectF rectF, float radius) {
        radius = Math.max(radius, 0);

        float left = rectF.left;
        float top = rectF.top;
        float right = rectF.right;
        float bottom = rectF.bottom;
        float width = rectF.width();
        float height = rectF.height();

        float radiusOffsetRatio = 128f / 100f;
        float endPointRatio = 83f / 100f;
        float firstCSecondPXRatio = 67f / 100f;
        float firstCSecondPYRatio = 4f / 100f;
        float firstCThirdPXRatio = 51f / 100f;
        float firstCThirdPYRatio = 13f / 100f;
        float secondCFirstPXRatio = 34f / 100f;
        float secondCFirstPYRatio = 22f / 100f;

        mRoundPath.reset();
        mRoundPath.moveTo((width / 2.0f) + left, top);
        mRoundPath.lineTo(
                coerceAtLeast((width / 2.0f), (width - radius * radiusOffsetRatio) + left),
                top
        );
        mRoundPath.cubicTo(
                left + width - radius * endPointRatio, top,
                left + width - radius * firstCSecondPXRatio,
                top + radius * firstCSecondPYRatio,
                left + width - radius * firstCThirdPXRatio,
                top + radius * firstCThirdPYRatio
        );
        mRoundPath.cubicTo(
                left + width - radius * secondCFirstPXRatio,
                top + radius * secondCFirstPYRatio,
                left + width - radius * secondCFirstPYRatio,
                top + radius * secondCFirstPXRatio,
                left + width - radius * firstCThirdPYRatio,
                top + radius * firstCThirdPXRatio
        );
        mRoundPath.cubicTo(
                left + width - radius * firstCSecondPYRatio,
                top + radius * firstCSecondPXRatio,
                left + width,
                top + radius * endPointRatio,
                right,
                top + coerceAtMost((height / 2.0f), radius * radiusOffsetRatio)
        );
        mRoundPath.lineTo(right, bottom);
        mRoundPath.lineTo(left, bottom);
        mRoundPath.lineTo(left, top + coerceAtMost((height / 2.0f), radius * radiusOffsetRatio));
        mRoundPath.cubicTo(
                left,
                top + radius * endPointRatio,
                left + radius * firstCSecondPYRatio,
                top + radius * firstCSecondPXRatio,
                left + radius * firstCThirdPYRatio,
                top + radius * firstCThirdPXRatio
        );
        mRoundPath.cubicTo(
                left + radius * secondCFirstPYRatio,
                top + radius * secondCFirstPXRatio,
                left + radius * secondCFirstPXRatio,
                top + radius * secondCFirstPYRatio,
                left + radius * firstCThirdPXRatio,
                top + radius * firstCThirdPYRatio
        );
        mRoundPath.cubicTo(
                left + radius * firstCSecondPXRatio,
                top + radius * firstCSecondPYRatio,
                left + radius * endPointRatio,
                top,
                left + coerceAtMost((width / 2.0f), radius * radiusOffsetRatio),
                top
        );
        mRoundPath.close();
        return mRoundPath;
    }
}

