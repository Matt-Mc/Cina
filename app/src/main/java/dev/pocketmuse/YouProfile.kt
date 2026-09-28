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
    val showPet: Boolean = true
)

class YouProfileStore(context: Context) {
    private val prefs = context.getSharedPreferences("you_profile", Context.MODE_PRIVATE)

    fun read() = YouProfile(
        name = prefs.getString("name", "").orEmpty(),
        pronouns = prefs.getString("pronouns", "").orEmpty(),
        about = prefs.getString("about", "").orEmpty(),
        preferences = prefs.getString("preferences", "").orEmpty(),
        petName = prefs.getString("pet_name", "Cina").orEmpty(),
        petColor = prefs.getInt("pet_color", 0).coerceIn(0, 5),
        petEyes = prefs.getInt("pet_eyes", 0).coerceIn(0, 2),
        petHat = prefs.getInt("pet_hat", 0).coerceIn(0, 3),
        showPet = prefs.getBoolean("show_pet", true)
    )

    fun save(profile: YouProfile) {
        prefs.edit()
            .putString("name", profile.name.trim().take(80))
            .putString("pronouns", profile.pronouns.trim().take(60))
            .putString("about", profile.about.trim().take(500))
            .putString("preferences", profile.preferences.trim().take(500))
            .putString("pet_name", profile.petName.trim().take(40))
            .putInt("pet_color", profile.petColor.coerceIn(0, 5))
            .putInt("pet_eyes", profile.petEyes.coerceIn(0, 2))
            .putInt("pet_hat", profile.petHat.coerceIn(0, 3))
            .putBoolean("show_pet", profile.showPet)
            .apply()
    }
}
