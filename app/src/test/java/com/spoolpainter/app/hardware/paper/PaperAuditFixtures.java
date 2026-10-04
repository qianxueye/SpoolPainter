package com.spoolpainter.app.hardware.paper;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Synthetic offline rows only; no logcat process or device command is started. */
public final class PaperAuditFixtures {
    private static int checks;
    private static final String MARKER="testmarker";
    private static final int APP=222,NATIVE=1006;
    private static final long T=1000000;
    private static NativeLogAudit.Transaction pre,feed,post;
    public static void main(String[] args) {
        {
            List<String> lines=fixture();
            NativeLogAudit.Result proof=NativeLogAudit.audit(lines,MARKER,APP,pre,feed,post);
            yes(proof.nativePid==NATIVE&&proof.matchedPrinterRows.size()==9,"valid three exchanges only");
            List<String> before=new ArrayList<>(lines.subList(0,5));
            yes(NativeLogAudit.auditPre(before,MARKER,APP,pre).matchedPrinterRows.size()==3,"pre-proof independently valid before feed");
            List<String> missing=new ArrayList<>(before);missing.remove(2);reject(()->NativeLogAudit.auditPre(missing,MARKER,APP,pre));
            List<String> wrongAck=new ArrayList<>(lines);wrongAck.set(6,row(T+105,999,"POS_AT","[SP_0] AT< 06"));reject(()->NativeLogAudit.audit(wrongAck,MARKER,APP,pre,feed,post));
            List<String> duplicate=new ArrayList<>(lines);duplicate.add(6,row(T+104,NATIVE,"POS_AT","[SP_0] AT> "+feed.txHex));reject(()->NativeLogAudit.audit(duplicate,MARKER,APP,pre,feed,post));
            List<String> noFeedAck=new ArrayList<>(lines);noFeedAck.remove(6);reject(()->NativeLogAudit.audit(noFeedAck,MARKER,APP,pre,feed,post));
            List<String> otherPrint=new ArrayList<>(lines);otherPrint.add(5,row(T+90,NATIVE,"POS_AT","[SP_0] AT> "+hex(frame(8,0x32,new byte[0]))));reject(()->NativeLogAudit.audit(otherPrint,MARKER,APP,pre,feed,post));
            List<String> badEnd=new ArrayList<>(lines);badEnd.remove(badEnd.size()-1);reject(()->NativeLogAudit.audit(badEnd,MARKER,APP,pre,feed,post));
            List<String> retry=new ArrayList<>(lines);retry.add(8,row(T+150,NATIVE,"POS_CONTROLLER","CMD{seq=2} TIMEDOUT, ignore it"));reject(()->NativeLogAudit.audit(retry,MARKER,APP,pre,feed,post));
            List<String> dropped=new ArrayList<>(lines);dropped.add(8,row(T+150,999,"logd","dropped 4 lines"));reject(()->NativeLogAudit.audit(dropped,MARKER,APP,pre,feed,post));
            List<String> suppressed=new ArrayList<>(lines);suppressed.add(8,row(T+150,NATIVE,"chatty","uid=1000 identical 5 lines"));reject(()->NativeLogAudit.audit(suppressed,MARKER,APP,pre,feed,post));
            List<String> unrelatedSuppression=new ArrayList<>(lines);unrelatedSuppression.add(8,row(T+150,1008,"chatty","uid=1000(system) FinalizerDaemon identical 206 lines"));
            yes(NativeLogAudit.audit(unrelatedSuppression,MARKER,APP,pre,feed,post).matchedPrinterRows.size()==9,"unrelated FinalizerDaemon suppression is not printer evidence loss");
            List<String> appSuppression=new ArrayList<>(lines);appSuppression.add(8,row(T+150,APP,"chatty","identical 3 lines"));reject(()->NativeLogAudit.audit(appSuppression,MARKER,APP,pre,feed,post));
            List<String> unrelated=new ArrayList<>(lines);unrelated.add(1,row(T-5,999,"POS_AT","[SP_1] AT> "+hex(frame(7,0x11,new byte[]{1,2,3}))));
            NativeLogAudit.Result clean=NativeLogAudit.audit(unrelated,MARKER,APP,pre,feed,post);
            yes(clean.matchedPrinterRows.size()==9&&!clean.matchedPrinterRows.toString().contains("SP_1"),"unrelated raw data never returned for persistence");
        }
        System.out.println("PaperAuditFixtures: "+checks+" checks passed; no device operations");
    }
    private static List<String> fixture(){
        pre=new NativeLogAudit.Transaction(hex(PaperProtocol.statusRequest(1)),hex(response(1,0x30,status())),T,T+20);
        feed=new NativeLogAudit.Transaction(hex(PaperProtocol.retractRequest(2)),hex(response(2,0x33,new byte[0])),T+100,T+125);
        post=new NativeLogAudit.Transaction(hex(PaperProtocol.statusRequest(3)),hex(response(3,0x30,status())),T+200,T+225);
        return new ArrayList<>(Arrays.asList(row(T-10,APP,"SpoolPaperMotion","BEGIN "+MARKER),
            row(T+3,NATIVE,"POS_AT","[SP_0] AT> "+pre.txHex),row(T+4,NATIVE,"POS_AT","[SP_0] AT< 06"),row(T+15,NATIVE,"POS_AT","[SP_0] AT< "+pre.rxHex),
            row(T+30,APP,"SpoolPaperMotion","CHECK "+MARKER),row(T+103,NATIVE,"POS_AT","[SP_0] AT> "+feed.txHex),row(T+105,NATIVE,"POS_AT","[SP_0] AT< 06"),row(T+120,NATIVE,"POS_AT","[SP_0] AT< "+feed.rxHex),
            row(T+203,NATIVE,"POS_AT","[SP_0] AT> "+post.txHex),row(T+205,NATIVE,"POS_AT","[SP_0] AT< 06"),row(T+220,NATIVE,"POS_AT","[SP_0] AT< "+post.rxHex),row(T+240,APP,"SpoolPaperMotion","END "+MARKER)));
    }
    private static String row(long ms,int pid,String tag,String message){return String.format(java.util.Locale.ROOT,"%.3f %d 7917 I %s: %s",ms/1000.0,pid,tag,message);}
    private static byte[] status(){return new byte[]{1,1,(byte)0x80,(byte)0xff,(byte)0xff,1,5,'L','T','P','0','2',2,1,25,3,1,1};}
    private static byte[] response(int seq,int cmd,byte[] data){byte[] payload=new byte[data.length+2];System.arraycopy(data,0,payload,2,data.length);return frame(seq,cmd,payload);}
    private static byte[] frame(int seq,int cmd,byte[] payload){int n=payload.length+2;byte[] b=new byte[n+5];b[0]=2;b[1]=(byte)(n>>>8);b[2]=(byte)n;b[3]=(byte)seq;b[4]=(byte)cmd;System.arraycopy(payload,0,b,5,payload.length);b[n+3]=3;int xor=0;for(int i=1;i<b.length-1;i++)xor^=b[i]&255;b[b.length-1]=(byte)xor;return b;}
    private static String hex(byte[] bytes){StringBuilder s=new StringBuilder();for(byte b:bytes)s.append(String.format("%02X",b&255));return s.toString();}
    private static void yes(boolean value,String why){if(!value)throw new AssertionError(why);checks++;}
    private static void reject(Runnable action){try{action.run();}catch(IllegalStateException|IllegalArgumentException expected){checks++;return;}throw new AssertionError("expected rejection");}
}
