package dev.live.translator.mobile.model

enum class ThemeMode {
    DARK, AMOLED;

    companion object {
        fun fromName(name: String?): ThemeMode = entries.firstOrNull { it.name == name } ?: DARK
    }
}

enum class AccentPreset(val label: String, val argb: Int) {
    LIME("Лайм", 0xFFC5E89D.toInt()),
    MINT("М’ята", 0xFF9DE8C9.toInt()),
    SKY("Блакить", 0xFF9DCBE8.toInt()),
    LAVENDER("Лаванда", 0xFFA7B4F5.toInt()),
    LILAC("Бузок", 0xFFC9A7F5.toInt()),
    PINK("Рожевий", 0xFFF0A7D8.toInt()),
    CORAL("Корал", 0xFFF5A7A7.toInt()),
    APRICOT("Абрикос", 0xFFF5C58F.toInt()),
    YELLOW("Жовтий", 0xFFEDE08A.toInt());

    companion object {
        val DEFAULT = LIME
        fun fromName(name: String?): AccentPreset = entries.firstOrNull { it.name == name } ?: DEFAULT
    }
}
