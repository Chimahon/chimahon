package chimahon.novel.sync.ttu

object GoogleCloudOAuthConfiguration {
    const val ttuSetupUrl: String =
        "https://github.com/ttu-ttu/ebook-reader?tab=readme-ov-file#storage-sources"
    const val ttuSetupLinkLabel: String = "\u30C3\u30C4 Google Cloud setup"
    const val googleCloudConsoleUrl: String = "https://console.cloud.google.com/auth/clients"
    const val googleCloudConsoleLinkLabel: String = "Google Cloud Console"
    const val googleDeviceUrl: String = "https://www.google.com/device"
    const val googleDeviceLinkLabel: String = "Google device page"

    const val introduction: String =
        "Google Drive sync uses Device Code flow so this Android app can use the same user-owned Google Cloud project as iOS/\u30C3\u30C4."

    val instructions: List<String> = listOf(
        "Open the same Google Cloud project used by iOS/\u30C3\u30C4 sync and make sure the Google Drive API is enabled.",
        "Open Google Auth Platform -> Clients in the $googleCloudConsoleLinkLabel, click CREATE CLIENT, and select application type TVs and Limited Input devices. If your console still shows the older navigation, use APIs & Services -> Credentials -> Create Credentials -> OAuth client ID.",
        "Paste that client ID and client secret here. Do not create an Android OAuth client for this flow.",
        "Press Connect Google Drive, open the verification URL, and enter the displayed device code.",
        "If authorization has trouble while app is in the background, open the $googleDeviceLinkLabel on another device and enter the device code shown here.",
        "Authorize the same Google Account whose Drive contains the \u30C3\u30C4 sync folder.",
    )

    val instructionLinks: Map<String, String> = mapOf(
        googleCloudConsoleLinkLabel to googleCloudConsoleUrl,
        googleDeviceLinkLabel to googleDeviceUrl,
    )
}
