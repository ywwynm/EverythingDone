$root = 'E:\projects\EverythingDone'
foreach ($variant in @('main','debug')) {
    $manifest = Get-Content -LiteralPath "$root\app\src\$variant\AndroidManifest.xml" -Raw
    $manifest = $manifest.Replace('android:name=".', 'android:name="com.ywwynm.everythingdone.')
    $manifest = $manifest.Replace('android:authorities="com.ywwynm.everythingdone"', 'android:authorities="com.ywwynm.everythingdone.covervalidation"')
    $manifest = $manifest.Replace('android:targetPackage="com.ywwynm.everythingdone"', 'android:targetPackage="com.ywwynm.everythingdone.covervalidation"')
    $manifest = $manifest.Replace('android:name="com.ywwynm.everythingdone.covergeneration.CoverRecoveryProbeActivity"' + "`r`n" + '            android:exported="false"', 'android:name="com.ywwynm.everythingdone.covergeneration.CoverRecoveryProbeActivity" android:exported="true"')
    $manifest = $manifest.Replace('android:name="com.ywwynm.everythingdone.covergeneration.CoverRecoveryProbeActivity"' + "`n" + '            android:exported="false"', 'android:name="com.ywwynm.everythingdone.covergeneration.CoverRecoveryProbeActivity" android:exported="true"')
    [IO.File]::WriteAllText("$root\tmp\cover-recovery\$variant-manifest.xml", $manifest)
}
