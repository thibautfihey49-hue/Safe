package com.mysafe.mysafe

import android.content.Context
import android.content.SharedPreferences
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
data class Appareil(
    val id: String,
    val nom: String,
    val estMaitre: Boolean,
    val cleSecrete: String
)

object AppairageManager {
    private const val PREFS_NAME = "mysafe_appairage"
    private const val KEY_MON_APPAREIL = "mon_appareil"
    private const val KEY_PARTENAIRE = "partenaire_appareil"
    private const val KEY_APPaire = "est_appaire"

    private val json = Json { ignoreUnknownKeys = true }

    fun getPrefs(context: Context): SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    fun genererID(): String = (1..6).map { "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789".random() }.joinToString("")
    fun genererCleSecrete(): String = (1..16).map { "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789".random() }.joinToString("")

    fun creerMonAppareil(context: Context, nom: String, estMaitre: Boolean): Appareil {
        val appareil = Appareil(id = genererID(), nom = nom, estMaitre = estMaitre, cleSecrete = genererCleSecrete())
        getPrefs(context).edit().putString(KEY_MON_APPAREIL, json.encodeToString(appareil)).apply()
        return appareil
    }

    fun getMonAppareil(context: Context): Appareil? {
        val str = getPrefs(context).getString(KEY_MON_APPAREIL, null) ?: return null
        return try { json.decodeFromString<Appareil>(str) } catch (e: Exception) { null }
    }

    fun sauvegarderPartenaire(context: Context, partenaire: Appareil) {
        getPrefs(context).edit().putString(KEY_PARTENAIRE, json.encodeToString(partenaire)).putBoolean(KEY_APPaire, true).apply()
    }

    fun getPartenaire(context: Context): Appareil? {
        val str = getPrefs(context).getString(KEY_PARTENAIRE, null) ?: return null
        return try { json.decodeFromString<Appareil>(str) } catch (e: Exception) { null }
    }

    fun estAppaire(context: Context): Boolean = getPrefs(context).getBoolean(KEY_APPaire, false)
    fun supprimerAppairage(context: Context) = getPrefs(context).edit().clear().apply()
    fun genererCodeAssoc(monAppareil: Appareil): String = "${monAppareil.id}:${monAppareil.cleSecrete}:${monAppareil.nom}"
    
    fun importerCodeAssoc(context: Context, code: String): Boolean {
        val parts = code.split(":")
        if (parts.size != 3) return false
        sauvegarderPartenaire(context, Appareil(id = parts[0], nom = parts[2], estMaitre = true, cleSecrete = parts[1]))
        return true
    }
}
