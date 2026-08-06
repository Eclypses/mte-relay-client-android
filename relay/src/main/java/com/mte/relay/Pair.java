// The MIT License (MIT)
//
// Copyright (c) Eclypses, Inc.
//
// All rights reserved.
//
// Permission is hereby granted, free of charge, to any person obtaining a copy
// of this software and associated documentation files (the "Software"), to deal
// in the Software without restriction, including without limitation the rights
// to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
// copies of the Software, and to permit persons to whom the Software is
// furnished to do so, subject to the following conditions:
//
// The above copyright notice and this permission notice shall be included in
// all copies or substantial portions of the Software.
//
// THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
// IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
// FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
// AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
// LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
// OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
// SOFTWARE.

package com.mte.relay;

import android.util.Base64;

import com.mte.relay.BuildConfig;
import com.eclypses.mte.MteKyber;
import com.eclypses.mte.MteBase;
import com.eclypses.mte.MteMkeDec;
import com.eclypses.mte.MteMkeEnc;
import com.eclypses.mte.MteStatus;

public class Pair {

    // Constructor for new Pair
    Pair() {
        MteKyber.init(MteKyber.KyberStrength.K1024);
        publicKeySize = MteKyber.getPublicKeySize();
        encryptedSize = MteKyber.getEncryptedSize();
        secretSize = MteKyber.getSecretSize();
        pairId = getRandomString(32);
        encPersStr = getRandomString(32);
        decPersStr = getRandomString(32);
        encMteKyber = new MteKyber();
        decMteKyber = new MteKyber();

        encMyPublicKey = new byte[publicKeySize];
        int kyberStatus = encMteKyber.createKeyPair(encMyPublicKey);
        checkKyberStatus(kyberStatus, "Encoder Kyber createKeyPair Error.");
        decMyPublicKey = new byte[publicKeySize];
        kyberStatus = decMteKyber.createKeyPair(decMyPublicKey);
        checkKyberStatus(kyberStatus, "Decoder Kyber createKeyPair Error.");
    }

    // Constructor for existing stored Pair
    Pair(String pairId, String encoderState, String decoderState) {
        this.pairId = pairId;
        this.encoder = new MteMkeEnc();
        this.encoderState = Base64.decode(encoderState, Base64.DEFAULT);
        this.decoder = new MteMkeDec(1000, -64);
        this.decoderState = Base64.decode(decoderState, Base64.DEFAULT);
    }

    final String pairId;
    String encPersStr;
    String decPersStr;
    int publicKeySize, encryptedSize, secretSize;
    long encNonce, decNonce;
    MteKyber encMteKyber;
    MteKyber decMteKyber;
    byte[] encMyPublicKey, encResponderEncryptedSecret, encSecret;
    byte[] decMyPublicKey, decResponderEncryptedSecret, decSecret;
    private MteMkeEnc encoder;
    private MteMkeDec decoder;
    private byte[] encoderState = new byte[32];
    private byte[] decoderState = new byte[32];

    public void createEncoderAndDecoder() throws MteException {
        instantiateEncoder();
        instantiateDecoder();
    }

    private void instantiateEncoder() throws MteException {
        encoder = new MteMkeEnc();
        encSecret = new byte[secretSize];
        int kyberStatus = encMteKyber.decryptSecret(encResponderEncryptedSecret, encSecret);
        checkKyberStatus(kyberStatus, "Kyber getSharedSecret Error");
        encoder.setEntropy(encSecret);
        encoder.setNonce(encNonce);
        MteStatus  status = encoder.instantiate(encPersStr);
        checkMteStatus(status, "Encoder Instantiate Error");
        saveEncoderState();
        if (BuildConfig.DEBUG) {
//             Log.i("MTE", "Encoder " + pairId + " Initial State: " + Base64.encodeToString(encoderState, Base64.DEFAULT)); // Used to compare with decoder state on server
        }
    }

    private void instantiateDecoder() throws MteException {
        decoder = new MteMkeDec(10, -63);
        decSecret = new byte[secretSize];
        int kyberStatus = decMteKyber.decryptSecret(decResponderEncryptedSecret, decSecret);
        checkKyberStatus(kyberStatus, "Kyber Decoder getSharedSecret Error");
        decoder.setEntropy(decSecret);
        decoder.setNonce(decNonce);
        MteStatus status = decoder.instantiate(decPersStr);
        checkMteStatus(status, "Decoder Instantiate Error");
        if (BuildConfig.DEBUG) {
//            Log.i("MTE", "Decoder " + pairId + " Initial State: " + Base64.encodeToString(decoderState, Base64.DEFAULT)); // Used to compare with encoder state on server
        }
        saveDecoderState();
    }

    public String encode(String message) throws MteException {
        restoreEncoderState();
        MteBase.StrStatus encodeResult = encoder.encodeB64(message);
        checkMteStatus(encodeResult.status, "Encode Error");
        saveEncoderState();
        return encodeResult.str;
    }

    public byte[] encode(byte[] bytes) throws MteException {
        restoreEncoderState();
        MteBase.ArrStatus encodeResult = encoder.encode(bytes);
        checkMteStatus(encodeResult.status, "Encode Error");
        saveEncoderState();
        return encodeResult.arr;
    }

    public int getFinishEncryptBytes() {
        return encoder.encryptFinishBytes();
    }

    public MteStatus startEncrypt() throws MteException {
        restoreEncoderState();
        MteStatus status = encoder.startEncrypt();
        checkMteStatus(status, "Start Encrypt Error");
        return status;
    }

    public MteStatus encryptChunk(byte[] bytes, int len) throws MteException {
        MteStatus status = encoder.encryptChunk(bytes, 0, len);
        checkMteStatus(status, "Encrypt Chunk Error");
        return status;
    }

    public MteBase.ArrStatus finishEncrypt() throws MteException {
        MteBase.ArrStatus encodeResult = encoder.finishEncrypt();
        checkMteStatus(encodeResult.status, "Finish Encrypt Error");
        saveEncoderState();
        return encodeResult;
    }

    public DecodeResult decode(String encoded) throws MteException {
        DecodeResult decodeResult = new DecodeResult();
        decodeResult.pairId = pairId;
        restoreDecoderState();
        MteBase.StrStatus result = decoder.decodeStrB64(encoded);
        checkMteStatus(result.status, "Decode Error");
        saveDecoderState();
        decodeResult.decodedStr = result.str;
        return decodeResult;
    }

    public DecodeResult decode(byte[] encoded)  throws MteException {
        DecodeResult decodeResult = new DecodeResult();
        decodeResult.pairId = pairId;
        restoreDecoderState();
        MteBase.ArrStatus result = decoder.decode(encoded);
        checkMteStatus(result.status, "Decode Error");
        saveDecoderState();
        decodeResult.decodedBytes = result.arr;
        return decodeResult;
    }

    public MteStatus startDecrypt() throws MteException {
        restoreDecoderState();
        MteStatus status = decoder.startDecrypt();
        checkMteStatus(status, "Start Decrypt Error");
        return status;
    }

    public byte[] decryptChunk(byte[] encoded) {
        return decoder.decryptChunk(encoded);
    }

    public int decryptChunk(byte[] encrypted, int encOff, int encLen, byte[] decrypted, int decOff) {
        return decoder.decryptChunk(encrypted, encOff, encLen, decrypted, decOff);
    }

    public MteBase.ArrStatus finishDecrypt() throws MteException {
        MteBase.ArrStatus result = decoder.finishDecrypt();
        checkMteStatus(result.status, "Finish Decrypt Error");
        saveDecoderState();
        return result;
    }


    public byte[] getEncoderState() {
        return encoderState;
    }

    public byte[] getDecoderState() {
        return decoderState;
    }

    private void restoreEncoderState() throws MteException {
        MteStatus status = encoder.restoreState(encoderState);
        checkMteStatus(status, "Encode Restore State Error");
    }

    private void saveEncoderState() throws MteException {
        encoderState = encoder.saveState();
        if (BuildConfig.DEBUG) {
//            Log.i("MTE", "Encoder " + pairId + " current state: " + Base64.encodeToString(encoderState, Base64.DEFAULT)); // Used to compare with current decoder state on server
        }
    }

    private void restoreDecoderState() throws MteException {
        MteStatus status = decoder.restoreState(decoderState);
        checkMteStatus(status, "Decode Restore State Error");
    }

    private void saveDecoderState() {
        decoderState = decoder.saveState();
        if (BuildConfig.DEBUG) {
//            Log.i("MTE", "Decoder " + pairId +  " current state: " + Base64.encodeToString(decoderState, Base64.DEFAULT)); // Used to compare with current encoder state on server
        }
    }

    private static void checkMteStatus(MteStatus status, String message) throws MteException {
        if (status != MteStatus.mte_status_success) {
            throw new MteException(status, message);
        }
    }

    private void checkKyberStatus(int status, String errorMessage) {
        if (status != MteKyber.Success) {
            throw new KyberException(status, errorMessage);
        }
    }


    private String getRandomString(Integer length) {
        String AlphaNumericString = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvxyz23456789";
        StringBuilder sb = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            int index
                    = (int) (AlphaNumericString.length()
                    * Math.random());
            sb.append(AlphaNumericString
                    .charAt(index));
        }
        return sb.toString();
    }

}
