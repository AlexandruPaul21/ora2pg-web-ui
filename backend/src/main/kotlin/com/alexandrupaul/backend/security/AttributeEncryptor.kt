package com.alexandrupaul.backend.security

import jakarta.persistence.AttributeConverter
import jakarta.persistence.Converter
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

@Component
@Converter
class AttributeEncryptor : AttributeConverter<String, String> {

    @Value("\${app.security.secret-key}")
    private lateinit var secretKey: String

    private val algorithm = "AES"

    override fun convertToDatabaseColumn(attribute: String?): String? {
        if (attribute == null) return null
        try {
            val key = SecretKeySpec(secretKey.toByteArray(), algorithm)
            val cipher = Cipher.getInstance(algorithm)
            cipher.init(Cipher.ENCRYPT_MODE, key)
            val encryptedBytes = cipher.doFinal(attribute.toByteArray())
            return Base64.getEncoder().encodeToString(encryptedBytes)
        } catch (e: Exception) {
            throw RuntimeException("Error encrypting data", e)
        }
    }

    override fun convertToEntityAttribute(dbData: String?): String? {
        if (dbData == null) return null
        try {
            val key = SecretKeySpec(secretKey.toByteArray(), algorithm)
            val cipher = Cipher.getInstance(algorithm)
            cipher.init(Cipher.DECRYPT_MODE, key)
            val decodedBytes = Base64.getDecoder().decode(dbData)
            return String(cipher.doFinal(decodedBytes))
        } catch (e: Exception) {
            throw RuntimeException("Error decrypting data", e)
        }
    }
}
