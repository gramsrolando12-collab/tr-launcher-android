package com.movtery.zalithlauncher.tr;

import java.security.GeneralSecurityException;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * scrypt (RFC 7914) con N=16384, r=8, p=1 y 32 bytes de salida: exactamente los parámetros por defecto de
 * crypto.scrypt de Node que usa el launcher de PC, para que la misma contraseña dé la misma clave en ambos.
 */
public final class TrScrypt {
    private TrScrypt() {}

    private static final int N = 16384, R = 8, P = 1, DK_LEN = 32;

    public static String deriveHex(String password, byte[] salt) {
        try {
            byte[] key = scrypt(password.getBytes("UTF-8"), salt);
            StringBuilder sb = new StringBuilder();
            for (byte b : key) sb.append(String.format("%02x", b & 0xff));
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    public static byte[] hexToBytes(String hex) {
        int n = hex.length() / 2;
        byte[] out = new byte[n];
        for (int i = 0; i < n; i++) out[i] = (byte) Integer.parseInt(hex.substring(i * 2, i * 2 + 2), 16);
        return out;
    }

    static byte[] scrypt(byte[] pw, byte[] salt) throws GeneralSecurityException {
        byte[] b = pbkdf2(pw, salt, 1, P * 128 * R);
        int[] xy = new int[64 * R];
        int[] v = new int[32 * R * N];
        for (int i = 0; i < P; i++) smix(b, i * 128 * R, v, xy);
        return pbkdf2(pw, b, 1, DK_LEN);
    }

    private static byte[] pbkdf2(byte[] pw, byte[] salt, int iterations, int dkLen) throws GeneralSecurityException {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(pw.length == 0 ? new byte[1] : pw, "HmacSHA256"));
        // Contraseñas vacías no se usan (el launcher exige mínimo de longitud), por eso basta este atajo.
        int hLen = 32;
        int blocks = (dkLen + hLen - 1) / hLen;
        byte[] out = new byte[dkLen];
        for (int i = 1; i <= blocks; i++) {
            mac.update(salt);
            mac.update(new byte[]{(byte) (i >>> 24), (byte) (i >>> 16), (byte) (i >>> 8), (byte) i});
            byte[] u = mac.doFinal();
            byte[] t = u.clone();
            for (int j = 1; j < iterations; j++) {
                u = mac.doFinal(u);
                for (int k = 0; k < hLen; k++) t[k] ^= u[k];
            }
            System.arraycopy(t, 0, out, (i - 1) * hLen, Math.min(hLen, dkLen - (i - 1) * hLen));
        }
        return out;
    }

    private static void smix(byte[] b, int bi, int[] v, int[] xy) {
        int words = 32 * R;
        int[] x = new int[words], y = new int[words];
        for (int i = 0; i < words; i++) {
            int o = bi + i * 4;
            x[i] = (b[o] & 0xff) | ((b[o + 1] & 0xff) << 8) | ((b[o + 2] & 0xff) << 16) | ((b[o + 3] & 0xff) << 24);
        }
        for (int i = 0; i < N; i++) {
            System.arraycopy(x, 0, v, i * words, words);
            blockMix(x, y);
            int[] t = x; x = y; y = t;
        }
        for (int i = 0; i < N; i++) {
            int j = x[(2 * R - 1) * 16] & (N - 1);
            for (int k = 0; k < words; k++) x[k] ^= v[j * words + k];
            blockMix(x, y);
            int[] t = x; x = y; y = t;
        }
        for (int i = 0; i < words; i++) {
            int o = bi + i * 4;
            b[o] = (byte) x[i]; b[o + 1] = (byte) (x[i] >>> 8); b[o + 2] = (byte) (x[i] >>> 16); b[o + 3] = (byte) (x[i] >>> 24);
        }
    }

    private static void blockMix(int[] in, int[] out) {
        int[] x = new int[16];
        System.arraycopy(in, (2 * R - 1) * 16, x, 0, 16);
        for (int i = 0; i < 2 * R; i++) {
            for (int k = 0; k < 16; k++) x[k] ^= in[i * 16 + k];
            salsa8(x);
            int dst = (i % 2 == 0 ? i / 2 : R + i / 2) * 16;
            System.arraycopy(x, 0, out, dst, 16);
        }
    }

    private static void salsa8(int[] b) {
        int[] x = b.clone();
        for (int i = 0; i < 8; i += 2) {
            x[4] ^= Integer.rotateLeft(x[0] + x[12], 7);  x[8] ^= Integer.rotateLeft(x[4] + x[0], 9);
            x[12] ^= Integer.rotateLeft(x[8] + x[4], 13); x[0] ^= Integer.rotateLeft(x[12] + x[8], 18);
            x[9] ^= Integer.rotateLeft(x[5] + x[1], 7);   x[13] ^= Integer.rotateLeft(x[9] + x[5], 9);
            x[1] ^= Integer.rotateLeft(x[13] + x[9], 13); x[5] ^= Integer.rotateLeft(x[1] + x[13], 18);
            x[14] ^= Integer.rotateLeft(x[10] + x[6], 7); x[2] ^= Integer.rotateLeft(x[14] + x[10], 9);
            x[6] ^= Integer.rotateLeft(x[2] + x[14], 13); x[10] ^= Integer.rotateLeft(x[6] + x[2], 18);
            x[3] ^= Integer.rotateLeft(x[15] + x[11], 7); x[7] ^= Integer.rotateLeft(x[3] + x[15], 9);
            x[11] ^= Integer.rotateLeft(x[7] + x[3], 13); x[15] ^= Integer.rotateLeft(x[11] + x[7], 18);
            x[1] ^= Integer.rotateLeft(x[0] + x[3], 7);   x[2] ^= Integer.rotateLeft(x[1] + x[0], 9);
            x[3] ^= Integer.rotateLeft(x[2] + x[1], 13);  x[0] ^= Integer.rotateLeft(x[3] + x[2], 18);
            x[6] ^= Integer.rotateLeft(x[5] + x[4], 7);   x[7] ^= Integer.rotateLeft(x[6] + x[5], 9);
            x[4] ^= Integer.rotateLeft(x[7] + x[6], 13);  x[5] ^= Integer.rotateLeft(x[4] + x[7], 18);
            x[11] ^= Integer.rotateLeft(x[10] + x[9], 7); x[8] ^= Integer.rotateLeft(x[11] + x[10], 9);
            x[9] ^= Integer.rotateLeft(x[8] + x[11], 13); x[10] ^= Integer.rotateLeft(x[9] + x[8], 18);
            x[12] ^= Integer.rotateLeft(x[15] + x[14], 7); x[13] ^= Integer.rotateLeft(x[12] + x[15], 9);
            x[14] ^= Integer.rotateLeft(x[13] + x[12], 13); x[15] ^= Integer.rotateLeft(x[14] + x[13], 18);
        }
        for (int i = 0; i < 16; i++) b[i] += x[i];
    }
}
