package com.spoolpainter.app.hardware.paper;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Pure auditor. Unrelated raw rows remain in memory and are never included in the result. */
public final class NativeLogAudit {
    private static final Pattern ROW=Pattern.compile("^\\s*(\\d+\\.\\d+)\\s+(\\d+)\\s+(\\d+)\\s+([VDIWEF])\\s+([^:]+):\\s*(.*)$");
    private static final Pattern AT=Pattern.compile("^\\[SP_(\\d+)\\]\\s+AT([<>])\\s+([0-9A-Fa-f]+)\\s*$");
    private static final Pattern FAULT=Pattern.compile("NAK|ACK\\s*TIMED?\\s*OUT|ACKTIMEDOUT|\\bretry\\b|retransmi|AT\\s*channel.*(?:timeout|closing)|CMD\\{.*TIMEDOUT|DeadObjectException|RemoteException|TransactionTooLargeException|binder.*(?:fail|dead|died)|(?:fail|dead|died).*binder|service.*(?:fail|dead|died|disconnect)|Fatal signal|FATAL EXCEPTION|SIGSEGV|SIGABRT|native.*(?:fault|crash)",Pattern.CASE_INSENSITIVE);
    private static final Pattern DROP=Pattern.compile("dropped\\s+\\d+|chatty.*(?:expire|identical|suppress)|buffer.*overrun|(?:logd|liblog|logcat).*(?:error|denied|drop|suppress|lost|overrun)",Pattern.CASE_INSENSITIVE);
    private NativeLogAudit() {}
    public static final class Transaction {
        public String txHex,rxHex; public long txMs,rxMs;
        public Transaction(String tx,String rx,long start,long end){txHex=tx;rxHex=rx;txMs=start;rxMs=end;}
    }
    public static final class Result {
        public final int nativePid;
        public final List<String> matchedPrinterRows;
        Result(int pid,List<String> rows){nativePid=pid;matchedPrinterRows=rows;}
    }
    private static final class Row {
        int index,pid,sp=-1; double ms; String tag,message,raw,direction; byte[] packet;
    }
    public static Result audit(List<String> lines,String marker,int appPid,Transaction pre,Transaction feed,Transaction post) {
        List<Row> rows=parse(lines),packets=packets(rows);
        Row first=rows.get(0),last=rows.get(rows.size()-1);
        check(first.pid==appPid&&last.pid==appPid&&first.tag.equals("SpoolPaperMotion")&&last.tag.equals("SpoolPaperMotion")&&first.message.equals("BEGIN "+marker)&&last.message.equals("END "+marker),"日志采集边界不完整");
        validateTransaction(pre,0x30);validateTransaction(feed,0x33);validateTransaction(post,0x30);
        check(first.ms<pre.txMs&&last.ms>post.rxMs&&pre.rxMs<=feed.txMs&&feed.rxMs<=post.txMs,"日志未覆盖完整操作时间");
        byte[] desired=unhex(feed.txHex);PaperProtocol.requireWhitelistedRequest(desired);check(desired[4]==0x33,"移动请求类型错误");
        Row sent=unique(packets,desired,">",-1,0);int pid=sent.pid;checkScopedLoss(rows,pid,appPid);
        for(Row r:rows) {
            boolean relevant=r.pid==pid||r.pid==appPid||r.tag.equals("PosPrinterService")||r.tag.equals("PosPrinterClient")
                ||r.tag.equals("System.err")||r.tag.equals("AndroidRuntime")||r.tag.equals("POS_CONTROLLER");
            check(!relevant||!FAULT.matcher(r.message).find(),"检测到原生重试、超时或服务故障；已停止，不能重发");
        }
        List<Row> source=new ArrayList<>();double previous=-1;
        for(Row r:packets) if(r.pid==pid&&r.sp==0) {check(r.ms>=previous,"同源日志时间倒序");previous=r.ms;check(!(r.packet.length==1&&r.packet[0]==0x15),"检测到 NAK；可能已移动，禁止重试");source.add(r);}
        List<String> retained=new ArrayList<>();
        Row[] a=verify(source,pre,pid,retained),b=verify(source,feed,pid,retained),c=verify(source,post,pid,retained);
        check(a[1].index<b[0].index&&b[1].index<c[0].index,"前检查、移动、后检查顺序不符");
        int feeds=0;
        for(Row r:packets) if(r.direction.equals(">")&&r.packet.length>4) {
            int command=r.packet[4]&255;
            check(command!=0x31&&command!=0x32,"采集期间出现其他打印或参数指令");
            if(command==0x33){feeds++;check(Arrays.equals(r.packet,desired)&&r.pid==pid&&r.sp==0,"存在其他移动请求");}
            if(r.pid==pid&&r.sp==0&&r.index>=a[0].index&&r.index<=c[1].index)
                check(Arrays.equals(r.packet,unhex(pre.txHex))||Arrays.equals(r.packet,desired)||Arrays.equals(r.packet,unhex(post.txHex)),"移动期间出现额外原生指令");
        }
        check(feeds==1,"移动请求缺失或重复；不能确认只执行一次");
        return new Result(pid,retained);
    }
    public static Result auditPre(List<String> lines,String marker,int appPid,Transaction pre) {
        List<Row> rows=parse(lines),packets=packets(rows);
        Row first=rows.get(0),last=rows.get(rows.size()-1);
        check(first.pid==appPid&&last.pid==appPid&&first.tag.equals("SpoolPaperMotion")&&last.tag.equals("SpoolPaperMotion")&&first.message.equals("BEGIN "+marker)&&last.message.equals("CHECK "+marker),"移动前日志采集边界未确认");
        validateTransaction(pre,0x30);check(first.ms<pre.txMs&&last.ms>pre.rxMs,"移动前日志未覆盖状态检查");
        Row sent=unique(packets,unhex(pre.txHex),">",-1,0);int pid=sent.pid;checkScopedLoss(rows,pid,appPid);
        List<Row> source=new ArrayList<>();double previous=-1;
        for(Row r:rows)check(!FAULT.matcher(r.message).find(),"移动前检测到日志故障或重试，未发送移动请求");
        for(Row r:packets) {
            if(r.pid==pid&&r.sp==0){check(r.ms>=previous,"移动前原生日志时间倒序");previous=r.ms;check(!(r.packet.length==1&&r.packet[0]==0x15),"移动前检测到 NAK");source.add(r);}
            if(r.direction.equals(">")&&r.packet.length>4) {
                int cmd=r.packet[4]&255;
                check(cmd!=0x31&&cmd!=0x32&&cmd!=0x33,"移动前存在其他打印或走纸请求");
                if(cmd==0x30)check(Arrays.equals(r.packet,unhex(pre.txHex))&&r.pid==pid&&r.sp==0,"移动前存在额外状态请求");
            }
        }
        List<String> retained=new ArrayList<>();verify(source,pre,pid,retained);return new Result(pid,retained);
    }
    private static List<Row> parse(List<String> lines) {
        check(lines!=null&&!lines.isEmpty(),"原生日志为空");
        List<Row> rows=new ArrayList<>();
        for(String line:lines) {
            Matcher match=ROW.matcher(line);check(match.matches(),"原生日志格式不完整");
            Row r=new Row();r.index=rows.size();r.ms=new BigDecimal(match.group(1)).multiply(BigDecimal.valueOf(1000)).doubleValue();
            r.pid=Integer.parseInt(match.group(2));r.tag=match.group(5).trim();r.message=match.group(6);r.raw=line;rows.add(r);
            if(r.tag.equals("logd")||r.tag.equals("liblog")||r.tag.equals("logcat"))
                check(!DROP.matcher(line).find(),"全局日志缓冲存在丢失，不能确认单次移动");
            if(r.tag.equals("POS_AT")) {
                Matcher packet=AT.matcher(r.message);check(packet.matches(),"原生传输记录不完整");r.sp=Integer.parseInt(packet.group(1));r.direction=packet.group(2);r.packet=unhex(packet.group(3));
                if(r.packet.length>1) frame(r.packet); else check(r.packet.length==1&&(r.packet[0]==6||r.packet[0]==0x15),"原生单字节响应未知");
            }
        }
        return rows;
    }
    private static void checkScopedLoss(List<Row> rows,int nativePid,int appPid) {
        for(Row r:rows)if(r.pid==nativePid||r.pid==appPid)
            check(!DROP.matcher(r.raw).find(),"本次打印来源的日志存在丢失或抑制，不能确认单次移动");
    }
    private static List<Row> packets(List<Row> rows) {
        List<Row> result=new ArrayList<>();for(Row r:rows)if(r.packet!=null)result.add(r);return result;
    }
    private static void validateTransaction(Transaction t,int command) {
        check(t!=null&&t.txHex!=null&&t.rxHex!=null&&t.txMs>0&&t.rxMs>=t.txMs&&t.rxMs-t.txMs<=1000,"SDK 响应超时或操作记录不完整");
        byte[] tx=unhex(t.txHex);frame(tx);PaperProtocol.requireWhitelistedRequest(tx);check((tx[4]&255)==command,"记录命令不匹配");
        byte[] rx=unhex(t.rxHex);PaperProtocol.successData(rx,tx[3]&255,command);
        if(command==0x30)PaperProtocol.parseStatus(rx,tx[3]&255).requireReady();
    }
    private static Row[] verify(List<Row> source,Transaction t,int pid,List<String> retained) {
        byte[] tx=unhex(t.txHex),rx=unhex(t.rxHex);Row sent=unique(source,tx,">",pid,0),received=unique(source,rx,"<",pid,0);
        check(sent.index<received.index&&sent.ms>=t.txMs&&sent.ms<=t.txMs+100&&received.ms>=t.rxMs-100&&received.ms<=t.rxMs,"原生发送／响应与 SDK 时间不匹配");
        List<Row> between=new ArrayList<>();
        int correlated=0;
        for(Row r:source){if(r.index>sent.index&&r.index<received.index)between.add(r);if(r.direction.equals("<")&&r.packet.length>4&&r.packet[3]==tx[3]&&r.packet[4]==tx[4])correlated++;}
        check(correlated==1,"匹配响应缺失或重复");
        check(between.size()==1&&between.get(0).direction.equals("<")&&between.get(0).packet.length==1&&between.get(0).packet[0]==6,"发送与响应之间缺少唯一同源 ACK06");
        retained.add(sent.raw);retained.add(between.get(0).raw);retained.add(received.raw);return new Row[]{sent,received};
    }
    private static Row unique(List<Row> rows,byte[] packet,String direction,int pid,int sp) {
        Row found=null;int count=0;for(Row r:rows)if(r.sp==sp&&(pid<0||r.pid==pid)&&direction.equals(r.direction)&&Arrays.equals(r.packet,packet)){found=r;count++;}
        check(count==1,"原生数据包缺失或重复");return found;
    }
    private static void frame(byte[] data) {
        check(data.length>=7&&data[0]==2&&data[data.length-2]==3,"原生帧边界错误");
        int length=((data[1]&255)<<8)|(data[2]&255);check(data.length==length+5,"原生帧长度错误");int xor=0;for(int i=1;i<data.length-1;i++)xor^=data[i]&255;
        check(xor==(data[data.length-1]&255),"原生帧校验和错误");
    }
    private static byte[] unhex(String text) {
        check(text!=null&&text.matches("(?:[0-9A-Fa-f]{2})+"),"十六进制数据不完整");byte[] bytes=new byte[text.length()/2];for(int i=0;i<bytes.length;i++)bytes[i]=(byte)Integer.parseInt(text.substring(i*2,i*2+2),16);return bytes;
    }
    private static void check(boolean ok,String reason){if(!ok)throw new IllegalStateException(reason);}
}
