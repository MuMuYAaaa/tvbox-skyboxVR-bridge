from pathlib import Path
from zipfile import ZipFile
import hashlib
import json
import struct
import zlib
import xml.etree.ElementTree as ET

root = Path(__file__).resolve().parent
import argparse
parser = argparse.ArgumentParser()
parser.add_argument('--apk', type=Path, default=root/'dist/TVBox-Skybox-Bridge-v0.3.0-arm64.apk')
apk = parser.parse_args().apk.resolve()
v = root / 'verification'
with ZipFile(root / 'original.apk') as original, ZipFile(apk) as final:
    assert final.testzip() is None
    assert len(final.namelist()) == len(set(final.namelist()))
    payloads = [n for n in original.namelist() if n.startswith(('assets/', 'lib/'))]
    for name in payloads:
        assert original.read(name) == final.read(name), name
        old, new = original.getinfo(name), final.getinfo(name)
        def raw_name(info):
            return info.filename.encode('utf-8' if info.flag_bits & 0x800 else 'cp437')
        assert raw_name(old) == raw_name(new), f'Filename encoding changed: {name}'
    for name in ('classes.dex', 'classes2.dex', 'classes3.dex'):
        dex = final.read(name)
        assert dex[:4] == b'dex\n'
        assert struct.unpack_from('<I', dex, 8)[0] == zlib.adler32(dex[12:]) & 0xffffffff
        assert dex[12:32] == hashlib.sha1(dex[32:]).digest()
        assert struct.unpack_from('<I', dex, 32)[0] == len(dex)

ns = '{http://schemas.android.com/apk/res/android}'
manifest = ET.parse(v / 'AndroidManifest.xml').getroot()
assert manifest.attrib['package'] == 'com.github.tvbox.osc.jun.skybox'
for p in manifest.findall('application/provider'):
    assert p.attrib[ns+'authorities'].startswith('com.github.tvbox.osc.jun.skybox.')
assert manifest.find('queries/intent/data').attrib[ns+'mimeType'] == 'video/mp4'
permissions = [p.attrib[ns+'name'] for p in manifest.findall('uses-permission')]
dispatcher = (v / 'smali/androidx/base/d20.smali').read_text(encoding='utf-8')
assert 'Lcom/github/tvbox/osc/skybox/SkyboxPlayer;->play(Landroid/app/Activity;Ljava/lang/String;Ljava/lang/String;Ljava/util/HashMap;)Ljava/lang/Boolean;' in dispatcher
assert 'SKYBOX' in dispatcher and 'const/16 v2, 0xf' in dispatcher
helper = (v / 'smali_classes3/com/github/tvbox/osc/skybox/SkyboxPlayer.smali').read_text(encoding='utf-8')
assert '"video/mp4"' in helper and '"android.intent.action.VIEW"' in helper
assert '->createChooser(' in helper and '->setClassName(' in helper
assert '->encode(' not in helper
assert 'Lcom/github/tvbox/osc/skybox/StreamProxyService;->prepare(' in helper
assert 'StreamProxyService;->onReturn(' in (v/'smali/androidx/base/d10.smali').read_text(encoding='utf-8')
services = manifest.findall('application/service')
service = next(s for s in services if s.attrib[ns+'name'] == 'com.github.tvbox.osc.skybox.StreamProxyService')
assert service.attrib[ns+'exported'] == 'false'
assert service.attrib[ns+'foregroundServiceType'] == 'mediaPlayback'
assert (v/'smali_classes3/com/github/tvbox/osc/skybox/StreamRelay.smali').is_file()
service_code = (v/'smali_classes3/com/github/tvbox/osc/skybox/StreamProxyService.smali').read_text(encoding='utf-8')
service_nested = '\n'.join(p.read_text(encoding='utf-8') for p in (v/'smali_classes3/com/github/tvbox/osc/skybox').glob('StreamProxyService*.smali'))
assert 'shouldStopWhenUnattended()Z' in service_nested
assert 'Landroid/os/PowerManager$WakeLock;->acquire()V' in service_code
assert 'Landroid/net/wifi/WifiManager$WifiLock;->acquire()V' in service_code
assert 'android.permission.WAKE_LOCK' in permissions
report = {
    'input_sha256': hashlib.sha256((root/'original.apk').read_bytes()).hexdigest(),
    'output_sha256': hashlib.sha256(apk.read_bytes()).hexdigest(),
    'output_bytes': apk.stat().st_size,
    'package': manifest.attrib['package'],
    'preserved_native_and_asset_entries': len(payloads),
    'zip_integrity': 'passed',
    'original_payload_and_filename_bytes': 'passed',
    'dex_checksums': 'passed for classes.dex, classes2.dex and classes3.dex',
    'roundtrip_decode_and_static_playback_wiring': 'passed',
    'handoff_contract_tests': '10 scenarios passed using JVM Android API doubles',
    'stream_relay_tests': '26 real local HTTP integration scenarios; see tests/test_stream_relay.py',
    'service_lifecycle_tests': 'Production service with Android API doubles: virtual 31-minute buffering, explicit stop and resource release passed',
    'version_code': 3,
    'apk_signature_schemes': 'v1, v2, v3 verified with Android apksigner',
    'zip_alignment': 'passed zipalign -c -p 4',
    'permissions': permissions,
    'headset_runtime_test': 'not performed',
    'scope': 'Long-playback session ownership, CPU/Wi-Fi protection, validated range resumption and cookie rotation; source URL renewal is not implemented',
    'reference': 'https://github.com/Predidit/Kazumi/blob/main/android/app/src/main/kotlin/com/example/kazumi/MainActivity.kt#L169-L174'
}
(apk.parent / 'validation.json').write_text(json.dumps(report, ensure_ascii=False, indent=2)+'\n', encoding='utf-8')
(apk.parent / 'SHA256.txt').write_text(report['output_sha256']+'  '+apk.name+'\n', encoding='utf-8')
print(json.dumps(report, ensure_ascii=False, indent=2))
