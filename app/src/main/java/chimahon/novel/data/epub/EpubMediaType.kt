package chimahon.novel.data.epub

enum class EpubMediaType(val value: String) {
    GIF("image/gif"),
    JPEG("image/jpeg"),
    PNG("image/png"),
    SVG("image/svg+xml"),
    XHTML("application/xhtml+xml"),
    OPF2("application/x-dtbncx+xml"),
    JAVASCRIPT("application/javascript"),
    OPENTYPE("application/font-sfnt"),
    WOFF("application/font-woff"),
    WOFF2("font/woff2"),
    MEDIA_OVERLAYS("application/smil+xml"),
    PLS("application/pls+xml"),
    MP3("audio/mpeg"),
    MP4("audio/mp4"),
    CSS("text/css"),
    UNKNOWN("application/octet-stream"),
    ;

    companion object {
        fun fromString(value: String?): EpubMediaType {
            if (value == null) return UNKNOWN
            return entries.find { it.value.equals(value, ignoreCase = true) } ?: UNKNOWN
        }
    }
}
