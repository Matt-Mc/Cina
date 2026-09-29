package dev.pocketmuse

import android.content.Context

data class YouProfile(
    val name: String = "",
    val pronouns: String = "",
    val about: String = "",
    val preferences: String = "",
    val petName: String = "Cina",
    val petColor: Int = 0,
    val petEyes: Int = 0,
    val petHat: Int = 0,
    val showPet: Boolean = true,
    val petCustomColor: String = "",
    val petMouth: Int = 0,
    val petAccessory: Int = 0,
    val petBlush: Boolean = true
)

internal fun normalizedPetColor(value: String): String = value.removePrefix("#")
    .uppercase(java.util.Locale.ROOT).takeIf { it.matches(Regex("[0-9A-F]{6}")) }.orEmpty()

class YouProfileStore(context: Context) {
    private val prefs = context.getSharedPreferences("you_profile", Context.MODE_PRIVATE)

    fun read() = YouProfile(
        name = prefs.getString("name", "").orEmpty(),
        pronouns = prefs.getString("pronouns", "").orEmpty(),
        about = prefs.getString("about", "").orEmpty(),
        preferences = prefs.getString("preferences", "").orEmpty(),
        petName = prefs.getString("pet_name", "Cina").orEmpty(),
        petColor = prefs.getInt("pet_color", 0).coerceIn(0, 5),
        petEyes = prefs.getInt("pet_eyes", 0).coerceIn(0, 5),
        petHat = prefs.getInt("pet_hat", 0).coerceIn(0, 6),
        showPet = prefs.getBoolean("show_pet", true),
        petCustomColor = normalizedPetColor(prefs.getString("pet_custom_color", "").orEmpty()),
        petMouth = prefs.getInt("pet_mouth", 0).coerceIn(0, 3),
        petAccessory = prefs.getInt("pet_accessory", 0).coerceIn(0, 2),
        petBlush = prefs.getBoolean("pet_blush", true)
    )

    fun save(profile: YouProfile) {
        prefs.edit()
            .putString("name", profile.name.trim().take(80))
            .putString("pronouns", profile.pronouns.trim().take(60))
            .putString("about", profile.about.trim().take(500))
            .putString("preferences", profile.preferences.trim().take(500))
            .putString("pet_name", profile.petName.trim().take(40))
            .putInt("pet_color", profile.petColor.coerceIn(0, 5))
            .putInt("pet_eyes", profile.petEyes.coerceIn(0, 5))
            .putInt("pet_hat", profile.petHat.coerceIn(0, 6))
            .putBoolean("show_pet", profile.showPet)
            .putString("pet_custom_color", normalizedPetColor(profile.petCustomColor))
            .putInt("pet_mouth", profile.petMouth.coerceIn(0, 3))
            .putInt("pet_accessory", profile.petAccessory.coerceIn(0, 2))
            .putBoolean("pet_blush", profile.petBlush)
            .apply()
    }
}
