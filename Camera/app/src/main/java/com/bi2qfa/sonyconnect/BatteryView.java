package com.bi2qfa.sonyconnect;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;

public class BatteryView extends View {
    private int level = -1;

    private final Paint framePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint capPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    public BatteryView(Context context) {
        this(context, null);
    }

    public BatteryView(Context context, AttributeSet attrs) {
        super(context, attrs);
        framePaint.setStyle(Paint.Style.STROKE);
        framePaint.setStrokeWidth(3f);
        framePaint.setColor(0xffdddddd);
        capPaint.setStyle(Paint.Style.FILL);
        capPaint.setColor(0xffdddddd);
        fillPaint.setStyle(Paint.Style.FILL);
    }

    public void setLevel(int pct) {
        level = pct;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        int w = getWidth();
        int h = getHeight();
        float capW = h / 5f;
        RectF body = new RectF(1.5f, 1.5f, w - capW - 2f, h - 1.5f);
        canvas.drawRoundRect(body, 4f, 4f, framePaint);
        canvas.drawRect(new RectF(w - capW - 1.5f, h * 0.3f, w - 1.5f, h * 0.7f), capPaint);
        if (level >= 0) {
            fillPaint.setColor(level <= 10 ? 0xffdd0000
                    : level <= 25 ? 0xffdd8800 : 0xffdddddd);
            float inset = 4.5f;
            float avail = (body.width() - inset * 2f) * level / 100f;
            if (avail > 1f) {
                canvas.drawRect(body.left + inset, body.top + inset,
                        body.left + inset + avail, body.bottom - inset, fillPaint);
            }
        }
    }
}
