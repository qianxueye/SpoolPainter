package com.spoolpainter.app.hardware.paper;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

/** logcat receives this as one argv value, never as shell text. */
public final class CaptureStartPolicy {
    private CaptureStartPolicy() {}
    public static String lookback(long nowMs){return lookback(nowMs,TimeZone.getDefault());}
    static String lookback(long nowMs,TimeZone zone){
        SimpleDateFormat format=new SimpleDateFormat("MM-dd HH:mm:ss.SSS",Locale.US);format.setTimeZone(zone);
        return format.format(new Date(nowMs-2000));
    }
}
