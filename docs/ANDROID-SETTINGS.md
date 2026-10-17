# Android network and Bluetooth settings

From the Linux phone profile, choose **Android network settings** or **Android
Bluetooth settings**, or run `omamobile-android network-settings` or
`omamobile-android bluetooth-settings`. The app's private UID-authenticated
portal requests Android's standard `ACTION_WIRELESS_SETTINGS` or
`ACTION_BLUETOOTH_SETTINGS` activity on the phone's default display. Android
owns the settings UI and the radios; Linux does not provide NetworkManager or
BlueZ through these entries, and the commands do not toggle radios.

The portal accepts only the two fixed operations. An accepted reply means
Android accepted the launch request; it does not prove the activity became
visible. Missing activities and Android launch-policy failures return an error
to the Linux command. Freshly prepared payloads include both menu entries. On
an existing installation the authenticated APK update can refresh a known
`omamobile-android` helper, but it does not add missing desktop entries; run
`omamobile-android network-settings` or `omamobile-android bluetooth-settings`
directly there.
