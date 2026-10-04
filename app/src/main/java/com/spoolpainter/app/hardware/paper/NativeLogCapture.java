package com.spoolpainter.app.hardware.paper;

import android.util.Log;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/** Bounded RAM collector. It never writes a raw logcat stream to disk. */
public final class NativeLogCapture implements AutoCloseable {
    public final String marker=UUID.randomUUID().toString().replace("-","");
    private final List<String> lines=new ArrayList<>();
    private final CountDownLatch began=new CountDownLatch(1),checked=new CountDownLatch(1),ended=new CountDownLatch(1);
    private volatile boolean failed,closed;
    private boolean collecting,complete;
    private int bytes,checkIndex=-1;
    private java.lang.Process process;
    private Thread reader;

    public static NativeLogCapture start() throws Exception {
        NativeLogCapture value=new NativeLogCapture();
        try {
            // Replay two seconds so a late logcat connection can recover our unique BEGIN.
            String since=CaptureStartPolicy.lookback(System.currentTimeMillis());
            value.process=new ProcessBuilder("/system/bin/logcat","-v","epoch","-T",since,
                "POS_AT:V","POS_CONTROLLER:V","PosPrinterService:V","PosPrinterClient:V","System.err:W","AndroidRuntime:E","SpoolPaperMotion:I","chatty:V","logd:V","logcat:V","liblog:V","*:S")
                .redirectErrorStream(true).start();
            value.reader=new Thread(value::read,"printer-native-audit");value.reader.setDaemon(true);value.reader.start();
            Log.i("SpoolPaperMotion","BEGIN "+value.marker);
            if(!value.began.await(2,TimeUnit.SECONDS)||value.failed)throw new IllegalStateException("原生日志起始标记未确认");
            Thread.sleep(120);return value;
        } catch(Exception error) {value.close();throw error;}
    }
    private void read() {
        try(BufferedReader input=new BufferedReader(new InputStreamReader(process.getInputStream(),StandardCharsets.UTF_8))) {
            String line;
            while((line=input.readLine())!=null) {
                if(line.startsWith("--------- beginning of "))continue;
                synchronized(lines) {
                    if(!collecting&&!complete&&matches(line,"BEGIN")){collecting=true;lines.add(line);began.countDown();continue;}
                    if(!collecting)continue;
                    bytes+=line.length();
                    if(lines.size()>=4000||bytes>524288){failed=true;continue;}
                    lines.add(line);
                    if(matches(line,"CHECK")&&checkIndex<0){checkIndex=lines.size()-1;checked.countDown();}
                    if(matches(line,"END")){collecting=false;complete=true;ended.countDown();}
                }
            }
            if(!closed&&!complete)failed=true;
        } catch(Exception ignored) {if(!closed)failed=true;}
    }
    private boolean matches(String line,String kind) {
        return line.matches("^\\s*\\d+\\.\\d+\\s+"+android.os.Process.myPid()+"\\s+\\d+\\s+I\\s+SpoolPaperMotion\\s*:\\s*"+kind+" "+Pattern.quote(marker)+"\\s*$");
    }
    public List<String> checkpoint() throws Exception {
        Thread.sleep(120);Log.i("SpoolPaperMotion","CHECK "+marker);
        if(!checked.await(2,TimeUnit.SECONDS)||failed)throw new IllegalStateException("移动前原生日志未确认，未发送移动请求");
        synchronized(lines){return new ArrayList<>(lines.subList(0,checkIndex+1));}
    }
    public List<String> finish() throws Exception {
        Thread.sleep(120);Log.i("SpoolPaperMotion","END "+marker);
        if(!ended.await(2,TimeUnit.SECONDS)||failed)throw new IllegalStateException("原生日志采集不完整，不能确认本次结果");
        synchronized(lines){return new ArrayList<>(lines);}
    }
    @Override public void close() {
        closed=true;
        if(process!=null){process.destroy();try{if(!process.waitFor(1,TimeUnit.SECONDS))process.destroyForcibly();}catch(InterruptedException error){Thread.currentThread().interrupt();}}
    }
}
