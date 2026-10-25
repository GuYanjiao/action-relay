# Xiaomi wearable SDK

`xms-wearable-1.4.jar` is the unchanged `classes.jar` extracted from Xiaomi's official `xms-wearable-lib_1.4_release.aar` in its Interconnect development demo.

Official API page: https://iot.mi.com/vela/quickapp/zh/features/network/interconnect.html

Official demo archive: https://cdn.cnbj3-fusion.fds.api.mi-img.com/quickapp-vela/interconnect_dev_test_demo.zip

The AAR contains no resources or application components. Its manifest declares package visibility for `com.mi.health` and `com.xiaomi.wearable`; the app manifest must preserve these queries. The SDK binds to `com.xiaomi.wearable.XMS_WEARABLE_SERVICE` in one of those companion packages.

The app uses `NodeApi`, `AuthApi`, `MessageApi`, and `ServiceApi`. It requests only `Permission.DEVICE_MANAGER`, which Xiaomi documents as sufficient for inter-application messaging. `Permission.NOTIFY` is not requested because this app does not send system notifications to the watch through Xiaomi's SDK.

Watch and phone app package names and signing certificates must match. SDK 1.4's Java bytecode targets older Android APIs; successful compilation against API 36 does not establish runtime compatibility with the user's Android 17 or companion-app version.

The older integration PDF inside the demo archive references Color2 test firmware. Those firmware-upgrade instructions are not part of this app's installation procedure.
