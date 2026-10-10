package dev.mateuszsury.androidagent;

/** Test APK owns the synthetic provider and supplies real Android URI grants. */
public class UsbFixtureGrantActivity extends android.app.Activity {
    @Override public void onCreate(android.os.Bundle state){super.onCreate(state);for(String root:new String[]{"rw","ro","unknown","offline"})grantUriPermission("dev.mateuszsury.androidagent",android.provider.DocumentsContract.buildTreeDocumentUri(UsbFixtureProvider.AUTH,root),android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION|android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION|android.content.Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION|android.content.Intent.FLAG_GRANT_PREFIX_URI_PERMISSION);finish();}
}
