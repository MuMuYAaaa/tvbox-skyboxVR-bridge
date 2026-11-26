"""Upgrade an already patched v1 decoded tree to the header-preserving relay."""
from pathlib import Path
root = Path(__file__).resolve().parent / 'decoded'
def update(relative, before, after):
    p = root / relative
    s = p.read_text(encoding='utf-8')
    assert s.count(before) == 1, (relative, before)
    p.write_text(s.replace(before, after, 1), encoding='utf-8')

update('smali/androidx/base/d20.smali',
       'invoke-static {v1, v0, v2}, Lcom/github/tvbox/osc/skybox/SkyboxPlayer;->play(Landroid/app/Activity;Ljava/lang/String;Ljava/lang/String;)Ljava/lang/Boolean;',
       'invoke-static {v1, v0, v2, v4}, Lcom/github/tvbox/osc/skybox/SkyboxPlayer;->play(Landroid/app/Activity;Ljava/lang/String;Ljava/lang/String;Ljava/util/HashMap;)Ljava/lang/Boolean;')
update('smali/androidx/base/d10.smali',
       '    invoke-super {p0}, Landroidx/base/t2;->onResume()V',
       '''    invoke-super {p0}, Landroidx/base/t2;->onResume()V

    iget-object v0, p0, Landroidx/base/t2;->f:Landroid/app/Activity;
    invoke-static {v0}, Lcom/github/tvbox/osc/skybox/StreamProxyService;->onReturn(Landroid/app/Activity;)V''')
update('AndroidManifest.xml', '    </application>',
       '''        <service android:name="com.github.tvbox.osc.skybox.StreamProxyService" android:exported="false" android:foregroundServiceType="mediaPlayback"/>
        <activity android:name="com.github.tvbox.osc.skybox.StreamDiagnosticsActivity" android:exported="false" android:label="SKYBOX 播放诊断"/>
    </application>''')
update('apktool.yml', 'versionCode: 1', 'versionCode: 2')
update('apktool.yml', 'versionName: 20261111-1518-skybox1', 'versionName: 20261111-1518-skybox2')
print('v2: preserved header map, foreground relay service, diagnostics on return, update version.')
