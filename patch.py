"""Apply the narrow playback and parallel-install patch to a fresh apktool decode."""
from pathlib import Path
import hashlib

ROOT = Path(__file__).resolve().parent
decoded = ROOT / 'decoded'
source = ROOT / 'original.apk'
expected = 'c3aee40419fe9cef88dbac99aa7aff6e26503f8fb5e3dd1dd07e46e8e28b3c02'
assert hashlib.sha256(source.read_bytes()).hexdigest() == expected, 'Unexpected input APK'

def replace_once(text, before, after):
    assert text.count(before) == 1, f'Patch anchor is not unique: {before[:100]!r}'
    return text.replace(before, after, 1)

path = decoded / 'smali/androidx/base/d20.smali'
text = path.read_text(encoding='utf-8')
text = replace_once(text,
    '    sput-object v0, Landroidx/base/d20;->b:Ljava/util/HashMap;',
    '''    # SKYBOX is always selectable; launch reports missing players gracefully.
    const/16 v2, 0xf
    invoke-static {v2}, Ljava/lang/Integer;->valueOf(I)Ljava/lang/Integer;
    move-result-object v2
    sget-object v3, Ljava/lang/Boolean;->TRUE:Ljava/lang/Boolean;
    invoke-virtual {v0, v2, v3}, Ljava/util/HashMap;->put(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;

    sput-object v0, Landroidx/base/d20;->b:Ljava/util/HashMap;''')
text = replace_once(text,
    '.method public static c(I)Ljava/lang/String;\n    .locals 4\n',
    '''.method public static c(I)Ljava/lang/String;
    .locals 4

    const/16 v0, 0xf
    if-ne p0, v0, :skybox_name_end
    const-string v0, "SKYBOX（外部播放）"
    return-object v0
    :skybox_name_end
''')
text = replace_once(text,
    '    packed-switch p0, :pswitch_data_0\n',
    '''    const/16 v7, 0xf
    move/from16 v8, p0
    if-ne v8, v7, :skybox_dispatch_end
    invoke-static {v1, v0, v2}, Lcom/github/tvbox/osc/skybox/SkyboxPlayer;->play(Landroid/app/Activity;Ljava/lang/String;Ljava/lang/String;)Ljava/lang/Boolean;
    move-result-object v0
    return-object v0
    :skybox_dispatch_end
    const-string v7, "name"
    const-string v8, "subs"

    packed-switch p0, :pswitch_data_0
''')
path.write_text(text, encoding='utf-8')

path = decoded / 'AndroidManifest.xml'
text = path.read_text(encoding='utf-8')
assert text.count('com.github.tvbox.osc.jun') == 5
text = text.replace('com.github.tvbox.osc.jun', 'com.github.tvbox.osc.jun.skybox')
text = replace_once(text, '    <application ', '''    <queries>
        <intent>
            <action android:name="android.intent.action.VIEW"/>
            <data android:mimeType="video/mp4"/>
        </intent>
    </queries>
    <application ''')
path.write_text(text, encoding='utf-8')

path = decoded / 'res/values/strings.xml'
text = path.read_text(encoding='utf-8')
text = replace_once(text, '<string name="app_name">TVBox</string>',
                    '<string name="app_name">TVBox SKYBOX</string>')
path.write_text(text, encoding='utf-8')

path = decoded / 'apktool.yml'
text = path.read_text(encoding='utf-8')
text = replace_once(text, 'versionName: 20261111-1518', 'versionName: 20261111-1518-skybox1')
path.write_text(text, encoding='utf-8')
print('Patched player registry, dispatch, label, package and provider authorities.')
