<h1 align="center">jBreakTV for Android TV</h1>
<h3 align="center">Based on a fork of <a href="https://github.com/jellyfin/jellyfin-androidtv">Jellyfin Android TV</a></h3>

---
<p style="text-align:center"><img width="300" src="https://github.com/BlackstormFirst/jBreakTV/blob/master/app/src/main/res/mipmap-hdpi/app_banner.png?raw=true" alt="jBreakTV" /></p>
<h4 align="center">Take a break ! Use jBreakTV !</h4>
<p>
jBreakTV is an alternative of the original Jellyfin client for Android TV, Nvidia Shield, and Amazon Fire TV devices.

As a fork, it retains core features and merging capabilities while introducing additional features and fixes.

Works with Jellyfin server versions 10.11.x and 12.x.

Recommendations (2026/09/25):
- Jellyfin Server 10.11.11
- Server Plugin: <a href="https://github.com/danieladov/jellyfin-plugin-mergeversions">Merge</a> (10.11.0.1)
- Server Plugin: <a href="https://github.com/Atilil/jellyfin-plugins">JellyTag</a> (the latest version or a fork that fixes the badge caching issues)

Here are some of the improvements:
- Added a feature that allows media playback from local or USB devices (can be disabled in the playback menu).
- Playback Index Manager (Added support for multiple video tracks in the player and improved fallback handling for audio and subtitles)
- Server-side audio and subtitle option management (the client now checks the user's audio and subtitles preferences on the server; all modes and options should now be supported for audio and subtitles, determining behavior when switching between episodes)
- Images Cache Management (optimizations and new options to manage the image loader's memory and disk cache; changes take effect only after a full restart of the application or the TV, try not to exceed 35% for the cache memory ^^)
- Library Filtering (Added filtering by Genre across libraries and by Type for movie libraries, as well as a workaround for audio filtering using Tags => The tag must be of the format: #language_French or #language_English ...)
- Navigation fluidity on the home page has also been improved.
- Option to change actor thumbnails to a circular shape in the customization menu.
- Download/Update Manager (for Debug builds only)
- Changes to several default user preferences during installation
- and lots of other fixes...


The application is currently provided in Debug mode; a properly signed version will soon be available for your devices.

Requests for new features or fixes can still be submitted, though you will likely be redirected to the original source; furthermore, this project focuses specifically on the stability and proper functioning of the video playback component, which is its core feature.

Note that some code modifications were developed with AI assistance to analyze the relevance and impact of the changes, though the final modifications remain the choice and decision of the developers.

Special thanks to the contributors of the Jellyfin, Jellyfin SDK Kotlin, and Jellyfin Android TV projects, whose work made the creation of this fork possible.

</p>

## Building

The app uses Gradle and requires the Android SDK. We recommend using Android Studio, which includes all required dependencies, for
development and building. For manual building without Android Studio make sure a compatible JDK and Android SDK are installed and in your
PATH, then use the Gradle wrapper (`./gradlew`) to build the project with the `assembleDebug` Gradle task to generate an apk file:

```shell
./gradlew assembleDebug
```

The task will create an APK file in the `/app/build/outputs/apk/debug` directory. This APK file uses a different app-id from our stable
builds and can be manually installed to your device.
