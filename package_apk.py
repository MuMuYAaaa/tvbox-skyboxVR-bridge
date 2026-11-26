from pathlib import Path
from zipfile import ZipFile, ZipInfo, ZIP_STORED

class OriginalZipInfo(ZipInfo):
    """Keep original APK filename bytes, including legacy-encoded asset names."""
    def __init__(self, info):
        super().__init__(info.filename, info.date_time)
        for attr in ZipInfo.__slots__:
            if hasattr(info, attr):
                setattr(self, attr, getattr(info, attr))
        self.original_name = info.filename.encode('utf-8' if info.flag_bits & 0x800 else 'cp437')
        self.original_utf8 = info.flag_bits & 0x800

    def _encodeFilenameFlags(self):
        return self.original_name, self.flag_bits | self.original_utf8

root = Path(__file__).resolve().parent
with ZipFile(root / 'build/tvbox-patched-base.apk') as src, ZipFile(root / 'original.apk') as original:
    with ZipFile(root / 'build/tvbox-unsigned.apk', 'w') as dst:
        for entry in src.infolist():
            if entry.filename.startswith(('lib/', 'assets/')):
                continue
            # Any signing metadata must be regenerated after modifying an APK.
            if entry.filename.upper().startswith('META-INF/') and entry.filename.upper().endswith(('.SF', '.RSA', '.DSA', '.EC', 'MANIFEST.MF')):
                continue
            dst.writestr(entry, src.read(entry.filename))
        for entry in original.infolist():
            if entry.filename.startswith(('lib/', 'assets/')):
                dst.writestr(OriginalZipInfo(entry), original.read(entry.filename))
        dst.write(root / 'build/dex/classes.dex', 'classes3.dex', compress_type=ZIP_STORED)
with ZipFile(root / 'original.apk') as original, ZipFile(root / 'build/tvbox-unsigned.apk') as built:
    names = [n for n in original.namelist() if n.startswith(('lib/', 'assets/')) and not n.endswith('/')]
    for name in names:
        assert original.read(name) == built.read(name), f'Unexpected payload change: {name}'
    assert len(built.namelist()) == len(set(built.namelist())), 'Duplicate ZIP entries'
    assert built.testzip() is None, 'ZIP integrity failure'
    print(f'Packaged classes3.dex; {len(names)} original native libraries/assets are byte-identical.')
