package com.aiterminal.util;

import lombok.extern.slf4j.Slf4j;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;
import java.net.NetworkInterface;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.security.spec.KeySpec;
import java.util.Base64;
import java.util.Enumeration;

/**
 * Secure storage utility for encrypting sensitive data like API keys.
 * Uses AES-GCM encryption with a machine-specific derived key.
 */
@Slf4j
public class SecureStorage {

    private static final String ALGORITHM = "AES/GCM/NoPadding";
    private static final int GCM_IV_LENGTH = 12;
    private static final int GCM_TAG_LENGTH = 128;
    private static final int KEY_LENGTH = 256;
    private static final int ITERATION_COUNT = 65536;
    private static final String ENCRYPTED_PREFIX = "ENC:";
    private static final String SALT = "AITerminal_v1_Salt";

    private static SecureStorage instance;
    private final SecretKey secretKey;

    private SecureStorage() {
        this.secretKey = deriveKey();
    }

    public static synchronized SecureStorage getInstance() {
        if (instance == null) {
            instance = new SecureStorage();
        }
        return instance;
    }

    /**
     * Encrypt a plaintext string.
     * @param plaintext The string to encrypt
     * @return Encrypted string with "ENC:" prefix, or original if encryption fails
     */
    public String encrypt(String plaintext) {
        if (plaintext == null || plaintext.isEmpty()) {
            return plaintext;
        }
        
        // Already encrypted
        if (plaintext.startsWith(ENCRYPTED_PREFIX)) {
            return plaintext;
        }

        try {
            Cipher cipher = Cipher.getInstance(ALGORITHM);
            
            // Generate random IV
            byte[] iv = new byte[GCM_IV_LENGTH];
            new SecureRandom().nextBytes(iv);
            
            GCMParameterSpec parameterSpec = new GCMParameterSpec(GCM_TAG_LENGTH, iv);
            cipher.init(Cipher.ENCRYPT_MODE, secretKey, parameterSpec);
            
            byte[] cipherText = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            
            // Combine IV + ciphertext
            ByteBuffer byteBuffer = ByteBuffer.allocate(iv.length + cipherText.length);
            byteBuffer.put(iv);
            byteBuffer.put(cipherText);
            
            String encrypted = Base64.getEncoder().encodeToString(byteBuffer.array());
            return ENCRYPTED_PREFIX + encrypted;
            
        } catch (Exception e) {
            log.error("Encryption failed", e);
            return plaintext;
        }
    }

    /**
     * Decrypt an encrypted string.
     * @param encrypted The encrypted string (with "ENC:" prefix)
     * @return Decrypted plaintext, or original if not encrypted or decryption fails
     */
    public String decrypt(String encrypted) {
        if (encrypted == null || encrypted.isEmpty()) {
            return encrypted;
        }
        
        // Not encrypted
        if (!encrypted.startsWith(ENCRYPTED_PREFIX)) {
            return encrypted;
        }

        try {
            String base64Data = encrypted.substring(ENCRYPTED_PREFIX.length());
            byte[] decoded = Base64.getDecoder().decode(base64Data);
            
            // Extract IV and ciphertext
            ByteBuffer byteBuffer = ByteBuffer.wrap(decoded);
            byte[] iv = new byte[GCM_IV_LENGTH];
            byteBuffer.get(iv);
            byte[] cipherText = new byte[byteBuffer.remaining()];
            byteBuffer.get(cipherText);
            
            Cipher cipher = Cipher.getInstance(ALGORITHM);
            GCMParameterSpec parameterSpec = new GCMParameterSpec(GCM_TAG_LENGTH, iv);
            cipher.init(Cipher.DECRYPT_MODE, secretKey, parameterSpec);
            
            byte[] plainText = cipher.doFinal(cipherText);
            return new String(plainText, StandardCharsets.UTF_8);
            
        } catch (Exception e) {
            log.warn("Decryption failed: {}", e.getMessage());
            return encrypted;
        }
    }

    /**
     * Check if a string is encrypted.
     */
    public boolean isEncrypted(String value) {
        return value != null && value.startsWith(ENCRYPTED_PREFIX);
    }

    /**
     * Derive encryption key from machine-specific features.
     */
    private SecretKey deriveKey() {
        try {
            String machineId = getMachineIdentifier();
            
            SecretKeyFactory factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
            KeySpec spec = new PBEKeySpec(
                    machineId.toCharArray(),
                    SALT.getBytes(StandardCharsets.UTF_8),
                    ITERATION_COUNT,
                    KEY_LENGTH
            );
            
            SecretKey tmp = factory.generateSecret(spec);
            return new SecretKeySpec(tmp.getEncoded(), "AES");
            
        } catch (Exception e) {
            log.error("Failed to derive encryption key", e);
            // Fallback to a less secure but functional key
            return new SecretKeySpec(
                    (SALT + System.getProperty("user.name")).getBytes(StandardCharsets.UTF_8),
                    0, 16, "AES"
            );
        }
    }

    /**
     * Get a unique machine identifier based on hardware and user info.
     */
    private String getMachineIdentifier() {
        StringBuilder sb = new StringBuilder();
        
        // Add username
        sb.append(System.getProperty("user.name", "unknown"));
        sb.append(":");
        
        // Add MAC address
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces.hasMoreElements()) {
                NetworkInterface ni = interfaces.nextElement();
                byte[] mac = ni.getHardwareAddress();
                if (mac != null && mac.length > 0) {
                    for (byte b : mac) {
                        sb.append(String.format("%02X", b));
                    }
                    break; // Use first available MAC
                }
            }
        } catch (Exception e) {
            log.debug("Failed to get MAC address", e);
        }
        
        // Add OS info
        sb.append(":");
        sb.append(System.getProperty("os.name", "unknown"));
        
        // Add user home directory (unique per user on shared machines)
        sb.append(":");
        sb.append(System.getProperty("user.home", "unknown"));
        
        return sb.toString();
    }
}
