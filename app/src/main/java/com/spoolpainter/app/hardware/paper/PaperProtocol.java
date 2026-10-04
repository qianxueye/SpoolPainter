package com.spoolpainter.app.hardware.paper;

import java.util.Arrays;

/** Closed packet vocabulary for this reviewed diagnostic; no arbitrary command constructor. */
public final class PaperProtocol {
    private PaperProtocol() {}
    public static final int RETRACT_UNITS = -80;
    private static final byte[] BACKWARD = {(byte) 0x45, 4, (byte) 0xff, (byte) 0xff, (byte) 0xff, (byte) 0xb0};
    private static final byte[] STATUS_PREFIX = {1, 1, (byte) 0x80, (byte) 0xff, (byte) 0xff, 1, 5, 'L', 'T', 'P', '0', '2', 2, 1};
    public static final String EXPECTED_VERSION = "SP_V1.01.007 YC02FF00 241121";

    public static byte[] statusRequest(int sequence) { return frame(sequence, 0x30, new byte[0]); }
    public static byte[] retractRequest(int sequence) { return frame(sequence, 0x33, BACKWARD); }

    public static void requireWhitelistedRequest(byte[] request) {
        require(request != null && request.length >= 7, "请求格式无效");
        int sequence = request[3] & 255;
        require(Arrays.equals(request, statusRequest(sequence)) || Arrays.equals(request, retractRequest(sequence)), "仅允许普通状态请求与固定 -80 单位回抽");
    }

    private static byte[] frame(int sequence, int command, byte[] payload) {
        require(sequence >= 1 && sequence <= 254, "序列号超出诊断范围");
        int length = payload.length + 2;
        byte[] out = new byte[length + 5];
        out[0] = 2; out[1] = (byte) (length >>> 8); out[2] = (byte) length;
        out[3] = (byte) sequence; out[4] = (byte) command;
        System.arraycopy(payload, 0, out, 5, payload.length);
        out[length + 3] = 3;
        out[length + 4] = xor(out, 1, length + 4);
        return out;
    }

    public static byte[] successData(byte[] response, int sequence, int command) {
        require(command == 0x30 || command == 0x33, "不支持此响应命令");
        require(response != null && response.length >= 9, "缺少完整响应；ACK 不代表成功");
        require((response[0] & 255) == 2, "响应 STX 错误");
        int length = ((response[1] & 255) << 8) | (response[2] & 255);
        require(length >= 4 && response.length == length + 5, "响应长度不匹配");
        require((response[3] & 255) == sequence, "响应序列号不匹配");
        require((response[4] & 255) == command, "响应命令不匹配");
        require((response[length + 3] & 255) == 3, "响应 ETX 错误");
        require(response[length + 4] == xor(response, 1, length + 4), "响应 LRC 校验失败");
        int firmwareStatus = ((response[5] & 255) << 8) | (response[6] & 255);
        require(firmwareStatus == 0, "固件返回错误状态：" + firmwareStatus);
        byte[] data = Arrays.copyOfRange(response, 7, length + 3);
        if (command == 0x33) require(data.length == 0, "移动响应包含未预期数据");
        return data;
    }

    public static Status parseStatus(byte[] response, int sequence) {
        byte[] data = successData(response, sequence, 0x30);
        require(data.length == 18, "打印状态数据必须恰为 18 字节");
        for (int i = 0; i < STATUS_PREFIX.length; i++) require(data[i] == STATUS_PREFIX[i], "打印机描述或状态 TLV 不匹配");
        require(data[15] == 3 && data[16] == 1, "纸张状态 TLV 不匹配");
        int temperature = data[14]; // Signed byte, as confirmed by the SDK parser.
        int paper = data[17] & 255;
        require(paper == 0 || paper == 1, "纸张状态值无效");
        return new Status(temperature, paper == 1);
    }

    public static void requireVersion(String version) {
        require(version != null, "无法读取 SP 固件版本");
        String value = version.trim();
        require(value.equals(EXPECTED_VERSION) || value.equals(EXPECTED_VERSION + " PEDSTA=4"), "SP 固件版本与已审查版本不匹配");
    }

    public static final class Status {
        public final int temperature;
        public final boolean paperPresent;
        private Status(int temperature, boolean paperPresent) { this.temperature = temperature; this.paperPresent = paperPresent; }
        public void requireReady() {
            require(paperPresent, "未检测到纸张");
            require(temperature >= 0 && temperature <= 50, "温度不在本次测试允许的 0–50 范围");
        }
    }

    private static byte xor(byte[] bytes, int first, int end) {
        int value = 0;
        for (int i = first; i < end; i++) value ^= bytes[i] & 255;
        return (byte) value;
    }
    private static void require(boolean value, String message) { if (!value) throw new IllegalArgumentException(message); }
}
