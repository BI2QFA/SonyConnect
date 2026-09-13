package com.bi2qfa.sonyconnect.ptpip

import java.io.IOException

























class PairingClient(
    private val sendReq: (opCode: Int, txId: Int, blob: ByteArray) -> Unit,
    private val recvRsp: () -> PtpCodec.OpBlob,
) {

    






    class Result(val cameraDeviceId: ByteArray, val alreadyPaired: Boolean = false)

    
    class PairingFailedException(message: String) : IOException(message)

    





    class DeviceBusyException(message: String) : IOException(message)

    





    @Throws(IOException::class)
    fun run(code: String, deviceName: String): Result {
        val clean = code.trim()
        if (clean.length != CODE_LEN || !clean.all { it in '0'..'9' }) {
            throw PairingFailedException("配对码必须是 $CODE_LEN 位数字")
        }

        
        sendReq(PtpCodec.OP_PAIR_BEGIN, TX_BEGIN, deviceName.toByteArray(Charsets.UTF_8))
        val r1 = recvRsp()
        if (r1.code == PtpCodec.RC_PAIRING_FAILED) {
            throw PairingFailedException("相机未开启配对模式")
        }
        if (r1.code == PtpCodec.RC_DEVICE_BUSY) {
            
            
            throw DeviceBusyException("相机正被其他设备占用")
        }
        if (r1.code == PtpCodec.RC_NOT_SUPPORTED) {
            
            
            
            
            
            
            return Result(ByteArray(0), alreadyPaired = true)
        }
        if (r1.code != PtpCodec.RC_OK) {
            throw PairingFailedException("配对被拒绝（0x${Integer.toHexString(r1.code)}）")
        }
        if (r1.blob.size < DEVICE_ID_LEN) {
            throw PairingFailedException("PAIR_BEGIN 应答过短: ${r1.blob.size}")
        }
        val devIdC = r1.blob.copyOfRange(0, DEVICE_ID_LEN)

        
        sendReq(PtpCodec.OP_PAIR_EXCHANGE, TX_EXCHANGE, clean.toByteArray(Charsets.US_ASCII))
        val r2 = recvRsp()
        if (r2.code != PtpCodec.RC_OK) {
            throw PairingFailedException("配对码不正确或已失效")
        }
        return Result(devIdC)
    }

    
    fun abort() {
        runCatching { sendReq(PtpCodec.OP_PAIR_ABORT, TX_ABORT, ByteArray(0)) }
    }

    companion object {
        const val CODE_LEN = 6
        const val DEVICE_ID_LEN = 8

        
        const val TX_BEGIN = 0x41
        const val TX_EXCHANGE = 0x42
        const val TX_ABORT = 0x44
    }
}
